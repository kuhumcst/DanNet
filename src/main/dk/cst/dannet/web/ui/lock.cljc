(ns dk.cst.dannet.web.ui.lock
  "Which values of an entity can be edited, and why the others can't.

  A value can be edited when the graph that changes go to holds it, which
  the origins in the page data say (see dk.cst.theophrastus.origin). Any
  other value is locked, and so is one that the release build writes or
  that edit mode has no input for yet."
  (:require [dk.cst.theophrastus.origin :as origin]
            [dk.cst.dannet.shared :as shared]))

(defn value-origins
  "The origins of the value `v` of the property `k` as a set, as the page
  data in `opts` has them."
  [opts k v]
  (let [o (origin/origin opts k v)]
    (if (set? o) o #{o})))

(defn row-origins
  "The origins of the values `v` of the property `k`, as the page data in
  `opts` has them, e.g. #{:inferred} for a row of inferred values."
  [opts k v]
  (into #{}
        (mapcat #(value-origins opts k %))
        (if (shared/multi-valued? v) v [v])))

(defn editable?
  "Can the value `v` of the property `k` be edited? It can when the graph
  that changes go to, the :default-origin of `opts`, is one of its origins."
  [{:keys [default-origin] :as opts} k v]
  (and (some? default-origin)
       (contains? (value-origins opts k v) default-origin)))

(def derived-properties
  "The properties that the release build writes for every resource, so that
  their values aren't edited by hand."
  #{:dns/inherited :skos/inScheme})

(def derived-synset-properties
  "The properties that the release build writes for a synset: its labels,
  which are made from its senses."
  #{:rdfs/label :dns/shortLabel})

(defn derived?
  "Does the release build write the property `k` of the entity in `opts`?"
  [{:keys [entity] :as opts} k]
  (or (contains? derived-properties k)
      (and (contains? derived-synset-properties k)
           (contains? (shared/setify (:rdf/type entity))
                      :ontolex/LexicalConcept))))

(def folded-properties
  "The properties of the rows that the table makes from several properties,
  whose edits can't be mapped back to those yet: the members of a container
  (see ui.table/fold-member-rows)."
  #{:rdfs/member})

(defn- unsupported?
  "Is the value `v` of the property `k` one that edit mode has no input for
  yet: a blank node, or a value of a folded row?"
  [k v]
  (or (map? v) (symbol? v) (contains? folded-properties k)))

(defn lock
  "Why the value `v` of the property `k` in `opts` can't be edited, as a key
  of the :locked comments, or nil when it can be. The property is :derived;
  the value is :inferred or :supplemented, or it is :elsewhere, in a graph
  that changes don't go to; or it can't be edited :yet."
  [opts k v]
  (cond
    (derived? opts k)
    :derived

    (not (editable? opts k v))
    (let [origins (value-origins opts k v)]
      (cond
        (contains? origins :inferred)     :inferred
        (contains? origins :supplemented) :supplemented
        :else                             :elsewhere))

    (unsupported? k v)
    :yet))
