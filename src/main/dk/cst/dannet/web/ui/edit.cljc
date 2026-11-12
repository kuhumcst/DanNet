(ns dk.cst.dannet.web.ui.edit
  "The fields of a row of the attribute-value table in edit mode.

  A field shows one value, and each edit is a pending change in the session
  (see dk.cst.dannet.change). The fields follow five rules:

  1. A field is open, as an input, only while the focus is in it. Enter or
     leaving the field enters the text, and Escape drops what was typed.
  2. The tint says how the field differs from the data: yellow replaces a
     value, green adds one and red removes one.
  3. A text that isn't a value has a wavy line under it, and it isn't a
     pending change yet.
  4. Each button does one thing, and the tint decides whether a field has
     it (see field-buttons).
  5. The buttons come in the order edit, undo, remove.

  The :edit-rules of the page (see dk.cst.dannet.editable) name the
  :languages that a text can be in, where nil is none, and the :translated
  properties, whose rows show which language has no text yet."
  (:require [clojure.string :as str]
            [rum.core :as rum]
            [dk.cst.dannet.change :as change]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.web.app :as app]
            [dk.cst.dannet.web.i18n :as i18n]
            [dk.cst.dannet.web.ui.edit.row :as row]
            [dk.cst.dannet.web.ui.input-type :as input-type]
            [dk.cst.dannet.web.ui.lock :as lock]
            [dk.cst.dannet.web.ui.rdf :as rdf]))

(defn icon-button
  "A button with the `icon` that calls `on-click`. The `label` is its name
  and its tooltip."
  [icon label on-click]
  [:button.edit-value__button {:type     "button"
                               :key      icon
                               :title    label
                               :on-click on-click}
   [:span {:class       ["edit-value__icon" (str "edit-value__icon--" icon)]
           :aria-hidden "true"}]
   [:span.visually-hidden label]])

(defn padlock
  "A padlock icon that says `why` a row or a value can't be edited."
  [why]
  [:span.padlock {:role "img" :aria-label why :title why}])

(def language-names
  "The Danish and English names of the languages of a text, where nil is
  none."
  {"da" ["dansk" "Danish"]
   "en" ["engelsk" "English"]
   nil  ["intet sprog" "no language"]})

(defn language-name
  "The Danish or English name of the language `lang`, by the reader's
  `languages`. A language without a name keeps its tag."
  [languages lang]
  (if-let [[da en] (get language-names lang)]
    (i18n/da-en languages da en)
    lang))

(defn lang-select
  "A dropdown of the languages `langs` that shows `lang` as the tag of a
  text, or that a language can be added. It calls `on-lang` with a chosen
  language, and `attrs` are added to its attributes."
  [{:keys [languages] :as opts} langs lang on-lang & [attrs]]
  (let [label (str (i18n/da-en languages "Sprog" "Language") ": "
                   (language-name languages lang))]
    [:select.edit-value__lang
     (merge {:aria-label label
             :title      label
             :value      (str lang)
             :on-change  (fn [e]
                           (on-lang (not-empty (.. e -target -value))))}
            attrs)
     ;; the language of the text is shown even when it isn't a choice
     (for [lang' (cond-> (vec langs)
                   (not-any? #(= lang %) langs) (conj lang))]
       (cond
         (some? lang')
         [:option {:key lang' :value lang'} lang']

         ;; choosing no language removes the tag of a text
         (some? lang)
         [:option {:key "" :value ""} (language-name languages nil)]

         ;; a text without a tag shows this, which the menu leaves out
         :else
         [:option {:key "" :value "" :hidden true}
          (i18n/da-en languages "tilføj sprog" "add language")]))]))

(def show-modal
  "A mixin that opens a <dialog> as a modal when it is drawn, with the
  focus in its input."
  {:did-mount (fn [state]
                #?(:cljs (let [dialog (rum/dom-node state)]
                           (.showModal dialog)
                           (some-> (.querySelector dialog "input") (.focus))))
                state)})

(rum/defcs search-dialog < show-modal
                           (rum/local nil ::results)
                           (rum/local 0 ::searches)
  "A dialog that finds a value with the `search` (see ui.input-type). It
  calls `on-value` with the chosen one, or `on-close` when there is none."
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
  "A dropdown of the `options` named by `label`, starting with the `value`.
  It calls `on-value` with a chosen option, or `on-cancel` when none is."
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

(defn- invalid-message
  "The message of a text that isn't a value, for a reader with `languages`."
  [languages]
  (i18n/da-en languages "Ikke en gyldig værdi" "Not a valid value"))

(rum/defcs text-input < (rum/local nil ::invalid?)
                        (rum/local false ::entering?)
                        (rum/local false ::searching?)
                        (rum/local nil ::chosen)
  "A text input for a value of the `input-type` (see ui.input-type), named by
  `label`, that starts out with the `draft` or else the `value`.

  Enter or the focus leaving it enters the text: the input calls `on-value`
  with a value, `on-invalid` with a text that isn't one and whether the
  focus is leaving, or `on-cancel` when nothing is new. Escape cancels too.
  A text is in the language `lang`, or the one chosen among `langs`. The
  `undo` is the label and the function of an undo button."
  [state {:keys [languages] :as opts}
   {:keys [parse-fn format-fn search] :as input-type}
   {:keys [value draft lang langs label undo on-value on-cancel on-invalid]}]
  (let [entering?  (::entering? state)
        searching? (::searching? state)
        ;; nil until the text is checked or typed in: a draft is invalid
        invalid?   (::invalid? state)
        invalid?'  (if (nil? @invalid?) (some? draft) @invalid?)
        ;; the chosen language is kept in a vector, since nil is a choice too
        chosen     (::chosen state)
        lang'      (if-let [[choice] @chosen] choice lang)
        old-text   (if (some? value) (format-fn value) "")
        message    (invalid-message languages)
        ;; A text is entered once, though the focus may leave right after
        ;; Enter. The browser's report takes the focus, so it is for Enter.
        enter!     (fn [input leaving?]
                     (let [s (str/trim (.-value input))]
                       (cond
                         @entering?
                         nil

                         ;; nothing new since a text was last entered
                         (or (empty? s)
                             (and (= s (or draft old-text)) (= lang' lang)))
                         (on-cancel)

                         :else
                         #?(:clj nil
                            :cljs
                            (do
                              (reset! entering? true)
                              (.then (js/Promise.resolve (parse-fn s lang'))
                                     (fn [v]
                                       (if (some? v)
                                         (on-value v)
                                         (do
                                           (reset! entering? false)
                                           (reset! invalid? true)
                                           (.setCustomValidity input message)
                                           (on-invalid s leaving?)
                                           (when-not leaving?
                                             (.reportValidity input)))))))))))
        cancel!    (fn []
                     (reset! entering? true)
                     (on-cancel))
        ;; moving between the input and its controls isn't leaving
        leave!     (fn [e]
                     (let [entry (.-currentTarget e)]
                       (when-not (or @searching?
                                     (some->> (.-relatedTarget e)
                                              (.contains entry)))
                         (enter! (.querySelector entry "textarea") true))))]
    [:span.edit-value__entry {:on-blur leave!}
     [:textarea.edit-value__input
      {:default-value (or draft old-text)
       :lang          lang'
       :aria-label    label
       :aria-invalid  (when invalid?' "true")
       :title         (when invalid?' message)
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
       :on-key-down   (fn [e]
                        (case (.-key e)
                          "Enter" (do (.preventDefault e)
                                      (enter! (.-target e) false))
                          "Escape" (cancel!)
                          nil))}]
     (when langs
       (lang-select opts langs lang' #(reset! chosen [%])
                    {:on-key-down (fn [e]
                                    (when (= "Escape" (.-key e))
                                      (cancel!)))}))
     (when (or undo search)
       [:span.edit-value__actions
        (when-let [[label f] undo]
          (icon-button "undo" label f))
        (when search
          (icon-button "search" (i18n/da-en languages "Søg" "Search")
                       #(reset! searching? true)))])
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
  "The input for a value of the `input-type` with the `props`: a dropdown of
  its options, or else a text input."
  [opts {:keys [options] :as input-type} props]
  (if options
    (options-input opts options props)
    (text-input opts input-type props)))

(def refocus
  "A mixin for a field that replaces its controls. When the field has set
  ::refocus to a selector and is drawn again, the control that matches gets
  the focus, unless the focus has gone elsewhere."
  {:init       (fn [state _]
                 (assoc state ::refocus (volatile! nil)))
   :did-update (fn [state]
                 #?(:cljs
                    (when-let [selector @(::refocus state)]
                      (vreset! (::refocus state) nil)
                      (when (= js/document.body js/document.activeElement)
                        (some-> (rum/dom-node state)
                                (.querySelector selector)
                                (.focus)))))
                 state)})

(defn field-buttons
  "The buttons of a field in their order, by whether it is `open?` as an
  input and by its `tint`: nil, :edited, :added or :retracted.

  - :edit opens the input: on a closed field, unless its value is removed.
  - :undo puts the field back as the data has it: on any field with a tint.
  - :remove marks the value in the data for removal: on a closed field
    with no tint or an edited one."
  [open? tint]
  (cond-> []
    (and (not open?) (not= tint :retracted)) (conj :edit)
    tint (conj :undo)
    (and (not open?) (contains? #{nil :edited} tint)) (conj :remove)))

(defn- buttons
  "The buttons of a closed field with the `tint` (see field-buttons), from
  `actions`, a map of a button to its label and function."
  [tint actions]
  [:span.edit-value__actions
   (for [button (field-buttons false tint)
         :let   [[label f] (get actions button)]]
     (icon-button (name button) label f))])

(defn- opens-input?
  "Does the click `e` open the input of a closed field? Not on a link or a
  control, or when it selects text."
  [e]
  #?(:clj  false
     :cljs (not (or (.closest (.-target e) "a, button, select, [tabindex]")
                    (seq (str (js/getSelection)))))))

(defn- invalid-content
  "The content of a closed field whose entered text `s` isn't a value."
  [s]
  [:ins.edit-value__content.edit-value__content--invalid s])

(defn- subject-of
  "The subject of the values in a table with `opts`: the blank node of a
  nested table, or else the resource of the page."
  [{:keys [subject blank-node] :as opts}]
  (if blank-node
    (:rdf/about blank-node)
    subject))

(defn- node-place
  "The place of the blank node of a nested table with `opts`, as
  change/locate-nodes takes it: the :subject and :property that hold the
  node, and those of its :values that can be edited."
  [{:keys [subject blank-node] :as opts}]
  (let [{:keys [property entity]} blank-node
        editable (fn [k vs]
                   (into #{}
                         (filter #(nil? (lock/lock opts k %)))
                         (shared/setify vs)))]
    {:subject  subject
     :property property
     :values   (into {}
                     (keep (fn [[k vs]]
                             (when-let [vs (not-empty (editable k vs))]
                               [k vs])))
                     entity)}))

(defn- update-changes!
  "Apply `f` and `args` to the pending changes, for an edit in a table with
  `opts`. An edit in the nested table of a blank node also keeps the place
  of the node, which its changes are located by when they are submitted."
  [{:keys [blank-node] :as opts} f & args]
  (when blank-node
    (app/remember-node! (:rdf/about blank-node) (node-place opts)))
  (apply app/update-changes! f args))

(defn- value-text
  "The value `v` as the text that names it in a label or a tooltip."
  [opts v]
  (if (keyword? v)
    (rdf/link-label opts v)
    (str v)))

(rum/defcs value-field < (rum/local false ::editing?)
                         (rum/local nil ::draft)
                         refocus
  "A field for the value `v` of the property `k` in `opts`, which is an
  rdf:_N property in the row of members.

  The `props` are the :input-type of its input, the :langs of a text, its
  :status among the pending changes and the :original value that an edited
  one replaces. An entered text that isn't a value is kept as a draft and
  shown as an edit. The tag of a text is a dropdown that changes its
  language."
  [state {:keys [languages comments] :as opts}
   k v {:keys [input-type status original langs]}]
  (let [editing? (::editing? state)
        draft    (::draft state)
        subject  (subject-of opts)
        t        [subject k v]
        ;; a draft is an edit, unless the value that it would replace is new
        tint     (if (and @draft (not= status :added)) :edited status)
        lang     (i18n/lang v)
        langs    (when (i18n/rdf-string? v) langs)
        ;; a pending value isn't in the data yet, so it has no origins
        shared?  (and (not (contains? #{:added :edited} status))
                      (next (lock/value-origins opts k v)))
        label    #(str (i18n/da-en languages %1 %2) ": "
                       (or @draft (value-text opts v)))
        close!   (fn []
                   (vreset! (::refocus state) "button")
                   (reset! editing? false))
        open!    (fn [e]
                   (when (and (not @editing?) (opens-input? e))
                     (reset! editing? true)))
        ;; a new status replaces the content, and with it the control in focus
        retag!   (fn [lang']
                   (vreset! (::refocus state) (if lang' "select" "button"))
                   (update-changes!
                     opts change/replace
                     t [subject k (i18n/with-lang v lang')]))
        undo     [(case tint
                    :retracted (label "Fortryd fjernelse af" "Undo removal of")
                    :added (label "Fortryd tilføjelse af" "Undo addition of")
                    (label "Fortryd rettelse af" "Undo edit of"))
                  (fn [_]
                    (reset! draft nil)
                    (close!)
                    (when status
                      (update-changes! opts change/without-triple t)))]
        ;; removing an edited value removes the value that it replaces
        remove!  (fn [_]
                   (reset! draft nil)
                   (update-changes!
                     opts
                     (if (= status :edited)
                       #(-> (change/without-triple % t)
                            (change/retract [subject k original]))
                       #(change/retract % t))))
        before   (cond
                   (some? original) original
                   @draft v)
        ;; the tooltip names the old language when the language has changed
        titles   [(when @draft
                    (invalid-message languages))
                  (when (some? before)
                    (str (i18n/da-en languages "Før" "Before") ": "
                         (value-text opts before)
                         (when (and (not @draft)
                                    (not= lang (i18n/lang before)))
                           (str " (" (language-name languages
                                                    (i18n/lang before))
                                ")"))))
                  (when shared?
                    (:shared comments))]]
    [:li.edit-value
     (cond-> {:class []}
       (not= tint :retracted) (-> (assoc :on-click open!)
                                  (update :class conj "edit-value--clickable"))
       tint (update :class conj (str "edit-value--" (name tint)))
       (some some? titles) (assoc :title (str/join "; " (remove nil? titles))))
     (cond
       @editing?
       (input opts input-type
              {:value      v
               :draft      @draft
               :lang       lang
               :langs      langs
               :label      (label "Ret" "Edit")
               :undo       (when (some #{:undo} (field-buttons true tint))
                             undo)
               :on-cancel  close!
               :on-invalid (fn [s leaving?]
                             (reset! draft s)
                             (when leaving?
                               (close!)))
               :on-value   (fn [v']
                             (reset! draft nil)
                             (close!)
                             (when (not= v v')
                               (update-changes!
                                 opts change/replace t [subject k v'])))})

       @draft
       (invalid-content @draft)

       ;; a removed value is a <del>, an added or edited one an <ins>
       :else
       [(case status :retracted :del (:added :edited) :ins :span)
        (update (rdf/lone-value-attrs v) :class
                #(remove nil? ["edit-value__content" %]))
        (rdf/value opts v)
        ;; a text without a tag gets its dropdown in the input
        (when (and langs lang (not= status :retracted))
          (lang-select opts langs lang retag!))])
     (when-not @editing?
       (buttons tint {:edit   [(label "Ret" "Edit") #(reset! editing? true)]
                      :undo   undo
                      :remove [(label "Fjern" "Remove") remove!]}))]))

(rum/defcs new-value-field < (rum/local false ::adding?)
                             (rum/local nil ::draft)
                             refocus
  "The field for a new value of the property `k` in `opts`: a button that
  opens an input. An entered value is added unless it is `taken`.

  The `props` are the :input-type of the input, the :lang of a new text, the
  :langs that it can be in and :gap? when the row has no text in that
  language, which the button then names. An entered text that isn't a value
  is kept as a draft and shown as an addition."
  [state {:keys [languages] :as opts}
   k taken {:keys [input-type lang langs gap?]}]
  (let [adding? (::adding? state)
        draft   (::draft state)
        subject (subject-of opts)
        tint    (when @draft :added)
        label   (cond-> (i18n/da-en languages "Tilføj en værdi" "Add a value")
                  gap? (str " " (i18n/da-en languages "på" "in")
                            " " (language-name languages lang)))
        close!  (fn []
                  (vreset! (::refocus state) "button")
                  (reset! adding? false))
        open!   (fn [e]
                  (when (and (not @adding?) (opens-input? e))
                    (reset! adding? true)))
        undo    [(str (i18n/da-en languages
                        "Fortryd tilføjelse af" "Undo addition of")
                      ": " @draft)
                 (fn [_]
                   (reset! draft nil)
                   (close!))]]
    [:li.edit-value
     (cond
       @adding? {:class (when tint "edit-value--added")}
       @draft {:class    ["edit-value--added" "edit-value--clickable"]
               :title    (invalid-message languages)
               :on-click open!}
       :else {:class (cond-> ["edit-value--new"]
                       gap? (conj "edit-value--gap"))})
     (cond
       @adding?
       (input opts input-type
              {:draft      @draft
               :lang       lang
               :langs      langs
               :label      label
               :undo       (when (some #{:undo} (field-buttons true tint))
                             undo)
               :on-cancel  close!
               :on-invalid (fn [s leaving?]
                             (reset! draft s)
                             (when leaving?
                               (close!)))
               :on-value   (fn [v]
                             (reset! draft nil)
                             (close!)
                             (when-not (some #{v} taken)
                               (update-changes!
                                 opts change/add [subject k v])))})

       @draft
       [:<>
        (invalid-content @draft)
        (buttons tint {:edit [(str (i18n/da-en languages "Ret" "Edit")
                                   ": " @draft)
                              #(reset! adding? true)]
                       :undo undo})]

       ;; the plus says it to the eye, the label to a screen reader
       :else
       [:button.edit-value__add {:type     "button"
                                 :title    label
                                 :on-click #(reset! adding? true)}
        [:span.edit-value__icon.edit-value__icon--add {:aria-hidden "true"}]
        (when gap?
          [:span.edit-value__gap {:aria-hidden "true"} lang])
        [:span.visually-hidden label]])]))

(rum/defc cell
  "A table cell for the values `vs` in the row of the property `k` in `opts`
  in edit mode: a field for each value that can be edited, a field for a new
  one, and last the locked values with a padlock that says why.

  A set of values is sorted by language, the reader's preferred one first,
  and then by text. A new text gets the language that a row of translations
  is missing (see ui.edit.row/missing-lang), or else that of the first field."
  [{:keys [changes comments languages edit-rules] :as opts} k vs]
  (let [subject     (subject-of opts)
        rank        (row/lang-rank languages)
        sorted      (if (set? vs)
                      (sort-by (juxt (comp rank i18n/lang)
                                     (rdf/label-sortkey-fn opts))
                               vs)
                      vs)
        entries     (distinct (for [v sorted
                                    p (lock/value-properties opts k v)]
                                [p v]))
        entry->lock (into {}
                          (map (fn [[p v :as entry]]
                                 [entry (lock/lock opts p v)]))
                          entries)
        properties  (row/properties opts k)
        fields      (row/fields changes subject properties
                                (remove entry->lock entries))
        input-type  (input-type/input-type k vs)
        locked      (map second (filter entry->lock entries))
        values      (concat (map :value fields) locked)
        langs       (when (:lang? input-type)
                      (not-empty (sort-by rank (:languages edit-rules))))
        gap         (when (and langs (contains? (:translated edit-rules) k))
                      (->> (remove (comp #{:retracted} :status) fields)
                           (map :value)
                           (concat locked)
                           (row/missing-lang langs)))
        ;; a new value is one of the options that the row doesn't have
        new-type    (cond-> input-type
                      (:options input-type) (update :options
                                                    #(remove (set values) %)))
        ;; A locked value can be added, since the graph that changes go to
        ;; doesn't hold it, and a container can hold a member twice.
        taken       (when-not (= k shared/member-row)
                      (map :value fields))
        item-key    (fn [p v] (pr-str [p (if (map? v) (:rdf/about v) v)]))]
    [:td
     [:ul.edit-values
      (for [{:keys [property value original] :as field} fields]
        ;; keyed by its property and by the value that it replaces, so that
        ;; an edit keeps its field
        (rum/with-key (value-field opts property value
                                   (assoc field
                                     :input-type input-type
                                     :langs langs))
                      (item-key property (if (some? original) original value))))
      ;; a row whose options are all taken has nothing to add
      (when-not (some-> (:options new-type) empty?)
        (rum/with-key (new-value-field
                        opts (row/new-value-property k properties) taken
                        {:input-type new-type
                         :langs      langs
                         :lang       (or gap (i18n/lang (first taken)))
                         :gap?       (some? gap)})
                      "new"))
      (for [[p v :as entry] (filter entry->lock entries)]
        [:li.edit-value.edit-value--locked
         (assoc (rdf/lone-value-attrs v) :key (item-key p v))
         (padlock (get-in comments [:locked (entry->lock entry)]))
         (rdf/value opts v)])]]))
