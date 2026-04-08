(ns dk.cst.dannet.web.ui
  "The core Rum components for displaying a DanNet page."
  (:require [rum.core :as rum]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.web.i18n :as i18n]
            [dk.cst.dannet.web.section :as section]
            #?(:clj  [dk.cst.dannet.web.ui.error :as error]
               :cljs [dk.cst.dannet.web.ui.error :as error :include-macros true])
            [dk.cst.dannet.web.ui.page :as page]
            [dk.cst.dannet.web.ui.search :as search]))

;; Track hydration state to distinguish between first and subsequent renders.
;; The initial render will happen server-side, whilst subsequent re-renders all
;; happen locally in the browser.
(defonce ^:dynamic *hydrated* false)

(rum/defc page-footer
  [languages]
  [:footer.page-footer
   [:hr]
   (i18n/da-en languages
     [:<>
      [:p {:lang "da"}
       [:a {:href   (shared/page-href "privacy")
            :titlex "Privatlivspolitik"}
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
       [:a {:href  "/dannet/metadata"
            :title "DanNet-Metadata (RDF)"}
        "Metadata"]
       " · "
       [:a {:href  "/dannet/page/downloads"
            :title "Downloads"}
        "Downloads"]]
      [:p {:lang "da"}
       "© 2023–2026, "
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
       [:a {:href  "/dannet/metadata"
            :title "DanNet metadata (RDF)"}
        "Metadata"]
       " · "
       [:a {:href  "/dannet/page/downloads"
            :title "Downloads"}
        "Downloads"]]
      [:p {:lang "en"}
       "© 2023–2026, "
       [:a {:href "https://cst.ku.dk/english"}
        "Centre for Language Technology"]
       " (" [:abbr {:title "University of Copenhagen"}
             "UCPH"] ")"
       " & "
       [:a {:lang "da" :href "https://dsl.dk/"}
        "Det Danske Sprog- og Litteraturselskab"]
       "."]])])

(rum/defc language-select
  [languages]
  (let [change-language (fn [e]
                          #?(:cljs (let [v (-> (.-target e)
                                               (.-value)
                                               (not-empty)
                                               (i18n/lang-prefs))]
                                     (shared/update-cookie! :languages (constantly v)))))]
    [:select.language.nav-icon
     {:title     (i18n/da-en languages
                   "Indstil sprogpræference"
                   "Set language preference")
      :value     (str (first languages))
      :on-change change-language}
     [:option {:value ""} "\uD83C\uDDFA\uD83C\uDDF3 Other"]
     [:option {:value "en"} "\uD83C\uDDEC\uD83C\uDDE7 English"]
     [:option {:value "da"} "\uD83C\uDDE9\uD83C\uDDF0 Dansk"]]))


(rum/defc help-arrows
  [page {:keys [languages] :as opts}]
  [:section.help-overlay {:aria-hidden true}
   [:div.help-overlay__item {:style {:top   10
                                     :color "#387111"}}
    (i18n/da-en languages
      "find ord"
      "find words")]
   [:div.help-overlay__item {:style {:top   52
                                     :color "#55f"}}
    (i18n/da-en languages
      "skriv SPARQL"
      "write SPARQL")]
   [:div.help-overlay__item {:style {:bottom "calc(50% - 12px)"
                                     :color  "#901a1e"}}
    (i18n/da-en languages
      "til forsiden"
      "to the frontpage")]
   [:div.help-overlay__item {:style {:bottom 52
                                     :color  "#df7300"}}
    (i18n/da-en languages
      "skift sprog"
      "change language")]
   [:div.help-overlay__item {:style {:bottom 10
                                     :color  "#019fa1"}}
    (i18n/da-en languages
      "detaljeniveau"
      "level of detail")]])

(rum/defc page-shell < rum/reactive
  [page {:keys [entity subject languages entities full-screen] :as opts}]
  ;; TODO: better solution? string keys + indirection reduce discoverability
  (let [page-component (get {"entity"   page/entity
                             "search"   page/search
                             "markdown" page/markdown
                             "metadata" page/metadata
                             "sparql"   page/sparql
                             "error"    page/error}
                            page
                            page/not-found)
        ;; The backend also needs access to user-specific state to be able to
        ;; subsequently hydrate the HTML on the client-side with no errors.
        state' #?(:clj (assoc @shared/state
                         :languages languages
                         :full-screen full-screen)
                  :cljs (rum/react shared/state))
        languages'     (:languages state')
        comments       {:inference
                        (i18n/da-en languages'
                          "indeholder logisk afledt data"
                          "contains logically inferred data")
                        :inheritance
                        (i18n/da-en languages'
                          "helt eller delvist nedarvet fra hypernym"
                          "fully or partially inherited from hypernym")
                        :supplemented
                        (i18n/da-en languages'
                          "suppleret fra andre ressourcer"
                          "supplemented from other resources")}
        details?       (or (get state' :details?)
                           (get opts :details?))
        entity-label*  (shared/->entity-label-fn details?)
        ;; Rejoin entities with subject (split for performance reasons)
        entities'      (assoc entities subject entity)
        synset?        (some section/semantic-rels? entity)
        full-diagram?  (and synset? (:full-screen state'))
        ;; Merge frontend state and backend state into a complete product.
        opts'          (assoc (merge opts state')
                         :synset? synset?
                         :comments comments
                         ;; Entity pages build k->label from expanded entity
                         ;; metadata; other pages (e.g. SPARQL) may supply their
                         ;; own k->label via the interceptor chain.
                         :k->label (if entities
                                     (update-vals entities' entity-label*)
                                     (:k->label opts)))
        [prefix _ _] (shared/parse-rdf-term subject)
        prefix'        (or prefix (some-> entity
                                          :vann/preferredNamespacePrefix
                                          symbol))
        toggle-details (fn [e]
                         (.preventDefault e)
                         (swap! shared/state update :details? not))]
    [:<>
     ;; TODO: make horizontal when screen size/aspect ratio is different?
     [:nav#main-nav
      {:class ["prefix"
               (if full-diagram?
                 "full-screen"
                 (cond
                   (= page "markdown") "dannet"
                   (= page "sparql") "w3c"
                   (= page "metadata") "meta"
                   :else (prefix/prefix->class prefix')))]}
      (search/form opts')
      [:a.sparql-link.nav-icon
       {:title (i18n/da-en languages'
                 "Åbn SPARQL-editor"
                 "Open SPARQL editor")
        :href  "/dannet/sparql"}]
      [:a.title {:title (i18n/da-en languages'
                          "Gå til forsiden"
                          "Go to the front page")
                 :href  (shared/page-href "frontpage")}
       ;; Wrapped in spans for staggered loading animation (see main.css).
       [:span "D"] [:span "a"] [:span "n"] [:span "N"] [:span "e"] [:span "t"]]
      (language-select languages')
      [:button.synset-details.nav-icon {:class    (when details?
                                                    "toggled")
                                        :title    (if details?
                                                    (i18n/da-en languages'
                                                      "Vis færre detaljer"
                                                      "Show fewer details")
                                                    (i18n/da-en languages'
                                                      "Vis flere detaljer"
                                                      "Show more details"))
                                        :on-click toggle-details}]]
     (when-not full-diagram?
       (help-arrows page opts'))
     [:div#content {:class [(when full-diagram?
                              "full-screen")
                            #?(:clj  ""
                               :cljs (if (and *hydrated*
                                              (not-empty (:fetch state')))
                                       "fetching"
                                       ""))]}
      [:main
       (error/try-render (page-component opts') (page/error opts'))]
      (page-footer languages')]]))
