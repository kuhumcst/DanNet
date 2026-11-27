(ns dk.cst.dannet.web.ui.edit.node
  "The fields of a blank node in edit mode (see ui.edit): the field of a
  node with its nested table and the field of the only value that a node
  holds, with the edits that add a node or remove one."
  (:require [clojure.string :as str]
            [rum.core :as rum]
            [dk.cst.dannet.change :as change]
            [dk.cst.dannet.web.app :as app]
            [dk.cst.dannet.web.ui.edit.control :as control]
            [dk.cst.dannet.web.ui.edit.focus :as focus]
            [dk.cst.dannet.web.ui.edit.row :as row]
            [dk.cst.dannet.web.ui.edit.value :as value]
            [dk.cst.dannet.web.ui.input-type :as input-type]
            [dk.cst.dannet.web.ui.rdf :as rdf]))

(defn node-text
  "The text that names a blank node in the row of the property `k` in
  `opts`: its `values` (see row/node-values) as texts, a resource by its
  label, or the name of the row when it has none."
  [opts k values]
  (if-let [texts (seq (distinct (map #(value/value-text opts %) values)))]
    (str/join ", " texts)
    (rdf/link-label opts k)))

(defn add-node!
  "Add a new blank node as a value of the property `k` in a table with
  `opts`, under a label that no other node of the session has.

  With `value`, a pair of a property and a value, the node is added with
  that value as one change. Without it, the first field of the node is
  opened once the page is drawn with it."
  [{:keys [nodes] :as opts} k & [value]]
  (let [label  (first (change/new-labels nodes))
        change (row/node-addition (value/subject-of opts) k label value)]
    (doseq [[label place] (change/new-places change)]
      (app/remember-node! label place))
    (value/edit! opts conj change)
    (when-not value
      #?(:cljs (focus/when-drawn!
                 #(.querySelector js/document
                                  (str (focus/node-selector label)
                                       " .edit-value__add"))
                 #(.click %))))))

(defn new-node
  "The node of a new blank node with the `label`, as the page has a node of
  the data: only its label, marked as :new? and with the `default-origin`
  of its values."
  [default-origin label]
  (with-meta {:rdf/about label}
             {:default-origin default-origin
              :new?           true}))

(defn remove-node!
  "Remove the blank node with the `label` in a table with `opts`. A node of
  the data is retracted from its `place`, which is kept for when the changes
  are submitted. Without a place the node is a new one, which is taken out
  of the pending changes."
  [opts label place]
  (if place
    (do
      (app/remember-node! label place)
      (value/edit! opts change/retract-node label place))
    (value/edit! opts change/without-node label)))

(rum/defcs node-field < focus/refocus
                        focus/keep-focus
  "A field for the blank node with the `label` in the row of the property
  `k` in `opts`: its nested table, and a button that removes the node.

  The `props` are the field (see row/fields), with the :node of the page,
  which a new node lacks, and :required, the constraints of a row that
  must keep the node. A removed node shows the values that it has in the
  data, with a button that puts it back."
  [state {:keys [languages default-origin changes] :as opts}
   k label {:keys [status node required]}]
  (let [place    (when node
                   (row/node-place (rdf/node-opts opts node)))
        ;; a removed node is named by its values in the data
        text     (node-text opts k
                            (row/node-values (when-not (= status :retracted)
                                               changes)
                                             label
                                             (:values place)))
        required (when-not (= status :added) required)
        ;; a new status replaces the button in focus with another
        button   ":scope > .edit-value__actions button"
        remove!  (fn [_]
                   (when node
                     (focus/refocus! state button))
                   (remove-node! opts label place))]
    [:li.edit-value.edit-node
     (cond-> {:data-node (str label)}
       status (assoc :class (str "edit-value--" (name status))))
     [(case status
        :retracted :del
        :added :ins
        :div)
      {:class "edit-value__content"}
      (cond
        ;; as the read page shows the node, which can be without a table
        (= status :retracted) (rdf/value (assoc opts :edit-mode false) node)
        node (rdf/node-table opts node)
        :else (rdf/node-table opts (new-node default-origin label)))]
     (control/buttons
       status
       {:undo   [(control/undo-label languages :retracted text)
                 (fn [_]
                   (focus/refocus! state button)
                   (value/edit! opts change/without-node label))]
        :remove [(control/remove-label languages text) remove!]}
       (when required
         (control/required-mark languages required)))]))

(defn sole-field
  "The field of a blank node that only holds the value of the property
  `sole` (see row/sole-property), in the row of the property `k` in `opts`:
  the field of that value, whose remove button removes the node with it.

  The node has the `label`, and `node` is the node of the page, which a new
  node lacks. With `required`, the row must keep the node. Nil when
  the node has no such value."
  [{:keys [changes default-origin] :as opts} k sole label node required]
  (let [nested (-> (rdf/node-opts opts
                                  (or node (new-node default-origin label)))
                   (assoc :attr-key sole))
        place  (row/node-place nested)
        must   (get-in nested [:constraints sole :must])
        [{:keys [value] :as field}]
        (row/fields changes label [sole]
                    (map #(vector sole %) (get-in place [:values sole])))]
    (when field
      (value/value-field
        nested sole value
        (assoc field
          :input-type (input-type/input-type [value] must)
          :required required
          :on-remove #(remove-node! opts label (when node place)))))))
