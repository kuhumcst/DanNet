(ns dk.wordnet.bootstrap
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
            [clojure.data.csv :as csv]))

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
  ;; - specifically, some encoding issue related to TDB - so now "+" is used.
  (keyword "dn" (str "form-" word-id "-" (str/replace form #" " "+"))))

(def special-case->replacement
  {"Torshavn|Thorshavn"     "Thorshavn"
   "{Torshavn|Thorshavn_1}" "{Thorshavn_1}"})

;; Note: a single "used_for_qualby" rel exists in the dataset - likely an error
;; https://github.com/globalwordnet/schemas
;; https://github.com/globalwordnet/schemas/blob/master/wn-lemon-1.1.ttl
;; https://github.com/globalwordnet/schemas/blob/master/wn-lemon-1.1.rdf
;; @prefix wn: <https://globalwordnet.github.io/schemas/wn#> .
(def gwa-rel
  {"concerns"            :wn/also
   "used_for"            :wn/instrument
   "used_for_object"     :wn/involved_instrument
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
   "involved_agent"      :wn/co_agent_instrument
   "involved_instrument" :wn/co_instrument_agent
   "involved_patient"    :wn/involved_patient
   "made_by"             :wn/result
   "near_synonym"        :wn/similar
   "near_antonym"        :wn/antonym
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

;; This is an imperfect method of mapping usages to senses, since the tokens of
;; the example sentences have not been lemmatised. It would be preferable to get
;; the full set of usage sentences mapped correctly to senses by DSL.
(defn determine-usage-token
  "Given a `label` from the synsets.csv file and an example `usage`,
  determine which of the words in the label the example pertains to."
  [label usage]
  (let [usage* (str/lower-case usage)
        label* (str/lower-case label)]
    (loop [[token & tokens] (map str/trim (re-seq #"[-æøå a-z]+" label*))]
      (when token
        (if (str/includes? usage* token)
          token
          (recur tokens))))))

(defn usages
  "Convert a `row` from 'synsets.csv' to usage key-value pairs."
  [[synset-id label gloss _ :as row]]
  (when-let [[_ usage-str] (re-find brug gloss)]
    (into {} (for [usage (str/split usage-str #" \|\| |\"; \"")]
               (when-let [token (determine-usage-token label usage)]
                 [[(synset-uri synset-id) token] usage])))))

(defn sanitize-ontological-type
  "Sanitizes the `ontological-type` string before conversion to resource names.

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
    [synset :dns/ontologicalFacet (keyword "dnc" concept)]))

(defn ->synset-triples
  "Convert a `row` from 'synsets.csv' to triples.

  The legacy `ontological-type` string is converted into facet triples along
  with an ontologicalType triple which is analogous to the old composite string.
  All the resulting triples reference RDF resources rather than plain strings."
  [[synset-id label gloss ontological-type :as row]]
  (when (= (count row) 5)
    (let [synset            (synset-uri synset-id)
          ontological-type* (sanitize-ontological-type ontological-type)
          definition        (str/replace gloss brug "")]
      (set/union
        #{[synset :rdfs/label (get special-case->replacement label label)]
          [synset :rdf/type :ontolex/LexicalConcept]
          [synset :dns/ontologicalType (keyword "dnc" ontological-type*)]}
        (when (not= definition "(ingen definition)")
          #{[synset :skos/definition definition]})
        (explode-ontological-type synset ontological-type*)))))

(defn- adjust-comment
  "Slighly rewords the inheritance comments present in the old DanNet dataset."
  [comment]
  (-> comment
      (str/replace #"^Inherited from" "Some relations inherited from")
      (str/replace #"synset with id (\d+)" "dn:synset-$1")
      (str/replace #" \((.+)\)$" " $1.")))

(defn ->relation-triples
  "Convert a `row` from 'relations.csv' to triples.

  Note: certain rows are unmapped, so the relation will remain a string!"
  [[subj-id _ rel obj-id taxonomic inheritance :as row]]
  (when (and (= (count row) 7)
             (not (ignored-relations rel)))
    (let [subj    (synset-uri subj-id)
          obj     (synset-uri obj-id)
          comment (not-empty inheritance)
          triples (if (and (= taxonomic "nontaxonomic")
                           (= rel "has_hyperonym"))
                    #{[subj :dns/orthogonalHypernym obj]}
                    #{[subj (gwa-rel rel) obj]})]
      (if comment
        (conj triples [subj :rdfs/comment (adjust-comment comment)])
        triples))))

;; TODO: can we create new forms/words/synsets rather than overload writtenRep?
(defn explode-written-reps
  "Create writtenRep triple(s) based on a `lexical-form` and a `written-rep`.

  In certain cases, multi-word expressions mark multiple written representations
  using slashes. These representations are exploded into multiple triples."
  [lexical-form written-rep]
  (if-let [block (re-find #"[^\s]+/[^\s]+" written-rep)]
    (let [replace-block (partial str/replace written-rep block)]
      (for [exploded-rep (map replace-block (str/split block #"/"))]
        [lexical-form :ontolex/writtenRep exploded-rep]))
    #{[lexical-form :ontolex/writtenRep written-rep]}))

(def pos-fixes
  "Ten words had 'None' as their POS tag. Looking at the other words in their
  synsets clearly inform the correct POS tags to use."
  {:dn/word-12005324-2 "Adjective"
   :dn/word-12002785   "Noun"
   :dn/word-11006697   "Noun"
   :dn/word-11022554   "Noun"
   :dn/word-11043739   "Noun"
   :dn/word-12007550   "Noun"
   :dn/word-11038834   "Noun"
   :dn/word-11047932   "Noun"
   :dn/word-12005626-1 "Verb"
   :dn/word-11018863   "Noun"})

;; TODO: investigate semantics of ' in input forms of multiword expressions
(defn ->word-triples
  "Convert a `row` from 'words.csv' to triples."
  [[word-id form pos :as row]]
  (when (and (= (count row) 4)
             (not= word-id "None-None"))                    ; a special case
    (let [word         (word-uri word-id)
          form         (get special-case->replacement form form)
          rdf-type     (form->lexical-entry form)
          written-rep  (if (= rdf-type :ontolex/MultiwordExpression)
                         (str/replace form #"'" "")
                         form)
          lexical-form (lexical-form-uri word-id written-rep)
          fixed-pos    (get pos-fixes word pos)]
      (set/union
        #{[lexical-form :rdf/type :ontolex/Form]

          [word :rdf/type rdf-type]
          [word :rdfs/label written-rep]
          [word :ontolex/canonicalForm lexical-form]

          ;; GWA and Ontolex have competing part-of-speech relations.
          ;; Ontolex prefers Lexinfo's relation, while GWA defines its own.
          ;; At the moment, we infer the ontolex:partOfSpeech relation using
          ;; the wn:PartOfSpeech instance as the object. Ideally, we would
          ;; also - only - infer the wn:PartOfSpeech owl:sameAs instance.
          ;; TODO: improve inference, look at other .rules files
          [word :wn/partOfSpeech (keyword "wn" (str/lower-case fixed-pos))]}
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
  [[sense-id word-id synset-id register :as row]]
  (when (and (= (count row) 5)
             (not (princeton-synset? synset-id)))
    (let [sense  (sense-uri sense-id)
          word   (word-uri word-id)
          synset (synset-uri synset-id)]
      (set/union
        (->register-triples sense register)
        #{[sense :rdf/type :ontolex/LexicalSense]
          [word :ontolex/evokes synset]
          [word :ontolex/sense sense]
          [synset :ontolex/lexicalizedSense sense]}))))

(def imports
  {:synsets   [->synset-triples (io/resource "dannet/csv/synsets.csv")]
   :relations [->relation-triples (io/resource "dannet/csv/relations.csv")]
   :words     [->word-triples (io/resource "dannet/csv/words.csv")]
   :senses    [->sense-triples (io/resource "dannet/csv/wordsenses.csv")]

   ;; Usages are a special case - these are not actual RDF triples!
   ;; Need to query the resulting graph to generate the real usage triples.
   :usages    [usages (io/resource "dannet/csv/synsets.csv")]})

(defn read-triples
  "Return triples using `row->triples` from the rows of a DanNet CSV `file`."
  [[row->triples file]]
  (with-open [reader (io/reader file :encoding "ISO-8859-1")]
    (->> (csv/read-csv reader :separator \@)
         (map row->triples)
         (doall))))

(comment
  ;; Example Synsets
  (->> (read-triples (:synsets imports))
       (take 10))

  ;; Find concepts (or alternatively, concept composites)
  (->> (read-triples (:synsets imports))
       (reduce into #{})
       (filter (comp #{:dns/ontologicalFacet} second))
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
  #_.)
