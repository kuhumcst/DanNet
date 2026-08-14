(ns dk.cst.dannet.db.export.dmlex
  "DMLex export functionality. See doc/dmlex/plan.md for the conversion rules.

  The intermediate structure is a single map using the DMLex property names as
  keys, e.g. {:langCode \"da\" :entries [{:headword \"hund\" :senses [...]}]}.
  It feeds both the XML and the JSON serializer, which differ in how they
  represent labels, parts of speech and sameAs URIs.

  The exported JSON can be browsed as a dictionary with the generic DMLex
  viewer at https://github.com/kuhumcst/dmlex-viewer."
  (:require [clojure.data.json :as json]
            [clojure.data.xml :as xml]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [clj-file-zip.core :as zip]
            [dk.cst.dannet.db :as db]
            [dk.cst.dannet.db.export.rdf :as rdf]
            [dk.cst.dannet.db.query :as q]
            [dk.cst.dannet.db.query.operation :as op]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.release :as release]))

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
        [["noun" "substantiv"]
         ["verb" "verbum"]
         ["adjective" "adjektiv"]]))

(def label-type-tags
  [{:tag         "synset"
    :description "DanNet synset"
    :sameAs      [(str (prefix/prefix->uri 'ontolex) "LexicalConcept")]}
   {:tag         "ontologicalType"
    :description "DanNet ontological type"
    :sameAs      [(str (prefix/prefix->uri 'dns) "ontologicalType")]}
   {:tag         "lexfile"
    :description "Semantisk felt fra WordNet, fx noun.animal"
    :sameAs      [(str (prefix/prefix->uri 'wn) "lexfile")]}
   {:tag         "domain"
    :description "Fagområde fra Den Danske Ordbog, fx zoo eller med"
    :sameAs      [(str (prefix/prefix->uri 'dc) "subject")]}
   {:tag         "gender"
    :description "The gender of the person that a DanNet synset denotes"
    :sameAs      [(str (prefix/prefix->uri 'dns) "gender")]}
   {:tag         "register"
    :description "Register, e.g. slang"
    :sameAs      [(str (prefix/prefix->uri 'lexinfo) "register")]}
   {:tag         "temporal"
    :description "Dating, e.g. old-fashioned"
    :sameAs      [(str (prefix/prefix->uri 'lexinfo) "dating")]}
   {:tag         "frequency"
    :description "Frequency of use"
    :sameAs      [(str (prefix/prefix->uri 'lexinfo) "frequency")]}
   {:tag         "usage"
    :description "Usage note from Den Danske Ordbog"
    :sameAs      [(str (prefix/prefix->uri 'lexinfo) "usageNote")]}
   {:tag         "norm"
    :description "Retskrivningsstatus for en bøjningsform"}
   {:tag         "sentiment"
    :description "Sentiment polarity from Det Danske Sentimentleksikon"
    :sameAs      [(str (prefix/prefix->uri 'marl) "hasPolarity")]}
   {:tag         "sentimentValue"
    :description "Sentiment value from -3 (negative) to 3 (positive)"
    :sameAs      [(str (prefix/prefix->uri 'marl) "polarityValue")]}])

(def label-type-of
  "DanNet sense property -> the DMLex labelTypeTag it belongs under."
  {:lexinfo/register  "register"
   :lexinfo/dating    "temporal"
   :lexinfo/frequency "frequency"})

(def sentiment-label-tags
  "The two sentiment inventories: the three polarities and the values from -3
  to 3. Neither declares `for`, since the labels go on entries and on senses."
  (concat
    (for [polarity ["Positive" "Neutral" "Negative"]]
      {:tag     polarity
       :typeTag "sentiment"
       :sameAs  [(str (prefix/prefix->uri 'marl) polarity)]})
    (for [value (range -3 4)]
      {:tag     (str value)
       :typeTag "sentimentValue"})))

(def norm-label-tags
  "The single norm-status tag. COR marks a form outside the spelling norm with
  an `unormeret: ` prefix on the label of the form."
  [{:tag         "unormeret"
    :typeTag     "norm"
    :for         "inflectedForm"
    :description "Bøjningsform uden for retskrivningsnormen"}])

(def source-identity-tags
  "The single source of the DanNet sense examples."
  [{:tag         "DDO"
    :description "Den Danske Ordbog"
    :sameAs      ["https://ordnet.dk/ddo"]}])

(def lexicographic-resource
  "The parts of the DMLex resource that do not come from the graph."
  {:title              "DanNet"
   :uri                prefix/dn-uri
   :langCode           "da"
   :labelTypeTags      label-type-tags
   :partOfSpeechTags   part-of-speech-tags
   :sourceIdentityTags source-identity-tags})

;; -----------------------------------------------------------------------------
;; Serialization

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

(defn tag-children
  "The shared child elements of the Controlled Values tag objects."
  [{:keys [description sameAs]}]
  (remove nil? (cons (->description description) (map ->same-as sameAs))))

(defn ->tag
  "The Controlled Values tag object `m` as the XML `element`, with the `ks` of
  `m` as its attributes."
  [element ks m]
  (into [element (attrs m ks)] (tag-children m)))

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

(defn ->example
  [{:keys [text labels] :as m}]
  (into [::dmlex/example (attrs m [:sourceIdentity :sourceElaboration :soundFile])
         [::dmlex/text text]]
        (map ->label labels)))

(defn ->sense
  [{:keys [indicator labels definitions examples] :as m}]
  (into [::dmlex/sense (attrs m [:id])]
        (concat (when indicator
                  [[::dmlex/indicator indicator]])
                (map ->label labels)
                (map ->definition definitions)
                (map ->example examples))))

(defn ->entry
  [{:keys [headword partsOfSpeech labels inflectedForms senses] :as m}]
  (into [::dmlex/entry (attrs m [:id :homographNumber])
         [::dmlex/headword headword]]
        (concat (map ->part-of-speech partsOfSpeech)
                (map ->label labels)
                (map ->inflected-form inflectedForms)
                (map ->sense senses))))

(defn ->lexicographic-resource
  "Build the XML element tree of a DMLex `resource`. The child order follows the
  sequences of dmlex_no-crosslingual.xsd."
  [{:keys [entries inflectedFormTags labelTags labelTypeTags partOfSpeechTags
           sourceIdentityTags relations relationTypes]
    :as   resource}]
  (into [::dmlex/lexicographicResource (merge {:xmlns dmlex-uri}
                                              (attrs resource [:title :uri :langCode]))]
        (concat (map ->entry entries)
                (map (partial ->tag ::dmlex/inflectedFormTag [:tag :for]) inflectedFormTags)
                (map (partial ->tag ::dmlex/labelTag [:tag :typeTag :for]) labelTags)
                (map (partial ->tag ::dmlex/labelTypeTag [:tag]) labelTypeTags)
                (map (partial ->tag ::dmlex/partOfSpeechTag [:tag :for]) partOfSpeechTags)
                (map (partial ->tag ::dmlex/sourceIdentityTag [:tag]) sourceIdentityTags)
                (map ->relation relations)
                (map ->relation-type relationTypes))))

(defn xml-str
  "Create a DMLex XML string from a DMLex `resource`."
  [resource]
  (-> (->lexicographic-resource resource)
      (xml/sexp-as-element)
      (xml/indent-str)))

(defn prune
  "Remove nil values and empty collections from the maps inside `x`."
  [x]
  (walk/postwalk
    (fn [v]
      (if (map? v)
        (into (empty v)
              (remove (fn [[_ v']]
                        (or (nil? v')
                            (and (coll? v') (empty? v')))))
              v)
        v))
    x))

;; TODO: report the homographNumber mismatch to the LEXIDMA committee
;; dmlex_no-crosslingual.xsd types the attribute as xs:integer while
;; dmlex_no-crosslingual.schema.json types the property as a string, so no
;; single value serializes into both. Drop this coercion once they agree.

(defn json-safe
  "The two schemas disagree on one property: `homographNumber` is an integer in
  the XSD and a string in the JSON schema."
  [resource]
  (update resource :entries
          (fn [entries]
            (mapv #(cond-> %
                     (:homographNumber %) (update :homographNumber str))
                  entries))))

(defn json-str
  "Create a DMLex JSON string from a DMLex `resource`."
  [resource]
  (json/write-str (prune (json-safe resource))
                  :key-fn name
                  :escape-slash false
                  :escape-unicode false))

;; -----------------------------------------------------------------------------
;; Extraction
;;
;; Every query runs on the raw DanNet graph. The inference graph materialises
;; both directions of every inverse relation as well as the transitive closure
;; of e.g. wn:hypernym, none of which was stated by a lexicographer.

(def word-query
  (op/sparql
    "SELECT ?word ?pos ?writtenRep
     WHERE {
       ?word ontolex:canonicalForm ?form ;
             wn:partOfSpeech ?pos .
       ?form ontolex:writtenRep ?writtenRep .
     }"))

(def sense-query
  (op/sparql
    "SELECT ?word ?sense ?synset
     WHERE {
       ?word ontolex:sense ?sense .
       ?synset ontolex:lexicalizedSense ?sense .
     }"))

(def definition-query
  (op/sparql
    "SELECT ?synset ?definition
     WHERE { ?synset skos:definition ?definition . }"))

(def example-query
  (op/sparql
    "SELECT ?sense ?example
     WHERE { ?sense lexinfo:senseExample ?example . }"))

(def domain-query
  (op/sparql
    "SELECT ?synset ?domain
     WHERE { ?synset dc:subject ?domain . }"))

(def lexfile-query
  (op/sparql
    "SELECT ?synset ?lexfile
     WHERE { ?synset wn:lexfile ?lexfile . }"))

(def variant-query
  "The written variants of DanNet's own multiword expressions."
  (op/sparql
    "SELECT ?word ?variant
     WHERE {
       ?word ontolex:otherForm ?form .
       ?form ontolex:writtenRep ?variant .
     }"))

(def ontological-type-query
  (op/sparql
    "SELECT ?synset ?member ?class
     WHERE {
       ?synset dns:ontologicalType ?bag .
       ?bag ?member ?class .
       FILTER(STRSTARTS(str(?member), CONCAT(str(rdf:), \"_\"))) .
     }"))

(defn ontological-type
  "The member concepts of one synset's ontological type, e.g.
  [\"LanguageRepresentation\" \"Artifact\" \"Object\"]. The `rows` are the
  members of one rdf:Bag, which the rdf:_N index puts back in order."
  [rows]
  (->> rows
       (sort-by #(parse-long (subs (name (get % '?member)) 1)))
       (mapv (comp name '?class))))

(def synset-label-query
  (op/sparql
    "SELECT ?synset ?label
     WHERE {
       ?synset rdfs:label ?label .
       FILTER(STRSTARTS(str(?synset), CONCAT(str(dn:), \"synset\"))) .
     }"))

(def short-label-query
  (op/sparql
    "SELECT ?synset ?label
     WHERE { ?synset dns:shortLabel ?label . }"))

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

(defn tag-descriptions
  "Descriptions of `concepts`, from the schema statements in `g`. A comment is
  more informative than a label, and Danish beats English in a Danish resource."
  [g concepts]
  (let [values (str/join " " (map prefix/kw->qname concepts))
        rows   (q/run g (op/sparql
                          "SELECT ?concept ?comment ?label ?enLabel
                           WHERE {
                             VALUES ?concept { " values " }
                             OPTIONAL { ?concept rdfs:comment ?comment .
                                        FILTER(LANG(?comment) = \"da\") . }
                             OPTIONAL { ?concept rdfs:label ?label .
                                        FILTER(LANG(?label) = \"da\") . }
                             OPTIONAL { ?concept rdfs:label ?enLabel .
                                        FILTER(LANG(?enLabel) = \"en\") . }
                           }"))]
    (into {} (for [{:syms [?concept ?comment ?label ?enLabel]} rows
                   :let [description (or ?comment ?label ?enLabel)]
                   :when description]
               [?concept (str description)]))))

(def inverse-query
  (op/sparql
    "SELECT ?a ?b WHERE { ?a owl:inverseOf ?b . }"))

(defn inverse-relations
  "DanNet relation -> its obverse relation, from the `owl:inverseOf` statements
  of the schema graph `g`. A symmetric relation maps to itself."
  [g]
  (into {} (comp (map (juxt '?a '?b))
                 (filter (fn [[a b]] (and (keyword? a) (keyword? b)))))
        (q/run g inverse-query)))

(defn relation-query
  [rel]
  (op/sparql "SELECT ?subject ?object
              WHERE { ?subject " (prefix/kw->qname rel) " ?object . }"))

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
  (let [rows      (map (juxt '?subject '?object) (q/run g (relation-query rel)))
        symmetric (= rel obverse)
        flipped   (when (and obverse (not symmetric))
                    (map (juxt '?object '?subject) (q/run g (relation-query obverse))))]
    (into #{}
          (comp (filter dannet-pair?)
                (remove (fn [[a b]] (= a b)))
                (map (if symmetric #(vec (sort-by name %)) vec)))
          (concat rows flipped))))

(def sense-label-query
  (op/sparql
    "SELECT ?sense ?property ?value
     WHERE {
       VALUES ?property { lexinfo:register lexinfo:dating lexinfo:frequency }
       ?sense ?property ?value .
     }"))

(def usage-note-query
  (op/sparql
    "SELECT ?sense ?note
     WHERE { ?sense lexinfo:usageNote ?note . }"))

(def gender-query
  (op/sparql
    "SELECT ?synset ?gender
     WHERE { ?synset dns:gender ?gender . }"))

(def ili-query
  (op/sparql
    "SELECT ?synset ?ili
     WHERE { ?synset wn:ili ?ili . }"))

(def cor-link-query
  "The owl:sameAs links from COR words to DanNet words, with the COR lemmas.
  COR states each link in both directions; selecting the COR-to-DanNet
  direction gives each pair once."
  (op/sparql
    "SELECT ?cor ?word ?lemma
     WHERE {
       ?cor owl:sameAs ?word ;
            ontolex:canonicalForm ?canonical .
       ?canonical ontolex:writtenRep ?lemma .
       FILTER(STRSTARTS(str(?word), str(dn:))) .
     }"))

(def cor-form-query
  (op/sparql
    "SELECT ?cor ?form ?writtenRep ?label
     WHERE {
       ?cor ontolex:otherForm ?form .
       ?form ontolex:writtenRep ?writtenRep ;
             rdfs:label ?label .
     }"))

(def sentiment-query
  (op/sparql
    "SELECT ?subject ?polarity ?value
     WHERE {
       ?subject dns:sentiment ?opinion .
       ?opinion marl:hasPolarity ?polarity .
       OPTIONAL { ?opinion marl:polarityValue ?value . }
     }"))

(defn run-queries
  "Fetch the DMLex source data from `db`. Everything but the schema statements
  comes from the raw graphs, which hold only what the DanNet releases state."
  [{:keys [dataset graph]}]
  (let [g            (db/get-graph dataset prefix/dn-uri)
        cor-g        (db/get-graph dataset prefix/cor-uri)
        dds-g        (db/get-graph dataset prefix/dds-uri)
        obverse-of   (inverse-relations graph)
        types        (q/run g ontological-type-query)
        genders      (q/run g gender-query)
        sense-labels (q/run g sense-label-query)
        concepts     (concat (map '?gender genders)
                             (map '?value sense-labels)
                             (map '?class types)
                             exported-relations)]
    {:words             (q/run g word-query)
     :senses            (q/run g sense-query)
     :definitions       (q/run g definition-query)
     :examples          (q/run g example-query)
     :domains           (q/run g domain-query)
     :lexfiles          (q/run g lexfile-query)
     :word-variants     (q/run g variant-query)
     :ontological-types types
     :synset-labels     (q/run g synset-label-query)
     :short-labels      (q/run g short-label-query)
     :genders           genders
     :sense-labels      sense-labels
     :usage-notes       (q/run g usage-note-query)
     :ilis              (q/run g ili-query)
     :cor-links         (q/run cor-g cor-link-query)
     :cor-forms         (q/run cor-g cor-form-query)
     :sentiment         (concat (q/run dds-g sentiment-query)
                                (q/run g sentiment-query))
     :descriptions      (tag-descriptions graph (distinct concepts))
     :obverse-of        obverse-of
     :relations         (into {}
                              (map (fn [rel]
                                     [rel (relation-pairs g rel (obverse-of rel))]))
                              exported-relations)}))

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
  form, e.g. \"hundenes\"-form (sb.fk.pl.best.gen). An `unormeret: ` prefix
  marks a form outside the spelling norm and is not part of the code, so it is
  removed. A code whose labels disagree gets no description."
  [cor-forms]
  (let [label #(some-> (re-find #"\(([^)]*)\)\s*$" (str (get % '?label)))
                       (second)
                       (str/replace #"^unormeret: " ""))]
    (->> (group-by (comp inflection-code '?form) cor-forms)
         (keep (fn [[code rows]]
                 (let [labels (into #{} (keep label) rows)]
                   (when (= 1 (count labels))
                     [code (first labels)]))))
         (into {}))))

(defn ->cor-form
  "The DMLex inflectedForm of one COR form row, with the norm status of the
  form as a label. The `unormeret: ` prefix inside the parenthesized part of
  the rdfs:label marks a form outside the spelling norm."
  [{:syms [?form ?writtenRep ?label]}]
  (cond-> {:tag  (inflection-code ?form)
           :text (str ?writtenRep)}
    (str/includes? (str ?label) "(unormeret: ") (assoc :labels ["unormeret"])))

(defn merge-forms
  "Deduplicate the inflected `forms` of one entry on the pair of tag and text,
  which the XSD makes unique. A merged form stays outside the spelling norm
  only when every copy is."
  [forms]
  (for [[[tag text] copies] (group-by (juxt :tag :text) forms)]
    (cond-> {:tag tag :text text}
      (every? :labels copies) (assoc :labels ["unormeret"]))))

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

(defn ->synset-label-tag
  "The labelTag that gives a `synset` its identity, with its `description` and
  its `ilis` as extra sameAs URIs."
  [synset description ilis]
  (cond-> {:tag     (name synset)
           :typeTag "synset"
           :for     "sense"
           :sameAs  (into [(str prefix/dn-uri (name synset))] ilis)}
    description (assoc :description description)))

(defn synset-indicator
  "A DMLex sense indicator from a synset `label`: the member words without the
  braces and the DDO sense markers, e.g. {hund_§1a; køter_§2} -> hund, køter."
  [label]
  (-> label
      (str/replace #"^\{|\}$" "")
      (str/replace #"_[0-9§]+[a-z]?" "")
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

(defn relation-roles
  "The member roles of `rel`: the role of the subject end and of the object end.
  A pair of inverse relations uses the two relation names. A symmetric relation
  uses one name for both ends. A relation with no declared obverse has no second
  name available, so it uses source and target."
  [rel obverse]
  (cond
    (nil? obverse)  ["source" "target"]
    (= rel obverse) [(name rel) (name rel)]
    :else           [(name obverse) (name rel)]))

(defn ->members
  [senses-of [subject object] [subject-role object-role]]
  (concat (for [sense (senses-of subject)]
            {:ref sense :role subject-role})
          (for [sense (senses-of object)]
            {:ref sense :role object-role})))

(defn ->relations
  "One DMLex relation for each synset pair, plus one synonym relation for each
  synset of two or more senses. Every member is a sense, so a pair with a
  senseless synset at one end produces nothing."
  [senses-of obverse-of relations]
  (concat
    (for [[synset senses] (sort-by (comp name key) senses-of)
          :when (next senses)]
      {:type    "synonym"
       :members (for [sense senses] {:ref sense :role "synonym"})})
    (for [[rel pairs] (sort-by (comp name key) relations)
          :let [roles (relation-roles rel (obverse-of rel))]
          pair (sort-by (partial mapv name) pairs)
          :when (every? (comp seq senses-of) pair)]
      {:type    (name rel)
       :members (->members senses-of pair roles)})))

(defn ->relation-types
  "The relationType declaration of each exported relation, with the schema
  `descriptions` of the DanNet relations."
  [descriptions obverse-of relations]
  (cons
    {:type             "synonym"
     :description      "Synonymi: betydningerne i relationen tilhører samme DanNet-synset."
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

(defn ->resource
  "Build the DMLex intermediate structure from `query-results`. A word without a
  written form is left out, since DMLex requires a headword. A word with an
  unusable part of speech keeps its entry, since DMLex does not require one."
  [{:keys [words senses definitions examples domains lexfiles word-variants
           ontological-types synset-labels short-labels genders sense-labels
           usage-notes ilis cor-links cor-forms sentiment descriptions
           obverse-of relations]}]
  (let [pos-of         (index words '?word (comp pos-tag '?pos))
        headword-of    (index words '?word (comp str '?writtenRep))
        number-of      (homograph-numbers words)
        definition-of  (index-many definitions '?synset (comp str '?definition))
        examples-of    (index-many examples '?sense (comp str '?example))
        domains-of     (index-many domains '?synset (comp str '?domain))
        lexfiles-of    (index-many lexfiles '?synset (comp str '?lexfile))
        variants-of    (index-many word-variants '?word (comp str '?variant))
        ontotype-of    (update-vals (group-by '?synset ontological-types)
                                    ontological-type)
        gender-of      (index genders '?synset '?gender)
        marks-of       (index-many sense-labels '?sense (comp name '?value))
        notes-of       (index-many usage-notes '?sense (comp str '?note))
        label-of       (merge (index synset-labels '?synset (comp str '?label))
                              (index short-labels '?synset (comp str '?label)))
        ili-of         (index-many (unambiguous-ilis ilis) '?synset
                                   #(str prefix/ili-uri (name (get % '?ili))))
        senses-of      (index-many senses '?synset (comp name '?sense))
        cors-of        (cor-words cor-links)
        forms-of       (update-vals (group-by '?cor cor-forms)
                                    #(into #{} (map ->cor-form) %))
        description-of (code-descriptions cor-forms)
        sentiment-of   (sentiment-labels sentiment)
        ->sense        (fn [{:syms [?sense ?synset]}]
                         (cond-> {:id     (name ?sense)
                                  :labels (-> [(name ?synset)]
                                              (into (ontotype-of ?synset))
                                              (into (lexfiles-of ?synset))
                                              (into (domains-of ?synset))
                                              (cond->
                                                (gender-of ?synset)
                                                (conj (name (gender-of ?synset))))
                                              (into (marks-of ?sense))
                                              (into (notes-of ?sense))
                                              (into (or (sentiment-of ?sense)
                                                        (sentiment-of ?synset))))}
                           (definition-of ?synset)
                           (assoc :definitions (mapv (fn [text] {:text text})
                                                     (definition-of ?synset)))

                           (examples-of ?sense)
                           (assoc :examples (mapv (fn [text]
                                                    {:text           text
                                                     :sourceIdentity "DDO"})
                                                  (examples-of ?sense)))))
        ->entry        (fn [[word ms]]
                         (let [headword  (headword-of word)
                               rows      (sort-by (comp str '?sense) ms)
                               inflected (->> (matching-cor-words headword (cors-of word))
                                              (mapcat forms-of)
                                              (merge-forms)
                                              (sort-by (juxt (comp parse-long :tag) :text)))
                               texts     (into #{headword} (map :text) inflected)
                               variants  (for [v (distinct (variants-of word))
                                               :when (not (texts v))]
                                           {:text v})
                               forms     (into (vec inflected) variants)]
                           (cond-> {:id       (name word)
                                    :headword headword
                                    :senses   (mapv (fn [sense indicator]
                                                      (cond-> sense
                                                        (seq indicator)
                                                        (assoc :indicator indicator)))
                                                    (mapv ->sense rows)
                                                    (indicators (map (comp label-of '?synset)
                                                                     rows)))}
                             (pos-of word) (assoc :partsOfSpeech [(pos-of word)])
                             (number-of word) (assoc :homographNumber (number-of word))
                             (sentiment-of word) (assoc :labels (sentiment-of word))
                             (seq forms) (assoc :inflectedForms forms))))
        entries        (->> (group-by '?word senses)
                            (sort-by (comp str key))
                            (map ->entry)
                            (filterv :headword))]
    (merge lexicographic-resource
           {:entries           entries
            :inflectedFormTags (->> (mapcat :inflectedForms entries)
                                    (into #{} (keep :tag))
                                    (sort-by parse-long)
                                    (mapv (fn [code]
                                            (cond-> {:tag code :for "entry"}
                                              (description-of code)
                                              (assoc :description (description-of code))))))
            :labelTags         (concat
                                 (for [synset (sort-by name (keys senses-of))]
                                   (->synset-label-tag synset (label-of synset) (ili-of synset)))
                                 (for [concept (sort-by name (distinct (map '?class ontological-types)))]
                                   (->sense-label-tag "ontologicalType"
                                                      (descriptions concept)
                                                      concept))
                                 (for [lexfile (sort (set (map (comp str '?lexfile) lexfiles)))]
                                   {:tag lexfile :typeTag "lexfile" :for "sense"})
                                 (for [domain (sort (set (map (comp str '?domain) domains)))]
                                   {:tag domain :typeTag "domain" :for "sense"})
                                 (for [gender (sort-by name (set (vals gender-of)))]
                                   (->sense-label-tag "gender"
                                                      (descriptions gender)
                                                      gender))
                                 (for [{:syms [?property ?value]}
                                       (sort-by (comp name '?value)
                                                (distinct (map #(select-keys % '[?property ?value])
                                                               sense-labels)))]
                                   (->sense-label-tag (label-type-of ?property)
                                                      (descriptions ?value)
                                                      ?value))
                                 (for [note (sort (set (map (comp str '?note) usage-notes)))]
                                   {:tag note :typeTag "usage" :for "sense"})
                                 norm-label-tags
                                 sentiment-label-tags)
            :relations         (->relations senses-of obverse-of relations)
            :relationTypes     (->relation-types descriptions obverse-of relations)})))

(defn license-comment
  "An XML comment stating the licence of the DanNet `version` DMLex export.

  DMLex 1.0 has no slot for licence metadata — the XSD only allows title, uri
  and langCode on lexicographicResource, and the JSON schema closes the object
  with additionalProperties: false — so the XML carries the licence as a
  schema-transparent comment."
  [version]
  (str "<!--\n"
       "DanNet " version " as DMLex.\n"
       "Combines DanNet and DDS (both CC BY-SA 4.0) with the CC0-licensed\n"
       "parts of COR. The combined dataset is licensed under CC BY-SA 4.0:\n"
       "https://creativecommons.org/licenses/by-sa/4.0/\n"
       "Copyright © Det Danske Sprog- og Litteraturselskab (DSL) and Center\n"
       "for Sprogteknologi (CST), University of Copenhagen. See README.txt\n"
       "for full attribution.\n"
       "-->\n"))

(defn export-metadata
  "Dataset metadata for the DanNet `version` DMLex export, shipped in the zip
  as metadata.json (see `license-comment` for why nothing can go in-band).
  Mirrors the RDF metadata of the dn graph in Dublin Core terms so that e.g.
  a DMLex viewer can consume it next to the DMLex JSON."
  [version]
  ;; array-map keeps this authored order in the serialized JSON
  (array-map
    "dc:title"       "DanNet"
    "dc:identifier"  prefix/dn-uri
    "dc:issued"      version
    "dc:language"    "da"
    "dc:description" {"en" (str "The Danish WordNet, combined with inflected "
                                "forms from COR and sentiment polarities "
                                "from DDS.")
                      "da" (str "Det danske WordNet, kombineret med "
                                "bøjningsformer fra COR og sentiment-"
                                "annoteringer fra DDS.")}
    "dc:publisher"   "Centre for Language Technology, University of Copenhagen"
    "dc:license"     "https://creativecommons.org/licenses/by-sa/4.0/"
    "dc:rights"      (str "Copyright © Centre for Language Technology "
                          "(University of Copenhagen) & The Society for Danish "
                          "Language and Literature; licensed under "
                          "CC BY-SA 4.0.")
    "dc:source"      [{"dc:title"   "DanNet"
                       "dc:license" "https://creativecommons.org/licenses/by-sa/4.0/"}
                      {"dc:title"   "DDS (Det Danske Sentimentleksikon)"
                       "dc:license" "https://creativecommons.org/licenses/by-sa/4.0/"}
                      {"dc:title"   "COR (Det Centrale Ordregister)"
                       "dc:license" "https://creativecommons.org/publicdomain/zero/1.0/"}]))

;; TODO: add the zip to the download page and the release pipeline (plan 9.6)
(defn export-dmlex!
  "Export a DMLex `resource` into `dir` as both XML and JSON, zipped together
  with the licence information and dataset metadata."
  [dir resource]
  (println "Beginning DMLex export of DanNet into" dir)
  (let [xml-file     (str dir "dannet-dmlex.xml")
        json-file    (str dir "dannet-dmlex.json")
        meta-file    (str dir "metadata.json")
        license-file (str dir "LICENSE")
        readme-file  (str dir "README.txt")
        zip-path     (str dir (prefix/export-file "dmlex" 'dn))]
    (io/make-parents xml-file)
    (spit xml-file (str/replace-first (xml-str resource) "?>\n"
                                      (str "?>\n" (license-comment release/to))))
    (spit json-file (json-str resource))
    (spit meta-file (with-out-str
                      (json/pprint (export-metadata release/to)
                                   :escape-slash false
                                   :escape-unicode false)))
    (rdf/copy-license! :cc-by-sa license-file)
    (spit readme-file (rdf/render-readme "dannet-dmlex.txt" release/to))
    (zip/zip-files [xml-file json-file meta-file license-file readme-file]
                   zip-path))
  (println "DMLex export of DanNet complete!"))

(comment
  (def query-results
    (time (run-queries @dk.cst.dannet.web.instance/db)))

  (def resource
    (time (->resource query-results)))

  (count (:entries resource))
  (count (:labelTags resource))
  (count (:relations resource))
  (first (:entries resource))

  (time (export-dmlex! "export/dmlex/" resource))
  #_.)
