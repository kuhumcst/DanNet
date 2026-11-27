(ns dk.cst.dannet.web.ui.edit.lock
  "Which values of an entity can be edited, and why the others can't.

  A value can be edited when the graph that changes go to holds it, which
  the origins in the page data say (see ui.origin). Any other value is
  locked, and so is one that edit mode has no input for yet.

  The :edit-rules of the page lock more. They are the rules of the dataset,
  a map with any of these keys (see dk.cst.dannet.editable for DanNet's):

  - :namespaces, the namespaces of the resources that can be edited, as a
    set of prefixes such as \"dn\". Without it, any resource can be.
  - :derived, the properties that a build writes for every resource, so
    that their values aren't edited by hand.
  - :derived-by-class, a map of a class to the properties that a build
    writes for the resources of that class.
  - :fixed, the properties that say what a resource is, e.g. its type, which
    an edit never changes."
  (:require [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.web.ui.origin :as origin]))

(defn editable?
  "Is the value `v` in the row of the property `k` in the graph that changes
  go to? That graph is the :default-origin of `opts`."
  [{:keys [default-origin] :as opts} k v]
  (and (some? default-origin)
       (contains? (origin/value-origins opts k v) default-origin)))

(defn derived?
  "Does a build write the property `k` of the entity in `opts`, according to
  the :derived and :derived-by-class of the :edit-rules?"
  [{:keys [entity edit-rules] :as opts} k]
  (let [{:keys [derived derived-by-class]} edit-rules]
    (boolean (or (contains? derived k)
                 (some #(contains? (get derived-by-class %) k)
                       (shared/setify (:rdf/type entity)))))))

(defn editable-resource?
  "Can the subject of the page in `opts` be edited, according to the
  :namespaces of the :edit-rules? Any subject can be when they name none."
  [{:keys [subject edit-rules] :as opts}]
  (let [namespaces (:namespaces edit-rules)]
    (or (nil? namespaces)
        (and (keyword? subject)
             (contains? namespaces (namespace subject))))))

(defn- blank-value?
  "Is the value `v` a blank node, as a map of its properties or as a symbol?"
  [v]
  (or (map? v) (symbol? v)))

(defn lock
  "Why the value `v` in the row of the property `k` in `opts` can't be
  edited, as a key of the :locked comments, or nil when it can be.

  The first reason that holds is given: the property is :derived or :fixed;
  the value is :inferred or :supplemented, or :elsewhere, in a graph that
  changes don't go to; the :resource is outside the namespaces that can be
  edited; or the value is a blank node that no shape describes, which
  can't be added or removed :yet. A value in the nested table of a locked
  blank node has the :lock of that node."
  [{:keys [blank-node edit-rules constraints] :as opts} k v]
  (cond
    ;; a node that is only locked as :yet has values that can be edited
    (some-> (:lock blank-node) (not= :yet))
    (:lock blank-node)

    ;; the rules name the properties of the resource, not of its blank nodes
    (and (not blank-node) (derived? opts k))
    :derived

    (and (not blank-node) (contains? (:fixed edit-rules) k))
    :fixed

    (not (editable? opts k v))
    (let [origins (origin/value-origins opts k v)]
      (cond
        (contains? origins :inferred)     :inferred
        (contains? origins :supplemented) :supplemented
        :else                             :elsewhere))

    (not (editable-resource? opts))
    :resource

    (and (blank-value? v) (not (get-in constraints [k :node])))
    :yet))

(defn node?
  "Is the value `v` a blank node whose own values can be edited in its
  nested table? The page data has the origins of those values then."
  [v]
  (and (map? v) (some? (:default-origin (meta v)))))
