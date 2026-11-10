(ns dk.cst.dannet.db.change
  "Changes (see dk.cst.dannet.change) resolved and made in the db of the
  graph that changes write to, the dn: graph in DanNet.

  A change resolves when the patterns of its :where and :retract match
  exactly once. A change that a later release has made stale then fails
  instead of touching the wrong node, and so does one whose patterns find
  duplicate blank nodes, which in DanNet point to an import bug."
  (:require [clojure.edn :as edn]
            [dk.cst.theophrastus :as theo]
            [dk.cst.theophrastus.convert :as convert]
            [dk.cst.theophrastus.term :as term]
            [dk.cst.theophrastus.txn :as txn]
            [dk.cst.dannet.change :as change])
  (:import [org.apache.jena.graph Triple]
           [org.apache.jena.query Query]
           [org.apache.jena.sparql.syntax ElementTriplesBlock]))

(defn read-changes
  "The vector of changes in the EDN `source`, e.g. a file or a resource."
  [source]
  (edn/read-string {:readers {'rdf/langstring term/read-langstring
                              'rdf/literal    term/read-literal}}
                   (slurp source)))

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
  "The `change` as a plain change in `db`: the patterns of its :where and
  :retract together have to match exactly once, and its ?variables take the
  values of that match. Throws when the change has problems (see
  change/problems) or doesn't match exactly once."
  [{:keys [prefixes] :as db} {:keys [where retract] :as change}]
  (when-some [problems (not-empty (change/problems change))]
    (throw (ex-info "Not a valid change"
                    {:change change :problems problems})))
  (let [solutions (theo/q db (where-query (into (vec where) retract) prefixes))
        n         (count solutions)]
    (if (= 1 n)
      (change/bind-variables change (first solutions))
      (throw (ex-info (str "The patterns of a change match " n " times, "
                           "not exactly once")
                      {:change change :matches n})))))

(defn apply-change!
  "Make the `change` in `db` in one write transaction, resolved as
  resolve-change does it, and return the plain change that was made."
  [db change]
  (txn/with-write-transaction db
    (let [{:keys [retract add] :as plain} (resolve-change db change)]
      (theo/retract! db (vec retract))
      (theo/transact! db (vec add))
      plain)))
