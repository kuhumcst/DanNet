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

  A blank node that the shapes describe is a field too (see node-field), and
  so is a new entry (see entry-field).

  The :edit-rules of the page (see dk.cst.dannet.editable) name the
  :languages that the dropdown of a text offers, where nil is none, the
  :translated properties, whose rows show which language has no text yet,
  and the :entries, which are the templates of the new entries."
  (:require [clojure.string :as str]
            [rum.core :as rum]
            [dk.cst.dannet.change :as change]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.web.app :as app]
            [dk.cst.dannet.web.i18n :as i18n]
            [dk.cst.dannet.web.ui.dialog :as dialog]
            [dk.cst.dannet.web.ui.edit.entry :as entry]
            [dk.cst.dannet.web.ui.edit.removal :as removal]
            [dk.cst.dannet.web.ui.edit.row :as row]
            [dk.cst.dannet.web.ui.input-type :as input-type]
            [dk.cst.dannet.web.ui.lock :as lock]
            [dk.cst.dannet.web.ui.rdf :as rdf]
            [dk.cst.theophrastus.term :as term]))

(defn icon
  "The icon with the `icon-name`, e.g. \"add\", which a screen reader
  skips."
  [icon-name]
  [:span {:class       ["edit-value__icon" (str "edit-value__icon--" icon-name)]
          :aria-hidden "true"}])

(defn icon-button
  "A button with the `icon-name` that calls `on-click`. The `label` is its
  name and its tooltip."
  [icon-name label on-click]
  [:button.edit-value__button {:type     "button"
                               :key      icon-name
                               :title    label
                               :on-click on-click}
   (icon icon-name)
   [:span.visually-hidden label]])

(defn plus-label
  "The name of the add button in the row of the property `k` in `opts`. The
  button looks the same in every row, so its name says which row."
  [{:keys [languages] :as opts} k]
  (str (i18n/da-en languages "Tilføj en værdi til" "Add a value to")
       " \"" (rdf/link-label opts k) "\""))

(defn add-button
  "The plus button of a row, which calls `on-click`. The `label` is its name
  and its tooltip (see plus-label). With `gap`, the button also shows that
  text, e.g. a language that the row lacks."
  [label on-click & [gap]]
  [:button.edit-value__add {:type     "button"
                            :title    label
                            :on-click on-click}
   (icon "add")
   (when gap
     [:span.edit-value__gap {:aria-hidden "true"} gap])
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
  "A dropdown of the languages `langs` in a table with `opts`, which shows
  `lang` as the tag of a text, or that a language can be added. It calls
  `on-lang` with a chosen language, and `attrs` are added to its attributes."
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

(defn after-redraw!
  "Call `f` once the page is drawn again, i.e. after the frame in which a
  change of state is rendered."
  [f]
  #?(:cljs (js/requestAnimationFrame #(js/requestAnimationFrame f))))

(defn when-drawn!
  "Call `f` with the element that the function `find` returns, once the
  page is drawn with it. A change of state can take several frames to draw,
  so `find` is called in each frame, for about a second."
  [find f]
  #?(:cljs (letfn [(look! [frames]
                     (if-let [element (find)]
                       (f element)
                       (when (pos? frames)
                         (js/requestAnimationFrame #(look! (dec frames))))))]
             (js/requestAnimationFrame #(look! 60)))))

(def row-plus
  "A CSS selector for the button that adds a value to a row, after the
  selector of the row. A row of blank nodes has such buttons in its nested
  tables too."
  "> td > .edit-values > .edit-value > .edit-value__add")

(defn node-selector
  "A CSS selector for the field of the blank node with the `label`."
  [label]
  (str "[data-node=\"" label "\"]"))

(defn in-row
  "The first element that the CSS `selector` matches in the row of the
  property `k` in the table of `subject`, if any. The subject is the
  resource of the page, or the label of a blank node with a nested table."
  [subject k selector]
  #?(:cljs (let [table (if (symbol? subject)
                         (str (node-selector subject) " table")
                         "table.attr-val:not(.attr-val--nested)")]
             (.querySelector js/document
                             (str table " > tbody > tr[property=\""
                                  (prefix/kw->qname k) "\"] " selector)))))

(defn focus-beside!
  "Move the focus to the control next to the dialog that the event `e`
  closes: the one that the CSS `selector` matches."
  [e selector]
  (some-> (.. e -target -parentNode) (.querySelector selector) (.focus)))

(defn close-fn
  "A function for the :on-close of a dialog, which sets the flag `showing?`
  of the dialog to false and moves the focus to the control next to the
  dialog that the CSS `selector` matches (see focus-beside!)."
  [showing? selector]
  (fn [e]
    (reset! showing? false)
    (focus-beside! e selector)))

(defn removal-reason
  "Why the entry `v` can't be chosen as a value when the pending `changes`
  remove it as a whole, for a reader with `languages`. Nil when they don't."
  [languages changes v]
  (when (entry/removed? changes v)
    (i18n/da-en languages
      "Ressourcen fjernes"
      "The resource is being removed")))

(def list-all
  "A mixin that shows every result of a search with :list-all? when its
  dialog opens, before a text is typed. Such a search has few results."
  {:did-mount (fn [state]
                #?(:cljs
                   (let [[_ {:keys [list-all? results-fn]}] (:rum/args state)]
                     (when list-all?
                       (.then (js/Promise.resolve (results-fn ""))
                              #(reset! (::results state) (vec %))))))
                state)})

(rum/defcs search-dialog < (rum/local nil ::results)
                           (rum/local 0 ::searches)
                           (rum/local "" ::text)
                           list-all
  "A dialog on a page with `opts` that finds a value with the `search` (see
  ui.input-type). It calls `on-value` with the chosen value, or `on-result`
  with the result as it is, and `on-close` when nothing is chosen.

  A result that the search refuses says why and can't be chosen, and
  neither can an entry that the pending changes remove. With `new-fn`, a
  line before the results offers to make a value: the function takes the
  typed text and returns nil, or the :name of what it makes in each
  language and the :text that it is made from. `on-new` gets the text."
  [state {:keys [languages changes] :as opts}
   {:keys [results-fn value-fn label-fn hiccup-fn refuse-fn one-line? title
           heading placeholder note]
    :as   search}
   {:keys [on-value on-result on-close new-fn on-new]}]
  (let [results  (::results state)
        searches (::searches state)
        text     (::text state)
        localize #(i18n/localize languages %)
        refusal  #(or (when refuse-fn (refuse-fn %))
                      (removal-reason languages changes (value-fn %)))
        offer    (when new-fn (new-fn @text))
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
                     (if on-result
                       (on-result result)
                       (on-value v))))]
    (dialog/dialog
      languages
      {:title    (localize title)
       :heading  heading
       :class    "search-dialog"
       :on-close on-close}
      [:<>
       (when note
         [:p#search-dialog-note.search-dialog__note (localize note)])
       [:input {:type             "search"
                :data-focus       true
                :auto-complete    "off"
                :aria-label       (localize title)
                :aria-describedby (when note "search-dialog-note")
                :placeholder      (localize placeholder)
                :on-change        #(let [s (.. % -target -value)]
                                     (reset! text s)
                                     (search! s))
                ;; Enter chooses the first result that can be chosen, once
                ;; the results are those of the text in the input
                :on-key-down      (fn [e]
                                    (when (and (= "Enter" (.-key e))
                                               (= @searches
                                                  (::search (meta @results) 0)))
                                      (when-let [result (->> @results
                                                             (remove refusal)
                                                             (first))]
                                        (.preventDefault e)
                                        (choose! result))))}]
       (when offer
         [:button.search-dialog__new {:type     "button"
                                      :on-click #(on-new @text)}
          (icon "add")
          (localize (:name offer))
          (when-let [s (:text offer)]
            [:<> ": " [:strong s]])])
       ;; the number of results is announced, not every result, and so is the
       ;; line that offers to make a value of the text
       [:p.visually-hidden {:role "status"}
        (when-let [n (some-> @results count)]
          (str (i18n/da-en languages
                 (str n (if (= n 1) " resultat" " resultater"))
                 (str n (if (= n 1) " result" " results")))
               (when offer
                 (str ". " (localize (:name offer))
                      (some->> (:text offer) (str ": "))))))]
       [:div.search-dialog__body
        (cond
          (nil? @results)
          nil

          (empty? @results)
          [:p (i18n/da-en languages "Ingen resultater" "No results")]

          :else
          [:ul.search-dialog__results
           {:class (when one-line? "search-dialog__results--one-line")}
           (for [result @results
                 :let   [reason (refusal result)]]
             [:li {:key (pr-str (value-fn result))}
              ;; not disabled, so that the reason can be reached and read
              [:button {:type          "button"
                        :aria-disabled (when reason "true")
                        :on-click      #(when-not reason
                                          (choose! result))}
               (hiccup-fn result opts)
               (when reason
                 [:small reason])]])])]])))

(rum/defc options-input
  "A dropdown in a table with `opts` of the `options`, named by `label`,
  that starts with the `value`. It calls `on-value` with a chosen option,
  or `on-cancel` when none is."
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

(defn entered-text
  "What an input does with the trimmed text `s` that is entered in it, by
  how its `props` set it up, before the text is parsed:

  - :empty, an empty text takes the value away, when it is :removable?;
  - :refuse, an empty text is refused, when the value is :required?;
  - :cancel, nothing is new: another empty text, or the :start text of the
    input, unless it is :retagged? with another language;
  - :parse, any other text is parsed as a value.

  Nothing is refused when the focus is :leaving? the input."
  [s {:keys [start retagged? removable? required? leaving?] :as props}]
  (cond
    (and (empty? s) removable?) :empty
    (and (empty? s) required? (not leaving?)) :refuse
    (or (empty? s) (and (= s start) (not retagged?))) :cancel
    :else :parse))

(defn entered-value
  "What an input does with `v`, the value that an entered text is parsed
  as, by how its `props` set it up:

  - :invalid, the text isn't a value, so `v` is nil;
  - :value, the value is entered;
  - :cancel, a value that can't be entered is dropped, when the focus is
    :leaving? the input;
  - :refuse, it is refused otherwise.

  A value can't be entered when it is among :taken, or when it is an entry
  that the pending :changes remove."
  [v {:keys [taken changes leaving?] :as props}]
  (cond
    (nil? v) :invalid
    (and (not-any? #{v} taken) (not (entry/removed? changes v))) :value
    leaving? :cancel
    :else :refuse))

(declare action-buttons)

(rum/defcs text-input < (rum/local nil ::invalid?)
                        (rum/local nil ::refusal)
                        (rum/local false ::entering?)
                        (rum/local false ::searching?)
                        (rum/local nil ::chosen)
  "A text input in a table with `opts` for a value of the `input-type` (see
  ui.input-type), named by `label`. It starts with the `draft` or else the
  `value`, in the language `lang` or one chosen among `langs`.

  Enter, or the focus leaving, enters the text (see entered-text and
  entered-value): the input calls `on-value` with a value, `on-invalid` with
  a text that isn't one and whether the focus is leaving, `on-empty` for an
  empty text, or `on-cancel` when nothing is new, as Escape does. On Enter
  it refuses a value among `taken`, an entry that is being removed, and an
  empty text with a `keep-reason`. Its buttons are those of an open field
  with the `tint`, from `actions` (see action-buttons)."
  [state {:keys [languages changes] :as opts}
   {:keys [parse-fn format-fn search] :as input-type}
   {:keys [value draft lang langs label tint actions taken keep-reason
           on-value on-cancel on-invalid on-empty]}]
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
        taken-reason (i18n/da-en languages
                       "Rækken har allerede denne værdi"
                       "The row already has this value")
        own        (action-buttons true tint actions)
        ;; why the entered text is refused, when it isn't for its form
        refusal    (::refusal state)
        ;; a text that isn't entered has `why` as its validity message
        flag!      (fn [input why]
                     (reset! entering? false)
                     (reset! invalid? true)
                     (.setCustomValidity input why))
        refuse!    (fn [input why]
                     (reset! refusal why)
                     (flag! input why)
                     (.reportValidity input))
        setup      {;; nothing is new since a text was last entered
                    :start      (or draft old-text)
                    :retagged?  (not= lang' lang)
                    :removable? (some? on-empty)
                    :required?  (some? keep-reason)}
        ;; the browser's report takes the focus, so it is for Enter
        parsed!    (fn [input s v leaving?]
                     (case (entered-value v {:taken    taken
                                             :changes  changes
                                             :leaving? leaving?})
                       :invalid (do (flag! input message)
                                    (on-invalid s leaving?)
                                    (when-not leaving?
                                      (.reportValidity input)))
                       :value (on-value v)
                       :cancel (on-cancel)
                       :refuse (refuse! input
                                        (or (removal-reason languages changes v)
                                            taken-reason))))
        ;; a text is entered once, though the focus may leave right after
        ;; Enter
        enter!     (fn [input leaving?]
                     (let [s (str/trim (.-value input))]
                       (when-not @entering?
                         (case (entered-text s (assoc setup
                                                 :leaving? leaving?))
                           :empty (do (reset! entering? true)
                                      (on-empty))
                           :refuse (refuse! input keep-reason)
                           :cancel (on-cancel)
                           :parse
                           #?(:clj nil
                              :cljs
                              (do
                                (reset! entering? true)
                                (.then (js/Promise.resolve (parse-fn s lang'))
                                       #(parsed! input s % leaving?))))))))
        cancel!    (fn []
                     (reset! entering? true)
                     (on-cancel))
        ;; moving between the input and its controls isn't leaving
        leave!     (fn [e]
                     (let [wrapper (.-currentTarget e)]
                       (when-not (or @searching?
                                     (some->> (.-relatedTarget e)
                                              (.contains wrapper)))
                         (enter! (.querySelector wrapper "textarea") true))))]
    [:span.edit-value__wrapper {:on-blur leave!}
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
     (when (or (seq own) search)
       [:span.edit-value__actions
        own
        (when search
          (icon-button "search" (i18n/da-en languages "Søg" "Search")
                       #(reset! searching? true)))])
     (when @searching?
       (search-dialog opts search
                      {:on-value on-value
                       :on-close (close-fn searching?
                                           ".edit-value__input")}))]))

(defn input
  "The input in a table with `opts` for a value of the `input-type`, with
  the `props`: a dropdown of its options, or else a text input."
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

(defn refocus!
  "Have the field with the component `state` move the focus to its control
  that the CSS `selector` matches, once it is drawn again (see refocus)."
  [state selector]
  (vreset! (::refocus state) selector))

(def keep-focus
  "A mixin for a field. When the field goes away while the focus is in it,
  e.g. when an added value is taken away or when its row is full, the focus
  moves to the nearest control that is on the page once it is drawn again:
  a button of the field after it, the add button of its row, a button of
  the last field in its row, or the button that adds a row to its table."
  {:will-unmount
   (fn [state]
     #?(:cljs
        (let [node (rum/dom-node state)]
          (when (.contains node js/document.activeElement)
            (let [after    (.-nextElementSibling node)
                  fields   (.-parentNode node)
                  row      (.closest node "tr")
                  table    (.closest node "table")
                  ;; not a button in the nested table of a blank node
                  own      ":scope > .edit-value__actions button"
                  controls [#(some-> after (.querySelector own))
                            #(some-> row
                                     (.querySelector (str ":scope " row-plus)))
                            #(some-> (.querySelectorAll
                                       fields
                                       ":scope > .edit-value:not(.edit-value--locked)")
                                     (array-seq)
                                     (last)
                                     (.querySelector own))
                            #(some-> (.-nextElementSibling table)
                                     (.querySelector "button"))]]
              (after-redraw!
                ;; unless the focus has gone elsewhere by itself
                #(when (= js/document.body js/document.activeElement)
                   (some-> (first (filter (fn [control]
                                            (some-> control .-isConnected))
                                          (map (fn [f] (f)) controls)))
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

  The tint is the :status of the value (see row/fields). A field with a
  draft, a typed text that isn't a value yet, is :edited, or :added when
  its value is new.

  - :edit opens the input: on a closed field, unless its value is removed.
  - :undo puts the value of the data back: on an edited, a moved or a
    removed value.
  - :remove takes the value away: on an added value, and on a closed field
    with no tint, an edited or a moved one."
  [open? tint]
  (cond-> []
    (and (not open?) (not= tint :retracted)) (conj :edit)
    (contains? #{:edited :moved :retracted} tint) (conj :undo)
    (or (= tint :added)
        (and (not open?) (contains? #{nil :edited :moved} tint)))
    (conj :remove)))

(defn action-buttons
  "The buttons of a field with the `tint`, by whether it is `open?` as an
  input (see field-buttons), from `actions`, a map of a button to its label
  and function. A button that isn't among them is left out.

  With `required`, the mark of a value that its row must keep, the mark is
  there in place of the button that removes the value."
  [open? tint actions & [required]]
  (for [button (field-buttons open? tint)
        :let   [[label f :as action] (get actions button)
                mark (when (= button :remove) required)]
        :when  (or action mark)]
    (or mark (icon-button (name button) label f))))

(defn buttons
  "The buttons of a closed field with the `tint`, from `actions` and with
  the mark `required` (see action-buttons)."
  [tint actions & [required]]
  [:span.edit-value__actions (action-buttons false tint actions required)])

(defn opens-input?
  "Does the click `e` open the input of a closed field? Not on a link or a
  control, or when it selects text."
  [e]
  #?(:clj  false
     :cljs (not (or (.closest (.-target e) "a, button, select, [tabindex]")
                    (seq (str (js/getSelection)))))))

(defn invalid-content
  "The content of a closed field whose entered text `s` isn't a value. The
  `message` says why to a reader who can't see the wavy line under it."
  [s message]
  [:ins.edit-value__content.edit-value__content--invalid
   s [:span.visually-hidden ": " message]])

(defn subject-of
  "The subject of the values in a table with `opts`: the blank node of a
  nested table, or else the resource of the page."
  [{:keys [subject blank-node] :as opts}]
  (if blank-node
    (:rdf/about blank-node)
    subject))

(defn node-place
  "The place of the blank node of a nested table with `opts`, as
  change/locate-nodes takes it: the :subject and :property that hold the
  node, and those of its :values that can be edited. A node that an editor
  has made is :new? and has no values in the data."
  [{:keys [subject blank-node] :as opts}]
  (let [{:keys [property entity new?]} blank-node
        editable (fn [k vs]
                   (into #{}
                         (filter #(nil? (lock/lock opts k %)))
                         (shared/setify vs)))]
    (if new?
      {:subject subject :property property :new? true}
      {:subject  subject
       :property property
       :values   (into {}
                       (keep (fn [[k vs]]
                               (when-let [vs (not-empty (editable k vs))]
                                 [k vs])))
                       entity)})))

(defn edit!
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

(defn edit-label
  "The name of the button that opens the input of the value that `text`
  names, for a reader with `languages`."
  [languages text]
  (str (i18n/da-en languages "Ret" "Edit") ": " text))

(defn remove-label
  "The name of the button that takes away the value that `text` names, for
  a reader with `languages`."
  [languages text]
  (str (i18n/da-en languages "Fjern" "Remove") ": " text))

(defn move-note
  "The note of a value that has moved from one row to another, for a reader
  of a table with `opts`: the property that the `field` (see row/fields)
  has it :moved-from or :moved-to, or nil."
  [{:keys [languages] :as opts} {:keys [moved-from moved-to] :as field}]
  (cond
    moved-from [:small.edit-value__note
                (i18n/da-en languages "Flyttet fra" "Moved from") ": "
                (rdf/resource-name moved-from opts)]
    moved-to [:small.edit-value__note
              (i18n/da-en languages "Flyttet til" "Moved to") ": "
              (rdf/resource-name moved-to opts)]))

(defn removed-note
  "The note of a removed value `v` in `opts` that is an entry which the
  pending changes remove as a whole, or nil. Undoing the removal of such a
  value puts the whole entry back."
  [{:keys [languages changes] :as opts} v]
  (when (entry/removed? changes v)
    [:small.edit-value__note
     (i18n/da-en languages
       "Hele ressourcen fjernes"
       "The whole resource is being removed")]))

(defn value-text
  "The value `v` as the text that names it in a label or a tooltip, in a
  table with `opts`."
  [opts v]
  (if (keyword? v)
    (rdf/link-label opts v)
    (str v)))

(defn status-tag
  "The element that marks a value with the `status` (see row/fields) as a
  change: :del for a removed one and :ins for an added, an edited or a moved
  one. Nil for a value of the data."
  [status]
  (case status
    :retracted :del
    (:added :edited :moved) :ins
    nil))

(defn value-content
  "The content of a field in a table with `opts`: an element with the `tag`
  that holds the value `v`. With `class`, it has that class too."
  [opts tag v & [class]]
  [tag
   (update (rdf/lone-value-attrs v) :class
           #(remove nil? ["edit-value__content" class %]))
   (rdf/value opts v)])

(defn with-note
  "The `content` of a field with the `note` after it, when it has one."
  [content note]
  (if note
    [:span.edit-value__content content note]
    content))

(rum/defcs value-field < (rum/local false ::editing?)
                         (rum/local nil ::draft)
                         refocus
                         keep-focus
  "A field for the value `v` of the property `k` in `opts`, which is an
  rdf:_N property in the row of members.

  The `props` are the field (see row/fields), with the :input-type of its
  input, the :langs of a text and :required, the constraints of a row that
  must keep the value. With :on-remove, the remove button calls that
  function, e.g. to remove the blank node that holds the value. An entered
  text that isn't a value is kept as a draft and shown as an edit."
  [state {:keys [languages comments] :as opts}
   k v {:keys [input-type status original langs required moved-to on-remove]
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
        text     (or @draft (value-text opts v))
        close!   (fn []
                   (refocus! state "button")
                   (reset! editing? false))
        open!    (fn [e]
                   (when (and (not @editing?) (opens-input? e))
                     (reset! editing? true)))
        ;; a new status replaces the content, and with it the control in focus
        retag!   (fn [lang']
                   (refocus! state (if lang' "select" "button"))
                   (edit! opts change/replace
                          t [subject k (i18n/with-lang v lang')]))
        ;; A pending value is taken away, and what its change removes stays
        ;; removed: the value that it replaces, or the one in its old row.
        remove!  (fn [& _]
                   (reset! draft nil)
                   ;; a removed value keeps its field, with other buttons
                   (refocus! state "button")
                   (cond
                     on-remove (on-remove)
                     status (edit! opts change/without-addition t)
                     :else (edit! opts change/retract t)))
        actions  {:edit   [(edit-label languages text) #(reset! editing? true)]
                  :undo   [(undo-label languages (if moved-to :moved tint) text)
                           (fn [_]
                             (reset! draft nil)
                             (close!)
                             (when status
                               (edit! opts change/without-triple t)))]
                  :remove [(remove-label languages text) remove!]}
        note     (or (move-note opts props)
                     (when (= status :retracted)
                       (removed-note opts v)))
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
               :label      (edit-label languages text)
               :tint       tint
               :actions    actions
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
                               (edit! opts change/replace t [subject k v'])))})

       @draft
       (invalid-content @draft message)

       :else
       (with-note
         (conj (value-content opts (or (status-tag status) :span) v)
               ;; A text without a tag gets its dropdown in the input, and a
               ;; text that can only be in its own language keeps the plain
               ;; tag.
               (when (and langs lang (not= langs [lang])
                          (not= status :retracted))
                 (lang-select opts langs lang retag!)))
         note))
     (when-not @editing?
       (buttons tint actions
                (when required
                  (required-mark languages required))))]))

(rum/defcs new-value-field < (rum/local false ::adding?)
                             (rum/local nil ::draft)
                             refocus
                             keep-focus
  "The field for a new value of the property `k` in `opts`: a button that
  opens an input. An entered value is added unless it is `taken`.

  The `props` are the :input-type of the input, the :lang of a new text, the
  :langs that it can be in and :gap? when the row has no text in that
  language, which the button then names. With :on-add, an entered value goes
  to that function, e.g. to add it inside a new blank node. An entered text
  that isn't a value is kept as a draft and shown as an addition."
  [state {:keys [languages attr-key] :as opts}
   k taken {:keys [input-type lang langs gap? on-add]}]
  (let [adding? (::adding? state)
        draft   (::draft state)
        subject (subject-of opts)
        tint    (when @draft :added)
        label   (cond-> (plus-label opts attr-key)
                  gap? (str " " (i18n/da-en languages "på" "in")
                            " " (language-name languages lang)))
        close!  (fn []
                  (refocus! state "button")
                  (reset! adding? false))
        open!   (fn [e]
                  (when (opens-input? e)
                    (reset! adding? true)))
        drop!   (fn [& _]
                  (reset! draft nil)
                  (close!))
        actions {:edit   [(edit-label languages @draft) #(reset! adding? true)]
                 :remove [(remove-label languages @draft) drop!]}
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
               :tint       tint
               :actions    actions
               :on-cancel  close!
               :on-empty   (when @draft drop!)
               :on-invalid (fn [s leaving?]
                             (reset! draft s)
                             (when leaving?
                               (close!)))
               :on-value   (fn [v]
                             (reset! draft nil)
                             (close!)
                             (cond
                               (some #{v} taken) nil
                               on-add (on-add v)
                               :else (edit! opts change/add [subject k v])))})

       @draft
       [:<>
        (invalid-content @draft message)
        (buttons tint actions)]

       :else
       (add-button label #(reset! adding? true) (when gap? lang)))]))

(defn new-labels
  "Labels for new blank nodes that none of the `nodes` of the session has
  yet, e.g. _new3 as the first one after two new nodes."
  [nodes]
  (let [n (count (filter :new? (vals nodes)))]
    (map #(symbol (str "_new" (+ n % 1))) (range))))

(rum/defcs entry-field < (rum/local false ::editing?)
                         refocus
                         keep-focus
  "A field for the new entry with the temporary ID `id` in a row of a table
  with `opts`: the label that the pending changes give the entry, as a link
  to the page of the entry. The label can be edited here too. The remove
  button takes the entry away, with what can't stay without it (see
  entry/without).

  The field also names the other entries that hold the entry, e.g. the
  synset of a new sense in the row of a word, which tells two senses with
  the same label apart."
  [state {:keys [languages changes draft? subject edit-rules] :as opts} id]
  (let [editing? (::editing? state)
        without  #(entry/without (:entries edit-rules) % id)
        label    (get (entry/labels changes) id)
        others   (map #(rdf/link-label opts %)
                      (entry/held-by (:entries edit-rules) changes id
                                     (subject-of opts)))
        text     (cond-> (if label (str label) (prefix/kw->qname id))
                   (seq others) (str " (" (str/join ", " others) ")"))
        close!   (fn []
                   (refocus! state "button")
                   (reset! editing? false))
        remove!  (fn [& _]
                   (edit! opts without)
                   ;; the page of a new entry that went with it is empty
                   (when (and draft?
                              (nil? (entry/entity (without changes) subject)))
                     (app/navigate-to prefix/changes-path {:replace? true})))
        actions  {:edit   [(edit-label languages text) #(reset! editing? true)]
                  :remove [(remove-label languages text) remove!]}]
    [:li.edit-value.edit-value--added
     (cond-> {:data-entry (prefix/kw->qname id)}
       (not @editing?) (assoc :class "edit-value--clickable"
                              :on-click (fn [e]
                                          (when (opens-input? e)
                                            (reset! editing? true)))))
     (if @editing?
       (text-input opts input-type/text
                   {:value       label
                    :lang        (i18n/lang label)
                    :label       (edit-label languages text)
                    :tint        :added
                    :actions     actions
                    :keep-reason (i18n/da-en languages
                                   "En ny ressource skal have en etiket"
                                   "A new resource needs a label")
                    :on-cancel   close!
                    :on-value    (fn [label']
                                   (close!)
                                   (if label
                                     (edit! opts change/replace
                                            [id :rdfs/label label]
                                            [id :rdfs/label label'])
                                     (edit! opts change/add
                                            [id :rdfs/label label'])))})
       [:<>
        ;; as any other resource in the row, so with a link to its page
        (with-note [:ins.edit-value__content (rdf/value opts id)]
                   (when (seq others)
                     [:small.edit-value__note (str/join ", " others)]))
        (buttons :added actions)])]))

(defn discard-entry!
  "Take the new entry with the `id` out of the pending changes of a page
  with `opts`, with what can't stay without it (see entry/without)."
  [{:keys [edit-rules] :as opts} id]
  (app/update-changes! #(entry/without (:entries edit-rules) % id)))

(defn entry-button
  "A button with the `text` that acts on the whole entry with the `id`, on a
  page with `opts`. Its name also says which entry."
  [opts id text on-click]
  [:button.entry-button {:type       "button"
                         :aria-label (str text ": " (rdf/link-label opts id))
                         :on-click   on-click}
   text])

(defn discard-entry-button
  "The button that takes the new entry with the `id` away as a whole, on a
  page with `opts`. Its `on-click` does that (see discard-entry!), and what
  the page needs besides, e.g. to move the focus."
  [{:keys [languages] :as opts} id on-click]
  (entry-button opts id
                (i18n/da-en languages
                  "Fjern den nye ressource"
                  "Remove the new resource")
                on-click))

(defn restore-entry!
  "Put the removed entry `id` of the data back: drop the pending change that
  removes it as a whole (see entry/without-removal)."
  [id]
  (app/update-changes! entry/without-removal id))

(defn restore-entry-button
  "The button that puts the removed entry with the `id` back, on a page
  with `opts`. Its `on-click` does that (see restore-entry!), and what the
  page needs besides, e.g. to move the focus."
  [{:keys [languages] :as opts} id on-click]
  (entry-button opts id
                (i18n/da-en languages
                  "Fortryd fjernelsen af ressourcen"
                  "Undo the removal of the resource")
                on-click))

(defn fetch-removal
  "A promise of what the server has for removing the entry `id` of the data
  (see db.change/removal-parts), or of nil when the data has no such entry
  or the request fails."
  [id]
  #?(:cljs
     ;; each entry has a URL of its own, so one request doesn't end another
     (let [url (str "/dannet/edit/removal?id="
                    (js/encodeURIComponent (prefix/kw->qname id)))]
       (-> (app/fetch! url)
           (.then (fn [response]
                    (app/clear-current-fetch url)
                    (let [parts (get-in response [:body :data])]
                      (when (and (= 200 (:status response))
                                 (seq (:triples parts)))
                        parts))))
           (.catch (fn [_]
                     (app/clear-current-fetch url)
                     nil))))))

(defn entity-labels
  "The label of each resource in `entities`, a map of a resource to its
  entity as the server sends it, as a page with `opts` shows them."
  [{:keys [detail-level] :as opts} entities]
  (update-vals entities (shared/->entity-label-fn detail-level)))

(defn remove-entry!
  "Make the removal of an entry of the data a pending change, on a page
  with `opts`: the one that the `answers` of the editor make of the `parts`
  that the server has for it (see ui.edit.removal and entry/with-removal). The
  places of its blank nodes and the labels of its resources are kept, which
  the list of the changes needs."
  [{:keys [edit-rules] :as opts} parts answers]
  (doseq [[label place] (removal/nodes parts)]
    (app/remember-node! label place))
  (app/remember-labels! (entity-labels opts (:entities parts)))
  (edit! opts #(entry/with-removal (:entries edit-rules) %
                                   (removal/removal parts answers))))

(def fetch-parts
  "A mixin that asks the server for the parts of a removal when its wizard
  is drawn, and keeps them as ::parts. The wizard closes when the data has
  no such entry."
  {:did-mount (fn [state]
                #?(:cljs
                   (let [[_ id {:keys [on-close]}] (:rum/args state)]
                     (.then (fetch-removal id)
                            #(if %
                               (reset! (::parts state) %)
                               (on-close)))))
                state)})

(rum/defcs removal-wizard < (rum/local nil ::parts)
                            fetch-parts
  "The wizard that removes the entry `id` of the data, on a page with
  `opts`. It is drawn once the server has given the parts of the removal.
  Then it says what goes with the entry and asks about the rest (see
  ui.edit.removal).

  It calls `on-done` right before the removal is made, e.g. to say where
  the focus goes, and `on-close` when it closes without one."
  [state {:keys [languages] :as opts} id {:keys [on-done on-close]}]
  (when-let [parts @(::parts state)]
    (let [labelled (update opts :k->label
                           #(merge (entity-labels opts (:entities parts)) %))]
      (dialog/wizard
        languages
        (assoc (removal/flow languages id parts)
          ;; a link would leave the page, and the wizard with it
          :resource-fn #(rdf/resource-name % labelled))
        {:on-done  (fn [answers]
                     (on-done)
                     (remove-entry! opts parts answers))
         :on-close on-close}))))

(rum/defcs remove-entry-button < (rum/local nil ::opener)
  "The button that removes the entry `id` of the data as a whole, on its own
  page with `opts`. It opens the wizard of the removal, which has to be
  confirmed (see removal-wizard).

  The button that puts the entry back then takes its place and the focus."
  [state {:keys [languages] :as opts} id]
  (let [opener (::opener state)]
    [:<>
     (entry-button
       opts id
       (i18n/da-en languages "Fjern ressourcen" "Remove the resource")
       #(reset! opener (.-currentTarget %)))
     (when-let [button @opener]
       (removal-wizard
         opts id
         {:on-done  (fn []
                      (reset! opener nil)
                      #?(:cljs
                         (let [header (.closest button "header")]
                           ;; the header still has this button at first
                           (when-drawn! #(let [other (.querySelector
                                                       header ".entry-button")]
                                           (when (not= other button)
                                             other))
                                        #(.focus %)))))
          :on-close #(reset! opener nil)}))]))

(rum/defcs data-entry-field < (rum/local false ::removing?)
                              refocus
                              keep-focus
  "A field for the entry `v` of the data in the row of the property `k` in
  `opts`, when the row takes new entries, e.g. a sense of a synset. The
  `props` are the field (see row/fields), with :required, the constraints
  of a row that must keep the value.

  The remove button removes the whole entry, since an entry without the
  link would be broken. It opens the wizard of the removal (see
  removal-wizard). A removed entry says so, and its button puts it back."
  [state {:keys [languages] :as opts} k v {:keys [status required]}]
  (let [removing? (::removing? state)
        removed?  (= status :retracted)
        text      (value-text opts v)
        link      [(subject-of opts) k v]
        content   (value-content opts (or (status-tag status) :span) v)]
    [:li.edit-value (when removed? {:class "edit-value--retracted"})
     (if removed?
       [:span.edit-value__content content (removed-note opts v)]
       content)
     (buttons status
              {:undo   [(undo-label languages :retracted text)
                        (fn [_]
                          (refocus! state "button")
                          (edit! opts change/without-triple link))]
               :remove [(str (i18n/da-en languages
                               "Fjern hele ressourcen"
                               "Remove the whole resource")
                             ": " text)
                        #(reset! removing? true)]}
              (when required
                (required-mark languages required)))
     (when @removing?
       (removal-wizard opts v
                       {:on-done  (fn []
                                    (reset! removing? false)
                                    (refocus! state "button"))
                        :on-close #(reset! removing? false)}))]))

(defn with-pending
  "The `search` for an entry with the new entries in `pending` among its
  results, before the ones that it finds itself: those whose label has the
  typed text in it, and all of them before a text is typed.

  A pending result is a map of the :pending ID of a new entry, its :label
  and its :kind, which is what such an entry is called in each language.
  The result says it after its label."
  [{:keys [results-fn value-fn label-fn hiccup-fn] :as search} pending]
  (let [matching (fn [s]
                   (let [s (str/lower-case (str/trim s))]
                     (filter #(str/includes? (str/lower-case (str (:label %)))
                                             s)
                             pending)))]
    (if (empty? pending)
      search
      (assoc search
        :list-all?  true
        ;; the search itself asks the server, which only a browser does
        :results-fn (fn [s]
                      #?(:clj  (matching s)
                         :cljs (.then (js/Promise.resolve (results-fn s))
                                      #(concat (matching s) %))))
        :value-fn   #(or (:pending %) (value-fn %))
        :label-fn   #(when (and label-fn (not (:pending %)))
                       (label-fn %))
        :hiccup-fn  (fn [{:keys [label kind] :as result}
                         {:keys [languages] :as opts}]
                      (if (:pending result)
                        [:<>
                         [:strong (str label)]
                         [:span (i18n/localize languages kind)]]
                        (hiccup-fn result opts)))))))

(rum/defcs new-entry-field < (rum/local false ::choosing?)
  "The field for a new entry in the row of the property `k` in `opts`: a
  button that opens a dialog, which asks for the gap of the template of
  `row-entry` that the subject of the row doesn't fill (see entry/for-row).
  The answer fills the template.

  The dialog says what the template adds besides, lists the new entries of
  the classes of the gap, and refuses an answer that already shares an
  entry of the template with the subject. It offers to make the answer
  when the typed text fits the template of one of the classes, and a made
  answer is shown on its own page."
  [state {:keys [languages changes entity inverses edit-rules nodes] :as opts}
   k {:keys [template given] :as row-entry}]
  (let [choosing? (::choosing? state)
        s         (subject-of opts)
        entries   (:entries edit-rules)
        [gap classes] (first (entry/asked template [given]))
        ;; a new answer is made from the template that the typed text fits
        answers   (keep entries classes)
        labels    (entry/labels changes (:derived-labels edit-rules))
        pending   (sort-by :pending
                           (for [class classes
                                 id    (entry/of-class changes class)]
                             {:pending id
                              :label   (get labels id)
                              :kind    (:name (get entries class))}))
        search    (with-pending (:search (some input-type/class-input-types
                                               classes))
                                pending)
        value-fn  (:value-fn search)
        taken     (when (:once template)
                    (into (entry/known template inverses entity given gap)
                          (entry/linked template changes given s gap)))
        reason    (get-in template [:once :reason])
        focus!    (fn [change]
                    #?(:cljs
                       (when-let [id (first (change/temporary-ids [change]))]
                         (when-drawn!
                           #(.querySelector
                              js/document
                              (str "[data-entry=\"" (prefix/kw->qname id)
                                   "\"] button"))
                           #(.focus %)))))
        add!      (fn [result]
                    (let [found    (or (some->> (:pending result)
                                                (entry/entity changes))
                                       result)
                          starting (entry/starting template {given entity
                                                             gap   found})
                          values   (assoc starting
                                     given s
                                     gap (value-fn result))
                          change   (entry/fill template changes values)]
                      (edit! opts conj change)
                      (focus! change)))
        make      (fn [text]
                    (some (fn [{:keys [name] :as answer}]
                            (some-> (entry/new-entry answer changes text
                                                     (new-labels nodes))
                                    (assoc :name name)))
                          answers))
        make!     (fn [text]
                    (let [{:keys [id] :as made} (make text)
                          change (entry/fill-around template changes gap made
                                                    {given s}
                                                    {given entity})]
                      ;; a new blank node has its place in the session
                      (doseq [[s' p o] (:add change)
                              :when (term/blank-node? o)]
                        (app/remember-node! o {:subject  s'
                                               :property p
                                               :new?     true}))
                      (edit! opts conj change)
                      (app/navigate-to (prefix/resolve-href id))))]
    [:li.edit-value.edit-value--new
     (add-button (plus-label opts k) #(reset! choosing? true))
     (when @choosing?
       (search-dialog
         opts
         (cond-> (assoc search :note (:note template))
           taken (assoc :refuse-fn
                        (fn [result]
                          (when (contains? taken (value-fn result))
                            (i18n/localize languages reason)))))
         {:on-result (fn [result]
                       (reset! choosing? false)
                       (add! result))
          :new-fn    (when (seq answers)
                       make)
          :on-new    (fn [text]
                       (reset! choosing? false)
                       (make! text))
          :on-close  (close-fn choosing? "button")}))]))

(defn node-text
  "The text that names a blank node in the row of the property `k` in
  `opts`: its `values` (see row/node-values) as texts, a resource by its
  label, or the name of the row when it has none."
  [opts k values]
  (if-let [texts (seq (distinct (map #(value-text opts %) values)))]
    (str/join ", " texts)
    (rdf/link-label opts k)))

(defn add-node!
  "Add a new blank node as a value of the property `k` in a table with
  `opts`, under a label that no other node of the session has.

  With `value`, a pair of a property and a value, the node is added with
  that value as one change. Without it, the first field of the node is
  opened once the page is drawn with it."
  [{:keys [nodes] :as opts} k & [[p v :as value]]]
  (let [subject (subject-of opts)
        label   (first (new-labels nodes))
        link    [subject k label]]
    (app/remember-node! label {:subject subject :property k :new? true})
    (if value
      (edit! opts conj {:add #{link [label p v]}})
      (do
        (edit! opts change/add link)
        #?(:cljs (when-drawn! #(.querySelector js/document
                                               (str (node-selector label)
                                                    " .edit-value__add"))
                              #(.click %)))))))

(rum/defcs node-field < refocus
                        keep-focus
  "A field for the blank node with the `label` in the row of the property
  `k` in `opts`: its nested table, and a button that removes the node.

  The `props` are the field (see row/fields), with the :node of the page,
  which a new node lacks, and :required, the constraints of a row that
  must keep the node. A removed node shows the values that it has in the
  data, with a button that puts it back."
  [state {:keys [languages default-origin changes] :as opts}
   k label {:keys [status node required]}]
  (let [place    (when node
                   (node-place (rdf/node-opts opts node)))
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
                   (if node
                     (do
                       (refocus! state button)
                       (app/remember-node! label place)
                       (edit! opts change/retract-node label place))
                     (edit! opts change/without-node label)))]
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
        :else (rdf/node-table opts (with-meta {:rdf/about label}
                                              {:default-origin default-origin
                                               :new?           true})))]
     (buttons status
              {:undo   [(undo-label languages :retracted text)
                        (fn [_]
                          (refocus! state button)
                          (edit! opts change/without-node label))]
               :remove [(remove-label languages text) remove!]}
              (when required
                (required-mark languages required)))]))

(defn- sole-field
  "The field of a blank node that only holds the value of the property
  `sole` (see row/sole-property), in the row of the property `k` in `opts`:
  the field of that value, whose remove button removes the node with it.

  The node has the `label`, and `node` is the node of the page, which a new
  node lacks. With `required`, the row must keep the node. Nil when
  the node has no such value."
  [{:keys [changes default-origin] :as opts} k sole label node required]
  (let [nested (-> (rdf/node-opts opts (or node
                                           (with-meta
                                             {:rdf/about label}
                                             {:default-origin default-origin
                                              :new?           true})))
                   (assoc :attr-key sole))
        place  (node-place nested)
        must   (get-in nested [:constraints sole :must])
        [{:keys [value] :as field}]
        (row/fields changes label [sole]
                    (map #(vector sole %) (get-in place [:values sole])))]
    (when field
      (value-field
        nested sole value
        (assoc field
          :input-type (input-type/input-type [value] must)
          :required required
          :on-remove (fn []
                       (if node
                         (do
                           (app/remember-node! label place)
                           (edit! opts change/retract-node label place))
                         (edit! opts change/without-node label))))))))

(rum/defc node-cell
  "A table cell for the values `vs` in the row of the property `k` in `opts`
  in edit mode, when the shapes describe its values as blank nodes: a field
  for each node that can be edited, a button that adds a node, and last the
  locked values with a padlock that says why.

  The :constraints of the table say whether the row can take another node
  or lose one. A node that only holds one value is the field of that value
  (see sole-field), and a new one is added with its value."
  [{:keys [changes comments languages constraints] :as opts} k vs]
  (let [subject  (subject-of opts)
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
        current  (+ (count locked)
                    (count (remove (comp #{:retracted} :status) fields)))
        full?    (some-> (:max must) (<= current))
        required (when (and (= 1 (:min must)) (>= 1 current))
                   must)
        label    (plus-label opts k)]
    [:td
     [:ul.edit-values
      (for [{:keys [value] :as field} fields
            :let [node (get by-label value)
                  ;; the page of a new entry has a new node as its bare label
                  node (when (map? node)
                         node)]]
        (rum/with-key
          (or (when sole
                (sole-field opts k sole value node required))
              (node-field opts k value
                          (assoc field :node node :required required)))
          (str value)))
      (cond
        full?
        nil

        sole
        (rum/with-key
          (new-value-field
            opts k nil
            {:input-type (input-type/input-type [] (get-in shape [sole :must]))
             :on-add     #(add-node! opts k [sole %])})
          "new")

        :else
        [:li.edit-value.edit-value--new {:key "new"}
         (add-button label #(add-node! opts k))])
      (for [v locked]
        [:li.edit-value.edit-value--locked
         (assoc (rdf/lone-value-attrs v) :key (pr-str (about v)))
         (padlock (get-in comments [:locked (lock/lock opts k v)]))
         (rdf/value opts v)])]]))

(rum/defc cell
  "A table cell for the values `vs` in the row of the property `k` in `opts`
  in edit mode: a field for each value that can be edited, a field for a new
  one, and last the locked values with a padlock that says why.

  A set of values is sorted by language, the reader's preferred one first,
  and then by text. The :constraints of the table shape the fields (see
  dk.cst.dannet.db.shapes/property-constraints). A new entry among the
  values, a row that takes new entries and an entry of the data in such a
  row each have a field of their own (see entry-field, new-entry-field and
  data-entry-field)."
  [{:keys [changes comments languages edit-rules constraints] :as opts} k vs]
  (let [subject     (subject-of opts)
        {:keys [must should]} (get constraints k)
        rank        (row/lang-rank languages)
        sorted      (if (set? vs)
                      (sort-by (juxt (comp rank i18n/lang)
                                     (rdf/label-sortkey-fn opts))
                               vs)
                      vs)
        pairs       (distinct (for [v sorted
                                    p (lock/value-properties opts k v)]
                                [p v]))
        pair->lock  (into {}
                          (map (fn [[p v :as pair]]
                                 [pair (lock/lock opts p v)]))
                          pairs)
        properties  (row/properties opts k)
        fields      (row/fields changes subject properties
                                (remove pair->lock pairs))
        input-type  (input-type/input-type vs must)
        locked      (map second (filter pair->lock pairs))
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
        ;; or a new entry, in a row that takes those
        row-entry   (entry/for-row (:entries edit-rules) k)
        ;; A locked value can be added, since the graph that changes go to
        ;; doesn't hold it, and a container can hold a member twice.
        taken       (when-not (= k shared/member-row)
                      (map :value fields))
        item-key    (fn [p v] (pr-str [p (if (map? v) (:rdf/about v) v)]))]
    [:td
     [:ul.edit-values
      (for [{:keys [property value original status] :as field} fields]
        ;; keyed by its property and by the value that it replaces, so that
        ;; an edit keeps its field
        (rum/with-key (cond
                        (change/temporary? value)
                        (entry-field opts value)

                        ;; an entry of the data in a row that takes new ones
                        (and row-entry
                             (keyword? value)
                             (contains? #{nil :retracted} status))
                        (data-entry-field opts property value
                                          (assoc field :required required))

                        :else
                        (value-field opts property value
                                     (assoc field
                                       :input-type input-type
                                       :langs langs
                                       :required required)))
                      (item-key property (if (some? original) original value))))
      (cond
        (or full? (some-> (:options new-type) empty?))
        nil

        row-entry
        (rum/with-key (new-entry-field opts k row-entry) "new")

        :else
        (rum/with-key (new-value-field
                        opts (row/new-value-property k properties) taken
                        {:input-type new-type
                         :langs      langs
                         :lang       (or gap (row/new-text-lang (first taken)
                                                                expected))
                         :gap?       (some? gap)})
                      "new"))
      (for [[p v :as pair] (filter pair->lock pairs)]
        [:li.edit-value.edit-value--locked
         (assoc (rdf/lone-value-attrs v) :key (item-key p v))
         (padlock (get-in comments [:locked (pair->lock pair)]))
         (rdf/value opts v)])]]))

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
      (icon "add")
      (i18n/localize languages title)]
     (when @choosing?
       (search-dialog opts
                      (property-search opts ks title true)
                      {:on-value (fn [k]
                                   (reset! choosing? false)
                                   (on-add k))
                       :on-close (close-fn choosing? "button")}))]))

(defn open-new-value!
  "Open the field for a new value in the row of the property `k` in the
  table of `subject` (see subject-of), once the page is drawn with it."
  [subject k]
  (when-drawn! #(in-row subject k row-plus) #(.click %)))

(defn- movable
  "The values in the row of the property `k` in `opts` that can move to
  another property: those of its values `vs` that can be edited and the
  ones that the pending changes add, but not the ones that they remove."
  [{:keys [changes] :as opts} k vs]
  (->> (for [v vs :when (nil? (lock/lock opts k v))] [k v])
       (row/fields changes (subject-of opts) [k])
       (remove (comp #{:retracted} :status))
       (map :value)))

(defn focus-row!
  "Move the focus to the first button of a value in the row of the property
  `k` of `subject`, once the page is drawn with it."
  [subject k]
  (when-drawn! #(in-row subject k ".edit-value .edit-value__button")
               #(.focus %)))

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
        [before after] (i18n/localize languages around)]
    (when (seq ks)
      [:<>
       (icon-button "edit"
                    (i18n/localize languages title)
                    #(reset! choosing? true))
       (when @choosing?
         (search-dialog
           opts
           (assoc (property-search opts ks title false)
             :heading [:<> before (rdf/resource-name k opts) after])
           {:on-value (fn [k']
                        (reset! choosing? false)
                        (edit! opts
                               (fn [changes]
                                 (reduce #(change/replace %1
                                                          [subject k %2]
                                                          [subject k' %2])
                                         changes
                                         values)))
                        (focus-row! subject k'))
            :on-close (close-fn choosing? ".edit-value__button")}))])))
