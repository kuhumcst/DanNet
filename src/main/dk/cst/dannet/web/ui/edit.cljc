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
     it (see field-buttons).
  5. The buttons come in the order edit, undo, remove.

  The :edit-rules of the page (see dk.cst.dannet.editable) name the
  :languages that the dropdown of a text offers, where nil is none, and the
  :translated properties, whose rows show which language has no text yet."
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

(defn- after-redraw!
  "Call `f` once the page is drawn again, i.e. after the frame in which a
  change of state is rendered."
  [f]
  #?(:cljs (js/requestAnimationFrame #(js/requestAnimationFrame f))))

(defn- in-row
  "The first element in the row of the property `k` on the page that the CSS
  `selector` matches, if any."
  [k selector]
  #?(:cljs (.querySelector js/document
                           (str "tr[property=\"" (prefix/kw->qname k) "\"] "
                                selector))))

(defn- focus-beside!
  "Move the focus to the control next to the dialog that the event `e`
  closes: the one that the CSS `selector` matches."
  [e selector]
  (some-> (.. e -target -parentNode) (.querySelector selector) (.focus)))

(def show-modal
  "A mixin that opens a <dialog> as a modal when it is drawn, with the
  focus in its input."
  {:did-mount (fn [state]
                #?(:cljs (let [dialog (rum/dom-node state)]
                           (.showModal dialog)
                           (some-> (.querySelector dialog "input") (.focus))))
                state)})

(def list-all
  "A mixin for the search dialog. A search with :list-all? has few enough
  results to show them all when the dialog opens, before a text is typed."
  {:did-mount (fn [state]
                #?(:cljs
                   (let [[_ {:keys [list-all? results-fn]}] (:rum/args state)]
                     (when list-all?
                       (.then (js/Promise.resolve (results-fn ""))
                              #(reset! (::results state) (vec %))))))
                state)})

(rum/defcs search-dialog < show-modal
                           (rum/local nil ::results)
                           (rum/local 0 ::searches)
                           list-all
  "A dialog that finds a value with the `search` (see ui.input-type). It
  calls `on-value` with the chosen one, or `on-close` when there is none."
  [state {:keys [languages] :as opts}
   {:keys [results-fn value-fn label-fn hiccup-fn title heading placeholder]
    :as   search}
   {:keys [on-value on-close]}]
  (let [results  (::results state)
        searches (::searches state)
        localize #(i18n/da-en languages (:da %) (:en %))
        ;; Only the results of the latest search are shown, and a search
        ;; waits until the typing pauses. Each list of results is tagged
        ;; with its search.
        search!  (fn [s]
                   #?(:cljs
                      (let [n (swap! searches inc)]
                        (js/setTimeout
                          (fn []
                            (when (= n @searches)
                              (.then (js/Promise.resolve (results-fn s))
                                     #(when (= n @searches)
                                        (reset! results
                                                (with-meta (vec %)
                                                           {::search n}))))))
                          200))))
        choose!  (fn [result]
                   (let [v (value-fn result)]
                     (when-let [label (and label-fn (label-fn result))]
                       (app/remember-labels! {v label}))
                     (on-value v)))]
    [:dialog.search-dialog
     {:aria-label (localize title)
      :on-close   on-close
      ;; a click on the backdrop is a click on the dialog itself
      :on-click   (fn [e]
                    (when (= (.-target e) (.-currentTarget e))
                      (.close (.-currentTarget e))))}
     [:header
      [:h2 (or heading (localize title))]
      (icon-button "remove" (i18n/da-en languages "Luk" "Close")
                   (fn [e] (.close (.closest (.-target e) "dialog"))))]
     [:input {:type          "search"
              :auto-complete "off"
              :aria-label    (localize title)
              :placeholder   (localize placeholder)
              :on-change     #(search! (.. % -target -value))
              ;; Enter chooses the first result, once the results are those
              ;; of the text in the input
              :on-key-down   (fn [e]
                               (when (and (= "Enter" (.-key e))
                                          (seq @results)
                                          (= @searches
                                             (::search (meta @results) 0)))
                                 (.preventDefault e)
                                 (choose! (first @results))))}]
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
         (for [result @results]
           [:li {:key (pr-str (value-fn result))}
            [:button {:type     "button"
                      :on-click #(choose! result)}
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

(defn invalid-message
  "The message of a text that isn't a value of the `input-type`, for a
  reader with `languages`: what a value has to be, when the input type has
  a :description of it."
  [languages {:keys [description] :as input-type}]
  (or (some->> description (i18n/select-label languages) (str))
      (i18n/da-en languages "Ikke en gyldig værdi" "Not a valid value")))

(rum/defcs text-input < (rum/local nil ::invalid?)
                        (rum/local nil ::refusal)
                        (rum/local false ::entering?)
                        (rum/local false ::searching?)
                        (rum/local nil ::chosen)
  "A text input for a value of the `input-type` (see ui.input-type), named by
  `label`, that starts out with the `draft` or else the `value`.

  Enter or the focus leaving it enters the text. The input then calls
  `on-value` with a value, `on-invalid` with a text that isn't one and
  whether the focus is leaving, `on-empty` for an empty text, or `on-cancel`
  when nothing is new, as Escape does. On Enter, a value among `taken`, or
  an empty text with a `keep-reason`, is refused with the reason.

  A text is in the language `lang`, or the one chosen among `langs`. The
  `undo` and the :remove are each the label and the function of a button."
  [state {:keys [languages] :as opts}
   {:keys [parse-fn format-fn search] :as input-type}
   {:keys [value draft lang langs label undo taken keep-reason on-value
           on-cancel on-invalid on-empty]
    removal :remove}]
  (let [entering?  (::entering? state)
        searching? (::searching? state)
        ;; nil until the text is checked or typed in: a draft is invalid
        invalid?   (::invalid? state)
        invalid?'  (if (nil? @invalid?) (some? draft) @invalid?)
        ;; the chosen language is kept in a vector, since nil is a choice too
        chosen     (::chosen state)
        lang'      (if-let [[choice] @chosen] choice lang)
        old-text   (if (some? value) (format-fn value) "")
        message    (invalid-message languages input-type)
        ;; why the entered text is refused, when it isn't for its form
        refusal    (::refusal state)
        refuse!    (fn [input why]
                     (reset! entering? false)
                     (reset! refusal why)
                     (reset! invalid? true)
                     (.setCustomValidity input why)
                     (.reportValidity input))
        ;; A text is entered once, though the focus may leave right after
        ;; Enter. The browser's report takes the focus, so it is for Enter.
        enter!     (fn [input leaving?]
                     (let [s (str/trim (.-value input))]
                       (cond
                         @entering?
                         nil

                         (and (empty? s) on-empty)
                         (do (reset! entering? true)
                             (on-empty))

                         (and (empty? s) keep-reason (not leaving?))
                         (refuse! input keep-reason)

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
                                       (cond
                                         (nil? v)
                                         (do
                                           (reset! entering? false)
                                           (reset! invalid? true)
                                           (.setCustomValidity input message)
                                           (on-invalid s leaving?)
                                           (when-not leaving?
                                             (.reportValidity input)))

                                         (not-any? #{v} taken)
                                         (on-value v)

                                         leaving?
                                         (on-cancel)

                                         :else
                                         (refuse!
                                           input
                                           (i18n/da-en languages
                                             "Rækken har allerede denne værdi"
                                             "The row already has this value"))))))))))
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
       :title         (when invalid?' (or @refusal message))
       :auto-focus    true
       :rows          1
       :spell-check   (when search "false")
       ;; the caret starts at the end of the text
       :on-focus      (fn [e]
                        (let [n (count (.. e -target -value))]
                          (.setSelectionRange (.-target e) n n)))
       :on-change     (fn [e]
                        (reset! invalid? false)
                        (reset! refusal nil)
                        (.setCustomValidity (.-target e) ""))
       :on-key-down   (fn [e]
                        (case (.-key e)
                          "Enter" (do (.preventDefault e)
                                      (enter! (.-target e) false))
                          "Escape" (cancel!)
                          nil))}]
     (when (and langs (not= langs [lang]))
       (lang-select opts langs lang' #(reset! chosen [%])
                    {:on-key-down (fn [e]
                                    (when (= "Escape" (.-key e))
                                      (cancel!)))}))
     (when (or undo removal search)
       [:span.edit-value__actions
        (when-let [[label f] undo]
          (icon-button "undo" label f))
        (when-let [[label f] removal]
          (icon-button "remove" label f))
        (when search
          (icon-button "search" (i18n/da-en languages "Søg" "Search")
                       #(reset! searching? true)))])
     (when @searching?
       (search-dialog opts search
                      {:on-value on-value
                       :on-close (fn [e]
                                   (reset! searching? false)
                                   (focus-beside! e ".edit-value__input"))}))]))

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

(def keep-focus
  "A mixin for a field. When the field goes away while the focus is in it,
  e.g. when an added value is taken away, the focus moves to the nearest
  control that is still on the page once it is drawn again: a button of
  the field after it, the plus of its row, or the button that adds a row to
  its table."
  {:will-unmount
   (fn [state]
     #?(:cljs
        (let [node (rum/dom-node state)]
          (when (.contains node js/document.activeElement)
            (let [controls [(some-> (.-nextElementSibling node)
                                    (.querySelector "button"))
                            (some-> (.closest node "tr")
                                    (.querySelector ".edit-value__add"))
                            (some-> (.closest node "table")
                                    (.-nextElementSibling)
                                    (.querySelector "button"))]]
              (after-redraw!
                ;; unless the focus has gone elsewhere by itself
                #(when (= js/document.body js/document.activeElement)
                   (some-> (first (filter (fn [control]
                                            (some-> control .-isConnected))
                                          controls))
                           (.focus))))))))
     state)})

(defn required-message
  "The reason why the one value that a row `must` have can't be removed, for
  a reader with `languages`: the row takes exactly one value, or at least
  one."
  [languages {:keys [min max] :as must}]
  (if (= min max)
    (i18n/da-en languages
      "Rækken skal have præcis én værdi, så den kan rettes, men ikke fjernes"
      "The row needs exactly one value, so it can be edited but not removed")
    (i18n/da-en languages
      "Rækken skal have mindst én værdi, så den sidste kan ikke fjernes"
      "The row needs at least one value, so the last one can't be removed")))

(defn required-mark
  "The mark of the one value that a row `must` have: a 1 in a ring, with the
  reason why it can't be removed as its tooltip (see required-message), for
  a reader with `languages`."
  [languages must]
  (let [reason (required-message languages must)]
    [:span.edit-value__required {:key        "required"
                                 :role       "img"
                                 :aria-label reason
                                 :title      reason}
     ;; a drawing, since the digit of a font isn't in the middle of its box
     [:span.edit-value__icon.edit-value__icon--one]]))

(defn field-buttons
  "The buttons of a field in their order, by whether it is `open?` as an
  input and by its `tint`: nil, :edited, :moved, :added or :retracted.

  - :edit opens the input: on a closed field, unless its value is removed.
  - :undo puts the field back as the data has it: on an edited, a moved or
    a removed value.
  - :remove takes the value away: on an added value, and on a closed field
    with no tint, an edited or a moved one."
  [open? tint]
  (cond-> []
    (and (not open?) (not= tint :retracted)) (conj :edit)
    (contains? #{:edited :moved :retracted} tint) (conj :undo)
    (or (= tint :added)
        (and (not open?) (contains? #{nil :edited :moved} tint)))
    (conj :remove)))

(defn- buttons
  "The buttons of a closed field with the `tint` (see field-buttons), from
  `actions`, a map of a button to its label and function. A button in
  `marks` has its mark there in its place, e.g. the count of a row that
  must keep the value in place of the button that removes it."
  [tint actions & [marks]]
  [:span.edit-value__actions
   (for [button (field-buttons false tint)
         :let   [[label f] (get actions button)]]
     (or (get marks button)
         (icon-button (name button) label f)))])

(defn- opens-input?
  "Does the click `e` open the input of a closed field? Not on a link or a
  control, or when it selects text."
  [e]
  #?(:clj  false
     :cljs (not (or (.closest (.-target e) "a, button, select, [tabindex]")
                    (seq (str (js/getSelection)))))))

(defn- invalid-content
  "The content of a closed field whose entered text `s` isn't a value. The
  `message` says why to a reader who can't see the wavy line under it."
  [s message]
  [:ins.edit-value__content.edit-value__content--invalid
   s [:span.visually-hidden ": " message]])

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
  of the node, which its changes are located by when they are submitted.

  The labels of the resources in the changes are kept too, since the list of
  the changes shows them on a page of its own (see ui.changes)."
  [{:keys [blank-node subject k->label] :as opts} f & args]
  (when blank-node
    (app/remember-node! (:rdf/about blank-node) (node-place opts)))
  (let [{:keys [changes]} (apply app/update-changes! f args)]
    (app/remember-labels!
      (select-keys k->label (conj (change/resources changes)
                                  subject
                                  (:property blank-node))))))

(defn undo-label
  "The name of the button that undoes a change with the `tint` to the value
  that `text` names, for a reader with `languages`."
  [languages tint text]
  (str (case tint
         :retracted (i18n/da-en languages
                      "Fortryd fjernelse af" "Undo removal of")
         :moved (i18n/da-en languages
                  "Fortryd flytning af" "Undo move of")
         (i18n/da-en languages
           "Fortryd rettelse af" "Undo edit of"))
       ": " text))

(defn move-note
  "The note of a value that has moved from one row to another, for a reader
  of a table with `opts`: the property that it has :moved-from or :moved-to,
  as the `field` has it (see row/fields), or nil."
  [{:keys [languages] :as opts} {:keys [moved-from moved-to] :as field}]
  (cond
    moved-from [:small.edit-value__note
                (i18n/da-en languages "Flyttet fra" "Moved from") ": "
                (rdf/resource-name moved-from opts)]
    moved-to [:small.edit-value__note
              (i18n/da-en languages "Flyttet til" "Moved to") ": "
              (rdf/resource-name moved-to opts)]))

(defn- value-text
  "The value `v` as the text that names it in a label or a tooltip."
  [opts v]
  (if (keyword? v)
    (rdf/link-label opts v)
    (str v)))

(rum/defcs value-field < (rum/local false ::editing?)
                         (rum/local nil ::draft)
                         refocus
                         keep-focus
  "A field for the value `v` of the property `k` in `opts`, which is an
  rdf:_N property in the row of members.

  The `props` are the field as row/fields has it, with the :input-type of
  its input, the :langs of a text and :required, the constraints of a row
  that must keep the value. An entered text that isn't a value is kept as a
  draft and shown as an edit. The tag of a text is a dropdown that changes
  its language."
  [state {:keys [languages comments] :as opts}
   k v {:keys [input-type status original langs required moved-to]
        :as   props}]
  (let [editing? (::editing? state)
        draft    (::draft state)
        subject  (subject-of opts)
        t        [subject k v]
        ;; a draft is an edit, unless the value that it would replace is new
        tint     (if (and @draft (not= status :added)) :edited status)
        lang     (i18n/lang v)
        langs    (when (i18n/rdf-string? v) langs)
        ;; a value that isn't in the row yet can be taken away again
        required (when-not (#{:added :moved} status) required)
        ;; a pending value isn't in the data yet, so it has no origins
        shared?  (and (not (contains? #{:added :edited :moved} status))
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
        undo     [(undo-label languages (if moved-to :moved tint)
                              (or @draft (value-text opts v)))
                  (fn [_]
                    (reset! draft nil)
                    (close!)
                    (when status
                      (update-changes! opts change/without-triple t)))]
        ;; A pending value is taken away, and what its change removes stays
        ;; removed: the value that it replaces, or the one in its old row.
        remove!  (fn [& _]
                   (reset! draft nil)
                   (update-changes!
                     opts
                     (if status
                       #(change/without-addition % t)
                       #(change/retract % t))))
        removal  [(label "Fjern" "Remove") remove!]
        note     (move-note opts props)
        before   (cond
                   (some? original) original
                   @draft v)
        message  (invalid-message languages input-type)
        ;; the tooltip names the old language when the language has changed
        titles   [(when @draft
                    message)
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
               :remove     (when (some #{:remove} (field-buttons true tint))
                             removal)
               :on-cancel  close!
               :on-empty   (when-not required
                             (fn []
                               (close!)
                               (remove!)))
               :keep-reason (some->> required (required-message languages))
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
       (invalid-content @draft message)

       ;; a removed value is a <del>, any other pending one an <ins>
       :else
       (let [content [(case status
                        :retracted :del
                        (:added :edited :moved) :ins
                        :span)
                      (update (rdf/lone-value-attrs v) :class
                              #(remove nil? ["edit-value__content" %]))
                      (rdf/value opts v)
                      ;; A text without a tag gets its dropdown in the input,
                      ;; and a text that can only be in its own language
                      ;; keeps the plain tag.
                      (when (and langs lang (not= langs [lang])
                                 (not= status :retracted))
                        (lang-select opts langs lang retag!))]]
         (if note
           [:span.edit-value__content content note]
           content)))
     (when-not @editing?
       (buttons tint {:edit   [(label "Ret" "Edit") #(reset! editing? true)]
                      :undo   undo
                      :remove removal}
                (when required
                  {:remove (required-mark languages required)})))]))

(rum/defcs new-value-field < (rum/local false ::adding?)
                             (rum/local nil ::draft)
                             refocus
  "The field for a new value of the property `k` in `opts`: a button that
  opens an input. An entered value is added unless it is `taken`.

  The `props` are the :input-type of the input, the :lang of a new text, the
  :langs that it can be in and :gap? when the row has no text in that
  language, which the button then names. An entered text that isn't a value
  is kept as a draft and shown as an addition."
  [state {:keys [languages attr-key] :as opts}
   k taken {:keys [input-type lang langs gap?]}]
  (let [adding? (::adding? state)
        draft   (::draft state)
        subject (subject-of opts)
        tint    (when @draft :added)
        ;; the plus of every row looks the same, so its name says which row
        label   (cond-> (str (i18n/da-en languages
                               "Tilføj en værdi til" "Add a value to")
                             " \"" (rdf/link-label opts attr-key) "\"")
                  gap? (str " " (i18n/da-en languages "på" "in")
                            " " (language-name languages lang)))
        close!  (fn []
                  (vreset! (::refocus state) "button")
                  (reset! adding? false))
        open!   (fn [e]
                  (when (and (not @adding?) (opens-input? e))
                    (reset! adding? true)))
        drop!   (fn [& _]
                  (reset! draft nil)
                  (close!))
        removal [(str (i18n/da-en languages "Fjern" "Remove") ": " @draft)
                 drop!]
        message (invalid-message languages input-type)]
    [:li.edit-value
     (cond
       @adding? {:class (when tint "edit-value--added")}
       @draft {:class    ["edit-value--added" "edit-value--clickable"]
               :title    message
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
               :taken      taken
               :remove     (when (some #{:remove} (field-buttons true tint))
                             removal)
               :on-cancel  close!
               :on-empty   (when @draft drop!)
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
        (invalid-content @draft message)
        (buttons tint {:edit   [(str (i18n/da-en languages "Ret" "Edit")
                                     ": " @draft)
                                #(reset! adding? true)]
                       :remove removal})]

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
  and then by text. The :constraints of the table shape the fields: the
  languages and the form of a text, the values to choose between, and
  whether the row can take another value or lose one (see
  dk.cst.dannet.db.shapes/property-constraints)."
  [{:keys [changes comments languages edit-rules constraints] :as opts} k vs]
  (let [subject     (subject-of opts)
        {:keys [must should]} (get constraints k)
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
        input-type  (input-type/input-type vs must)
        locked      (map second (filter entry->lock entries))
        values      (concat (map :value fields) locked)
        ;; the values of the row as the pending changes leave them
        current     (->> (remove (comp #{:retracted} :status) fields)
                         (map :value)
                         (concat locked))
        ;; a text with a datatype of its own is a code, without a language
        langs       (when (and (:lang? input-type) (not (:datatype must)))
                      (not-empty (sort-by rank (or (:languages must)
                                                   (:languages edit-rules)))))
        gap         (when (and langs (contains? (:translated edit-rules) k))
                      (row/missing-lang langs current))
        expected    (sort-by rank (or (:languages must) (:languages should)))
        full?       (some-> (:max must) (<= (count current)))
        ;; a row keeps the one value that it must have
        required    (when (and (= 1 (:min must)) (>= 1 (count current)))
                      must)
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
                                     :langs langs
                                     :required required))
                      (item-key property (if (some? original) original value))))
      ;; a row that is full, or whose options are all taken, takes no more
      (when-not (or full? (some-> (:options new-type) empty?))
        (rum/with-key (new-value-field
                        opts (row/new-value-property k properties) taken
                        {:input-type new-type
                         :langs      langs
                         :lang       (or gap (row/new-text-lang (first taken)
                                                                expected))
                         :gap?       (some? gap)})
                      "new"))
      (for [[p v :as entry] (filter entry->lock entries)]
        [:li.edit-value.edit-value--locked
         (assoc (rdf/lone-value-attrs v) :key (item-key p v))
         (padlock (get-in comments [:locked (entry->lock entry)]))
         (rdf/value opts v)])]]))

(defn- property-search
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
      [:span.edit-value__icon.edit-value__icon--add {:aria-hidden "true"}]
      (i18n/da-en languages (:da title) (:en title))]
     (when @choosing?
       (search-dialog opts
                      (property-search opts ks title true)
                      {:on-value (fn [k]
                                   (reset! choosing? false)
                                   (on-add k))
                       :on-close (fn [e]
                                   (reset! choosing? false)
                                   (focus-beside! e "button"))}))]))

(defn open-new-value!
  "Open the field for a new value in the row of the property `k`, once the
  page is drawn again."
  [k]
  (after-redraw! #(some-> (in-row k ".edit-value__add") (.click))))

(defn- movable
  "The values in the row of the property `k` in `opts` that can move to
  another property: those of its values `vs` that can be edited and the
  ones that the pending changes add, but not the ones that they remove."
  [{:keys [changes] :as opts} k vs]
  (->> (for [v vs :when (nil? (lock/lock opts k v))] [k v])
       (row/fields changes (subject-of opts) [k])
       (remove (comp #{:retracted} :status))
       (map :value)))

(defn- focus-row!
  "Move the focus to the first button of a value in the row of the property
  `k`, once the page is drawn again."
  [k]
  (after-redraw! #(some-> (in-row k ".edit-value .edit-value__button")
                          (.focus))))

(rum/defcs value-mover < (rum/local false ::choosing?)
  "A button by the name of the row of the property `k` in `opts` that moves
  the values `vs` of the row to another property. It opens a dialog with the
  properties that the values fit (see row/alternatives), and each value
  moves as a change of its own, so that it can be undone on its own.

  There is no button when the values fit no other property, or in the
  nested table of a blank node."
  [state {:keys [languages constraints entity blank-node] :as opts} k vs]
  (let [choosing? (::choosing? state)
        subject   (subject-of opts)
        values    (when-not blank-node (movable opts k vs))
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
        [before after] (i18n/da-en languages (:da around) (:en around))]
    (when (seq ks)
      [:<>
       (icon-button "edit"
                    (i18n/da-en languages (:da title) (:en title))
                    #(reset! choosing? true))
       (when @choosing?
         (search-dialog
           opts
           (assoc (property-search opts ks title false)
             :heading [:<> before (rdf/resource-name k opts) after])
           {:on-value (fn [k']
                        (reset! choosing? false)
                        (update-changes!
                          opts
                          (fn [changes]
                            (reduce #(change/replace %1
                                                     [subject k %2]
                                                     [subject k' %2])
                                    changes
                                    values)))
                        (focus-row! k'))
            :on-close (fn [e]
                        (reset! choosing? false)
                        (focus-beside! e ".edit-value__button"))}))])))
