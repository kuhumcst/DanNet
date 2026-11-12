(ns dk.cst.dannet.web.ui.lock
  "Which values of an entity can be edited, and why the others can't.

  A value can be edited when the graph that changes go to holds it, which
  the origins in the page data say (see dk.cst.theophrastus.origin). Any
  other value is locked, and so is one that edit mode has no input for yet.

  The :edit-rules of the page lock more. They are the rules of the dataset,
  a map with any of these keys (see dk.cst.dannet.editable for DanNet's):

  - :namespaces, the namespaces of the resources that can be edited, as a
    set of prefixes such as \"dn\". Without it, any resource can be.
  - :derived, the properties that a build writes for every resource, so
    that their values aren't edited by hand.
  - :derived-by-type, a map of a type to the properties that a build writes
    for the resources of that type."
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
        (if (shared/multi-valued? v) v [v])))

(defn editable?
  "Is the value `v` in the row of the property `k` in the graph that changes
  go to? That graph is the :default-origin of `opts`."
  [{:keys [default-origin] :as opts} k v]
  (and (some? default-origin)
       (contains? (value-origins opts k v) default-origin)))

(defn derived?
  "Does a build write the property `k` of the entity in `opts`, according to
  the :derived and :derived-by-type of the :edit-rules?"
  [{:keys [entity edit-rules] :as opts} k]
  (let [{:keys [derived derived-by-type]} edit-rules]
    (boolean (or (contains? derived k)
                 (some #(contains? (get derived-by-type %) k)
                       (shared/setify (:rdf/type entity)))))))

(defn editable-resource?
  "Can the subject of the page in `opts` be edited, according to the
  :namespaces of the :edit-rules? Any subject can be when they name none."
  [{:keys [subject edit-rules] :as opts}]
  (let [namespaces (:namespaces edit-rules)]
    (or (nil? namespaces)
        (and (keyword? subject)
             (contains? namespaces (namespace subject))))))

(defn- blank-node?
  "Is the value `v` a blank node, as a map of its properties or as a symbol?
  Edit mode has no input for one yet."
  [v]
  (or (map? v) (symbol? v)))

(defn lock
  "Why the value `v` in the row of the property `k` in `opts` can't be
  edited, as a key of the :locked comments, or nil when it can be.

  The property is :derived; the value is :inferred or :supplemented, or it
  is :elsewhere, in a graph that changes don't go to; the :resource is
  outside the namespaces that can be edited; or the value is a blank node,
  which can't be replaced :yet. The first reason that holds is the one
  given. A value in the nested table of a blank node is locked when the
  node is, for the :lock of the :blank-node in `opts`."
  [{:keys [blank-node] :as opts} k v]
  (cond
    ;; a node that is only locked as :yet has values that can be edited
    (some-> (:lock blank-node) (not= :yet))
    (:lock blank-node)

    ;; the rules name the properties of the resource, not of its blank nodes
    (and (not blank-node) (derived? opts k))
    :derived

    (not (editable? opts k v))
    (let [origins (value-origins opts k v)]
      (cond
        (contains? origins :inferred)     :inferred
        (contains? origins :supplemented) :supplemented
        :else                             :elsewhere))

    (not (editable-resource? opts))
    :resource

    (blank-node? v)
    :yet))

(defn node?
  "Is the value `v` a blank node whose own values can be edited in its
  nested table? The page data has the origins of those values then."
  [v]
  (and (map? v) (some? (:default-origin (meta v)))))
