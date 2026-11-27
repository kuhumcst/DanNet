(ns dk.cst.dannet.web.ui.origin
  "Where the values of an entity come from, as the page data has it (see
  dk.cst.theophrastus.origin): the graph that holds a value, or that the
  value is inferred or supplemented. The tables mark a row by its origins,
  and edit mode locks a value by them (see ui.edit.lock)."
  (:require [dk.cst.theophrastus.origin :as origin]
            [dk.cst.dannet.shared :as shared]))

(defn value-properties
  "The properties of the entity in `opts` that hold the value `v`, which is
  listed in the row of the property `k`.

  That is `k` itself, except in the row of members (see shared/member-row),
  where it is the rdf:_N properties that have `v`, in the order of their
  ordinals. A member that none of them has is a value of rdfs:member itself."
  [{:keys [entity] :as opts} k v]
  (or (when (= k shared/member-row)
        (->> (keys entity)
             (filter #(and (shared/member-property? %)
                           (= v (shared/unwrap (get entity %)))))
             (sort-by shared/member-ordinal)
             (seq)))
      [k]))

(defn value-origins
  "The origins of the value `v` in the row of the property `k` as a set, as
  the page data in `opts` has them for the properties that hold it."
  [opts k v]
  (into #{}
        (mapcat (fn [p]
                  (let [o (origin/origin opts p v)]
                    (if (set? o) o #{o}))))
        (value-properties opts k v)))

(defn row-origins
  "The origins of the values `v` in the row of the property `k`, as the page
  data in `opts` has them, e.g. #{:inferred} for a row of inferred values."
  [opts k v]
  (into #{}
        (mapcat #(value-origins opts k %))
        (shared/values-of v)))
