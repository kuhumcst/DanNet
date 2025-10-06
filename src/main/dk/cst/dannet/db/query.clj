(ns dk.cst.dannet.db.query
  "Functions for querying an Apache Jena graph."
  (:require [clojure.edn :as edn]
            [clojure.walk :as walk]
            [arachne.aristotle.query :as q]
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

;; TODO: what about blank-expanded-entity?
(defn blank-entity
  "Retrieve the blank object entity of `subject` and `predicate` in Graph `g`."
  [g subject predicate blank-object]
  (when (and subject predicate)
    (->> (entity g blank-object)
         ;; TODO: why is this transformation necessary?
         (reduce (fn [acc [?p ?o]]
                   (update acc ?p (fnil conj #{}) ?o))
                 {}))))

;; I am not smart enough to do this through SPARQL/algebra, so instead I have to
;; resort to this hack.
(defn attach-blank-entities
  "Replace blank resources in `entity` of `subject` in `g` with entity maps."
  [g subject entity]
  (let [predicate (volatile! nil)]
    (walk/prewalk
      (fn [x]
        (cond
          (vector? x)
          (do (vreset! predicate (first x)) x)

          (symbol? x)
          (with-meta x (blank-entity g subject @predicate x))

          :else x))
      entity)))

(def synset-indegrees-file
  "db/synset-indegree.edn")

;; This takes around 6 minutes to generate, unfortunately...
(defn save-synset-indegrees!
  "Generate and store synset indegrees found in `g`."
  [g]
  (->> (run g op/synset-indegree)
       (map (juxt '?o '?indegree))
       (sort-by first)
       (clojure.pprint/pprint)
       (with-out-str)
       (spit synset-indegrees-file)))

;; Mapping of synset-id->indegree for the synset resources.
(defonce synset-indegrees
  (delay
    (try
      (->> (slurp synset-indegrees-file)
           (edn/read-string)
           (into {}))
      (catch Exception _
        nil))))

(defn synset-weights
  "Return a mapping of synset-id->weight for synset IDs found in `coll`."
  [coll]
  (let [indegrees @synset-indegrees
        weights   (volatile! (transient {}))]
    (clojure.walk/postwalk
      (fn [x]
        (when-let [v (and (keyword? x) (get indegrees x))]
          (vswap! weights assoc! x v)))
      coll)
    (persistent! @weights)))

(defn- assoc-resource-label!
  "Conj `label-value` into the set at (get-in acc [resource label-type])."
  [acc resource label-type label-value]
  (let [resource-labels (get acc resource {})
        label-set       (get resource-labels label-type #{})
        updated-labels  (assoc resource-labels
                          label-type (conj label-set label-value))]
    (assoc! acc resource updated-labels)))

(defn other-entities
  "Restructure the `expanded-entity-result` as a mapping from resource->entity,
  not including the subject resource itself, e.g:

    {resource {label-type #{label-values}}}"
  [expanded-entity-result]
  (when (seq expanded-entity-result)
    (persistent!
      (reduce
        (fn [acc {:syms [?p ?o ?pl ?plr ?ol ?olr]}]
          (cond-> acc
            ?plr (assoc-resource-label! ?p ?plr ?pl)
            ?olr (assoc-resource-label! ?o ?olr ?ol)))
        (transient {})
        expanded-entity-result))))

(defn weighted-relations
  "Sort synset relation collections in `entity` by their weights.

  Uses synset-rel-theme keys to identify relevant relations and synset-indegrees
  for weights. Returns entity with sorted collections (highest weight first)."
  [entity]
  (let [indegrees     @synset-indegrees
        synset-rel-ks (set (keys shared/synset-rel-theme))]
    (persistent!
      (reduce-kv (fn [m k v]
                   (assoc! m k
                           (if (and (synset-rel-ks k) (coll? v))
                             (sort-by #(get indegrees % 0) > v)
                             v)))
                 (transient {})
                 entity))))

(defn expanded-entity
  "Return the expanded entity description of `subject` in Graph `g`."
  [g subject]
  (if-let [result (not-empty (run g op/expanded-entity {'?s subject}))]
    (with-meta (->> (basic-entity result)
                    (weighted-relations)
                    (attach-blank-entities g subject))
               (cond-> {:entities       (other-entities result)
                        ;; TODO: make more performant?
                        :synset-weights (synset-weights result)
                        ;; TODO: is it necessary to attach subject?
                        :subject        subject}
                 (instance? BaseInfGraph g)
                 (assoc :inferred (inferred-entity result (find-raw g subject)))))
    (with-meta {} {:subject subject})))

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
  (entity (:graph @dk.cst.dannet.web.resources/db)
          :dn/synset-1771)
  #_.)
