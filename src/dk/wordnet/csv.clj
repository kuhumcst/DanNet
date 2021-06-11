(ns dk.wordnet.csv
  "Mapping the old DanNet CSV export to Ontolex-lemon.

  The following relations are excluded as part of the import:
    - eq_has_synonym:   mapping to an old version of Princeton Wordnet
    - eq_has_hyponym:   mapping to an old version of Princeton Wordnet
    - eq_has_hyperonym: mapping to an old version of Princeton Wordnet
    - used_for_qualby:  not in use, just 1 full triple + 3 broken ones

  See: https://www.w3.org/2016/05/ontolex"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.data.csv :as csv]))

(defn synset-uri
  [id]
  (keyword "dn" (str "synset-" id)))

(defn word-uri
  [id]
  (keyword "dn" (str "word-" id)))

(defn wordsense-uri
  [id]
  (keyword "dn" (str "wordsense-" id)))

(def brug
  #"\s*\(Brug: \"(.+)\"")

;; TODO: get this approved
(defn lexical-form-uri
  [word-id form]
  (keyword "dn" (str "form-" word-id "-" (str/replace form #" " "_"))))

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

(defn determine-usage-word
  "Given a `label` from the synsets.csv file and an example `usage`,
  determine which of the words in the label the example pertains to."
  [label usage]
  (let [usage* (str/lower-case usage)
        label* (str/lower-case label)
        words  (map str/trim (re-seq #"[-æøå a-z]+" label*))]
    (loop [[word & rem-words] words]
      (when word
        (if (str/includes? usage* word)
          word
          (recur rem-words))))))

;; TODO: usage triples
;; https://www.w3.org/2016/05/ontolex/#usage
;; Ideally this should be triples of the form:
#_#{[wordsense :ontolex/usage '_usage]
    ['_usage :rdf/value usage-example]}
;; This requires mapping the word form and synset to the matching wordsense.

(defn ->synset-usages
  "Convert a `row` from 'synsets.csv' to usage key-value pairs."
  [[synset-id label gloss _ :as row]]
  (when-let [[_ usage-str] (re-find brug gloss)]
    (for [usage (str/split usage-str #" \|\| |\"; \"")]
      (when-let [word (determine-usage-word label usage)]
        [(synset-uri synset-id) word usage]))))

(defn ->synset-triples
  "Convert a `row` from 'synsets.csv' to triples."
  [[synset-id label gloss ontological-type :as row]]
  (when (= (count row) 5)
    (let [synset (synset-uri synset-id)]
      #{[synset :rdfs/label label]
        ;; TODO: separate usage example from definition
        [synset :skos/definition (str/replace gloss brug "")]
        [synset :dns/ontologicalType ontological-type]
        [synset :rdf/type :ontolex/LexicalConcept]})))

;; TODO: generate reverse relations?
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
        [word :rdf/type :ontolex/lexicalEntry]
        [word :rdf/type (form->lexical-entry form)]})))

;; TODO: register mapping
;; https://www.w3.org/2016/05/ontolex/#lexical-sense-reference
(defn ->wordsense-triples
  "Convert a `row` from 'wordsenses.csv' to triples."
  [[wordsense-id word-id synset-id register :as row]]
  (when (= (count row) 5)
    (let [wordsense (wordsense-uri wordsense-id)
          word      (word-uri word-id)
          synset    (synset-uri synset-id)]
      #{[wordsense :rdf/type :ontolex/LexicalSense]
        [wordsense :ontolex/isLexicalizedSenseOf synset]
        [wordsense :ontolex/isSenseOf word]

        [word :ontolex/evokes synset]
        [word :ontolex/sense wordsense]

        [synset :ontolex/isEvokedBy word]
        [synset :ontolex/lexicalizedSense wordsense]})))

(defn unmapped?
  [triples]
  (some (comp string? second) triples))

(def csv-imports
  {:synsets    [->synset-triples (io/resource "dannet/csv/synsets.csv")]
   :relations  [->relation-triples (io/resource "dannet/csv/relations.csv")]
   :words      [->word-triples (io/resource "dannet/csv/words.csv")]
   :wordsenses [->wordsense-triples (io/resource "dannet/csv/wordsenses.csv")]})

(defn read-triples
  "Return triples using `row->triples` from the rows of a DanNet CSV `file`."
  [row->triples file]
  (with-open [reader (io/reader file :encoding "ISO-8859-1")]
    (->> (csv/read-csv reader :separator \@)
         (map row->triples)
         (doall))))

(comment
  ;; Example usages
  (->> (read-triples ->synset-usages (io/resource "dannet/csv/synsets.csv"))
       (remove nil?)
       (take 10))

  ;; Example Synsets
  (->> (apply read-triples (resources :synsets))
       (take 10))

  ;; Example Words
  (->> (apply read-triples (resources :words))
       (take 10))

  ;; Example Wordsenses
  (->> (apply read-triples (resources :wordsenses))
       (take 10))

  ;; Example relations
  (->> (apply read-triples (resources :relations))
       (remove unmapped?)
       (take 10))

  ;; unconverted relations
  (->> (apply read-triples (resources :relations))
       (map (comp second first))
       (filter string?)
       (into #{}))

  ;; Find instances of a specific relation
  (let [rel "used_for_qualby"]
    (->> (read-triples ->relation-triples (resources :relations))
         (filter (comp (partial = rel) second first))
         (into #{})))
  #_.)
