(ns dk.cst.dannet.web.ui.search
  (:require [rum.core :as rum]
            [ont-app.vocabulary.lstr :as lstr]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.web.i18n :as i18n]
            #?(:cljs [lambdaisland.uri :as uri])
            #?(:cljs [dk.cst.aria.combobox :as combobox])))

(defn- form-elements->query-params
  "Retrieve a map of query parameters from HTML `form-elements`."
  [form-elements]
  (into {} (for [form-element form-elements]
             (when (not-empty (.-name form-element))
               [(.-name form-element) (.-value form-element)]))))

(defn submit-form
  "Submit a form `target` element (optionally with a custom `query-string`)."
  [target & [query-str]]
  #?(:cljs (let [action    (.-action target)
                 query-str (or query-str
                               (-> (.-elements target)
                                   (form-elements->query-params)
                                   (uri/map->query-string)))
                 url       (str action (when query-str
                                         (str "?" query-str)))]
             (js/document.activeElement.blur)
             (shared/navigate-to url))))

;; TODO: handle other methods (only handles GET for now)
(defn on-submit
  "Generic function handling form submit events in Rum components."
  [e]
  #?(:cljs (let [target (.-target e)]
             (.preventDefault e)
             (submit-form target))))

(defn autofocus-ref
  [node]
  (when node (.focus node)))

(defn select-text
  "Select text in the target that triggers `e` with a small delay to bypass
  browser's other text selection logic."
  [e]
  #?(:cljs (js/setTimeout #(.select (.-target e)) 100)))

(defn update-search-suggestions
  "An :on-change handler for search suggestions. Each unknown string initiates
  a backend fetch for autocomplete results."
  [e]
  #?(:cljs (let [s                (.-value (.-target e))
                 s'               (shared/search-string s)
                 path             [:search :completion s']
                 autocomplete-url "/dannet/autocomplete"]
             (swap! shared/state assoc-in [:search :s] s')
             (when-not (get-in @shared/state path)
               (.then (shared/api autocomplete-url {:query-params {:s s'}})
                      #(do
                         (shared/clear-current-fetch autocomplete-url)
                         (when-let [v (not-empty (:autocompletions (:body %)))]
                           (swap! shared/state assoc-in path v))))))))

(defn search-completion-item-id
  [v]
  (str "search-completion-item-" v))

(rum/defc search-suggestion
  [v on-key-down]
  (let [handle-click (fn [_]
                       #?(:cljs (let [form  (js/document.getElementById "search-form")
                                      input (js/document.getElementById "search-input")]
                                  (set! (.-value input) v)
                                  (submit-form form (str "lemma=" v)))
                          :clj  nil))]
    [:li {:role        "option"
          :tab-index   "-1"
          :on-key-down on-key-down
          :id          (search-completion-item-id v)
          :on-click    handle-click}
     v]))

(rum/defcs search-form < (rum/local false ::open)
  [state {:keys [lemma search languages] :as opts}]
  (let [{:keys [completion s]} search
        open                (::open state)
        open?               @open
        prevent-closing     (fn [e]
                              (.preventDefault e)
                              (.stopPropagation e))
        s'                  (shared/search-string s)
        completion-items    (get completion s')
        suggestions?        (boolean (not-empty completion-items))
        submit-label        (i18n/select-label languages
                                               [(lstr/->LangStr "Søg" "da")
                                                (lstr/->LangStr "Search" "en")])
        on-key-down #?(:clj nil :cljs
                       (combobox/keydown-handler
                         #(let [form (js/document.getElementById "search-form")]
                            (submit-form form)
                            (js/document.activeElement.blur))
                         (js/document.getElementById "search-input")
                         (js/document.getElementById "search-completion")
                         {"Escape" (fn [e]
                                     (.preventDefault e)
                                     (js/document.activeElement.blur))}))
        toggle              (fn [e]
                              (.preventDefault e)
                              (.stopPropagation e)
                              (swap! open not))]
    [:form {:role      "search"
            :class     (if open? "search-active" "")
            :id        "search-form"
            :title     (i18n/da-en languages
                         "Søg efter synsets"
                         "Search for synsets")
            :action    prefix/search-path
            :on-click  toggle
            :on-submit on-submit
            :method    "get"}
     (when open?
       [:<>
        [:div.search-form__top
         [:input {:role                  "combobox"
                  :aria-expanded         suggestions?
                  :aria-controls         (str (when suggestions?
                                                "search-completion"))
                  :aria-activedescendant (str (when suggestions?
                                                "search-completion-selected"))
                  :id                    "search-input"
                  :name                  "lemma"
                  :placeholder           (i18n/da-en languages
                                           "skriv noget..."
                                           "write something...")
                  :on-key-down           on-key-down
                  :ref                   autofocus-ref
                  :on-focus              select-text
                  :on-click              prevent-closing    ; should not bubble
                  :on-change             update-search-suggestions
                  :auto-complete         "off"
                  :default-value         (or lemma "")}]
         [:input {:type      "submit"
                  :tab-index "-1"
                  :title     (str submit-label)
                  :value     (str submit-label)}]]
        [:ul {:role      "listbox"
              :tab-index "-1"
              :id        "search-completion"}
         (when suggestions?
           (for [v completion-items]
             (rum/with-key (search-suggestion v on-key-down) v)))]])]))
