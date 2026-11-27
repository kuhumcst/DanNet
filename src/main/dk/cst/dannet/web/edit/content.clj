(ns dk.cst.dannet.web.edit.content
  "What the editor reads from the shapes and the schemas for a resource,
  which the page of an entry and the routes of the editor both send (see
  dk.cst.dannet.web.resources and dk.cst.dannet.web.edit)."
  (:require [dk.cst.dannet.db.query :as q]
            [dk.cst.dannet.db.shapes :as shapes]
            [dk.cst.dannet.editable :as editable]
            [dk.cst.dannet.entry :as entry]
            [dk.cst.dannet.web.instance :as instance]))

(defn- constraint-resources
  "The resources that the `constraints` on the properties of an entry name:
  each property, the values that it can choose between, and the same for
  the properties of its blank nodes."
  [constraints]
  (mapcat (fn [[k {:keys [must should node]}]]
            (concat [k] (:in must) (:in should) (constraint-resources node)))
          constraints))

(def entry-inverses
  "The inverse of each property that the templates of new entries name (see
  editable/rules), as the schemas declare it. The page of an entry lists
  what points to it under the inverse of the property, e.g. the words of a
  synset."
  (delay (q/inverse-properties (:inference @instance/db)
                               (entry/properties (:entries editable/rules)))))

(def editor-content
  "What the editor reads from the shapes and the schemas for a resource
  with the set of `classes`: the :constraints on its properties (see
  shapes/constraints-of), the :inverses of the properties that link new
  entries (see entry-inverses), and the labels of the resources that these
  name as :entities. A property has a label since a row can be added for
  it, and so do the values that it can choose between."
  (memoize
    (fn [classes]
      (let [constraints (shapes/constraints-of @shapes/editorial-constraints
                                               classes)]
        {:constraints constraints
         :inverses    @entry-inverses
         :entities    (q/resource-labels (:inference @instance/db)
                                         (-> (constraint-resources constraints)
                                             (concat (vals @entry-inverses))
                                             (set)))}))))

(defn with-constraints
  "The page `content` of a resource with the `classes`, with what the editor
  reads from the shapes and the schemas (see editor-content). The labels
  that the page already has are kept."
  [content classes]
  (let [{:keys [constraints inverses entities]} (editor-content (set classes))]
    (-> (assoc content :constraints constraints :inverses inverses)
        (update :entities #(merge entities %)))))
