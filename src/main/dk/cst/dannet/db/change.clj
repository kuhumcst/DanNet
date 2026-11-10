(ns dk.cst.dannet.db.change
  "Changes (see dk.cst.dannet.change) resolved against a db.

  A change resolves when its :where matches exactly once. A change that a
  later release has made stale then fails instead of touching the wrong node,
  and so does one whose :where finds duplicate blank nodes, which in DanNet
  point to an import bug."
  (:require [dk.cst.theophrastus :as theo]
            [dk.cst.theophrastus.convert :as convert]
            [dk.cst.dannet.change :as change])
  (:import [org.apache.jena.graph Triple]
           [org.apache.jena.query Query]
           [org.apache.jena.sparql.syntax ElementTriplesBlock]))

(defn- where-query
  "A SELECT query of the [s p o] `patterns`, built from Jena nodes with
  `prefixes` for keywords, so that no value has to be escaped as SPARQL."
  ^Query [patterns prefixes]
  (let [block (ElementTriplesBlock.)]
    (doseq [pattern patterns]
      (let [[s p o] (map #(convert/node % prefixes) pattern)]
        (.addTriple block (Triple/create s p o))))
    (doto (Query.)
      (.setQuerySelectType)
      (.setQueryResultStar true)
      (.setQueryPattern block))))

(defn resolve-change
  "The `change` as plain triples in `db`: its :where has to match exactly once,
  and its ?variables take the values of that match. A :where without
  variables only checks that its triples are in `db`. Throws when the :where
  matches nothing or more than once."
  [{:keys [prefixes] :as db} {:keys [where] :as change}]
  (if (empty? where)
    (change/bind-variables change {})
    (let [solutions (theo/q db (where-query where prefixes))
          n         (count solutions)]
      (if (= 1 n)
        (change/bind-variables change (first solutions))
        (throw (ex-info (str "The :where of a change matches " n " times, "
                             "not exactly once")
                        {:change change :matches n}))))))
