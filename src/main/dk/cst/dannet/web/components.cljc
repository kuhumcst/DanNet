(ns dk.cst.dannet.web.components
  "Shared frontend/backend Rum components."
  (:require [clojure.string :as str]
            [flatland.ordered.map :as fop]
            [rum.core :as rum]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.web.i18n :as i18n]
            [dk.cst.dannet.web.section :as section]
            [ont-app.vocabulary.lstr :as lstr]
            [nextjournal.markdown :as md]
            [nextjournal.markdown.transform :as md.transform]
            [dk.cst.dannet.web.components.rdf :as rdf]
            [dk.cst.dannet.web.components.search :as search]
            [dk.cst.dannet.web.components.table :as table]
            #?(:cljs [dk.cst.dannet.web.components.visualization :as viz])
            #?(:clj [better-cond.core :refer [cond]])
            #?(:cljs [dk.cst.aria.combobox :as combobox])
            #?(:cljs [reagent.cookies :as cookie])
            #?(:cljs [lambdaisland.uri :as uri])
            #?(:cljs [reitit.frontend.history :as rfh])
            #?(:cljs [reitit.frontend.easy :as rfe]))
  #?(:cljs (:require-macros [better-cond.core :refer [cond]]))
  (:refer-clojure :exclude [cond])
  #?(:clj (:import [clojure.lang Named])))

;; TODO: superfluous DN:A4-ark http://localhost:3456/dannet/data/synset-48300
;; TODO: empty synset http://localhost:3456/dannet/data/synset-47272
;; TODO: equivalent class empty http://localhost:3456/dannet/external/semowl/InformationEntity
;; TODO: empty definition http://0.0.0.0:3456/dannet/data/synset-42955

;; Track hydration state to distinguish between first and subsequent renders.
(defonce ^:dynamic *hydrated* false)

(defn- ordered-subentity
  "Select a subentity from `entity` based on `ks` (may be a predicate too) and
  order it according to the labels of the preferred languages."
  [opts ks entity]
  (not-empty
    (into (fop/ordered-map)
          (if (coll? ks)
            (->> (for [k ks]
                   (when-let [v (k entity)]
                     [k v]))
                 (remove nil?))
            (sort-by (shared/label-sortkey-fn opts)
                     (filter ks entity))))))

(defn- elem-classes
  [el]
  (set (str/split (.getAttribute el "class") #" ")))

(defn- apply-classes
  [el classes]
  (.setAttribute el "class" (str/join " " classes)))

(def radial-tree-selector
  ".radial-tree-nodes [fill],
  .radial-tree-links [stroke],
  .radial-tree-labels [data-theme]")

(defn- get-diagram
  [e]
  (.-previousSibling (.-parentElement (.-parentElement (.-parentElement (.-target e))))))

;; Inspiration for checkboxes: https://www.w3schools.com/howto/tryit.asp?filename=tryhow_css_custom_checkbox
(rum/defcs radial-tree-legend < (rum/local nil ::selected)
  [state {:keys [languages k->label] :as opts} subentity]
  (let [selected (::selected state)]
    [:ul.radial-tree-legend
     (for [k (keys subentity)]
       (when-let [theme (get shared/synset-rel-theme k)]
         (let [label        (i18n/select-label languages (k->label k))
               is-selected? (= @selected theme)]
           [:li {:key k}
            [:label {:lang (i18n/lang label)} (str label)
             [:input {:type      "radio"
                      :name      "radial-tree-filter"
                      :value     theme
                      :checked   is-selected?
                      :read-only true
                      :on-click  (fn [e]
                                   (let [new-selection (if is-selected? nil theme)
                                         diagram       (get-diagram e)]
                                     (reset! selected new-selection)
                                     (doseq [el (.querySelectorAll diagram radial-tree-selector)]
                                       (let [classes (elem-classes el)
                                             show?   (or (nil? new-selection)
                                                         (= new-selection (.getAttribute el "stroke"))
                                                         (= new-selection (.getAttribute el "fill"))
                                                         (= new-selection (.getAttribute el "data-theme"))
                                                         (get classes "radial-item__subject"))]
                                         (if show?
                                           (apply-classes el (disj classes "radial-item__de-emphasized"))
                                           (apply-classes el (conj classes "radial-item__de-emphasized")))))))}]
             [:span {:class "radial-tree-legend__bullet"
                     :style {:background theme}}]]])))]))

(rum/defc entity-page
  [{:keys [subject href languages comments inferred entity k->label details?]
    :as   opts}]
  (let [[prefix local-name rdf-uri] (shared/parse-rdf-term subject)
        ;; Bypass the default use of dns:shortLabel in case the user wants the
        ;; detailed label (usually rsfs:label).
        label-key     (if (and details? (get (:rdf/type entity)
                                             :ontolex/LexicalConcept))
                        :rdfs/label
                        (shared/find-label-key entity))
        select-label* (partial i18n/select-label languages)
        label         (select-label* (k->label subject))
        label-lang    (i18n/lang label)
        a-titles      [#voc/lstr"Visit this location directly@en"
                       #voc/lstr"Besøg denne lokation direkte@da"]
        inherited     (->> (shared/setify (:dns/inherited entity))
                           (map (comp prefix/qname->kw first k->label))
                           (set))
        uri-only?     (and (not label) (= local-name rdf-uri))]
    [:article
     [:header
      [:h1
       (rdf/prefix-elem prefix)
       [:span {:title (if label
                        (prefix/kw->qname label-key)
                        (if uri-only?
                          rdf-uri
                          (str prefix ":" local-name)))
               :key   subject
               :lang  label-lang}
        (if label
          (rdf/transform-val label opts)
          (if uri-only?
            [:a.rdf-uri {:href  rdf-uri
                         :title (i18n/select-label languages a-titles)
                         :key   rdf-uri}
             (rdf/break-up-uri rdf-uri)]
            local-name))]
       (when label-lang
         [:sup label-lang])]
      (when-not uri-only?
        (if-let [uri-prefix (and prefix (prefix/prefix->uri prefix))]
          [:a.rdf-uri {:href  rdf-uri
                       :title (i18n/select-label languages a-titles)
                       :label (i18n/select-label languages a-titles)}
           [:span.rdf-uri__prefix {:key uri-prefix}
            (rdf/break-up-uri uri-prefix)]
           [:span.rdf-uri__name {:key local-name}
            (rdf/break-up-uri local-name)]]
          [:a.rdf-uri {:href  rdf-uri
                       :title (i18n/select-label languages a-titles)
                       :key   rdf-uri}
           (rdf/break-up-uri rdf-uri)]))]
     (for [[title ks] (section/page-sections entity)]
       (when-let [subentity (ordered-subentity opts ks entity)]
         [:section {:key (or title :no-title)}
          (when title
            [:h2 (str (i18n/select-label languages title))])
          (if (not-empty (select-keys shared/synset-rel-theme (keys subentity)))
            [:<>
             [:p.subheading (i18n/da-en languages
                              "Vis som "
                              "Display as ")
              [:select {:value     (get-in opts [:section title :display :selected])
                        :on-change (fn [e]
                                     (swap! shared/state assoc-in
                                            [:section title :display :selected]
                                            (.-value (.-target e))))}
               (let [m (get-in shared/ui [:section title :display :options])]
                 (->> (sort-by second (if (= "da" (first languages))
                                        (update-vals m first)
                                        (update-vals m second)))
                      (map (fn [[k v]]
                             [:option {:key   k
                                       :value k}
                              v]))))]]
             (case (get-in opts [:section title :display :selected])
               "radial" [:<>
                         [:div.radial-tree {:key (str (hash subentity))}
                          #?(:cljs (viz/radial-tree
                                     (assoc opts :label label)
                                     subentity)
                             :clj  [:div.radial-tree-diagram])
                          (radial-tree-legend opts subentity)]
                         [:p.note
                          [:strong "! "]
                          (i18n/da-en languages
                            "Data kan være udeladt; se tabellen for samtlige detaljer."
                            "Data may be omitted; view table for full details.")]]
               (table/attr-val-table (assoc opts :inherited inherited) subentity))]
            (table/attr-val-table (assoc opts :inherited inherited) subentity))]))
     [:section.notes
      (when (not-empty inferred)
        [:p.note.desktop-only [:strong "∴ "] (:inference comments)])
      (when (not-empty inherited)
        [:p.note.desktop-only [:strong "† "] (:inheritance comments)])
      [:p.note
       [:strong "↓ "]
       (i18n/da-en languages
         "hent data som: "
         "download data as: ")
       ;; TODO: some weird href diff in frontend/backend here
       ;;       http://localhost:3456/dannet/external?subject=%3Chttp%3A%2F%2Fwww.w3.org%2F2000%2F01%2Frdf-schema%23%3E
       [:a {:href     (str href (if (re-find #"\?" href) "&" "?")
                           "format=turtle")
            :type     "text/turtle"
            :title    "Turtle"
            :download true}
        "Turtle"]
       ", "
       [:a {:href     (str href (if (re-find #"\?" href) "&" "?")
                           "format=json-ld")
            :type     "application/ld+json"
            :title    "JSON-LD"
            :download true}
        "JSON-LD"]]]]))

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

(def md->hiccup
  (memoize
    (partial md/->hiccup
             (assoc md.transform/default-hiccup-renderers
               ;; Clerk likes to ignore alt text and produce <figure> tags,
               ;; so we need to intercept the regular image rendering to produce
               ;; accessible images.
               :image (fn [{:as ctx ::keys [parent]}
                           {:as node :keys [attrs content]}]
                        (let [alt (-> (filter (comp #{:text} :type) content)
                                      (first)
                                      (get :text))]
                          [:img (assoc attrs :alt alt)]))))))

(defn hiccup->title*
  "Find the title string located in the first :h1 element in `hiccup`."
  [hiccup]
  (->> (tree-seq vector? rest hiccup)
       (reduce (fn [_ x]
                 (when (= :h1 (first x))
                   (let [node (last x)]
                     (reduced (if (= :img (first node))
                                (:alt (second node))
                                node)))))
               nil)))

(def hiccup->title
  (memoize hiccup->title*))

(rum/defc markdown-page < rum/reactive
  [{:keys [languages content] :as opts}]
  (let [ls     (i18n/select-label languages content)
        lang   (lstr/lang ls)
        md     (str ls)
        hiccup (md->hiccup md)]
    #?(:cljs (when-let [title (hiccup->title hiccup)]
               (set! js/document.title title)))
    [:article.document {:lang lang}
     (md->hiccup md)]))

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

(rum/defc language-select < rum/reactive
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

(comment
  (hiccup->title* (md/->hiccup (slurp "pages/about-da.md")))
  (hiccup->title* nil)
  #_.)
