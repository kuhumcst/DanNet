(ns dk.cst.dannet.crosspos-review
  "Regenerate the cross-PoS review workbooks in doc/crosspos/ from the live
  graph.

  These replace a hand-assembled spreadsheet that had silently drifted from the
  data (it was missing two rows of the 2d set). Everything here is derived from
  the graph instead, with manually entered columns merged forward from the
  existing workbooks so review work is never lost.

  Usage from the REPL, with a built database:

    (regenerate! (:dataset @dk.cst.dannet.web.instance/db))"
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [dk.ative.docjure.spreadsheet :as xl]
            [dk.cst.dannet.db :as db]
            [dk.cst.dannet.db.query :as q]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.shared :as shared])
  (:import [org.apache.poi.common.usermodel HyperlinkType]
           [org.apache.poi.ss.usermodel BorderStyle CellType FillPatternType
                                        Font IndexedColors]
           [org.apache.poi.ss.util CellRangeAddressList]
           [org.apache.poi.xssf.usermodel XSSFDataValidationHelper]))

(def files
  {:2d "doc/crosspos/2d-cross-pos-taxonomy.xlsx"
   :a4 "doc/crosspos/a4-deferred-crosspos.xlsx"})

(def headers
  "Reviewer-facing columns first, machinery last. Both files open on the synset
  being judged, so the reviewer does not have to reorient between them."
  {:2d ["synset" "ordklasser" "nuværende hypernym" "antal i gruppen"
        "forslag" "nyt hypernym" "ny relation" "status" "kommentar"
        "synset URI" "hypernym URI"]
   :a4 ["synset" "ordklasser" "nuværende hypernym" "gruppe"
        "forslag" "nyt hypernym" "ny relation" "status" "kommentar"
        "synset URI" "hypernym URI"]})

(def header-aliases
  "Old English headers, for reading workbooks written before the rename.
  Delete once the Danish workbooks are committed."
  {"source"              "synset"
   "source URI"          "synset URI"
   "target"              "nuværende hypernym"
   "target URI"          "hypernym URI"
   "n"                   "antal i gruppen"
   "group"               "gruppe"
   "retarget candidates" "forslag"
   "suggestion"          "forslag"
   "retarget to"         "nyt hypernym"
   "new relation"        "ny relation"
   "decision"            "status"
   "comment"             "kommentar"})

(def statuses
  "Dropdown values for the status column.

  Filling in `nyt hypernym` or `ny relation` already says the row changes, so
  status only covers the cases with no edit to make. Blank means nobody has
  looked at the row yet."
  ["" "beholdes" "slettes" "ordklassefejl" "i tvivl"])

(def relations
  "Dropdown values for the new relation column, taken from the relations
  actually in use. The two dns:crossPoS* relations are omitted: replacing them
  is the point of the exercise."
  (->> (keys shared/synset-rel-theme)
       (remove #{:dns/crossPoSHypernym :dns/crossPoSHyponym})
       (map #(str (namespace %) ":" (name %)))
       (sort)
       (into [""])))

(def column-widths
  {"synset"             34
   "ordklasser"         12
   "nuværende hypernym" 34
   "antal i gruppen"    9
   "gruppe"             14
   "forslag"            40
   "nyt hypernym"       34
   "ny relation"        22
   "status"             15
   "kommentar"          40
   "synset URI"         30
   "hypernym URI"       30})

(def link-columns
  "Label column -> the URI column its hyperlink comes from. The reviewer clicks
  the readable name, so the URI columns stay out of the way on the right."
  {"synset"             "synset URI"
   "nuværende hypernym" "hypernym URI"})

(def pos-abbr
  "wn:partOfSpeech names to Danish abbreviations.

  Not shared/pos-abbr-da: that map is keyed on \"adj\", while wn:partOfSpeech
  gives \"adjective\", so adjectives would silently render blank. The empty
  string is the malformed {2ndOrder} placeholder, see README §B3."
  {"noun"      "sb."
   "verb"      "vb."
   "adjective" "adj."
   "adverb"    "adv."
   ""          "(ingen)"})

;; Mirrors the exclusions in bootstrap/fix-verb-phrase-pos!, kept in step by
;; check-counts! rather than by a shared def: the pipeline hashes that
;; function's own form to decide when to rebuild, so a reference to a def
;; elsewhere would not trigger one.
(def verb-phrase-exclusions
  #{:dn/synset-27542                                        ; {over kors}
    :dn/synset-27572                                        ; {i pleje}
    :dn/synset-30501})                                      ; {skåret ... over samme læst}

(def second-order
  "Artificial top-ontology node whose only word has empty-IRI PoS values."
  :dn/synset-42970)

(def a4-groups
  {:dn/synset-8143   "sprog" :dn/synset-8091 "sprog" :dn/synset-8109 "sprog"
   :dn/synset-7878   "brugsmarkering" :dn/synset-8079 "brugsmarkering"
   :dn/synset-48279  "person" :dn/synset-2119 "person" :dn/synset-6217 "person"
   :dn/synset-48734  "fast udtryk" :dn/synset-1478 "fast udtryk"
   :dn/synset-116    "fast udtryk"})

(def a4-suggestions
  "What each a4 group probably wants, per doc/crosspos/README.md §A4.

  A proposal, not a decision: the three ordklassefejl groups each assert an
  established nominal reading that needs DDO evidence per item, which is why
  they were deferred rather than batched. Only the register group's relation is
  prefilled, on the same basis as 2d prefilling a sole retarget candidate."
  {"sprog"      {"forslag"   "ordklassefejl"
                 "kommentar" (str "Automatisk forslag: substantiveret adjektiv "
                                  "(et sprog). Bekræft den substantiviske brug "
                                  "i DDO.")}
   "person"     {"forslag"   "ordklassefejl"
                 "kommentar" (str "Automatisk forslag: substantiveret adjektiv "
                                  "(en person). Bekræft den substantiviske "
                                  "brug i DDO.")}
   "fast udtryk" {"forslag"   "ordklassefejl"
                  "kommentar" (str "Automatisk forslag: fast nominalt udtryk. "
                                   "Bekræft den substantiviske brug i DDO.")}
   "brugsmarkering" {"forslag"     "ny relation: wn:exemplifies"
                     "ny relation" "wn:exemplifies"
                     "kommentar"   (str "Automatisk forslag: hypernymet er en "
                                        "brugsmarkering, ikke et overbegreb.")}})

(defn- index
  "Build {synset {:label .. :pos #{..} :lemmas #{..}}}.

  PoS and lemmas are queried separately on purpose: a word may carry a
  partOfSpeech without a canonicalForm/writtenRep, and joining the two would
  silently drop such synsets from the PoS comparison."
  [g]
  (let [labels (into {} (map (juxt '?s (comp str '?l)))
                     (q/run g '[:bgp [?s :rdfs/label ?l]]))
        pos    (q/run g '[:bgp
                          [?synset :ontolex/lexicalizedSense ?sense]
                          [?word :ontolex/sense ?sense]
                          [?word :wn/partOfSpeech ?pos]])
        lemmas (q/run g '[:bgp
                          [?synset :ontolex/lexicalizedSense ?sense]
                          [?word :ontolex/sense ?sense]
                          [?word :ontolex/canonicalForm ?form]
                          [?form :ontolex/writtenRep ?rep]])]
    (as-> (into {} (map (fn [[s l]] [s {:label l}])) labels) $
          (reduce (fn [m {:syms [?synset ?pos]}]
                    (update-in m [?synset :pos] (fnil conj #{}) ?pos))
                  $ pos)
          (reduce (fn [m {:syms [?synset ?rep]}]
                    (update-in m [?synset :lemmas] (fnil conj #{}) (str ?rep)))
                  $ lemmas))))

(defn- pairs
  "Every wn:hypernym pair the HypernymPOSShape flags, i.e. any pair whose
  synsets are lexicalized by words disagreeing in PoS."
  [g idx]
  (->> (q/run g '[:bgp [?s :wn/hypernym ?o]])
       (keep (fn [{:syms [?s ?o]}]
               (let [a (get-in idx [?s :pos]) b (get-in idx [?o :pos])]
                 (when (and (str/starts-with? (str ?o) ":dn/")
                            (seq a) (seq b) (not= 1 (count (into a b))))
                   {:source ?s :target ?o :spos a :tpos b}))))))

(defn partition-pairs
  "Split the flagged pairs into the four disjoint groups documented in
  doc/crosspos/README.md."
  [idx flagged]
  (let [disjoint? #(empty? (set/intersection (:spos %) (:tpos %)))
        phrase?   (fn [{:keys [source spos tpos]}]
                    (and (= spos #{:wn/noun}) (= tpos #{:wn/verb})
                         (let [ls (get-in idx [source :lemmas])]
                           (and (seq ls) (every? #(str/includes? % " ") ls)))))
        top?      #(or (= second-order (:source %)) (= second-order (:target %)))
        dis       (filter disjoint? flagged)]
    {:2a (filter phrase? dis)
     :2b (remove disjoint? flagged)
     :2c (filter top? dis)
     :2d (remove #(or (phrase? %) (top? %)) dis)}))

(defn deferred-crosspos
  "The dns:crossPoSHypernym triples deliberately retained for review."
  [g]
  (->> (q/run g '[:bgp [?s :dns/crossPoSHypernym ?o]])
       (map (fn [{:syms [?s ?o]}] {:source ?s :target ?o}))))

(defn- uri [k] (str (prefix/kw->uri k)))

(def noun-suffixes
  "Danish nominalization suffixes tried by derived-noun-candidates."
  ["else" "ning" "ing" "en" "eri" "sel" "tion"])

(defn- ->lemma-index
  "Build {lemma #{synset ..}} from the synset index `idx`."
  [idx]
  (reduce (fn [m [s {:keys [lemmas]}]]
            (reduce #(update %1 %2 (fnil conj #{}) s) m lemmas))
          {} idx))

(defn- same-lemma-candidates
  "Synsets that share a lemma with the hypernym of pair `p` but have the PoS of
  its source, looked up in `idx` and `lemma->synsets` -- the homograph pattern:
  {karmin} under the adjective {rød} suggests the noun {rød}."
  [idx lemma->synsets {:keys [source target spos]}]
  (->> (get-in idx [target :lemmas])
       (mapcat lemma->synsets)
       (filter #(= spos (get-in idx [% :pos])))
       (remove #{source target})
       (distinct)))

(defn- derived-noun-candidates
  "Noun synsets whose lemma is a nominalization of a verb lemma of the
  hypernym of pair `p` (verb stem + noun-suffixes), looked up in `idx` and
  `lemma->synsets` -- the deverbal pattern: {filtrering} under {fjerne}
  suggests {fjernelse}. Only applies to noun sources under verb targets."
  [idx lemma->synsets {:keys [source target spos tpos]}]
  (when (and (= spos #{:wn/noun}) (= tpos #{:wn/verb}))
    (->> (get-in idx [target :lemmas])
         (mapcat (fn [lemma]
                   (let [stem (str/replace lemma #"e$" "")]
                     (mapcat (comp lemma->synsets #(str stem %)) noun-suffixes))))
         (filter #(= #{:wn/noun} (get-in idx [% :pos])))
         (remove #{source target})
         (distinct))))

(defn- candidate-columns
  "The columns the candidate heuristics can prefill for pair `p`: `forslag`
  lists every candidate, and a single candidate also prefills `nyt hypernym`
  plus a `kommentar` naming the heuristic. The reviewer's own entries override
  these in the merge."
  [idx lemma->synsets p]
  (let [same    (same-lemma-candidates idx lemma->synsets p)
        derived (remove (set same) (derived-noun-candidates idx lemma->synsets p))
        ->str   #(str (get-in idx [% :label]) " <" (uri %) ">")
        cands   (concat same derived)]
    (when (seq cands)
      (merge {"forslag" (str/join "; " (map ->str cands))}
             (when (= 1 (count cands))
               {"nyt hypernym" (uri (first cands))
                "kommentar"    (if (seq same)
                                 "Automatisk forslag: samme lemma som hypernymet, med synsettets ordklasse."
                                 "Automatisk forslag: hypernymets verbum som afledt substantiv.")})))))

(defn- cell->str
  "Cell value as a string. Excel stores the count column as a double and may turn
  a URI into a formula, so neither can be read as a string cell directly."
  [cell]
  (if (nil? cell)
    ""
    (condp = (.getCellType cell)
      CellType/STRING (.getStringCellValue cell)
      CellType/NUMERIC (let [d (.getNumericCellValue cell)]
                         (if (== d (Math/rint d))
                           (str (long d))
                           (str d)))
      CellType/FORMULA (try (.getStringCellValue cell) (catch Exception _ ""))
      CellType/BOOLEAN (str (.getBooleanCellValue cell))
      "")))

(defn- read-sheet
  "Existing workbook as {[synset-uri hypernym-uri] row-map}, for merging manual
  columns. Old English headers are translated on the way in, so a pre-rename
  workbook still merges forward. Rows Excel leaves behind without a synset URI
  are skipped."
  [path]
  (when (.exists (io/file path))
    (with-open [in (io/input-stream path)]
      (let [sheet (first (xl/sheet-seq (xl/load-workbook in)))
            rows  (xl/row-seq sheet)
            n     (.getLastCellNum (first rows))
            ->row #(mapv (fn [i] (cell->str (.getCell % i))) (range n))
            hdr   (mapv #(get header-aliases % %) (->row (first rows)))]
        (into {} (for [r (rest rows)
                       :let [m (zipmap hdr (->row r))]
                       :when (not (str/blank? (get m "synset URI")))]
                   [[(get m "synset URI") (get m "hypernym URI")] m]))))))

(defn- style-sheet!
  "Hyperlinks on the readable label cells, a highlight on the columns the
  reviewer fills in, a frozen header row, dropdowns on status and ny relation,
  and fixed column widths."
  [sheet hdr n manual]
  (let [wb       (.getWorkbook sheet)
        ch       (.getCreationHelper wb)
        link     (doto (.createCellStyle wb)
                   (.setFont (doto (.createFont wb)
                               (.setUnderline Font/U_SINGLE)
                               (.setColor (.getIndex IndexedColors/BLUE)))))
        action   (doto (.createCellStyle wb)
                   (.setFillForegroundColor (.getIndex IndexedColors/LEMON_CHIFFON))
                   (.setFillPattern FillPatternType/SOLID_FOREGROUND))
        header   (doto (.createCellStyle wb)
                   (.setFont (doto (.createFont wb) (.setBold true)))
                   (.setFillForegroundColor (.getIndex IndexedColors/GREY_25_PERCENT))
                   (.setFillPattern FillPatternType/SOLID_FOREGROUND)
                   (.setBorderBottom BorderStyle/MEDIUM))
        idx-of   (fn [h] (first (keep-indexed (fn [i x] (when (= h x) i)) hdr)))
        col-idx  (fn [pred] (keep-indexed (fn [i h] (when (pred h) i)) hdr))
        act-cols (col-idx manual)
        pairs    (keep (fn [[label uri]]
                         (when-let [li (idx-of label)]
                           (when-let [ui (idx-of uri)] [li ui])))
                       link-columns)]

    (doseq [i (range 1 (inc n))
            :let [r (.getRow sheet i)]
            :when r]
      (doseq [[li ui] pairs
              :let [lc (.getCell r li) uc (.getCell r ui)]
              :when (and lc uc (= CellType/STRING (.getCellType uc)))
              :let [v (.getStringCellValue uc)]
              :when (str/starts-with? v "http")]
        (doto lc
          (.setHyperlink (doto (.createHyperlink ch HyperlinkType/URL)
                           (.setAddress v)))
          (.setCellStyle link)))
      (doseq [ci act-cols]
        (.setCellStyle (or (.getCell r ci) (.createCell r ci)) action)))

    (doseq [ci (range (count hdr))
            :let [cell (.getCell (.getRow sheet 0) ci)]
            :when cell]
      (.setCellStyle cell header))
    (.createFreezePane sheet 1 1)

    (when-let [si (idx-of "status")]
      (let [dvh        (XSSFDataValidationHelper. sheet)
            constraint (.createExplicitListConstraint dvh (into-array String statuses))
            validation (.createValidation dvh constraint
                                          (CellRangeAddressList. 1 n si si))]
        (.setShowErrorBox validation false)
        (.addValidationData sheet validation)))

    ;; The relation list is well past Excel's 255-char limit for an inline
    ;; list, so the values go on a hidden sheet and the constraint points there.
    (when-let [ri (idx-of "ny relation")]
      (let [ref-sheet  (.createSheet wb "relations")
            _          (doseq [[i v] (map-indexed vector relations)]
                         (-> (.createRow ref-sheet i)
                             (.createCell 0)
                             (.setCellValue (str v))))
            _          (.setSheetHidden wb (.getSheetIndex wb "relations") true)
            dvh        (XSSFDataValidationHelper. sheet)
            constraint (.createFormulaListConstraint
                         dvh (str "relations!$A$1:$A$" (count relations)))
            validation (.createValidation dvh constraint
                                          (CellRangeAddressList. 1 n ri ri))]
        (.setShowErrorBox validation false)
        (.addValidationData sheet validation)))

    (doseq [[i h] (map-indexed vector hdr)]
      (.setColumnWidth sheet i (* 256 (get column-widths h 18))))))

(defn- write-sheet!
  "Write `rows` to an .xlsx workbook at `path`, styled for review."
  [path sheet-name hdr manual rows]
  (io/make-parents path)
  (let [data (into [hdr] (map (fn [m] (mapv #(get m % "") hdr))) rows)
        wb   (xl/create-workbook sheet-name data)
        sh   (.getSheetAt wb 0)]
    (style-sheet! sh hdr (count rows) manual)
    (xl/save-workbook! (str path) wb)))

(def ^:private editable
  "Columns the reviewer fills in, highlighted in both files. `forslag` is not
  among them: it is what the script proposes, not what she decides."
  ["nyt hypernym" "ny relation" "status" "kommentar"])


(defn- row [idx {:keys [source target]} extra]
  (let [pos #(->> (get-in idx [% :pos])
                  (map (fn [p] (let [n (name p)] (get pos-abbr n n))))
                  (sort)
                  (str/join "+"))]
    (merge {"synset"             (get-in idx [source :label])
            "synset URI"         (uri source)
            "nuværende hypernym" (get-in idx [target :label])
            "hypernym URI"       (uri target)
            "ordklasser"         (str (pos source) " → " (pos target))}
           extra)))

(defn- check-counts!
  "Fail loudly if the graph no longer matches the counts the pipeline asserts
  and the documentation cites.

  These are the counts of a *built* database, i.e. after make-release-changes!
  has run. 2a is 3 rather than 110 because the 107 corrected synsets are verbs
  by then and no longer match the noun-source criterion; the 3 that remain are
  exactly the hand-excluded ones. 2c is 0 rather than 52 because the 2026-08-21
  release removed the malformed empty-IRI PoS values from the {2ndOrder}
  placeholder word, so the shape no longer flags its pairs."
  [groups deferred]
  (let [actual   (assoc (update-vals groups count) :deferred (count deferred))
        expected {:2a 3 :2b 149 :2c 0 :2d 285 :deferred 104}
        left     (set (map :source (:2a groups)))]
    (when (not= expected actual)
      (throw (ex-info "cross-PoS group counts have changed; update the docs"
                      {:expected expected :actual actual})))
    (when (not= left verb-phrase-exclusions)
      (throw (ex-info "unexpected 2a remainder; the exclusions no longer match"
                      {:expected verb-phrase-exclusions :actual left})))
    actual))

(defn regenerate!
  "Rewrite both review workbooks from `dataset`, preserving manual columns."
  [dataset]
  (let [g        (db/get-graph dataset prefix/dn-uri)
        idx      (index g)
        flagged  (pairs g idx)
        deferred (deferred-crosspos g)
        groups   (partition-pairs idx flagged)
        counts   (check-counts! groups deferred)
        ;; Blank cells must not wipe a prefill, so only non-blank reviewer
        ;; values override what the script proposes.
        carried  (fn [prev p cols]
                   (into {} (remove (comp str/blank? val))
                         (select-keys (get prev [(uri (:source p)) (uri (:target p))])
                                      cols)))

        ;; --- 2d: candidate heuristics under the reviewer's own entries ---
        prev   (read-sheet (:2d files))
        n-by   (frequencies (map (comp uri :target) (:2d groups)))
        l->s   (->lemma-index idx)
        rows2d (->> (:2d groups)
                    (map (fn [p]
                           (row idx p (merge (candidate-columns idx l->s p)
                                             (carried prev p editable)
                                             {"antal i gruppen"
                                              (n-by (uri (:target p)))}))))
                    (sort-by (juxt #(- (get % "antal i gruppen"))
                                   #(get % "nuværende hypernym")
                                   #(get % "synset"))))

        ;; --- 2a: NOT regenerated ---
        ;; Once fix-verb-phrase-pos! has run, the 107 corrected synsets are
        ;; verbs and no longer match the criterion, so the 110-row candidate
        ;; list cannot be reconstructed from a built database. The workbook is
        ;; a static record of a decision already applied; check-counts!
        ;; verifies that the 3 synsets still matching are exactly the excluded
        ;; ones.

        ;; --- a4: the retained dns:crossPoSHypernym pairs ---
        prevA4 (read-sheet (:a4 files))
        rowsA4 (->> deferred
                    (map (fn [{:keys [target] :as p}]
                           (let [grp (get a4-groups target "?")]
                             (row idx p (merge (get a4-suggestions grp)
                                               (carried prevA4 p editable)
                                               {"gruppe" grp})))))
                    (sort-by (juxt #(get % "gruppe")
                                   #(get % "nuværende hypernym")
                                   #(get % "synset"))))]

    (write-sheet! (:2d files) "2d cross-PoS taksonomi" (:2d headers)
                  (set editable) rows2d)
    (write-sheet! (:a4 files) "a4 udskudte cross-PoS" (:a4 headers)
                  (set editable) rowsA4)
    counts))

(comment
  (regenerate! (:dataset @dk.cst.dannet.web.instance/db))
  #_.)
