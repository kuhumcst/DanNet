(ns dk.cst.dannet.web.components
  "Shared frontend/backend Rum components."
  (:require [rum.core :as rum]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.web.i18n :as i18n]
            [ont-app.vocabulary.lstr :as lstr]
            [dk.cst.dannet.web.components.entity :as entity]
            [dk.cst.dannet.web.components.search :as search]
            [dk.cst.dannet.web.components.table :as table]
            [dk.cst.dannet.web.components.markdown :as mdc]))

;; TODO: superfluous DN:A4-ark http://localhost:3456/dannet/data/synset-48300
;; TODO: empty synset http://localhost:3456/dannet/data/synset-47272
;; TODO: equivalent class empty http://localhost:3456/dannet/external/semowl/InformationEntity
;; TODO: empty definition http://0.0.0.0:3456/dannet/data/synset-42955

;; Track hydration state to distinguish between first and subsequent renders.
(defonce ^:dynamic *hydrated* false)

(rum/defc entity-page
  [{:keys [entity k->label]
    :as   opts}]
  ;; TODO: could this transformation be moved to the backend?
  (let [inherited (->> (shared/setify (:dns/inherited entity))
                       (map (comp prefix/qname->kw first k->label))
                       (set))
        opts'     (assoc opts :inherited inherited)]
    [:article
     (entity/entity-header opts')
     (entity/entity-content opts')
     (entity/entity-notes opts')]))

(rum/defc search-page
  [{:keys [languages lemma search-results details?] :as opts}]
  [:article.search
   [:header
    [:h1 (str "\"" lemma "\"")]]
   (if (empty? search-results)
     [:p (i18n/da-en languages
           "Ingen resultater kunne findes for dette lemma."
           "No results could be found for this lemma.")]
     (for [[k entity] search-results]
       (let [{:keys [k->label short-label]} (meta entity)
             k->label' (if (and (not details?) short-label)
                         (assoc k->label
                           k short-label)
                         k->label)]
         (rum/with-key (table/attr-val-table {:languages languages
                                              :k->label  k->label'}
                                             entity)
                       k))))])

(rum/defc markdown-page
  [{:keys [languages content] :as opts}]
  (let [ls     (i18n/select-label languages content)
        lang   (lstr/lang ls)
        md     (str ls)
        hiccup (mdc/md->hiccup md)]
    #?(:cljs (when-let [title (mdc/hiccup->title hiccup)]
               (set! js/document.title title)))
    [:article.document {:lang lang}
     hiccup]))

;; TODO: find better solution? string keys + indirection reduce discoverability
(def pages
  "Mapping from page data metadata :page key to the relevant Rum component."
  {"entity"   entity-page
   "search"   search-page
   "markdown" markdown-page})

(rum/defc page-footer
  [{:keys [languages] :as opts}]
  [:footer
   (i18n/da-en languages
     [:<>
      [:p {:lang "da"}
       [:a {:href  (shared/page-href "privacy")
            :title "Privatlivspolitik"}
        "Privatliv"]
       " · "
       [:a {:href  "https://www.was.digst.dk/wordnet-dk"
            :title "Tilgængelighedserklæring"}
        "Tilgængelighed"]
       " · "
       [:a {:href  (shared/page-href "releases")
            :title "DanNet-versioner"}
        "Versioner"]
       " · "
       [:a {:href  "/dannet/data"
            :title "DanNet-metadata (RDF)"}
        "Metadata"]]
      [:p {:lang "da"}
       "© 2023–2025, "
       [:a {:href "https://cst.ku.dk"}
        "Center for Sprogteknologi"]
       " (" [:abbr {:title "Københavns Universitet"}
             "KU"] ")"
       " & "
       [:a {:href "https://dsl.dk/"}
        "Det Danske Sprog- og Litteraturselskab"]
       "."]]
     [:<>
      [:p {:lang "en"}
       [:a {:href  (shared/page-href "privacy")
            :title "Privacy policy"}
        "Privacy"]
       " · "
       [:a {:href  "https://www.was.digst.dk/wordnet-dk"
            :title "Accessibility statement"}
        "Accessibility"]
       " · "
       [:a {:href  (shared/page-href "releases")
            :title "DanNet releases"}
        "Releases"]
       " · "
       [:a {:href  "/dannet/data"
            :title "DanNet metadata (RDF)"}
        "Metadata"]]
      [:p {:lang "en"}
       "© 2023–2025, "
       [:a {:href "https://cst.ku.dk/english"}
        "Centre for Language Technology"]
       " (" [:abbr {:title "University of Copenhagen"}
             "KU"] ")"
       " & "
       [:a {:lang "da" :href "https://dsl.dk/"}
        "Det Danske Sprog- og Litteraturselskab"]
       "."]])])

(rum/defc language-select
  [languages]
  (let [change-language (fn [e]
                          #?(:cljs (let [v   (-> (.-target e)
                                                 (.-value)
                                                 (not-empty)
                                                 (i18n/lang-prefs))
                                         url "/cookies"]
                                     (swap! shared/state assoc :languages v)
                                     (.then (shared/api url {:method :put
                                                             :body   {:languages v}})
                                            (shared/clear-fetch url)))))]
    [:select.language
     {:title     "Language preference"
      :value     (str (first languages))
      :on-change change-language}
     [:option {:value ""} "\uD83C\uDDFA\uD83C\uDDF3 Other"]
     [:option {:value "en"} "\uD83C\uDDEC\uD83C\uDDE7 English"]
     [:option {:value "da"} "\uD83C\uDDE9\uD83C\uDDF0 Dansk"]]))

(rum/defc loader
  []
  [:div.loader
   [:span.loader__element]
   [:span.loader__element]
   [:span.loader__element]])

(rum/defc help-arrows
  [page {:keys [languages] :as opts}]
  [:section.help-overlay {:aria-hidden true
                          :style       {:opacity (when (not= page "markdown") 0)}}
   [:div.help-overlay__item {:style {:top   6
                                     :color "#df7300"}}
    (i18n/da-en languages
      "start søgning"
      "start search")]
   [:div.help-overlay__item {:style {:bottom 44
                                     :color  "#55f"}}
    (i18n/da-en languages
      "skift sprog"
      "change language")]
   [:div.help-overlay__item {:style {:bottom 6
                                     :color  "#019fa1"}}
    (i18n/da-en languages
      "detaljeniveau"
      "level of detail")]])

(rum/defc page-shell < rum/reactive
  [page {:keys [entity subject languages entities] :as opts}]
  (let [page-component (or (get pages page)
                           (throw (ex-info
                                    (str "No component for page: " page)
                                    opts)))
        state' #?(:clj (assoc @shared/state :languages languages)
                  :cljs (rum/react shared/state))
        languages'     (:languages state')
        comments       {:inference
                        (i18n/da-en languages'
                          "helt eller delvist logisk udledt"
                          "fully or partially logically inferred")
                        :inheritance
                        (i18n/da-en languages'
                          "helt eller delvist  nedarvet fra hypernym"
                          "fully or partially inherited from hypernym")}
        details?       (or (get state' :details?)
                           (get opts :details?))
        entity-label*  (shared/->entity-label-fn details?)
        ;; Rejoin entities with subject (split for performance reasons)
        entities'      (assoc entities subject entity)
        ;; Merge frontend state and backend state into a complete product.
        opts'          (assoc (merge opts state')
                         :comments comments
                         :k->label (update-vals entities' entity-label*))
        [prefix _ _] (shared/parse-rdf-term subject)
        prefix'        (or prefix (some-> entity
                                          :vann/preferredNamespacePrefix
                                          symbol))
        toggle-details (fn [e]
                         (.preventDefault e)
                         (swap! shared/state update :details? not))]
    [:<>
     ;; TODO: make horizontal when screen size/aspect ratio is different?
     [:nav {:class ["prefix" (prefix/prefix->class (if (= page "markdown")
                                                     'dn
                                                     prefix'))]}
      (help-arrows page opts')
      (search/search-form opts')
      [:a.title {:title (i18n/da-en languages
                          "Gå til forsiden"
                          "Go to the front page")
                 :href  (shared/page-href "frontpage")}
       "DanNet"]
      (language-select languages')
      [:button.synset-details {:class    (when details?
                                           "toggled")
                               :title    (if details?
                                           "Show fewer details"
                                           "Show more details")
                               :on-click toggle-details}]]
     [:div#content {:class #?(:clj  ""
                              :cljs (if (and *hydrated*
                                             (not-empty (:fetch opts')))
                                      "fetching"
                                      ""))}
      (loader)
      [:main
       (page-component opts')]
      [:hr]
      (page-footer opts')]]))
