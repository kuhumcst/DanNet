(ns dk.cst.dannet.web.ui.entity
  "Rendering of the RDF resources in DanNet."
  (:require [flatland.ordered.map :as fop]
            [rum.core :as rum]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.web.i18n :as i18n]
            [dk.cst.dannet.web.section :as section]
            #?(:clj  [dk.cst.dannet.web.ui.error :as error]
               :cljs [dk.cst.dannet.web.ui.error :as error :include-macros true])
            [dk.cst.dannet.web.ui.rdf :as rdf]
            [dk.cst.dannet.web.ui.table :as table]
            [dk.cst.dannet.web.ui.visualization :as viz]))

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

(rum/defc entity-header
  [{:keys [subject languages entity k->label details?]
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
        a-titles      [#voc/lstr "Visit this location directly@en"
                       #voc/lstr "Besøg denne lokation direkte@da"]
        uri-only?     (and (not label) (= local-name rdf-uri))]
    [:header
     [:h1
      (rdf/prefix-badge prefix)
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
          (rdf/break-up-uri rdf-uri)]))]))

(rum/defc entity-notes
  [{:keys [href languages comments inferred inherited supplemented]
    :as   opts}]
  [:section.notes
   (when (not-empty inferred)
     [:p.note.desktop-only [:strong "∴ "] (:inference comments)])
   (when (not-empty inherited)
     [:p.note.desktop-only [:strong "† "] (:inheritance comments)])
   (when (not-empty supplemented)
     [:p.note.desktop-only [:strong "↪ "] (:supplemented comments)])
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
     "JSON-LD"]]])

(rum/defc display-mode-selector
  [title {:keys [languages]
          :as   opts}]
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
                   v]))))]])

;; TODO: this implementation a bit of a hack -- surely there is a better way?
(defn semantic-relations?
  [subentity]
  (not-empty (select-keys shared/synset-rel-theme (keys subentity))))

(rum/defc entity-section
  [title subentity {:keys [languages] :as opts}]
  [:section {:key (or title :no-title)}
   (when title
     [:h2 (str (i18n/select-label languages title))])
   (if (semantic-relations? subentity)
     [:<>
      (display-mode-selector title opts)
      (case (get-in opts [:section title :display :selected])
        "radial" [:<>
                  (error/try-render (viz/expanded-radial subentity opts))
                  [:p.note
                   [:strong "! "]
                   (i18n/da-en languages
                     "Data kan være udeladt; se tabellen for samtlige detaljer."
                     "Data may be omitted; view table for full details.")]]
        (table/attr-val-table opts subentity))]
     (table/attr-val-table opts subentity))])

(rum/defc full-screen-content
  [{:keys [entity]
    :as   opts}]
  (error/try-render
    (viz/expanded-radial
      (ordered-subentity opts section/semantic-rels? entity)
      opts)))

(rum/defc entity-content
  [{:keys [entity]
    :as   opts}]
  (for [[title ks] (section/page-sections entity)]
    (when-let [subentity (ordered-subentity opts ks entity)]
      (rum/with-key
        (entity-section title subentity opts)
        (or title :no-title)))))
