(ns dk.cst.dannet.db.query
  "Functions for querying an Apache Jena graph."
  (:require [clojure.edn :as edn]
            [clojure.walk :as walk]
            [arachne.aristotle.query :as q]
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
  (->> (map (comp (partial apply hash-map) (juxt '?p '?o)) entity-query-result)
       (apply merge-with set-merge)))

(defn find-raw
  "Return the raw entity query result for `subject` in `g` (no inference)."
  [^FBRuleInfGraph g subject]
  (->> [(.getSchemaGraph g) (.getRawGraph g)]
       (pmap #(run % op/entity {'?s subject}))
       (apply concat)))

(defn inferred-entity
  "Determine inferred parts of entity described by `result` given `raw-result`."
  [result raw-result]
  (let [raw-result (set raw-result)]
    (->> result
         (filter #(not (get raw-result (select-keys % '[?s ?p ?o]))))
         (basic-entity))))

(defn entity
  "Return the entity description of `subject` in Graph `g`.
  
  For inference graphs, includes metadata with inferred vs. raw triples."
  [g subject]
  (if-let [result (not-empty (run g op/entity {'?s subject}))]
    (with-meta
      (basic-entity result)
      (cond-> {:subject subject}
        (instance? BaseInfGraph g)
        (assoc :inferred (inferred-entity result (find-raw g subject)))))
    (with-meta {} {:subject subject})))

;; TODO: what about blank-expanded-entity?
(defn blank-entity
  "Retrieve the blank object entity of `subject` and `predicate` in Graph `g`."
  [g subject predicate blank-object]
  (when (and subject predicate)
    (->> (entity g blank-object)
         (map (fn [[?p ?o]] {?p #{?o}}))
         (apply merge-with into))))

;; I am not smart enough to do this through SPARQL/algebra, so instead I have to
;; resort to this hack.
(defn attach-blank-entities
  "Replace blank resources in `entity` of `subject` in `g` with entity maps."
  [g subject entity]
  (let [predicate (atom nil)]
    (walk/prewalk
      (fn [x] (cond
                (vector? x)
                (do (reset! predicate (first x)) x)

                ;; In order to avoid infinite recursive walks, entity maps are
                ;; hidden in metadata rather than directly replacing symbols.
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

(def synset-indegrees
  "Mapping of synset-id->indegree for the synset resources."
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
  (let [weights (atom {})]
    (clojure.walk/postwalk
      (fn [x]
        (when-let [v (and (keyword? x) (get @synset-indegrees x))]
          (swap! weights assoc x v)))
      coll)
    @weights))

(defn other-entities
  "Restructure the `expanded-entity-result` as a mapping from resource->entity,
  not including the subject entity itself."
  [expanded-entity-result]
  (->> expanded-entity-result
       (map (fn [{:syms [?p ?o ?pl ?plr ?ol ?olr]}]
              (cond-> {}
                ?plr (assoc ?p {?plr #{?pl}})
                ?olr (assoc ?o {?olr #{?ol}}))))
       (apply merge-with (partial merge-with into))))

(defn expanded-entity
  "Return the expanded entity description of `subject` in Graph `g`."
  [g subject]
  (if-let [result (not-empty (run g op/expanded-entity {'?s subject}))]
    (with-meta (->> (basic-entity result)
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
