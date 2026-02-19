(ns dk.cst.dannet.web.ui.page
  "The different page types rendered in the DanNet UI."
  (:require [rum.core :as rum]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.web.i18n :as i18n]
            [ont-app.vocabulary.lstr :as lstr]
            [dk.cst.dannet.web.ui.entity :as entity]
            [dk.cst.dannet.web.ui.table :as table]
            [dk.cst.dannet.web.ui.catalog :as catalog]
            [dk.cst.dannet.web.ui.markdown :as mdc]))

;; TODO: superfluous DN:A4-ark http://localhost:3456/dannet/data/synset-48300
;; TODO: empty synset http://localhost:3456/dannet/data/synset-47272
;; TODO: equivalent class empty http://localhost:3456/dannet/external/semowl/InformationEntity
;; TODO: empty definition http://0.0.0.0:3456/dannet/data/synset-42955
(rum/defc entity
  [{:keys [entity k->label synset? full-screen]
    :as   opts}]
  ;; TODO: could this transformation be moved to the backend?
  (let [inherited (->> (shared/setify (:dns/inherited entity))
                       (map (comp prefix/qname->kw first k->label))
                       (set))
        opts'     (assoc opts :inherited inherited)]
    (if (and synset? full-screen)
      [:article
       (entity/full-screen-content opts')]
      [:article
       (entity/entity-header opts')
       (entity/entity-content opts')
       (entity/entity-notes opts')])))

(rum/defc search
  [{:keys [languages lemma search-results details?] :as opts}]
  [:article.search
   [:header.page-header
    [:h1 (i18n/da-en languages "Søgeresultater" "Search results")]
    [:p.subheading
     (i18n/da-en languages
       (str "Fandt "(count search-results) " synsets der matcher \"" lemma "\"")
       (str "Found " (count search-results) " synsets matching \"" lemma "\""))]]
   (when-not (empty? search-results)
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

(rum/defc markdown
  [{:keys [languages content] :as opts}]
  (let [ls     (i18n/select-label languages content)
        lang   (lstr/lang ls)
        md     (str ls)
        hiccup (mdc/md->hiccup md)]
    #?(:cljs (when-let [title (mdc/hiccup->title hiccup)]
               (set! js/document.title title)))
    [:article.document {:lang lang}
     hiccup]))

(rum/defc metadata
  "Display the catalog of schemas and datasets referenced in the graph."
  [{:keys [languages catalog] :as opts}]
  (let [k->label       (catalog/k->label catalog)
        ordered-groups (catalog/prepare-groups catalog opts)]
    [:article.metadata
     [:header.page-header
      [:h1 "Metadata"]]
     [:p.subheading (i18n/da-en languages
                      "Dette er de primære skemaer og datasæt der bruges i DanNet."
                      "These are the core schemas and datasets used in DanNet.")]
     (if (empty? ordered-groups)
       [:p (i18n/da-en languages
             "Ingen ressourcer fundet."
             "No resources found.")]
       (for [[group-key title desc entries] ordered-groups]
         [:section {:key (or group-key "other")}
          [:h2 (if (string? title)
                 title
                 (i18n/da-en languages (:da title) (:en title)))]
          [:p.subheading (i18n/da-en languages (:da desc) (:en desc))]
          (catalog/table opts k->label entries)]))]))

(rum/defc error
  "User-friendly error page shown when a page component fails to render."
  [{:keys [languages] :as opts}]
  (i18n/da-en languages
    [:article.document {:lang "da"}
     [:h1 "Noget gik galt"]
     [:p "Der opstod en fejl under indlæsning af denne side."]
     [:ul
      [:li [:a {:href "javascript:location.reload()"} "Genindlæs siden"]
       " – det kan løse midlertidige problemer."]
      [:li [:a {:href (shared/page-href "frontpage")} "Gå til forsiden"]
       " – for at finde det du søger på en anden måde."]
      [:li [:a {:href "mailto:simongray@hum.ku.dk"} "Rapportér fejlen"]
       " – hvis problemet fortsætter."]]]
    [:article.document {:lang "en"}
     [:h1 "Something went wrong"]
     [:p "An error occurred while loading this page."]
     [:ul
      [:li [:a {:href "javascript:location.reload()"} "Reload the page"]
       " – this may fix temporary issues."]
      [:li [:a {:href (shared/page-href "frontpage")} "Go to the front page"]
       " – to find what you're looking for another way."]
      [:li [:a {:href "mailto:simongray@hum.ku.dk"} "Report the issue"]
       " – if the problem persists."]]]))

(rum/defc not-found
  "User-friendly 404 page shown when a page type is unknown or missing."
  [{:keys [languages] :as opts}]
  (i18n/da-en languages
    [:article.document {:lang "da"}
     [:h1 "Siden blev ikke fundet"]
     [:p "Den side, du leder efter, findes ikke eller er blevet flyttet."]
     [:ul
      [:li [:a {:href (shared/page-href "frontpage")} "Gå til forsiden"]
       " – for at finde det du søger."]
      [:li [:a {:href "javascript:history.back()"} "Gå tilbage"]
       " – til den forrige side."]]]
    [:article.document {:lang "en"}
     [:h1 "Page not found"]
     [:p "The page you're looking for doesn't exist or has been moved."]
     [:ul
      [:li [:a {:href (shared/page-href "frontpage")} "Go to the front page"]
       " – to find what you're looking for."]
      [:li [:a {:href "javascript:history.back()"} "Go back"]
       " – to the previous page."]]]))
