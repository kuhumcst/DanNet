(ns dk.cst.dannet.db.bootstrap
  "Represent DanNet as an in-memory graph or within a persisted database (TDB).

  Inverse relations are not explicitly created, but rather handled by way of
  inference using a Jena OWL reasoner."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [arachne.aristotle :as aristotle]
            [clj-file-zip.core :as zip]
            [taoensso.telemere :as t]
            [dk.cst.dannet.db.query :as q]
            [dk.cst.dannet.db.query.operation :as op]
            [dk.cst.dannet.db.transaction :as txn]
            [dk.cst.dannet.db :as db]
            [dk.cst.dannet.db.bootstrap.cor :as cor]
            [dk.cst.dannet.db.bootstrap.downloads :as downloads]
            [dk.cst.dannet.db.bootstrap.metadata :as md]
            [dk.cst.dannet.hash :as h]
            [dk.cst.dannet.release :as release]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.prefix :as prefix])
  (:import [java.io File]
           [java.time LocalDateTime]
           [java.time.format DateTimeFormatter]
           [org.apache.jena.query Dataset DatasetFactory]
           [org.apache.jena.rdf.model Model ModelFactory]
           [org.apache.jena.reasoner.rulesys GenericRuleReasoner Rule]
           [org.apache.jena.tdb TDBFactory]
           [org.apache.jena.tdb2 TDB2Factory]
           [org.apache.jena.util ResourceUtils]))

(defn assert-expected-dannet-release!
  "Assert that the DanNet `model` is the expected release to bootstrap from.

  On mismatch, reports the version actually found so it's diagnosable: the
  dataset-level <dn> owl:versionInfo is authoritative, but older releases carry
  no such triple, so we fall back to a sample of any versionInfo values present."
  [model]
  (let [graph    (.getGraph ^Model model)
        expected (q/run graph [:bgp [md/<dn> :owl/versionInfo release/from]])]
    (when (empty? expected)
      (let [dn-vers (->> (q/run graph [:bgp [md/<dn> :owl/versionInfo '?v]])
                         (map #(str (get % '?v)))
                         (distinct)
                         (vec))
            actual  (if (seq dn-vers)
                      dn-vers
                      (->> (q/run graph '[:bgp [?s :owl/versionInfo ?v]])
                           (map #(str (get % '?v)))
                           (distinct)
                           (take 5)
                           (vec)))]
        (t/log! {:level :error
                 :id    :dannet.bootstrap/unexpected-release
                 :data  {:expected release/from
                         :actual   actual}}
                "Bootstrap files are not the expected release")
        (throw (ex-info (str "bootstrap files not the expected release. Expected "
                             (pr-str release/from) ", found "
                             (if (seq actual) (pr-str actual) "no owl:versionInfo")
                             ". Restart with refetch (--refetch, or restart-refetch "
                             "in the REPL) to download the expected release.")
                        {:expected release/from
                         :actual   actual}))))))

(h/defn add-open-english-wordnet-labels!
  "Generate appropriate labels for the (otherwise unlabeled) OEWN in `dataset`."
  [dataset]
  (t/trace! {:id :dannet.bootstrap/oewn-labels :run-val :elided}
    (let [oewn-graph   (db/get-graph dataset prefix/oewn-uri)
          label-graph  (db/get-graph dataset prefix/oewn-extension-uri)
          ms           (q/run oewn-graph op/oewn-label-targets)
          collect-rep  (fn [m {:syms [?synset ?rep]}]
                         (update m ?synset conj (str ?rep)))
          synset-label (fn [labels]
                         (as-> labels $
                               (set $)
                               (sort $)
                               (str/join "; " $)
                               (md/en "{" $ "}")))]
      (txn/transact-exec dataset
        (t/log! {:level :debug
                 :id    :dannet.bootstrap/oewn-synset-labels
                 :data  {:graph (str prefix/oewn-extension-uri)}}
                "Adding OEWN synset labels")
        (->> (reduce collect-rep {} ms)
             (map (fn [[synset labels]]
                    [synset :rdfs/label (synset-label labels)]))
             (aristotle/add label-graph)))
      (txn/transact-exec dataset
        (t/log! {:level :debug
                 :id    :dannet.bootstrap/oewn-word-labels
                 :data  {:graph (str prefix/oewn-extension-uri)}}
                "Adding OEWN sense and word labels")
        (->> ms
             (mapcat (fn [{:syms [?sense ?word ?rep]}]
                       [[?word :rdfs/label (md/en "\"" ?rep "\"")]
                        [?sense :rdfs/label ?rep]]))
             (aristotle/add label-graph)))
      ;; Carry the CC BY 4.0 licence in the RDF itself, not just the export zip
      ;; (issue #96): the OEWN label extension is our derivative of the Open
      ;; English Wordnet, published under the same CC BY 4.0 licence.
      (txn/transact-exec dataset
        (t/log! {:level :debug
                 :id    :dannet.bootstrap/oewn-license
                 :data  {:graph (str prefix/oewn-extension-uri)}}
                "Adding OEWN extension licence metadata")
        (let [oewn-ext (prefix/uri->rdf-resource prefix/oewn-extension-uri)]
          (aristotle/add
            label-graph
            [[oewn-ext :dc/license "<https://creativecommons.org/licenses/by/4.0/>"]
             ["<https://creativecommons.org/licenses/by/4.0/>" :rdfs/label "CC BY 4.0"]
             [oewn-ext :dc/rights (md/en "DanNet-style labels for the Open English Wordnet; "
                                         "© the Open English Wordnet contributors, "
                                         "licensed under CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/).")]]))))))

;; TODO: move to separate ns
(h/defn add-open-english-wordnet!
  "Add the Open English WordNet to a Jena `dataset`."
  [dataset]
  (t/trace! {:id :dannet.bootstrap/import-oewn :run-val :elided}
    (let [oewn-changefn (fn [temp-model]
                          (t/log! {:level :debug :id :dannet.bootstrap/oewn-clean}
                                  "Removing problematic OEWN entries")
                          (db/remove! temp-model [prefix/oewn-uri :lime/entry '_]))]
      (db/import-files dataset prefix/oewn-uri [downloads/oewn-ttl-path] oewn-changefn)
      (db/import-files dataset prefix/ili-uri [downloads/ili-path])))
  (add-open-english-wordnet-labels! dataset))

(h/defn add-in-scheme!
  "Add skos:inScheme to all DanNet and COR resources (GitHub issue #175),
  mirroring how the OEWN marks scheme membership on its resources. Every URI
  subject residing in the namespace of its containing model is linked to the
  RDF resource of the relevant dataset. The DDS dataset is deliberately left
  out since it contains no resources of its own, only annotations of dn:
  resources; the OEWN label extensions don't need it either."
  [dataset]
  (doseq [[model-uri scheme] [[prefix/dn-uri md/<dn>]
                              [prefix/cor-uri md/<cor>]]]
    (let [model    (db/get-model dataset model-uri)
          g        (db/get-graph dataset model-uri)
          subjects (txn/transact model
                     (->> (iterator-seq (.listSubjects model))
                          (filter #(.isURIResource %))
                          (map #(.getURI %))
                          (filter #(str/starts-with? % model-uri))
                          (doall)))]
      (txn/transact-exec g
        (t/log! {:level :info
                 :id    :dannet.bootstrap/add-in-scheme
                 :data  {:scheme scheme
                         :count  (count subjects)}}
                "Adding skos:inScheme to resources")
        (db/safe-add! g (for [uri subjects]
                          [(prefix/uri->rdf-resource uri) :skos/inScheme scheme]))))))

(h/defn regenerate-short-labels!
  "Regenerate every dns:shortLabel from its synset's rdfs:label using the
  shared/canonical entry-ID heuristic, breaking ties by word polysemy (a proxy
  for word commonness). A short label is only emitted when canonical omits
  senses, with the \"…\" marker appended; otherwise rdfs:label suffices as-is."
  [dataset]
  (t/log! {:level :info
           :id    :dannet.bootstrap/regenerate-short-labels}
          "Regenerating short synset labels")
  (let [g        (db/get-graph dataset prefix/dn-uri)
        polysemy (->> (q/run g op/sense-label-polysemy)
                      (map (juxt (comp str '?senseLabel) '?polysemy))
                      (into {}))
        tiebreak (shared/polysemy-tiebreak polysemy)]
    (db/update-triples! prefix/dn-uri dataset op/synset-long-short-labels
      (fn [{:syms [?synset ?label]}]
        (when-let [short (shared/short-label tiebreak ?label)]
          [?synset :dns/shortLabel (md/da short)]))
      (fn [{:syms [?synset ?shortLabel]}]
        (when ?shortLabel
          [?synset :dns/shortLabel ?shortLabel])))))

(h/defn rewrite-ddo-source-urls!
  "Rewrite every DDO dns:source URL to the gammel.ordnet.dk subdomain (GitHub
  issue #192). The redesigned ordnet.dk redirects the old deep links without
  their def_id highlighting; the legacy UI at gammel.ordnet.dk retains it."
  [dataset]
  (t/log! {:level :info
           :id    :dannet.bootstrap/rewrite-ddo-source-urls}
          "Rewriting DDO source URLs to gammel.ordnet.dk")
  (db/update-triples! prefix/dn-uri dataset op/ddo-sources
    (fn [{:syms [?s ?src]}]
      [?s :dns/source (-> (prefix/rdf-resource->uri ?src)
                          (str/replace-first "https://ordnet.dk/"
                                             "https://gammel.ordnet.dk/")
                          (prefix/uri->rdf-resource))])
    (fn [{:syms [?s ?src]}]
      [?s :dns/source ?src])))

(h/defn add-missing-sense-sources!
  "Mint dns:source for senses that lack one but carry a DDO definition ID as
  dns:dslSense (GitHub issue #192), inserting that ID as the def_id in the
  deep link of the sense's word. Runs after `rewrite-ddo-source-urls!` so the
  minted URLs inherit the rewritten subdomain."
  [dataset]
  (t/log! {:level :info
           :id    :dannet.bootstrap/add-missing-sense-sources}
          "Minting dns:source for senses with a dns:dslSense ID")
  (db/update-triples! prefix/dn-uri dataset op/missing-dsl-sense-sources
    (fn [{:syms [?sense ?dslSense ?wordSource]}]
      (let [url (prefix/rdf-resource->uri ?wordSource)]
        (when (str/includes? url "&query=")
          [?sense :dns/source
           (-> url
               (str/replace-first "&query=" (str "&def_id=" ?dslSense "&query="))
               (prefix/uri->rdf-resource))])))))

(h/defn add-missing-written-reps!
  "Add the ontolex:writtenRep missing from the canonicalForm of 15 words
  (GitHub issue #203). The form nodes exist but are empty blank nodes, so any
  query joining word -> form -> writtenRep silently drops these words. The
  representation is recovered from the word's rdfs:label, which for every
  other DDO word equals the writtenRep wrapped in literal quotes; none of the
  15 labels contain the slash-alternative notation that is the only exception.

  The count is asserted so that a future bootstrap dataset silently growing or
  shrinking this set fails loudly instead.

  NB: adding reps makes these words newly visible to rep-joining queries, so
  this must run BEFORE any fix whose criteria join word -> form -> writtenRep
  (e.g. fix-verb-phrase-pos! on feature/cross-pos-changes), and such fixes
  should re-verify their expected-count asserts against the repaired data."
  [dataset]
  (t/log! {:level :info
           :id    :dannet.bootstrap/add-missing-written-reps}
          "Adding missing ontolex:writtenRep to dangling canonical forms")
  (let [g        (db/get-graph dataset prefix/dn-uri)
        expected 15
        found    (count (q/run g op/missing-written-reps))]
    (assert (= expected found)
            (str "expected " expected " words with a dangling canonicalForm, "
                 "found " found)))
  (db/update-triples! prefix/dn-uri dataset op/missing-written-reps
    (fn [{:syms [?form ?label]}]
      [?form :ontolex/writtenRep
       (md/da (str/replace (str ?label) #"^\"|\"$" ""))])))

(h/defn retarget-eq-ili-relations!
  "Retarget dns:eq* relations that link synsets to Interlingual Index entries
  instead of OEWN synsets (GitHub issue #205). The eq* relations hold between
  concepts in separate datasets, so every ILI target carried by exactly one
  OEWN synset is replaced with that synset -- the reading confirmed by all 77
  synsets that already carry the same relation to both an OEWN synset and its
  ILI. The remaining 25 triples are deleted: 2 target the WN-LMF placeholder
  ili:in (no identifiable concept) and 23 target retired ILI ids whose
  synsets -- mostly named entities and biological genera -- are absent from
  the current OEWN edition, leaving nothing to retarget to.

  Must run after add-open-english-wordnet!, which supplies the ILI -> synset
  mapping."
  [dataset]
  (let [oewn-g    (db/get-graph dataset prefix/oewn-uri)
        ;; ili:in maps to thousands of synsets and drops out via the
        ;; exactly-one requirement.
        ili->oewn (->> (q/run oewn-g '[:bgp [?synset :wn/ili ?ili]])
                       (group-by '?ili)
                       (into {} (keep (fn [[ili ms]]
                                        (when (= 1 (count ms))
                                          [ili (get (first ms) '?synset)])))))
        g         (db/get-graph dataset prefix/dn-uri)
        counts    (->> (q/run g op/eq-ili-relations)
                       (map (fn [{:syms [?ili]}]
                              (if (ili->oewn ?ili) :retarget :delete)))
                       (frequencies))
        expected  {:retarget 2620 :delete 25}]
    (t/log! {:level :info
             :id    :dannet.bootstrap/retarget-eq-ili-relations
             :data  counts}
            "Retargeting eq* relations from ILI entries to OEWN synsets")
    (assert (= expected counts)
            (str "expected eq*-to-ILI triples " expected ", found " counts))
    (db/update-triples! prefix/dn-uri dataset op/eq-ili-relations
      (fn [{:syms [?synset ?rel ?ili]}]
        (when-let [oewn-synset (ili->oewn ?ili)]
          [?synset ?rel oewn-synset]))
      (fn [{:syms [?synset ?rel ?ili]}]
        [?synset ?rel ?ili]))))

(h/defn remove-asserted-lexinfo-pos!
  "Delete the asserted lexinfo:partOfSpeech triples, which duplicate every
  word's wn:partOfSpeech 1:1 (GitHub issue #17). The lexinfo triple is now
  derived at query time by the value-translating rules in dannet.rules, so
  wn:partOfSpeech becomes the sole asserted -- and sole exported -- PoS.

  The count is asserted: one triple per word, including the defective
  empty-valued pair on dn:word-temporary_3."
  [dataset]
  (t/log! {:level :info
           :id    :dannet.bootstrap/remove-asserted-lexinfo-pos}
          "Deleting asserted lexinfo:partOfSpeech duplicates")
  (let [g        (db/get-graph dataset prefix/dn-uri)
        expected 62043
        found    (count (q/run g op/asserted-lexinfo-pos))]
    (assert (= expected found)
            (str "expected " expected " asserted lexinfo:partOfSpeech "
                 "triples, found " found)))
  (db/update-triples! prefix/dn-uri dataset op/asserted-lexinfo-pos
    (constantly nil)
    (fn [{:syms [?w ?pos]}]
      [?w :lexinfo/partOfSpeech ?pos])))

(h/defn remove-scaffolding-words!
  "Delete the artificial words \"TOP\", \"1stOrder\" and \"2ndOrder\" -- and
  their senses and canonical forms -- from `dataset`. These words lexicalize
  the EuroWordNet scaffolding synsets and are not Danish lemmas; \"2ndOrder\"
  is also the only word in DanNet whose PoS values are empty IRIs (wn: and
  lexinfo:), which is resolved by its deletion. The synsets remain as valuable
  synthetic parents, though without lexicalizations they are no longer picked
  up by the WN-LMF export -- deliberately so."
  [dataset]
  (t/log! {:level :info
           :id    :dannet.bootstrap/remove-scaffolding-words}
          "Removing artificial words from EuroWordNet scaffolding synsets")
  (let [g     (db/get-graph dataset prefix/dn-uri)
        model (db/get-model dataset prefix/dn-uri)
        cf    (.getProperty model (prefix/kw->uri :ontolex/canonicalForm))
        rows  (q/run g op/scaffolding-lexicalizations)]
    (assert (= 3 (count rows))
            (str "expected 3 scaffolding words, found " (count rows)))
    (txn/transact-exec model
      (doseq [{:syms [?sense ?word]} rows]
        (let [word (.getResource model (prefix/kw->uri ?word))]
          ;; The canonical forms are blank nodes, only reachable via interop.
          (doseq [stmt (doall (iterator-seq (.listStatements model word cf nil)))]
            (.removeAll model (.asResource (.getObject stmt)) nil nil))
          (db/remove! model [?word '_ '_])
          (db/remove! model [?sense '_ '_])
          (db/remove! model ['_ '_ ?sense]))))))

(h/defn mint-temporary-word-ids!
  "Replace the placeholder dn:word-temporary_N identifiers in `dataset` with
  dn:word-s<senseId> identifiers, reusing the scheme of the 3256 words
  synthesized for the 2023 adjective import. The temporary numbers are
  renumbered between DSL's CSV exports (e.g. temporary_3 was \"Fjerritslev\"
  in DanNet 2.2 but \"2ndOrder\" in 2.5.1) while the sense ids are stable,
  so each word's single sense id serves as its anchor.

  NB: two identical \"tinglysningskontor\" words share a sense and so merge
  into a single word; hence 950 words yield 949 identifiers. The merged word
  keeps just one of its two identical canonical forms, while the duplicated
  {tinglysningskontor} synsets it evokes are in turn merged by
  merge-tinglysningskontor-synsets!.

  Must run after remove-scaffolding-words!, which deletes 3 of the 953
  temporary words and is assumed by the expected count."
  [dataset]
  (let [g        (db/get-graph dataset prefix/dn-uri)
        model    (db/get-model dataset prefix/dn-uri)
        renames  (->> (q/run g op/temporary-words)
                      (map (fn [{:syms [?word ?sense]}]
                             [(prefix/kw->uri ?word)
                              (->> (subs (name ?sense) (count "sense-"))
                                   (str "word-s")
                                   (keyword "dn")
                                   (prefix/kw->uri))])))
        expected 950]
    (t/log! {:level :info
             :id    :dannet.bootstrap/mint-temporary-word-ids
             :data  {:count (count renames)}}
            "Minting stable identifiers for temporary words")
    (assert (= expected (count renames))
            (str "expected " expected " temporary words, found " (count renames)))
    ;; Renaming uses Jena interop since the words' canonical forms are blank
    ;; nodes, which Aristotle cannot round-trip (adding them back as literals).
    (txn/transact-exec model
      (doseq [[old-uri new-uri] renames]
        (ResourceUtils/renameResource (.getResource model old-uri) new-uri))
      ;; The merged word inherits a canonical form from each source word.
      (let [word  (.getResource model (prefix/kw->uri :dn/word-s24000079))
            cf    (.getProperty model (prefix/kw->uri :ontolex/canonicalForm))
            forms (doall (iterator-seq (.listStatements model word cf nil)))]
        (doseq [stmt (rest forms)]
          (.removeAll model (.asResource (.getObject stmt)) nil nil)
          (.remove model stmt))))))

(h/defn merge-tinglysningskontor-synsets!
  "Merge dn:synset-48184 into its duplicate dn:synset-48286 in `dataset`.
  DSL's CSV exports contain two degenerate {tinglysningskontor} synsets --
  absent from synsets.csv and thus without type or definition -- which are
  triple-for-triple identical, sharing their single sense. Each is referenced
  once by {tingbog_§1}, via wn:co_instrument_agent resp. wn:holo_location;
  both relations are kept on the surviving synset, which is also finally
  typed as an ontolex:LexicalConcept.

  Must run after mint-temporary-word-ids!, which merges the two words evoking
  the duplicates into the single word assumed by the expected count."
  [dataset]
  (t/log! {:level :info
           :id    :dannet.bootstrap/merge-tinglysningskontor-synsets}
          "Merging duplicate {tinglysningskontor} synsets")
  (let [g     (db/get-graph dataset prefix/dn-uri)
        model (db/get-model dataset prefix/dn-uri)
        query '[:bgp [?s ?p :dn/synset-48184]]
        found (count (q/run g query))]
    (assert (= 2 found)
            (str "expected 2 references to dn:synset-48184, found " found))
    (db/update-triples! prefix/dn-uri dataset query
      (fn [{:syms [?s ?p]}]
        [?s ?p :dn/synset-48286])
      (fn [{:syms [?s ?p]}]
        [?s ?p :dn/synset-48184]))
    (txn/transact-exec model
      (db/remove! model [:dn/synset-48184 '_ '_]))
    (txn/transact-exec g
      (db/safe-add! g [[:dn/synset-48286 :rdf/type :ontolex/LexicalConcept]]))))

(def synset-identity-properties
  "The properties left out when two synsets are compared: the blank nodes of
  dns:inherited and dns:ontologicalType differ by identity even where their
  contents match, and labels and definitions are what a comparison looks past."
  #{:ontolex/lexicalizedSense :rdf/type :skos/inScheme :dc/subject
    :dns/shortLabel :rdfs/label :skos/definition :dns/inherited
    :dns/ontologicalType})

(defn synset-relations
  "The relations placing `synset` in the wordnet, as [property object] pairs."
  [g synset]
  (->> (q/run g [:bgp [synset '?p '?o]])
       (remove (comp synset-identity-properties '?p))
       (map (juxt '?p '?o))
       (set)))

(defn synset-ontological-type
  "The ontological concepts of `synset`, read through its blank node so that
  two synsets can be compared by content."
  [g synset]
  (->> (q/run g [:bgp [synset :dns/ontologicalType '?bnode] ['?bnode '?p '?concept]])
       (map '?concept)
       (filter keyword?)
       (set)))

(defn duplicate-synsets
  "[survivor doomed] when one of the synsets `a` and `b` in `g` holds
  everything the other does, or nil when the two model different concepts.

  The survivor is the richer of the pair. Where each holds exactly what the
  other does, the one carrying a definition survives, and failing that the
  lower id."
  [g a b]
  (let [profile  (fn [synset] [(synset-relations g synset)
                               (synset-ontological-type g synset)])
        holds?   (fn [[relations ontotype] [relations' ontotype']]
                   (and (set/subset? relations' relations)
                        (set/subset? ontotype' ontotype)))
        defined? (fn [synset] (seq (q/run g [:bgp [synset :skos/definition '?o]])))
        id       (fn [synset] (parse-long (re-find #"\d+" (name synset))))
        pa       (profile a)
        pb       (profile b)]
    (cond
      (and (holds? pa pb) (holds? pb pa)) (cond
                                            (defined? a) [a b]
                                            (defined? b) [b a]
                                            :else        (vec (sort-by id [a b])))
      (holds? pa pb)                      [a b]
      (holds? pb pa)                      [b a])))

(h/defn merge-duplicate-synsets!
  "Merge the synset pairs of `dataset` that share a sense while modelling one
  and the same concept (GitHub issue #209).

  A sense pairs one word with one synset, so a sense lexicalized by two
  synsets is a modelling violation. In 10 of the 78 cases the pair is not two
  concepts at all: one synset holds everything the other does, being either an
  exact copy of it or a stub minted to anchor a few relations. Those merge the
  way the {tinglysningskontor} duplicates do -- every reference to the poorer
  synset is retargeted at the survivor, a definition only it carried is moved
  across, and it is then deleted.

  Runs before split-shared-senses!, which divides the senses of the other 68
  pairs, those modelling genuinely different concepts."
  [dataset]
  (t/log! {:level :info
           :id    :dannet.bootstrap/merge-duplicate-synsets}
          "Merging synsets that share a sense and model one concept")
  (let [g        (db/get-graph dataset prefix/dn-uri)
        model    (db/get-model dataset prefix/dn-uri)
        merges   (->> (q/run g op/shared-senses)
                      (group-by '?sense)
                      (vals)
                      (keep (fn [rows]
                              (let [[a b] (sort-by name (distinct (map '?synset rows)))]
                                (duplicate-synsets g a b)))))
        expected 10]
    (assert (= expected (count merges))
            (str "expected " expected " duplicate synset pairs, found "
                 (count merges)))
    (doseq [[survivor doomed] merges]
      (db/update-triples! prefix/dn-uri dataset [:bgp ['?s '?p doomed]]
        (fn [{:syms [?s ?p]}] [?s ?p survivor])
        (fn [{:syms [?s ?p]}] [?s ?p doomed]))
      ;; A definition carried only by the doomed synset would otherwise go
      ;; down with it, since only its references are retargeted above.
      (when-let [definition (and (empty? (q/run g [:bgp [survivor :skos/definition '?o]]))
                                 (first (map '?o (q/run g [:bgp [doomed :skos/definition '?o]]))))]
        (txn/transact-exec g
          (db/safe-add! g [[survivor :skos/definition definition]])))
      (txn/transact-exec model
        (db/remove! model [doomed '_ '_])))))

(h/defn split-shared-senses!
  "Divide each sense of `dataset` still shared by two synsets into one sense
  per synset (GitHub issue #209).

  Where the two synsets model different concepts -- the 68 pairs left once
  merge-duplicate-synsets! has dealt with the other 10 -- the shared sense
  cannot simply move to one of them, so it becomes two.

  The division follows the convention DSL already uses, e.g. for
  dn:sense-23000002: dn:sense-<id>-i1 and -i2, each carrying dns:dslSense with
  the original DDO sense id, and a label ending in (1) resp. (2), as in
  \"Afghanistan_§1(1)\", since the two would otherwise read alike wherever a
  sense is shown without its synset. The unsuffixed sense disappears, as it
  does there. The disambiguator joins the sense marker rather than following a
  second underscore, a shape shared/sense-label cannot parse and which
  therefore renders blank.

  Renaming the sense to -i1 carries the word and both synsets along with it,
  after which the second synset is moved onto the -i2 copy.

  Must run after merge-duplicate-synsets!, whose 10 merges the count below
  assumes."
  [dataset]
  (t/log! {:level :info
           :id    :dannet.bootstrap/split-shared-senses}
          "Dividing senses shared by two synsets")
  (let [g        (db/get-graph dataset prefix/dn-uri)
        model    (db/get-model dataset prefix/dn-uri)
        uri      (fn [kw] (.getResource model (prefix/kw->uri kw)))
        ;; Every read happens before the first write: a query issued inside a
        ;; write transaction comes back empty, and db/safe-add! logs such a
        ;; failure rather than raising it.
        splits   (->> (q/run g op/shared-senses)
                      (group-by '?sense)
                      (into (sorted-map)
                            (map (fn [[sense rows]]
                                   (let [triples (q/run g [:bgp [sense '?p '?o]])]
                                     [sense {:word  (some '?word rows)
                                             :moved (second (sort-by name (distinct (map '?synset rows))))
                                             :label (str (some (fn [{:syms [?p ?o]}]
                                                                 (when (= :rdfs/label ?p) ?o))
                                                               triples))
                                             :own   (vec (for [{:syms [?p ?o]} triples
                                                               :when (not= :rdfs/label ?p)]
                                                           [?p ?o]))}])))))
        expected 68]
    (assert (= expected (count splits))
            (str "expected " expected " shared senses, found " (count splits)))
    (doseq [[sense {:keys [word moved label own]}] splits]
      (let [dsl-id  (subs (name sense) (count "sense-"))
            renamed (keyword "dn" (str (name sense) "-i1"))
            copy    (keyword "dn" (str (name sense) "-i2"))]
        (txn/transact-exec model
          (ResourceUtils/renameResource (uri sense) (prefix/kw->uri renamed))
          (.removeAll model (uri renamed)
                      (.getProperty model (prefix/kw->uri :rdfs/label)) nil)
          (.removeAll model (uri moved)
                      (.getProperty model (prefix/kw->uri :ontolex/lexicalizedSense))
                      (uri renamed)))
        (txn/transact-exec g
          (db/safe-add! g (into [[moved :ontolex/lexicalizedSense copy]
                                 [word :ontolex/sense copy]
                                 [renamed :rdfs/label (md/da label "(1)")]
                                 [copy :rdfs/label (md/da label "(2)")]
                                 [renamed :dns/dslSense dsl-id]
                                 [copy :dns/dslSense dsl-id]]
                                (for [[p o] own] [copy p o]))))))))

(h/defn rebuild-cor-graph!
  "Rebuild the cor: graph of `dataset` from the published COR source files.

  The graph has been carried forward as data since the 2022 import of COR₁
  1.02; rebuilding derives every word and form from the current edition
  instead. The links tying COR words to DanNet words and senses have no such
  source: the COR.EXT links still come from DSL's link file, while the COR₁
  link file is lost, so those links survive from the graph being replaced.
  Links whose COR word is gone from the sources are remapped via the official
  changelogs, links to senses divided by a split are retargeted through
  dns:dslSense (cf. 493db1c and issue #146), and links whose word or sense no
  longer exists on either side are pruned.

  Must run after split-shared-senses!, so that retargeting sees the final
  sense IDs, and before add-in-scheme!, which re-adds skos:inScheme to the
  rebuilt graph."
  [dataset]
  (t/log! {:level :info
           :id    :dannet.bootstrap/rebuild-cor-graph
           :data  {:version release/cor-version}}
          "Rebuilding COR graph from source files")
  (let [cor-graph   (db/get-graph dataset prefix/cor-uri)
        cor-model   (db/get-model dataset prefix/cor-uri)
        dn-graph    (db/get-graph dataset prefix/dn-uri)
        cor-word?   (fn [x] (and (keyword? x) (= "cor" (namespace x))))
        cor1?       (fn [x] (and (cor-word? x)
                                 (not (str/starts-with? (name x) "COR.EXT."))))
        ;; Every read happens before the first write -- see split-shared-senses!.
        word-links  (->> (q/run cor-graph '[:bgp [?w :owl/sameAs ?dn]])
                         (filter (comp cor1? '?w))
                         (map (juxt '?w '?dn)))
        sense-links (->> (q/run cor-graph '[:bgp [?w :ontolex/sense ?s]])
                         (filter (comp cor1? '?w))
                         (map (juxt '?w '?s)))
        dn-words    (->> [:ontolex/Word :ontolex/MultiwordExpression :ontolex/Affix]
                         (mapcat #(q/run dn-graph [:bgp ['?w :rdf/type %]]))
                         (map '?w)
                         (set))
        dn-senses   (->> (q/run dn-graph '[:bgp [?s :rdf/type :ontolex/LexicalSense]])
                         (map '?s)
                         (set))
        dsl-senses  (->> (q/run dn-graph '[:bgp [?s :dns/dslSense ?id]])
                         (reduce (fn [m {:syms [?s ?id]}]
                                   (update m ?id (fnil conj #{}) ?s))
                                 {}))
        source      (cor/source-triples)
        cor-words   (into #{}
                          (keep (fn [[s p _]]
                                  (when (#{:ontolex/canonicalForm
                                           :ontolex/otherForm} p)
                                    s)))
                          source)
        remap       (cor/id-remap)
        new-words   (fn [w]
                      (if (cor-words w)
                        [w]
                        (->> (remap (name w))
                             (map (partial keyword "cor"))
                             (filter cor-words))))
        new-senses  (fn [s]
                      (if (dn-senses s)
                        [s]
                        (get dsl-senses (subs (name s) (count "sense-")))))
        ext-links   (cor/ext-link-triples)
        word-pairs  (into (set word-links)
                          (for [[s p o] ext-links
                                :when (and (= :owl/sameAs p) (cor-word? s))]
                            [s o]))
        sense-pairs (into (set sense-links)
                          (for [[s p o] ext-links
                                :when (= :ontolex/sense p)]
                            [s o]))]
    (t/trace! {:id :dannet.bootstrap/drop-cor-graph}
      (txn/transact-exec cor-model
        (.removeAll ^Model cor-model)))
    ;; The source triples go in first, in bounded transactions, so that the
    ;; multi-million member set is released before the link triples are
    ;; computed -- holding both at once puts the 8 GB production heap under
    ;; enough GC pressure to slow the whole rebuild by an order of magnitude.
    (t/trace! {:id :dannet.bootstrap/add-cor-source}
      (doseq [chunk (partition-all 500000 source)]
        (txn/transact-exec cor-graph
          (db/safe-add! cor-graph chunk))))
    (let [word-triples  (set (for [[w dn] word-pairs
                                   :when (dn-words dn)
                                   w' (new-words w)
                                   t [[w' :owl/sameAs dn] [dn :owl/sameAs w']]]
                               t))
          sense-triples (set (for [[w s] sense-pairs
                                   w' (new-words w)
                                   s' (new-senses s)]
                               [w' :ontolex/sense s']))]
      (t/log! {:level :info
               :id    :dannet.bootstrap/carry-cor-links
               :data  {:in  {:word-links  (count word-pairs)
                             :sense-links (count sense-pairs)}
                       :out {:word-links  (/ (count word-triples) 2)
                             :sense-links (count sense-triples)}}}
              "Carrying COR links over to the rebuilt graph")
      (t/trace! {:id :dannet.bootstrap/add-cor-links}
        (txn/transact-exec cor-graph
          (db/safe-add! cor-graph (into word-triples sense-triples))))
      (txn/transact-exec cor-model
        (md/update-metadata! (get md/metadata 'cor) cor-model)))))

(h/defn make-release-changes!
  "Apply the changes that produce this release, i.e. deletions and additions
  to either of the export datasets.

  Nothing runs while `to` equals `from`: the database then reproduces the
  release it was bootstrapped from and must stay faithful to it. Setting `to`
  cuts a release and enables the changes below, which are cleared out once that
  release has shipped."
  [dataset]
  (when (not= release/from release/to)
    (t/log! {:level :info
             :id    :dannet.bootstrap/release-changes
             :data  {:from release/from :to release/to}}
            "Applying release changes")

    ;; ==== Changes for this particular release. ====
    (rewrite-ddo-source-urls! dataset)
    (add-missing-sense-sources! dataset)
    (add-missing-written-reps! dataset)
    (retarget-eq-ili-relations! dataset)
    (remove-asserted-lexinfo-pos! dataset)
    (remove-scaffolding-words! dataset)
    (mint-temporary-word-ids! dataset)
    (merge-tinglysningskontor-synsets! dataset)
    (merge-duplicate-synsets! dataset)
    (split-shared-senses! dataset)
    (rebuild-cor-graph! dataset)

    ;; ==== Derived data, regenerated for every release. NOT cleared out. ====
    (add-in-scheme! dataset)
    (regenerate-short-labels! dataset)))

(defn ->dataset
  "Get a Dataset object of the given `db-type`. TDB also requires a `db-path`.

  NOTE: TDB 1 does not require transactions until after the first transaction
  has taken place, while TDB 2 *always* requires transactions when reading from
  or writing to the database."
  [db-type & [db-path]]
  (case db-type
    :tdb1 (TDBFactory/createDataset ^String db-path)
    :tdb2 (TDB2Factory/connectDataset ^String db-path)
    :in-mem (DatasetFactory/create)
    :in-mem-txn (DatasetFactory/createTxnMem)))

(defn- log-entry
  [db-name db-type input-dir]
  (let [now       (LocalDateTime/now)
        formatter (DateTimeFormatter/ofPattern "yyyy/MM/dd HH:mm:ss")
        filenames (sort (->> (file-seq input-dir)
                             (remove #{input-dir})
                             (map #(.getName ^File %))))]
    (str
      "Location: " db-name "\n"
      "Type: " db-type "\n"
      "Created: " (.format now formatter) "\n"
      "Input data: " (str/join ", " filenames))))

(def reasoner
  "The custom reasoner inferring many triples present in the complete dataset.

  The rules in 'dannet.rules' are purpose-built for DanNet, covering only
  owl:inverseOf and rdfs:subPropertyOf entailment as tabled backward rules."
  (let [rules (Rule/parseRules (slurp (io/resource "etc/dannet.rules")))]
    (doto (GenericRuleReasoner. rules)
      (.setMode GenericRuleReasoner/HYBRID)
      (.setTransitiveClosureCaching true))))

(defn dataset->db
  "Construct a database map from an Apache Jena `dataset`.

  If `schema-uris` are provided, the returned model & graph contain inferences;
  otherwise, the model/graph is of union of the models/graphs in the dataset.

  The base (non-inference) model is always available as :base-model for use by
  the SPARQL endpoint, where inference is opt-in."
  [^Dataset dataset & [schema-uris]]
  (if schema-uris
    (let [schema    (db/->schema-model schema-uris)
          model     (.getUnionModel dataset)
          inf-model (ModelFactory/createInfModel reasoner schema model)
          inf-graph (.getGraph inf-model)]
      ;; A plain :info log, not a trace! around createInfModel: Jena builds the
      ;; InfModel lazily, so tracing here would report a near-zero runtime. The
      ;; real inference cost is realized later, on first traversal -- see the
      ;; :dannet.graph/* traces in dk.cst.dannet.web.instance.
      (t/log! {:level :info
               :id    :dannet.graph/inference-model
               :data  {:schema-count (count schema-uris)}}
              "Constructing inference model")
      {:dataset    dataset
       :base-model model
       :model      inf-model
       :graph      inf-graph})
    (let [model (.getUnionModel dataset)
          graph (.getGraph model)]
      {:dataset    dataset
       :base-model model
       :model      model
       :graph      graph})))

(h/defn ->dannet
  "Create a Jena database from the latest DanNet export.

    :input-dir         - Previous DanNet version TTL export as a File directory.
    :db-type           - :tdb1, :tdb2, :in-mem, and :in-mem-txn are supported
    :db-path           - Where to persist the TDB1/TDB2 data.
    :schema-uris       - A collection of URIs containing schemas."
  [& {:keys [^File input-dir db-path db-type schema-uris refetch?]
      :or   {db-type :in-mem} :as opts}]
  (let [log-path (str db-path "/log.txt")]
    (if input-dir
      ;; Either refetch or assert the datasets are already present. Neither
      ;; downloads silently on a normal start; both run for side effects before
      ;; the file-seq below, which expects the inputs on disk.
      (let [_              (downloads/assert-input-dir! input-dir release/from)
            _              (if refetch?
                             (downloads/refetch-datasets! input-dir release/from)
                             (downloads/assert-datasets-present! input-dir))
            ;; The indegree cache can arrive with the fetch above, i.e. after
            ;; this namespace was loaded, so the delay is re-derived here.
            _              (q/reload-synset-indegrees!)
            files          (->> (file-seq input-dir)
                                (filter #(re-find #"\.zip$" (.getName ^File %))))
            fn-hashes      [(:hash (meta #'add-open-english-wordnet!))
                            (:hash (meta #'add-open-english-wordnet-labels!))
                            (:hash (meta #'make-release-changes!))
                            (:hash (meta #'rebuild-cor-graph!))
                            (:hash (meta #'cor/->cor-triples))
                            (:hash (meta #'cor/->cor-ext-triples))
                            (:hash (meta #'cor/->cor-link-triples))
                            (:hash (meta #'cor/id-remap))
                            (:hash (meta #'add-in-scheme!))
                            (:hash (meta #'regenerate-short-labels!))
                            (:hash (meta #'md/add-dataset-statistics!))
                            (:hash (meta #'md/metadata))
                            (:hash (meta #'md/update-metadata!))
                            (:hash (meta #'->dannet))
                            (hash prefix/schemas)
                            ;; The emitted version is baked into the dataset
                            ;; metadata but isn't captured by any hashed form
                            ;; above (those hash source, not values), so include
                            ;; it explicitly -- otherwise cutting a release
                            ;; (:to "SNAPSHOT" -> a real version, same :from)
                            ;; wouldn't change db-name and the stale, still
                            ;; SNAPSHOT-labelled database would be reused.
                            release/to
                            ;; The OEWN edition is likewise only a value: its
                            ;; ttl isn't among the hashed input zips, so it is
                            ;; included explicitly -- otherwise bumping the
                            ;; edition wouldn't trigger a rebuild.
                            downloads/oewn-version
                            ;; The COR editions for the same reason: their
                            ;; source files aren't among the hashed input zips.
                            release/cor-version
                            release/cor-ext-version]
            ;; Undo potentially negative number by bit-shifting.
            files-hash     (h/pos-hash files)
            bootstrap-hash (h/pos-hash fn-hashes)
            db-name        (str files-hash "-" bootstrap-hash)
            full-db-path   (str db-path "/" db-name)
            zip-file?      (comp #(str/ends-with? % ".zip") #(.getName %))
            ttl-file?      (comp #(str/ends-with? % ".ttl") #(.getName %))
            ;; Written as the final build step below; a directory without it
            ;; is a partial build (e.g. an exception mid-build) and is rebuilt.
            build-complete (io/file full-db-path "build-complete.txt")
            db-exists?     (.exists build-complete)
            new-entry      (log-entry db-name db-type input-dir)
            dataset        (->dataset db-type full-db-path)
            ;; Include the current build hash to make debugging easier
            metadata'      (update md/metadata 'dn conj [md/<dn> :dn/build db-name])]
        (t/log! {:level :debug
                 :id    :dannet.bootstrap/db-path
                 :data  {:path full-db-path}}
                "Resolved database path")
        (if db-exists?
          (do
            (t/event! :dannet.bootstrap/build-skipped
                      {:level :info
                       :msg   "Skipping build (database already exists)"
                       :data  {:db-name db-name}})
            (dataset->db dataset schema-uris))
          (t/trace! {:id      :dannet.bootstrap/build
                     :run-val :elided
                     :data    {:db-name db-name
                               :db-type db-type
                               :input   (.getName input-dir)
                               :path    full-db-path}}
            (do
              (t/log! {:level :info
                       :id    :dannet.bootstrap/build-started
                       :data  {:db-name db-name :input (.getName input-dir)}}
                      "Building new database")
              (doseq [zip-file (filter zip-file? (file-seq input-dir))]
                ;; unzip writes to (str output-parent (.getName entry)) with no
                ;; separator, so output-parent must be a dir path ending in "/"
                ;; -- otherwise entries get the zip's own path prepended (e.g.
                ;; "oewn-extension.zipoewn-extension.ttl").
                (zip/unzip zip-file (str (.getParent ^File zip-file) "/"))
                (let [ttl-file  (first (filter ttl-file? (file-seq input-dir)))
                      model-uri (prefix/zip-file->uri (.getName zip-file))
                      prefix    (prefix/uri->prefix model-uri)
                      update!   (when prefix
                                  (partial md/update-metadata! (metadata' prefix)))
                      ;; Special behaviour to check bootstrap files version
                      changefn  (if (= prefix 'dn)
                                  (fn [model]
                                    (t/log! {:level :debug
                                             :id    :dannet.bootstrap/version-check
                                             :data  {:model (str model-uri)}}
                                            "Checking bootstrap version")
                                    (assert-expected-dannet-release! model)
                                    (update! model))
                                  update!)]
                  (db/import-files dataset model-uri [ttl-file] changefn)
                  (zip/delete-file ttl-file)))

              ;; The English is always explicitly added as it is not part of our
              ;; own latest export (only the DanNet-like labels we produce are).
              ;; It is imported BEFORE the release changes, some of which need
              ;; the OEWN graph for lookups (e.g. retarget-eq-ili-relations!).
              (add-open-english-wordnet! dataset)

              ;; Effectuate changes for the current release.
              (make-release-changes! dataset)

              ;; Runs after the release changes so that the dataset
              ;; statistics reflect the data actually being exported.
              (md/add-dataset-statistics! dataset)

              (t/log! {:level :info
                       :id    :dannet.bootstrap/db-created
                       :data  {:db-name db-name}}
                      "Database created")
              (spit log-path (str new-entry "\n----\n") :append true)
              ;; The :in-mem db types never create the directory.
              (when (.isDirectory (io/file full-db-path))
                (spit build-complete new-entry))
              (dataset->db dataset schema-uris)))))
      (let [db-name      (->> (slurp log-path)
                              (re-seq #"Location: (.+)")
                              (last)
                              (second))
            full-db-path (str db-path "/" db-name)
            dataset      (->dataset db-type full-db-path)]
        (t/log! {:level :warn :id :dannet.bootstrap/no-input-dir}
                "No input dir provided -- reusing existing database from log")
        (dataset->db dataset schema-uris)))))
