(ns dk.cst.dannet.web.ui.edit
  "The fields of a row of the attribute-value table in edit mode.

  A field shows one value, and each edit is a pending change in the session
  (see dk.cst.dannet.change). The fields follow five rules:

  1. A field is open, as an input, only while the focus is in it. Enter or
     leaving the field enters the text, and Escape drops what was typed. An
     emptied field removes its value.
  2. The tint says how the field differs from the data: yellow replaces a
     value or has moved it here from another row, green adds one and red
     removes one.
  3. A text that isn't a value has a wavy line under it, and it isn't a
     pending change yet.
  4. Each button does one thing, and the tint decides whether a field has
     it (see control/field-buttons).
  5. The buttons come in the order edit, undo, remove.

  A blank node that the shapes describe is a field too (see
  node/node-field), and so is a new entry (see entry-field/entry-field).

  The cells of the table are here. The fields are in ui.edit.value,
  ui.edit.entry and ui.edit.node, and they share the inputs of
  ui.edit.input, the buttons of ui.edit.control and the focus handling of
  ui.edit.focus.

  The :edit-rules of the page (see dk.cst.dannet.editable) name the
  :languages that the dropdown of a text offers, where nil is none, the
  :translated properties, whose rows show which language has no text yet,
  and the :entries, which are the templates of the new entries."
  (:require [clojure.string :as str]
            [rum.core :as rum]
            [dk.cst.dannet.change :as change]
            [dk.cst.dannet.entry :as entry]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.web.i18n :as i18n]
            [dk.cst.dannet.web.ui.edit.control :as control]
            [dk.cst.dannet.web.ui.edit.entry :as entry-field]
            [dk.cst.dannet.web.ui.edit.focus :as focus]
            [dk.cst.dannet.web.ui.edit.input :as input]
            [dk.cst.dannet.web.ui.edit.node :as node]
            [dk.cst.dannet.web.ui.edit.row :as row]
            [dk.cst.dannet.web.ui.edit.value :as value]
            [dk.cst.dannet.web.ui.input-type :as input-type]
            [dk.cst.dannet.web.ui.lock :as lock]
            [dk.cst.dannet.web.ui.rdf :as rdf]))

(rum/defc node-cell
  "A table cell for the values `vs` in the row of the property `k` in `opts`
  in edit mode, when the shapes describe its values as blank nodes: a field
  for each node that can be edited, a button that adds a node, and last the
  locked values with a padlock that says why.

  The :constraints of the table say whether the row can take another node
  or lose one. A node that only holds one value is the field of that value
  (see node/sole-field), and a new one is added with its value."
  [{:keys [changes constraints] :as opts} k vs]
  (let [subject  (value/subject-of opts)
        {:keys [must] shape :node} (get constraints k)
        sole     (row/sole-property shape)
        about    #(if (map? %) (:rdf/about %) %)
        sorted   (sort-by (rdf/label-sortkey-fn opts) vs)
        locked   (filter #(lock/lock opts k %) sorted)
        ;; the changes name a node by its label
        by-label (into {}
                       (map (juxt about identity))
                       (remove (set locked) sorted))
        fields   (row/fields changes subject [k]
                             (map #(vector k %) (keys by-label)))
        current  (+ (count locked) (count (row/kept fields)))
        full?    (row/full? must current)
        required (row/required must current)
        label    (control/plus-label opts k)]
    [:td
     [:ul.edit-values
      (for [{:keys [value] :as field} fields
            :let [node (get by-label value)
                  ;; the page of a new entry has a new node as its bare label
                  node (when (map? node)
                         node)]]
        (rum/with-key
          (or (when sole
                (node/sole-field opts k sole value node required))
              (node/node-field opts k value
                               (assoc field :node node :required required)))
          (str value)))
      (cond
        full?
        nil

        sole
        (rum/with-key
          (value/new-value-field
            opts k nil
            {:input-type (input-type/input-type [] (get-in shape [sole :must]))
             :on-add     #(node/add-node! opts k [sole %])})
          "new")

        :else
        [:li.edit-value.edit-value--new {:key "new"}
         (control/add-button label #(node/add-node! opts k))])
      (for [v locked]
        (control/locked-value opts v (lock/lock opts k v)
                              (pr-str (about v))))]]))

(rum/defc cell
  "A table cell for the values `vs` in the row of the property `k` in `opts`
  in edit mode: a field for each value that can be edited, a field for a new
  one, and last the locked values with a padlock that says why.

  A set of values is sorted by language, the reader's preferred one first,
  and then by text. The :constraints of the table shape the fields (see
  dk.cst.dannet.db.shapes/property-constraints). A new entry among the
  values, a row that takes new entries and an entry of the data in such a
  row each have a field of their own (see ui.edit.entry)."
  [{:keys [languages edit-rules constraints] :as opts} k vs]
  (let [rank       (row/lang-rank languages)
        sorted     (if (set? vs)
                     (sort-by (juxt (comp rank i18n/lang)
                                    (rdf/label-sortkey-fn opts))
                              vs)
                     vs)
        input-type (input-type/input-type vs (get-in constraints [k :must]))
        {:keys [properties fields locked langs gap expected full? required
                new-type taken]}
        (row/cell opts (value/subject-of opts) k sorted input-type)
        ;; a row that takes new entries has a field of its own for those
        row-entry  (entry/for-row (:entries edit-rules) k)
        item-key   (fn [p v] (pr-str [p (if (map? v) (:rdf/about v) v)]))]
    [:td
     [:ul.edit-values
      (for [{:keys [property value original status] :as field} fields]
        ;; keyed by its property and by the value that it replaces, so that
        ;; an edit keeps its field
        (rum/with-key (cond
                        (change/temporary? value)
                        (entry-field/entry-field opts value)

                        ;; an entry of the data in a row that takes new ones
                        (and row-entry
                             (keyword? value)
                             (contains? #{nil :retracted} status))
                        (entry-field/data-entry-field
                          opts property value
                          (assoc field :required required))

                        :else
                        (value/value-field opts property value
                                           (assoc field
                                             :input-type input-type
                                             :langs langs
                                             :required required)))
                      (item-key property (if (some? original) original value))))
      (cond
        (or full? (some-> (:options new-type) empty?))
        nil

        row-entry
        (rum/with-key (entry-field/new-entry-field opts k row-entry) "new")

        :else
        (rum/with-key (value/new-value-field
                        opts (row/new-value-property k properties) taken
                        {:input-type new-type
                         :langs      langs
                         :lang       (or gap (row/new-text-lang (first taken)
                                                                expected))
                         :gap?       (some? gap)})
                      "new"))
      (for [{:keys [property value] why :lock} locked]
        (control/locked-value opts value why (item-key property value)))]]))

(defn property-search
  "A search among the properties `ks` of a table with `opts`, in a dialog
  with the `title` in each language (see ui.input-type for what a search
  is). It finds a property by its label or its prefixed name. With
  `missing?`, the properties are ones that the entry lacks, so those that
  it is expected to have come first and say so (see row/expected)."
  [{:keys [languages constraints] :as opts} ks title missing?]
  (let [expected #(when missing? (row/expected constraints %))
        sorted   (sort-by (juxt (comp {:must 0 :should 1 nil 2} expected)
                                (rdf/label-sortkey-fn opts))
                          ks)
        text     #(str/lower-case
                    (str (rdf/link-label opts %) " " (prefix/kw->qname %)))]
    {:title       title
     :placeholder {:da "skriv et navn..." :en "write a name..."}
     :list-all?   true
     :results-fn  (fn [s]
                    (let [s (str/lower-case (str/trim s))]
                      (filter #(str/includes? (text %) s) sorted)))
     :value-fn    identity
     :hiccup-fn   (fn [k _]
                    [:<>
                     [:strong (rdf/resource-name k opts)]
                     [:span (prefix/kw->qname k)]
                     (case (expected k)
                       :must [:small (i18n/da-en languages
                                       "Skal have en værdi"
                                       "Must have a value")]
                       :should [:small (i18n/da-en languages
                                         "Bør have en værdi"
                                         "Should have a value")]
                       nil)])}))

(rum/defcs row-adder < (rum/local false ::choosing?)
  "A button below a table with `opts` that adds a row. It opens a dialog
  with the properties `ks` to choose between, and calls `on-add` with the
  chosen one."
  [state {:keys [languages] :as opts} ks on-add]
  (let [choosing? (::choosing? state)
        title     {:da "Tilføj en række" :en "Add a row"}]
    [:div.edit-add-row
     [:button {:type     "button"
               :on-click #(reset! choosing? true)}
      (control/icon "add")
      (i18n/localize languages title)]
     (when @choosing?
       (input/search-dialog
         opts
         (property-search opts ks title true)
         {:on-value (fn [k]
                      (reset! choosing? false)
                      (on-add k))
          :on-close (focus/close-fn choosing? "button")}))]))

(rum/defcs value-mover < (rum/local false ::choosing?)
  "A button by the name of the row of the property `k` in `opts` that moves
  the values `vs` of the row to another property. It opens a dialog with the
  properties that the values fit (see row/alternatives), and each value
  moves as a change of its own, so that it can be undone on its own.

  There is no button when the values fit no other property, or in the
  nested table of a blank node."
  [state {:keys [languages constraints entity blank-node] :as opts} k vs]
  (let [choosing? (::choosing? state)
        subject   (value/subject-of opts)
        values    (when-not blank-node (row/movable opts subject k vs))
        ks        (when (seq values)
                    (row/alternatives constraints (row/addable opts) entity
                                      k (count values)))
        ;; the words before and after the name of the row
        around    {:da ["Flyt værdierne fra " " til en anden relation"]
                   :en ["Move the values from " " to another relation"]}
        title     (update-vals around
                               (fn [[before after]]
                                 (str before "\"" (rdf/link-label opts k) "\""
                                      after)))
        [before after] (i18n/localize languages around)]
    (when (seq ks)
      [:<>
       (control/icon-button "edit"
                            (i18n/localize languages title)
                            #(reset! choosing? true))
       (when @choosing?
         (input/search-dialog
           opts
           (assoc (property-search opts ks title false)
             :heading [:<> before (rdf/resource-name k opts) after])
           {:on-value (fn [k']
                        (reset! choosing? false)
                        (value/edit! opts row/move subject k k' values)
                        (focus/focus-row! subject k'))
            :on-close (focus/close-fn choosing? ".edit-value__button")}))])))
