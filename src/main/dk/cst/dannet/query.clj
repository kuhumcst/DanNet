(ns dk.cst.dannet.query
  "Functions for querying and navigating an Apache Jena graph."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [clojure.core.protocols :as p]
            [arachne.aristotle.query :as q]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.transaction :as txn]
            [dk.cst.dannet.web.components :as com]
            [dk.cst.dannet.query.operation :as op])
  (:import [org.apache.jena.reasoner.rulesys FBRuleInfGraph]))

(defn anonymous?
  [resource]
  (and (symbol? resource)
       (str/starts-with? resource "_")))

;; TODO: currently removes examples which is not desirable - fix?
(defn only-uris
  "Exclude anonymous resource `results`, optionally keyed under `k`.

  The OWL reasoner produces a bunch of triples with anonymous resources in the
  object position. This is a way to remove these triples from the results of an
  Aristotle query."
  ([k results]
   (remove (comp anonymous? k) results))
  ([results]
   (remove (comp #(some anonymous? %) vals) results)))

;; TODO: what about symbols? Some are ?vars, others are _blank-resources
(defn entity?
  "Is `x` an RDF entity?"
  [x]
  (or (keyword? x)
      (and (string? x)
           (re-matches #"<.+>" x))))

(declare entity)
(declare run)
(declare run-basic)

(defn- nav-subjects
  "Helper function for 'nav-meta'."
  [g & subject-triples]
  (let [results (->> (into [:bgp] subject-triples)
                     (run g)
                     (only-uris)
                     (mapcat vals)
                     (into #{}))]
    (if (= 1 (count results))
      (entity g (first results))
      results)))

(defn nav-meta
  "Create metadata with a generalised Navigable implementation for a Graph `g`.

  For RDF entities, returns the entity description as a map.
  For literals, returns all the subjects of the same relation to the literal.
  For one-to-many relations, returns all subjects with the same set of objects."
  [g]
  {`p/nav (fn [coll k v]
            (cond
              (entity? v)
              (entity g v)

              (set? v)
              (with-meta (apply nav-subjects g (for [o v] [(gensym "?") k o]))
                         (nav-meta g))

              :else
              (with-meta (nav-subjects g ['?s k v])
                         (nav-meta g))))})

(defn- set-nav-merge
  "Helper function for merge-with in 'entity'."
  [g]
  (fn [v1 v2]
    (if (set? v1)
      (with-meta (conj v1 v2) (meta v1))
      (vary-meta (hash-set v1 v2) merge (nav-meta g)))))

(defn- basic-entity
  "Get entity from `entity-query-result`."
  [entity-query-result]
  (->> (map (comp (partial apply hash-map) (juxt '?p '?o)) entity-query-result)
       (apply merge)))

(defn- navigable-entity
  "Get entity from `entity-query-result` implementing the Navigable protocol."
  [g entity-query-result]
  (->> (map (comp (partial apply hash-map) (juxt '?p '?o)) entity-query-result)
       (apply merge-with (set-nav-merge g))))

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

(defn entity-triples
  [g subject]
  (when-let [result (run-basic g op/entity {'?s subject})]
    (map (juxt '?s '?p '?o) result)))

(defn entity-map
  [g subject]
  (when-let [result (run-basic g op/entity {'?s subject})]
    (basic-entity result)))

(defn entity
  "Return the entity description of `subject` in Graph `g`."
  [g subject]
  (if-let [result (not-empty (run g op/entity {'?s subject}))]
    (with-meta (navigable-entity g result)
               (assoc (nav-meta g)
                 :inferred (inferred-entity result (find-raw g subject))
                 :subject subject))
    (with-meta {} {:subject subject})))

;; TODO: what about blank-expanded-entity?
(defn blank-entity
  "Retrieve the blank object entity of `subject` and `predicate` in Graph `g`."
  [g subject predicate blank-object]
  (when (and subject predicate)
    (->> (entity g blank-object)
         (map (fn [[?p ?o]] {?p #{?o}}))
         (apply merge-with into))))

(defn set-merge
  "Helper function for merge-with in 'entity-label-mapping'."
  [v1 v2]
  (cond
    (= v1 v2)
    v1

    (set? v1)
    (conj v1 v2)

    :else
    #{v1 v2}))

(defn resource?
  [x]
  (when x
    (or (keyword? x)
        (symbol? x)
        (prefix/rdf-resource? x))))

(defn- entity-label-mapping
  "Create a mapping from resource->label based on the `xe` that is the result
   of the 'expanded-entity' query.

   The purpose of this mapping is to allow displaying labels or label-like
   things for the different resources in the frontend, as this is often
   necessary to successfully navigate an RDF graph as a human being."
  [xe]
  (loop [[head & tail] xe
         entity {}]
    (if-let [{:syms [?p ?pl ?plr
                     ?o ?ol ?olr]} head]

      ;; Each label-like resource of every predicate and object are collected.
      (recur tail (cond-> entity
                    (and ?pl ?plr (resource? ?p))
                    (update ?p (partial merge-with set-merge) {?plr ?pl})

                    (and ?ol ?olr (resource? ?o))
                    (update ?o (partial merge-with set-merge) {?olr ?ol})))

      ;; Finally, the best label key and its associated label(s) are chosen;
      ;; the other labels are discarded.
      (update-vals entity (fn [m]
                            (get m (com/entity->label-key m)))))))

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

(defn expanded-entity
  "Return the expanded entity description of `subject` in Graph `g`."
  [g subject]
  (if-let [result (not-empty (run g op/expanded-entity {'?s subject}))]
    (with-meta (->> (navigable-entity g result)
                    (attach-blank-entities g subject))
               (assoc (nav-meta g)
                 :k->label (entity-label-mapping result)
                 :inferred (inferred-entity result (find-raw g subject))
                 ;; TODO: is it necessary to attach subject?
                 :subject subject))
    (with-meta {} {:subject subject})))

(defn run-basic
  "Same as 'run' below, but doesn't attach Navigable metadata."
  [g & remaining-args]
  (txn/transact g
    (apply q/run g remaining-args)))

(defn run
  "Wraps the 'run' function from Aristotle, providing transactions when needed.
  The results are also made Navigable using for use with e.g. Reveal or REBL."
  [g & remaining-args]
  (->> (apply run-basic g remaining-args)
       (map #(vary-meta % merge (nav-meta g)))))

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
