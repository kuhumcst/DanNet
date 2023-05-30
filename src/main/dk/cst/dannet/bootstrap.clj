(ns dk.cst.dannet.bootstrap
  "Bootstrapping DanNet by mapping the old DanNet CSV export to Ontolex-lemon
  and Global WordNet Assocation relations.

  The following relations are excluded as part of the import:
    - eq_has_synonym:   mapping to an old version of Princeton Wordnet
    - eq_has_hyponym:   mapping to an old version of Princeton Wordnet
    - eq_has_hyperonym: mapping to an old version of Princeton Wordnet
    - used_for_qualby:  not in use, just 1 full triple + 3 broken ones

  Inverse relations are not explicitly created, but rather handled by way of
  inference using a Jena OWL reasoner.

  See: https://www.w3.org/2016/05/ontolex"
  (:require [clojure.set :as set]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.data.csv :as csv]
            [clojure.tools.reader.edn :as edn]
            [clojure.math.combinatorics :as combo]
            [clojure.walk :as walk]
            [clj-yaml.core :as yaml]
            [arachne.aristotle.graph :refer [rdf-bag]]
            [ont-app.vocabulary.lstr :refer [->LangStr]]
            [better-cond.core :as better]
            [reitit.impl :refer [percent-encode]]
            [dk.cst.dannet.hash :as h]
            [dk.cst.dannet.web.components :as com]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.query :as q]
            [dk.cst.dannet.query.operation :as op]))

;; TODO: missing labels
;;       http://localhost:3456/dannet/data/synset-48454
;;       http://localhost:3456/dannet/data/synset-49086
;;       http://localhost:3456/dannet/data/synset-3085
;;       http://0.0.0.0:3456/dannet/data//synset-49069
;; TODO: weird? http://localhost:3456/dannet/data/synset-47363

(defn da
  [& s]
  (->LangStr (apply str s) "da"))

(defn en
  [& s]
  (->LangStr (apply str s) "en"))

(defn fix-ellipsis
  [definition]
  (str/replace definition " ..." "…"))

(def <simongray>
  (prefix/uri->rdf-resource "https://simongray.dk"))

(def <cst>
  (prefix/uri->rdf-resource "https://cst.dk"))

(def <dsl>
  (prefix/uri->rdf-resource "https://dsl.dk"))

(def <dsn>
  "<https://dsn.dk>")

(def <dn>
  "The RDF resource URI for the DanNet dataset."
  (prefix/prefix->rdf-resource 'dn))

(def <dns>
  "The RDF resource URI for the DanNet schema."
  (prefix/prefix->rdf-resource 'dns))

(def <dnc>
  "The RDF resource URI for the DanNet/EuroWordNet concepts."
  (prefix/prefix->rdf-resource 'dnc))

(def <dds>
  "The RDF resource URI for the sentiment dataset."
  (prefix/prefix->rdf-resource 'dds))

(def <cor>
  "The RDF resource URI for the COR dataset."
  "<https://ordregister.dk>")

(def dn-zip-basic-uri
  (prefix/dataset-uri "rdf" 'dn))

(def dn-zip-merged-uri
  (prefix/dataset-uri "rdf" 'dn "merged"))

(def dn-zip-complete-uri
  (prefix/dataset-uri "rdf" 'dn "complete"))

(def dn-zip-csv-uri
  (prefix/dataset-uri "csv" 'dn))

(def cor-zip-uri
  (prefix/dataset-uri "rdf" 'cor))

(def dds-zip-uri
  (prefix/dataset-uri "rdf" 'dds))

(def dns-schema-uri
  (prefix/schema-uri 'dns))

(def dnc-schema-uri
  (prefix/schema-uri 'dnc))

(def release-date
  "2023-05-23")

(def dc-issued-old
  "2013-01-03")

(defn see-also
  "Generate rdfs:seeAlso backlink triples for `rdf-resources`."
  [& rdf-resources]
  (set (for [[k v] (combo/permuted-combinations rdf-resources 2)]
         [k :rdfs/seeAlso v])))

(h/def metadata-triples
  "Metadata for the different datasets is defined here.

  The Dublin Core Terms NS is used below which supersedes
  the older DC namespace (see: https://www.dublincore.org/schemas/rdfs/ )."
  (set/union
    #{[<dns> :rdf/type :owl/Ontology]
      [<dns> :vann/preferredNamespacePrefix "dns"]
      [<dns> :vann/preferredNamespaceUri (prefix/prefix->uri 'dns)]
      [<dns> :dc/title #voc/lstr "DanNet schema@en"]
      [<dns> :dc/title #voc/lstr "DanNet-skema@da"]
      [<dns> :dc/description #voc/lstr "Schema for DanNet-specific relations.@en"]
      [<dns> :dc/description #voc/lstr "Skema for DanNet-specifikke relationer.@da"]
      [<dns> :dc/issued release-date]
      [<dns> :dc/contributor <simongray>]
      [<dns> :dc/contributor <cst>]
      [<dns> :dc/contributor <dsl>]
      [<dns> :dc/publisher <cst>]
      [<dns> :foaf/homepage "<https://cst.ku.dk/projekter/dannet>"]
      [<dns> :dc/rights (en "Copyright © Centre for Language Technology (University of Copenhagen) & The Society for Danish Language and Literature.")]
      [<dns> :dc/rights (da "Copyright © Center for Sprogteknologi (Københavns Universitet) & Det Danske Sprog- og Litteraturselskab.")]
      [<dns> :dc/license "<https://creativecommons.org/licenses/by-sa/4.0/>"]

      [<dnc> :rdf/type :owl/Ontology]                       ;TODO: :skos/ConceptScheme instead?
      [<dnc> :vann/preferredNamespacePrefix "dnc"]
      [<dnc> :vann/preferredNamespaceUri (prefix/prefix->uri 'dnc)]
      [<dnc> :dc/title #voc/lstr "DanNet concepts@en"]
      [<dnc> :dc/title #voc/lstr "DanNet-koncepter@da"]
      [<dnc> :dc/description #voc/lstr "Schema containing all DanNet/EuroWordNet concepts.@en"]
      [<dnc> :dc/description #voc/lstr "Skema der indholder alle DanNet/EuroWordNet-koncepter.@da"]
      [<dnc> :dc/issued release-date]
      [<dnc> :dc/contributor <simongray>]
      [<dnc> :dc/contributor <cst>]
      [<dnc> :dc/contributor <dsl>]
      [<dnc> :dc/publisher <cst>]
      [<dnc> :foaf/homepage "<https://cst.ku.dk/projekter/dannet>"]
      [<dnc> :dc/rights (en "Copyright © Centre for Language Technology (University of Copenhagen) & The Society for Danish Language and Literature.")]
      [<dnc> :dc/rights (da "Copyright © Center for Sprogteknologi (Københavns Universitet) & Det Danske Sprog- og Litteraturselskab.")]
      [<dnc> :dc/license "<https://creativecommons.org/licenses/by-sa/4.0/>"]

      [<dn> :rdf/type :dcat/Dataset]
      [<dn> :rdf/type :lime/Lexicon]
      [<dn> :vann/preferredNamespacePrefix "dn"]
      [<dn> :vann/preferredNamespaceUri (prefix/prefix->uri 'dn)]
      [<dn> :rdfs/label "DanNet"]
      [<dn> :dc/title "DanNet"]
      [<dn> :dc/language "da"]
      [<dn> :dc/description #voc/lstr "The Danish WordNet.@en"]
      [<dn> :dc/description #voc/lstr "Det danske WordNet.@da"]
      [<dn> :dc/issued release-date]
      [<dn> :dc/contributor <simongray>]
      [<dn> :dc/contributor <cst>]
      [<dn> :dc/contributor <dsl>]
      [<dn> :dc/publisher <cst>]
      [<dn> :foaf/homepage "<https://cst.ku.dk/projekter/dannet>"]
      [<dn> :schema/email "simongray@hum.ku.dk"]
      [<dn> :owl/versionInfo release-date]
      [<dn> :dc/rights (en "Copyright © Centre for Language Technology (University of Copenhagen) & "
                           "The Society for Danish Language and Literature; "
                           "licensed under CC BY-SA 4.0 (https://creativecommons.org/licenses/by-sa/4.0/).")]
      [<dn> :dc/rights (da "Copyright © Center for Sprogteknologi (Københavns Universitet) & "
                           "Det Danske Sprog- og Litteraturselskab; "
                           "udgives under CC BY-SA 4.0 (https://creativecommons.org/licenses/by-sa/4.0/).")]
      [<dn> :dc/license "<https://creativecommons.org/licenses/by-sa/4.0/>"]
      ["<https://creativecommons.org/licenses/by-sa/4.0/>" :rdfs/label "CC BY-SA 4.0"]

      [<dds> :rdfs/label "DDS"]
      [<dds> :dc/title "DDS"]
      [<dds> :dc/description #voc/lstr "The Danish Sentiment Lexicon@en"]
      [<dds> :dc/description #voc/lstr "Det Danske Sentimentleksikon@da"]
      [<dds> :dc/contributor <cst>]
      [<dds> :dc/contributor <dsl>]
      [<dds> :rdfs/seeAlso (prefix/uri->rdf-resource "https://github.com/dsldk/danish-sentiment-lexicon")]

      [<cor> :rdfs/label "COR"]
      [<cor> :dc/title "COR"]
      [<cor> :dc/contributor <cst>]
      [<cor> :dc/contributor <dsl>]
      [<cor> :dc/contributor <dsn>]
      [<cor> :dc/description #voc/lstr "The Central Word Registry.@en"]
      [<cor> :dc/description #voc/lstr "Det Centrale Ordregister.@da"]
      [<cor> :rdfs/seeAlso (prefix/uri->rdf-resource "https://dsn.dk/sprogets-udvikling/sprogteknologi-og-fagsprog/cor/")]

      ;; Contributors/publishers
      [<simongray> :rdf/type :foaf/Person]
      [<simongray> :foaf/name "Simon Gray"]
      [<simongray> :foaf/workplaceHomepage "<https://nors.ku.dk/ansatte/?id=428973&vis=medarbejder>"]
      [<simongray> :foaf/homepage <simongray>]
      [<simongray> :foaf/weblog "<https://simon.grays.blog>"]
      [<cst> :rdf/type :foaf/Group]
      [<cst> :foaf/name (da "Center for Sprogteknologi")]
      [<cst> :foaf/name (en "Centre for Language Technology")]
      [<cst> :rdfs/comment (da "Centret er en del af Københavns universitet.")]
      [<cst> :rdfs/comment (en "The centre is part of the University of Copenhagen.")]
      [<cst> :foaf/homepage <cst>]
      [<cst> :foaf/homepage "<https://cst.ku.dk>"]
      [<cst> :foaf/member <simongray>]
      [<dsl> :rdf/type :foaf/Group]
      [<dsl> :foaf/name (da "Det Danske Sprog- og Litteraturselskab")]
      [<dsl> :foaf/name (en "The Society for Danish Language and Literature")]
      [<dsl> :foaf/homepage <dsl>]
      [<dsn> :rdf/type :foaf/Group]
      [<dsn> :foaf/name (da "Dansk Sprognævn")]
      [<dsn> :foaf/name (en "The Danish Language Council")]
      [<dsn> :foaf/homepage <dsn>]}

    (see-also <dn> <dns> <dnc>)
    (see-also <dn> <dds>)
    (see-also <dn> <cor>)
    (see-also <cst> <dsl> <dsn>)

    ;; Downloads
    #{[<dn> :dcat/downloadURL (prefix/uri->rdf-resource dn-zip-basic-uri)]
      [<dn> :dcat/downloadURL (prefix/uri->rdf-resource dn-zip-merged-uri)]
      [<dn> :dcat/downloadURL (prefix/uri->rdf-resource dn-zip-complete-uri)]
      [<dn> :dcat/downloadURL (prefix/uri->rdf-resource dn-zip-csv-uri)]
      [<cor> :dcat/downloadURL (prefix/uri->rdf-resource cor-zip-uri)]
      [<dds> :dcat/downloadURL (prefix/uri->rdf-resource dds-zip-uri)]
      [<dns> :dcat/downloadURL (prefix/uri->rdf-resource dns-schema-uri)]
      [<dnc> :dcat/downloadURL (prefix/uri->rdf-resource dnc-schema-uri)]}))

(defn synset-uri
  [id]
  (keyword "dn" (str "synset-" id)))

(defn word-uri
  [id]
  (keyword "dn" (str "word-" id)))

(defn sense-uri
  [id]
  (keyword "dn" (str "sense-" id)))

;; I am not allowed to start the identifier/local name with a dot in QNames.
;; Similarly, I cannot begin the name part of a keyword with a number.
;; For this reason, I need to include the "COR." part in the local name too.
;; NOTE: QName an be validated using http://sparql.org/query-validator.html
(defn cor-uri
  [& parts]
  (keyword "cor" (str "COR." (str/join "." (remove nil? parts)))))

(def brug
  #"\s*\(Brug: \"(.+)\"\)")

(def inserted-by-DanNet
  #"Inserted by DanNet: ?")

(defn- princeton-synset?
  [id]
  (re-find #"(::|:\d\d|:_b)$" id))

(def ignored-relations
  #{"eq_has_synonym"
    "eq_has_hyponym"
    "eq_has_hyperonym"
    "used_for_qualby"})

;; Forms are represented using blank nodes as we want them to appear inline
;; + they are assumed to be in a 1:1 relationship with their parent word anyway.
(defn lexical-form-uri
  [word-id]
  (symbol (str "_dn-form-" word-id)))

(def special-cases
  {"{DN:abstract_entity}"   "{DN:Abstract Entity}"
   "Torshavn|Thorshavn"     "Thorshavn"
   "{Torshavn|Thorshavn_1}" "{Thorshavn_1}"
   "Z, z"                   "Z"
   "Q, q"                   "Q"
   "Y, y"                   "Y"
   "Æ, æ"                   "Æ"})

;; Note: a single "used_for_qualby" rel exists in the dataset - likely an error
;; https://github.com/globalwordnet/schemas
;; https://github.com/globalwordnet/schemas/blob/master/wn-lemon-1.1.ttl
;; https://github.com/globalwordnet/schemas/blob/master/wn-lemon-1.1.rdf
;; @prefix wn: <https://globalwordnet.github.io/schemas/wn#> .
(def gwa-rel
  {"concerns"            :wn/also                           ; TODO: ask GWA to rename to concerns/associated?
   "used_for"            :dns/usedFor
   "used_for_object"     :dns/usedForObject
   "has_holonym"         :wn/holonym
   "has_holo_location"   :wn/holo_location
   "has_holo_madeof"     :wn/holo_substance
   "has_holo_member"     :wn/holo_member
   "has_holo_part"       :wn/holo_part
   "has_hyperonym"       :wn/hypernym
   "has_hyponym"         :wn/hyponym
   "has_meronym"         :wn/meronym
   "has_mero_location"   :wn/mero_location
   "has_mero_madeof"     :wn/mero_substance
   "has_mero_member"     :wn/mero_member
   "has_mero_part"       :wn/mero_part
   "involved_agent"      :wn/co_instrument_agent
   "involved_instrument" :wn/co_agent_instrument
   "involved_patient"    :wn/involved_patient
   "made_by"             :wn/result
   "near_synonym"        :wn/similar
   "near_antonym"        :dns/nearAntonym
   "role_agent"          :wn/agent
   "role_patient"        :wn/patient
   "domain"              :wn/has_domain_topic
   "is_instance_of"      :wn/instance_hypernym

   ;; xpos_near_synonym is from EuroWordnet: "we have decided to use a separate
   ;; relation for synonymy across parts-of-speech: XPOS_NEAR_SYNONYM"
   ;; See: https://globalwordnet.github.io/gwadoc/pdf/EWN_general.pdf
   "xpos_near_synonym"   :wn/similar})

(defn form->lexical-entry
  "Derive the correct Ontolex LexicalEntry type from the `form` of a word."
  [form]
  (cond
    (re-find #" " form)
    :ontolex/MultiwordExpression

    (or (str/starts-with? form "-")
        (str/ends-with? form "-"))
    :ontolex/Affix

    :else :ontolex/Word))

;; An imperfect method of mapping examples to senses, since the tokens of the
;; example sentences have not been lemmatised. It would be preferable to get the
;; full set of example sentences mapped correctly to senses by DSL.
(defn determine-example-token
  "Given a `label` from the synsets.csv file and an example `example`,
  determine which of the words in the label the example pertains to."
  [label example]
  (let [example* (str/lower-case example)
        label*   (str/lower-case label)]
    (loop [[token & tokens] (map str/trim (re-seq #"[-æøå a-z]+" label*))]
      (when token
        (if (str/includes? example* token)
          token
          (recur tokens))))))

(h/defn examples
  "Convert a `row` from 'synsets.csv' to example key-value pairs."
  [[synset-id label gloss _ :as row]]
  (when-let [[_ example-str] (re-find brug gloss)]
    (into {} (for [example (str/split example-str #" \|\| |\"; \"")]
               (when-let [token (determine-example-token label example)]
                 [[(synset-uri synset-id) (da token)] (da example)])))))

(defn clean-ontological-type
  "Clean up the `ontological-type` string before conversion to resource names.

    - Removes parentheses from the `ontological-type` string.
    - Replaces numbers which might have complicated using concepts as names in
    certain circumstances.
    - Replaces plus signs with dashes."
  [ontological-type]
  (-> ontological-type
      (str/replace #"\(|\)" "")
      (str/replace #"\+" "-")
      (str/replace #"1st" "First")
      (str/replace #"2nd" "Second")
      (str/replace #"3rd" "Third")))

(defn explode-ontological-type
  "Create ontologicalType triple(s) based on a `synset` and the legacy DanNet
  `ontological-type` string.

  The triples are placed into an RDF Bag (= a set)."
  [synset ontological-type]
  (let [bag (symbol (str "_" ontological-type))]
    (set/union
      #{[synset :dns/ontologicalType bag]}
      (set (->> (str/split ontological-type #"-")
                (map (partial keyword "dnc"))
                (rdf-bag)
                (map (partial into [bag])))))))

(defn n->letter
  "Convert a number `n` to a letter in the English alphabet."
  [n]
  (char (+ n 96)))

(def old-multi-word
  #"([^,_]+)(,\d+)?((?:_\d+)+)?: (.+)")

(def old-single-word
  #"([^,_]+)(,\d+)?((?:_\d+)+)?")

;; e.g. "gas-, vand- og sanitetsmester_1"
(def old-long-word
  #"([^_]+)(,\d+)?((?:_\d+)+)?")

;; Match weird alphabet entries like {Q, q_1}.
(def old-alphabet-combo
  #"([A-ZÆØÅ]), [a-zæøå](_.+)?")

(def old-inserted-by-DanNet-word
  #"DN:(.+)")

;; Read ./pages/label-rewrite-en.md for more.
(defn sense-label
  "Create a sense label from `word`, optional `entry-id`, and `definition-id`."
  [word entry-id definition-id]
  (if (or (not definition-id)
          (= definition-id "_0"))
    (if entry-id
      (str word "_" (subs entry-id 1))
      word)
    (let [[_ def-id sub-id] (str/split definition-id #"_")]
      (str word "_"
           (when entry-id
             (subs entry-id 1))
           "§" def-id
           (when sub-id
             (str (n->letter (parse-long sub-id))))))))

(defn remove-prefix-apostrophes
  "Remove special apostrophes from `s` used to denote particles/stress.
  This doesn't remove *actual* grammatical apostrophes, e.g. in be'r."
  [s]
  (str/replace s #"(^|\s)'+" "$1"))

(defn rewrite-sense-label
  "Rewrite an old sense `label` to fit the new standard."
  [label]
  (better/cond
    :let [[_ w] (re-matches old-inserted-by-DanNet-word label)]
    w
    w

    :let [[_ w e d] (re-matches old-single-word label)]
    w
    (sense-label w e d)

    :let [[_ w e d mwe] (re-matches old-multi-word label)]
    mwe
    (let [w*    (remove-prefix-apostrophes w)
          mwe*  (remove-prefix-apostrophes mwe)
          begin (str/index-of (str/lower-case mwe*) (str/lower-case w*))
          size  (count mwe*)
          end   (when begin
                  (min (or (str/index-of mwe* "," begin) size) ; ...synset-7290
                       (or (str/index-of mwe* " " begin) size)))
          w**   (when end
                  (subs mwe* begin end))]
      ;; In cases where the word cannot be located inside the MWE, the entry and
      ;; definition IDs at the end of the MWE string. Otherwise, the IDs are
      ;; placed immediately after the referenced word.
      (if w**
        (str/replace mwe* w** (sense-label w** e d))
        (sense-label mwe* e d)))

    :let [[_ w d] (re-matches old-alphabet-combo label)]
    w
    (sense-label w nil d)

    :let [[_ w e d] (re-matches old-long-word label)]
    w
    (sense-label w e d)))

(def old-synset-sep
  #"\{|;|\}")

(defn rewrite-synset-label
  "Rewrite an old synset `label` to fit the new standard."
  [label]
  (str "{"
       (->> (get special-cases label label)
            (com/sense-labels old-synset-sep)
            (map rewrite-sense-label)
            (str/join "; "))
       "}"))

;; TODO: ...also include the missing example triples, somehow?
(def at-symbol-triples
  "Special case triples for the @ symbol (@ is used to delimit input columns)."
  (let [synset     (synset-uri "8715")
        label      (rewrite-synset-label "{snabel-a_1}")
        definition "skrifttegnet @"]
    (set/union
      #{[synset :rdf/type :ontolex/LexicalConcept]
        #_[synset :dc/issued dc-issued-old]
        [synset :rdfs/label (da label)]
        [synset :skos/definition (da (fix-ellipsis definition))]}
      (->> (clean-ontological-type "LanguageRepresentation+Artifact+Object")
           (explode-ontological-type synset)))))

(h/defn ->synset-triples
  "Convert a `row` from 'synsets.csv' to triples."
  [[synset-id label gloss ontological-type :as row]]
  (if (= synset-id "8715")
    at-symbol-triples                                       ; special case
    (when (= (count row) 5)
      (let [synset     (synset-uri synset-id)
            definition (-> gloss
                           (str/replace brug "")
                           (str/replace inserted-by-DanNet ""))]
        (set/union
          #{[synset :rdf/type :ontolex/LexicalConcept]
            #_[synset :dc/issued dc-issued-old]}
          (when (not-empty label)
            #{[synset :rdfs/label (da (rewrite-synset-label label))]})
          (when (and (not= definition "(ingen definition)")
                     (not (str/blank? definition)))
            #{[synset :skos/definition (da (fix-ellipsis definition))]})
          (->> (clean-ontological-type ontological-type)
               (explode-ontological-type synset)))))))

;; TODO: use RDF* instead? How will this work with Aristotle? What about export?
;; https://jena.apache.org/documentation/rdf-star/
(defn- explode-inheritance
  [subj-id rel comment]
  (when-let [[_ id l] (re-matches #"^Inherited from synset with id (\d+) \((.+)\)$"
                                  comment)]
    (let [from    (synset-uri id)
          subject (synset-uri subj-id)
          inherit (keyword "dn" (str "inherit-" subj-id "-" (name rel)))]
      #{[subject :dns/inherited inherit]
        [inherit :rdf/type :dns/Inheritance]
        [inherit :rdfs/label (prefix/kw->qname rel)]
        [inherit :dns/inheritedFrom from]
        [inherit :dns/inheritedRelation rel]})))

(h/defn ->relation-triples
  "Convert a `row` from 'relations.csv' to triples.

  Note: certain rows are unmapped, so the relation will remain a string!"
  [[subj-id _ rel obj-id taxonomic inheritance :as row]]
  (when (and (= (count row) 7)
             (not (ignored-relations rel)))
    (let [subj    (synset-uri subj-id)
          obj     (synset-uri obj-id)
          comment (not-empty inheritance)
          rel*    (if (and (= taxonomic "nontaxonomic")
                           (= rel "has_hyperonym"))
                    :dns/orthogonalHypernym
                    (gwa-rel rel))]
      (cond-> #{[subj rel* obj]}
        comment (set/union (explode-inheritance subj-id rel* comment))))))

(def senseidx->english-synset
  (delay (edn/read-string (slurp "bootstrap/other/english/senseidx.edn"))))

(h/defn ->english-link-triples
  "Convert a `row` from 'relations.csv' to triples.

  Note: certain rows are unmapped, so the relation will remain a string!"
  [[subj-id _ rel obj-id _ _ :as row]]
  ;; Ignores eq_has_hyponym and eq_has_hyperonym, no equivalent in GWA schema.
  ;; This loses us 123 of the original 5000l links to the Princton WordNet.
  (when (= "eq_has_synonym" rel)
    ;; TODO: need backup for IDs that match e.g. "ENG20-07945291-n"
    (when-let [obj (get @senseidx->english-synset obj-id)]
      #{[(synset-uri subj-id) :wn/eq_synonym obj]})))

;; TODO: can we create new forms/words/synsets rather than overload writtenRep?
(defn explode-written-reps
  "Create writtenRep triple(s) based on a `lexical-form` and a `written-rep`.

  In certain cases, multi-word expressions mark multiple written representations
  using slashes. These representations are exploded into multiple triples."
  [lexical-form written-rep]
  (if-let [block (re-find #"[^\s]+/[^\s]+" written-rep)]
    (let [replace-block (partial str/replace written-rep block)]
      (for [exploded-rep (map replace-block (str/split block #"/"))]
        [lexical-form :ontolex/writtenRep (da exploded-rep)]))
    #{[lexical-form :ontolex/writtenRep (da written-rep)]}))

(def pos-fixes
  "Ten words had 'None' as their POS tag. Looking at the other words in their
  synsets clearly inform the correct POS tags to use."
  {:dn/word-12005324-2 "adjective"
   :dn/word-12002785   "noun"
   :dn/word-11006697   "noun"
   :dn/word-11022554   "noun"
   :dn/word-11043739   "noun"
   :dn/word-12007550   "noun"
   :dn/word-11038834   "noun"
   :dn/word-11047932   "noun"
   :dn/word-12005626-1 "verb"
   :dn/word-11018863   "noun"})

(defn qt
  [s & after]
  (apply str "\"" s "\"" after))

(declare read-triples)
(declare cor-k-pos)

(def sense-properties
  (let [row->kv (fn [[dannetsemid lemma hom pos dn_lemma id gloss]]
                  (let [[_ pos'] (re-matches #"([^\.]+)\.?" pos)
                        pos-class (cor-k-pos pos')]
                    [dannetsemid (cond-> {:synset-id   id
                                          :written-rep lemma
                                          :sense-label dn_lemma}
                                   (not-empty gloss) (assoc :definition gloss)
                                   pos-class (assoc :pos pos-class))]))]
    (delay
      (->> (read-triples [row->kv
                          "bootstrap/other/dannet-new/dannetsemid_synsetid.csv"
                          :encoding "UTF-8"
                          :separator \tab
                          :preprocess rest])
           (into {})))))

(def sense-definitions
  (delay
    (->> (read-triples [identity
                        "bootstrap/other/dannet-new/adj_suppl_brutto_221222.csv"
                        :encoding "UTF-8"
                        :separator \tab
                        :preprocess rest])
         (into {}))))

(def sense-siblings
  (delay
    (->> (read-triples [(fn [[_ _ _ _ _ dannetsemid _ sek_id _ :as row]]
                          (when-not (str/blank? sek_id)
                            {dannetsemid #{sek_id}}))
                        "bootstrap/other/dannet-new/adjectives.tsv"
                        :encoding "UTF-8"
                        :separator \tab
                        :preprocess rest])
         (partition-by ffirst)
         (reduce (fn [m coll]
                   (apply merge-with set/union m coll)) {})
         (mapcat (fn [[_ sense-ids]]
                   (for [sense-id sense-ids]
                     [sense-id (disj sense-ids sense-id)])))
         (into {}))))

(def existing-resources
  (delay
    (->> (read-triples [(fn [[sense-id word-id synset-id register _ :as row]]
                          (when (and (not-empty sense-id)
                                     (not-empty synset-id))
                            [sense-id synset-id]))
                        "bootstrap/dannet/DanNet-2.5.1_csv/wordsenses.csv"])
         (into {}))))

(def sense-examples
  "Examples for the new sense in the 2023 adjective data."
  (delay
    (->> (with-open [reader (io/reader "bootstrap/other/dannet-new/adj_suppl_cit_brutto_221222.csv")]
           (mapv #(str/split % #"\t") (line-seq reader)))
         (filter #(= 2 (count %)))
         (into {}))))

(def sense-id->multi-word-synset
  "Information needed to construct labels for multi-word synsets in 2022 data.
  An important thing to note is these are actually sense IDs used for synthesis!

  When available, the annotated sense label from 'sense-properties' is preferred
  to the raw written representation."
  (delay
    (let [id->label (comp :sense-label @sense-properties)
          raw       (-> "bootstrap/other/dannet-new/multi-word-synsets.edn"
                        (slurp)
                        (edn/read-string))
          rows->kvs (fn [rows]
                      (let [sense-ids    (map #(nth % 7) rows)
                            wlabels      (map #(nth % 6) rows)
                            slabels      (map id->label sense-ids)
                            labels       (map #(or %1 %2) slabels wlabels)
                            synset-id    (first (sort sense-ids))
                            synset-label (as-> labels $
                                               (map rewrite-sense-label $)
                                               (sort $)
                                               (str/join "; " $)
                                               (str "{" $ "}")
                                               (da $))
                            m            {:mws-id    synset-id
                                          :mws-label synset-label}]
                        (map (fn [rows]
                               [(nth rows 7) m])
                             rows)))]
      (into {} (mapcat rows->kvs raw)))
    #_.))

;; TODO: investigate semantics of ' in input forms of multiword expressions
(h/defn ->word-triples
  "Convert a `row` from 'words.csv' to triples."
  [[word-id form pos _ :as row]]
  (when (and (= (count row) 4)
             (not (get #{"None-None" "0-0"} word-id)))      ; see issue #40
    (let [word         (word-uri word-id)
          form         (get special-cases form form)
          rdf-type     (form->lexical-entry form)
          written-rep  (if (= rdf-type :ontolex/MultiwordExpression)
                         (remove-prefix-apostrophes form)
                         form)
          lexical-form (lexical-form-uri word-id)
          fixed-pos    (when pos
                         (get pos-fixes word (str/lower-case pos)))]
      (set/union
        #{#_[lexical-form :rdf/type :ontolex/Form]
          #_[lexical-form :rdfs/label (da (qt written-rep "-form"))]

          [word :rdf/type rdf-type]
          [word :rdfs/label (da (qt written-rep))]
          [word :ontolex/canonicalForm lexical-form]}
        (when fixed-pos
          ;; GWA and Ontolex have competing part-of-speech relations.
          ;; Ontolex prefers Lexinfo's relation, while GWA defines its own.
          #{[word :lexinfo/partOfSpeech (keyword "lexinfo" fixed-pos)]
            [word :wn/partOfSpeech (keyword "wn" fixed-pos)]})
        (explode-written-reps lexical-form written-rep)))))

(defn- ->register-triples
  "Convert the `register` of a `sense` to appropriate triples."
  [sense register]
  (if (empty? register)
    #{}
    (cond-> #{[sense :lexinfo/usageNote (da register)]}

      (re-find #"gl." register)
      (conj [sense :lexinfo/dating :lexinfo/old])

      (re-find #"sj." register)
      (conj [sense :lexinfo/frequency :lexinfo/rarelyUsed])

      (re-find #"jargon" register)
      (conj [sense :lexinfo/register :lexinfo/inHouseRegister])

      (re-find #"slang" register)
      (conj [sense :lexinfo/register :lexinfo/slangRegister]))))

(h/defn iri-encode
  "Encode `s` as an IRI (basically just reitit url-encode which allows ÆØÅ)."
  [s]
  (str/replace s #"[^ÆØÅæøåA-Za-z0-9\!'\(\)\*_~.-]+" percent-encode))

(h/defn ->ddo-resource
  "Get RDF resource with `label` for a DDO `word-id` and optionally `sense-id`."
  ([word-id sense-id label]                                 ; for senses
   (let [lemma (iri-encode (str/replace label #"_[^ $]+" ""))]
     (str "<https://ordnet.dk/ddo/ordbog?entry_id=" word-id
          (when sense-id
            (str "&def_id=" sense-id))
          "&query=" lemma ">")))
  ([word-id label]                                          ; for words
   (->ddo-resource word-id nil label)))

(h/defn ->sense-triples
  "Convert a `row` from 'wordsenses.csv' to triples."
  [[sense-id word-id synset-id register _ :as row]]
  (when (and (= (count row) 5)
             (not (princeton-synset? synset-id)))
    (let [id->label (comp :sense-label @sense-properties)
          sense     (sense-uri sense-id)
          word      (word-uri word-id)
          synset    (synset-uri synset-id)]
      (set/union
        (->register-triples sense register)
        #{[sense :rdf/type :ontolex/LexicalSense]
          [synset :ontolex/lexicalizedSense sense]}

        ;; These are not part of the original CSV export, but from an extra file
        ;; sent to me by Thomas Troelsgård from DSL.
        (when-let [label (some-> sense-id id->label rewrite-sense-label)]
          #{[sense :rdfs/label label]})

        ;; Links to the DDO dictionary on ordnet.dk.
        (when-let [old-label (some-> sense-id id->label)]
          #{[sense :dns/source (->ddo-resource word-id sense-id old-label)]
            [word :dns/source (->ddo-resource word-id old-label)]})

        ;; The "inserted by DanNet" senses refer to the same dummy word, "TOP".
        ;; These relations make no sense to include. Instead, the necessary
        ;; words must be synthesized at a later point.
        (when (not= word :dn/word-0-0)
          #{[word :ontolex/evokes synset]
            [word :ontolex/sense sense]})))))

(h/defn mark-duplicate-senses
  [rows]
  (let [dupe-kv     (fn [n [lemma kap afs afsnitsnavn denbet
                            dannetsemid sek_holem sek_id sek_denbet
                            :as row]]
                      [[dannetsemid sek_id] n])
        get-dupe-id (->> (group-by #(nth % 7) rows)
                         (mapcat (fn [[_ rows]]
                                   (when (> (count rows) 1)
                                     (map-indexed dupe-kv rows))))
                         (into {}))]
    (map #(with-meta % {:get-dupe-id get-dupe-id}) rows)))

(h/defn ->2023-triples
  "Convert a `row` from 'adjectives.tsv' to triples."
  [[lemma kap afs afsnitsnavn denbet dannetsemid sek_holem sek_id sek_denbet
    :as row]]
  (when-not (str/blank? sek_id)
    (let [{:keys [get-dupe-id]} (meta row)
          existing-synset-id    @existing-resources
          sense-id->mws         @sense-id->multi-word-synset
          sense-id->siblings    @sense-siblings
          sense-id->synset-id   (comp :synset-id @sense-properties)
          sense-id->sense-label (comp :sense-label @sense-properties)
          sense-id->definition  (comp :definition @sense-properties)
          sense-id->definition' (fn [id]
                                  (when-not (str/blank? id)
                                    (get @sense-definitions id)))
          sense                 (sense-uri sek_id)
          mws-or-sense-id       (fn [sense-id]
                                  (or (:mws-id (sense-id->mws sense-id))
                                      sense-id))
          use-old-synset-id     (comp sense-id->synset-id mws-or-sense-id)
          dupe-id               #(get-dupe-id [dannetsemid %])
          sense-label           (-> (or (sense-id->sense-label sek_id)
                                        sek_holem)
                                    (rewrite-sense-label))
          synthesize-synset-id  (fn [sense-id]
                                  (str "s" (mws-or-sense-id sense-id)
                                       (when-let [dupe-id (dupe-id sense-id)]
                                         (str "-d" (inc dupe-id)))))
          pick-synset-id        (fn [sense-id]
                                  (or (existing-synset-id sense-id)
                                      (use-old-synset-id sense-id)
                                      (synthesize-synset-id sense-id)))
          synset-id             (pick-synset-id sek_id)
          synset                (synset-uri synset-id)]
      (set/union
        #{[sense :rdf/type :ontolex/LexicalSense]
          [synset :rdf/type :ontolex/LexicalConcept]

          ;; Labels
          [sense :rdfs/label sense-label]
          [synset :rdfs/label (or (:mws-label (sense-id->mws sek_id))
                                  (da (str "{" sense-label "}")))]
          #_[synset :dc/issued release-date]

          ;; Lexical connections
          [synset :ontolex/lexicalizedSense sense]}

        ;; Mark any sibling synsets as :wn/similar (= near synonym)
        (when-let [siblings (sense-id->siblings (mws-or-sense-id sek_id))]
          (->> (map pick-synset-id siblings)
               (remove nil?)
               (map synset-uri)
               (remove #{synset})
               (map (fn [sibling-synset]
                      [synset :wn/similar sibling-synset]))
               (into #{})))

        ;; TODO: doesn't seem to work in some cases, e.g. http://localhost:3456/dannet/data/synset-21592
        ;;       this appears to be related to the fact that we're using the old
        ;;       CSV export, which doesn't include entities created after 2013.
        ;; Inheritance (effectuated in the ->dannet function)
        ;; See also: 'synthesize-inherited-relations' defined below.
        (when-let [from-id (pick-synset-id dannetsemid)]
          (let [hypernym (keyword "dn" (str "inherit-" synset-id "-hypernym"))
                ontotype (keyword "dn" (str "inherit-" synset-id "-ontologicalType"))
                from     (synset-uri from-id)]
            (set/union
              #{[synset :wn/similar from]}

              ;; We can only automatically inherit these values when the synset
              ;; ID is brand new, i.e. it has been synthesized. Otherwise, it
              ;; must be assumed that the manually assigned values are prefered.
              (when (str/starts-with? synset-id "s")
                #{[synset :dns/inherited ontotype]
                  [synset :dns/inherited hypernym]

                  [hypernym :rdf/type :dns/Inheritance]
                  [hypernym :rdfs/label (prefix/kw->qname :wn/hypernym)]
                  [hypernym :dns/inheritedFrom from]
                  [hypernym :dns/inheritedRelation :wn/hypernym]

                  [ontotype :rdf/type :dns/Inheritance]
                  [ontotype :rdfs/label (prefix/kw->qname :dns/ontologicalType)]
                  [ontotype :dns/inheritedFrom from]
                  [ontotype :dns/inheritedRelation :dns/ontologicalType]}))))

        (when-let [example (get @sense-examples sek_id)]
          (when-not (str/blank? example)
            #{[sense :lexinfo/senseExample (da example)]}))

        (when-let [definition (not-empty (or (sense-id->definition' sek_id)
                                             (sense-id->definition sek_id)
                                             sek_denbet))]
          #{[synset :skos/definition (da (fix-ellipsis definition))]})))))

(defn synthesize-missing-words
  "Create word-related triples for missing words in `g`.
  This an extra step needed to properly integrate the 2023 data."
  [g]
  (->> (q/run g op/missing-words)
       (map (fn [{:syms [?sense ?synset ?label]}]
              (let [sense-id->pos (comp :pos @sense-properties)
                    sense-id      (subs (name ?sense) 6)
                    word-id       (str "s" sense-id)
                    word          (word-uri word-id)
                    [_ written-rep] (re-matches #"([^_]+)(.*)?" ?label)
                    lexical-form  (lexical-form-uri word-id)]
                #{[word :rdf/type :ontolex/LexicalEntry]
                  [word :rdfs/label (da (qt written-rep))]
                  #_[lexical-form :rdfs/label (da (qt written-rep "-form"))]
                  #_[lexical-form :rdf/type :ontolex/Form]

                  [word :ontolex/evokes ?synset]
                  [word :ontolex/sense ?sense]
                  [word :ontolex/canonicalForm lexical-form]
                  [lexical-form :ontolex/writtenRep (da written-rep)]

                  [word :wn/partOfSpeech (or (sense-id->pos sense-id)
                                             :wn/adjective)]})))))

(defn synthesize-inherited-relations
  "Create inheritance-related triples for missing words in `g`.
  This an extra step needed to properly integrate the 2023 data."
  [g]
  (->> (q/run g op/missing-inheritance)
       (map (fn [{:syms [?synset ?hypernym ?ontotype]}]
              #{[?synset :dns/ontologicalType ?ontotype]
                [?synset :wn/hypernym ?hypernym]}))))

;; Coercing to int to avoid ^^<http://www.w3.org/2001/XMLSchema#long> in export
(def pol-val
  {"nxx" (int -3)
   "nx"  (int -2)
   "n"   (int -1)
   "0"   (int 0)
   "p"   (int 1)
   "px"  (int 2)
   "pxx" (int 3)})

(defn pol-class
  [pol]
  (cond
    (get #{"p" "px" "pxx"} pol) :marl/Positive
    (get #{"n" "nx" "nxx"} pol) :marl/Negative
    :else :marl/Neutral))

(h/defn ->sentiment-triples
  "Convert a `row` from 'sense_polarities.tsv' to Opinion triples.

  In ~2000 cases a sense-id will be missing (it has the same ID as the word-id).
  Since there is no sense-id provided for these rows, we do not create sense
  triples. However, these missing senses may later be synthesized."
  [[_ sense-id _ _ _ sense-pol word-pol word-id :as row]]
  (let [word           (word-uri word-id)
        sense          (sense-uri sense-id)
        _word-opinion  (symbol (str "_opinion-word-" word-id))
        _sense-opinion (symbol (str "_opinion-sense-" sense-id))]
    (set/union
      #{#_[_word-opinion :rdf/type :marl/Opinion]
        #_[_word-opinion :marl/describesObject word]
        [word :dns/sentiment _word-opinion]
        [_word-opinion :marl/polarityValue (get pol-val word-pol (int 0))]
        [_word-opinion :marl/hasPolarity (pol-class word-pol)]}
      (when (not= sense-id word-id)
        #{[sense :dns/sentiment _sense-opinion]
          [_sense-opinion :marl/polarityValue (get pol-val sense-pol (int 0))]
          [_sense-opinion :marl/hasPolarity (pol-class sense-pol)]}))))

(defn- preprocess-cor
  "Add metadata to each row in `rows` to associate a canonical form with an ID.
  Relies on the lemma in the second column and the first form being canonical."
  [rows]
  (let [canonical (->> (partition-by (fn [[_ lemma]] lemma) rows)
                       (map ffirst)
                       (set))]
    (map #(with-meta % {:canonical canonical}) rows)))

(def cor-id
  "For splitting a COR-compatible id into: [id lemma-id form-id rep-id]."
  #"COR\.(?:([^\d]+)\.)?([^\.]+)(?:\.([^\.]+))?(?:\.([^\.]+))?")

(def cor-k-pos
  {"sb"           :lexinfo/noun
   "vb"           :lexinfo/verb
   "adj"          :lexinfo/adjective
   "adv"          :lexinfo/adverb
   "konj"         :lexinfo/conjunction
   "præp"         :lexinfo/preposition
   "prop"         :lexinfo/properNoun
   "udråbsord"    :lexinfo/interjection
   "pron"         :lexinfo/pronoun
   "talord"       :lexinfo/numeral

   ;; Using Olia as a backup ontology instead of Lexinfo.
   "præfiks"      :olia/Prefix
   "lydord"       :olia/OnomatopoeticWord

   ;; Currently not supported, TODO: find lexinfo/wordnet equivalents
   "infinitivens" nil
   "fsubj"        nil
   "fork"         nil
   "flerord"      nil
   "kolon"        nil})

(h/defn ->cor-k-triples
  "Convert a `row` from the COR-K ID file to triples; assumes that the
  rows have been preprocessed by 'preprocess-cor-k' beforehand.

  Since the format is exploded, this function produces superfluous triples.
  However, duplicate triples are automatically subsumed upon importing."
  [[id lemma comment grammar form normative :as row]]
  (let [{:keys [canonical]} (meta row)                      ; via preprocessing
        form-rel     (if (canonical id)
                       :ontolex/canonicalForm
                       :ontolex/otherForm)
        [_ cor-ns lemma-id form-id rep-id] (re-matches cor-id id)
        full         (cor-uri cor-ns lemma-id form-id rep-id)
        lexical-form (cor-uri cor-ns lemma-id form-id)
        word         (cor-uri cor-ns lemma-id)
        pos-abbr     (first (str/split grammar #"\."))
        pos          (get cor-k-pos pos-abbr)]
    (cond-> #{[word :rdf/type (form->lexical-entry lemma)]
              [word :rdfs/label (da (qt lemma))]
              [word form-rel lexical-form]

              [lexical-form :rdf/type :ontolex/Form]
              [lexical-form :rdfs/label (da (qt form "-form ("
                                                (if (= normative "0")
                                                  (str "unormeret: " grammar)
                                                  (str "" grammar))
                                                ")"))]
              [lexical-form :ontolex/writtenRep (da form)]}

      pos
      (conj [word :lexinfo/partOfSpeech pos])

      (not-empty comment)
      (conj [word :rdfs/comment (da comment)])

      ;; Since COR distinguishes alternative written representations with IDs,
      ;; this relation exists to avoid losing these distinctions in the dataset.
      ;; Alternative representations are represented with strings in Ontolex!
      rep-id
      (conj [lexical-form :dns/source full]))))

(h/defn ->cor-ext-triples
  [[id lemma comment _ _ _ grammar form :as row]]
  (->cor-k-triples (with-meta [id lemma comment grammar form] (meta row))))

(h/defn ->cor-link-triples
  [[id word-id sense-id :as row]]
  (let [[_ cor-ns lemma-id _ _] (re-matches cor-id id)
        cor-word (cor-uri cor-ns lemma-id)
        dn-word  (word-uri word-id)
        dn-sense (sense-uri sense-id)]
    #{[cor-word :owl/sameAs dn-word]
      [dn-word :owl/sameAs cor-word]
      [cor-word :ontolex/sense dn-sense]}))

(defn build-senseidx
  "Mapping from senseidx to Open English WordNet IDs.

  Uses the 'entries-*.yaml' files from the OEWN repository. It takes around 10
  seconds to create this data, so the corresponding .edn file is saved to disk."
  []
  (let [id->synset (atom {})
        dir        (io/file "bootstrap/other/english/yaml")]
    (doseq [file (remove #{dir} (file-seq dir))]
      (->> (io/reader file)
           (yaml/parse-stream)
           (walk/postwalk (fn [x]
                            (when (map? x)
                              (let [id     (get x :id)
                                    synset (some->>
                                             (get x :synset)
                                             (str "oewn-")
                                             (keyword "en"))]
                                (when (and id synset)
                                  (swap! id->synset assoc id synset))))
                            x))))
    (spit "bootstrap/other/english/senseidx.edn" (pr-str @id->synset))))

(h/def imports
  {prefix/dn-uri
   {:synsets    [->synset-triples "bootstrap/dannet/DanNet-2.5.1_csv/synsets.csv"]
    :relations  [->relation-triples "bootstrap/dannet/DanNet-2.5.1_csv/relations.csv"]
    :words      [->word-triples "bootstrap/dannet/DanNet-2.5.1_csv/words.csv"]
    :senses     [->sense-triples "bootstrap/dannet/DanNet-2.5.1_csv/wordsenses.csv"]
    :metadata   [nil metadata-triples]

    ;; Examples are a special case - these are not actual RDF triples!
    ;; Need to query the resulting graph to generate the real example triples.
    :examples   [examples "bootstrap/dannet/DanNet-2.5.1_csv/synsets.csv"]

    ;; The 2023 additions of mainly adjectives.
    :2023       [->2023-triples "bootstrap/other/dannet-new/adjectives.tsv"
                 :encoding "UTF-8"
                 :separator \tab
                 :preprocess (comp mark-duplicate-senses rest)]

    ;; Links to the Open English WordNet
    :oewn-links [->english-link-triples "bootstrap/dannet/DanNet-2.5.1_csv/relations.csv"]}

   ;; Received in email from Sanni 2022-05-23. File renamed, header removed.
   prefix/dds-uri
   {:sentiment [->sentiment-triples
                "bootstrap/other/sentiment/sense_polarities.tsv"
                :encoding "UTF-8"
                :separator \tab]}

   prefix/cor-uri
   {:cor-k        [->cor-k-triples "bootstrap/other/cor/cor1.02.tsv"
                   :encoding "UTF-8"
                   :separator \tab
                   :preprocess preprocess-cor]
    :cor-ext      [->cor-ext-triples "bootstrap/other/cor/corext1.0.tsv"
                   :encoding "UTF-8"
                   :separator \tab
                   :preprocess preprocess-cor]
    :cor-k-link   [->cor-link-triples "bootstrap/other/cor/ddo_bet_corlink.csv"
                   :encoding "UTF-8"
                   :separator \tab
                   :preprocess rest]
    :cor-ext-link [->cor-link-triples "bootstrap/other/cor/ddo_bet_corextlink.csv"
                   :encoding "UTF-8"
                   :separator \tab
                   :preprocess rest]}})

(defn- merge-args
  [[_ file & {:as opts}]]
  (into [first file] (apply concat (dissoc opts :merge))))

(defn- preprocess-fn-update
  [[row->triples file & {:keys [preprocess]
                         :as   opts}]
   preprocess-fn]
  (into [row->triples file]
        (apply concat (-> (if preprocess
                            opts
                            (assoc opts :preprocess identity))
                          (dissoc :merge)
                          (update :preprocess #(comp % preprocess-fn))))))

(h/defn read-triples
  "Return triples using `row->triples` from the rows of a DanNet CSV `file`.

  For the `opts`...

    - :encoding is assumed to be ISO-8859-1
    - :separator is assumed to be @
    - :preprocess fn is assumed to be 'identity' (no-op)
    - :merge is unused by default, but when provided it contain the args to this
      function for the CSV file to merge with. Additionally, each CSV file is
      assumed to have a comparable ID value in the first column."
  [[row->triples file & {:keys [encoding separator preprocess merge]
                         :or   {preprocess identity}
                         :as   opts}
    :as args]]
  (cond
    ;; Metadata triples are consumed directly
    (set? file)
    file

    ;; TODO: unused, remove again?
    ;; Once :merge args are provided, the two inputs will be merged.
    ;; If the same ID appears, the rows of the newer file are always preferred.
    merge
    (let [old-ids  (set (read-triples (merge-args args)))
          new-ids  (set (read-triples (merge-args merge)))
          in-old   (set/difference old-ids new-ids)
          in-new   (set/difference new-ids old-ids)
          new-file (second merge)
          retain   (partial filter (comp in-old first))]
      (do
        (println "\tMerging" file "and" new-file)
        (println "\tAdding" (count in-new) "new rows from" new-file)
        (println "\tRetaining" (count in-old) "rows in" file))
      (concat
        (read-triples (preprocess-fn-update args retain))
        (read-triples merge)))

    :else
    (with-open [reader (io/reader file :encoding (or encoding "ISO-8859-1"))]
      (->> (csv/read-csv reader :separator (or separator \@))
           (preprocess)
           (map row->triples)
           (doall)))))

(def hashes
  "A set of the hashes of the relevant bootstrap functions."
  (->> [#'imports
        #'read-triples
        #'->synset-triples
        #'->relation-triples
        #'->word-triples
        #'->sense-triples
        #'metadata-triples
        #'examples
        #'->2023-triples
        #'iri-encode
        #'->ddo-resource
        #'->english-link-triples
        #'->sentiment-triples
        #'->cor-k-triples
        #'->cor-ext-triples
        #'->cor-link-triples]
       (map (comp :hash meta))
       (set)))

(comment
  ;; Example 2023 adjective triples
  (->> (read-triples (get-in imports [prefix/dn-uri :2023]))
       (take 10))

  ;; Example sentiment triples
  (->> (read-triples (get-in imports [prefix/dds-uri :sentiment]))
       (take 10))

  ;; Example Synsets
  (->> (read-triples (get-in imports [prefix/dn-uri :synsets]))
       (take 10))

  ;; Example COR-K triples
  (->> (read-triples (get-in imports [prefix/cor-uri :cor-k]))
       (take 10))

  ;; Example COR.EXT triples
  (->> (read-triples (get-in imports [prefix/cor-uri :cor-ext]))
       (take 10))

  ;; Example COR link triples
  (->> (read-triples (get-in imports [prefix/cor-uri :cor-k-link]))
       (take 10))

  ;; Find facets, i.e. ontologicalType
  (->> (read-triples (get-in imports [prefix/dn-uri :synsets]))
       (reduce into #{})
       (filter (comp #{:dns/ontologicalType} second))
       (map #(nth % 2))
       (into #{})
       (map (comp symbol name))
       (sort))

  ;; Example Words
  (->> (read-triples (get-in imports [prefix/dn-uri :words]))
       (take 10))

  ;; Example Wordsenses
  (->> (read-triples (get-in imports [prefix/dn-uri :senses]))
       (take 10))

  ;; Example relations
  (->> (read-triples (get-in imports [prefix/dn-uri :relations]))
       (take 10))

  ;; Example links to the Open English WordNet
  (->> (read-triples (get-in imports [prefix/dn-uri :en-links]))
       (take 10))

  ;; unconverted relations
  (->> (read-triples (get-in imports [prefix/dn-uri :relations]))
       (map (comp second first))
       (filter string?)
       (into #{}))

  ;; Create a mapping from oldschool WordNet sense IDs to OEWN synset IDs
  (build-senseidx)
  @senseidx->english-synset

  ;; Find instances of a specific relation
  (let [rel "used_for_qualby"]
    (->> (read-triples (get-in imports [prefix/dn-uri :relations]))
         (filter (comp (partial = rel) second first))
         (into #{})))

  ;; Test COR regex
  (re-matches cor-id "COR.00010")
  (re-matches cor-id "COR.00010.800")
  (re-matches cor-id "COR.00010.880.01")
  (re-matches cor-id "COR.EXT.100099.123.01")

  ;; Edge cases while cleaning synset labels
  (rewrite-synset-label "{45-knallert; EU-knallert}")
  (rewrite-synset-label "{3. g'er_1}")
  (rewrite-synset-label "{tænke_13: tænke 'højt}")
  (rewrite-synset-label "{indtale_1; tale,2_26: tale 'ind}")
  (rewrite-synset-label "{pose,1_3: en pose penge}")
  (rewrite-synset-label "{folketing_2: Folketinget}")
  (rewrite-synset-label "{aften_12: i (går) aftes}")
  (rewrite-synset-label "{brud,2_2: hvid brud}")
  (rewrite-synset-label "{Q, q_1}")
  (rewrite-synset-label "{R,1_1}")
  (rewrite-synset-label "{gas-, vand- og sanitetsmester_1}")

  ;; Test rewriting sense labels
  (remove-prefix-apostrophes "glen's lade ''er 'fin")
  (rewrite-sense-label "word,1_8_2: word 'particle")
  (rewrite-sense-label "word_8_2: word 'particle")
  (rewrite-sense-label "tale_8_2: talens 'gaver")
  (rewrite-sense-label "tale_8_2: gavens 'taler")           ; test reverse MWE
  (rewrite-sense-label "word,1_8_2")
  (rewrite-sense-label "word_8_2")
  (rewrite-sense-label "DN:TOP,2_1_5")
  (rewrite-sense-label "friturestegning_0")
  (rewrite-sense-label "friturestegning,2_0")

  (fix-ellipsis "som vidner om el. er udtryk for en evne til at fre ...")

  ;; Error output for Nicolai.
  ;; The words.csv content differs somewhat between the new export and 2.2.
  (let [line->word-id (fn [[word-id]] word-id)
        words-25-res  "bootstrap/dannet/DanNet-2.5.1_csv/words.csv"
        words-22-res  "bootstrap/dannet/DanNet-2.2_csv/words.csv"
        words-25      (set (read-triples [line->word-id words-25-res]))
        words-22      (set (read-triples [line->word-id words-22-res]))
        missing-in-25 (set/difference words-22 words-25)
        new-in-25     (set/difference words-25 words-22)
        split         (fn [line] (str/split line #"@"))
        check-file    (fn [f pred out]
                        (with-open [reader (io/reader f :encoding "ISO-8859-1")]
                          (->> (line-seq reader)
                               (filter (comp pred first split))
                               (str/join "\n")
                               (spit out))))]
    (check-file words-22-res missing-in-25 "missing-in-dannet-25.txt")
    (check-file words-25-res new-in-25 "new-in-dannet-25.txt"))

  ;; At least the hypernyms seem to be distinct from the new adjectives.
  (let [rows (read-triples [identity
                            "bootstrap/other/dannet-new/adjectives.tsv"
                            :encoding "UTF-8"
                            :separator \tab
                            :preprocess rest])]
    ;; No apparent intersection
    (set/intersection (set (map #(nth % 5) rows))
                      (set (map #(nth % 7) rows))))
  #_.)
