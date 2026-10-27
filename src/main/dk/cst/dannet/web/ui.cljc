(ns dk.cst.dannet.web.ui
  "The core Rum components for displaying a DanNet page."
  (:require [rum.core :as rum]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.web.i18n :as i18n]
            [dk.cst.dannet.web.app :as app]
            #?(:clj  [dk.cst.dannet.web.ui.error :as error]
               :cljs [dk.cst.dannet.web.ui.error :as error :include-macros true])
            [dk.cst.dannet.web.ui.model :as model]
            [dk.cst.dannet.web.ui.page :as page]
            [dk.cst.dannet.web.ui.rdf :as rdf]
            [dk.cst.dannet.web.ui.search :as search]))

(rum/defc page-footer
  [languages]
  [:footer.page-footer
   [:hr]
   [:p {:lang (i18n/da-en languages "da" "en")}
    [:a {:href  (shared/page-href "privacy")
         :title (i18n/da-en languages "Privatlivspolitik" "Privacy policy")}
     (i18n/da-en languages "Privatliv" "Privacy")]
    " · "
    [:a {:href  "https://www.was.digst.dk/wordnet-dk"
         :title (i18n/da-en languages
                  "Tilgængelighedserklæring"
                  "Accessibility statement")}
     (i18n/da-en languages "Tilgængelighed" "Accessibility")]
    " · "
    [:a {:href  (shared/page-href "releases")
         :title (i18n/da-en languages "DanNet-versioner" "DanNet releases")}
     (i18n/da-en languages "Versioner" "Releases")]
    " · "
    [:a {:href  "/dannet/metadata"
         :title (i18n/da-en languages
                  "DanNet-Metadata (RDF)"
                  "DanNet metadata (RDF)")}
     "Metadata"]
    " · "
    [:a {:href  "/dannet/page/downloads"
         :title "Downloads"}
     "Downloads"]]
   [:p {:lang (i18n/da-en languages "da" "en")}
    "© 2023–2026, "
    [:a {:href (i18n/da-en languages
                 "https://cst.ku.dk"
                 "https://cst.ku.dk/english")}
     (i18n/da-en languages
       "Center for Sprogteknologi"
       "Centre for Language Technology")]
    " (" [:abbr {:title (i18n/da-en languages
                          "Københavns Universitet"
                          "University of Copenhagen")}
          (i18n/da-en languages "KU" "UCPH")] ")"
    " & "
    [:a {:href "https://dsl.dk/"}
     (i18n/da-en languages
       "Det Danske Sprog- og Litteraturselskab"
       "The Society for Danish Language and Literature")]
    "."]])

(rum/defc language-select
  [languages]
  ;; Language changes do NOT reload the page; the content re-renders
  ;; reactively via the client state.
  (let [change-language (fn [e]
                          #?(:cljs (let [v (-> (.-target e)
                                               (.-value)
                                               (not-empty)
                                               (i18n/lang-prefs))]
                                     (app/update-cookie! :languages (constantly v))
                                     ;; Keep <html lang> in sync since no page
                                     ;; reload occurs; the SSR'ed attribute is
                                     ;; semantic and drives the CSS rule hiding
                                     ;; redundant language tag superscripts.
                                     (set! (.-lang js/document.documentElement)
                                           (or (first v) "")))))]
    [:select.language.nav-icon
     {:title     (i18n/da-en languages
                   "Indstil sprogpræference"
                   "Set language preference")
      :value     (i18n/da-en languages "da" "en")
      :on-change change-language}
     #_[:option {:value ""} "\uD83C\uDDFA\uD83C\uDDF3 Other"]
     [:option {:value "en"} "\uD83C\uDDEC\uD83C\uDDE7 English"]
     [:option {:value "da"} "\uD83C\uDDE9\uD83C\uDDF0 Dansk"]]))

(defn- change-detail-level!
  [e]
  #?(:cljs (let [v (keyword (.-value (.-target e)))]
             (app/update-cookie! :detail-level (constantly v))
             ;; Keep <html data-detail-level> in sync since no page reload
             ;; occurs; at the :high detail level the CSS keeps all language
             ;; tag superscripts visible regardless of the UI language.
             (.setAttribute js/document.documentElement
                            "data-detail-level" (name v)))))

(rum/defc detail-level-select
  [detail-level languages]
  [:select.detail-level.nav-icon
   {:title     (i18n/da-en languages
                 "Justér berigelse med etiketter"
                 "Adjust label enrichment")
    :value     (name detail-level)
    :on-change change-detail-level!}
   [:option {:value "basic"}
    "Minimal"]
   [:option {:value "normal"}
    (i18n/da-en languages "Dynamisk" "Dynamic")]
   [:option {:value "high"}
    (i18n/da-en languages "Detaljeret" "Detailed")]])

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

(def pages
  "The component of each page type, plus the colour class of its navigation
  bar when the page has one of its own."
  {:entity    {:render page/entity}
   :search    {:render page/search}
   :markdown  {:render page/markdown :nav-class "dannet"}
   :sparql    {:render page/sparql :nav-class "w3c"}
   :metadata  {:render page/metadata :nav-class "meta"}
   :relations {:render page/relations :nav-class "wordnet"}
   :error     {:render page/error}})

(rum/defc page-shell < rum/reactive
  [{:keys [page data prefs]}]
  (let [{:keys [render nav-class]
         :or   {render page/not-found}} (get pages page)
        ;; The server renders with the preferences of the request, the client
        ;; with those in its session, which start out as the same.
        state         #?(:clj  (merge app/default-state prefs)
                         :cljs (rum/react app/session))
        {:keys [entity subject languages detail-level synset? full-screen]
         :as   opts}  (model/render-opts data state)
        full-diagram? (and synset? full-screen)
        [prefix _ _] (rdf/parse-rdf-term subject)
        prefix'       (or prefix (some-> entity
                                         :vann/preferredNamespacePrefix
                                         symbol))]
    [:<>
     ;; TODO: make horizontal when screen size/aspect ratio is different?
     [:nav#main-nav
      {:class ["prefix"
               (if full-diagram?
                 "full-screen"
                 (or nav-class (prefix/prefix->class prefix')))]}
      (search/form opts)
      [:a.sparql-link.nav-icon
       {:title (i18n/da-en languages
                 "Åbn SPARQL-editor"
                 "Open SPARQL editor")
        :href  "/dannet/sparql"}]
      [:a.title {:title (i18n/da-en languages
                          "Gå til forsiden"
                          "Go to the front page")
                 :href  (shared/page-href "frontpage")}
       ;; Wrapped in spans for staggered loading animation (see main.css).
       [:span "D"] [:span "a"] [:span "n"] [:span "N"] [:span "e"] [:span "t"]]
      (language-select languages)
      (detail-level-select detail-level languages)]
     (when-not full-diagram?
       (help-arrows page opts))
     [:div#content {:class (when full-diagram?
                             "full-screen")}
      [:main
       (error/try-render (render opts) (page/error opts))]
      (page-footer languages)]]))
