(ns dk.cst.dannet.web.ui.search
  (:require [dk.cst.dannet.web.ui.rdf :as rdf]
            [rum.core :as rum]
            [ont-app.vocabulary.lstr :as lstr]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.web.i18n :as i18n]
            #?(:clj  [dk.cst.dannet.web.ui.error :as error]
               :cljs [dk.cst.dannet.web.ui.error :as error :include-macros true])
            #?(:cljs [lambdaisland.uri :as uri])
            #?(:cljs [dk.cst.dannet.web.ui.search.aria :as aria])))

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

(defn update-suggestions
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

(defn completion-item-id
  [v]
  (str "search-completion-item-" v))

(rum/defc suggestion
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
          :id          (completion-item-id v)
          :on-click    handle-click}
     v]))

(rum/defcs form < (rum/local false ::open)
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
                       (aria/keydown-handler
                         #(let [form (js/document.getElementById "search-form")]
                            (reset! open false)
                            (submit-form form)
                            (js/document.activeElement.blur))
                         {"Escape" (fn [e]
                                     (.preventDefault e)
                                     (js/document.activeElement.blur))}))
        toggle              (fn [e]
                              (.preventDefault e)
                              (.stopPropagation e)
                              (swap! open not))]
    [:search {:class    (if open? "search-active" "")
              :on-click toggle}
     (when open?
       [:<>
        [:form {:id        "search-form"
                :title     (i18n/da-en languages
                             "Søg efter synsets"
                             "Search for synsets")
                :action    prefix/search-path
                :on-submit on-submit
                :method    "get"}
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
                  :on-change             update-suggestions
                  :auto-complete         "off"
                  :default-value         (or lemma s "")}]
         [:input {:type      "submit"
                  :tab-index "-1"
                  :title     (str submit-label)
                  :value     (str submit-label)}]]
        [:output {:aria-live "polite"}
         [:ul {:role      "listbox"
               :tab-index "-1"
               :id        "search-completion"}
          (when suggestions?
            (for [v completion-items]
              (rum/with-key (suggestion v on-key-down) v)))]]])]))

(rum/defc result
  [k
   {:keys [dc/subject skos/definition dns/ontologicalType wn/lexfile] :as entity}
   {:keys [details? languages] :as opts}]
  (let [{:keys [k->label short-label]} (meta entity)
        opts' (assoc opts :k->label (if (and (not details?) short-label)
                                      (assoc k->label
                                        k short-label)
                                      k->label))
        pos   (some->> lexfile
                       (shared/lexfile->pos)
                       (get (i18n/da-en languages
                              shared/pos-abbr-da
                              shared/pos-abbr-en)))]
    [:<>
     [:dt (error/try-render
            (rdf/entity-link subject opts') (str subject))]
     [:dd
      [:ul
       [:li
        (when pos
          [:abbr.pos-label pos])
        (error/try-render
          (rdf/transform-val definition opts') (str definition))]
       [:li
        (error/try-render
          (rdf/blank-resource (assoc opts' :attr-key :dns/ontologicalType)
                              (meta ontologicalType)))]]]]))
