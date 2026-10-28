(ns dk.cst.dannet.web.ui.search
  "For handling search input and presenting search suggestions/results."
  (:require [dk.cst.dannet.web.ui.rdf :as rdf]
            [rum.core :as rum]
            [dk.cst.theophrastus.term :as term]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.web.i18n :as i18n]
            [dk.cst.dannet.web.app :as app]
            [dk.cst.dannet.web.ui.form :as form]
            [dk.cst.dannet.web.ui.entity :as entity]
            #?(:cljs [dk.cst.dannet.web.ui.search.aria :as aria])))

(defn update-suggestions
  "An :on-change handler for search suggestions. Each unknown string initiates
  a backend fetch for autocomplete results."
  [e]
  #?(:cljs (let [s                (.-value (.-target e))
                 s'               (shared/search-string s)
                 path             [:search :completion s']
                 autocomplete-url "/dannet/autocomplete"]
             (swap! app/session assoc-in [:search :s] s')
             (when-not (get-in @app/session path)
               (.then (app/fetch! autocomplete-url {:query-params {:s s'}})
                      #(do
                         (app/clear-current-fetch autocomplete-url)
                         (when-let [v (not-empty (get-in % [:body :data :autocompletions]))]
                           (swap! app/session assoc-in path v))))))))

(defn completion-item-id
  [v]
  (str "search-completion-item-" v))

(rum/defc suggestion
  [v on-key-down]
  (let [[lemma inflected] (if (vector? v) v [v])
        display      (if inflected
                       (str lemma " (" inflected ")")
                       lemma)
        handle-click (fn [_]
                       #?(:cljs (let [form  (js/document.getElementById "search-form")
                                      input (js/document.getElementById "search-input")]
                                  (set! (.-value input) lemma)
                                  (form/submit-form form (str "lemma=" lemma)))
                          :clj  nil))]
    [:li {:role        "option"
          :tab-index   "-1"
          :on-key-down on-key-down
          :id          (completion-item-id display)
          :on-click    handle-click}
     display]))

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
                                               [(term/->LangStr "Søg" "da")
                                                (term/->LangStr "Search" "en")])
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
         ;; No aria-activedescendant: the arrow keys move DOM focus onto the
         ;; options themselves (see search.aria).
         [:input {:role                  "combobox"
                  :aria-expanded         suggestions?
                  :aria-controls         (str (when suggestions?
                                                "search-completion"))
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
              (rum/with-key (suggestion v on-key-down) (str v))))]]])]))

;; TODO: hydration fails on /dannet/search?lemma=bank: in the link inside a
;;       result's <dt>, the server renders "bank" and the client "banke". Both
;;       render the same page data, so CLJ and CLJS differ somewhere on the
;;       way from the label to the text. Already broken on master.
(rum/defc result
  [k {:keys [dc/subject] :as entity} {:keys [detail-level languages] :as opts}]
  ;; db.search/look-up stores the synset under :dc/subject (so it equals k)
  ;; and puts the labels of a result in the metadata of its entity.
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
                                         (app/navigate-to href))))
           :on-key-down     (fn [e]
                              #?(:cljs (when (= "Enter" (.-key e))
                                         (app/navigate-to href))))}
     [:dt {:id dt-id}
      (rdf/entity-link subject opts')]
     [:dd
      (entity/synset-summary entity opts')]]))
