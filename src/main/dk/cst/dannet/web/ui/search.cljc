(ns dk.cst.dannet.web.ui.search
  "For handling search input and presenting search suggestions/results."
  (:require [dk.cst.dannet.web.ui.rdf :as rdf]
            [rum.core :as rum]
            [ont-app.vocabulary.lstr :as lstr]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.web.i18n :as i18n]
            [dk.cst.dannet.web.ui.form :as form]
            [dk.cst.dannet.web.ui.entity :as entity]
            #?(:clj  [dk.cst.dannet.web.ui.error :as error]
               :cljs [dk.cst.dannet.web.ui.error :as error :include-macros true])
            #?(:cljs [lambdaisland.uri :as uri])
            #?(:cljs [dk.cst.dannet.web.ui.search.aria :as aria])))

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
                                  (form/submit-form form (str "lemma=" v)))
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
                            (form/submit-form form)
                            (js/document.activeElement.blur))
                         {"Escape" (fn [e]
                                     (.preventDefault e)
                                     (js/document.activeElement.blur))}))
        toggle              (fn [e]
                              (.preventDefault e)
                              (.stopPropagation e)
                              (swap! open not))]
    [:search {:class    (if open? "search-active" "")
              :title    (when-not open?
                          (i18n/da-en languages
                            "Søg efter synsets"
                            "Search for synsets"))
              :on-click toggle}
     (when open?
       [:<>
        [:form {:id        "search-form"
                :title     (i18n/da-en languages
                             "Søg efter synsets"
                             "Search for synsets")
                :action    prefix/search-path
                :on-submit form/on-submit
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
                                           "begynd at skrive..."
                                           "start writing...")
                  :on-key-down           on-key-down
                  :ref                   form/autofocus-ref
                  :on-focus              form/select-text
                  :on-click              prevent-closing    ; should not bubble
                  :on-change             update-suggestions
                  :auto-complete         "off"
                  :required              true
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
  [k {:keys [dc/subject] :as entity} {:keys [detail-level languages] :as opts}]
  (let [{:keys [k->label short-label]} (meta entity)
        opts' (assoc opts :k->label (if (and (not= detail-level :high)
                                             short-label)
                                      (assoc k->label k short-label)
                                      k->label))
        dt-id (str "result-" (name subject))
        label (str (or (i18n/select-label languages (get k->label subject))
                       subject))
        href  (prefix/resolve-href subject)]
    ;; NOTE: divs are actually allowed by the spec as grouping elements in a dl!
    [:div {:role            "group"
           :aria-labelledby dt-id
           :tab-index       "0"
           :title           (i18n/da-en languages
                              (str "Gå til " label)
                              (str "Go to " label))
           :on-click        (fn [e]
                              #?(:cljs (when-not (.closest (.-target e) "a")
                                         (shared/navigate-to href))))
           :on-key-down     (fn [e]
                              #?(:cljs (when (= "Enter" (.-key e))
                                         (shared/navigate-to href))))}
     [:dt {:id dt-id}
      (error/try-render
        (rdf/entity-link subject opts') (str subject))]
     [:dd
      (entity/synset-summary entity opts')]]))
