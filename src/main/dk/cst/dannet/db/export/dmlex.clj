(ns dk.cst.dannet.db.export.dmlex
  "DMLex export functionality. See doc/dmlex/plan.md for the conversion rules.

  The intermediate structure is a single map using the DMLex property names as
  keys, e.g. {:langCode \"da\" :entries [{:headword \"hund\" :senses [...]}]}.
  It feeds both the XML and the JSON serializer, which differ in how they
  represent labels, parts of speech, sameAs URIs and the annotation markers
  (stand-off startIndex/endIndex pairs in JSON, inline elements in XML).

  The exported JSON can be browsed as a dictionary with the generic DMLex
  browser at https://github.com/kuhumcst/DMLex-browser."
  (:require [clojure.data.json :as json]
            [clojure.data.xml :as xml]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-file-zip.core :as zip]
            [ont-app.vocabulary.lstr :as lstr]
            [dk.cst.dannet.db :as db]
            [dk.cst.dannet.db.export.rdf :as rdf]
            [dk.cst.dannet.db.query :as q]
            [dk.cst.dannet.db.query.operation :as op]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.release :as release]
            [dk.cst.dannet.shared :as shared]))

(def dmlex-uri
  "http://docs.oasis-open.org/lexidma/ns/dmlex-1.0")

(xml/alias-uri 'dmlex dmlex-uri)

(def pos-tag
  "DanNet part-of-speech resource -> DMLex partOfSpeech tag."
  {:wn/noun      "noun"
   :wn/verb      "verb"
   :wn/adjective "adjective"})

(def part-of-speech-tags
  (mapv (fn [[tag description]]
          {:tag         tag
           :for         "entry"
           :description description
           :sameAs      [(str (prefix/prefix->uri 'lexinfo) tag)]})
        ;; The XSD asserts a non-empty description even though it also declares
        ;; the element optional, so every partOfSpeechTag needs one.
        [["noun" {"da" "substantiv" "en" "noun"}]
         ["verb" {"da" "verbum" "en" "verb"}]
         ["adjective" {"da" "adjektiv" "en" "adjective"}]]))

(def label-type-tags
  [{:tag         "synset"
    :description {"da" "DanNet-synset"
                  "en" "DanNet synset"}
    :sameAs      [(str (prefix/prefix->uri 'ontolex) "LexicalConcept")]}
   {:tag         "ontologicalType"
    :description {"da" "DanNets ontologiske type"
                  "en" "DanNet ontological type"}
    :sameAs      [(str (prefix/prefix->uri 'dns) "ontologicalType")]}
   {:tag         "lexfile"
    :description {"da" "Semantisk felt fra WordNet, fx noun.animal"
                  "en" "Semantic field from WordNet, e.g. noun.animal"}
    :sameAs      [(str (prefix/prefix->uri 'wn) "lexfile")]}
   {:tag         "domain"
    :description {"da" "Fagområde fra Den Danske Ordbog, fx zoologi eller medicin"
                  "en" "Subject domain from Den Danske Ordbog, e.g. zoology or medicine"}
    :sameAs      [(str (prefix/prefix->uri 'dc) "subject")]}
   {:tag         "gender"
    :description {"da" "Kønnet på den person som et DanNet-synset betegner"
                  "en" "The gender of the person that a DanNet synset denotes"}
    :sameAs      [(str (prefix/prefix->uri 'dns) "gender")]}
   {:tag         "register"
    :description {"da" "Register, fx slang"
                  "en" "Register, e.g. slang"}
    :sameAs      [(str (prefix/prefix->uri 'lexinfo) "register")]}
   {:tag         "temporal"
    :description {"da" "Datering, fx gammeldags"
                  "en" "Dating, e.g. old-fashioned"}
    :sameAs      [(str (prefix/prefix->uri 'lexinfo) "dating")]}
   {:tag         "frequency"
    :description {"da" "Brugsfrekvens"
                  "en" "Frequency of use"}
    :sameAs      [(str (prefix/prefix->uri 'lexinfo) "frequency")]}
   {:tag         "usage"
    :description {"da" "Brugsnote fra Den Danske Ordbog"
                  "en" "Usage note from Den Danske Ordbog"}
    :sameAs      [(str (prefix/prefix->uri 'lexinfo) "usageNote")]}
   {:tag         "norm"
    :description {"da" "Retskrivningsstatus for en bøjningsform"
                  "en" "Spelling-norm status of an inflected form"}}
   {:tag         "sentiment"
    :description {"da" "Sentiment-polaritet fra Det Danske Sentimentleksikon"
                  "en" "Sentiment polarity from Det Danske Sentimentleksikon"}
    :sameAs      [(str (prefix/prefix->uri 'marl) "hasPolarity")]}
   {:tag         "sentimentValue"
    :description {"da" "Sentiment-værdi fra -3 (negativ) til 3 (positiv)"
                  "en" "Sentiment value from -3 (negative) to 3 (positive)"}
    :sameAs      [(str (prefix/prefix->uri 'marl) "polarityValue")]}
   {:tag         "frame"
    :description {"da" "Berkeley FrameNet-ramme fremkaldt af betydningen, via COR.SEM"
                  "en" "Berkeley FrameNet frame evoked by the sense, via COR.SEM"}
    :sameAs      [(str (prefix/prefix->uri 'dns) "frame")]}
   {:tag         "simpleOntologicalType"
    :description {"da" "Ontologisk type i COR.SEM's forenklede ontologi; kun angivet hvor den afviger fra synsettets ontologiske type"
                  "en" "Ontological type in the simplified COR.SEM ontology; only stated where it differs from the ontological type of the synset"}
    :sameAs      [(str (prefix/prefix->uri 'dns) "simpleOntologicalType")]}
   {:tag         "polysemyPattern"
    :description {"da" "Systematisk polysemimønster fra COR.SEM"
                  "en" "Systematic polysemy pattern from COR.SEM"}
    :sameAs      [(str (prefix/prefix->uri 'dns) "polysemyPattern")]}
   {:tag         "centrality"
    :description {"da" "Betydningens centralitet i det danske kerneordforråd, via COR.SEM: nøgleord i Den Danske Begrebsordbog og/eller centralt begreb i DanNet (koblet til Princeton WordNets Core WordNet)"
                  "en" "The centrality of the sense in the Danish core vocabulary, via COR.SEM: a keyword in the thesaurus Den Danske Begrebsordbog and/or a central DanNet concept (linked to Princeton WordNet's Core WordNet)"}
    :sameAs      [(str (prefix/prefix->uri 'dns) "centrality")]}
   {:tag         "corsem"
    :description {"da" "Betydningens id i COR.SEM, betydningsinventaret til Det Centrale Ordregister"
                  "en" "The id of the sense in COR.SEM, the sense inventory of the Central Word Registry (COR)"}
    :sameAs      [(str (prefix/prefix->uri 'dns) "eqSense")]}
   {:tag         "source"
    :description {"da" "Kildehenvisning til den fulde definition i Den Danske Ordbog"
                  "en" "Source link to the full definition in Den Danske Ordbog (DDO)"}
    :sameAs      [(str (prefix/prefix->uri 'dns) "source")]}])

(def label-type-of
  "DanNet sense property -> the DMLex labelTypeTag it belongs under."
  {:lexinfo/register  "register"
   :lexinfo/dating    "temporal"
   :lexinfo/frequency "frequency"})

(def sentiment-label-tags
  "The two sentiment inventories: the three polarities and the values from -3
  to 3. Neither declares `for`, since the labels go on entries and on senses.
  The polarity tags are the MARL class names, so each carries a readable
  description."
  (concat
    (for [[polarity description] [["Positive" {"da" "positiv" "en" "positive"}]
                                  ["Neutral" "neutral"]
                                  ["Negative" {"da" "negativ" "en" "negative"}]]]
      {:tag         polarity
       :typeTag     "sentiment"
       :description description
       :sameAs      [(str (prefix/prefix->uri 'marl) polarity)]})
    (for [value (range -3 4)]
      {:tag     (str value)
       :typeTag "sentimentValue"})))

(def centrality-label-tags
  "The three centrality marks of COR.SEM, carried onto the exactly matched
  DanNet senses. The bare values collide with the sentimentValue tags, so the
  tags are prefixed; the descriptions carry the meaning."
  [{:tag         "centrality-1"
    :typeTag     "centrality"
    :for         "sense"
    :description {"da" "nøgleord i Begrebsordbogen"
                  "en" "thesaurus keyword"}}
   {:tag         "centrality-2"
    :typeTag     "centrality"
    :for         "sense"
    :description {"da" "centralt begreb"
                  "en" "central concept"}}
   {:tag         "centrality-3"
    :typeTag     "centrality"
    :for         "sense"
    :description {"da" "nøgleord og centralt begreb"
                  "en" "keyword and central concept"}}])

(def norm-label-tags
  "The norm-status tags. COR states the norm status of a form in the
  rdfs:comment of the ontolex:Form; the two possible comments map to these
  tags via norm-comment->tag."
  [{:tag         "unormeret"
    :typeTag     "norm"
    :for         "inflectedForm"
    :description {"da" "Bøjningsform uden for retskrivningsnormen"
                  "en" "Inflected form outside the official spelling norm"}}
   {:tag         "sandsynligvis korrekt"
    :typeTag     "norm"
    :for         "inflectedForm"
    :description {"da" "Bøjningsform som ikke er normeret, men sandsynligvis korrekt"
                  "en" "Inflected form not formally normed, yet probably correct"}}])

(def source-identity-tags
  "The single source of the DanNet sense examples. The deep link to the DDO
  definition of a sense travels on each of its examples as the
  sourceElaboration attribute."
  [{:tag         "DDO"
    :description {"da" "Den Danske Ordbog"
                  "en" "Den Danske Ordbog (The Danish Dictionary)"}
    :sameAs      ["https://ordnet.dk/ddo"]}])

(def definition-type-tags
  "The single definition-type tag, only used by the English variant: a synset
  with an unambiguous ILI link carries the English definition of its
  interlingual concept next to the Danish definition."
  [{:tag         "ili"
    :description {"da" "Engelsk definition af det interlingvale begreb (CILI)"
                  "en" "English definition of the interlingual concept (CILI)"}
    :sameAs      [(prefix/prefix->uri 'ili)]}])

(defn localize
  "The Controlled Values `tags` with each bilingual :description map resolved
  to `lang`. A plain-string description is the same in both languages and
  passes through untouched."
  [lang tags]
  (mapv (fn [{:keys [description] :as tag}]
          (cond-> tag
            (map? description) (assoc :description (get description lang))))
        tags))

(defn lexicographic-resource
  "The parts of the DMLex resource in `lang` that do not come from the graph.
  The full schemas require `translationLanguages` next to the crosslingual
  headwordTranslations.

  The langCode names the language of the whole resource, not just of the
  headwords, since the DMLex browser keys its UI language on it. The headwords
  stay Danish in the English variant."
  [lang]
  {:title                "DanNet"
   :uri                  prefix/dn-uri
   :langCode             lang
   :translationLanguages ["en"]
   :labelTypeTags        (localize lang label-type-tags)
   :partOfSpeechTags     (localize lang part-of-speech-tags)
   :sourceIdentityTags   (localize lang source-identity-tags)})

(defn attrs
  "The `ks` of `m` as an XML attribute map, minus the nil values."
  [m ks]
  (into {} (comp (map (juxt identity m))
                 (remove (comp nil? second)))
        ks))

(defn ->same-as
  [uri]
  [::dmlex/sameAs {:uri uri}])

(defn ->description
  [description]
  (when description
    [::dmlex/description description]))

(defn ->tag
  "The Controlled Values tag object `m` as the XML `element`, with the `ks` of
  `m` as its attributes."
  [element ks {:keys [description sameAs] :as m}]
  (into [element (attrs m ks)]
        (remove nil? (cons (->description description) (map ->same-as sameAs)))))

(defn ->relation-type
  [{:keys [description memberTypes sameAs] :as m}]
  (into [::dmlex/relationType (attrs m [:type :scopeRestriction])]
        (concat (remove nil? [(->description description)])
                (map (partial ->tag ::dmlex/memberType [:role :type :min :max :hint])
                     memberTypes)
                (map ->same-as sameAs))))

(defn ->member
  [m]
  [::dmlex/member (attrs m [:ref :role :obverseListingOrder])])

(defn ->relation
  [{:keys [description members] :as m}]
  (into [::dmlex/relation (attrs m [:type])]
        (concat (remove nil? [(->description description)])
                (map ->member members))))

(defn ->label
  [tag]
  [::dmlex/label {:tag tag}])

(defn ->part-of-speech
  [tag]
  [::dmlex/partOfSpeech {:tag tag}])

(defn ->inflected-form
  [{:keys [text labels] :as m}]
  (into [::dmlex/inflectedForm (attrs m [:tag])
         [::dmlex/text text]]
        (map ->label labels)))

(defn ->definition
  [{:keys [text] :as m}]
  [::dmlex/definition (attrs m [:definitionType])
   [::dmlex/text text]])

(defn ->text
  "The XML text element of `text`, with the stand-off `markers` of the
  intermediate structure inlined as headwordMarker elements, which is how the
  XML serialization of the annotation module represents them."
  [text markers]
  (let [indices (concat [0]
                        (mapcat (juxt :startIndex :endIndex) markers)
                        [(count text)])]
    (->> (map (fn [kind [start end]]
                (let [s (subs text start end)]
                  (if (= kind :marker)
                    [::dmlex/headwordMarker s]
                    s)))
              (cycle [:text :marker])
              (partition 2 1 indices))
         (remove #{""})
         (into [::dmlex/text]))))

(defn ->example
  [{:keys [text headwordMarkers labels] :as m}]
  (into [::dmlex/example (attrs m [:sourceIdentity :sourceElaboration :soundFile])
         (->text text headwordMarkers)]
        (map ->label labels)))

(defn ->headword-translation
  [{:keys [text] :as m}]
  [::dmlex/headwordTranslation (attrs m [:langCode])
   [::dmlex/text text]])

(defn ->sense
  [{:keys [indicator labels definitions examples headwordTranslations] :as m}]
  (into [::dmlex/sense (attrs m [:id])]
        (concat (when indicator
                  [[::dmlex/indicator indicator]])
                (map ->label labels)
                (map ->definition definitions)
                (map ->example examples)
                (map ->headword-translation headwordTranslations))))

(defn ->entry
  [{:keys [headword partsOfSpeech labels inflectedForms senses] :as m}]
  (into [::dmlex/entry (attrs m [:id :homographNumber])
         [::dmlex/headword headword]]
        (concat (map ->part-of-speech partsOfSpeech)
                (map ->label labels)
                (map ->inflected-form inflectedForms)
                (map ->sense senses))))

(defn resource-children
  "The child hiccup elements of the lexicographicResource of `resource`. The
  child order follows the sequences of dmlex.xsd. Kept lazy so the XML
  serialization can stream one child element at a time."
  [{:keys [entries translationLanguages definitionTypeTags inflectedFormTags
           labelTags labelTypeTags partOfSpeechTags sourceIdentityTags
           relations relationTypes]}]
  (concat (map ->entry entries)
          (for [lang translationLanguages]
            [::dmlex/translationLanguage {:langCode lang}])
          (map (partial ->tag ::dmlex/definitionTypeTag [:tag]) definitionTypeTags)
          (map (partial ->tag ::dmlex/inflectedFormTag [:tag :for]) inflectedFormTags)
          (map (partial ->tag ::dmlex/labelTag [:tag :typeTag :for]) labelTags)
          (map (partial ->tag ::dmlex/labelTypeTag [:tag]) labelTypeTags)
          (map (partial ->tag ::dmlex/partOfSpeechTag [:tag :for]) partOfSpeechTags)
          (map (partial ->tag ::dmlex/sourceIdentityTag [:tag]) sourceIdentityTags)
          (map ->relation relations)
          (map ->relation-type relationTypes)))

(defn indent-sexp
  "Indent the XML `element` sexp at the nesting `depth` by inserting
  whitespace text nodes between its children. Only an element whose children
  are all elements is indented: whitespace is insignificant there, while an
  element with character data (e.g. an example text with inline markers)
  must keep its content untouched. The indenting XML emitters make no such
  distinction, which is why the indentation is inserted as data instead."
  ([element]
   (indent-sexp 0 element))
  ([depth element]
   (let [[tag & children] element
         [attr-map kids] (if (map? (first children))
                           [(first children) (next children)]
                           [nil children])
         pad             (fn [n] (apply str "\n" (repeat n "  ")))]
     (if (and (seq kids) (every? vector? kids))
       (-> (if attr-map [tag attr-map] [tag])
           (into (mapcat (fn [kid]
                           [(pad (inc depth)) (indent-sexp (inc depth) kid)])
                         kids))
           (conj (pad depth)))
       element))))

(defn write-xml!
  "Write a DMLex `resource` to file `f` as indented XML, with the text of
  `license` preceding the root element as a schema-transparent comment.

  The root's children are built, indented and converted to elements lazily,
  one child at a time, and `xml/emit` drops each as soon as it is written,
  so the whole element tree is never materialized at once."
  [f license resource]
  (with-open [w (io/writer f)]
    (xml/emit ["\n" (xml/xml-comment license) "\n"
               (xml/element* ::dmlex/lexicographicResource
                             (merge {:xmlns dmlex-uri}
                                    (attrs resource [:title :uri :langCode]))
                             (concat
                               (mapcat (fn [kid]
                                         ["\n  " (xml/sexp-as-element
                                                   (indent-sexp 1 kid))])
                                       (resource-children resource))
                               ["\n"]))]
              w)))

(defn prune
  "Remove nil values and empty collections from the maps inside `x`.

  Unchanged substructure is returned as-is rather than rebuilt, so pruning a
  mostly clean resource shares almost all of its structure."
  [x]
  (cond
    (map? x)
    (reduce-kv (fn [m k v]
                 (let [v' (prune v)]
                   (cond
                     (or (nil? v') (and (coll? v') (empty? v')))
                     (dissoc m k)

                     (identical? v' v) m
                     :else (assoc m k v'))))
               x
               x)

    (vector? x)
    (let [x' (mapv prune x)]
      (if (every? true? (map identical? x' x)) x x'))

    (seq? x)
    (map prune x)

    :else x))

;; TODO: report the homographNumber mismatch to the LEXIDMA committee
;; dmlex_no-crosslingual.xsd types the attribute as xs:integer while
;; dmlex_no-crosslingual.schema.json types the property as a string, so no
;; single value serializes into both. Drop the :value-fn once they agree.

(defn write-json!
  "Write a DMLex `resource` to file `f` as JSON, streamed through `json/write`.
  The `homographNumber` of an entry is coerced during the write: it is an
  integer in the XSD but a string in the JSON schema."
  [f resource]
  (with-open [w (io/writer f)]
    (json/write (prune resource) w
                :key-fn name
                :value-fn (fn [k v] (if (= k :homographNumber) (str v) v))
                :escape-slash false
                :escape-unicode false)))

;; Every query runs on the raw DanNet graph. The inference graph materialises
;; both directions of every inverse relation as well as the transitive closure
;; of e.g. wn:hypernym, none of which was stated by a lexicographer.

(defn ontological-type
  "The member concepts of one synset's ontological type, e.g.
  [\"LanguageRepresentation\" \"Artifact\" \"Object\"]. The `rows` are the
  members of one rdf:Bag, which the rdf:_N index puts back in order."
  [rows]
  (->> rows
       (sort-by #(parse-long (subs (name (get % '?member)) 1)))
       (mapv (comp name '?class))))

(def exported-relations
  "The DanNet relations that become DMLex relations, in the direction that the
  export keeps. The obverse of each is flipped into this direction. The
  direction is the one that the lexicographers used, except for wn:meronym and
  wn:mero_location, where the meronymy relations stay consistent instead.

  dns:subsumed is missing on purpose: it holds between senses rather than
  synsets, and all 188 of its objects are senses that do not exist in DanNet."
  #{:wn/hypernym
    :wn/instance_hypernym
    :wn/meronym
    :wn/mero_part
    :wn/mero_member
    :wn/mero_substance
    :wn/mero_location
    :wn/domain_topic
    :wn/agent
    :wn/patient
    :wn/result
    :wn/co_instrument_agent
    :wn/similar
    :wn/also
    :dns/crossPoSHypernym
    :dns/orthogonalHypernym
    :dns/nearAntonym
    :dns/usedFor
    :dns/usedForObject})

(def cor-sem-relations
  "The relations read from the cor-sem: graph rather than the dn: graph: the
  synset alternation pairs derived from COR.SEM's systematic polysemy (GitHub
  issue #207). The graph also states alternation pairs between its own
  COR.SEM senses; dannet-pair? keeps those out."
  #{:dns/alternatesTo
    :dns/alternatesWith})

(defn tag-descriptions
  "Descriptions of `concepts` in `lang`, from the schema statements in `g`. A
  comment is more informative than a label, and a label in the other export
  language fills in when `lang` has neither. Lexinfo states some comments
  twice in one literal, separated by ' // '; only the first copy is kept."
  [g lang concepts]
  (let [other  (if (= lang "da") "en" "da")
        values (str/join " " (map prefix/kw->qname concepts))
        rows   (q/run g (op/sparql
                          "SELECT ?concept ?comment ?label ?otherLabel
                           WHERE {
                             VALUES ?concept { " values " }
                             OPTIONAL { ?concept rdfs:comment ?comment .
                                        FILTER(LANG(?comment) = \"" lang "\") . }
                             OPTIONAL { ?concept rdfs:label ?label .
                                        FILTER(LANG(?label) = \"" lang "\") . }
                             OPTIONAL { ?concept rdfs:label ?otherLabel .
                                        FILTER(LANG(?otherLabel) = \"" other "\") . }
                           }"))]
    (into {} (for [{:syms [?concept ?comment ?label ?otherLabel]} rows
                   :let [description (or ?comment ?label ?otherLabel)]
                   :when description]
               [?concept (first (str/split (str description) #" // "))]))))

(defn inverse-relations
  "DanNet relation -> its obverse relation, from the `owl:inverseOf` statements
  of the schema graph `g`. A symmetric relation maps to itself."
  [g]
  (into {} (comp (map (juxt '?a '?b))
                 (filter (fn [[a b]] (and (keyword? a) (keyword? b)))))
        (q/run g op/inverse-query)))

(defn dannet-pair?
  "Is every synset of `pair` in DanNet? A relation to another dataset cannot
  become a DMLex relation, since a member must be in the same file."
  [pair]
  (every? #(= "dn" (namespace %)) pair))

(defn relation-pairs
  "The synset pairs of `rel` in the raw graph `g`. The statements of the obverse
  relation are flipped into the direction of `rel`. A symmetric relation gets
  one pair for each unordered pair of synsets."
  [g rel obverse]
  (let [rows      (map (juxt '?subject '?object) (q/run g (op/relation-query rel)))
        symmetric (= rel obverse)
        flipped   (when (and obverse (not symmetric))
                    (map (juxt '?object '?subject) (q/run g (op/relation-query obverse))))]
    (into #{}
          (comp (filter dannet-pair?)
                (remove (fn [[a b]] (= a b)))
                (map (if symmetric #(vec (sort-by name %)) vec)))
          (concat rows flipped))))

(defn run-queries
  "Fetch the DMLex source data from `db`. Everything but the schema statements
  comes from the raw graphs, which hold only what the DanNet releases state."
  [{:keys [dataset graph]}]
  (let [g            (db/get-graph dataset prefix/dn-uri)
        cor-g        (db/get-graph dataset prefix/cor-uri)
        dds-g        (db/get-graph dataset prefix/dds-uri)
        oewn-g       (db/get-graph dataset prefix/oewn-uri)
        ili-g        (db/get-graph dataset prefix/ili-uri)
        sem-g        (db/get-graph dataset prefix/cor-sem-uri)
        obverse-of   (inverse-relations graph)
        types        (q/run g op/ontological-type-query)
        genders      (q/run g op/gender-query)
        sense-labels (q/run g op/sense-label-query)
        concepts     (distinct (concat (map '?gender genders)
                                       (map '?value sense-labels)
                                       exported-relations
                                       cor-sem-relations))]
    {:words             (q/run g op/word-query)
     :senses            (q/run g op/sense-query)
     :definitions       (q/run g op/definition-query)
     :examples          (q/run g op/example-query)
     :sources           (q/run g op/source-query)
     :domains           (q/run g op/domain-query)
     :lexfiles          (q/run g op/lexfile-query)
     :word-variants     (q/run g op/variant-query)
     :ontological-types types
     :synset-labels     (q/run g op/synset-label-query)
     :short-labels      (q/run g op/short-label-query)
     :member-labels     (q/run g op/synset-sense-label-query)
     :polysemy          (q/run g op/sense-label-polysemy)
     :genders           genders
     :sense-labels      sense-labels
     :usage-notes       (q/run g op/usage-note-query)
     :ilis              (q/run g op/ili-query)
     :ili-definitions   (q/run ili-g op/ili-definition-query)
     :oewn-lemmas       (q/run oewn-g op/oewn-lemma-query)
     :cor-links         (q/run cor-g op/cor-link-query)
     :cor-forms         (q/run cor-g op/cor-form-query)
     :sentiment         (concat (q/run dds-g op/sentiment-query)
                                (q/run g op/sentiment-query))
     :eq-senses         (q/run sem-g op/eq-sense-query)
     :eq-sense-matches  (q/run sem-g op/eq-sense-match-query)
     :corsem-frames     (q/run sem-g op/corsem-frame-query)
     :linked-synsets    (q/run sem-g op/linked-synset-query)
     :corsem-patterns   (q/run sem-g op/polysemy-pattern-query)
     :pattern-labels    (q/run sem-g op/pattern-label-query)
     :centralities      (q/run sem-g op/centrality-query)
     :simple-ontotypes  (q/run sem-g op/simple-ontotype-query)
     :ontotype-members  (q/run sem-g op/ontotype-members-query)
     :corsem-notes      (q/run sem-g op/usage-note-query)
     :descriptions      {"da" (tag-descriptions graph "da" concepts)
                         "en" (tag-descriptions graph "en" concepts)}
     :indegrees         @q/synset-indegrees
     :obverse-of        obverse-of
     :relations         (merge
                          (into {}
                                (map (fn [rel]
                                       [rel (relation-pairs g rel (obverse-of rel))]))
                                exported-relations)
                          (into {}
                                (map (fn [rel]
                                       [rel (relation-pairs sem-g rel (obverse-of rel))]))
                                cor-sem-relations))}))

(defn index
  "Index the query result `ms` as a map of `kf` to a single `vf` value."
  [ms kf vf]
  (into {} (map (juxt kf vf)) ms))

(defn index-many
  "Index the query result `ms` as a map of `kf` to a sorted vector of `vf`
  values."
  [ms kf vf]
  (update-vals (group-by kf ms) #(vec (sort (map vf %)))))

(defn homograph-numbers
  "DanNet word -> DMLex homographNumber for the `words` that share a headword
  and a part of speech. DMLex requires this combination to be unique."
  [words]
  (->> (group-by (fn [{:syms [?pos ?writtenRep]}] [(str ?writtenRep) ?pos]) words)
       (filter (comp next val))
       (mapcat (fn [[_ ms]]
                 (->> (sort-by (comp str '?word) ms)
                      (map-indexed (fn [n {:syms [?word]}] [?word (inc n)])))))
       (into {})))

(defn unambiguous-ilis
  "The `ilis` rows where one synset claims one ILI and one ILI claims one synset.
  An ILI identifier is an identity claim, so the other rows contradict each
  other. They stay out of the export until DanNet corrects them."
  [ilis]
  (let [by-synset (group-by '?synset ilis)
        by-ili    (group-by '?ili ilis)]
    (->> ilis
         (remove #(next (by-synset (get % '?synset))))
         (remove #(next (by-ili (get % '?ili)))))))

(defn headword-markers
  "The DMLex headwordMarkers of the `headword` in the example `text`: one
  stand-off startIndex/endIndex pair per occurrence, inflected forms included."
  [headword text]
  (mapv (fn [[start end]]
          {:startIndex start :endIndex end})
        (shared/match-bounds (shared/lemma-pattern [headword]) text)))

(defn inflection-code
  "The inflection code of a COR `resource`, the last dot-segment of its
  identifier in both the COR.NNNNN.CCC and the COR.EXT.NNNNNN.CCC shape."
  [resource]
  (peek (str/split (name resource) #"\.")))

(defn cor-words
  "DanNet word -> COR word -> the lemmas of the COR word, from the `cor-links`
  query result. A COR word with spelling variants holds several lemmas."
  [cor-links]
  (reduce (fn [m {:syms [?word ?cor ?lemma]}]
            (update-in m [?word ?cor] (fnil conj #{}) (str ?lemma)))
          {}
          cor-links))

(defn matching-cor-words
  "The COR words of `cor-word->lemmas` whose lemma is the `headword`. When the
  linked COR words hold different lemmas, a merge of their paradigms would put
  the forms of another lemma on the entry, so only the matching COR words keep
  their forms. When no lemma matches, all of them do: COR is the authority for
  the spelling, so a differing lemma alone is not a fault."
  [headword cor-word->lemmas]
  (let [lemmas   (into #{} (mapcat val) cor-word->lemmas)
        matching (filter (fn [[_ ls]] (ls headword)) cor-word->lemmas)]
    (if (and (> (count lemmas) 1) (seq matching))
      (map key matching)
      (keys cor-word->lemmas))))

(defn code-descriptions
  "Inflection code -> readable label, parsed from the rdfs:label of each COR
  form, e.g. \"hundenes\"-form (sb.fk.pl.best.gen). A code whose labels
  disagree gets no description."
  [cor-forms]
  (let [label #(some-> (re-find #"\(([^)]*)\)\s*$" (str (get % '?label)))
                       (second))]
    (->> (group-by (comp inflection-code '?form) cor-forms)
         (keep (fn [[code rows]]
                 (let [labels (into #{} (keep label) rows)]
                   (when (= 1 (count labels))
                     [code (first labels)]))))
         (into {}))))

(def norm-comment->tag
  "The rdfs:comment a COR form carries for its norm status (see the
  normativity table in dk.cst.dannet.db.bootstrap.cor) mapped to the
  matching norm-status label tag."
  {"unormeret"                                 "unormeret"
   "ikke normeret, men sandsynligvis korrekt" "sandsynligvis korrekt"})

(defn ->cor-form
  "The DMLex inflectedForm of one COR form row, with the norm status of the
  form (the rdfs:comment of the ontolex:Form) as a label."
  [{:syms [?form ?writtenRep ?comment]}]
  (cond-> {:tag  (inflection-code ?form)
           :text (str ?writtenRep)}
    (norm-comment->tag (str ?comment))
    (assoc :labels [(norm-comment->tag (str ?comment))])))

(defn merge-forms
  "Deduplicate the inflected `forms` of one entry on the pair of tag and text,
  which the XSD makes unique. A merged form keeps the strongest norm status
  among its copies: fully normed beats probably correct, which in turn beats
  unnormed."
  [forms]
  (for [[[tag text] copies] (group-by (juxt :tag :text) forms)
        :let [statuses (map (comp first :labels) copies)]]
    (cond-> {:tag tag :text text}
      (every? some? statuses)
      (assoc :labels [(if (some #{"sandsynligvis korrekt"} statuses)
                        "sandsynligvis korrekt"
                        "unormeret")]))))

(defn sentiment-labels
  "Subject -> its sentiment label tags, e.g. [\"Positive\" \"2\"]. The fault
  conditions of plan section 14.2 apply: a subject with more than one polarity
  gets no labels at all, and a subject with more than one value, or with a
  value that disagrees with its polarity, keeps the polarity label only."
  [sentiment]
  (into {}
        (for [[subject rows] (group-by '?subject sentiment)
              :let [polarities (into #{} (map '?polarity) rows)
                    values     (into #{} (comp (map '?value) (remove nil?)) rows)
                    polarity   (when (= 1 (count polarities)) (first polarities))
                    value      (when (= 1 (count values)) (first values))
                    agree?     (case polarity
                                 :marl/Positive (some-> value pos?)
                                 :marl/Neutral  (some-> value zero?)
                                 :marl/Negative (some-> value neg?)
                                 nil)]
              :when polarity]
          [subject (cond-> [(name polarity)]
                     agree? (conj (str value)))])))

(defn frame-labels
  "Synset -> the FrameNet frames its senses carry as labels, projected from
  the COR.SEM senses via `corsem-frames` and `linked-synsets`.

  The projection follows query/supplement-synset: a COR.SEM sense contributes
  its frames to a synset only when nothing needs distributing, i.e. it links
  just that synset or carries just one frame."
  [corsem-frames linked-synsets]
  (let [frames-of (index-many corsem-frames '?corsem (comp name '?frame))
        links-of  (index-many linked-synsets '?corsem '?synset)]
    (->> (for [[corsem frames] frames-of
               :let [links (links-of corsem)]
               synset links
               :when (or (= 1 (count frames)) (= 1 (count links)))
               frame frames]
           [synset frame])
         (reduce (fn [m [synset frame]]
                   (update m synset (fnil conj (sorted-set)) frame))
                 {}))))

(defn ->synset-label-tag
  "The labelTag that gives a `synset` its identity, with its `description` and
  its `ilis` as extra sameAs URIs."
  [synset description ilis]
  (cond-> {:tag     (name synset)
           :typeTag "synset"
           :for     "sense"
           :sameAs  (into [(str prefix/dn-uri (name synset))] ilis)}
    description (assoc :description description)))

(defn strip-sense-markers
  "The synset `label` without the DDO sense markers, e.g.
  {hund_§1a; køter_§2} -> {hund; køter}."
  [label]
  (str/replace label #"_[0-9§]+[a-z]?" ""))

(defn synset-indicator
  "A DMLex sense indicator from a synset `label`: the member words without the
  braces and the DDO sense markers, e.g. {hund_§1a; køter_§2} -> hund, køter."
  [label]
  (-> label
      (strip-sense-markers)
      (str/replace #"^\{|\}$" "")
      (str/replace "; " ", ")))

(defn marked-indicator
  "A sense indicator that keeps the DDO sense markers of the synset `label`,
  e.g. {hund_§1a; køter_§2} -> hund §1a, køter §2."
  [label]
  (-> label
      (str/replace #"^\{|\}$" "")
      (str/replace "_" " ")
      (str/replace "; " ", ")))

(defn duplicates
  "The non-nil values that occur more than once in `xs`."
  [xs]
  (->> (remove nil? xs)
       (frequencies)
       (into #{} (comp (filter (comp #(> % 1) val)) (map key)))))

(defn indicators
  "The sense indicators of one entry, from the synset `labels` of its senses.
  The plain form of a label is preferred; a plain form that collides inside
  the entry falls back to the marked form; a marked form that also collides
  is dropped, since the XSD makes the indicators of an entry unique."
  [labels]
  (let [plain  (mapv #(some-> % synset-indicator) labels)
        marked (mapv #(some-> % marked-indicator) labels)
        dupe?  (duplicates plain)
        picked (mapv (fn [p m] (if (and p (dupe? p)) m p)) plain marked)
        dupe?' (duplicates picked)]
    (mapv (fn [x] (when-not (and x (dupe?' x)) x)) picked)))

(defn synset-descriptions
  "Synset -> the description of its labelTag, from the synset `labels`.

  The DDO sense markers are bookkeeping that a dictionary reader cannot use, so
  a label loses them. A stripped label that names a second synset keeps them.
  The markers are then the only thing that tells the two apart, and without
  them most multi-sense entries carry the same description twice."
  [labels]
  (let [stripped (update-vals labels strip-sense-markers)
        dupe?    (duplicates (vals stripped))]
    (into {} (for [[synset s] stripped]
               [synset (if (dupe? s) (labels synset) s)]))))

(defn ->sense-label-tag
  "A labelTag for a DanNet `resource` that a sense carries, for example a
  gender."
  [type-tag description resource]
  (cond-> {:tag     (name resource)
           :typeTag type-tag
           :for     "sense"
           :sameAs  [(str (prefix/prefix->uri (symbol (namespace resource)))
                          (name resource))]}
    description (assoc :description description)))

(defn ->source-label-tag
  "The labelTag that carries the DDO source `url` of a DanNet `resource` (a
  sense or a word) as its sameAs, following the identity-register
  convention of plan section 5.3. The tag is the resource name, which equals
  the id of the DMLex object that carries the label; `for` names that object
  kind."
  [for resource url]
  {:tag     (name resource)
   :typeTag "source"
   :for     for
   :sameAs  [url]})

(defn relation-roles
  "The member roles of `rel`: the role of the subject end and of the object end.
  A pair of inverse relations uses the two relation names. A symmetric relation
  uses one name for both ends. A relation with no declared obverse has no name
  for its subject end, so it borrows the GWA convention and prefixes its own
  name with `involved_`. A shared pair such as source and target collides
  between relations, and a consumer cannot tell the two apart."
  [rel obverse]
  (cond
    (nil? obverse)  [(str "involved_" (name rel)) (name rel)]
    (= rel obverse) [(name rel) (name rel)]
    :else           [(name obverse) (name rel)]))

(defn listing-order-fn
  "Synset -> its rank among all synsets, from the synset `indegrees`.

  A pair states no order of its own, so a consumer that merges many relations
  into one list has nothing to rank them by. The inverted indegree gives the
  ascending rank that DMLex asks for, and it carries the prominence measure
  that ranks the DanNet search results. Synsets of equal indegree get equal
  ranks, which leaves the tie-break to the consumer."
  [indegrees]
  (let [ceiling (reduce max 0 (vals indegrees))]
    (fn [synset]
      (- ceiling (get indegrees synset 0)))))

(defn member-positions
  "Sense -> its position in its own synset, from `centrality`, `polysemy` and
  `label-rows`.

  The order is the one behind the synset labels: COR.SEM centrality first,
  then the `shared/canonical` entry-ID heuristic with word polysemy as the
  tiebreak. A sense with more than one label takes the position of its best
  one."
  [centrality polysemy label-rows]
  (let [primary  (shared/centrality-primary centrality)
        tiebreak (shared/polysemy-tiebreak polysemy)
        keyfn    (comp (juxt primary shared/entry-sort-key tiebreak) '?label)]
    (into {}
          (for [[_ rows] (group-by '?synset label-rows)
                :let [senses (distinct (map (comp name '?sense)
                                            (sort-by keyfn rows)))]
                [position sense] (map-indexed vector senses)]
            [sense position]))))

(defn member-order-fn
  "Build the function that gives a relation member its `obverseListingOrder`
  from the member's synset and sense, using `listing-order` and `positions`.

  The rank of the synset is the major component and the position of the
  sense within it the minor one. A sense without a position goes last."
  [listing-order positions]
  (let [size (inc (reduce max 0 (vals positions)))]
    (fn [synset sense]
      (+ (* (listing-order synset) size)
         (get positions sense (dec size))))))

(defn ->members
  "The members of one relation pair, from `senses-of` and `member-order`.

  Despite its name, the `obverseListingOrder` of a member comes from its own
  end of the pair: it says where this member goes when the pair is listed
  from the other end."
  [senses-of member-order [subject object] [subject-role object-role]]
  (concat (for [sense (senses-of subject)]
            {:ref                 sense
             :role                subject-role
             :obverseListingOrder (member-order subject sense)})
          (for [sense (senses-of object)]
            {:ref                 sense
             :role                object-role
             :obverseListingOrder (member-order object sense)})))

(defn ->relations
  "One DMLex relation for each synset pair, plus one synonym relation for
  each synset of two or more senses.

  Every member is a sense, so a pair with a senseless synset at one end
  produces nothing. The synonym members share one synset, so only the
  sense-position component of their `member-order` separates them."
  [senses-of member-order obverse-of relations]
  (concat
    (for [[synset senses] (sort-by (comp name key) senses-of)
          :when (next senses)]
      {:type    "synonym"
       :members (for [sense senses]
                  {:ref                 sense
                   :role                "synonym"
                   :obverseListingOrder (member-order synset sense)})})
    (for [[rel pairs] (sort-by (comp name key) relations)
          :let [roles (relation-roles rel (obverse-of rel))]
          pair (sort-by (partial mapv name) pairs)
          :when (every? (comp seq senses-of) pair)]
      {:type    (name rel)
       :members (->members senses-of member-order pair roles)})))

(defn ->relation-types
  "The relationType declaration of each exported relation in `lang`, with the
  schema `descriptions` of the DanNet relations."
  [lang descriptions obverse-of relations]
  (cons
    {:type             "synonym"
     :description      (get {"da" (str "Synonymi: betydningerne i relationen "
                                       "tilhører samme DanNet-synset.")
                             "en" (str "Synonymy: the senses in the relation "
                                       "belong to the same DanNet synset.")}
                            lang)
     :scopeRestriction "sameResource"
     :memberTypes      [{:role "synonym" :type "sense" :min 2 :hint "navigate"}]}
    (for [rel (sort-by name (keys relations))
          :let [obverse                  (obverse-of rel)
                [subject-role object-role] (relation-roles rel obverse)]]
      (cond-> {:type             (name rel)
               :scopeRestriction "sameResource"
               :sameAs           [(str (prefix/prefix->uri (symbol (namespace rel)))
                                       (name rel))]
               :memberTypes      (if (= rel obverse)
                                   [{:role subject-role :type "sense" :min 2 :hint "navigate"}]
                                   [{:role subject-role :type "sense" :min 1 :hint "navigate"}
                                    {:role object-role :type "sense" :min 1 :hint "navigate"}])}
        (descriptions rel) (assoc :description (descriptions rel))))))

(defn ->indices
  "Index the raw rows of `query-results` into the compact structures consumed
  by both language variants of the export.

  The symbol-keyed rows dominate the memory footprint of `run-queries` and can
  be released as soon as this returns; the indices share their strings and
  keywords."
  [{:keys [words senses definitions examples sources domains lexfiles
           word-variants ontological-types synset-labels short-labels
           member-labels polysemy genders sense-labels usage-notes ilis
           ili-definitions oewn-lemmas cor-links cor-forms sentiment
           eq-senses eq-sense-matches corsem-frames linked-synsets
           corsem-patterns pattern-labels centralities simple-ontotypes
           ontotype-members corsem-notes descriptions indegrees obverse-of
           relations]}]
  (let [listing-order (listing-order-fn indegrees)
        label-of      (merge (index synset-labels '?synset (comp str '?label))
                             (index short-labels '?synset (comp str '?label)))
        senses-of     (index-many senses '?synset (comp name '?sense))
        unambiguous   (unambiguous-ilis ilis)
        marks-of      (index-many sense-labels '?sense (comp name '?value))
        notes-of      (index-many usage-notes '?sense (comp str '?note))
        eq-of         (index eq-senses '?corsem '?sense)
        sense-syn     (index senses '?sense '?synset)
        centrality    (q/label-centralities centralities eq-sense-matches
                                            member-labels)
        ;; The atom sets behind the two ontological type systems, as name
        ;; sets, so a COR.SEM simple type can be compared with the type of
        ;; the matched sense's synset.
        syn-classes   (update-vals (group-by '?synset ontological-types)
                                   #(into #{} (map (comp name '?class)) %))
        stype-names   (update-vals (group-by '?ontotype ontotype-members)
                                   ontological-type)
        stypes-of     (->> (for [{:syms [?corsem ?ontotype]} simple-ontotypes
                                 :let [sense (eq-of ?corsem)]
                                 :when (and sense
                                            (not= (set (stype-names ?ontotype))
                                                  (syn-classes (sense-syn sense))))]
                             [sense (name ?ontotype)])
                           (reduce (fn [m [sense t]]
                                     (update m sense (fnil conj (sorted-set)) t))
                                   {}))
        patterns-of   (->> (for [{:syms [?corsem ?pattern]} corsem-patterns
                                 :let [sense (eq-of ?corsem)]
                                 :when sense]
                             [sense (name ?pattern)])
                           (reduce (fn [m [sense p]]
                                     (update m sense (fnil conj (sorted-set)) p))
                                   {}))
        ;; The one usable COR.SEM usage restriction: "frekvens" becomes the
        ;; note "sj. el. gl." in the corsem bootstrap, carried onto matched
        ;; senses with no usage information of their own. The "sprogbrug"
        ;; note only says that some unnamed restriction exists.
        frek-of       (into {} (for [{:syms [?sense ?note]} corsem-notes
                                     :let [note  (str ?note)
                                           sense (eq-of ?sense)]
                                     :when (and (= "sj. el. gl." note)
                                                sense
                                                (not (notes-of sense))
                                                (not (marks-of sense)))]
                                 [sense note]))
        frames-of     (frame-labels corsem-frames linked-synsets)]
    {:descriptions      descriptions
     :obverse-of        obverse-of
     :relations         relations
     :member-order      (member-order-fn
                          listing-order
                          (member-positions
                            centrality
                            (index polysemy (comp str '?senseLabel) '?polysemy)
                            member-labels))
     :pos-of            (index words '?word (comp pos-tag '?pos))
     :headword-of       (index words '?word (comp str '?writtenRep))
     :number-of         (homograph-numbers words)
     :definition-of     (index-many definitions '?synset (comp str '?definition))
     :examples-of       (index-many examples '?sense (comp str '?example))
     ;; A handful of subjects carry two dns:source URLs; the sorted vector of
     ;; index-many makes the pick of the first deterministic.
     :source-of         (update-vals (index-many sources '?s
                                                 (comp prefix/rdf-resource->uri
                                                       '?source))
                                     first)
     ;; The domain names are language-tagged full names (see the bootstrap
     ;; release change name-subject-domains!), so both domain indices key on
     ;; the language first.
     :domains-of        (update-vals (group-by (comp lstr/lang '?domain) domains)
                                     #(index-many % '?synset (comp str '?domain)))
     :domain-strings    (update-vals (group-by (comp lstr/lang '?domain) domains)
                                     #(vec (sort (set (map (comp str '?domain) %)))))
     :lexfiles-of       (index-many lexfiles '?synset (comp str '?lexfile))
     :lexfile-strings   (sort (set (map (comp str '?lexfile) lexfiles)))
     :variants-of       (index-many word-variants '?word (comp str '?variant))
     :ontotype-of       (index ontological-types '?synset (comp name '?bag))
     :ontotype-strings  (sort (set (map (comp name '?bag) ontological-types)))
     ;; One row per synset AND member, so the members of one bag repeat
     ;; once per synset that carries it and need the distinct projection.
     :ontotype-description-of (update-vals
                                (group-by (comp name '?bag)
                                          (distinct
                                            (map #(select-keys % '[?bag ?member ?class])
                                                 ontological-types)))
                                #(str/join " + " (ontological-type %)))
     :gender-of         (index genders '?synset '?gender)
     :marks-of          marks-of
     :sense-label-pairs (sort-by (comp name second)
                                 (distinct (map (juxt '?property '?value)
                                                sense-labels)))
     :notes-of          notes-of
     :note-strings      (sort (into (set (map (comp str '?note) usage-notes))
                                    (vals frek-of)))
     :frek-of           frek-of
     :frames-of         frames-of
     :frame-strings     (sort (into #{} (mapcat val) frames-of))
     :stypes-of         stypes-of
     :stype-strings     (sort (into #{} (mapcat val) stypes-of))
     :stype-description-of (into {}
                                 (map (fn [[t names]]
                                        [(name t) (str/join " + " names)]))
                                 stype-names)
     :patterns-of       patterns-of
     :pattern-strings   (sort (into #{} (mapcat val) patterns-of))
     :pattern-description-of (into {}
                                   (map (fn [{:syms [?pattern ?label]}]
                                          [(name ?pattern) (str ?label)]))
                                   pattern-labels)
     :centrality-of     (into {}
                              (for [{:syms [?corsem ?centrality]} centralities
                                    :let [sense (eq-of ?corsem)]
                                    :when sense]
                                [sense (str "centrality-" ?centrality)]))
     :corsem-id-of      (into {}
                              (map (fn [{:syms [?corsem ?sense]}]
                                     [?sense (name ?corsem)]))
                              eq-senses)
     :label-of          label-of
     :plain-label-of    (synset-descriptions
                          (select-keys label-of (keys senses-of)))
     :ili-of            (index-many unambiguous '?synset
                                    #(str prefix/ili-uri (name (get % '?ili))))
     :ili-key-of        (index unambiguous '?synset '?ili)
     :ili-definition-of (index ili-definitions '?ili (comp str '?definition))
     :english-of        (update-vals (group-by '?ili oewn-lemmas)
                                     #(vec (sort (distinct (map (comp str '?lemma) %)))))
     :senses-of         senses-of
     :cors-of           (cor-words cor-links)
     :forms-of          (update-vals (group-by '?cor cor-forms)
                                     #(into #{} (map ->cor-form) %))
     :description-of    (code-descriptions cor-forms)
     :sentiment-of      (sentiment-labels sentiment)
     ;; DMLex gives a sense no listingOrder, so the document order is the
     ;; sense order. The best-connected synset leads, as in the DanNet
     ;; search results.
     :word->senses      (->> (group-by '?word senses)
                             (sort-by (comp str key))
                             (mapv (fn [[word rows]]
                                     [word (->> rows
                                                (sort-by (juxt (comp listing-order '?synset)
                                                               (comp str '?sense)))
                                                (mapv (juxt '?sense '?synset)))])))}))

(defn ->resource
  "Build the DMLex intermediate structure in `lang` from the `indices`. A
  word without a written form is left out, since DMLex requires a headword. A
  word with an unusable part of speech keeps its entry, since DMLex does not
  require one."
  [lang {:keys [descriptions obverse-of relations member-order pos-of
                headword-of number-of definition-of examples-of source-of
                domains-of domain-strings lexfiles-of lexfile-strings variants-of
                ontotype-of ontotype-strings ontotype-description-of
                gender-of marks-of
                sense-label-pairs notes-of note-strings label-of
                plain-label-of ili-of ili-key-of ili-definition-of english-of
                senses-of cors-of forms-of description-of sentiment-of
                frek-of frames-of frame-strings stypes-of stype-strings
                stype-description-of patterns-of pattern-strings
                pattern-description-of centrality-of corsem-id-of
                word->senses]}]
  (let [descriptions   (get descriptions lang)
        domains-of     (get domains-of lang {})
        domain-strings (get domain-strings lang)
        ;; The English ILI definitions only supplement the English variant;
        ;; they describe the interlingual concept, not the DanNet sense.
        ili-def-of   (if (= lang "en")
                       (comp ili-definition-of ili-key-of)
                       (constantly nil))
        ->sense-map  (fn [headword [sense synset]]
                       (let [english     (english-of (ili-key-of synset))
                             ili-def     (ili-def-of synset)
                             definitions (cond-> (mapv (fn [text] {:text text})
                                                       (definition-of synset))
                                           ili-def
                                           (conj {:text           ili-def
                                                  :definitionType "ili"}))]
                         (cond-> {:id     (name sense)
                                  :labels (-> [(name synset)]
                                              (cond->
                                                (ontotype-of synset)
                                                (conj (str "dnt:" (ontotype-of synset))))
                                              (into (map #(str "cor.sem:" %))
                                                    (stypes-of sense))
                                              (into (lexfiles-of synset))
                                              (into (domains-of synset))
                                              (into (map #(str "frame:" %))
                                                    (frames-of synset))
                                              (into (map #(str "dnp:" %))
                                                    (patterns-of sense))
                                              (cond->
                                                (gender-of synset)
                                                (conj (name (gender-of synset))))
                                              (into (marks-of sense))
                                              (into (notes-of sense))
                                              (cond->
                                                (frek-of sense)
                                                (conj (frek-of sense)))
                                              (into (or (sentiment-of sense)
                                                        (sentiment-of synset)))
                                              (cond->
                                                (centrality-of sense)
                                                (conj (centrality-of sense)))
                                              (cond->
                                                (corsem-id-of sense)
                                                (conj (corsem-id-of sense)))
                                              (cond->
                                                (source-of sense)
                                                (conj (name sense))))}
                           (seq definitions)
                           (assoc :definitions definitions)

                           (examples-of sense)
                           (assoc :examples
                                  (mapv (fn [text]
                                          (cond-> {:text           text
                                                   :sourceIdentity "DDO"}
                                            (source-of sense)
                                            (assoc :sourceElaboration
                                                   (source-of sense))

                                            headword
                                            (assoc :headwordMarkers
                                                   (headword-markers headword text))))
                                        (examples-of sense)))

                           english
                           (assoc :headwordTranslations
                                  (mapv (fn [text] {:text text :langCode "en"})
                                        english)))))
        ->entry-map  (fn [[word rows]]
                       (let [headword  (headword-of word)
                             inflected (->> (matching-cor-words headword (cors-of word))
                                            (mapcat forms-of)
                                            (merge-forms)
                                            (sort-by (juxt (comp parse-long :tag) :text)))
                             texts     (into #{headword} (map :text) inflected)
                             variants  (for [v (distinct (variants-of word))
                                             :when (not (texts v))]
                                         {:text v})
                             forms     (into (vec inflected) variants)
                             labels    (cond-> (vec (sentiment-of word))
                                         (source-of word) (conj (name word)))]
                         (cond-> {:id       (name word)
                                  :headword headword
                                  :senses   (mapv (fn [sense indicator]
                                                    (cond-> sense
                                                      (seq indicator)
                                                      (assoc :indicator indicator)))
                                                  (mapv #(->sense-map headword %) rows)
                                                  (indicators (map (comp label-of second)
                                                                   rows)))}
                           (pos-of word) (assoc :partsOfSpeech [(pos-of word)])
                           (number-of word) (assoc :homographNumber (number-of word))
                           (seq labels) (assoc :labels labels)
                           (seq forms) (assoc :inflectedForms forms))))
        entries      (->> word->senses
                          (map ->entry-map)
                          (filterv :headword))
        ;; The senses of the exported entries, in entry order; shared by the
        ;; source and COR.SEM identity registers below.
        exported     (->> word->senses
                          (filter (comp headword-of first))
                          (mapcat (fn [[_ rows]]
                                    (map first rows)))
                          (distinct))]
    (merge (lexicographic-resource lang)
           {:entries            entries
            :definitionTypeTags (when (= lang "en")
                                  (localize lang definition-type-tags))
            :inflectedFormTags  (->> (mapcat :inflectedForms entries)
                                     (into #{} (keep :tag))
                                     (sort-by parse-long)
                                     (mapv (fn [code]
                                             (cond-> {:tag code :for "entry"}
                                               (description-of code)
                                               (assoc :description (description-of code))))))
            :labelTags          (concat
                                  (for [synset (sort-by name (keys senses-of))]
                                    (->synset-label-tag synset (plain-label-of synset) (ili-of synset)))
                                  (for [otype ontotype-strings]
                                    {:tag         (str "dnt:" otype)
                                     :typeTag     "ontologicalType"
                                     :for         "sense"
                                     :description (ontotype-description-of otype)
                                     :sameAs      [(str (prefix/prefix->uri 'dnt)
                                                        otype)]})
                                  (for [lexfile lexfile-strings]
                                    {:tag lexfile :typeTag "lexfile" :for "sense"})
                                  (for [domain domain-strings]
                                    {:tag domain :typeTag "domain" :for "sense"})
                                  (for [gender (sort-by name (set (vals gender-of)))]
                                    (->sense-label-tag "gender"
                                                       (descriptions gender)
                                                       gender))
                                  (for [[property value] sense-label-pairs]
                                    (->sense-label-tag (label-type-of property)
                                                       (descriptions value)
                                                       value))
                                  (for [note note-strings]
                                    {:tag note :typeTag "usage" :for "sense"})
                                  (localize lang norm-label-tags)
                                  (localize lang sentiment-label-tags)
                                  (localize lang centrality-label-tags)
                                  ;; The frame, ontological type and pattern
                                  ;; tags are prefixed: the bare names collide
                                  ;; across inventories (e.g. the frame and
                                  ;; the concept both named Substance), and
                                  ;; the XSD keys every labelTag on its tag
                                  ;; alone. The COR.SEM simple types share the
                                  ;; dnt: inventory with the standard types,
                                  ;; so their tags take a cor.sem: prefix and
                                  ;; state the shared resource in sameAs. The
                                  ;; description carries the readable name.
                                  (for [frame frame-strings]
                                    {:tag         (str "frame:" frame)
                                     :typeTag     "frame"
                                     :for         "sense"
                                     :description frame
                                     :sameAs      [(str prefix/framenet-uri frame)]})
                                  (for [stype stype-strings]
                                    {:tag         (str "cor.sem:" stype)
                                     :typeTag     "simpleOntologicalType"
                                     :for         "sense"
                                     :description (stype-description-of stype)
                                     :sameAs      [(str (prefix/prefix->uri 'dnt) stype)]})
                                  (for [pattern pattern-strings]
                                    {:tag         (str "dnp:" pattern)
                                     :typeTag     "polysemyPattern"
                                     :for         "sense"
                                     :description (pattern-description-of pattern)
                                     :sameAs      [(str (prefix/prefix->uri 'dnp) pattern)]})
                                  ;; The DDO source register of section 9.12:
                                  ;; one tag per sourced entry and sense, in
                                  ;; the order of the entries.
                                  (for [[word _] word->senses
                                        :when (and (headword-of word)
                                                   (source-of word))]
                                    (->source-label-tag "entry" word
                                                        (source-of word)))
                                  (for [sense exported
                                        :when (source-of sense)]
                                    (->source-label-tag "sense" sense
                                                        (source-of sense)))
                                  ;; The COR.SEM identity register: the id of
                                  ;; the exactly matching COR.SEM sense, with
                                  ;; the COR resource as sameAs.
                                  (for [sense exported
                                        :when (corsem-id-of sense)]
                                    {:tag     (corsem-id-of sense)
                                     :typeTag "corsem"
                                     :for     "sense"
                                     :sameAs  [(str prefix/cor-uri
                                                    (corsem-id-of sense))]}))
            :relations          (->relations senses-of member-order obverse-of relations)
            :relationTypes      (->relation-types lang descriptions obverse-of relations)})))

(defn license-comment
  "The text of an XML comment stating the licence of the DanNet `version`
  DMLex export.

  DMLex 1.0 has no slot for licence metadata (the XSD only allows title, uri
  and langCode on lexicographicResource, and the JSON schema closes the object
  with additionalProperties: false), so the XML carries the licence as a
  schema-transparent comment."
  [version]
  (str "\nDanNet " version " as DMLex.\n"
       "Combines DanNet and DDS (both CC BY-SA 4.0) with the CC0-licensed\n"
       "parts of COR and COR.SEM and English equivalents from the Open\n"
       "English Wordnet (CC BY 4.0). The combined dataset is licensed under\n"
       "CC BY-SA 4.0:\n"
       "https://creativecommons.org/licenses/by-sa/4.0/\n"
       "Copyright © Det Danske Sprog- og Litteraturselskab (DSL) and Center\n"
       "for Sprogteknologi (CST), University of Copenhagen. See README.txt\n"
       "for full attribution.\n"))

(defn export-metadata
  "Dataset metadata for the DanNet `version` DMLex export in `lang`, shipped in
  the zip as metadata.json (see `license-comment` for why nothing can go
  in-band). Mirrors the RDF metadata of the dn graph in Dublin Core terms so
  that e.g. a DMLex browser can consume it next to the DMLex JSON."
  [version lang]
  ;; array-map keeps this authored order in the serialized JSON
  (array-map
    "dc:title"       "DanNet"
    "dc:identifier"  prefix/dn-uri
    "dc:issued"      version
    "dc:language"    lang
    "dc:description" {"en" (str "The Danish WordNet, combined with inflected "
                                "forms from COR, sense ids, FrameNet frames "
                                "and systematic polysemy from COR.SEM, "
                                "sentiment polarities from DDS and English "
                                "equivalents from the Open English Wordnet.")
                      "da" (str "Det danske WordNet, kombineret med "
                                "bøjningsformer fra COR, betydnings-id'er, "
                                "FrameNet-rammer og systematisk polysemi fra "
                                "COR.SEM, sentiment-annoteringer fra DDS og "
                                "engelske ækvivalenter fra Open English "
                                "Wordnet.")}
    "dc:publisher"   (get {"en" (str "Centre for Language Technology, "
                                     "University of Copenhagen")
                           "da" (str "Center for Sprogteknologi, "
                                     "Københavns Universitet")}
                          lang)
    "dc:license"     "https://creativecommons.org/licenses/by-sa/4.0/"
    "dc:rights"      (get {"en" (str "Copyright © Centre for Language Technology "
                                     "(University of Copenhagen) & The Society "
                                     "for Danish Language and Literature; "
                                     "licensed under CC BY-SA 4.0.")
                           "da" (str "Copyright © Center for Sprogteknologi "
                                     "(Københavns Universitet) & Det Danske "
                                     "Sprog- og Litteraturselskab; licenseret "
                                     "under CC BY-SA 4.0.")}
                          lang)
    "dc:source"      [{"dc:title"      "DanNet"
                       "dc:identifier" prefix/dn-uri
                       "dc:license"    "https://creativecommons.org/licenses/by-sa/4.0/"}
                      {"dc:title"      "DDS (Det Danske Sentimentleksikon)"
                       "dc:identifier" prefix/dds-uri
                       "dc:license"    "https://creativecommons.org/licenses/by-sa/4.0/"}
                      {"dc:title"      "COR (Det Centrale Ordregister)"
                       "dc:identifier" "https://ordregister.dk"
                       "dc:license"    "https://creativecommons.org/publicdomain/zero/1.0/"}
                      {"dc:title"      "COR.SEM (the COR sense inventory)"
                       "dc:identifier" "https://ordregister.dk/sem"
                       "dc:license"    "https://creativecommons.org/publicdomain/zero/1.0/"}
                      {"dc:title"      "OEWN (Open English Wordnet)"
                       "dc:identifier" prefix/oewn-uri
                       "dc:license"    "https://creativecommons.org/licenses/by/4.0/"}]))

;; TODO: add the zip to the download page and the release pipeline (plan 9.6)
(defn export-dmlex!
  "Export a DMLex `resource` into `dir` as both XML and JSON, zipped together
  with the licence information, the dataset metadata and the presentation
  config. The :langCode of the resource picks the language variant of the
  companions and the name of the zip.

  The presentation config is DanNet's own display taste. DMLex has no slot for
  it, so it ships as a companion file like metadata.json. One file serves both
  variants: it names each tag in every language it has, and the viewer resolves
  the names to the language its reader picked."
  [dir {:keys [langCode] :as resource}]
  (println "Beginning DMLex export of DanNet into" dir)
  (let [xml-file     (str dir "dannet-dmlex-" langCode ".xml")
        json-file    (str dir "dannet-dmlex-" langCode ".json")
        meta-file    (str dir "metadata.json")
        present-file (str dir "presentation.json")
        license-file (str dir "LICENSE")
        readme-file  (str dir "README.txt")
        zip-path     (str dir (prefix/export-file "dmlex" 'dn langCode))]
    (io/make-parents xml-file)
    (write-xml! xml-file (license-comment release/to) resource)
    (write-json! json-file resource)
    (spit meta-file (with-out-str
                      (json/pprint (export-metadata release/to langCode)
                                   :escape-slash false
                                   :escape-unicode false)))
    (with-open [in (io/input-stream
                     (io/resource "bundled/dmlex/presentation.json"))]
      (io/copy in (io/file present-file)))
    (rdf/copy-license! :cc-by-sa license-file)
    (spit readme-file (rdf/render-readme "dannet-dmlex.txt" release/to))
    (zip/zip-files [xml-file json-file meta-file present-file license-file
                    readme-file]
                   zip-path))
  (println "DMLex export of DanNet complete!"))

(defn export-dmlex-variants!
  "Export both language variants of the DMLex zip into `dir` from `db`.

  The raw query rows are indexed once and shared between the variants, and
  each variant's resource is built and released in turn."
  [dir db]
  (let [indices (->indices (run-queries db))]
    (doseq [lang ["da" "en"]]
      (export-dmlex! dir (->resource lang indices)))))

(comment
  (time (export-dmlex-variants! "export/dmlex/" @dk.cst.dannet.web.instance/db))

  ;; Exploring the intermediate structure.
  (def indices
    (time (->indices (run-queries @dk.cst.dannet.web.instance/db))))

  (def resource
    (time (->resource "da" indices)))

  (count (:entries resource))
  (count (:labelTags resource))
  (count (:relations resource))
  (first (:entries resource))

  (time (export-dmlex! "export/dmlex/" resource))
  #_.)
