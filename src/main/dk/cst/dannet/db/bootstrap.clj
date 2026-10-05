(ns dk.cst.dannet.db.bootstrap
  "Represent DanNet as an in-memory graph or within a persisted database (TDB).

  Inverse relations are not explicitly created, but rather handled by way of
  inference using a Jena OWL reasoner."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [arachne.aristotle :as aristotle]
            [clj-file-zip.core :as zip]
            [taoensso.telemere :as t]
            [dk.cst.dannet.db.query :as q]
            [dk.cst.dannet.db.query.operation :as op]
            [dk.cst.dannet.db.transaction :as txn]
            [dk.cst.dannet.db :as db]
            [dk.cst.dannet.db.bootstrap.corsem :as corsem]
            [dk.cst.dannet.db.bootstrap.downloads :as downloads]
            [dk.cst.dannet.db.bootstrap.metadata :as md]
            [dk.cst.dannet.db.bootstrap.premon :as premon]
            [dk.cst.dannet.hash :as h]
            [dk.cst.dannet.release :as release]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.prefix :as prefix])
  (:import [java.io File]
           [java.time LocalDateTime]
           [java.time.format DateTimeFormatter]
           [java.util.regex Pattern]
           [org.apache.jena.query Dataset DatasetFactory]
           [org.apache.jena.rdf.model Model ModelFactory]
           [org.apache.jena.reasoner.rulesys GenericRuleReasoner Rule]
           [org.apache.jena.tdb TDBFactory]
           [org.apache.jena.tdb2 TDB2Factory]))

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

(h/defn synset-label
  "The label of a synset given the `labels` of its senses, i.e. the distinct
  labels sorted and joined, e.g. {hund_1§1; køter_§1}, in the language of
  `->lang` (md/da or md/en)."
  [->lang labels]
  (->lang "{" (str/join "; " (sort (distinct (map str labels)))) "}"))

(h/defn add-open-english-wordnet-labels!
  "Generate appropriate labels for the (otherwise unlabeled) OEWN in `dataset`."
  [dataset]
  (t/trace! {:id :dannet.bootstrap/oewn-labels :run-val :elided}
    (let [oewn-graph  (db/get-graph dataset prefix/oewn-uri)
          label-graph (db/get-graph dataset prefix/oewn-extension-uri)
          ms          (q/run oewn-graph op/oewn-label-targets)
          collect-rep (fn [m {:syms [?synset ?rep]}]
                        (update m ?synset conj (str ?rep)))]
      (txn/transact-exec dataset
        (t/log! {:level :debug
                 :id    :dannet.bootstrap/oewn-synset-labels
                 :data  {:graph (str prefix/oewn-extension-uri)}}
                "Adding OEWN synset labels")
        (->> (reduce collect-rep {} ms)
             (map (fn [[synset labels]]
                    [synset :rdfs/label (synset-label md/en labels)]))
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

(h/defn add-framenet!
  "Add the framenet graph to `dataset`: the FrameNet 1.7 frame inventory
  converted from the PreMOn dump by the premon ns."
  [dataset]
  (t/trace! {:id :dannet.bootstrap/import-framenet :run-val :elided}
    (let [graph (db/get-graph dataset prefix/framenet-uri)
          model (db/get-model dataset prefix/framenet-uri)]
      (doseq [chunk (partition-all 500000 (premon/source-triples))]
        (txn/transact-exec graph
          (db/safe-add! graph chunk)))
      (txn/transact-exec model
        (md/update-metadata! (get (md/metadata) 'frame) model)))))

(h/defn add-in-scheme!
  "Add skos:inScheme to all DanNet, COR and COR.SEM resources (GitHub issue
  #175), mirroring how the OEWN marks scheme membership on its resources.
  Every URI subject residing in the resource namespace of its containing model
  is linked to the RDF resource of the relevant dataset. The COR.SEM IDs share
  the cor: namespace, so the sense inventory is set apart by its full COR.SEM.
  prefix; this also leaves the cor: words and frame: resources appearing as
  subjects in the cor-sem: graph unmarked, as they belong to other schemes
  (the frame: resources are marked in the framenet graph instead).
  The DDS dataset is deliberately left out since it contains no resources of
  its own, only annotations of dn: resources; the OEWN label extensions don't
  need it either."
  [dataset]
  (doseq [[model-uri ns-uri scheme] [[prefix/dn-uri prefix/dn-uri md/<dn>]
                                     [prefix/cor-uri prefix/cor-uri md/<cor>]
                                     [prefix/cor-sem-uri (str prefix/cor-uri "COR.SEM.") md/<cor-sem>]
                                     [prefix/framenet-uri prefix/framenet-uri md/<framenet>]]]
    (let [model    (db/get-model dataset model-uri)
          g        (db/get-graph dataset model-uri)
          subjects (txn/transact model
                     (->> (iterator-seq (.listSubjects model))
                          (filter #(.isURIResource %))
                          (map #(.getURI %))
                          (filter #(str/starts-with? % ns-uri))
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
  "Regenerate every dns:shortLabel from its synset's rdfs:label, ranking the
  senses by COR.SEM centrality first and the shared/canonical entry-ID
  heuristic among equally central senses, breaking remaining ties by word
  polysemy (a proxy for word commonness). A short label is only emitted when
  canonical omits senses, with the \"…\" marker appended; otherwise
  rdfs:label suffices as-is.

  The centrality values reach the dn: senses through the sense matches of the
  cor-sem: graph, so this must run after that graph is imported."
  [dataset]
  (t/log! {:level :info
           :id    :dannet.bootstrap/regenerate-short-labels}
          "Regenerating short synset labels")
  (let [g          (db/get-graph dataset prefix/dn-uri)
        sem-g      (db/get-graph dataset prefix/cor-sem-uri)
        centrality (q/label-centralities
                     (q/run sem-g op/centrality-query)
                     (q/run sem-g op/eq-sense-match-query)
                     (q/run g op/synset-sense-label-query))
        polysemy   (->> (q/run g op/sense-label-polysemy)
                        (map (juxt (comp str '?senseLabel) '?polysemy))
                        (into {}))
        primary    (shared/centrality-primary centrality)
        tiebreak   (shared/polysemy-tiebreak polysemy)]
    (db/update-triples! prefix/dn-uri dataset op/synset-long-short-labels
      (fn [{:syms [?synset ?label]}]
        (when-let [short (shared/short-label primary tiebreak ?label)]
          [?synset :dns/shortLabel (md/da short)]))
      (fn [{:syms [?synset ?shortLabel]}]
        (when ?shortLabel
          [?synset :dns/shortLabel ?shortLabel])))))

(def dannet-2-owl-dir
  "The DanNet 2.2 OWL release, the only source of the DanNet 2 negations.

  It is not downloaded automatically. Get DanNet-2.2_owl.zip from
  https://repository.clarin.dk/items/db6c5063-1d5d-435a-8ccc-8239fd826542
  and unzip it into bootstrap/dannet/, next to the DanNet 2.2 CSV release."
  "bootstrap/dannet/DanNet-2.2_owl")

(def negated-relation-predicates
  "The current predicate of each DanNet 2.2 relation that has negations, keyed
  by its name in the DanNet 2.2 OWL release."
  {"concerns"          :wn/also
   "locationMeronymOf" :wn/holo_location
   "madeofHolonymOf"   :wn/mero_substance
   "memberHolonymOf"   :wn/mero_member
   "memberMeronymOf"   :wn/holo_member
   "partHolonymOf"     :wn/mero_part
   "roleAgent"         :wn/agent
   "rolePatient"       :wn/patient
   "usedFor"           :dns/usedFor})

(h/defn dannet-2-negations
  "Read the negated relations in the DanNet 2.2 OWL release in `dir` as
  [synset relation target from] tuples of DanNet 2.2 ids and relation names.

  `from` is the synset that the negation is inherited from, or nil for a
  direct negation. Only an XML comment gives this synset, and RDF parsers
  discard comments, so the files are read as text."
  [dir]
  (let [negation-re (re-pattern
                      (str "<owl:NegativeObjectPropertyAssertion>"
                           "(?:<!-- Inherited from synset with id (\\d+) .*?-->)?"
                           "\\s*<rdf:subject rdf:resource=\"&dn;synset-(\\d+)\"/>"
                           "<rdf:predicate rdf:resource=\"&\\w+;(\\w+)\"/>"
                           "<rdf:object rdf:resource=\"&dn;synset-(\\d+)\"/>"))]
    (->> (.listFiles (io/file dir))
         (filter #(str/ends-with? (.getName ^File %) ".rdf"))
         (mapcat #(re-seq negation-re (slurp % :encoding "ISO-8859-1")))
         (map (fn [[_ from synset relation target]]
                [synset relation target from])))))

(h/defn negation-triples
  "The triples of an owl:NegativePropertyAssertion that denies [`s` `p` `o`],
  naming the synset `from` that the negation is inherited from, if any."
  [[s p o] from]
  (let [negation (symbol (str "_negation-" (name s) "-" (name p) "-" (name o)))]
    (cond-> #{[negation :rdf/type :owl/NegativePropertyAssertion]
              [negation :owl/sourceIndividual s]
              [negation :owl/assertionProperty p]
              [negation :owl/targetIndividual o]}
      from (conj [negation :dns/inheritedFrom from]))))

(h/defn remove-inheritance-source!
  "Remove `from` from the sources of the `p` relations that `s` inherits in
  `model`, and remove the inheritance mark when no source remains."
  [^Model model s p from]
  (let [res       #(.createResource model ^String (prefix/kw->uri %))
        prop      #(.createProperty model ^String (prefix/kw->uri %))
        from-prop (prop :dns/inheritedFrom)]
    (doseq [stmt (vec (iterator-seq (.listProperties (res s) (prop :dns/inherited))))
            :let [mark (.getResource stmt)]
            :when (.hasProperty mark (prop :dns/inheritedRelation) (res p))]
      (.remove model mark from-prop (res from))
      (when-not (.hasProperty mark from-prop)
        (.removeAll model mark nil nil)
        (.remove model stmt)))))

(h/defn replace-negated-relations!
  "Replace the relations in the dn: graph of `dataset` that DanNet 2.2 negated
  with owl:NegativePropertyAssertion resources (GitHub issue #216).

  Most of the 268 negations define a concept by what it lacks, for example
  {ikkeryger} is not the agent of {ryge}. 203 of them are inherited from the
  7 synsets that state them directly, 184 from {lokale}. The DanNet 2.2 CSV
  release shows them as ordinary relations, so the conversion of that release
  asserted all 268. An inheritance mark stays only while its synset inherits
  other targets of the relation from the same synset.

  The counts are asserted so that a future bootstrap dataset silently growing
  or shrinking this set fails loudly instead."
  [dataset]
  (t/log! {:level :info
           :id    :dannet.bootstrap/replace-negated-relations}
          "Replacing the relations that DanNet 2.2 negated")
  (let [g         (db/get-graph dataset prefix/dn-uri)
        model     (db/get-model dataset prefix/dn-uri)
        synset    #(keyword "dn" (str "synset-" %))
        negations (for [[s relation o from] (dannet-2-negations dannet-2-owl-dir)]
                    [[(synset s) (negated-relation-predicates relation) (synset o)]
                     (some-> from synset)])
        inherited (filter second negations)]
    (assert (= 268 (count negations))
            (str "expected 268 negations, found " (count negations)))
    (assert (= 203 (count inherited))
            (str "expected 203 inherited negations, found " (count inherited)))
    (assert (every? #(seq (q/run g [:bgp (first %)])) negations)
            "expected every negated relation to be asserted")
    (txn/transact-exec model
      (doseq [[triple] negations]
        (db/remove! model triple)))
    (txn/transact-exec g
      (db/safe-add! g (mapcat #(apply negation-triples %) negations)))
    (let [sources (for [[[s p] from] inherited
                        :when (empty? (q/run g [:bgp [s p '?o] [from p '?o]]))]
                    [s p from])]
      (t/log! {:level :info
               :id    :dannet.bootstrap/remove-inheritance-sources
               :data  {:count (count sources)}}
              "Removing inheritance sources that only negations justified")
      (txn/transact-exec model
        (doseq [[s p from] sources]
          (remove-inheritance-source! model s p from))))))

(def dannet-2-csv-dir
  "The DanNet 2.5.1 CSV release, the source of the DanNet 2 sense examples.

  It is not downloaded automatically. Unzip it into bootstrap/dannet/, next to
  the DanNet 2.2 releases. The 2023 conversion also read this release."
  "bootstrap/dannet/DanNet-2.5.1_csv")

(def truncated-dannet-2-examples
  "The DanNet 2 examples that the release cut off at a subscript, a
  superscript or a comma."
  #{"Øget CO" "1 m" "10"})

(h/defn read-dannet-2-csv
  "Read the rows of the DanNet 2 CSV `file` in `dir` as vectors of fields."
  [dir file]
  (with-open [r (io/reader (io/file dir file) :encoding "ISO-8859-1")]
    (mapv #(str/split % #"@" -1) (line-seq r))))

(h/defn example-groups
  "Read the usage examples in the gloss of a DanNet 2 synsets.csv `row` as one
  group of examples per label word with examples, in label order.

  The examples follow \"(Brug: \" in the gloss, with \"; \" between the groups
  and \" || \" between the examples of a group. A few glosses contain the
  field separator @, so the gloss is every field before the last two."
  [[_ _ & fields]]
  (when-let [[_ s] (re-find #"\(Brug: \"(.+)\"\)"
                            (str/join "@" (drop-last 2 fields)))]
    (for [group (str/split s #"\"; \"")]
      (map str/trim (str/split group #" \|\| ")))))

(h/defn label-forms
  "The written forms of the words in a DanNet 2 synset `label`, in label order,
  e.g. [\"tilpasse\" \"passe ''til\"] for {tilpasse_1; passe,1_1: passe ''til}."
  [label]
  (mapv (fn [word]
          (if-let [[_ phrase] (re-find #": (.+)$" word)]
            phrase
            (str/replace word #"^DN:|,\d+_.*$|_.*$" "")))
        (str/split (subs label 1 (dec (count label))) #"; ")))

(h/defn dannet-2-example-synsets
  "Read the DanNet 2 synsets with usage examples from the CSV release in `dir`
  as maps of the synset :id, the :forms of its label words, the :sense-ids of
  each form and the example :groups."
  [dir]
  (let [forms  (into {} (map (juxt first second))
                     (read-dannet-2-csv dir "words.csv"))
        senses (group-by #(nth % 2) (read-dannet-2-csv dir "wordsenses.csv"))]
    (for [[id label :as row] (read-dannet-2-csv dir "synsets.csv")
          :let [groups (example-groups row)]
          :when groups]
      {:id        id
       :forms     (label-forms label)
       :sense-ids (reduce (fn [m [sense-id word-id]]
                            (update m (forms word-id) (fnil conj #{}) sense-id))
                          {}
                          (senses id))
       :groups    groups})))

(h/defn form-parts
  "The parts of a DanNet 2 word `form` as sets of alternative words, without
  the stress marks and the optional parts in parentheses."
  [form]
  (->> (-> (str/lower-case form)
           (str/replace #"\([^)]*\)|(?<![\p{L}\p{N}])'+" "")
           (str/split #"\s+"))
       (remove str/blank?)
       (map #(set (str/split % #"/")))))

(h/defn inflected-match?
  "True if `example` contains each part of the word `form` in its written form
  or in one of its `inflections`."
  [inflections form example]
  (let [words (set (re-seq #"[\p{L}\p{N}]+(?:['-][\p{L}\p{N}]+)*"
                           (str/lower-case example)))]
    (every? (fn [alternatives]
              (some words (mapcat #(inflections % [%]) alternatives)))
            (form-parts form))))

(h/defn prefix-match?
  "True if a word in `example` starts with each part of the word `form`.

  A final e of a part longer than 3 letters is optional, so that hasselbusk
  matches \"hasselbuske\"."
  [form example]
  (let [example (str/lower-case example)]
    (every? (fn [alternatives]
              (some (fn [word]
                      (let [stem (str/replace word #"(?<=...)e$" "")
                            re   (re-pattern (str "(?<!\\p{L})"
                                                  (Pattern/quote stem)))]
                        (re-find re example)))
                    alternatives))
            (form-parts form))))

(h/defn best-matches
  "The indices of the word `forms` that `match?` the most examples in `group`."
  [match? forms group]
  (let [scores (for [form forms]
                 (count (filter #(match? form %) group)))
        best   (apply max scores)]
    (if (pos? best)
      (set (keep-indexed #(when (= best %2) %1) scores))
      #{})))

(h/defn ordered-choices
  "Every way to choose one of the `candidates` indices per group such that the
  indices increase."
  ([candidates]
   (ordered-choices -1 candidates))
  ([prev [indices & more :as candidates]]
   (if (empty? candidates)
     [[]]
     (for [i      indices
           :when  (> i prev)
           choice (ordered-choices i more)]
       (cons i choice)))))

(h/defn group-word-indices
  "The possible label word indices of each example group of a DanNet 2 synset,
  given its label `forms`, its example `groups` and the `inflections`.

  The groups follow the label order, so a synset with a group for each label
  word needs no matching. Otherwise, the examples are matched against the
  forms, with prefix-match? for a group that inflected-match? cannot match,
  and only increasing indices are possible."
  [inflections forms groups]
  (if (= (count forms) (count groups))
    (map hash-set (range (count forms)))
    (let [inflected? (partial inflected-match? inflections)
          choices    (ordered-choices
                       (for [group groups]
                         (let [indices (best-matches inflected? forms group)]
                           (if (empty? indices)
                             (best-matches prefix-match? forms group)
                             indices))))]
      (for [i (range (count groups))]
        (set (map #(nth % i) choices))))))

(h/defn sense-ids
  "The DanNet 2 sense ids in the name of a current `sense`, e.g. 21016133 and
  21016132 for dn:sense-21016133-i2_21016132-i1."
  [sense]
  (map #(str/replace % #"-i\d+$" "")
       (str/split (subs (name sense) 6) #"_")))

(h/defn current-sense
  "The current sense of the DanNet 2 sense `id` in the DanNet 2 synset
  `synset-id`, given the `senses` of every DanNet 2 sense id and the `synset`
  of every current sense.

  Since 2023, some senses moved to another synset, so a sense outside the
  synset is the result when it is the only candidate."
  [senses synset synset-id id]
  (let [candidates (senses id)
        in-synset  (filter #(= (keyword "dn" (str "synset-" synset-id))
                               (synset %))
                           candidates)]
    (cond
      (= 1 (count in-synset)) (first in-synset)
      (= 1 (count candidates)) (first candidates))))

(h/defn place-dannet-2-examples
  "Place the examples of a DanNet 2 `synset` on current senses as [synset-id
  example senses] tuples, given the `inflections` and a `current-sense` fn.

  The examples of a group get the senses of every label word with the form of
  their label word, since the label does not show which of these senses is
  which. The examples of a group that can belong to label words with
  different forms get no senses."
  [inflections current-sense {:keys [id forms sense-ids groups] :as synset}]
  (for [[indices group] (map vector
                             (group-word-indices inflections forms groups)
                             groups)
        :let [group-forms (set (map forms indices))
              senses      (when (= 1 (count group-forms))
                            (set (keep #(current-sense id %)
                                       (sense-ids (first group-forms)))))]
        example group]
    [id example senses]))

(h/defn normalize-example
  "Normalize the quote marks and the white space of an `example`."
  [example]
  (-> example
      (str/replace #"[\"'\u201C\u201D\u2018\u2019\u00AB\u00BB`\u00B4]" "'")
      (str/replace #"\s+" " ")
      (str/trim)))

(h/defn example-changes
  "The changes to the current `examples`, [sense example] pairs, that apply the
  DanNet 2 `placements`, given the `synset` of every current sense.

  Gives :add and :remove, both [sense example] pairs, and the :tied senses
  that share examples with a sense of the same lemma. Examples are compared
  after normalize-example. A DanNet 2 example on a wrong sense of its synset
  is removed, unless an example that cannot be placed has the same text. The
  other examples lose any surrounding white space."
  [synset examples placements]
  (let [text-key       (fn [[sense example]]
                         [sense (normalize-example (str example))])
        placed         (for [[_ example senses] placements
                             sense senses]
                         [sense example])
        targets        (set (map text-key placed))
        current        (set (map text-key examples))
        placed-texts   (set (for [[sense text] (map text-key placed)]
                              [(synset sense) text]))
        unplaced-texts (set (for [[id example senses] placements
                                  :when (empty? senses)]
                              [(keyword "dn" (str "synset-" id))
                               (normalize-example example)]))
        wrong?         (fn [[sense :as pair]]
                         (let [[_ text :as k] (text-key pair)]
                           (and (placed-texts [(synset sense) text])
                                (not (unplaced-texts [(synset sense) text]))
                                (not (targets k)))))
        padded         (filter (fn [[_ example]]
                                 (not= (str example) (str/trim (str example))))
                               (remove wrong? examples))]
    {:add    (concat (->> placed
                          (remove (comp current text-key))
                          (remove (comp truncated-dannet-2-examples second))
                          (distinct))
                     (for [[sense example] padded]
                       [sense (str/trim (str example))]))
     :remove (concat (filter wrong? examples) padded)
     :tied   (set (for [[_ _ senses] placements
                        :when (next senses)
                        sense senses]
                    sense))}))

(h/defn dannet-2-example-changes
  "The example-changes to the dn: graph of `dataset` that restore the DanNet 2
  examples of the CSV release in `dir`, and the :unplaced examples.

  The word forms in the cor: graph of `dataset` give the inflections."
  [dataset dir]
  (let [g           (db/get-graph dataset prefix/dn-uri)
        inflections (reduce (fn [m {:syms [?lemma ?rep]}]
                              (let [lemma (str/lower-case ?lemma)]
                                (update m lemma (fnil conj #{lemma})
                                        (str/lower-case ?rep))))
                            {}
                            (q/run (db/get-graph dataset prefix/cor-uri)
                                   op/cor-word-forms))
        synset      (->> '[:bgp [?synset :ontolex/lexicalizedSense ?sense]]
                         (q/run g)
                         (map (juxt '?sense '?synset))
                         (into {}))
        senses      (reduce (fn [m sense]
                              (reduce #(update %1 %2 (fnil conj #{}) sense)
                                      m (sense-ids sense)))
                            {}
                            (keys synset))
        placements  (mapcat (partial place-dannet-2-examples inflections
                                     (partial current-sense senses synset))
                            (dannet-2-example-synsets dir))
        examples    (->> '[:bgp [?sense :lexinfo/senseExample ?example]]
                         (q/run g)
                         (map (juxt '?sense '?example)))]
    (assoc (example-changes synset examples placements)
      :unplaced (filter (comp empty? last) placements))))

(h/defn restore-dannet-2-examples!
  "Restore the DanNet 2 usage examples that the 2023 conversion lost or put on
  a wrong sense in the dn: graph of `dataset`.

  The 2023 conversion matched each example to the first label word of its
  synset that occurs in it, as a substring. It kept only the last example per
  synset and word, so 19,629 examples are missing from their senses. A
  substring such as ask in asketræ put 573 examples on a wrong sense. The
  examples of a group that can belong to several senses of the same lemma go
  to all 48 of these senses, with an rdfs:comment. The 109 examples that fit
  different lemmas, or no lemma, stay out. The white space around 49 examples
  is also removed.

  The counts are asserted so that a future bootstrap dataset silently growing
  or shrinking this set fails loudly instead."
  [dataset]
  (t/log! {:level :info
           :id    :dannet.bootstrap/restore-dannet-2-examples}
          "Restoring the DanNet 2 usage examples")
  (let [g        (db/get-graph dataset prefix/dn-uri)
        model    (db/get-model dataset prefix/dn-uri)
        comments [(md/en "DanNet 2 gave the examples of this sense for its"
                         " lemma and not for the sense. This synset has more"
                         " than one sense of the lemma, so each example can"
                         " belong to another of these senses.")
                  (md/da "DanNet 2 angav betydningseksemplerne for denne"
                         " betydning ud fra lemmaet og ikke ud fra betydningen."
                         " Dette synset har mere end én betydning af lemmaet,"
                         " så hvert eksempel kan høre til en anden af disse"
                         " betydninger.")]
        {:keys [add remove tied] :as changes} (dannet-2-example-changes
                                                dataset dannet-2-csv-dir)
        counts   (update-vals changes count)]
    (assert (= {:add 19678 :remove 622 :tied 48 :unplaced 109} counts)
            (str "unexpected example changes " counts))
    (t/log! {:level :info
             :id    :dannet.bootstrap/dannet-2-example-changes
             :data  counts}
            "Changing the DanNet 2 usage examples")
    (txn/transact-exec model
      (doseq [[sense example] remove]
        (db/remove! model [sense :lexinfo/senseExample example])))
    (txn/transact-exec g
      (db/safe-add! g (concat
                        (for [[sense example] add]
                          [sense :lexinfo/senseExample (md/da example)])
                        (for [sense tied
                              comment comments]
                          [sense :rdfs/comment comment]))))))

(h/defn move-renamed-synset-ili!
  "Move the wn:ili of dn:synset-78106 in `dataset` to dn:synset-78881, the id
  that DanNet 2.5 gave the same {dumdristig} synset, and let the new id
  subsume the old one.

  The 2023 import of the DanNet 2.2 links to Princeton WordNet put the ILI on
  the 2.2 id, which has no other data; DanNet 2.5 dropped the link of the new
  id. The ILI matches the old link to reckless%5:00:00:bold:00."
  [dataset]
  (t/log! {:level :info
           :id    :dannet.bootstrap/move-renamed-synset-ili}
          "Moving the ILI of a renamed DanNet 2.2 synset")
  (let [g        (db/get-graph dataset prefix/dn-uri)
        model    (db/get-model dataset prefix/dn-uri)
        old      :dn/synset-78106
        new      :dn/synset-78881
        ilis     (map '?ili (q/run g [:bgp [old :wn/ili '?ili]]))
        expected 1]
    (assert (= expected (count ilis))
            (str "expected " expected " ILI to move, found " (count ilis)))
    (txn/transact-exec model
      (db/remove! model [old '_ '_]))
    (txn/transact-exec g
      (db/safe-add! g (cons [new :dns/subsumed old]
                            (for [ili ilis]
                              [new :wn/ili ili]))))))

(h/defn add-ili-eq-synonyms!
  "Add a wn:eq_synonym from each dn: synset in `dataset` to the OEWN synset
  that carries its wn:ili concept, when the link is unambiguous: the synset
  has exactly one wn:ili, no wn:eq_synonym yet, and no other eq* relation to
  that OEWN synset.

  The links to the OEWN then match the links to the ILI. Most of the new ones
  belong to the 2023 links to the CILI, the rest to the DanNet 2.2 links by
  ENG20 offset, which the 2023 conversion could only map to the ILI. 245
  links are left for review: 233 of synsets with several wn:ili and 12 of
  synsets whose wn:eq_synonym points elsewhere (see the SHACL shapes
  dns:LexicalConceptShape-ili and dns:IliEqSynonymShape). A concept that no
  single OEWN synset carries (e.g. the placeholder ili:in) is skipped, and so
  is a wn:ili on a resource that is not a synset: the stubs of 5 duplicates
  that subsume-removed-duplicates! removes.

  Must run after add-open-english-wordnet!, which supplies the ILI -> synset
  mapping, and after move-renamed-synset-ili!."
  [dataset]
  (let [ili->oewn (->> (q/run (db/get-graph dataset prefix/oewn-uri)
                              '[:bgp [?synset :wn/ili ?ili]])
                       (group-by '?ili)
                       (into {} (keep (fn [[ili ms]]
                                        (when (= 1 (count ms))
                                          [ili (get (first ms) '?synset)])))))
        g         (db/get-graph dataset prefix/dn-uri)
        linked    (set (for [p [:wn/eq_synonym :dns/eqHypernym :dns/eqHyponym
                                :dns/eqSimilar]
                             {:syms [?s ?o]} (q/run g [:bgp ['?s p '?o]])]
                         [?s ?o]))
        equated   (set (map '?s (q/run g '[:bgp [?s :wn/eq_synonym ?o]])))
        ili-links (->> (q/run g '[:bgp [?synset :rdf/type :ontolex/LexicalConcept]
                                        [?synset :wn/ili ?ili]])
                       (group-by '?synset))
        triples   (set (for [[synset ms] ili-links
                             :when (and (= 1 (count ms))
                                        (not (equated synset)))
                             :let [oewn-synset (ili->oewn (get (first ms) '?ili))]
                             :when (and oewn-synset
                                        (not (linked [synset oewn-synset])))]
                         [synset :wn/eq_synonym oewn-synset]))
        expected  3644]
    (t/log! {:level :info
             :id    :dannet.bootstrap/add-ili-eq-synonyms
             :data  {:triples (count triples)}}
            "Adding wn:eq_synonym links that match the wn:ili links")
    (assert (= expected (count triples))
            (str "expected " expected " wn:eq_synonym triples, found "
                 (count triples)))
    (txn/transact-exec g
      (db/safe-add! g triples))))

(h/defn register-triples
  "The triples of the DanNet 2 `register` text of `sense`, the way the 2023
  conversion made them: a usage note with the text, plus the dating,
  frequency or register value that its abbreviations name."
  [sense register]
  (cond-> #{[sense :lexinfo/usageNote (md/da register)]}
    (str/includes? register "gl.") (conj [sense :lexinfo/dating :lexinfo/old])
    (str/includes? register "sj.") (conj [sense :lexinfo/frequency :lexinfo/rarelyUsed])
    (str/includes? register "jargon") (conj [sense :lexinfo/register :lexinfo/inHouseRegister])
    (str/includes? register "slang") (conj [sense :lexinfo/register :lexinfo/slangRegister])))

(h/defn restore-dannet-2-registers!
  "Restore the DanNet 2 register of the senses in `dataset` that DSL split
  into -i1, -i2, ... readings, one per synset.

  The 2023 conversion took these readings from DSL's own data, which has no
  register, so 41 of them lost the register that DanNet 2 gave the sense
  before the split. Each DanNet 2.5.1 wordsenses.csv row names a sense and a
  synset; the reading of that sense in that synset gets the register, unless
  it already has a usage note."
  [dataset]
  (t/log! {:level :info
           :id    :dannet.bootstrap/restore-dannet-2-registers}
          "Restoring the DanNet 2 register of split senses")
  (let [g        (db/get-graph dataset prefix/dn-uri)
        senses   (reduce (fn [m {:syms [?synset ?sense]}] (update m ?synset conj ?sense))
                         {} (q/run g '[:bgp [?synset :ontolex/lexicalizedSense ?sense]]))
        noted    (set (map '?s (q/run g '[:bgp [?s :lexinfo/usageNote ?note]])))
        triples  (set (for [[id _ synset register] (read-dannet-2-csv dannet-2-csv-dir
                                                                       "wordsenses.csv")
                            :when (not (str/blank? register))
                            :let [reading (re-pattern (str "sense-" id "-i\\d+"))]
                            sense (get senses (keyword "dn" (str "synset-" synset)))
                            :when (and (re-matches reading (name sense))
                                       (not (noted sense)))
                            triple (register-triples sense register)]
                        triple))
        restored (count (distinct (map first triples)))
        expected 41]
    (assert (= expected restored)
            (str "expected " expected " senses to restore, found " restored))
    (txn/transact-exec g
      (db/safe-add! g triples))))

(h/defn retarget-dangling-sentiments!
  "Move the DDS sentiments in `dataset` of dn: senses that no longer exist to
  the senses that replaced them.

  A sense split into -i1, -i2, ... readings passes its sentiment on to each
  reading. Of two senses merged into one, the sense named first in the merged
  id passes its sentiment on, so that the merged sense has one opinion; the
  sentiment of the other is removed. The two agree in 4 of the 5 merged pairs."
  [dataset]
  (t/log! {:level :info
           :id    :dannet.bootstrap/retarget-dangling-sentiments}
          "Moving the sentiments of senses that no longer exist")
  (let [dn-g      (db/get-graph dataset prefix/dn-uri)
        dds-g     (db/get-graph dataset prefix/dds-uri)
        model     (db/get-model dataset prefix/dds-uri)
        senses    (set (map '?s (q/run dn-g '[:bgp [?s :rdf/type :ontolex/LexicalSense]])))
        subsumer  (into {} (map (juxt '?old '?s))
                        (q/run dn-g '[:bgp [?s :dns/subsumed ?old]]))
        readings  (fn [s]
                    (let [reading (re-pattern (str (name s) "-i\\d+"))]
                      (filter #(re-matches reading (name %)) senses)))
        targets   (fn [s]
                    (or (seq (readings s))
                        (when-let [merged (subsumer s)]
                          (when (str/starts-with? (name merged) (str (name s) "_"))
                            [merged]))))
        moves     (into {} (for [{:syms [?s]} (q/run dds-g '[:bgp [?s :dns/sentiment ?o]])
                                 :when (and (= "dn" (namespace ?s))
                                            (str/starts-with? (name ?s) "sense-")
                                            (not (senses ?s)))]
                             [?s (vec (targets ?s))]))
        counts    {:dangling (count moves)
                   :moved    (count (mapcat val moves))}
        expected  {:dangling 18 :moved 20}
        resource  #(.getResource model (prefix/kw->uri %))
        sentiment (.getProperty model (prefix/kw->uri :dns/sentiment))]
    (assert (= expected counts)
            (str "expected sentiment moves " expected ", found " counts))
    (txn/transact-exec model
      (doseq [[s targets] moves
              stmt (vec (iterator-seq (.listProperties (resource s) sentiment)))
              :let [opinion (.getObject stmt)]]
        (doseq [target targets]
          (.add model (resource target) sentiment opinion))
        (when (empty? targets)
          (.removeAll model (.asResource opinion) nil nil))
        (.remove model stmt)))))

(def collapsed-readings
  "The merged senses that took the readings of another synset, each with that
  synset and the suffix of its readings, as the label of the synset still
  shows, e.g. {Nordkorea_§1(2)} for -i2."
  {:dn/sense-23000165-i1_23000318-i2_23000165-i2_23000318-i1 [:dn/synset-9239 "i2"]
   :dn/sense-23000302-i1_23000269-i1_23000269-i2_23000302-i2 [:dn/synset-61007 "i2"]
   :dn/sense-23000047-i2_23000303-i2_23000047-i1_23000303-i1 [:dn/synset-9121 "i2"]
   :dn/sense-21016133-i2_21016133-i1_21016132-i1_21016132-i2 [:dn/synset-42830 "i1"]})

(h/defn restore-collapsed-readings!
  "Give the synsets in `collapsed-readings` back the readings that a merge of
  duplicate senses moved to another synset in `dataset`.

  DSL splits a sense of two synsets into -i1 and -i2 readings, e.g. a country
  name for the country and for its population. A later merge of duplicate
  senses joined these readings into one sense of one synset. That left the
  population synsets of {Nordkorea}, {Congo} and {Republikken Congo} without
  senses, and {besiddelse; eje; ejendom} without eje. Each synset gets one
  sense again, with the ids, labels, DDO sense ids and sources of its own
  readings. The merged sense keeps the other readings."
  [dataset]
  (t/log! {:level :info
           :id    :dannet.bootstrap/restore-collapsed-readings}
          "Restoring readings that a merge of duplicate senses moved")
  (let [g        (db/get-graph dataset prefix/dn-uri)
        model    (db/get-model dataset prefix/dn-uri)
        changes  (for [[merged [synset suffix]] collapsed-readings
                       :let [triples  (q/run g [:bgp [merged '?p '?o]])
                             objects  (fn [p] (keep #(when (= p (get % '?p)) (get % '?o))
                                                    triples))
                             index    (subs suffix 1)
                             swap     (fn [label i] (str/replace (str label) #"\(\d\)$"
                                                                 (str "(" i ")")))
                             label    (first (objects :rdfs/label))
                             own?     #(str/ends-with? (str %) (str "(" index ")"))
                             alts     (objects :skos/altLabel)
                             own      (filter own? (cons label alts))
                             readings (filter #(str/ends-with? (name %) (str "-" suffix))
                                              (objects :dns/subsumed))
                             ids      (sort (map #(subs (name %) (count "sense-")) readings))
                             sense    (keyword "dn" (str "sense-" (str/join "_" ids)))
                             main     (swap label index)
                             kept     (remove own? alts)
                             kept'    (when (own? label)
                                        (first (filter #(= (swap label (if (= index "1") 2 1))
                                                           (str %))
                                                       kept)))
                             word     (some '?w (q/run g [:bgp ['?w :ontolex/sense merged]]))]]
                   {:remove (concat (for [l (filter own? alts)] [merged :skos/altLabel l])
                                    (for [r readings] [merged :dns/subsumed r])
                                    (when kept'
                                      [[merged :rdfs/label label]
                                       [merged :skos/altLabel kept']]))
                    :add    (concat [[sense :rdf/type :ontolex/LexicalSense]
                                     [sense :rdfs/label (md/da main)]
                                     [word :ontolex/sense sense]
                                     [synset :ontolex/lexicalizedSense sense]]
                                    (for [l own :when (not= main (str l))]
                                      [sense :skos/altLabel (md/da (str l))])
                                    (for [r readings] [sense :dns/subsumed r])
                                    (for [p [:dns/dslSense :dns/source]
                                          o (objects p)]
                                      [sense p o])
                                    (when kept'
                                      [[merged :rdfs/label (md/da (str kept'))]]))
                    :relabel (when kept' [label kept'])})
        expected 4]
    (assert (= expected (count changes))
            (str "expected " expected " synsets to restore, found " (count changes)))
    (txn/transact-exec model
      (doseq [triple (mapcat :remove changes)]
        (db/remove! model triple)))
    (txn/transact-exec g
      (db/safe-add! g (mapcat :add changes)))
    ;; The synset of a merged sense whose main label moved to the restored
    ;; sense names the label in its own label too.
    (doseq [[old new] (keep :relabel changes)]
      (db/update-triples! prefix/dn-uri dataset
                          [:bgp ['?synset :ontolex/lexicalizedSense '?sense]
                           ['?sense :rdfs/label new]
                           ['?synset :rdfs/label '?label]]
        (fn [{:syms [?synset ?label]}]
          [?synset :rdfs/label (md/da (str/replace (str ?label) (str old) (str new)))])
        (fn [{:syms [?synset ?label]}]
          [?synset :rdfs/label ?label])))))

(h/defn restore-at-sign-synsets!
  "Restore the definition and ontological type of the dn: synsets in `dataset`
  whose row in the DanNet 2.5.1 synsets.csv the 2023 conversion skipped.

  The gloss of {e-maile_1; maile_1} has an e-mail address in an example, and
  its @ is also the field separator of the file, so the row has one field
  too many. The 2023 conversion skipped rows of the wrong length; the
  examples of the synset are back since restore-dannet-2-examples!."
  [dataset]
  (t/log! {:level :info
           :id    :dannet.bootstrap/restore-at-sign-synsets}
          "Restoring synsets whose DanNet 2 row has an @ in its gloss")
  (let [g        (db/get-graph dataset prefix/dn-uri)
        defined  (set (map '?s (q/run g '[:bgp [?s :skos/definition ?d]])))
        triples  (for [row (read-dannet-2-csv dannet-2-csv-dir "synsets.csv")
                       :let [n      (count row)
                             synset (keyword "dn" (str "synset-" (first row)))]
                       :when (and (> n 5) (not (defined synset)))
                       :let [gloss      (str/join "@" (subvec row 2 (- n 2)))
                             definition (-> gloss
                                            (str/replace #"\s*\(Brug: .*$" "")
                                            (str/replace " ..." "…")
                                            (str/trim))
                             [ontotype type-triples] (corsem/->ontotype
                                                       (corsem/ontotype-atoms
                                                         (nth row (- n 2))))]]
                   (into [[synset :skos/definition (md/da definition)]
                          [synset :dns/ontologicalType ontotype]]
                         type-triples))
        expected 1]
    (assert (= expected (count triples))
            (str "expected " expected " synsets to restore, found " (count triples)))
    (txn/transact-exec g
      (db/safe-add! g (apply concat triples)))))

(def duplicates-removed-in-2023
  "The DanNet 2 synsets that the 2023 conversion removed as duplicates
  (GitHub issue #46), each with the synset that has the same definition and
  kept the relations."
  {"645"  "568"  "668"  "667"  "679"  "582"  "680"  "603"
   "681"  "631"  "684"  "566"  "686"  "591"  "693"  "623"
   "695"  "572"  "704"  "624"  "710"  "706"  "711"  "707"
   "712"  "708"  "713"  "709"  "747"  "581"  "751"  "610"
   "769"  "576"  "774"  "558"  "775"  "560"  "800"  "586"
   "801"  "587"  "802"  "601"  "807"  "571"  "809"  "579"
   "842"  "605"  "843"  "617"  "846"  "595"  "850"  "580"
   "873"  "588"  "884"  "602"  "888"  "621"  "890"  "561"
   "902"  "612"  "904"  "596"  "905"  "630"  "913"  "632"
   "915"  "613"  "936"  "935"  "937"  "609"  "947"  "629"
   "950"  "949"  "953"  "614"  "962"  "585"  "974"  "620"
   "1337" "1336" "1358" "1357" "1412" "1411" "1413" "1411"
   "1558" "1557" "1559" "1557" "69723" "51871" "69724" "51874"})

(def duplicates-merged-in-2026
  "The synsets that the release of 2026-08-21 merged into a synset sharing
  their sense (GitHub issue #209, and {tinglysningskontor}), each with the
  synset that took over their relations."
  {"11213"        "11203"        "47681" "47680" "47745" "47744"
   "47767"        "16942"        "47839" "17376" "47923" "17097"
   "47954"        "16700"        "48025" "16721" "48184" "48286"
   "s51004129-d1" "s51004129-d2"
   "s53002587-d1" "s53002587-d2"})

(h/defn subsume-removed-duplicates!
  "Link each synset in `dataset` that took over a removed duplicate to the id
  of that duplicate with dns:subsumed, so that the old id still leads to the
  concept; see duplicates-removed-in-2023 and duplicates-merged-in-2026.

  The 2023 import of the DanNet 2 domain codes also gave the ids removed in
  2023 a dc:subject, so each became a stub with only that and skos:inScheme;
  the stubs are removed. 5 stubs also have a wn:ili and 1 a dns:eqHypernym,
  which their twin has too; these go with the stub, so the 5 ILIs are no
  longer shared. The merges of 2026 left nothing behind."
  [dataset]
  (t/log! {:level :info
           :id    :dannet.bootstrap/subsume-removed-duplicates}
          "Linking the removed duplicates to the synsets that took them over")
  (let [g        (db/get-graph dataset prefix/dn-uri)
        model    (db/get-model dataset prefix/dn-uri)
        synset   #(keyword "dn" (str "synset-" %))
        typed    (set (map '?s (q/run g '[:bgp [?s :rdf/type :ontolex/LexicalConcept]])))
        left     (fn [old] (seq (q/run g [:bgp [(synset old) '?p '?o]])))
        links    (for [[old twin] (merge duplicates-removed-in-2023
                                         duplicates-merged-in-2026)
                       :when (and (not (typed (synset old)))
                                  (typed (synset twin)))]
                   [(synset old) (synset twin)])
        stubs    (filter left (keys duplicates-removed-in-2023))
        counts   {:links (count links) :stubs (count stubs)}
        expected {:links 63 :stubs 52}]
    (assert (= expected counts)
            (str "expected duplicates " expected ", found " counts))
    (txn/transact-exec model
      (doseq [old stubs]
        (db/remove! model [(synset old) '_ '_])))
    (txn/transact-exec g
      (db/safe-add! g (for [[old twin] links]
                        [twin :dns/subsumed old])))))

(h/defn unstressed
  "Remove the DanNet 2 stress marks from `s`: one or two apostrophes before a
  word, e.g. \"køre ''med (på)\", or by mistake one after it, \"se' ud over\"."
  [s]
  (str/replace s #"(?<=^|[\s/(])'+|(?<=\p{L})'(?=\s)" ""))

(h/defn stressed
  "Write the DanNet 2 form `s` with the stress sign ˈ of Den Danske Ordbog in
  place of two apostrophes, e.g. \"se ˈtil\" for \"se ''til\".

  Single apostrophes are removed, as DDO does not show that stress."
  [s]
  (unstressed (str/replace s #"(?<=^|[\s/(])''" "ˈ")))

(h/defn form-variants
  "The forms that the DanNet 2 `form` gives with a slash, e.g. \"blive ''væk\"
  and \"blive ''borte\" for \"blive ''væk/''borte\"."
  [form]
  (if-let [block (re-find #"\S+/\S+" form)]
    (map #(str/replace form block %) (str/split block #"/"))
    [form]))

(h/defn restore-stress-marks!
  "Remove the DanNet 2 stress marks that remain in the labels and written forms
  of `dataset`, and give each form that Den Danske Ordbog shows with a stress
  sign a dns:stressedRep, e.g. \"se ˈtil\" next to the writtenRep \"se til\".

  DanNet 2 marks the stressed word of a multiword form with apostrophes from
  DDO: two for the stress that DDO shows, one for stress that it does not
  show. DDO shows it mostly where the stress separates two expressions with
  the same spelling, e.g. \"se til\" and \"se ˈtil\". The 2023 conversion
  removed the marks after a space only, so they remain after a slash, e.g. in
  \"rive løs/'fri\"."
  [dataset]
  (t/log! {:level :info
           :id    :dannet.bootstrap/restore-stress-marks}
          "Restoring the stress marks of DanNet 2 forms")
  (let [model    (db/get-model dataset prefix/dn-uri)
        prop     #(.getProperty model (prefix/kw->uri %))
        rep      (prop :ontolex/writtenRep)
        forms    (fn [id]
                   (let [word (.getResource model (prefix/kw->uri
                                                    (keyword "dn" (str "word-" id))))]
                     (for [p    [:ontolex/canonicalForm :ontolex/otherForm]
                           stmt (iterator-seq (.listProperties word (prop p)))]
                       (.getResource stmt))))
        marked   (txn/transact model
                   (vec (for [p    [(prop :rdfs/label) rep]
                              stmt (iterator-seq (.listStatements model nil p nil))
                              :when (not= (.getString stmt)
                                          (unstressed (.getString stmt)))]
                          stmt)))
        stresses (txn/transact model
                   (vec (for [[id form] (read-dannet-2-csv dannet-2-csv-dir "words.csv")
                              :when (str/includes? form "''")
                              variant (form-variants form)
                              f       (forms id)
                              stmt    (iterator-seq (.listProperties f rep))
                              :when (= (unstressed variant)
                                       (unstressed (.getString stmt)))]
                          [f (stressed variant)])))
        counts   {:unmarked (count marked) :stressed (count stresses)}
        expected {:unmarked 76 :stressed 76}]
    (assert (= expected counts)
            (str "expected stress marks " expected ", found " counts))
    (txn/transact-exec model
      (doseq [stmt marked
              :let [lit (.getLiteral stmt)]]
        (.remove model stmt)
        (.add model (.getSubject stmt) (.getPredicate stmt)
              (.createLiteral model (unstressed (.getString lit)) (.getLanguage lit))))
      (doseq [[f s] stresses]
        (.add model f (prop :dns/stressedRep) (.createLiteral model s "da"))))))

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
    (replace-negated-relations! dataset)
    (restore-dannet-2-examples! dataset)
    (move-renamed-synset-ili! dataset)
    ;; Deliberately omits the 245 ambiguous ILI links (several wn:ili, or a
    ;; wn:eq_synonym elsewhere); SHACL shapes list them for review.
    (add-ili-eq-synonyms! dataset)
    (restore-dannet-2-registers! dataset)
    (retarget-dangling-sentiments! dataset)
    (restore-collapsed-readings! dataset)
    (restore-at-sign-synsets! dataset)
    (subsume-removed-duplicates! dataset)
    ;; Deliberately omits the 963 forms with single stress marks only: DDO does
    ;; not show this predictable stress, e.g. on the particle in "slå op".
    (restore-stress-marks! dataset)

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
      ;; real inference cost is realized later, on first traversal; see the
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
                            (:hash (meta #'add-framenet!))
                            (:hash (meta #'premon/premon-index))
                            (:hash (meta #'premon/source-triples))
                            (hash premon/fe-status)
                            (hash premon/frame-relations)
                            (hash premon/fe-relations)
                            (:hash (meta #'make-release-changes!))
                            (:hash (meta #'move-renamed-synset-ili!))
                            (:hash (meta #'add-ili-eq-synonyms!))
                            (:hash (meta #'register-triples))
                            (:hash (meta #'restore-dannet-2-registers!))
                            (:hash (meta #'retarget-dangling-sentiments!))
                            (hash collapsed-readings)
                            (:hash (meta #'restore-collapsed-readings!))
                            (:hash (meta #'restore-at-sign-synsets!))
                            (hash duplicates-removed-in-2023)
                            (hash duplicates-merged-in-2026)
                            (:hash (meta #'subsume-removed-duplicates!))
                            (:hash (meta #'unstressed))
                            (:hash (meta #'stressed))
                            (:hash (meta #'form-variants))
                            (:hash (meta #'restore-stress-marks!))
                            (:hash (meta #'synset-label))
                            (:hash (meta #'add-in-scheme!))
                            (:hash (meta #'regenerate-short-labels!))
                            (:hash (meta #'replace-negated-relations!))
                            (:hash (meta #'dannet-2-negations))
                            (:hash (meta #'negation-triples))
                            (:hash (meta #'remove-inheritance-source!))
                            (hash negated-relation-predicates)
                            (:hash (meta #'restore-dannet-2-examples!))
                            (:hash (meta #'dannet-2-example-changes))
                            (:hash (meta #'example-changes))
                            (:hash (meta #'normalize-example))
                            (:hash (meta #'place-dannet-2-examples))
                            (:hash (meta #'current-sense))
                            (:hash (meta #'sense-ids))
                            (:hash (meta #'group-word-indices))
                            (:hash (meta #'ordered-choices))
                            (:hash (meta #'best-matches))
                            (:hash (meta #'prefix-match?))
                            (:hash (meta #'inflected-match?))
                            (:hash (meta #'form-parts))
                            (:hash (meta #'dannet-2-example-synsets))
                            (:hash (meta #'label-forms))
                            (:hash (meta #'example-groups))
                            (:hash (meta #'read-dannet-2-csv))
                            (hash truncated-dannet-2-examples)
                            (:hash (meta #'md/add-dataset-statistics!))
                            (:hash (meta #'md/metadata))
                            (:hash (meta #'md/update-metadata!))
                            (:hash (meta #'->dannet))
                            (hash prefix/schemas)
                            ;; The emitted version is baked into the dataset
                            ;; metadata but isn't captured by any hashed form
                            ;; above (those hash source, not values), so include
                            ;; it explicitly -- otherwise cutting a release
                            ;; (:to bumped away from :from) wouldn't change
                            ;; db-name and the stale database would be reused.
                            release/to
                            ;; The OEWN edition is likewise only a value: its
                            ;; ttl isn't among the hashed input zips, so it is
                            ;; included explicitly; otherwise bumping the
                            ;; edition wouldn't trigger a rebuild. The same
                            ;; goes for the PreMOn release behind the framenet
                            ;; graph.
                            downloads/oewn-version
                            release/premon-version
                            ;; The COR editions likewise: they only figure as
                            ;; values, in the dc:hasVersion dataset metadata.
                            release/cor-version
                            release/cor-ext-version
                            release/cor-sem-version]
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
            metadata'      (update (md/metadata) 'dn conj [md/<dn> :dns/build db-name])]
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
                ;; separator, so output-parent must be a dir path ending in "/";
                ;; otherwise entries get the zip's own path prepended (e.g.
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
              ;; It is imported BEFORE the release changes, some of which may
              ;; need the OEWN graph for lookups.
              (add-open-english-wordnet! dataset)

              ;; The FrameNet frame inventory likewise arrives by download
              ;; rather than as part of our own export; the dns:frame links
              ;; added by the release changes below resolve against it.
              (add-framenet! dataset)

              ;; Effectuate changes for the current release.
              ;; These are always tied to the current release and depend on the
              ;; former release, i.e. the contents of this function is versioned
              ;; together with every single formal release.
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
                "No input dir provided; reusing existing database from log")
        (dataset->db dataset schema-uris)))))
