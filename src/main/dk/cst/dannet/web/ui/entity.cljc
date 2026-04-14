(ns dk.cst.dannet.web.ui.entity
  "Rendering of the RDF resources in DanNet."
  (:require [clojure.string :as str]
            [flatland.ordered.map :as fop]
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
  [{:keys [subject languages entity k->label detail-level]
    :as   opts}]
  (let [[prefix local-name rdf-uri] (shared/parse-rdf-term subject)
        ;; Bypass the default use of dns:shortLabel in case the user wants the
        ;; detailed label (usually rdfs:label).
        label-key     (if (and (= detail-level :high)
                               (get (:rdf/type entity)
                                    :ontolex/LexicalConcept))
                        :rdfs/label
                        (shared/find-label-key entity))
        select-label* (partial i18n/select-label languages)
        label         (select-label* (k->label subject))
        label-lang    (i18n/lang label)
        a-titles      [#voc/lstr "Visit this location directly@en"
                       #voc/lstr "Besøg denne lokation direkte@da"]
        uri-only?     (and (not label) (= local-name rdf-uri))]
    [:header.page-header
     [:h1
      (rdf/prefix-badge prefix)
      [:span (cond-> {:title (if label
                               (prefix/kw->qname label-key)
                               (if uri-only?
                                 rdf-uri
                                 (str prefix ":" local-name)))
                      :key   subject
                      :lang  label-lang}
               label (assoc :property (prefix/kw->qname label-key)))
       (if label
         (rdf/transform-val label opts)
         (if uri-only?
           [:a.rdf-uri {:href  rdf-uri
                        :title (str (select-label* a-titles))
                        :key   rdf-uri}
            (rdf/break-up-uri rdf-uri)]
           local-name))]
      (when label-lang
        [:sup label-lang])]
     (when-not uri-only?
       (if-let [uri-prefix (and prefix (prefix/prefix->uri prefix))]
         [:a.rdf-uri {:href  rdf-uri
                      :title (str (select-label* a-titles))}
          [:span.rdf-uri__prefix {:key uri-prefix}
           (rdf/break-up-uri uri-prefix)]
          [:span.rdf-uri__name {:key local-name}
           (rdf/break-up-uri local-name)]]
         [:a.rdf-uri {:href  rdf-uri
                      :title (str (select-label* a-titles))
                      :key   rdf-uri}
          (rdf/break-up-uri rdf-uri)]))]))

(rum/defc entity-notes
  [{:keys [href languages comments inferred inherited supplemented]
    :as   opts}]
  [:aside.notes
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

;; TODO: reconsider the need for this
(def ui-descriptions
  "UI descriptions which shouldn't be configurable by the client."
  {:section {section/semantic-title
             {:display {:options {"table"  ["\uD83D\uDDC4\uFE0F tabel"
                                            "\uD83D\uDDC4\uFE0F table"]
                                  "radial" ["\uD83D\uDCCA diagram"
                                            "\uD83D\uDCCA diagram"]}}}}})

(rum/defc display-mode-selector
  [title {:keys [languages]
          :as   opts}]
  [:label.subheading (i18n/da-en languages
                       "Vis som "
                       "Display as ")
   [:select {:value     (get-in opts [:section title :display :selected])
             :on-change (fn [e]
                          (swap! shared/state assoc-in
                                 [:section title :display :selected]
                                 (.-value (.-target e))))}
    (let [m (get-in ui-descriptions [:section title :display :options])]
      (->> (sort-by second (if (= "da" (first languages))
                             (update-vals m first)
                             (update-vals m second)))
           (map (fn [[k v]]
                  [:option {:key   k
                            :value k}
                   v]))))]])

(defn da-en-pos
  [languages pos]
  (get (i18n/da-en languages
         shared/pos-abbr-da
         shared/pos-abbr-en)
       pos))

(rum/defc synset-summary
  [{:keys [wn/lexfile dns/ontologicalType] :as subentity}
   {:keys [languages entity] :as opts}]
  ;; The Danish and English WordNets differ in how they represent part-of-speech
  ;; where the Danish WordNet takes the standard OntoLex approach, while the
  ;; OEWN uses a custom relations
  (let [pos      (da-en-pos languages (or (some->> lexfile shared/lexfile->pos)
                                          (some->> entity :wn/partOfSpeech name)))
        ;; OEWN uses dc:subject despite the GWA defining wn:lexfile explicitly!
        lexfile' (or lexfile (:dc/subject entity))]
    [:ul.synset-summary
     [:li
      (when pos
        [:abbr.pos-label (cond-> {:title (if lexfile'
                                           (str lexfile' " (wn:lexfile)")
                                           "wn:lexfile")}
                           lexfile' (assoc :property "wn:lexfile"
                                           :content (str lexfile')))
         pos])
      (if-let [definition (:skos/definition subentity)]
        (let [definition' (if (set? definition)
                            (->> (sort (map str definition))
                                 (str/join "; "))
                            definition)]
          [:span {:property "skos:definition"
                  :title    "skos:definition"}
           (error/try-render
             (rdf/transform-val definition' opts)
             (str definition'))])
        (when-let [definition (:wn/definition subentity)]
          [:span {:property "wn:definition"
                  :title    "wn:definition"}
           (error/try-render
             (rdf/blank-node opts (meta definition)))]))]
     (when ontologicalType
       [:li {:property "dns:ontologicalType"
             :title    "dns:ontologicalType"}
        (error/try-render
          (rdf/blank-node (assoc opts :attr-key :dns/ontologicalType)
                          (meta ontologicalType)))])]))

(rum/defc synset-header-content
  [subentity opts]
  [:<>
   (synset-summary subentity opts)])

(rum/defc semantic-relations-content
  [title subentity {:keys [languages] :as opts}]
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
     (table/attr-val-table opts subentity))])

(rum/defc entity-section-content
  [title subentity opts]
  (cond
    (= title section/semantic-title)
    (semantic-relations-content title subentity opts)

    (and (nil? title)
         (shared/rdf= (:rdf/type subentity) :ontolex/LexicalConcept))
    (synset-header-content subentity opts)

    :else
    (table/attr-val-table opts subentity)))

(rum/defc entity-section
  [title subentity {:keys [languages] :as opts}]
  (if title
    (let [title-id (str "section-title-" (shared/lstr-slug title))]
      [:section.subentity {:key             title
                           :aria-labelledby title-id}
       [:h2 {:id title-id} (str (i18n/select-label languages title))]
       (entity-section-content title subentity opts)])
    [:section.subentity {:key        :no-title
                         :aria-label "RDF resource summary"}
     (entity-section-content title subentity opts)]))

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
