(ns dk.cst.dannet.web.ui.page
  "The different page types rendered in the DanNet UI."
  (:require [rum.core :as rum]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.web.i18n :as i18n]
            [dk.cst.dannet.web.ui.search :as search]
            [dk.cst.dannet.web.ui.entity :as entity]
            [dk.cst.dannet.web.ui.catalog :as catalog]
            [dk.cst.dannet.web.ui.rdf :as rdf]
            [dk.cst.dannet.web.ui.relations :as rels]
            [dk.cst.dannet.web.ui.sparql :as sparql]
            [dk.cst.dannet.web.ui.error :as error]))

;; TODO: superfluous DN:A4-ark http://localhost:3456/dannet/data/synset-48300
;; TODO: empty synset http://localhost:3456/dannet/data/synset-47272
;; TODO: equivalent class empty http://localhost:3456/dannet/external/semowl/InformationEntity
;; TODO: empty definition http://0.0.0.0:3456/dannet/data/synset-42955
(rum/defc entity
  [{:keys [entity synset? full-screen subject]
    :as   opts}]
  (let [[_ _ rdf-uri] (rdf/parse-rdf-term subject)
        typeof (prefix/rdfa-val (:rdf/type entity))]
    (if (and synset? full-screen)
      [:article.rdf-resource
       (entity/full-screen-content opts)]
      [:article.rdf-resource (cond-> {:about rdf-uri}
                               typeof (assoc :typeof typeof))
       (entity/entity-header opts)
       (entity/entity-content opts)
       (entity/entity-notes opts)])))

(rum/defc search
  [{:keys [languages lemma search-results] :as opts}]
  [:article.search
   [:header.page-header
    [:h1 (i18n/da-en languages "Søgning" "Search results")]
    [:p.subheading
     (i18n/da-en languages
       (str "Fandt " (count search-results) " synsets der matcher \"" lemma "\"")
       (str "Found " (count search-results) " synsets matching \"" lemma "\""))]]
   (when-not (empty? search-results)
     [:dl.search-results
      (for [[k entity] search-results]
        (rum/with-key (search/result k entity opts) k))])
   [:p.note
    [:strong "? "]
    (i18n/da-en languages
      [:<> "overvej at bruge " [:a {:href prefix/sparql-path} "SPARQL"] " til avancerede søgninger"]
      [:<> "consider using " [:a {:href prefix/sparql-path} "SPARQL"] " for advanced queries"])]])

(rum/defc markdown
  [{:keys [languages content] :as opts}]
  (let [lang   (first (filter content languages))
        hiccup (get content lang)]
    [:article.document {:lang lang}
     hiccup]))

(defn- grouped-tables
  "An article with a table per group in `groups`, each a [key title desc
  entries] tuple. The `row` function renders an entry as a table row, and the
  `text` holds the :heading, :intro, :none (for no groups) and the :name-label
  of the second column."
  [{:keys [languages]} {:keys [heading intro none name-label]} groups row]
  [:article.metadata
   [:header.page-header
    [:h1 heading]]
   [:p.subheading intro]
   (if (empty? groups)
     [:p none]
     (for [[group-key title desc entries] groups]
       [:section {:key (str (or group-key "other"))}
        [:h2 (if (string? title)
               title
               (i18n/da-en languages (:da title) (:en title)))]
        [:p.subheading (i18n/da-en languages (:da desc) (:en desc))]
        [:table.attr-val
         [:colgroup
          [:col {:aria-label (i18n/da-en languages "RDF-præfix" "RDF prefix")}]
          [:col {:aria-label name-label}]
          [:col {:aria-label (i18n/da-en languages "Beskrivelse" "Description")}]]
         [:tbody (map row entries)]]]))])

(rum/defc metadata
  "Display the catalog of schemas and datasets referenced in the graph."
  [{:keys [languages catalog] :as opts}]
  (let [k->label (catalog/k->label catalog)]
    (grouped-tables
      opts
      {:heading    "Metadata"
       :intro      (i18n/da-en languages
                     [:<> "Dette er de primære skemaer og datasæt der bruges i DanNet. Se også de "
                      [:a {:href prefix/relations-path} "synset-relationer"]
                      " der er i brug."]
                     [:<> "These are the core schemas and datasets used in DanNet. See also the "
                      [:a {:href prefix/relations-path} "synset relations"]
                      " in use."])
       :none       (i18n/da-en languages
                     "Ingen ressourcer fundet."
                     "No resources found.")
       :name-label (i18n/da-en languages "Navn (nøgle)" "Name (key)")}
      (catalog/prepare-groups catalog opts)
      #(catalog/row opts k->label %))))

(rum/defc relations
  "Display the synset relations in use in DanNet, divided into groups."
  [{:keys [languages relations detail-level] :as opts}]
  (let [k->label (rels/k->label relations detail-level)]
    (grouped-tables
      opts
      {:heading    (i18n/da-en languages "Synset-relationer" "Synset relations")
       :intro      (i18n/da-en languages
                     [:<> "Synset-relationerne i brug i DanNet ("
                      [:a {:href prefix/metadata-path} "se skemaer"]
                      "); relevant når man skriver "
                      [:a {:href prefix/sparql-path} "SPARQL"] "."]
                     [:<> "The synset relations in use in DanNet ("
                      [:a {:href prefix/metadata-path} "view schemas"]
                      "); relevant when writing "
                      [:a {:href prefix/sparql-path} "SPARQL"] "."])
       :none       (i18n/da-en languages
                     "Ingen relationer fundet."
                     "No relations found.")
       :name-label "Relation"}
      (rels/prepare-groups relations opts)
      #(rels/row opts k->label %))))

(rum/defc sparql
  "SPARQL query editor and results page."
  [{:keys [languages] :as opts}]
  [:article.sparql
   [:header.page-header
    [:h1 (i18n/da-en languages
           "SPARQL-editor"
           "SPARQL editor")]]
   [:p.subheading
    (i18n/da-en languages
      "Søg i DanNet-databasen ("
      "Search the DanNet database (")
    [:a {:href  "/dannet/page/sparql"
         :title (i18n/da-en languages
                  "SPARQL-guide"
                  "SPARQL guide")}
     (i18n/da-en languages
       "læs guide"
       "read tutorial")] ")"
    (i18n/da-en languages
      [:<> "; se også oversigten over " [:a {:href prefix/relations-path} "synset-relationer"] "."]
      [:<> "; see also the overview of " [:a {:href prefix/relations-path} "synset relations"] "."])]
   (sparql/editor opts)
   (sparql/output opts)])

(rum/defc error
  "User-friendly error page shown when a page component fails to render.

  When an :anomaly is present in `opts`, it is rendered below the friendly
  links via `error/anomaly-fallback` to give specific details."
  [{:keys [languages anomaly] :as opts}]
  [:article.document {:lang (i18n/da-en languages "da" "en")}
   [:h1 (i18n/da-en languages "Noget gik galt" "Something went wrong")]
   (if anomaly
     (error/anomaly-fallback anomaly languages)
     [:p (i18n/da-en languages
           "Der opstod en fejl under indlæsning af denne side."
           "An error occurred while loading this page.")])
   [:ul
    [:li [:a {:href "javascript:location.reload()"}
          (i18n/da-en languages "Genindlæs siden" "Reload the page")]
     (i18n/da-en languages
       " – det kan løse midlertidige problemer."
       " – this may fix temporary issues.")]
    [:li [:a {:href (shared/page-href "frontpage")}
          (i18n/da-en languages "Gå til forsiden" "Go to the front page")]
     (i18n/da-en languages
       " – for at finde det du søger på en anden måde."
       " – to find what you're looking for another way.")]
    [:li [:a {:href "mailto:simongray@hum.ku.dk"}
          (i18n/da-en languages "Rapportér fejlen" "Report the issue")]
     (i18n/da-en languages
       " – hvis problemet fortsætter."
       " – if the problem persists.")]]])

(rum/defc not-found
  "User-friendly 404 page shown when a page type is unknown or missing."
  [{:keys [languages] :as opts}]
  [:article.document {:lang (i18n/da-en languages "da" "en")}
   [:h1 (i18n/da-en languages "Siden blev ikke fundet" "Page not found")]
   [:p (i18n/da-en languages
         "Den side, du leder efter, findes ikke eller er blevet flyttet."
         "The page you're looking for doesn't exist or has been moved.")]
   [:ul
    [:li [:a {:href (shared/page-href "frontpage")}
          (i18n/da-en languages "Gå til forsiden" "Go to the front page")]
     (i18n/da-en languages
       " – for at finde det du søger."
       " – to find what you're looking for.")]
    [:li [:a {:href "javascript:history.back()"}
          (i18n/da-en languages "Gå tilbage" "Go back")]
     (i18n/da-en languages
       " – til den forrige side."
       " – to the previous page.")]]])
