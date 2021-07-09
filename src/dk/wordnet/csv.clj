(ns dk.wordnet.csv
  "Mapping the old DanNet CSV export to Ontolex-lemon.

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
  #"\s*\(Brug: \"(.+)\"")

;; TODO: get this approved
(defn lexical-form-uri
  [word-id form]
  ;; Originally, spaces were replaced with "_" but this caused issues with Jena
  ;; - specifically, some encoding issue related to TDB - so now "+" is used.
  (keyword "dn" (str "form-" word-id "-" (str/replace form #" " "+"))))

;; Note: a single "used_for_qualby" rel exists in the dataset - likely an error
;; https://github.com/globalwordnet/schemas
;; https://github.com/globalwordnet/schemas/blob/master/wn-lemon-1.1.ttl
;; https://github.com/globalwordnet/schemas/blob/master/wn-lemon-1.1.rdf
;; @prefix wn: <https://globalwordnet.github.io/schemas/wn#> .
(def gwa-rel
  {"concerns"            :wn/also                           ;TODO
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
   "involved_agent"      :wn/co_agent_instrument            ;TODO
   "involved_instrument" :wn/co_instrument_agent            ;TODO
   "involved_patient"    :wn/involved_patient               ;TODO
   "made_by"             :wn/result
   "near_synonym"        :wn/similar
   "near_antonym"        :wn/antonym                        ;TODO: information loss?
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

(defn ->synset-triples
  "Convert a `row` from 'synsets.csv' to triples."
  [[synset-id label gloss ontological-type :as row]]
  (when (= (count row) 5)
    (let [synset (synset-uri synset-id)]
      #{[synset :rdfs/label label]
        [synset :skos/definition (str/replace gloss brug "")]
        [synset :dns/ontologicalType ontological-type]
        [synset :rdf/type :ontolex/LexicalConcept]})))

;; TODO: inheritance comment currently ignored - what to do?
(defn ->relation-triples
  "Convert a `row` from 'relations.csv' to triples.

  Note: certain rows are unmapped, so the relation will remain a string!"
  [[subj-id _ rel obj-id taxonomic _ :as row]]
  (when (= (count row) 7)
    (let [subj (synset-uri subj-id)
          obj  (synset-uri obj-id)]
      (if (and (= taxonomic "nontaxonomic")
               (= rel "has_hyperonym"))
        #{[subj :dns/hypernym_ortho obj]
          [subj (gwa-rel rel) obj]}
        (if-let [rel* (gwa-rel rel)]
          #{[subj rel* obj]}
          #{[subj rel obj-id]})))))

(defn ->word-triples
  "Convert a `row` from 'words.csv' to triples."
  [[word-id form pos :as row]]
  (when (= (count row) 4)
    (let [word         (word-uri word-id)
          lexical-form (lexical-form-uri word-id form)]
      #{[lexical-form :ontolex/writtenRep form]
        [lexical-form :rdf/type :ontolex/Form]

        [word :rdfs/label form]
        [word :ontolex/canonicalForm lexical-form]
        [word :lexinfo/partOfSpeech pos]
        [word :rdf/type (form->lexical-entry form)]

        ;; This is inferred by the subclass provided by form->lexical-entry
        #_[word :rdf/type :ontolex/LexicalEntry]})))

(defn- ->register-triples
  "Convert the `register` of a `sense` to appropriate triples."
  [sense register]
  (if (empty? register)
    #{}
    (let [blank-node (symbol (str "_" (name sense) "-register"))]
      ;; TODO: :lexinfo/usageNote or :ontolex/usage?
      (cond-> #{[sense :lexinfo/usageNote blank-node]
                [blank-node :rdf/value register]}

        (re-find #"gl." register)
        (conj [sense :lexinfo/dating :lexinfo/old])

        (re-find #"sj." register)
        (conj [sense :lexinfo/frequency :lexinfo/rarelyUsed])

        (re-find #"slang" register)
        (conj [sense :lexinfo/register :lexinfo/slangRegister])))))

(defn ->sense-triples
  "Convert a `row` from 'wordsenses.csv' to triples."
  [[sense-id word-id synset-id register :as row]]
  (when (= (count row) 5)
    (let [sense  (sense-uri sense-id)
          word   (word-uri word-id)
          synset (synset-uri synset-id)]
      (set/union
        (->register-triples sense register)
        #{[sense :rdf/type :ontolex/LexicalSense]
          [word :ontolex/evokes synset]
          [word :ontolex/sense sense]
          [synset :ontolex/lexicalizedSense sense]

          ;; Inverse relations (handled by OWL inference instead)
          #_[synset :ontolex/isEvokedBy word]
          #_[sense :ontolex/isSenseOf word]
          #_[sense :ontolex/isLexicalizedSenseOf synset]}))))

(defn unmapped?
  [triples]
  (some (comp string? second) triples))

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
  [row->triples file]
  (with-open [reader (io/reader file :encoding "ISO-8859-1")]
    (->> (csv/read-csv reader :separator \@)
         (map row->triples)
         (doall))))

(comment
  ;; Example Synsets
  (->> (apply read-triples (:synsets imports))
       (take 10))

  ;; Example Words
  (->> (apply read-triples (:words imports))
       (take 10))

  ;; Example Wordsenses
  (->> (apply read-triples (:senses imports))
       (take 10))

  ;; Example relations
  (->> (apply read-triples (:relations imports))
       (remove unmapped?)
       (take 10))

  ;; unconverted relations
  (->> (apply read-triples (:relations imports))
       (map (comp second first))
       (filter string?)
       (into #{}))

  ;; Find instances of a specific relation
  (let [rel "used_for_qualby"]
    (->> (apply read-triples (:relations imports))
         (filter (comp (partial = rel) second first))
         (into #{})))
  #_.)
