(ns dk.cst.dannet.web.ui.entity
  "The parts of an entity page: its header, its sections and its notes."
  (:require [clojure.string :as str]
            [flatland.ordered.map :as fop]
            [rum.core :as rum]
            [dk.cst.theophrastus.term :as term]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.web.i18n :as i18n]
            [dk.cst.dannet.web.section :as section]
            #?(:clj  [dk.cst.dannet.web.ui.error :as error]
               :cljs [dk.cst.dannet.web.ui.error :as error :include-macros true])
            [dk.cst.dannet.web.ui.rdf :as rdf]
            [dk.cst.dannet.web.ui.table :as table]
            [dk.cst.dannet.web.ui.tabs :as tabs]
            [dk.cst.dannet.web.ui.visualization :as viz]))

(defn rdf=
  "An equals which also supports RDF open-ended semantics, i.e. `v` can  also be
  a set or a vector containing `x`."
  [v x]
  (cond
    (set? v) (get v x)
    (coll? v) (get (set v) x)
    :else (= v x)))

(defn lstr-slug
  "Turn a coll of `lstrs` into a slug that is HTML5 id compatible."
  [lstrs]
  (some-> (i18n/select-label [nil "en" "da"] lstrs)
          (str)
          (str/lower-case)
          (str/replace #"\s+" "-")))

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
            (sort-by (rdf/label-sortkey-fn opts)
                     (filter ks entity))))))

(rum/defc entity-header
  [{:keys [subject languages entity k->label detail-level]
    :as   opts}]
  (let [[prefix local-name rdf-uri] (rdf/parse-rdf-term subject)
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
        a-titles      [(term/->LangString "Visit this location directly" "en")
                       (term/->LangString "Besøg denne lokation direkte" "da")]
        uri-only?     (and (not label) (= local-name rdf-uri))
        uri-link      [:a.rdf-uri {:href  rdf-uri
                                   :title (str (select-label* a-titles))
                                   :key   rdf-uri}
                       (rdf/break-up-uri rdf-uri)]]
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
           uri-link
           local-name))]
      ;; Rendered explicitly since the CSS-based superscripts skip the h1 span;
      ;; hidden when matching the UI language, mirroring the CSS rule.
      (when (and label-lang (or (= detail-level :high)
                                (not= label-lang (first languages))))
        [:sup label-lang])]
     (when-not uri-only?
       (if-let [uri-prefix (and prefix (prefix/prefix->uri prefix))]
         [:a.rdf-uri {:href  rdf-uri
                      :title (str (select-label* a-titles))}
          [:span.rdf-uri__prefix {:key uri-prefix}
           (rdf/break-up-uri uri-prefix)]
          [:span.rdf-uri__name {:key local-name}
           (rdf/break-up-uri local-name)]]
         uri-link))]))

(rum/defc entity-notes
  [{:keys [href languages comments inferred inherited supplemented]
    :as   opts}]
  (let [format-href #(str href (if (re-find #"\?" href) "&" "?") "format=" %)]
    [:aside.notes
     (when (not-empty inferred)
       [:p.note.desktop-only [:strong "∴ "] (:inference comments)])
     (when (not-empty inherited)
       [:p.note.desktop-only [:strong "† "] (:inheritance comments)])
     (when (not-empty supplemented)
       [:p.note.desktop-only [:strong "↪ "] (:supplemented comments)])
     (when (not-empty (:folded opts))
       [:p.note.desktop-only [:strong "⊑ "] (:entailment comments)])
     [:p.note
      [:strong "↓ "]
      (i18n/da-en languages
        "hent data som: "
        "download data as: ")
      [:a {:href     (format-href "turtle")
           :type     "text/turtle"
           :title    "Turtle"
           :download true}
       "Turtle"]
      ", "
      [:a {:href     (format-href "json-ld")
           :type     "application/ld+json"
           :title    "JSON-LD"
           :download true}
       "JSON-LD"]]]))

(defn da-en-pos
  [languages pos]
  (get (i18n/da-en languages
         shared/pos-abbr-da
         shared/pos-abbr-en)
       pos))

(defn- synset-lemmas
  "Candidate lemmas for a synset `entity` derived from its label(s)."
  [{:keys [rdfs/label]}]
  (->> (shared/setify label)
       (mapcat #(shared/sense-labels shared/synset-sep (str %)))
       (keep #(second (re-matches shared/sense-label %)))
       (map str/trim)
       (remove #{shared/omitted ""})
       (distinct)))

(defn split-matches
  "Split `s` into segments of [:text s]/[:match s] according to regex `re`.
  Unlike str/split, the matched segments are retained in the output."
  [re s]
  (let [indices (concat [0] (apply concat (shared/match-bounds re s)) [(count s)])
        spans   (partition 2 1 indices)
        kinds   (cycle [:text :match])]
    (->> (map (fn [kind [start end]]
                [kind (subs s start end)])
              kinds
              spans)
         (filterv (comp seq second)))))

(defn- highlight-lemmas
  "Emphasise occurrences of `lemmas` (incl. inflected forms) in the string `s`."
  [lemmas s]
  (if (empty? lemmas)
    s
    (into [:<>] (for [[kind segment] (split-matches (shared/lemma-pattern lemmas) s)]
                  (case kind
                    :match [:strong segment]
                    :text segment)))))

(defn- example-strs
  "The usage examples in a synset `subentity` as [property example] pairs.
  DanNet synsets are supplemented with lexinfo:senseExample gathered from their
  senses, while the OEWN attaches wn:example blank nodes directly to synsets."
  [{:keys [lexinfo/senseExample wn/example]}]
  (cond
    senseExample
    (for [ex (sort-by str (shared/setify senseExample))]
      ["lexinfo:senseExample" ex])

    example
    (for [ex (->> (shared/setify example)
                  (keep (comp first :rdf/value))            ; blank node values
                  (sort-by str))]
      ["wn:example" ex])))

(rum/defc synset-examples
  "The usage examples in `subentity`, rendered dictionary-style as list items
  with the lemmas of the synset in `opts` highlighted within the example text;
  any after the first are shown by a button below them."
  [subentity {:keys [entity] :as opts}]
  (when-let [examples (not-empty (example-strs subentity))]
    (let [[example & more] examples
          lemmas (synset-lemmas entity)
          item   (fn [[property ex]]
                   [:li.sense-example {:key      (str ex)
                                       :property property
                                       :title    property
                                       :lang     (i18n/lang ex)}
                    (error/try-render-with [e ex]
                      (highlight-lemmas lemmas (str ex))
                      (str ex))])]
      ;; keyed by the examples, so the next synset's examples start folded too
      [:li.summary-examples {:key (hash examples)}
       (rdf/show-more opts [:ul (item example)] (count more) #(map item more))])))

(rum/defc synset-summary
  "The definition, usage examples and ontological type of a synset; the
  `badge`, if any, sits opposite the ontological type on the last line."
  ([subentity opts]
   (synset-summary subentity opts nil))
  ([{:keys [wn/lexfile dns/ontologicalType] :as subentity}
    {:keys [languages entity] :as opts}
    badge]
   ;; DanNet synsets derive their part of speech from wn:lexfile, while OEWN
   ;; synsets state it in wn:partOfSpeech.
   (let [opts     (assoc opts :bare-sets? true)
         pos      (da-en-pos languages (or (some->> lexfile shared/lexfile->pos)
                                           (some->> entity :wn/partOfSpeech name)))
         ;; OEWN uses dc:subject despite the GWA defining wn:lexfile explicitly!
         lexfile' (or lexfile (:dc/subject entity))]
     [:ul.synset-summary {:typeof "ontolex:LexicalConcept"}
      [:li.summary-main
       (when pos
         [:abbr.pos-label (cond-> {:title (if lexfile'
                                            (str lexfile' " (wn:lexfile)")
                                            "wn:lexfile")}
                            lexfile' (assoc :property "wn:lexfile"
                                            :content (str lexfile')))
          pos])
       (if-let [definition (:skos/definition subentity)]
         (let [definition* (i18n/select-str languages definition)
               definition' (if (coll? definition*)
                             (->> (sort (map str definition*))
                                  (str/join "; "))
                             definition*)]
           [:span {:property "skos:definition"
                   :title    "skos:definition"}
            (rdf/transform-val definition' opts)])
         (when-let [definition (:wn/definition subentity)]
           [:span {:property "wn:definition"
                   :title    "wn:definition"}
            (rdf/blank-node opts definition)]))]
      (synset-examples subentity opts)
      (when (or ontologicalType badge)
        [:li.summary-types
         (when ontologicalType
           [:span {:property "dns:ontologicalType"
                   :title    "dns:ontologicalType"}
            (rdf/value (assoc opts :attr-key :dns/ontologicalType)
                       ontologicalType)])
         badge])])))

(rum/defc shape-badge
  "Discreet badge displaying the rdf:type `shape` behind a custom summary
  component, since such components otherwise hide the type of the resource."
  [shape opts]
  [:span.summary-badge {:property "rdf:type"
                        :title    "rdf:type"}
   (rdf/entity-link shape (assoc opts :link-title "rdf:type"))])

(def table-view
  "The view of a section as its attribute-value table, which has a border of
  its own, so the view needs no card."
  {:id     :table
   :icon   "table"
   :label  {:da "tabel" :en "table"}
   :card?  false
   :render (fn [subentity opts]
             (table/attr-val-table opts subentity))})

(def summary-tablist
  "The views of the summary card at the top of a synset page."
  {:label {:da "Visning af resumé" :en "Summary view"}
   :views [{:id     :summary
            :icon   "summary"
            :label  {:da "resumé" :en "summary"}
            ;; The search page reuses the synset summary without the badge.
            :render (fn [subentity opts]
                      (synset-summary subentity opts
                                      (shape-badge :ontolex/LexicalConcept opts)))}
           table-view]})

(def relation-tablist
  "The views of the semantic relations of a synset."
  {:label {:da "Visning af betydningsrelationer" :en "Semantic relations view"}
   :views [{:id     :diagram
            :icon   "diagram"
            :label  {:da "diagram" :en "diagram"}
            :render (fn [subentity opts]
                      (error/try-render (viz/expanded-diagram subentity opts)))}
           table-view]})

(rum/defc semantic-relations-content
  [id subentity {:keys [languages] :as opts}]
  [:<>
   [:p.subheading
    (i18n/da-en languages
      [:<> [:a {:href prefix/relations-path} "Lær mere"]
       " om de enkelte relationer."]
      [:<> [:a {:href prefix/relations-path} "Learn more"]
       " about the individual relations."])]
   (tabs/switchable id relation-tablist subentity opts)
   ;; A note on what the diagram leaves out, below its frame.
   (when (= :diagram (:id (tabs/selected-view id relation-tablist opts)))
     [:p.note
      [:strong "! "]
      (i18n/da-en languages
        "Data kan være udeladt; se tabellen for samtlige detaljer."
        "Data may be omitted; view table for full details.")])])

(defn- synset-header?
  "True if section `id` holds the summary at the top of the synset page in
  `opts`: the :top section (see section/defined-sections) of a synset that
  isn't a frame."
  [id {:keys [entity subject]}]
  (and (= :top id)
       (rdf= (:rdf/type entity) :ontolex/LexicalConcept)
       (not (shared/frame? subject))))

(rum/defc entity-section-content
  [id subentity opts]
  (cond
    (= id :semantic)
    (semantic-relations-content id subentity opts)

    (synset-header? id opts)
    (tabs/switchable id summary-tablist subentity opts)

    :else
    (table/attr-val-table opts subentity)))

(rum/defc entity-section
  [id subentity {:keys [languages] :as opts}]
  (let [title    (section/titles id)
        title-id (when title (str "section-title-" (lstr-slug title)))]
    [:section.subentity (if title
                          {:aria-labelledby title-id}
                          {:aria-label "RDF resource summary"})
     (when title
       [:h2 {:id title-id} (str (i18n/select-label languages title))])
     (error/try-render (entity-section-content id subentity opts))]))

(rum/defc full-screen-content
  [{:keys [entity]
    :as   opts}]
  (error/try-render
    (viz/expanded-diagram
      (ordered-subentity opts shared/semantic-rels? entity)
      opts)))

(rum/defc entity-content
  [{:keys [entity]
    :as   opts}]
  (for [[id ks] (section/page-sections entity)]
    (when-let [subentity (ordered-subentity opts ks entity)]
      (rum/with-key (entity-section id subentity opts) id))))
