(ns dk.cst.dannet.web.ui.changes
  "The list of the pending changes of an editor (see dk.cst.dannet.change),
  by the entry that each change is made to.

  A value is shown as its field shows it, in the tint of its change, and
  with a button that undoes the change."
  (:require [rum.core :as rum]
            [dk.cst.dannet.change :as change]
            [dk.cst.dannet.web.app :as app]
            [dk.cst.dannet.web.i18n :as i18n]
            [dk.cst.dannet.web.ui.edit :as edit]
            [dk.cst.dannet.web.ui.edit.row :as row]
            [dk.cst.dannet.web.ui.rdf :as rdf]))

(defn- subjects
  "The subjects of the triples of the pending `changes`, in the order of
  their first change."
  [changes]
  (distinct (for [{:keys [retract add]} changes
                  [s] (concat retract add)]
              s)))

(defn- rows
  "The rows of the subject `s` in the list of the pending `changes`: a map
  for each property that they change, of the :subject, the :property and
  its :fields (see row/fields). The row of a blank node in `nodes` also has
  the :holder, the property of its entry that holds the node."
  [changes nodes s]
  (for [p    (sort (change/changed-properties changes s))
        :let [{:keys [retracted]} (change/property-changes changes s p)
              fields (row/fields changes s [p]
                                 (map #(vector p %) (sort-by str retracted)))]
        :when (seq fields)]
    (cond-> {:subject s :property p :fields fields}
      (contains? nodes s) (assoc :holder (get-in nodes [s :property])))))

(defn entries
  "The entries that the pending `changes` are made to, in the order of their
  first change, as maps of the :entry and its :rows (see rows).

  A change to a blank node in `nodes`, the places of the blank nodes of the
  session, is listed under the entry that holds the node. An entry whose
  changes cancel each other out is left out."
  [changes nodes]
  (let [entry-of #(get-in nodes [% :subject] %)
        ss       (subjects changes)]
    (for [entry (distinct (map entry-of ss))
          :let  [rows (mapcat #(rows changes nodes %)
                              (filter #(= entry (entry-of %)) ss))]
          :when (seq rows)]
      {:entry entry :rows rows})))

(def keep-focus
  "A mixin for a value in the list of the pending changes. When the value
  goes away while the focus is in it, i.e. when its change is undone, the
  focus moves to the undo button after it in the list, else to the one
  before it, else to the status of the list."
  {:will-unmount
   (fn [state]
     #?(:cljs
        (let [focused js/document.activeElement]
          (when (.contains (rum/dom-node state) focused)
            (let [buttons (js/Array.from
                            (.querySelectorAll
                              js/document
                              "article.changes .edit-value__button"))
                  i       (.indexOf buttons focused)]
              (some-> (or (aget buttons (inc i))
                          (aget buttons (dec i))
                          (.querySelector js/document
                                          "article.changes [role=status]"))
                      (.focus))))))
     state)})

(rum/defc pending-value < keep-focus
  "A value of the property `p` of the subject `s` in the list of the pending
  changes, with a button that undoes its change. The `field` is the value
  as row/fields has it, and a value that replaces another is shown below
  the one that it replaces."
  [{:keys [languages] :as opts} s p {:keys [value status original] :as field}]
  (let [content (fn [tag v class]
                  [tag (update (rdf/lone-value-attrs v) :class
                               #(remove nil? ["edit-value__content" class %]))
                   (rdf/value opts v)])
        text    (if (keyword? value) (rdf/link-label opts value) (str value))]
    [:li.edit-value {:class (str "edit-value--" (name status))}
     (case status
       :retracted (content :del value nil)
       :added (content :ins value nil)
       :edited [:span.edit-value__content
                (content :del original "edit-value__old")
                (content :ins value nil)])
     [:span.edit-value__actions
      (edit/icon-button "undo"
                        (edit/undo-label languages status text)
                        #(app/update-changes! change/without-triple
                                              [s p value]))]]))

(rum/defc entry-changes
  "The pending changes to the `entry` as a table of its `rows` (see
  entries), under a heading that links to the entry and its full IRI."
  [{:keys [languages] :as opts} entry rows]
  [:section
   [:header
    [:h2 (rdf/resource-hyperlink entry opts)]
    (rdf/uri-link entry opts)]
   [:table.attr-val
    [:colgroup
     [:col {:aria-label (i18n/da-en languages "RDF-præfix" "RDF prefix")}]
     [:col {:aria-label (i18n/da-en languages "Navn (nøgle)" "Name (key)")}]
     [:col {:aria-label (i18n/da-en languages "Ændring" "Change")}]]
    [:tbody
     (for [{:keys [subject holder property fields] :as row} rows]
       [:tr {:key (pr-str [subject property])}
        [:td.attr-prefix
         (rdf/prefix-badge (symbol (namespace property)))]
        [:td.attr-name
         (when holder
           [:<> (rdf/entity-link holder opts) " › "])
         (rdf/entity-link property opts)]
        [:td
         [:ul.edit-values
          (for [{:keys [value] :as field} fields]
            (rum/with-key (pending-value opts subject property field)
                          (pr-str value)))]]])]]])

(rum/defc changes-list
  "The pending changes in `opts`, by the entry that each is made to, or a
  note that there are none. Their number is a status, so that an undo is
  read out, and it can take the focus when the last change is undone."
  [{:keys [languages changes nodes] :as opts}]
  (let [entries (entries changes nodes)
        n       (count (mapcat :fields (mapcat :rows entries)))]
    [:<>
     [:p.subheading {:role "status" :tab-index -1}
      (if (empty? entries)
        (i18n/da-en languages
          "Der er ingen ændringer endnu."
          "There are no changes yet.")
        (i18n/da-en languages
          (str n (if (= n 1) " ændring i " " ændringer i ")
               (count entries) " opslag")
          (str n (if (= n 1) " change to " " changes to ")
               (count entries)
               (if (= 1 (count entries)) " entry" " entries"))))]
     (for [{:keys [entry rows]} entries]
       (rum/with-key (entry-changes opts entry rows) (str entry)))]))
