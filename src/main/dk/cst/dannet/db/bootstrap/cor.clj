(ns dk.cst.dannet.db.bootstrap.cor
  "Convert the COR source files into the triples of the cor: graph.

  COR is built from the files published by Dansk Sprognævn rather than carried
  over from the previous release's own export, so that every triple remains
  traceable to a source file. The links tying COR IDs to DanNet words and
  senses come from DSL instead: the COR.EXT link file survives, while its COR₁
  counterpart (a 2022 email attachment) is lost, so those links are carried
  over from the previous release's graph by bootstrap/rebuild-cor-graph!.
  COR words without a link are still imported, as COR is exported as a
  dataset in its own right."
  (:require [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [dk.cst.dannet.db.bootstrap.metadata :as md]
            [dk.cst.dannet.hash :as h]
            [dk.cst.dannet.release :as release]))

(def source-dir
  "bootstrap/other/cor")

(def cor-id
  "Splits a COR ID into its namespace, lemma ID, form ID and representation ID.

  The namespace part is absent in COR₁ IDs and \"EXT\" in COR.EXT IDs, while
  the two trailing parts are absent from the ID of a word."
  #"COR\.(?:([^\d]+)\.)?([^\.]+)(?:\.([^\.]+))?(?:\.([^\.]+))?")

(defn cor-uri
  "The cor: resource for the ID `parts`, e.g. (cor-uri \"EXT\" \"100002\").

  The \"COR.\" prefix stays in the local name because a QName may not begin
  with a dot and a keyword may not begin with a digit."
  [& parts]
  (keyword "cor" (str "COR." (str/join "." (remove nil? parts)))))

(defn word-uri
  [id]
  (keyword "dn" (str "word-" id)))

(defn sense-uri
  [id]
  (keyword "dn" (str "sense-" id)))

(defn qt
  "Quote `s`, followed by any `after` parts."
  [s & after]
  (apply str "\"" s "\"" after))

(def pos-tags
  "The part-of-speech abbreviations of the COR grammar labels mapped to the
  matching Lexinfo resource.

  Together with word-type-tags, every abbreviation occurring in the source
  files is listed, including the ones that map to nothing; a nil simply
  leaves the word without an asserted part of speech."
  {"sb"        :lexinfo/noun
   "vb"        :lexinfo/verb
   "adj"       :lexinfo/adjective
   "adv"       :lexinfo/adverb
   "konj"      :lexinfo/conjunction
   "præp"      :lexinfo/preposition
   "prop"      :lexinfo/properNoun
   "udråbsord" :lexinfo/interjection
   "pron"      :lexinfo/pronoun
   "talord"    :lexinfo/numeral

   ;; TODO: find lexinfo/wordnet equivalents
   "art"       nil
   "flerord"   nil
   "fork"      nil
   "formsubj"  nil
   "iflerord"  nil
   "infmærke"  nil
   "romertal"  nil})

(def word-type-tags
  "The abbreviations denoting a class of lexical entry rather than a part of
  speech, mapped to the matching Lexinfo -- or Olia -- class and asserted as
  rdf:type. Lexinfo has no PartOfSpeech values for these, only classes, and
  lexinfo:partOfSpeech conventionally takes individuals rather than classes."
  {"præfiks" :lexinfo/Prefix
   "suffiks" :lexinfo/Suffix
   "lydord"  :olia/OnomatopoeticWord})

(def normativity
  "The COR normering codes mapped to the rdfs:comment stating the norm status
  of a form; consumed as such by the DMLex export. N (normeret) is the
  default and so is absent.

  COR₁ used 0 and 1 until version 1.5.0.0, where 1 was divided into N and K."
  {"U" "unormeret"
   "K" "ikke normeret, men sandsynligvis korrekt"})

(defn form->lexical-entry
  "Derive the Ontolex LexicalEntry type from the `form` of a word."
  [form]
  (cond
    (re-find #" " form)
    :ontolex/MultiwordExpression

    (or (str/starts-with? form "-")
        (str/ends-with? form "-"))
    :ontolex/Affix

    :else :ontolex/Word))

(defn mark-canonical
  "Mark every row of `rows` with the set of IDs holding a canonical form.

  COR keeps the rows of a lemma together and lists its canonical form first,
  so the first ID of every run of identical lemmas is the canonical one."
  [rows]
  (let [canonical (->> (partition-by second rows)
                       (map ffirst)
                       (set))]
    (map #(with-meta % {:canonical canonical}) rows)))

(h/defn ->cor-triples
  "Convert a `row` of the COR₁ file to triples; assumes the rows have been
  through mark-canonical beforehand.

  The exploded source format restates a word for each of its forms, so the
  word-level triples are produced many times over. Duplicates are subsumed on
  import and the redundancy is not worth avoiding here."
  [[id lemma comment grammar form normative :as row]]
  (let [{:keys [canonical]} (meta row)
        form-rel     (if (canonical id)
                       :ontolex/canonicalForm
                       :ontolex/otherForm)
        [_ cor-ns lemma-id form-id rep-id] (re-matches cor-id id)
        full         (cor-uri cor-ns lemma-id form-id rep-id)
        lexical-form (cor-uri cor-ns lemma-id form-id)
        word         (cor-uri cor-ns lemma-id)
        tag          (first (str/split grammar #"\."))
        pos          (get pos-tags tag)
        word-type    (get word-type-tags tag)]
    (cond-> #{[word :rdf/type (form->lexical-entry lemma)]
              [word :rdfs/label (md/da (qt lemma))]
              [word form-rel lexical-form]

              [lexical-form :rdf/type :ontolex/Form]
              [lexical-form :rdfs/label (md/da (qt form "-form (" grammar ")"))]
              [lexical-form :ontolex/writtenRep (md/da form)]}

      pos
      (conj [word :lexinfo/partOfSpeech pos])

      word-type
      (conj [word :rdf/type word-type])

      (normativity normative)
      (conj [lexical-form :rdfs/comment (md/da (normativity normative))])

      (not-empty comment)
      (conj [word :rdfs/comment (md/da comment)])

      ;; Ontolex has no way to tell alternative written representations apart,
      ;; while COR gives each an ID; the source link preserves the distinction.
      rep-id
      (conj [lexical-form :dc/source full]))))

(h/defn ->cor-ext-triples
  "Convert a `row` of the COR.EXT file to triples.

  COR.EXT states the same fields as COR₁ in a different order, with a DDO
  entry ID and a normering code in between that the conversion doesn't use."
  [[id lemma comment _pos _ddo-id _normative grammar form :as row]]
  (->cor-triples (with-meta [id lemma comment grammar form] (meta row))))

(h/defn ->cor-link-triples
  "Convert a `row` of either link file to the triples tying a COR word to the
  DanNet word and sense of the DDO entry it was matched with."
  [[id word-id sense-id]]
  (let [[_ cor-ns lemma-id] (re-matches cor-id id)
        cor-word (cor-uri cor-ns lemma-id)
        dn-word  (word-uri word-id)
        dn-sense (sense-uri sense-id)]
    #{[cor-word :owl/sameAs dn-word]
      [dn-word :owl/sameAs cor-word]
      [cor-word :dns/linkedSense dn-sense]}))

(defn file->triples
  "The triples of the tab-separated `filename` in source-dir, as produced by
  `row->triples` from its rows -- optionally rearranged by `preprocess` first."
  [row->triples filename & {:keys [preprocess]
                            :or   {preprocess identity}}]
  (with-open [reader (io/reader (io/file source-dir filename) :encoding "UTF-8")]
    (->> (csv/read-csv reader :separator \tab)
         (preprocess)
         (mapcat row->triples)
         (into #{}))))

(defn source-triples
  "Every word and form triple of the COR graph, read from the source files."
  []
  (into (file->triples ->cor-triples (str "cor" release/cor-version ".tsv")
                       :preprocess mark-canonical)
        (file->triples ->cor-ext-triples (str "corext" release/cor-ext-version ".tsv")
                       :preprocess mark-canonical)))

(defn ext-link-triples
  "The triples tying COR.EXT words to the DanNet words and senses matched by
  DSL. The link file carries a header row, while the data files do not."
  []
  (file->triples ->cor-link-triples "ddo_bet_corextlink.csv"
                 :preprocess rest))

(def changelogs
  "The official changelogs covering the COR₁ editions between the 2022 import
  of 1.02 and release/cor-version, in order."
  ["cor1.02-cor1.5.0.0.cordiff"
   "cor1.5.0.0-cor1.5.1.0.cordiff"])

(defn- lemma-id
  "The lemma-level part of the COR `id`, e.g. \"COR.02139\" of
  \"COR.02139.400.01\"."
  [id]
  (str/join "." (take 2 (str/split id #"\."))))

(defn- changelog-remap
  "Map of old to new lemma-level IDs, read from the MRG, MOV and REP lines of
  the changelog `filename`; a lemma divided across several new IDs maps to
  all of them."
  [filename]
  (with-open [reader (io/reader (io/file source-dir filename) :encoding "UTF-8")]
    (->> (line-seq reader)
         (keep (partial re-matches #"(?:MRG|MOV|REP) (\S+) < (\S+)"))
         (reduce (fn [m [_ new-id old-id]]
                   (update m (lemma-id old-id) (fnil conj #{}) (lemma-id new-id)))
                 {}))))

(h/defn id-remap
  "Map of 1.02-era lemma-level IDs to the set of IDs replacing them in
  release/cor-version, composed from the changelogs.

  Only replacements are recorded: an ID absent from the map is unchanged or
  gone entirely. Whether a mapped ID itself exists is left for the caller to
  check, as a replacement may in turn have been deleted by a later edition."
  []
  (let [[m1 m2] (map changelog-remap changelogs)
        expand (fn [m id] (get m id #{id}))]
    (into {}
          (keep (fn [id]
                  (let [ids (set (mapcat (partial expand m2) (expand m1 id)))]
                    (when (not= ids #{id})
                      [id ids]))))
          (set (concat (keys m1) (keys m2))))))

(comment
  ;; A single word with both a normeret and an unormeret form.
  (->> (mark-canonical [["COR.00010.400.01" "ifølge" "" "fork" "if." "N"]
                        ["COR.00010.880.01" "ifølge" "" "præp" "ifølge" "N"]])
       (mapcat ->cor-triples)
       (into #{}))

  ;; The form comments produced by the normering codes.
  (map normativity ["N" "U" "K"])

  (count (source-triples))
  (count (ext-link-triples))
  (count (id-remap))
  #_.)
