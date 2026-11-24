(ns dk.cst.dannet.web.ui.changes
  "The list of the pending changes of an editor (see dk.cst.dannet.change),
  by the entry that each change is made to.

  A value is shown as its field shows it, in the tint of its change, and
  with the button that its field has: one that undoes the change, or one
  that takes an added value away. A check asks the server what the
  shapes say about the changes, and each result is shown with the entry or
  the row that it is about. Nothing takes a submitted change yet, so
  submitting shows the text that would be sent."
  (:require [clojure.string :as str]
            [rum.core :as rum]
            [dk.cst.dannet.change :as change]
            [dk.cst.dannet.prefix :as prefix]
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
  the :holder, the property of its entry that holds the node.

  The field of a blank node that the changes add or remove has the :rows of
  the node itself, without a :holder."
  [changes nodes s]
  (for [p    (sort (change/changed-properties changes s))
        :let [{:keys [retracted]} (change/property-changes changes s p)
              fields (for [{:keys [value] :as field}
                           (row/fields changes s [p]
                                       (map #(vector p %)
                                            (sort-by str retracted)))]
                       (if (contains? nodes value)
                         (assoc field :rows (map #(dissoc % :holder)
                                                 (rows changes nodes value)))
                         field))]
        :when (seq fields)]
    (cond-> {:subject s :property p :fields fields}
      (contains? nodes s) (assoc :holder (get-in nodes [s :property])))))

(defn entries
  "The entries that the pending `changes` are made to, in the order of their
  first change, as maps of the :entry and its :rows (see rows).

  A change to a blank node in `nodes`, the places of the blank nodes of the
  session, is listed under the entry that holds the node: in rows of its
  own, or in the field of the node when the changes add or remove the node
  itself. An entry whose changes cancel each other out is left out."
  [changes nodes]
  (let [entry-of #(get-in nodes [% :subject] %)
        ss       (subjects changes)
        linked   (into #{}
                       (for [{:keys [retract add]} changes
                             [_ _ o]               (concat retract add)
                             :when                 (contains? nodes o)]
                         o))]
    (for [entry (distinct (map entry-of ss))
          :let  [rows (mapcat #(rows changes nodes %)
                              (filter #(and (= entry (entry-of %))
                                            (not (linked %)))
                                      ss))]
          :when (seq rows)]
      {:entry entry :rows rows})))

(defn row-problems
  "The `results` of a check that are about the `row` of the `entry` (see
  entries): those whose path is the property of the row or, for the row of
  a blank node, the property that holds the node."
  [results entry {:keys [holder property] :as row}]
  (filter (fn [{:keys [focus-node path]}]
            (and (= focus-node entry)
                 (= path (or holder property))))
          results))

(defn entry-problems
  "The `results` of a check that are about the `entry` and none of its
  `rows`, e.g. the result of a rule about several of its values."
  [results entry rows]
  (remove (fn [{:keys [focus-node] :as result}]
            (or (not= focus-node entry)
                (some #(seq (row-problems [result] entry %)) rows)))
          results))

(defn other-problems
  "The `results` of a check that are about none of the `entries` that the
  pending changes are made to, by the entry that each is about. A change
  can break a rule of an entry that it only points to, e.g. a sense that a
  synset no longer has."
  [results entries]
  (->> (remove (comp (set (map :entry entries)) :focus-node) results)
       (group-by :focus-node)))

(defn- triples-text
  "The `triples` under a key of a change as EDN text: the patterns of a
  :where in their order, and a set of triples sorted, so that a change
  always has the same text."
  [triples]
  (if (set? triples)
    (str "#{" (str/join " " (sort (map pr-str triples))) "}")
    (pr-str triples)))

(defn- change-text
  "The `change` as EDN text, with each of its keys on a line of its own and
  their values in a column."
  [change]
  (let [ks    (filter #(contains? change %) [:where :retract :add])
        width (apply max 0 (map #(count (str %)) ks))
        pad   #(apply str % (repeat (- width (count (str %))) " "))]
    (str "{"
         (str/join "\n  "
                   (map #(str (pad %) " " (triples-text (get change %))) ks))
         "}")))

(defn submission
  "The pending `changes` as they are submitted: the EDN text of a change
  file, with their blank nodes located by `nodes` (see change/locate-nodes)."
  [changes nodes]
  (str "["
       (str/join "\n " (map change-text (change/locate-nodes changes nodes)))
       "]"))

(defn- check!
  "Ask the server what the shapes say about the pending `changes`, sent as
  they are submitted with `nodes` (see submission), and keep the answer as
  the :check of the session.

  An answer is dropped when the changes have changed in the meantime."
  [changes nodes]
  #?(:cljs
     (let [answer! (fn [check]
                     (app/clear-current-fetch prefix/check-path)
                     (when (= changes (:changes @app/session))
                       (swap! app/session assoc :check check)))]
       (swap! app/session assoc :check {:running? true})
       (-> (app/fetch! prefix/check-path
                       {:method       :post
                        :content-type :edn
                        :body         (submission changes nodes)})
           (.then #(answer! (get-in % [:body :data :check])))
           (.catch #(answer! {:failed? true}))))))

(defn- problem
  "The message of the `result` of a check, marked as an error or as a
  warning for a reader with `languages`."
  [languages {:keys [severity message source-shape] :as result}]
  (let [warning? (= severity :sh/Warning)]
    [:p.check-problem (cond-> {:key (str source-shape message)}
                        warning? (assoc :class "check-problem--warning"))
     [:strong (if warning?
                (i18n/da-en languages "Advarsel: " "Warning: ")
                (i18n/da-en languages "Fejl: " "Error: "))]
     ;; the shapes have their messages in English
     [:span {:lang "en"} message]]))

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
  changes, with a button that undoes its change, or that takes it away when
  it is added. The `field` is the value as row/fields has it. A value that
  replaces another is shown below the one that it replaces, and a moved one
  says which row it has moved from or to."
  [{:keys [languages] :as opts} s p
   {:keys [value status original moved-to] :as field}]
  (let [content (fn [tag v class]
                  [tag (update (rdf/lone-value-attrs v) :class
                               #(remove nil? ["edit-value__content" class %]))
                   (rdf/value opts v)])
        text    (if (keyword? value) (rdf/link-label opts value) (str value))
        note    (edit/move-note opts field)
        main    (case status
                  :retracted (content :del value nil)
                  (:added :moved) (content :ins value nil)
                  :edited [:span.edit-value__content
                           (content :del original "edit-value__old")
                           (content :ins value nil)])
        t       [s p value]]
    [:li.edit-value {:class (str "edit-value--" (name status))}
     (if note
       [:span.edit-value__content main note]
       main)
     [:span.edit-value__actions
      (if (= status :added)
        (edit/icon-button "remove"
                          (str (i18n/da-en languages "Fjern" "Remove")
                               ": " text)
                          #(app/update-changes! change/without-addition t))
        (edit/icon-button "undo"
                          (edit/undo-label languages
                                           (if moved-to :moved status)
                                           text)
                          #(app/update-changes! change/without-triple t)))]]))

(declare changed-rows)

(rum/defc pending-node < keep-focus
  "A blank node of the property `p` in the list of the pending changes to
  the `entry`: the values of the node as a nested table, with a button that
  takes a new node away or puts a removed one back. The `field` is the node
  as rows has it."
  [{:keys [languages] :as opts} entry p {:keys [value status rows] :as field}]
  (let [removed? (= status :retracted)
        text     (edit/node-text opts p (map :value (mapcat :fields rows)))
        undo!    #(app/update-changes! change/without-node value)]
    [:li.edit-value.edit-node {:class (str "edit-value--" (name status))}
     [(if removed? :del :ins) {:class "edit-value__content"}
      (if (seq rows)
        (changed-rows opts entry rows status)
        (i18n/da-en languages "Ingen værdier endnu" "No values yet"))]
     [:span.edit-value__actions
      (if removed?
        (edit/icon-button "undo"
                          (edit/undo-label languages :retracted text)
                          undo!)
        (edit/icon-button "remove"
                          (str (i18n/da-en languages "Fjern" "Remove")
                               ": " text)
                          undo!))]]))

(rum/defc changed-rows
  "A table of the `rows` of the pending changes to the `entry` (see
  entries), with what the check said about each row.

  The rows of a blank node that the changes add or remove are a nested
  table in the field of the node, with its status as `node-status`. The
  values of a removed node have no buttons of their own, since the node is
  put back as a whole."
  [{:keys [languages check] :as opts} entry rows node-status]
  [:table.attr-val (when node-status {:class "attr-val--nested"})
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
         (for [{:keys [value] :as field} fields
               :let [k (pr-str value)]]
           (cond
             (contains? field :rows)
             (rum/with-key (pending-node opts entry property field) k)

             (= node-status :retracted)
             [:li (assoc (rdf/lone-value-attrs value) :key k)
              (rdf/value opts value)]

             :else
             (rum/with-key (pending-value opts subject property field) k)))]
        (when-not node-status
          (map #(problem languages %)
               (row-problems (:results check) entry row)))]])]])

(rum/defc entry-changes
  "The pending changes to the `entry` as a table of its `rows` (see
  entries), under a heading that links to the entry and its full IRI. An
  entry without rows only has what the check said about it (see
  other-problems)."
  [{:keys [languages check] :as opts} entry rows]
  [:section
   [:header
    [:h2 (rdf/resource-hyperlink entry opts)]
    (rdf/uri-link entry opts)
    (map #(problem languages %)
         (entry-problems (:results check) entry rows))]
   (when (seq rows)
     (changed-rows opts entry rows nil))])

(defn- check-summary
  "What the `check` of the pending changes is doing or said, in a few words
  for a reader with `languages`, or nil before the first check."
  [languages {:keys [running? results error failed?] :as check}]
  (let [n-of     (fn [severity]
                   (count (filter #(= severity (:severity %)) results)))
        errors   (n-of :sh/Violation)
        warnings (n-of :sh/Warning)]
    (cond
      running? (i18n/da-en languages
                 "Tjekker ændringerne …"
                 "Checking the changes …")
      failed? (i18n/da-en languages
                "Ændringerne kunne ikke tjekkes."
                "The changes couldn't be checked.")
      error (i18n/da-en languages
              "En ændring kan ikke udføres på de nuværende data."
              "A change can't be made to the data as it is.")
      (nil? results) nil
      (empty? results) (i18n/da-en languages
                         "Ingen problemer fundet."
                         "No problems found.")
      :else (i18n/da-en languages
              (str errors " fejl og " warnings
                   (if (= warnings 1) " advarsel." " advarsler."))
              (str errors (if (= errors 1) " error and " " errors and ")
                   warnings (if (= warnings 1) " warning." " warnings."))))))

(rum/defc check-control
  "The button that checks the pending changes in `opts`, and what the check
  is doing or last said. A change that can't be made is shown as the server
  read it."
  [{:keys [languages changes nodes check] :as opts}]
  (let [{:keys [running? error change]} check]
    [:div.changes-check
     ;; a disabled button would lose the focus
     [:button {:type          "button"
               :aria-disabled (when running? "true")
               :on-click      #(when-not running?
                                 (check! changes nodes))}
      (i18n/da-en languages
        "Tjek ændringerne for fejl"
        "Check the changes for errors")]
     ;; a status is only read out when its text changes, so it has to be in
     ;; the page while it is empty
     [:p {:role "status"}
      (check-summary languages check)]
     (when error
       [:p.check-problem
        [:span {:lang "en"} error] ": " [:code (pr-str change)]])]))

(rum/defcs submit-control < (rum/local false ::submitted?)
  "The button that submits the pending changes in `opts`. For now it shows
  the text that would be submitted (see submission)."
  [state {:keys [languages changes nodes] :as opts}]
  (let [submitted? (::submitted? state)]
    [:div.changes-submit
     [:button {:type     "button"
               :on-click #(reset! submitted? true)}
      (i18n/da-en languages
        "Indsend ændringerne til godkendelse"
        "Submit the changes for approval")]
     [:p {:role "status"}
      (when @submitted?
        (i18n/da-en languages
          "Ændringer kan ikke indsendes endnu. Dette ville blive indsendt:"
          "Changes can't be submitted yet. This would be submitted:"))]
     (when @submitted?
       [:pre [:code (submission changes nodes)]])]))

(rum/defc changes-list
  "The pending changes in `opts`, by the entry that each is made to, or a
  note that there are none. Their number is a status, so that an undo is
  read out, and it can take the focus when the last change is undone."
  [{:keys [languages changes nodes check] :as opts}]
  (let [entries (entries changes nodes)
        n       (count changes)]
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
     (when (seq entries)
       [:<>
        (check-control opts)
        (for [{:keys [entry rows]} entries]
          (rum/with-key (entry-changes opts entry rows) (str entry)))
        (for [entry (keys (other-problems (:results check) entries))]
          (rum/with-key (entry-changes opts entry nil) (str entry)))
        (submit-control opts)])]))
