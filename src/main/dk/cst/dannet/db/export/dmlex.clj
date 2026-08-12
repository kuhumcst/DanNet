(ns dk.cst.dannet.db.export.dmlex
  "DMLex export functionality. See doc/dmlex/plan.md for the conversion rules.

  The intermediate structure is a single map using the DMLex property names as
  keys, e.g. {:langCode \"da\" :entries [{:headword \"hund\" :senses [...]}]}.
  It feeds both the XML and the JSON serializer, which differ in how they
  represent labels, parts of speech and sameAs URIs."
  (:require [clojure.data.json :as json]
            [clojure.data.xml :as xml]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [dk.cst.dannet.db :as db]
            [dk.cst.dannet.db.query :as q]
            [dk.cst.dannet.db.query.operation :as op]
            [dk.cst.dannet.prefix :as prefix]))

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
    :sameAs      [(str (prefix/prefix->uri 'lexinfo) "usageNote")]}])

(def label-type-of
  "DanNet sense property -> the DMLex labelTypeTag it belongs under."
  {:lexinfo/register  "register"
   :lexinfo/dating    "temporal"
   :lexinfo/frequency "frequency"})

(def lexicographic-resource
  "The parts of the DMLex resource that do not come from the graph."
  {:title            "DanNet"
   :uri              prefix/dn-uri
   :langCode         "da"
   :labelTypeTags    label-type-tags
   :partOfSpeechTags part-of-speech-tags})

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

(defn ->part-of-speech-tag
  [m]
  (into [::dmlex/partOfSpeechTag (attrs m [:tag :for])]
        (tag-children m)))

(defn ->label-type-tag
  [m]
  (into [::dmlex/labelTypeTag (attrs m [:tag])]
        (tag-children m)))

(defn ->label-tag
  [m]
  (into [::dmlex/labelTag (attrs m [:tag :typeTag :for])]
        (tag-children m)))

(defn ->member-type
  [m]
  (into [::dmlex/memberType (attrs m [:role :type :min :max :hint])]
        (tag-children m)))

(defn ->relation-type
  [{:keys [description memberTypes sameAs] :as m}]
  (into [::dmlex/relationType (attrs m [:type :scopeRestriction])]
        (concat (remove nil? [(->description description)])
                (map ->member-type memberTypes)
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
  [{:keys [headword partsOfSpeech labels senses] :as m}]
  (into [::dmlex/entry (attrs m [:id :homographNumber])
         [::dmlex/headword headword]]
        (concat (map ->part-of-speech partsOfSpeech)
                (map ->label labels)
                (map ->sense senses))))

(defn ->lexicographic-resource
  "Build the XML element tree of a DMLex `resource`. The child order follows the
  sequences of dmlex_no-crosslingual.xsd."
  [{:keys [entries labelTags labelTypeTags partOfSpeechTags
           relations relationTypes]
    :as   resource}]
  (into [::dmlex/lexicographicResource (merge {:xmlns dmlex-uri}
                                              (attrs resource [:title :uri :langCode]))]
        (concat (map ->entry entries)
                (map ->label-tag labelTags)
                (map ->label-type-tag labelTypeTags)
                (map ->part-of-speech-tag partOfSpeechTags)
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

(def ontological-type-query
  (op/sparql
    "SELECT ?synset ?member ?class
     WHERE {
       ?synset dns:ontologicalType ?bag .
       ?bag ?member ?class .
       FILTER(STRSTARTS(str(?member), CONCAT(str(rdf:), \"_\"))) .
     }"))

(defn ontological-type
  "The ontological type of a synset as DanNet writes it, e.g.
  {LanguageRepresentation; Artifact; Object}. The `rows` are the members of one
  rdf:Bag, which the rdf:_N index puts back in order."
  [rows]
  (->> rows
       (sort-by #(parse-long (subs (name (get % '?member)) 1)))
       (map (comp name '?class))
       (str/join "; ")
       (format "{%s}")))

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

;; TODO: decide whether the sentiment data belongs in DMLex at all
;; The 181 dns:sentiment statements come from Det Danske Sentimentleksikon
;; rather than from DanNet proper, so they stay out of the export for now. They
;; would fit the label mechanism: a labelTypeTag with Positive and Negative
;; tags, reached through the marl:hasPolarity property of the blank node.

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

(defn run-queries
  "Fetch the DMLex source data from `db`. Everything but the schema statements
  comes from the raw DanNet graph, which holds only what a lexicographer
  stated."
  [{:keys [dataset graph]}]
  (let [g            (db/get-graph dataset prefix/dn-uri)
        obverse-of   (inverse-relations graph)
        types        (q/run g ontological-type-query)
        genders      (q/run g gender-query)
        sense-labels (q/run g sense-label-query)
        concepts     (concat (map '?gender genders) (map '?value sense-labels))]
    {:words             (q/run g word-query)
     :senses            (q/run g sense-query)
     :definitions       (q/run g definition-query)
     :examples          (q/run g example-query)
     :ontological-types types
     :synset-labels     (q/run g synset-label-query)
     :short-labels      (q/run g short-label-query)
     :genders           genders
     :sense-labels      sense-labels
     :usage-notes       (q/run g usage-note-query)
     :ilis              (q/run g ili-query)
     :descriptions      (tag-descriptions graph (distinct concepts))
     :obverse-of        obverse-of
     :relations         (into {}
                              (map (fn [rel]
                                     [rel (relation-pairs g rel (obverse-of rel))]))
                              exported-relations)}))

(defn index
  "Index the query result `ms` as a map of `k` to a single `v` value."
  [ms k v]
  (into {} (map (juxt k v)) ms))

(defn index-many
  "Index the query result `ms` as a map of `k` to a sorted vector of `v` values."
  [ms k v]
  (update-vals (group-by k ms) #(vec (sort (map v %)))))

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

(defn ->synset-label-tag
  "The labelTag that gives a `synset` its identity."
  [synset {:keys [description ilis]}]
  (cond-> {:tag     (name synset)
           :typeTag "synset"
           :for     "sense"
           :sameAs  (into [(str prefix/dn-uri (name synset))] ilis)}
    description (assoc :description description)))

;; TODO: reconsider the composite ontological type tag
;; The alternative is one tag for each DanNet concept, which keeps a sameAs URI
;; and a Danish description on every concept, but loses the bag as a unit and
;; its order. Neither option is clearly better.

(defn ->ontological-type-label-tag
  "One labelTag for a composite ontological type. It has no `sameAs` URI: the
  composite is a set of DanNet concepts, and a `sameAs` URI for each member
  would claim that the composite is the same as each of its parts."
  [ontotype]
  {:tag     ontotype
   :typeTag "ontologicalType"
   :for     "sense"})

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
  synset of two or more senses. A relation needs two members, so a pair with a
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
          :let [members (->members senses-of pair roles)]
          :when (next members)]
      {:type    (name rel)
       :members members})))

(defn ->relation-types
  "The relationType declaration of each exported relation."
  [obverse-of relations]
  (cons
    {:type             "synonym"
     :scopeRestriction "sameResource"
     :memberTypes      [{:role "synonym" :type "sense" :min 2 :hint "navigate"}]}
    (for [rel (sort-by name (keys relations))
          :let [obverse                  (obverse-of rel)
                [subject-role object-role] (relation-roles rel obverse)]]
      {:type             (name rel)
       :scopeRestriction "sameResource"
       :sameAs           [(str (prefix/prefix->uri (symbol (namespace rel)))
                               (name rel))]
       :memberTypes      (if (= rel obverse)
                           [{:role subject-role :type "sense" :min 2 :hint "navigate"}]
                           [{:role subject-role :type "sense" :min 1 :hint "navigate"}
                            {:role object-role :type "sense" :min 1 :hint "navigate"}])})))

(defn ->resource
  "Build the DMLex intermediate structure from `query-results`. A word without a
  written form is left out, since DMLex requires a headword. A word with an
  unusable part of speech keeps its entry, since DMLex does not require one."
  [{:keys [words senses definitions examples ontological-types
           synset-labels short-labels genders sense-labels usage-notes
           ilis descriptions obverse-of relations]}]
  (let [pos-of        (index words '?word (comp pos-tag '?pos))
        headword-of   (index words '?word (comp str '?writtenRep))
        number-of     (homograph-numbers words)
        definition-of (index-many definitions '?synset (comp str '?definition))
        examples-of   (index-many examples '?sense (comp str '?example))
        ontotype-of   (update-vals (group-by '?synset ontological-types)
                                   ontological-type)
        gender-of     (index genders '?synset '?gender)
        marks-of      (index-many sense-labels '?sense (comp name '?value))
        notes-of      (index-many usage-notes '?sense (comp str '?note))
        label-of      (merge (index synset-labels '?synset (comp str '?label))
                             (index short-labels '?synset (comp str '?label)))
        ili-of        (index-many (unambiguous-ilis ilis) '?synset
                                  #(str prefix/ili-uri (name (get % '?ili))))
        senses-of     (index-many senses '?synset (comp name '?sense))
        ->sense       (fn [{:syms [?sense ?synset]}]
                        (cond-> {:id     (name ?sense)
                                 :labels (cond-> [(name ?synset)]
                                           (ontotype-of ?synset)
                                           (conj (ontotype-of ?synset))

                                           (gender-of ?synset)
                                           (conj (name (gender-of ?synset))))}
                          (marks-of ?sense)
                          (update :labels into (marks-of ?sense))

                          (notes-of ?sense)
                          (update :labels into (notes-of ?sense))

                          (definition-of ?synset)
                          (assoc :definitions (mapv (fn [text] {:text text})
                                                    (definition-of ?synset)))

                          (examples-of ?sense)
                          (assoc :examples (mapv (fn [text] {:text text})
                                                 (examples-of ?sense)))))
        ->entry       (fn [[word ms]]
                        (cond-> {:id       (name word)
                                 :headword (headword-of word)
                                 :senses   (mapv ->sense (sort-by (comp str '?sense) ms))}
                          (pos-of word) (assoc :partsOfSpeech [(pos-of word)])
                          (number-of word) (assoc :homographNumber (number-of word))))
        synsets       (set (map '?synset senses))]
    (merge lexicographic-resource
           {:entries       (->> (group-by '?word senses)
                                (sort-by (comp str key))
                                (map ->entry)
                                (filterv :headword))
            :labelTags     (concat
                             (for [synset (sort-by name synsets)]
                               (->synset-label-tag synset {:description (label-of synset)
                                                           :ilis        (ili-of synset)}))
                             (for [ontotype (sort (set (vals ontotype-of)))]
                               (->ontological-type-label-tag ontotype))
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
                               {:tag note :typeTag "usage" :for "sense"}))
            :relations     (->relations senses-of obverse-of relations)
            :relationTypes (->relation-types obverse-of relations)})))

(defn export-dmlex!
  "Export a DMLex `resource` into `dir` as both XML and JSON."
  [dir resource]
  (println "Beginning DMLex export of DanNet into" dir)
  (let [xml-file  (str dir "dannet-dmlex.xml")
        json-file (str dir "dannet-dmlex.json")]
    (io/make-parents xml-file)
    (spit xml-file (xml-str resource))
    (spit json-file (json-str resource)))
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
