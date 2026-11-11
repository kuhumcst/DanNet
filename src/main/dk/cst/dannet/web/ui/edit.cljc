(ns dk.cst.dannet.web.ui.edit
  "The fields of a row of the attribute-value table in edit mode.

  Every value that can be edited is a field with buttons that change it, and
  its input is of the input type of its row (see ui.input-type). Each edit is a
  pending change in the session (see dk.cst.dannet.change), and the fields
  show the values as the pending changes leave them."
  (:require [clojure.string :as str]
            [rum.core :as rum]
            [dk.cst.dannet.change :as change]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.web.app :as app]
            [dk.cst.dannet.web.i18n :as i18n]
            [dk.cst.dannet.web.ui.input-type :as input-type]
            [dk.cst.dannet.web.ui.lock :as lock]
            [dk.cst.dannet.web.ui.rdf :as rdf]))

(defn icon-button
  "A button that shows the `icon` and is named by `label`, which is its
  tooltip too. It calls `on-click`."
  [icon label on-click]
  [:button.edit-value__button {:type     "button"
                               :title    label
                               :on-click on-click}
   [:span {:class       ["edit-value__icon" (str "edit-value__icon--" icon)]
           :aria-hidden "true"}]
   [:span.visually-hidden label]])

(defn padlock
  "A padlock icon whose tooltip and accessible name is `why`, the reason that
  the row or value it marks can't be edited."
  [why]
  [:span.padlock {:role "img" :aria-label why :title why}])

(def show-modal
  "A mixin that opens a <dialog> as a modal one when it is drawn, which
  keeps the focus inside it and closes it on Escape. The focus starts in
  its input."
  {:did-mount (fn [state]
                #?(:cljs (let [dialog (rum/dom-node state)]
                           (.showModal dialog)
                           (some-> (.querySelector dialog "input") (.focus))))
                state)})

(rum/defcs search-dialog < show-modal
                           (rum/local nil ::results)
                           (rum/local 0 ::searches)
  "A dialog that finds a value with the `search` (see ui.input-type) and calls
  `on-value` with the one that is chosen, or `on-close` when it is closed
  without one."
  [state {:keys [languages] :as opts}
   {:keys [results-fn value-fn label-fn hiccup-fn title placeholder] :as search}
   {:keys [on-value on-close]}]
  (let [results  (::results state)
        searches (::searches state)
        localize #(i18n/da-en languages (:da %) (:en %))
        ;; Only the results of the latest search are shown, and a search
        ;; waits until the typing pauses.
        search!  (fn [s]
                   #?(:cljs
                      (let [n (swap! searches inc)]
                        (js/setTimeout
                          (fn []
                            (when (= n @searches)
                              (.then (js/Promise.resolve (results-fn s))
                                     #(when (= n @searches)
                                        (reset! results (vec %))))))
                          200))))]
    [:dialog.search-dialog
     {:aria-label (localize title)
      :on-close   on-close
      ;; a click on the backdrop is a click on the dialog itself
      :on-click   (fn [e]
                    (when (= (.-target e) (.-currentTarget e))
                      (.close (.-currentTarget e))))}
     [:header
      [:h2 (localize title)]
      (icon-button "remove" (i18n/da-en languages "Luk" "Close")
                   (fn [e] (.close (.closest (.-target e) "dialog"))))]
     [:input {:type          "search"
              :auto-complete "off"
              :aria-label    (localize title)
              :placeholder   (localize placeholder)
              :on-change     #(search! (.. % -target -value))}]
     ;; the number of results is announced, not every result
     [:p.visually-hidden {:role "status"}
      (when-let [n (some-> @results count)]
        (i18n/da-en languages (str n " resultater") (str n " results")))]
     [:div.search-dialog__body
      (cond
        (nil? @results)
        nil

        (empty? @results)
        [:p (i18n/da-en languages "Ingen resultater" "No results")]

        :else
        [:ul.search-dialog__results
         (for [result @results
               :let [v (value-fn result)]]
           [:li {:key (pr-str v)}
            [:button {:type     "button"
                      :on-click (fn [_]
                                  (when label-fn
                                    (input-type/remember-label!
                                      v (label-fn result)))
                                  (on-value v))}
             (hiccup-fn result opts)]])])]]))

(rum/defc options-input
  "A dropdown of the `options` that starts out with the `value`, when there
  is one, and is named by `label`. It calls `on-value` with another option
  that is chosen, and `on-cancel` when none is."
  [opts options {:keys [value label on-value on-cancel]}]
  (let [qname (some-> value prefix/kw->qname)]
    [:select.edit-value__input
     {:aria-label    label
      :auto-focus    true
      :default-value (str qname)
      :on-blur       (fn [_] (on-cancel))
      :on-key-down   (fn [e]
                       (when (= "Escape" (.-key e))
                         (on-cancel)))
      :on-change     (fn [e]
                       (let [qname' (.. e -target -value)]
                         (if (contains? #{"" qname} qname')
                           (on-cancel)
                           (on-value (prefix/qname->kw qname')))))}
     ;; a value that isn't an option is shown as none
     (when-not (some #{value} options)
       [:option {:value ""} "…"])
     (for [option options]
       [:option {:key   (str option)
                 :value (prefix/kw->qname option)}
        (rdf/link-label opts option)])]))

(rum/defcs text-input < (rum/local false ::invalid?)
                        (rum/local false ::entering?)
                        (rum/local false ::searching?)
  "A text input for a value of the `input-type` (see ui.input-type), with a
  button for its search when it has one. It starts out with the `value`,
  when there is one, and is named by `label`.

  Enter or leaving the input enters the text, and Escape leaves the value as
  it was: the input calls `on-value` with a new value and `on-cancel` when
  there is none. A text that isn't a value stays in the input, which is then
  invalid and has a wavy line under its text. The `like` value is one that a
  new value should resemble, e.g. in its language."
  [state {:keys [languages] :as opts}
   {:keys [parse-fn format-fn search] :as input-type}
   {:keys [value like label on-value on-cancel]}]
  (let [invalid?   (::invalid? state)
        entering?  (::entering? state)
        searching? (::searching? state)
        old-text   (if (some? value) (format-fn value) "")
        message    (i18n/da-en languages
                     "Ikke en gyldig værdi" "Not a valid value")
        ;; Leaving the input for its own search button isn't leaving it.
        to-button? (fn [e]
                     (some-> (.-relatedTarget e)
                             (.closest ".edit-value__actions")
                             (.-parentNode)
                             (= (.-parentNode (.-target e)))))
        ;; A text is entered once, though Enter may be followed by the
        ;; input losing the focus. A text that isn't a value makes the input
        ;; invalid; the browser only reports it on Enter, since a report
        ;; takes the focus back.
        enter!     (fn [e report?]
                     (let [input (.-target e)
                           s     (str/trim (.-value input))]
                       (cond
                         @entering?
                         nil

                         (or (empty? s) (= s old-text))
                         (on-cancel)

                         :else
                         #?(:clj nil
                            :cljs
                            (do
                              (reset! entering? true)
                              (.then (js/Promise.resolve
                                       (parse-fn s (if (some? value)
                                                     value
                                                     like)))
                                     (fn [v]
                                       (if (some? v)
                                         (on-value v)
                                         (do
                                           (reset! entering? false)
                                           (reset! invalid? true)
                                           (.setCustomValidity input message)
                                           (when report?
                                             (.reportValidity input)))))))))))]
    [:<>
     [:textarea.edit-value__input
      {:default-value old-text
       :lang          (i18n/lang (if (some? value) value like))
       :aria-label    label
       :aria-invalid  (when @invalid? "true")
       :title         (when @invalid? message)
       :auto-focus    true
       :rows          1
       :spell-check   (when search "false")
       ;; the caret starts at the end of the text
       :on-focus      (fn [e]
                        (let [n (count (.. e -target -value))]
                          (.setSelectionRange (.-target e) n n)))
       :on-change     (fn [e]
                        (reset! invalid? false)
                        (.setCustomValidity (.-target e) ""))
       :on-blur       (fn [e]
                        (when-not (or (to-button? e) @searching?)
                          (enter! e false)))
       :on-key-down   (fn [e]
                        (case (.-key e)
                          "Enter" (do (.preventDefault e) (enter! e true))
                          "Escape" (do (reset! entering? true) (on-cancel))
                          nil))}]
     (when search
       [:span.edit-value__actions
        (icon-button "search" (i18n/da-en languages "Søg" "Search")
                     #(reset! searching? true))])
     (when @searching?
       (search-dialog opts search
                      {:on-value on-value
                       ;; the focus goes back to the input
                       :on-close (fn [e]
                                   (reset! searching? false)
                                   (some-> (.. e -target -parentNode)
                                           (.querySelector ".edit-value__input")
                                           (.focus)))}))]))

(defn input
  "The input of a value of the `input-type` with the `props` of the input: a
  dropdown of its options, or else a text input."
  [opts {:keys [options] :as input-type} props]
  (if options
    (options-input opts options props)
    (text-input opts input-type props)))

(def refocus
  "A mixin for a field whose buttons give way to an input. When the field
  has set ::refocus? and is drawn again, the focus goes to its first button,
  unless it has gone elsewhere, so that the keyboard doesn't lose its place
  when the input closes."
  {:init       (fn [state _]
                 (assoc state ::refocus? (volatile! false)))
   :did-update (fn [state]
                 #?(:cljs
                    (when @(::refocus? state)
                      (vreset! (::refocus? state) false)
                      (when (= js/document.body js/document.activeElement)
                        (some-> (rum/dom-node state)
                                (.querySelector "button")
                                (.focus)))))
                 state)})

(defn- value-text
  "The value `v` as the text that names it in a label or a tooltip."
  [opts v]
  (if (keyword? v)
    (rdf/link-label opts v)
    (str v)))

(rum/defcs value-field < (rum/local false ::editing?) refocus
  "A value `v` of the property `k` in `opts` that can be edited, framed like
  an input field. The property is the one that holds the value, which in the
  row of members is an rdf:_N property. Its `props` are the :input-type of
  its input and its :status among the pending changes: :added, :retracted,
  :edited when it replaces the value :original, or nil.

  Its buttons remove it, or undo the change that removed, added or edited
  it. The pencil turns it into the input, and so does a click elsewhere in
  the field. A value entered there replaces it."
  [state {:keys [subject languages comments] :as opts}
   k v {:keys [input-type status original]}]
  (let [editing? (::editing? state)
        t        [subject k v]
        pencil?  (not= status :retracted)
        ;; a pending value isn't in the data yet, so it has no origins
        shared?  (and (not (contains? #{:added :edited} status))
                      (next (lock/value-origins opts k v)))
        label    #(str (i18n/da-en languages %1 %2) ": " (value-text opts v))
        change!  (fn [f & args]
                   (fn [_] (apply app/update-changes! f args)))
        close!   (fn []
                   (vreset! (::refocus? state) true)
                   (reset! editing? false))
        ;; A click anywhere in the field opens its input, as the pencil does,
        ;; unless it is on a link or a button or selects text.
        open!    (fn [e]
                   #?(:cljs (when-not (or @editing?
                                          (.closest (.-target e)
                                                    "a, button, [tabindex]")
                                          (seq (str (js/getSelection))))
                              (reset! editing? true))))
        titles   [(when original
                    (str (i18n/da-en languages "Før" "Before") ": "
                         (value-text opts original)))
                  (when shared?
                    (:shared comments))]]
    [:li.edit-value
     (cond-> {:class []}
       pencil? (-> (assoc :on-click open!)
                   (update :class conj "edit-value--clickable"))
       status (update :class conj (str "edit-value--" (name status)))
       (some some? titles) (assoc :title (str/join "; " (remove nil? titles))))
     (if @editing?
       (input opts input-type
              {:value     v
               :label     (label "Ret" "Edit")
               :on-cancel close!
               :on-value  (fn [v']
                            (close!)
                            (app/update-changes!
                              change/replace t [subject k v']))})
       ;; a removed value is a <del>, an added or edited one an <ins>
       [(case status :retracted :del (:added :edited) :ins :span)
        (update (rdf/lone-value-attrs v) :class
                #(remove nil? ["edit-value__content" %]))
        (rdf/value opts v)])
     (when-not @editing?
       [:span.edit-value__actions
        (when pencil?
          (icon-button "edit" (label "Ret" "Edit") #(reset! editing? true)))
        (case status
          :retracted (icon-button "undo" (label "Fortryd fjernelse af"
                                                "Undo removal of")
                                  (change! change/without-triple t))
          :added (icon-button "remove" (label "Fjern" "Remove")
                              (change! change/without-triple t))
          ;; removing an edited value removes the value it replaces
          :edited [:<>
                   (icon-button "undo" (label "Fortryd rettelse af"
                                              "Undo edit of")
                                (change! change/without-triple t))
                   (icon-button "remove" (label "Fjern" "Remove")
                                (change! #(-> (change/without-triple % t)
                                              (change/retract
                                                [subject k original]))))]
          (icon-button "remove" (label "Fjern" "Remove")
                       (change! change/retract t)))])]))

(rum/defcs new-value-field < (rum/local false ::adding?) refocus
  "An empty field at the end of a row in `opts`, whose button turns it into
  the input of the `input-type`. A value entered there is added to the
  property `k`, unless it is one of the `taken` values.

  A new value resembles the first of the taken ones, e.g. in its language."
  [state {:keys [subject languages] :as opts} k taken input-type]
  (let [adding? (::adding? state)
        label   (i18n/da-en languages "Tilføj en værdi" "Add a value")
        close!  (fn []
                  (vreset! (::refocus? state) true)
                  (reset! adding? false))]
    [:li.edit-value {:class (when-not @adding? "edit-value--new")}
     (if @adding?
       (input opts input-type
              {:like      (first taken)
               :label     label
               :on-cancel close!
               :on-value  (fn [v]
                            (close!)
                            (when-not (some #{v} taken)
                              (app/update-changes!
                                change/add [subject k v])))})
       ;; the plus says it to the eye, the label to a screen reader
       [:button.edit-value__add {:type     "button"
                                 :title    label
                                 :on-click #(reset! adding? true)}
        [:span.edit-value__icon.edit-value__icon--add {:aria-hidden "true"}]
        [:span.visually-hidden label]])]))

(defn value-order
  "The values `vs` of a property in the order of its fields, with the
  pending `changes` to the property made (see change/property-changes): a
  replaced value gives its place to the value that replaces it, and the
  other added values come last."
  [vs {:keys [added replaced] :as changes}]
  (let [replacing (fn [v] (filter added (get replaced v)))]
    ;; distinct, as a value can be replaced by one that is already there
    (distinct (concat (mapcat #(or (seq (replacing %)) [%]) vs)
                      (sort-by str added)))))

;; A row lists the values of one property, except the row of members, which
;; the table makes of the rdf:_N properties of a container (see
;; shared/member-row). A change is made to the property that holds a value,
;; so the functions below work with pairs of a property and a value.

(defn row-properties
  "The properties whose values the row of the property `k` in `opts` lists.

  That is `k` itself. In the row of members, the rdf:_N properties that the
  entity or a pending change has come before it, in the order of their
  ordinals."
  [{:keys [subject entity changes] :as opts} k]
  (if (= k shared/member-row)
    (-> (->> (concat (keys entity) (change/changed-properties changes subject))
             (filter shared/member-property?)
             (distinct)
             (sort-by shared/member-ordinal))
        (concat [k]))
    [k]))

(defn new-value-property
  "The property that a new value in the row of the property `k` is added
  to, when the row lists the values of the `properties` (see row-properties).

  That is `k` itself, or in the row of members, the rdf:_N property after
  the highest one. A new member never takes the ordinal of a member whose
  removal is pending, so that undoing the removal can't give two members the
  same ordinal. A removed member therefore leaves a gap in the ordinals."
  [k properties]
  (if (= k shared/member-row)
    (->> (filter shared/member-property? properties)
         (map shared/member-ordinal)
         (reduce max 0)
         (inc)
         (str "_")
         (keyword "rdf"))
    k))

(defn row-fields
  "The fields of a row, with the pending `changes` to the `properties` of
  the subject `s` made. The `entries` are the values of the row that can be
  edited, each as a pair of the property that holds it and the value.

  A field is a map of its :property and :value. A value with a pending
  change also has its :status, which is :added, :retracted or :edited, and
  an edited one has the :original value that it replaces. The fields are in
  the order of `properties`, and those of one property in the order of its
  values (see value-order)."
  [changes s properties entries]
  (let [p->vs (update-vals (group-by first entries) #(map second %))]
    (for [p    properties
          :let [{:keys [added retracted replaced]
                 :as   pending} (change/property-changes changes s p)
                original (into {}
                               (for [[old replacing] replaced
                                     v               replacing]
                                 [v old]))]
          v    (value-order (get p->vs p) pending)
          :let [status (cond
                         (retracted v) :retracted
                         (contains? original v) :edited
                         (added v) :added)]]
      (cond-> {:property p :value v}
        status (assoc :status status)
        (= status :edited) (assoc :original (get original v))))))

(rum/defc cell
  "A table cell that lists every one of the values `vs` in the row of the
  property `k` in `opts`, for a row in edit mode, with the pending changes
  made: an edited value shows its new text. The values that can be edited
  are followed by a field for adding one. A value that can't be edited has a
  padlock that says why, and it comes last."
  [{:keys [subject changes comments] :as opts} k vs]
  (let [sorted      (if (set? vs) (sort-by (rdf/label-sortkey-fn opts) vs) vs)
        entries     (distinct (for [v sorted
                                    p (lock/value-properties opts k v)]
                                [p v]))
        entry->lock (into {}
                          (map (fn [[p v :as entry]]
                                 [entry (lock/lock opts p v)]))
                          entries)
        properties  (row-properties opts k)
        fields      (row-fields changes subject properties
                                (remove entry->lock entries))
        input-type  (input-type/input-type k vs)
        values      (concat (map :value fields)
                            (map second (filter entry->lock entries)))
        ;; a new value is one of the options that the row doesn't have
        new-type    (cond-> input-type
                      (:options input-type) (update :options
                                                    #(remove (set values) %)))
        ;; A value in a field can't be added again, but a locked value can
        ;; be, since the graph that changes go to doesn't hold it. A
        ;; container can hold a member twice, so its row takes any value.
        taken       (when-not (= k shared/member-row)
                      (map :value fields))
        item-key    (fn [p v] (pr-str [p (if (map? v) (:rdf/about v) v)]))]
    [:td
     [:ul.edit-values
      (for [{:keys [property value original] :as field} fields]
        ;; keyed by its property and by the value that it replaces, so that
        ;; an edit keeps its field
        (rum/with-key (value-field opts property value
                                   (assoc field :input-type input-type))
                      (item-key property (if (some? original) original value))))
      ;; a row whose options are all taken has nothing to add
      (when-not (some-> (:options new-type) empty?)
        (rum/with-key (new-value-field opts (new-value-property k properties)
                                       taken new-type)
                      "new"))
      (for [[p v :as entry] (filter entry->lock entries)]
        [:li.edit-value.edit-value--locked
         (assoc (rdf/lone-value-attrs v) :key (item-key p v))
         (padlock (get-in comments [:locked (entry->lock entry)]))
         (rdf/value opts v)])]]))
