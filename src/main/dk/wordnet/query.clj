(ns dk.wordnet.query
  "Functions for querying and navigating an Apache Jena graph."
  (:require [clojure.string :as str]
            [clojure.core.protocols :as p]
            [arachne.aristotle.query :as q]
            [dk.wordnet.transaction :as txn]))

(defn anonymous?
  [resource]
  (and (symbol? resource)
       (str/starts-with? resource "_")))

;; TODO: currently removes usages which is not desirable - fix?
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

(defn entity
  "Return the entity description of `subject` in Graph `g`."
  [g subject]
  (when-let [e (->> (run g [:bgp [subject '?p '?o]])
                    #_(only-uris)
                    (map (comp (partial apply hash-map) (juxt '?p '?o)))
                    (apply merge-with (set-nav-merge g)))]
    (with-meta e (assoc (nav-meta g)
                   :subject subject))))

;; TODO: what about blank-expanded-entity?
(defn blank-entity
  "Retrieve the blank object entity of `subject` and `predicate` in Graph `g`."
  [g subject predicate]
  (when (and subject predicate)
    (->> (q/run g ['?p '?o] [:bgp
                             [subject predicate '?blank]
                             '[?blank ?p ?o]])
         (map (fn [[p o]] {p #{o}}))
         (apply merge-with into))))

(defn- set-merge
  "Helper function for merge-with in 'entity-label-mapping'."
  [v1 v2]
  (cond
    (= v1 v2)
    v1

    (set? v1)
    (conj v1 v2)

    :else
    (hash-set v1 v2)))


(defn- entity-label-mapping
  "Create a mapping from keyword -> rdfs:label based on the `xe` that is the
   result of the 'expanded-entity' query."
  [xe]
  (loop [[head & tail] xe
         m {}]
    (if-let [{:syms [?p ?pl ?o ?ol]} head]
      (let [pm (when ?pl {?p ?pl})
            om (when ?ol {?o ?ol})]
        (recur tail (merge-with set-merge m pm om)))
      m)))

(defn expanded-entity
  "Return the expanded entity description of `subject` in Graph `g`."
  [g subject]
  (when-let [xe (-> (run g [:conditional
                            [:conditional
                             [:bgp [subject '?p '?o]]
                             [:bgp ['?p :rdfs/label '?pl]]]
                            [:bgp ['?o :rdfs/label '?ol]]])
                    #_(only-uris))]
    (let [e (->> (map (comp (partial apply hash-map) (juxt '?p '?o)) xe)
                 (apply merge-with (set-nav-merge g)))]
      (with-meta e (assoc (nav-meta g)
                     :k->label (entity-label-mapping xe)
                     :subject subject)))))

(defn run
  "Wraps the 'run' function from Aristotle, providing transactions when needed.
  The results are also made Navigable using for use with e.g. Reveal or REBL."
  [g & remaining-args]
  (->> (txn/transact g
         (apply q/run g remaining-args))
       (map #(vary-meta % merge (nav-meta g)))))
