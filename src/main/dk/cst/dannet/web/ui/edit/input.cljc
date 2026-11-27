(ns dk.cst.dannet.web.ui.edit.input
  "The inputs of the fields of edit mode (see ui.edit): a text input or a
  dropdown for a value of an input type, and the dialog that searches for a
  value (see ui.input-type)."
  (:require [clojure.string :as str]
            [rum.core :as rum]
            [dk.cst.dannet.entry :as entry]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.web.app :as app]
            [dk.cst.dannet.web.i18n :as i18n]
            [dk.cst.dannet.web.ui.dialog :as dialog]
            [dk.cst.dannet.web.ui.edit.control :as control]
            [dk.cst.dannet.web.ui.edit.focus :as focus]
            [dk.cst.dannet.web.ui.rdf :as rdf]))

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
                              #(reset! (::results state) (vec %))
                              #(reset! (::failed? state) true)))))
                state)})

(rum/defcs search-dialog < (rum/local nil ::results)
                           (rum/local 0 ::searches)
                           (rum/local "" ::text)
                           (rum/local false ::failed?)
                           list-all
  "A dialog on a page with `opts` that finds a value with the `search` (see
  ui.input-type). It calls `on-value` with the chosen value, or `on-result`
  with the result as it is, and `on-close` when nothing is chosen.

  A result that the search refuses says why and can't be chosen, and
  neither can an entry that the pending changes remove. With `new-fn`, a
  line before the results offers to make a value: the function takes the
  typed text and returns nil, or the :name of what it makes in each
  language and the :text that it is made from. `on-new` gets the text.

  When a search fails, the dialog says so, and Enter searches again."
  [state {:keys [languages changes] :as opts}
   {:keys [results-fn value-fn label-fn hiccup-fn refuse-fn one-line? title
           heading placeholder note]
    :as   search}
   {:keys [on-value on-result on-close new-fn on-new]}]
  (let [results  (::results state)
        searches (::searches state)
        text     (::text state)
        failed?  (::failed? state)
        failure  (i18n/da-en languages
                   "Søgningen mislykkedes. Tryk Enter for at prøve igen."
                   "The search failed. Press Enter to try again.")
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
                                        (reset! failed? false)
                                        (reset! results
                                                (with-meta (vec %)
                                                           {::search n})))
                                     #(when (= n @searches)
                                        (reset! failed? true)))))
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
                ;; the results are those of the text in the input. After a
                ;; search that failed, it searches again.
                :on-key-down      (fn [e]
                                    (when (= "Enter" (.-key e))
                                      (cond
                                        @failed?
                                        (do (.preventDefault e)
                                            (search! @text))

                                        (= @searches
                                           (::search (meta @results) 0))
                                        (when-let [result (->> @results
                                                               (remove refusal)
                                                               (first))]
                                          (.preventDefault e)
                                          (choose! result)))))}]
       (when offer
         [:button.search-dialog__new {:type     "button"
                                      :on-click #(on-new @text)}
          (control/icon "add")
          (localize (:name offer))
          (when-let [s (:text offer)]
            [:<> ": " [:strong s]])])
       ;; the number of results is announced, not every result, and so is the
       ;; line that offers to make a value of the text
       [:p.visually-hidden {:role "status"}
        (if @failed?
          failure
          (when-let [n (some-> @results count)]
            (str (i18n/da-en languages
                   (str n (if (= n 1) " resultat" " resultater"))
                   (str n (if (= n 1) " result" " results")))
                 (when offer
                   (str ". " (localize (:name offer))
                        (some->> (:text offer) (str ": ")))))))]
       [:div.search-dialog__body
        (cond
          @failed?
          [:p failure]

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
  empty text with a `keep-reason`. A text whose lookup fails is refused on
  Enter too, and dropped when the focus leaves. Its buttons are those of an
  open field with the `tint`, from `actions` (see control/action-buttons)."
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
        failed-reason (i18n/da-en languages
                        "Værdien kunne ikke slås op. Prøv igen."
                        "The value couldn't be looked up. Try again.")
        own        (control/action-buttons true tint actions)
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
        ;; A lookup that fails says nothing about the text, so Enter can try
        ;; again. The text is dropped when the focus leaves, as a value that
        ;; can't be entered is.
        failed!    (fn [input leaving?]
                     (if leaving?
                       (on-cancel)
                       (refuse! input failed-reason)))
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
                                       #(parsed! input s % leaving?)
                                       #(failed! input leaving?))))))))
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
       (control/lang-select opts langs lang' #(reset! chosen [%])
                            {:on-key-down (fn [e]
                                            (when (= "Escape" (.-key e))
                                              (cancel!)))}))
     (when (or (seq own) search)
       [:span.edit-value__actions
        own
        (when search
          (control/icon-button "search" (i18n/da-en languages "Søg" "Search")
                               #(reset! searching? true)))])
     (when @searching?
       (search-dialog opts search
                      {:on-value on-value
                       :on-close (focus/close-fn searching?
                                                 ".edit-value__input")}))]))

(defn input
  "The input in a table with `opts` for a value of the `input-type`, with
  the `props`: a dropdown of its options, or else a text input."
  [opts {:keys [options] :as input-type} props]
  (if options
    (options-input opts options props)
    (text-input opts input-type props)))
