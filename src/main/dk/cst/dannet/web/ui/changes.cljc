(ns dk.cst.dannet.web.ui.changes
  "The list of the pending changes of an editor (see dk.cst.dannet.change),
  by the resource that each change is made to.

  A value is shown as its field shows it, in the tint of its change, with a
  button that undoes the change or takes an added value away. A new entry
  and an entry that is removed as a whole are each one change, with one
  button for the whole entry.

  A check asks the server what the shapes say about the changes, and each
  result is shown with the resource or the row that it is about. Nothing takes
  a submitted change yet, so submitting shows the text that would be sent."
  (:require [clojure.string :as str]
            [rum.core :as rum]
            [dk.cst.dannet.change :as change]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.web.app :as app]
            [dk.cst.dannet.web.i18n :as i18n]
            [dk.cst.dannet.web.ui.edit :as edit]
            [dk.cst.dannet.web.ui.edit.entry :as entry]
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
  the :holder, the property of its resource that holds the node.

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

(defn sections
  "The sections of the list of the pending `changes`, one for each resource
  that they are made to, in the order of their first change: maps of the
  :resource and its :rows (see rows).

  A change to a blank node in `nodes`, the places of the blank nodes of the
  session, is listed under the resource that holds the node: in rows of its
  own, or in the field of the node when the changes add or remove the node
  itself. A resource whose changes cancel each other out is left out."
  [changes nodes]
  (let [resource-of #(get-in nodes [% :subject] %)
        ss          (subjects changes)
        linked      (into #{}
                          (for [{:keys [retract add]} changes
                                [_ _ o]               (concat retract add)
                                :when                 (contains? nodes o)]
                            o))]
    (for [resource (distinct (map resource-of ss))
          :let     [rows (mapcat #(rows changes nodes %)
                                 (filter #(and (= resource (resource-of %))
                                               (not (linked %)))
                                         ss))]
          :when    (seq rows)]
      {:resource resource :rows rows})))

(defn row-problems
  "The `results` of a check that are about the `row` of the `resource` (see
  sections): those whose path is the property of the row or, for the row of
  a blank node, the property that holds the node."
  [results resource {:keys [holder property] :as row}]
  (filter (fn [{:keys [focus-node path]}]
            (and (= focus-node resource)
                 (= path (or holder property))))
          results))

(defn section-problems
  "The `results` of a check that are about the `resource` of a section and
  none of its `rows`, e.g. the result of a rule about several of its values."
  [results resource rows]
  (remove (fn [{:keys [focus-node] :as result}]
            (or (not= focus-node resource)
                (some #(seq (row-problems [result] resource %)) rows)))
          results))

(defn other-problems
  "The `results` of a check that are about none of the `sections` of the
  pending changes, by the resource that each is about. A change can break a
  rule of a resource that it only points to, e.g. a sense that a synset no
  longer has."
  [results sections]
  (->> (remove (comp (set (map :resource sections)) :focus-node) results)
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
  (let [ks    (filter #(contains? change %)
                      [:where :retract :add :unreference])
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

(defn check!
  "Ask the server what the shapes say about the pending changes in `opts`,
  sent as they are submitted (see submission), and keep the answer as the
  :check of the session. The labels of the resources that it is about are
  kept too, since a change can break a rule of a resource that it doesn't
  name.

  An answer is dropped when the changes have changed in the meantime."
  [{:keys [changes nodes] :as opts}]
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
           (.then (fn [response]
                    (let [{:keys [check entities]}
                          (get-in response [:body :data])]
                      (app/remember-labels! (edit/entity-labels opts entities))
                      (answer! check))))
           (.catch #(answer! {:failed? true}))))))

(defn problem
  "The message of the `result` of a check, marked as an error or as a
  warning for a reader with `languages`. With `n`, it also says that it is
  about that many resources."
  [languages {:keys [severity message source-shape] :as result} & [n]]
  (let [warning? (= severity :sh/Warning)]
    [:p.check-problem (cond-> {:key (str source-shape message)}
                        warning? (assoc :class "check-problem--warning"))
     [:strong (if warning?
                (i18n/da-en languages "Advarsel: " "Warning: ")
                (i18n/da-en languages "Fejl: " "Error: "))]
     ;; the shapes have their messages in English
     [:span {:lang "en"} message]
     (when n
       (str " " (i18n/da-en languages "Ressourcer" "Resources") ": " n))]))

(defn fetch-references!
  "Ask the server how many triples point to each of the resources `ids`,
  which the pending changes in `opts` unreference, and keep the answer as
  the :references of the session: the :ids that it is about, and the
  :counts, a map of a resource to a map of a property to a number. The
  labels of the properties are kept too.

  A request that is ended, e.g. by leaving the page, is made again the next
  time. One that fails isn't, so the list goes without the counts."
  [opts ids]
  #?(:cljs
     (let [qnames (map prefix/kw->qname (sort ids))
           url    (str "/dannet/edit/references?ids="
                       (js/encodeURIComponent (str/join "," qnames)))
           ;; an answer is dropped when the changes have changed
           same?  #(= ids (get-in @app/session [:references :ids]))]
       (swap! app/session assoc :references {:ids ids})
       (-> (app/fetch! url)
           (.then (fn [response]
                    (let [{:keys [references entities]}
                          (get-in response [:body :data])]
                      ;; an ended request is cleared by what ended it
                      (when response
                        (app/clear-current-fetch url))
                      (app/remember-labels! (edit/entity-labels opts entities))
                      (cond
                        (not (same?)) nil
                        (nil? response) (swap! app/session dissoc :references)
                        :else (swap! app/session assoc-in
                                     [:references :counts] references)))))
           (.catch #(app/clear-current-fetch url))))))

(def look-up-references
  "A mixin that asks the server how many references there are to what the
  pending changes unreference, since they aren't in the changes, and asks
  again when the changes unreference something else."
  (let [look! (fn [state]
                (let [[{:keys [changes references] :as opts}] (:rum/args state)
                      ids (change/unreferenced changes)]
                  (when (and (seq ids) (not= ids (:ids references)))
                    (fetch-references! opts ids)))
                state)]
    {:did-mount  look!
     :did-update look!}))

(defn focus-status!
  "Move the focus to the status of the list, e.g. before an edit that takes
  the button in focus away with its entry."
  []
  #?(:cljs (some-> (.querySelector js/document
                                   "article.changes [role=status]")
                   (.focus))))

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
              (if-some [button (or (aget buttons (inc i))
                                   (aget buttons (dec i)))]
                (.focus button)
                (focus-status!))))))
     state)})

(defn whole?
  "Is the `resource` one change in the pending changes of `opts`, so that
  its values have no buttons of their own? A new entry is, and so is an
  entry of the data that is removed as a whole."
  [{:keys [changes] :as opts} resource]
  (or (change/temporary? resource)
      (entry/removed? changes resource)))

(rum/defc pending-value < keep-focus
  "A value of the property `p` of the subject `s` in the list of the pending
  changes to the `resource`, on a page with `opts`. The `field` is the value
  (see row/fields). Its button undoes the change, or takes an added value
  away.

  A value that replaces another is shown below it, a moved one says which
  row it has moved from or to, and a removed entry says when the whole
  entry is removed. A value of an entry that is one change has no button
  (see whole?), and a new entry as a value has the button that takes that
  entry away."
  [{:keys [languages] :as opts} resource s p
   {:keys [value status original moved-to] :as field}]
  (let [content (fn [tag v class]
                  [tag (update (rdf/lone-value-attrs v) :class
                               #(remove nil? ["edit-value__content" class %]))
                   (rdf/value opts v)])
        text    (if (keyword? value) (rdf/link-label opts value) (str value))
        whole?  (whole? opts resource)
        note    (or (edit/move-note opts field)
                    (when (and (= status :retracted) (not whole?))
                      (edit/removed-note opts value)))
        main    (case status
                  :retracted (content :del value nil)
                  (:added :moved) (content :ins value nil)
                  :edited [:span.edit-value__content
                           (content :del original "edit-value__old")
                           (content :ins value nil)])
        t       [s p value]
        removal (str (i18n/da-en languages "Fjern" "Remove") ": " text)]
    [:li.edit-value {:class (str "edit-value--" (name status))}
     (if note
       [:span.edit-value__content main note]
       main)
     (when-not whole?
       [:span.edit-value__actions
        (cond
          (and (= status :added) (change/temporary? value))
          (edit/icon-button "remove" removal
                            (fn [_]
                              (focus-status!)
                              (edit/discard-entry! opts value)))

          (= status :added)
          (edit/icon-button "remove" removal
                            #(app/update-changes! change/without-addition t))

          :else
          (edit/icon-button
            "undo"
            (edit/undo-label languages (if moved-to :moved status) text)
            #(app/update-changes! change/without-triple t)))])]))

(declare changed-rows)

(rum/defc pending-node < keep-focus
  "A blank node of the property `p` in the list of the pending changes to
  the `resource`, on a page with `opts`: the values of the node as a nested
  table, with a button that takes a new node away or puts a removed one
  back. The `field` is the node (see rows). A node of an entry that is one
  change has no button (see whole?)."
  [{:keys [languages] :as opts} resource p
   {:keys [value status rows] :as field}]
  (let [removed? (= status :retracted)
        text     (edit/node-text opts p (map :value (mapcat :fields rows)))
        undo!    #(app/update-changes! change/without-node value)]
    [:li.edit-value.edit-node {:class (str "edit-value--" (name status))}
     [(if removed? :del :ins) {:class "edit-value__content"}
      (if (seq rows)
        (changed-rows opts resource rows status)
        (i18n/da-en languages "Ingen værdier endnu" "No values yet"))]
     (when-not (whole? opts resource)
       [:span.edit-value__actions
        (if removed?
          (edit/icon-button "undo"
                            (edit/undo-label languages :retracted text)
                            undo!)
          (edit/icon-button "remove"
                            (str (i18n/da-en languages "Fjern" "Remove")
                                 ": " text)
                            undo!))])]))

(rum/defc changed-rows
  "A table of the `rows` of the pending changes to the `resource` (see
  sections), on a page with `opts`, with what the check said about each row.

  The rows of a blank node that the changes add or remove are a nested
  table in the field of the node, with its status as `node-status`. The
  values of a removed node have no buttons of their own, since the node is
  put back as a whole."
  [{:keys [languages check] :as opts} resource rows node-status]
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
             (rum/with-key (pending-node opts resource property field) k)

             (= node-status :retracted)
             [:li (assoc (rdf/lone-value-attrs value) :key k)
              (rdf/value opts value)]

             :else
             (rum/with-key
               (pending-value opts resource subject property field)
               k)))]
        (when-not node-status
          (map #(problem languages %)
               (row-problems (:results check) resource row)))]])]])

(defn- reference-counts
  "The number of triples that point to the `resource`, which the pending
  changes in `opts` unreference, as a map of a property to a number. It is
  what the server last counted (see look-up-references), or nil when that
  count was made for other changes."
  [{:keys [changes references] :as opts} resource]
  (when (= (:ids references) (change/unreferenced changes))
    (get-in references [:counts resource])))

(rum/defc section
  "The pending changes to the `resource` as a table of its `rows` (see
  sections), on a page with `opts`, under a heading that links to the
  resource.

  A new entry has a button that takes it away as a whole. An entry that is
  removed as a whole has one that puts it back, and says how many
  references go with it once the server has counted them. A resource
  without rows only has what the check said about it (see other-problems)."
  [{:keys [languages check changes] :as opts} resource rows]
  [:section
   [:header
    [:h2 (rdf/resource-hyperlink resource opts)]
    (rdf/uri-link resource opts)
    (cond
      (change/temporary? resource)
      (edit/discard-entry-button opts resource
                                 (fn [_]
                                   (focus-status!)
                                   (edit/discard-entry! opts resource)))

      (entry/removed? changes resource)
      (edit/restore-entry-button opts resource
                                 (fn [_]
                                   (focus-status!)
                                   (edit/restore-entry! resource))))
    (when-let [counts (reference-counts opts resource)]
      [:p.entry-references
       (i18n/da-en languages
         "Henvisninger hertil fjernes også"
         "The references to it are removed too")
       ": "
       (str/join ", " (for [[p n] (sort-by (juxt (comp - val) key) counts)]
                        (str n " × " (rdf/link-label opts p))))])
    (map #(problem languages %)
         (section-problems (:results check) resource rows))]
   (when (seq rows)
     (changed-rows opts resource rows nil))])

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
  [{:keys [languages check] :as opts}]
  (let [{:keys [running? error change]} check]
    [:div.changes-check
     ;; a disabled button would lose the focus
     [:button {:type          "button"
               :aria-disabled (when running? "true")
               :on-click      #(when-not running?
                                 (check! opts))}
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

(rum/defc problem-summary
  "What the check said about resources that no change is made to, on a page
  with `opts`, when they are too many to list: each message of the
  `results` once, with the number of resources that it is about."
  [{:keys [languages] :as opts} results]
  [:section
   [:header
    [:h2 (i18n/da-en languages "Andre ressourcer" "Other resources")]
    (for [[_ about] (sort-by (comp - count val)
                             (group-by (juxt :severity :message) results))]
      (problem languages (first about)
               (count (distinct (map :focus-node about)))))]])

(rum/defc changes-list < look-up-references
  "The pending changes in `opts`, by the resource that each is made to, or
  a note that there are none. Their number is a status, so that an undo is
  read out, and it can take the focus when the last change is undone."
  [{:keys [languages changes nodes check] :as opts}]
  (let [sections (sections changes nodes)
        others   (other-problems (:results check) sections)
        n        (count changes)
        one?     (= 1 (count sections))]
    [:<>
     [:p.subheading {:role "status" :tab-index -1}
      (if (empty? sections)
        (i18n/da-en languages
          "Der er ingen ændringer endnu."
          "There are no changes yet.")
        (i18n/da-en languages
          (str n (if (= n 1) " ændring i " " ændringer i ")
               (count sections) (if one? " ressource" " ressourcer"))
          (str n (if (= n 1) " change to " " changes to ")
               (count sections) (if one? " resource" " resources"))))]
     (when (seq sections)
       [:<>
        (check-control opts)
        (for [{:keys [resource rows]} sections]
          (rum/with-key (section opts resource rows) (str resource)))
        ;; a removal can leave thousands of resources with the same
        ;; problem, so more than a few are summed up by message
        (if (<= (count others) 20)
          (for [resource (keys others)]
            (rum/with-key (section opts resource nil) (str resource)))
          (problem-summary opts (mapcat val others)))
        (submit-control opts)])]))
