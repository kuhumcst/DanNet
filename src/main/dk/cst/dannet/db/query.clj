(ns dk.cst.dannet.db.query
  "Functions for querying an Apache Jena graph."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.core.memoize :as memo]
            [taoensso.telemere :as t]
            [dk.cst.theophrastus :as theo]
            [dk.cst.dannet.entity :as ent]
            [dk.cst.dannet.release :as release]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.db.query.operation :as op]))

;; TODO: replace with fnil in all places where update is used anyway
(defn set-merge
  "Merge the values `v1` and `v2`, collecting distinct values in a set; used
  with merge-with over query results."
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

;; theo/entity expands a blank node into a map with its :rdf/about, at any
;; depth, while DanNet expands it one level deep.
(defn- about
  "The symbol of `x` when it's the map of a blank node, otherwise `x`."
  [x]
  (if (map? x) (:rdf/about x) x))

(defn- blank-node
  "`x` with every value but its :rdf/about as a set, and any blank node in
  them as its symbol, when it's the map of a blank node; otherwise `x`.

  NB: multi-valued properties are already sets and must NOT be wrapped again;
  nested sets, e.g. in the dns:inheritedFrom of inheritance nodes with
  multiple parent synsets, crash the frontend rendering."
  [x]
  (if (map? x)
    (assoc (update-vals (dissoc x :rdf/about)
                        #(into #{} (map about) (shared/setify %)))
      :rdf/about (:rdf/about x))
    x))

(defn- update-objects
  "Apply `f` to each object in the entity `e` from theo/entity, leaving out
  its :rdf/about."
  [e f]
  (update-vals (dissoc e :rdf/about)
               #(if (set? %) (into #{} (map f) %) (f %))))

(defn entity
  "Return the entity description of `subject` in `g`: a map of predicate to
  object, or to a set of objects, with the subject as metadata."
  [g subject]
  (with-meta (update-objects (theo/entity g subject) about)
             {:subject subject}))

(defn attach-blank-nodes
  "Replace each blank node symbol in `entity` with the map of its properties
  in `g`, as expanded-entity does."
  [g entity]
  (update-objects entity #(if (symbol? %)
                            (blank-node (assoc (theo/entity g %) :rdf/about %))
                            %)))

;; TODO: SPARQL result rows could carry their blank nodes as maps, as
;;       attach-blank-nodes makes them, rather than in a separate :blank-nodes
;;       map that the SPARQL UI looks them up in.
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
   (->> (theo/q g op/synset-indegree)
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
    (let [result (theo/q g op/resource-labels {'?resource resources})]
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

;; hypernym-ancestry* recurses through the cached hypernym-ancestry, so an
;; ancestor chain shared by many synsets is built once.
(declare hypernym-ancestry)

(defn hypernym-ancestry*
  "Implementation for `hypernym-ancestry`. Use that function instead."
  [g synset-kw]
  (let [e         (entity g synset-kw)
        hypernyms (or (not-empty (shared/setify (:wn/hypernym e)))
                      (shared/setify (:dns/orthogonalHypernym e)))]
    (when (seq hypernyms)
      (mapv (fn [h]
              ;; dns:shortLabel is a subproperty of rdfs:label, so on the
              ;; inference graph the short label is an rdfs:label value too.
              (let [h-entity    (entity g h)
                    short-label (:dns/shortLabel h-entity)
                    label       (or (->> (shared/setify (:rdfs/label h-entity))
                                         (remove #{short-label})
                                         (first))
                                    short-label)]
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

;; COR.SEM senses can be coarser than DanNet's, and the frames were assigned at
;; COR.SEM's granularity: a sense spanning several synsets may well carry one
;; frame per facet, so distributing several frames over several synsets would
;; fabricate attributions no annotator made (e.g. the efterlade sense pairing
;; Abandonment with "leave behind at one's death" but linking the "bequeath"
;; synset too).
;; TODO: consider asserting these derived links in the cor-sem graph instead,
;;       so they also reach SPARQL and the downloads.
(defn synset-frames
  "The FrameNet frames that the COR.SEM `cor-senses` of `subject` in `g`
  contribute to that synset, or nil.

  A sense contributes its frames only when nothing needs distributing: it
  links just this synset, or it carries just one frame, which describes its
  whole meaning and so holds for every synset it spans."
  [g subject cor-senses]
  (->> cor-senses
       (mapcat (fn [sense]
                 (let [{:dns/keys [linkedSynset frame]} (entity g sense)
                       frames' (shared/setify frame)]
                   (when (or (= 1 (count frames'))
                             (= #{subject} (shared/setify linkedSynset)))
                     frames'))))
       (set)
       (not-empty)))

(defn supplement-synset
  "Supplement `synset` for `subject` in `g` with examples and DDO source links
  gathered from its senses, the English definition of its ILI concept, and the
  FrameNet frames of its COR.SEM senses (see synset-frames).
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
        frames       (synset-frames g subject cor-senses)
        ili-def      (->> (shared/setify (:wn/ili synset))
                          (mapcat #(shared/setify (:skos/definition (entity g %))))
                          (set)
                          (not-empty))
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

(defn- expanded-entity*
  "Return the expanded entity description of `subject` in `g`."
  [g subject]
  (if-let [e (theo/entity g subject)]
    (let [entity+  (-> (update-objects e blank-node)
                       (weighted-relations))
          ;; Labels are fetched in one batched VALUES query; joining them onto
          ;; every entity triple multiplied result rows for large synsets.
          entities (resource-labels g (ent/displayed-resources entity+))
          ;; theo/entity puts the :inferred part of an inference graph in the
          ;; metadata.
          entity*  (with-meta entity+
                              (assoc (meta e) :entities entities
                                              :subject subject))]
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
  "The `entity` of `subject` in `g` for display: relations sorted by weight,
  blank nodes attached, :entities labels (and :inferred) in its metadata.

  DanNet synsets and words get supplemented, while COR words and DanNet senses
  leave out the values they also assert under a dns: subproperty."
  (memo/lru expanded-entity* :lru/threshold 500))

(defn table-query
  "Run query `q` in `g`, transposing the results as rows of `ks`.

  Any one-to-many relationships in the result values are represented as set
  values contained in the resulting table rows."
  [g ks q]
  (map (fn [m] (mapv m ks))
       (-> (group-by #(get % (first ks)) (theo/q g q))
           (update-vals #(apply merge-with set-merge %))
           (vals))))

(comment
  (entity (:inference @dk.cst.dannet.web.instance/db) :dn/synset-1771)
  (gathered-sense-values (:inference @dk.cst.dannet.web.instance/db)
                         :dn/synset-3047 :lexinfo/senseExample)
  (hypernym-ancestry (:inference @dk.cst.dannet.web.instance/db) :dn/synset-3047)
  #_.)
