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
           [org.apache.jena.query Dataset DatasetFactory]
           [org.apache.jena.rdf.model Model ModelFactory ResourceFactory]
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
        (md/update-metadata! (get md/metadata 'frame) model)))))

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
  cor-sem: graph, so this must run after `add-cor-sem-graph!`."
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

(h/defn name-ontological-types!
  "Replace the anonymous rdf:Bag values of dns:ontologicalType in the dn:
  graph of `dataset` with named dnt: ontological types: the same bags,
  deduplicated and named after their sorted atoms, so that ontological types
  can be linked, browsed, and compared, in particular against the COR.SEM
  simple ontotypes, which share the dnt: resources whenever the atom sets
  coincide."
  [dataset]
  (let [dn-graph     (db/get-graph dataset prefix/dn-uri)
        dn-model     (db/get-model dataset prefix/dn-uri)
        rows         (q/run dn-graph '[:bgp
                                       [?syn :dns/ontologicalType ?bag]
                                       [?bag ?p ?atom]])
        replacements (for [[bag rows] (group-by '?bag rows)
                           :when (symbol? bag)              ; blank nodes only
                           :let [atoms (into #{}
                                             (keep (fn [{:syms [?p ?atom]}]
                                                     (when (shared/member-property? ?p)
                                                       ?atom)))
                                             rows)]
                           :when (seq atoms)]
                       (conj (corsem/->ontotype atoms)
                             (into #{} (map '?syn) rows)))
        ontotypes    (into #{} (map first) replacements)
        otype-prop   (ResourceFactory/createProperty
                       (prefix/kw->uri :dns/ontologicalType))]
    (t/log! {:level :info
             :id    :dannet.bootstrap/name-ontological-types
             :data  {:bags (count replacements) :ontotypes (count ontotypes)}}
            "Naming the ontological type bags")
    ;; The blank bags fall outside db/remove!'s pattern language, so removal
    ;; happens through the Jena API instead.
    (txn/transact-exec dn-model
      (doseq [stmt (vec (iterator-seq (.listStatements dn-model nil otype-prop nil)))
              :let [bag (.getObject stmt)]
              :when (.isAnon bag)]
        (.removeAll dn-model (.asResource bag) nil nil)
        (.remove dn-model stmt)))
    (txn/transact-exec dn-graph
      (db/safe-add! dn-graph (into #{}
                                   (mapcat (fn [[ontotype triples syns]]
                                             (into triples
                                                   (map (fn [syn]
                                                          [syn :dns/ontologicalType ontotype]))
                                                   syns)))
                                   replacements)))))

(h/defn rename-cor-sense-links!
  "Reassert the cor: graph's links to DanNet senses in `dataset` (originally
  [cor-word ontolex:sense dn-sense] from DSL's link files) as the
  dns:linkedSense subproperty, keeping the links into DanNet's sense inventory
  apart from COR's own senses (COR.SEM); the word-level counterpart of
  dns:linkedSynset. Inference still entails ontolex:sense for interop."
  [dataset]
  (let [cor-graph (db/get-graph dataset prefix/cor-uri)
        cor-model (db/get-model dataset prefix/cor-uri)
        links     (q/run cor-graph '[:bgp [?w :ontolex/sense ?s]])]
    (t/log! {:level :info
             :id    :dannet.bootstrap/rename-cor-sense-links
             :data  {:count (count links)}}
            "Renaming COR sense links to dns:linkedSense")
    (txn/transact-exec cor-model
      (db/remove! cor-model '[_ :ontolex/sense _]))
    (txn/transact-exec cor-graph
      (db/safe-add! cor-graph (map (fn [{:syms [?w ?s]}]
                                     [?w :dns/linkedSense ?s])
                                   links)))))

(h/defn sense-correspondences
  "Compute the sense-level SKOS matches linking the COR.SEM senses of the
  fixed link `triples` to the DanNet senses of `dn-graph`, joining the two
  inventories on shared word (via the cor: words' owl:sameAs links in
  `cor-graph`) and shared synset.

  A pairing unique in both directions is a dns:eqSense; the rest are
  dns:eqNearSense, i.e. COR.SEM merging DDO senses that DanNet keeps apart or
  splitting apart senses that DanNet merged. Both properties are self-inverse
  subproperties of the SKOS matches, so dn: sense pages show the matches back
  by inference. Pairs whose source URLs disagree on the DDO entry_id are
  returned under :mismatched instead of asserted; a missing source on either
  side is not counted as disagreement."
  [dn-graph cor-graph triples]
  (let [links          (reduce (fn [acc [s p o]]
                                 (case p
                                   :ontolex/sense
                                   (update-in acc [:words o] (fnil conj #{}) s)

                                   :dns/linkedSynset
                                   (update-in acc [:synsets s] (fnil conj #{}) o)

                                   :dns/source
                                   (assoc-in acc [:entry s] (q/ddo-entry-id o))

                                   acc))
                               {} triples)
        cor->dn-words  (reduce (fn [acc {:syms [?cw ?dw]}]
                                 (if (keyword? ?dw)
                                   (update acc ?cw (fnil conj #{}) ?dw)
                                   acc))
                               {} (q/run cor-graph '[:bgp [?cw :owl/sameAs ?dw]]))
        dn-sense->syns (reduce (fn [acc {:syms [?syn ?s]}]
                                 (update acc ?s (fnil conj #{}) ?syn))
                               {} (q/run dn-graph '[:bgp [?syn :ontolex/lexicalizedSense ?s]]))
        dn-index       (reduce (fn [acc {:syms [?w ?s]}]
                                 (reduce #(update %1 [?w %2] (fnil conj #{}) ?s)
                                         acc (dn-sense->syns ?s)))
                               {} (q/run dn-graph '[:bgp [?w :ontolex/sense ?s]]))
        dn-entries     (reduce (fn [acc {:syms [?s ?url]}]
                                 (update acc ?s (fnil conj #{}) (q/ddo-entry-id ?url)))
                               {} (q/run dn-graph '[:bgp [?s :dns/source ?url]]))
        entry-ok?      (fn [[sem ds]]
                         (let [e  (get-in links [:entry sem])
                               es (dn-entries ds)]
                           (or (nil? e) (empty? es) (contains? es e))))
        raw-pairs      (set (for [[sem syns] (:synsets links)
                                  :let [dws (into #{}
                                                  (mapcat #(cor->dn-words % #{}))
                                                  (get-in links [:words sem] #{}))]
                                  syn syns
                                  ds (into #{} (mapcat #(dn-index [% syn] #{})) dws)]
                              [sem ds]))
        {ok-pairs  true
         bad-pairs false} (group-by entry-ok? raw-pairs)
        pairs          (set ok-pairs)
        sem-degree     (frequencies (map first pairs))
        dn-degree      (frequencies (map second pairs))
        exact?         (fn [[sem ds]]
                         (and (= 1 (sem-degree sem)) (= 1 (dn-degree ds))))]
    {:exact      (set (filter exact? pairs))
     :close      (set (remove exact? pairs))
     :mismatched (set bad-pairs)}))

;; TODO: the alternation links between two DanNet synsets currently ship only
;;       with the COR.SEM dataset and show up only on the website; decide
;;       whether they should also be part of the DanNet downloads (the CSV
;;       and DMLex exports read the dn: graph alone and so skip them).
(h/defn sense-alternations
  "Compute the sense alternation pairs derivable from the fixed link
  `triples`: the two senses of a word sharing a systematic polysemy pattern,
  and the two distinct DanNet synsets such senses link unambiguously.

  Words with more than two senses in the same pattern are skipped (the
  pairing is ambiguous), as are synsets when either sense links no synset,
  several, or the two senses link the same one.

  A pair is ordered by the reading order of the pattern's name (asserted as
  dns:alternatesTo) where ontotype discrimination decides it: exactly one
  member's ontological type contains a reading's concept, the second reading
  taking precedence over the first. The remaining pairs come sorted and
  undirected under :plain (dns:alternatesWith); synset pairs whose two sense
  pairs order both ways are demoted to :plain."
  [triples]
  (let [links        (reduce (fn [acc [s p o]]
                               (cond
                                 (= p :ontolex/sense)
                                 (update-in acc [:senses o] (fnil conj #{}) s)

                                 (= p :dns/polysemyPattern)
                                 (update-in acc [:patterns s] (fnil conj #{}) o)

                                 (= p :dns/linkedSynset)
                                 (update-in acc [:synsets s] (fnil conj #{}) o)

                                 (= p :dns/simpleOntologicalType)
                                 (update-in acc [:stypes s] (fnil conj #{}) o)

                                 (and (shared/member-property? p)
                                      (keyword? s)
                                      (= "dnt" (namespace s)))
                                 (update-in acc [:type-atoms s] (fnil conj #{}) o)

                                 :else acc))
                             {} triples)
        p->readings  (into {}
                           (map (fn [[s {:keys [readings]}]]
                                  [(first (corsem/->polysemy-pattern s))
                                   readings]))
                           corsem/polysemy-pattern-info)
        atoms-of     (fn [sense]
                       (into #{}
                             (mapcat #(get-in links [:type-atoms %] #{}))
                             (get-in links [:stypes sense] #{})))
        order-pair   (fn [[a b]]
                       (let [aa   (atoms-of a)
                             bb   (atoms-of b)
                             disc (fn [r first-reading?]
                                    (when (keyword? r)
                                      (let [ia (contains? aa r)
                                            ib (contains? bb r)]
                                        (cond
                                          (and ia (not ib)) (if first-reading? [a b] [b a])
                                          (and ib (not ia)) (if first-reading? [b a] [a b])))))
                             try-p (fn [p]
                                     (when-let [[r1 r2] (p->readings p)]
                                       (or (disc r2 false) (disc r1 true))))]
                         (->> (set/intersection (get-in links [:patterns a] #{})
                                                (get-in links [:patterns b] #{}))
                              (sort)
                              (some try-p))))
        wp->senses   (reduce (fn [acc [sense words]]
                               (reduce (fn [acc [w p]]
                                         (update acc [w p] (fnil conj #{}) sense))
                                       acc
                                       (for [w words
                                             p (get-in links [:patterns sense] #{})]
                                         [w p])))
                             {} (:senses links))
        sense-pairs  (into (sorted-set)
                           (comp (filter #(= 2 (count %)))
                                 (map (comp vec sort)))
                           (vals wp->senses))
        ordered      (into {} (keep (fn [pair]
                                      (when-let [o (order-pair pair)]
                                        [pair o])))
                           sense-pairs)
        syn-of       (fn [sense]
                       (let [syns (get-in links [:synsets sense] #{})]
                         (when (= 1 (count syns))
                           (first syns))))
        syn-pair     (fn [[a b]]
                       (let [sa (syn-of a) sb (syn-of b)]
                         (when (and sa sb (not= sa sb))
                           [sa sb])))
        directed     (into (sorted-set) (keep syn-pair) (vals ordered))
        ;; A synset pair whose two sense pairs order both ways cannot keep a
        ;; direction and is demoted to the undirected set.
        conflicted   (into #{} (filter (fn [[x y]] (directed [y x]))) directed)
        syn-ordered  (reduce disj directed conflicted)
        undirected   (into (sorted-set)
                           (comp (remove ordered)
                                 (keep syn-pair)
                                 (map (comp vec sort)))
                           sense-pairs)
        syn-plain    (reduce disj
                             (into undirected (map (comp vec sort)) conflicted)
                             (map (comp vec sort) syn-ordered))]
    {:senses  {:ordered (into (sorted-set) (vals ordered))
               :plain   (reduce disj sense-pairs (keys ordered))}
     :synsets {:ordered syn-ordered
               :plain   syn-plain}}))

(h/defn add-cor-sem-graph!
  "Add the cor-sem: graph to `dataset`, built from the published COR.SEM
  source file (GitHub issue #207).

  The links carried by the source get the treatment the COR links got: word
  links whose cor: word is gone from the current COR edition are remapped via
  the official changelogs (5) or pruned (27), and synset links whose dn:
  synset no longer resolves are pruned (just 8, all trailing values of the
  irregular \"; \"-separated DanNet-link rows). The 551 nominally DSL-internal
  synset-s* ids resolve fine, their synsets having since been published with
  the 2023 adjective supplement. The frame resources and the payload anchored
  directly on the senses are kept as-is.

  The fixed links are then enriched with the sense-correspondences, stating
  which COR.SEM and DanNet senses describe the same meaning.

  Must run before add-in-scheme!, which marks scheme membership on the new
  graph."
  [dataset]
  (t/log! {:level :info
           :id    :dannet.bootstrap/add-cor-sem-graph
           :data  {:version release/cor-sem-version}}
          "Building COR.SEM graph from source file")
  (let [sem-graph (db/get-graph dataset prefix/cor-sem-uri)
        sem-model (db/get-model dataset prefix/cor-sem-uri)
        dn-graph  (db/get-graph dataset prefix/dn-uri)
        cor-graph (db/get-graph dataset prefix/cor-uri)
        ;; Typing alone misses the handful of degenerate synsets absent from
        ;; DSL's synsets.csv, so lexicalization also counts as existence.
        synsets   (into (->> (q/run dn-graph '[:bgp [?synset :rdf/type :ontolex/LexicalConcept]])
                             (map '?synset)
                             (set))
                        (->> (q/run dn-graph '[:bgp [?synset :ontolex/lexicalizedSense ?sense]])
                             (map '?synset)))
        cor-words (->> shared/cor-word-types
                       (mapcat #(q/run cor-graph [:bgp ['?w :rdf/type %]]))
                       (map '?w)
                       (set))
        remap     (cor/id-remap)
        fix-link  (fn [[s p o :as triple]]
                    (case p
                      :ontolex/sense
                      (if (cor-words s)
                        [triple]
                        (for [w (->> (remap (name s))
                                     (map (partial keyword "cor"))
                                     (filter cor-words))]
                          [w p o]))

                      (:dns/linkedSynset :dns/hypernymAnchor)
                      (when (synsets o)
                        [triple])

                      [triple]))
        source    (corsem/source-triples)
        triples   (into #{} (mapcat fix-link) source)
        {:keys [exact close mismatched]} (sense-correspondences
                                           dn-graph cor-graph triples)
        alt       (sense-alternations triples)
        triples'  (-> triples
                      (into (map (fn [[s ds]] [s :dns/eqSense ds])) exact)
                      (into (map (fn [[s ds]] [s :dns/eqNearSense ds])) close)
                      (into (map (fn [[a b]] [a :dns/alternatesTo b]))
                            (concat (get-in alt [:senses :ordered])
                                    (get-in alt [:synsets :ordered])))
                      (into (map (fn [[a b]] [a :dns/alternatesWith b]))
                            (concat (get-in alt [:senses :plain])
                                    (get-in alt [:synsets :plain]))))
        counts    {:in          (count source)
                   :out         (count triples)
                   :exact       (count exact)
                   :close       (count close)
                   :mismatched  (count mismatched)
                   :alt-senses  {:ordered (count (get-in alt [:senses :ordered]))
                                 :plain   (count (get-in alt [:senses :plain]))}
                   :alt-synsets {:ordered (count (get-in alt [:synsets :ordered]))
                                 :plain   (count (get-in alt [:synsets :plain]))}}
        ;; :in/:out include the minimal frame: resources of the 5 post-1.7
        ;; frames (the rest live in the framenet graph) and the triples of
        ;; the named dnt: ontological types and dnp: polysemy patterns in
        ;; the source.
        expected  {:in          386189
                   :out         386150
                   :exact       34561
                   :close       4860
                   :mismatched  2
                   :alt-senses  {:ordered 298 :plain 64}
                   :alt-synsets {:ordered 126 :plain 32}}]
    (t/log! {:level :info
             :id    :dannet.bootstrap/fix-cor-sem-links
             :data  counts}
            "Carrying COR.SEM links after remapping/pruning")
    (assert (= expected counts)
            (str "expected COR.SEM triple counts " expected ", found " counts))
    (doseq [chunk (partition-all 500000 triples')]
      (txn/transact-exec sem-graph
        (db/safe-add! sem-graph chunk)))
    (txn/transact-exec sem-model
      (md/update-metadata! (get md/metadata 'cor-sem) sem-model))))

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
    (name-ontological-types! dataset)
    (rename-cor-sense-links! dataset)
    (add-cor-sem-graph! dataset)

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
                            (:hash (meta #'add-cor-sem-graph!))
                            (:hash (meta #'sense-correspondences))
                            (:hash (meta #'sense-alternations))
                            (:hash (meta #'corsem/->polysemy-pattern))
                            (hash corsem/polysemy-pattern-info)
                            (:hash (meta #'name-ontological-types!))
                            (:hash (meta #'rename-cor-sense-links!))
                            (:hash (meta #'corsem/->corsem-triples))
                            (:hash (meta #'corsem/->frame-triples))
                            (:hash (meta #'corsem/->ontotype))
                            (:hash (meta #'corsem/ontotype-atoms))
                            (:hash (meta #'cor/id-remap))
                            ;; Value hashes: the converter's lookup data isn't
                            ;; captured by the hashed forms above.
                            (hash corsem/restriction-notes)
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
                            ;; it explicitly; otherwise cutting a release
                            ;; (:to "SNAPSHOT" -> a real version, same :from)
                            ;; wouldn't change db-name and the stale, still
                            ;; SNAPSHOT-labelled database would be reused.
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
                            ;; values, in the dc:hasVersion dataset metadata
                            ;; and (for COR.SEM) the source filename.
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
