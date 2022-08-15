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
            [ont-app.vocabulary.lstr :refer [->LangStr]]
            [better-cond.core :as better]
            [dk.cst.dannet.web.components :as com]
            [dk.cst.dannet.prefix :as prefix :refer [<dn>]])
  (:import [java.util Date]))

(defn da
  [s]
  (->LangStr s "da"))

;; TODO: add the others as contributors too
(def <simongray>
  (prefix/uri->rdf-resource "http://simongray.dk"))

;; TODO: add more, perhaps from dcat? rdf type indicating dataset?
(def metadata-triples
  "Metadata for the DanNet dataset is defined here since it doesn't have a
  associated .ttl file. The Dublin Core Terms NS is used below which supersedes
  the older DC namespace (see: https://www.dublincore.org/schemas/rdfs/ )."
  #{[<dn> :vann/preferredNamespacePrefix "dn"]
    [<dn> :vann/preferredNamespaceUri (prefix/prefix->uri 'dn)]
    [<dn> :dc/title "DanNet"]
    [<dn> :dc/description #lstr "The Danish WordNet.@en"]
    [<dn> :dc/description #lstr "Det danske WordNet.@da"]
    [<dn> :dc/issued #inst "2022-07-01"]                    ;TODO
    [<dn> :dc/modified (new Date)]
    [<dn> :dc/contributor <simongray>]
    [<dn> :dc/publisher "<http://cst.ku.dk>"]
    ;; TODO: should be dct:RightsStatement
    [<dn> :dc/rights #lstr "Copyright © University of Copenhagen & Society for Danish Language and Literature.@en"]
    ;; TODO: should be dct:LicenseDocument
    [<dn> :dc/license "<https://cst.ku.dk/projekter/dannet/license.txt>"]
    [<simongray> :rdf/type :foaf/Person]
    [<simongray> :foaf/name "Simon Gray"]
    [<simongray> :foaf/mbox "<mailto:simongray@hum.ku.dk>"]
    [<dn> :foaf/homepage <dn>]
    [<dn> :dcat/downloadURL (-> (prefix/prefix->uri 'dn)
                                (prefix/remove-trailing-slash)
                                (str ".ttl")
                                (prefix/uri->rdf-resource))]})

(defn synset-uri
  [id]
  (keyword "dn" (str "synset-" id)))

(defn word-uri
  [id]
  (keyword "dn" (str "word-" id)))

(defn sense-uri
  [id]
  (keyword "dn" (str "sense-" id)))

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

(defn lexical-form-uri
  [word-id form]
  ;; Originally, spaces were replaced with "_" but this caused issues with Jena
  ;; - specifically, some encoding issue related to TDB - so now "-" is used.
  (keyword "dn" (str "form-" word-id "-" (str/replace form #" |/" "-"))))

(def special-cases
  {"{DN:abstract_entity}"   "{DN:Abstract Entity}"
   "Torshavn|Thorshavn"     "Thorshavn"
   "{Torshavn|Thorshavn_1}" "{Thorshavn_1}"
   "Z, z"                   "Z"
   "Q, q"                   "Q"
   "Y, y"                   "Y"
   "Æ, æ"                   "Æ"})

;; TODO: remove relevant relations during bootstrap (hyper- and hypo-)
(def self-referential-hyponyms
  "These synsets for some reason list themselves as their hyponym."
  #{:dn/synset-3010 :dn/synset-48917})

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

(defn examples
  "Convert a `row` from 'synsets.csv' to example key-value pairs."
  [[synset-id label gloss _ :as row]]
  (when-let [[_ example-str] (re-find brug gloss)]
    (into {} (for [example (str/split example-str #" \|\| |\"; \"")]
               (when-let [token (determine-example-token label example)]
                 [[(synset-uri synset-id) token] (da example)])))))

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
  `ontological-type` string."
  [synset ontological-type]
  (for [concept (str/split ontological-type #"-")]
    [synset :dns/ontologicalType (keyword "dnc" concept)]))

(defn n->letter
  "Convert a number `n` to a letter in the English alphabet."
  [n]
  (char (+ n 96)))

(def old-multi-word
  #"([^,_]+)(,\d+)?((?:_\d+)+)?: (.+)")

(def old-single-word
  #"([^,_]+)(,\d+)?((?:_\d+)+)?")

;; Match weird alphabet entries like {Q, q_1}.
(def old-alphabet-combo
  #"([A-ZÆØÅ]), [a-zæøå](_.+)?")

(def old-inserted-by-DanNet-word
  #"DN:(.+)")

;; Read ./doc/label-rewrite.md for more.
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
    (sense-label w nil d)))

(def old-synset-sep
  #"\{|;|\}")

;; TODO: derive from ontolex:writtenRep? removes ordnet.dk connection, though...
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
        [synset :rdfs/label (da label)]
        [synset :skos/definition (da definition)]}
      (->> (clean-ontological-type "LanguageRepresentation+Artifact+Object")
           (explode-ontological-type synset)))))

(defn ->synset-triples
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
          #{[synset :rdf/type :ontolex/LexicalConcept]}
          (when (not-empty label)
            #{[synset :rdfs/label (da (rewrite-synset-label label))]})
          (when (and (not= definition "(ingen definition)")
                     (not (str/blank? definition)))
            #{[synset :skos/definition (da definition)]})
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

(defn ->relation-triples
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

;; TODO: investigate semantics of ' in input forms of multiword expressions
(defn ->word-triples
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
          lexical-form (lexical-form-uri word-id written-rep)
          fixed-pos    (when pos
                         (get pos-fixes word (str/lower-case pos)))]
      (set/union
        #{[lexical-form :rdf/type :ontolex/Form]
          [lexical-form :rdfs/label (da (qt written-rep "-form"))]

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
    (let [blank-node (symbol (str "_" (name sense) "-register"))]
      (cond-> #{[sense :lexinfo/usageNote blank-node]
                [blank-node :rdf/value register]}

        (re-find #"gl." register)
        (conj [sense :lexinfo/dating :lexinfo/old])

        (re-find #"sj." register)
        (conj [sense :lexinfo/frequency :lexinfo/rarelyUsed])

        (re-find #"jargon" register)
        (conj [sense :lexinfo/register :lexinfo/inHouseRegister])

        (re-find #"slang" register)
        (conj [sense :lexinfo/register :lexinfo/slangRegister])))))

(defn ->sense-triples
  "Convert a `row` from 'wordsenses.csv' to triples."
  [[sense-id word-id synset-id register _ :as row]]
  (when (and (= (count row) 5)
             (not (princeton-synset? synset-id)))
    (let [sense  (sense-uri sense-id)
          word   (word-uri word-id)
          synset (synset-uri synset-id)]
      (set/union
        (->register-triples sense register)
        #{[sense :rdf/type :ontolex/LexicalSense]
          [synset :ontolex/lexicalizedSense sense]}

        ;; The "inserted by DanNet" senses refer to the same dummy word, "TOP".
        ;; These relations make no sense to include. Instead, the necessary
        ;; words must be synthesized at a later point.
        (when (not= word :dn/word-0-0)
          #{[word :ontolex/evokes synset]
            [word :ontolex/sense sense]})))))

(def polarity-ratio
  "Convert the -3 to 3 score to a standard marl:polarityValue from 0.0 to 1.0."
  {-3 0.0
   -2 0.17
   -1 0.33
   0  0.5
   1  0.67
   2  0.85
   3  1.0})

;; TODO: map to senses (get data from Sussi, Sanni)
(defn ->sentiment-triples
  "Convert a `row` from '2_headword_headword_polarity.csv' to triples."
  [[word variant pos word-id score forms :as row]]
  (let [word     (word-uri word-id)
        _opinion (symbol (str "_" word "-_opinion"))
        v        (parse-long score)
        polarity (cond
                   (> v 0) :marl/Positive
                   (< v 0) :marl/Negative
                   :else :marl/Neutral)]
    (set/union
      #{[word :dns/sentiment _opinion]
        #_[_opinion :rdf/type :marl/Opinion]
        #_[_opinion :marl/describesObject word]
        [_opinion :marl/polarityValue (polarity-ratio v)]
        [_opinion :marl/hasPolarity polarity]})))

(defn- preprocess-cor-k
  "Add metadata to each row in `rows` to associate a canonical form with an ID."
  [rows]
  (let [m {:lemma->id (update-vals (group-by second rows) ffirst)}]
    (map #(with-meta % m) rows)))

(def cor-id
  "For splitting a COR-K id into: [id lemma-id form-id _ rep-id]."
  #"COR\.([^\.]+)\.([^\.]+)(\.([^\.]+))?")

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

   ;; Currently not supported, TODO: find lexinfo/wordnet equivalents
   "infinitivens" nil
   "fsubj"        nil
   "præfiks"      nil
   "talord"       nil
   "lydord"       nil
   "fork"         nil
   "flerord"      nil
   "kolon"        nil})

;; http://dsn.dk/sprogets-udvikling/sprogteknologi-og-fagsprog/cor#
(defn ->cor-k-triples
  "Convert a `row` from the COR-K ID file to triples; assumes that the
  rows have been preprocessed by 'preprocess-cor-k' beforehand.

  Since the format is exploded, this function produces superfluous triples.
  However, duplicate triples are automatically subsumed upon importing."
  [[id lemma definition grammar form :as row]]
  (let [lemma->id (:lemma->id (meta row))
        form-rel  (if (= (lemma->id lemma) id)
                    :ontolex/canonicalForm
                    :ontolex/otherForm)
        [id lemma-id form-id _ rep-id] (re-matches cor-id id)
        word-id   (keyword "cor" lemma-id)
        form-id   (keyword "cor" (str lemma-id "." form-id))
        pos-abbr  (first (str/split grammar #"\."))
        pos       (get cor-k-pos pos-abbr)]
    (cond-> #{[word-id :rdf/type (form->lexical-entry lemma)]
              [word-id :rdfs/label (da (qt lemma))]
              [word-id form-rel form-id]

              [form-id :rdf/type :ontolex/Form]
              [form-id :rdfs/label grammar]
              [form-id :ontolex/writtenRep (da form)]}

      (not-empty definition)
      (conj [word-id :skos/definition (da definition)])

      pos
      (conj [word-id :lexinfo/partOfSpeech pos])

      ;; Since COR distinguishes written representations with additional IDs,
      ;; this comment exists to avoid losing these distinctions in the dataset.
      rep-id
      (conj [form-id :rdfs/comment (da (str id " → " form))]))))

(def imports
  {:synsets   [->synset-triples (io/resource "dannet/csv/synsets.csv")]
   :relations [->relation-triples (io/resource "dannet/csv/relations.csv")]
   :words     [->word-triples (io/resource "dannet/csv/words.csv")]
   :senses    [->sense-triples (io/resource "dannet/csv/wordsenses.csv")]
   :metadata  [nil metadata-triples]
   :sentiment [->sentiment-triples
               (io/resource "2_headword_headword_polarity.csv")
               :encoding "UTF-8"
               :separator \tab]
   :cor-k     [->cor-k-triples (io/resource "cor/ro2021-0.1.cor")
               :encoding "UTF-8"
               :separator \tab
               :preprocess preprocess-cor-k]

   ;; Examples are a special case - these are not actual RDF triples!
   ;; Need to query the resulting graph to generate the real example triples.
   :examples  [examples (io/resource "dannet/csv/synsets.csv")]})

(defn read-triples
  "Return triples using `row->triples` from the rows of a DanNet CSV `file`."
  [[row->triples file & {:keys [encoding separator preprocess]
                         :or   {preprocess identity}
                         :as   opts}]]
  (if (set? file)                                           ; metadata triples?
    file
    (with-open [reader (io/reader file :encoding (or encoding "ISO-8859-1"))]
      (->> (csv/read-csv reader :separator (or separator \@))
           (preprocess)
           (map row->triples)
           (doall)))))

(comment
  ;; Example sentiment triples
  (->> (read-triples (:sentiment imports))
       (filter (comp not-empty second))
       (take 10))

  ;; Example Synsets
  (->> (read-triples (:synsets imports))
       (take 10))

  ;; Example COR-K triples
  (->> (read-triples (:cor-k imports))
       (take 10))

  ;; Find facets, i.e. ontologicalType
  (->> (read-triples (:synsets imports))
       (reduce into #{})
       (filter (comp #{:dns/ontologicalType} second))
       (map #(nth % 2))
       (into #{})
       (map (comp symbol name))
       (sort))

  ;; Example Words
  (->> (read-triples (:words imports))
       (take 10))

  ;; Example Wordsenses
  (->> (read-triples (:senses imports))
       (take 10))

  ;; Example relations
  (->> (read-triples (:relations imports))
       (take 10))

  ;; unconverted relations
  (->> (read-triples (:relations imports))
       (map (comp second first))
       (filter string?)
       (into #{}))

  ;; Find instances of a specific relation
  (let [rel "used_for_qualby"]
    (->> (read-triples (:relations imports))
         (filter (comp (partial = rel) second first))
         (into #{})))

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
  #_.)
