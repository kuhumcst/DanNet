(ns dk.cst.dannet.db.query
  "Functions for querying an Apache Jena graph."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [clojure.core.memoize :as memo]
            [taoensso.telemere :as t]
            [arachne.aristotle.query :as q]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.release :as release]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.db.transaction :as txn]
            [dk.cst.dannet.db.query.operation :as op])
  (:import [org.apache.jena.reasoner BaseInfGraph]
           [org.apache.jena.reasoner.rulesys FBRuleInfGraph]))

(defn run
  "Wraps the 'run' function from Aristotle, providing transactions when needed."
  [g & remaining-args]
  (txn/transact g
    (apply q/run g remaining-args)))

;; TODO: replace with fnil in all places where update is used anyway
(defn set-merge
  "Helper function for merge-with in 'entity-label-mapping'."
  [v1 v2]
  (cond
    (nil? v1)
    v2

    (= v1 v2)
    v1

    (set? v1)
    (conj v1 v2)

    :else
    #{v1 v2}))

(declare entity)

(defn- basic-entity
  "Get entity from `entity-query-result`."
  [entity-query-result]
  (persistent!
    (reduce (fn [m {:syms [?p ?o]}]
              (assoc! m ?p (set-merge (get m ?p) ?o)))
            (transient {})
            entity-query-result)))

(defn find-raw
  "Return the raw entity query result for `subject` in `g` (no inference)."
  [^FBRuleInfGraph g subject]
  (let [query-graph (fn [graph] (run graph op/entity {'?s subject}))
        triple-keys (fn [result] (select-keys result '[?s ?p ?o]))
        xf          (comp (mapcat query-graph) (map triple-keys))]
    (into #{} xf [(.getSchemaGraph g) (.getRawGraph g)])))

(defn inferred-entity
  "Determine inferred parts of `result` given `raw-result` triples."
  [result raw-result]
  (let [triple-keys (fn [item] (select-keys item '[?s ?p ?o]))
        in-raw?     (fn [item] (contains? raw-result (triple-keys item)))]
    (basic-entity (remove in-raw? result))))

(defn entity
  "Return the entity description of `subject` in Graph `g`.
  
  For inference graphs, includes metadata with inferred vs. raw triples."
  [g subject]
  (if-let [result (not-empty (run g op/entity {'?s subject}))]
    (with-meta (basic-entity result) {:subject subject})
    (with-meta {} {:subject subject})))

;; TODO: can it use basic-entity instead of entity?
;; TODO: what about blank-expanded-entity?
(defn blank-node
  "Retrieve the blank object entity of `subject` and `predicate` in Graph `g`.

  Every value is normalised to a set. NB: multi-valued properties are already
  sets in the entity map and must NOT be wrapped again; doing so produced
  nested sets, e.g. for the dns:inheritedFrom of inheritance nodes with
  multiple parent synsets, crashing the frontend rendering."
  [g subject predicate blank-object]
  (when (and subject predicate)
    (->> (entity g blank-object)
         (reduce (fn [acc [?p ?o]]
                   (update acc ?p (fnil into #{}) (shared/setify ?o)))
                 {}))))

;; I am not smart enough to do this through SPARQL/algebra!
(defn attach-blank-nodes
  "Replace blank node symbols in `entity` of `subject` in `g` with entity maps."
  [g subject entity]
  (let [predicate (volatile! nil)]
    (walk/prewalk
      (fn [x]
        (cond
          (vector? x)
          (do (vreset! predicate (first x)) x)

          (symbol? x)
          (with-meta x (blank-node g subject @predicate x))

          :else x))
      entity)))

;; TODO: reuse attach-blank-nodes (requires re-think of data flow for SPARQL)
;; Due to the flow in how SPARQL results are handled, we can't attach metadata
;; the same way we do in 'attach-blank-nodes' above, so we need this other
;; function to do the job.
(defn collect-blank-nodes
  "Collect blank node entity maps in SPARQL result `rows` in graph `g`.
  Returns a map from blank node symbol to its entity description."
  [g rows]
  (let [blanks (into #{} (comp (mapcat vals) (filter symbol?)) rows)]
    (when (seq blanks)
      (persistent!
        (reduce (fn [acc b]
                  (if-let [e (not-empty (entity g b))]
                    (assoc! acc b e)
                    acc))
                (transient {})
                blanks)))))

(defn indegrees-file
  "The synset-indegree cache location for release `v`, i.e. where a bootstrap
  from `v` reads it and where its release asset belongs."
  [v]
  (io/file (release/version-dir v) release/indegrees-filename))

(def indegrees-files
  "Where the synset-indegree cache is read from, in order of precedence. The
  first is the legacy location, kept as an override so a deployment that already
  has the file next to its database keeps working without moving it; the second
  is the release asset it ships as, alongside the other bootstrap inputs."
  [(io/file "db" release/indegrees-filename)
   (indegrees-file release/from)])

(def indegrees-export
  "Where a regenerated cache is written. It describes the release being produced
  rather than the one bootstrapped from, so it ships with the export artifacts."
  (io/file "export" release/indegrees-filename))

(defn save-synset-indegrees!
  "Generate and store the synset indegrees found in `g`, by default among the
  export artifacts. Takes around 6 minutes, unfortunately."
  ([g]
   (save-synset-indegrees! g indegrees-export))
  ([g dest]
   (io/make-parents dest)
   (->> (run g op/synset-indegree)
        (map (juxt '?o '?indegree))
        (sort-by first)
        (pprint/pprint)
        (with-out-str)
        (spit dest))))

(defn- read-synset-indegrees
  []
  ;; Degrades silently otherwise: every lookup returns 0, so search results and
  ;; entity relations come back unranked rather than erroring.
  (let [unavailable! (fn [why]
                       (t/log! {:level :error
                                :id    :dannet.query/indegrees-unavailable
                                :data  {:searched (mapv str indegrees-files)
                                        :why      why}}
                               (str "SYNSET INDEGREE CACHE UNAVAILABLE; search "
                                    "results and entity relations will be "
                                    "UNRANKED. " why))
                       nil)]
    (if-let [f (first (filter #(.exists %) indegrees-files))]
      (try
        (->> (slurp f)
             (edn/read-string)
             (into {}))
        (catch Exception e
          (unavailable! (str f " could not be read: " (.getMessage e)))))
      (unavailable! (str "None of " (mapv str indegrees-files) " exist.")))))

;; Mapping of synset-id->indegree for the synset resources.
(defonce synset-indegrees
  (delay (read-synset-indegrees)))

(defn reload-synset-indegrees!
  "Re-derive the indegree cache, which may have been downloaded or switched out
  since this namespace was loaded."
  []
  (alter-var-root #'synset-indegrees (constantly (delay (read-synset-indegrees)))))

(defn resource-labels
  "Fetch labels for a set of `resources` (keywords or bracketed RDF resource
  strings) from graph `g`. Returns `{resource {label-type #{label-values}}}`."
  [g resources]
  (when (seq resources)
    (let [result (run g (op/resource-labels-query resources))]
      (persistent!
        (reduce
          (fn [acc {:syms [?resource ?labelRel ?label]}]
            (assoc! acc ?resource
                    (update (get acc ?resource {}) ?labelRel
                            (fnil conj #{}) ?label)))
          (transient {})
          result)))))

(defn weighted-relations
  "Sort synset relation collections in `entity` by their weights.

  Uses shared/weight-sorted-rel? to identify relevant relations and
  synset-indegrees for weights. Returns entity with sorted collections
  (highest weight first)."
  [entity]
  (let [indegrees @synset-indegrees]
    (persistent!
      (reduce-kv (fn [m k v]
                   (assoc! m k
                           (if (and (shared/weight-sorted-rel? k) (coll? v))
                             (sort-by #(get indegrees % 0) > v)
                             v)))
                 (transient {})
                 entity))))

(defn gathered-sense-values
  "Return the set of `k` values gathered from the senses of `synset-kw` in `g`,
  e.g. usage examples or DDO source links."
  [g synset-kw k]
  (let [synset    (entity g synset-kw)
        sense-kws (shared/setify (:ontolex/lexicalizedSense synset))]
    (->> sense-kws
         (keep (fn [sense-kw]
                 (shared/setify (k (entity g sense-kw)))))
         (reduce into #{})
         (not-empty))))

(defn ddo-entry-id
  "The entry_id parameter of the DDO source `url`, e.g. \"entry_id=11029335\";
  nil when absent."
  [url]
  (re-find #"entry_id=\d+" (str url)))

(defn dedupe-ddo-sources
  "Remove from `urls` any DDO link whose entry_id also appears in a link with
  a def_id, i.e. an entry-level link duplicating a sense-level one.

  The COR.SEM senses of a synset only carry entry-level DDO links, so without
  this the synset page would double its DanNet sense sources."
  [urls]
  (let [sense-refs (into #{}
                         (comp (filter #(str/includes? % "def_id="))
                               (keep ddo-entry-id))
                         urls)]
    (into #{}
          (remove #(and (not (str/includes? % "def_id="))
                        (sense-refs (ddo-entry-id %))))
          urls)))

(defn label-centralities
  "Sense label -> COR.SEM centrality, joining the `centralities` and `matches`
  rows of the cor-sem: graph with the `label-rows` of the dn: graph.

  The matches cover dns:eqSense and dns:eqNearSense alike: a near match still
  concerns the same word, and the exact matches alone leave too many central
  senses unmarked to rank by."
  [centralities matches label-rows]
  (let [corsem->centrality (into {}
                                 (map (juxt '?corsem '?centrality))
                                 centralities)
        sense->centrality  (into {}
                                 (keep (fn [{:syms [?corsem ?sense]}]
                                         (when-let [c (corsem->centrality ?corsem)]
                                           [?sense c])))
                                 matches)]
    (into {}
          (keep (fn [{:syms [?sense ?label]}]
                  (when-let [c (sense->centrality ?sense)]
                    [(str ?label) c])))
          label-rows)))

(declare hypernym-ancestry)

(defn hypernym-ancestry*
  "Implementation for `hypernym-ancestry`. Use that function instead."
  [g synset-kw]
  (let [e         (entity g synset-kw)
        hypernyms (or (not-empty (shared/setify (:wn/hypernym e)))
                      (shared/setify (:dns/orthogonalHypernym e)))]
    (when (seq hypernyms)
      (mapv (fn [h]
              (let [h-entity    (entity g h)
                    label       (:rdfs/label h-entity)
                    short-label (:dns/shortLabel h-entity)]
                (cond-> {:wn/hypernym h
                         :rdfs/label  (str label)
                         :ancestors   (hypernym-ancestry g h)}
                  (and short-label (not= label short-label))
                  (assoc :dns/shortLabel (str short-label)))))
            hypernyms))))

(def hypernym-ancestry
  "Return the hypernym ancestry tree for `synset-kw` in `g`.

  Handles multiple hypernyms, returning a vector where each entry has
  `:wn/hypernym`, `:rdfs/label`, and `:ancestors`. Results are LRU-cached
  (1000 entries) since ancestry chains are shared across many synsets."
  (memo/lru hypernym-ancestry* :lru/threshold 1000))

(defn supplement-synset
  "Supplement `synset` for `subject` in `g` with examples and DDO source links
  gathered from its senses, the English definition of its ILI concept, and the
  FrameNet frames of the COR.SEM senses linking only this synset.
  Returns synset with metadata updated with `:supplemented`, `:ancestry`, and
  additional `:entities` labels."
  [g synset subject]
  (let [examples     (gathered-sense-values g subject :lexinfo/senseExample)
        sources      (some-> (gathered-sense-values g subject :dns/source)
                             (dedupe-ddo-sources))
        ;; The entailed cross-dataset lexicalizations stay under their asserted
        ;; dns:linkedSynsetOf row rather than doubling DanNet's own senses.
        cor-senses   (some-> (:dns/linkedSynsetOf synset)
                             (shared/setify))
        senses       (when cor-senses
                       (not-empty
                         (into #{}
                               (remove cor-senses)
                               (shared/setify (:ontolex/lexicalizedSense synset)))))
        ;; COR.SEM senses can be coarser than DanNet's, and the frames were
        ;; assigned at COR.SEM's granularity: a sense spanning several synsets
        ;; may well carry one frame per facet, so distributing several frames
        ;; over several synsets would fabricate attributions no annotator made
        ;; (e.g. the efterlade sense pairing Abandonment with "leave behind at
        ;; one's death" but linking the "bequeath" synset too). Hence a sense
        ;; contributes its frames only when nothing needs distributing: it
        ;; links just this synset, or it carries just one frame, which
        ;; describes its whole meaning and so holds for every synset it spans.
        ;; TODO: consider asserting these derived links in the cor-sem graph
        ;;       instead, so they also reach SPARQL and the downloads.
        frames       (->> cor-senses
                          (mapcat (fn [sense]
                                    (let [{:dns/keys [linkedSynset frame]} (entity g sense)
                                          frames' (shared/setify frame)]
                                      (when (or (= 1 (count frames'))
                                                (= #{subject} (shared/setify linkedSynset)))
                                        frames'))))
                          (set)
                          (not-empty))
        ili-def      (some->> (:wn/ili synset)
                              (entity g)
                              :skos/definition
                              (shared/setify))
        ancestry     (hypernym-ancestry g subject)
        synset'      (cond-> synset
                       examples (assoc :lexinfo/senseExample examples)
                       sources (assoc :dns/source sources)
                       senses (assoc :ontolex/lexicalizedSense senses)
                       frames (assoc :dns/frame frames)
                       ili-def (update :skos/definition
                                       #(into ili-def (shared/setify %))))
        supplemented (cond-> #{}
                       examples (conj :lexinfo/senseExample)
                       sources (conj :dns/source)
                       frames (conj :dns/frame)
                       ili-def (conj :skos/definition))
        entities     (cond-> (reduce (fn [m rel]
                                       (if (contains? m rel)
                                         m
                                         (assoc m rel (select-keys (entity g rel)
                                                                   [:rdfs/label]))))
                                     (-> synset meta :entities)
                                     supplemented)
                       frames (merge (resource-labels g frames)))]
    (vary-meta synset' merge
               {:entities     entities
                :supplemented (not-empty supplemented)
                :ancestry     ancestry})))

;; TODO: make the word entity page resemble a traditional dictionary entry via
;;       custom display elements, e.g. the abbreviated inflected forms found in
;;       the DMLex browser project.
(defn supplement-word
  "Supplement `word` in `g` with inflected forms from the COR words it is
  owl:sameAs. Returns word with `:ontolex/otherForm` added and metadata
  updated with `:supplemented` and additional `:entities` labels."
  [g word]
  (let [cor-word? (fn [k] (and (keyword? k) (= "cor" (namespace k))))
        forms     (->> (shared/setify (:owl/sameAs word))
                       (filter cor-word?)
                       (mapcat #(shared/setify (:ontolex/otherForm (entity g %))))
                       (set)
                       (not-empty))]
    (if forms
      (let [rel-entity (entity g :ontolex/otherForm)
            entities   (-> (:entities (meta word))
                           (merge (resource-labels g forms))
                           (assoc :ontolex/otherForm
                                  (select-keys rel-entity [:rdfs/label])))]
        (-> word
            (update :ontolex/otherForm #(into forms (shared/setify %)))
            (vary-meta merge {:entities     entities
                              :supplemented #{:ontolex/otherForm}})))
      word)))

(defn subtract-asserted
  "Remove from the values of `super` in `entity` those already asserted under
  its subproperty `sub`, dropping the `super` relation when nothing remains.

  Keeps the COR and DanNet inventories apart on entity pages: the entailed
  links into the other dataset stay under their asserted dns: subproperty row
  (see dns:linkedSense/dns:linkedSenseOf)."
  [entity super sub]
  (if-let [vs (some-> (get entity sub) (shared/setify))]
    (let [remaining (into #{} (remove vs) (shared/setify (get entity super)))]
      (if (seq remaining)
        (assoc entity super remaining)
        (dissoc entity super)))
    entity))

(defn- embedded-resources
  "Collect keyword resources (predicates and objects) found in the blank node
  entity maps attached as metadata on symbols within `entity` values."
  [entity]
  (let [->coll #(if (coll? %) % [%])]
    (into #{}
          (comp (mapcat ->coll)
                (filter symbol?)
                (keep meta)
                (mapcat (fn [m] (concat (keys m) (mapcat ->coll (vals m)))))
                (filter keyword?))
          (vals entity))))

(defn- expanded-entity*
  "Return the expanded entity description of `subject` in Graph `g`."
  [g subject]
  (if-let [result (not-empty (run g op/entity {'?s subject}))]
    (let [entity+   (->> (basic-entity result)
                         (weighted-relations)
                         (attach-blank-nodes g subject))
          ;; Labels are fetched in one batched VALUES query; joining them onto
          ;; every entity triple multiplied result rows for large synsets.
          resources (into #{}
                          (comp (mapcat (juxt '?p '?o))
                                (filter prefix/resource?))
                          result)
          entities  (resource-labels g resources)
          ;; Labels for resources inside blank node entity maps are fetched
          ;; separately for use in the nested attr-val tables.
          entities+ (merge entities
                           (resource-labels
                             g (remove entities (embedded-resources entity+))))
          entity*   (with-meta entity+
                               (cond-> {:entities entities+
                                        :subject  subject}
                                 (instance? BaseInfGraph g)
                                 (assoc :inferred (inferred-entity result (find-raw g subject)))))]
      (cond
        (shared/dn-synset? subject entity*)
        (supplement-synset g entity* subject)

        (shared/dn-word? subject entity*)
        (supplement-word g entity*)

        (shared/cor-word? subject entity*)
        (subtract-asserted entity* :ontolex/sense :dns/linkedSense)

        (shared/dn-sense? subject entity*)
        (subtract-asserted entity* :ontolex/isSenseOf :dns/linkedSenseOf)

        :else entity*))
    (with-meta {} {:subject subject})))

;; Large synsets can have thousands of semantic relations (e.g. synset-2119 has
;; 1165 hyponyms) which take ~3s to query from Jena. To improve perceived
;; performance, the web layer truncates large entities on initial page load and
;; fetches the remaining data via a second "deferred" request. Without caching,
;; both requests would hit the database for the same entity. The cache ensures
;; the deferred request completes in <1ms.
(def expanded-entity
  (memo/lru expanded-entity* :lru/threshold 500))

(defn table-query
  "Run query `q` in `g`, transposing the results as rows of `ks`.

  Any one-to-many relationships in the result values are represented as set
  values contained in the resulting table rows. This is the main difference
  from the built-in vector transposition in 'arachne.aristotle.query/run'."
  [g ks q]
  (map (fn [m] (mapv m ks))
       (-> (group-by #(get % (first ks)) (run g q))
           (update-vals #(apply merge-with set-merge %))
           (vals))))

(comment
  (entity (:graph @dk.cst.dannet.web.instance/db) :dn/synset-1771)
  (gathered-sense-values (:graph @dk.cst.dannet.web.instance/db)
                         :dn/synset-3047 :lexinfo/senseExample)
  (hypernym-ancestry (:graph @dk.cst.dannet.web.instance/db) :dn/synset-3047)
  #_.)
