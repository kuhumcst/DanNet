(ns dk.cst.dannet.chainnet
  "Build a ChainNet annotation layer on top of DanNet.

  Provides tooling to match metaphorical senses from the DAMETA input
  spreadsheet against DanNet sense data, producing lemma groups for
  annotation in a flat one-row-per-sense output format."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [dk.ative.docjure.spreadsheet :as xl]
            [dk.cst.dannet.db.transaction :as tx]
            [dk.cst.dannet.shared :as shared])
  (:import [org.apache.jena.query QueryFactory QueryExecutionFactory]
           [org.apache.poi.common.usermodel HyperlinkType]
           [org.apache.poi.ss.usermodel BorderStyle CellType FillPatternType Font IndexedColors]
           [org.apache.poi.ss.util CellRangeAddressList]
           [org.apache.poi.xssf.usermodel XSSFDataValidationHelper]))

(def dameta-file
  "bootstrap/other/chainnet/DAMETA Nyt ark.xlsx")

(def dameta-columns
  {:A :id
   :B :lemma
   :C :sentence
   :E :exp1
   :H :exp4
   :K :entry
   :M :annotator})

(def old-export-dir
  "The ChainNet spreadsheets exported from the earlier, faulty input data."
  "doc/chainnet/old")

(defn selected?
  "Is this DAMETA `row` part of the usable selection? Rows with no annotator,
  rows marked kasseret, and rows missing exp1 or exp4 are all excluded."
  [{:keys [annotator exp1 exp4]}]
  (let [annotator (str/trim (str annotator))]
    (and (not (str/blank? annotator))
         (not= "kasseret" (str/lower-case annotator))
         (not (str/blank? (str exp1)))
         (not (str/blank? (str exp4))))))

;; DDO entry values come in mixed formats across sheets: "1.a", "1a", "1A",
;; "1.a.", 2.0, and free text like "ordet mangler" or "Ikke i DDO".
;; We normalize to a canonical form (lowercase, no trailing dot) or nil.
(defn normalize-entry [entry]
  (cond
    (nil? entry)    nil
    (number? entry) (str (long entry))
    (string? entry) (let [s (-> entry str/trim str/lower-case (str/replace #"\.$" ""))]
                      (when (re-matches #"\d+[a-z.]*" s) s))
    :else           nil))

(defn merge-lemma-rows
  "Reduce the DAMETA `rows` of a single lemma to one row per DDO entry. Rows
  sharing an entry collapse into the one with an example sentence, and rows
  without an entry fold into the surviving entry when only one survives. Every
  source identifier is kept in :id so annotations can be traced back to DAMETA."
  [rows]
  (let [entryless (remove :entry rows)
        by-entry  (or (not-empty (group-by :entry (filter :entry rows)))
                      {nil entryless})
        folded    (when (= 1 (count by-entry))
                    (map :id entryless))]
    (for [[_ same] (sort-by (comp str key) by-entry)
          :let [sentenced (remove #(str/blank? (str (:sentence %))) same)]]
      (assoc (or (first sentenced) (first same))
        :id (str/join "+" (distinct (concat (map :id same) folded)))))))

(defn annotated-lemmas
  "Lemmas from the previously exported ChainNet spreadsheets in `dir` whose
  groups were fully resolved, i.e. the green category an annotator has already
  worked through. These are kept in the new export even when the DAMETA
  selection excludes them."
  [dir]
  (->> (.listFiles (io/file dir))
       (filter #(str/ends-with? (.getName %) ".xlsx"))
       (mapcat (fn [f]
                 (->> (xl/load-workbook-from-file f)
                      (xl/sheet-seq)
                      (first)
                      (xl/select-columns {:A :lemma :C :sense-id :D :derived-from})
                      (rest)
                      (group-by :lemma)
                      (keep (fn [[lemma rows]]
                              (when (and (every? #(str/starts-with? (str (:sense-id %)) "http") rows)
                                         (every? :derived-from rows))
                                lemma))))))
       (set)))

(defn load-dameta
  "Load the DAMETA input rows, keeping the selection plus every lemma in
  `rescued`. Rows are grouped by lemma and reduced to one row per DDO entry;
  a lemma that survives only by rescue is marked :selected? false."
  [rescued]
  (->> (io/file dameta-file)
       (xl/load-workbook-from-file)
       (xl/select-sheet "Data")
       (xl/select-columns dameta-columns)
       (rest)
       (remove #(str/blank? (str (:lemma %))))
       (map #(assoc % :lemma (str/trim (str (:lemma %)))
               :entry (normalize-entry (:entry %))
               :selected? (selected? %)))
       (group-by :lemma)
       (sort-by key)
       (mapcat (fn [[lemma rows]]
                 (let [in? (boolean (some :selected? rows))]
                   (when (or in? (rescued lemma))
                     (map #(assoc % :selected? in?)
                          (merge-lemma-rows (if in? (filter :selected? rows) rows)))))))))

;; DDO references use dot notation (1.a, 1.b) while DanNet labels use
;; § notation (§1a, §1b). These functions convert between the two and
;; derive parent/child relationships from suffixes.
(defn ddo-entry->suffix
  "Convert DDO `entry` to a DanNet label suffix (e.g. 1.a -> §1a, 1 -> §1)."
  [entry]
  (when entry
    (str "§" (str/replace entry "." ""))))

(defn parent-suffix
  "Derive the parent sense label suffix from a DanNet sense label `suffix`.
  The parent of §1a is §1. Returns nil for root entries."
  [suffix]
  (when suffix
    (let [trimmed (str/replace suffix #"[a-z]$" "")]
      (when (not= trimmed suffix)
        trimmed))))

(defn label->suffix
  "Extract the §-suffix from a DanNet sense `label` (e.g. kulde_§1a -> §1a)."
  [label]
  (second (re-find #"(§.+)" label)))

;; Per-lemma SPARQL queries (1007 of them) cause TDB2 BlockMgrMapped
;; segment allocation errors. Instead, we fetch all Danish senses in one
;; query and group by lemma in memory. The inference model produces ~64k
;; duplicate rows (146k total vs 82k unique) so we deduplicate by :label.
(defn dedup-senses
  "Remove duplicate `senses` (from inference model) by :label."
  [senses]
  (->> senses
       (reduce (fn [m s]
                 (if (contains? m (:label s))
                   m
                   (assoc m (:label s) s)))
               {})
       (vals)))

(def all-senses-query
  "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
   PREFIX ontolex: <http://www.w3.org/ns/lemon/ontolex#>
   PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
   SELECT ?lemma ?sense ?label ?def WHERE {
     ?entry ontolex:canonicalForm/ontolex:writtenRep ?lemma .
     ?entry ontolex:sense ?sense .
     ?sense rdfs:label ?label .
     OPTIONAL {
       ?sense ontolex:isLexicalizedSenseOf/skos:definition ?def .
       FILTER(LANG(?def) = 'da')
     }
     FILTER(LANG(?lemma) = 'da')
   }")

(defn fetch-all-senses
  "Fetch all Danish senses in one bulk query to `model`, returning a map of
  lemma -> deduplicated sense vectors. Grouping and deduplication happen
  outside the Jena transaction (large update-vals inside transact-read
  causes segment errors)."
  [model]
  (let [raw (tx/transact-read model
              (let [query (QueryFactory/create all-senses-query)]
                (with-open [qe (QueryExecutionFactory/create query model)]
                  (let [rs (.execSelect qe)]
                    (loop [acc []]
                      (if (.hasNext rs)
                        (let [sol (.next rs)]
                          (recur (conj acc
                                       {:lemma (.getString (.getLiteral sol "lemma"))
                                        :sense (.getURI (.getResource sol "sense"))
                                        :label (.getString (.getLiteral sol "label"))
                                        :def   (when-let [d (.get sol "def")]
                                                 (.getString (.asLiteral d)))})))
                        acc))))))]
    (update-vals (group-by :lemma raw) dedup-senses)))

(defn lemma-senses
  "The DanNet senses of `lemma` in `senses-by-lemma`, falling back to a
  lower-case lookup since the odd DAMETA lemma is capitalised."
  [senses-by-lemma lemma]
  (or (get senses-by-lemma lemma)
      (get senses-by-lemma (str/lower-case lemma))))

;; Root rows are marked with "-" in derived-from, qualia-role, and relation
;; since those fields only apply to the metaphor sense. Virtual sense IDs
;; (unknown_root, unknown_metaphor, unknown_sense) mark senses that don't
;; exist in DanNet yet.
(defn make-output-row
  [{:keys [lemma id selected?]} sense-id description example derived-from task]
  (let [root? (= derived-from "-")]
    {:lemma        lemma
     :id           id
     :sense-id     sense-id
     :derived-from derived-from
     :task         task
     :description  description
     :example      example
     :annotator    nil
     :qualia-role  (when root? "-")
     :relation     (when root? "-")
     :comment      nil
     :in-selection selected?}))

(defn generate-rows
  "Generate output rows for a single spreadsheet `row` and `senses-by-lemma`.

  Each input row produces 2+ output rows forming a lemma group. Virtual
  sense IDs (unknown_root, unknown_metaphor, unknown_sense) are used when
  a sense doesn't exist in DanNet. The :derived-from field is pre-filled
  when the relationship is known, and :task explains provenance."
  [{:keys [lemma entry sentence] :as row} senses-by-lemma]
  (let [senses    (dedup-senses (lemma-senses senses-by-lemma (str lemma)))
        suffix    (ddo-entry->suffix entry)
        p-suffix  (parent-suffix suffix)
        by-suffix (fn [s] (label->suffix (:label s)))
        metaphor  (when suffix
                    (first (filter #(= suffix (by-suffix %)) senses)))
        base      (when p-suffix
                    (first (filter #(= p-suffix (by-suffix %)) senses)))]
    (cond
      ;; lemma not in DanNet at all.
      (empty? senses)
      [(make-output-row row "unknown_root" nil nil "-" "not in DanNet")
       (make-output-row row "unknown_metaphor" nil sentence "unknown_root" "not in DanNet")]

      ;; both metaphor (e.g. §1a) and base (e.g. §1) found.
      (and metaphor base)
      [(make-output-row row (:sense base) (:def base) nil "-" nil)
       (make-output-row row (:sense metaphor) (:def metaphor) sentence (:sense base) nil)]

      ;; metaphor found (e.g. only §1a exists), but expected
      ;; root is missing from DanNet. Virtual root row.
      (and metaphor p-suffix (nil? base))
      [(make-output-row row "unknown_root" nil nil "-" "root not in DanNet")
       (make-output-row row (:sense metaphor) (:def metaphor) sentence "unknown_root" nil)]

      ;; nil DDO ref but DanNet has §1/§1a pair.
      ;; Assume §1a = metaphor, §1 = root (e.g. festfyrværkeri, kalejdoskop).
      (and (nil? entry)
           (let [suffixes (set (keep by-suffix senses))]
             (and (contains? suffixes "§1") (contains? suffixes "§1a"))))
      (let [root (first (filter #(= "§1" (by-suffix %)) senses))
            met  (first (filter #(= "§1a" (by-suffix %)) senses))]
        [(make-output-row row (:sense root) (:def root) nil "-" "verify inferred IDs")
         (make-output-row row (:sense met) (:def met) sentence
                          (:sense root) "verify inferred IDs")])

      ;; Sub-sense sought but only parent exists (e.g. §10a sought, only §10;
      ;; or §1b sought, only §1). Known root + virtual metaphor row.
      (and suffix
           (parent-suffix suffix)
           (not metaphor)
           (= 1 (count (filter #(= (parent-suffix suffix) (by-suffix %)) senses))))
      (let [root (first (filter #(= (parent-suffix suffix) (by-suffix %)) senses))]
        [(make-output-row row (:sense root) (:def root) nil "-" nil)
         (make-output-row row "unknown_metaphor" nil sentence (:sense root)
                          "metaphor not in DanNet")])

      ;; Top-level entry (e.g. "2") matched a sense but we don't know if
      ;; it's root or metaphor. Filter to the §N family (§2, §2a, §2b...)
      ;; so the annotator only sees the relevant senses.
      (and metaphor (nil? p-suffix))
      (let [family (filter #(when-let [s (by-suffix %)]
                              (str/starts-with? s suffix))
                           senses)
            known  (if (> (count family) 1) family senses)]
        (if (= 1 (count known))
          (let [s (first known)]
            [(make-output-row row (:sense s) (:def s) nil nil "assign roles")
             (make-output-row row "unknown_sense" nil sentence nil "assign roles")])
          (let [rows (mapv (fn [s]
                             (make-output-row row (:sense s)
                                              (:def s) nil nil "assign roles"))
                           known)]
            (update rows 0 assoc :example sentence))))

      ;; single sense with no § in label (e.g. enøjet, grundmuret)
      ;; or single sense where suffix didn't match. Known sense + virtual row;
      ;; annotator decides which is root vs metaphor.
      (= 1 (count senses))
      (let [known (first senses)]
        [(make-output-row row (:sense known) (:def known) nil nil "assign roles")
         (make-output-row row "unknown_sense" nil sentence nil "assign roles")])

      ;; multiple senses, none matching the target suffix.
      ;; Includes _(N) split-senses (e.g. afpillet_(1)/_(2)), nil-entry
      ;; cases with §1/§2 (e.g. gen), and edge cases like bankbog seeking
      ;; §1b. Annotator assigns roles.
      :else
      (let [known (mapv (fn [s]
                          (make-output-row row (:sense s)
                                           (:def s) nil nil "assign roles"))
                        senses)]
        (update known 0 assoc :example sentence)))))

(def output-columns
  [:lemma :id :task :sense-id :derived-from :qualia-role :relation
   :description :example :annotator :comment :in-selection])

(def output-headers
  ["lemma" "id" "task" "sense ID" "derived from" "qualia role" "relation"
   "description" "example" "annotator" "comment" "in DAMETA selection"])

(def output-column-widths
  ;;  lemma  id  task  sense-id  derived-from  qualia  relation  description  example  annotator  comment  in-selection
  [20       14  22    55        55            15      15        50           60       12         25       20])

(def ^:private col-idx
  "Column indices by key, derived from output-columns."
  (into {} (map-indexed (fn [i k] [k i]) output-columns)))

;; Groups are classified by how much data we already have: :complete means
;; all sense IDs are real URIs and derived-from is resolved, :partial means
;; at least one real sense exists, :missing means everything is virtual.
;; This drives both the sorting order and the lemma cell colour.

(defn group-status [rows]
  (let [uri?        #(str/starts-with? (str (:sense-id %)) "http")
        all-real?   (every? uri? rows)
        all-filled? (every? :derived-from rows)]
    (cond
      (and all-real? all-filled?) :complete
      (some uri? rows)            :partial
      :else                       :missing)))

(def input-notes
  "Known problems in the DAMETA input, keyed by identifier. Rather than
  special-casing a handful of rows in code, the note is appended to the task
  column so the annotator can see what is wrong."
  {"n410" "NB: vente also has a second group above, from DDO 1b"
   "s358" "NB: the word column holds a stray quotation; the lemma looks like belejre"})

(defn note-input-issues
  "Append any `input-notes` entry to the task column of the matching rows."
  [output-rows]
  (map (fn [{:keys [id task] :as row}]
         (if-let [note (input-notes id)]
           (assoc row :task (str/join "; " (remove nil? [task note])))
           row))
       output-rows))

(defn sort-by-status
  "Sort output rows by group status (complete → partial → missing), then by
  lemma so that regenerating the export produces a stable ordering."
  [output-rows]
  (let [groups   (group-by :lemma output-rows)
        statuses (update-vals groups group-status)
        order    {:complete 0 :partial 1 :missing 2}]
    (->> groups
         (sort-by (fn [[lemma _]] [(order (statuses lemma)) lemma]))
         (mapcat val)
         (vec))))

(defn make-styles
  "Create all cell styles for the workbook: status colours for the lemma
  column (green/yellow/red), a plain link style, an action highlight for
  cells the annotator needs to fill in, and a combined action+link style
  for URI cells that need verification."
  [wb]
  (let [fill (fn [color]
               (doto (.createCellStyle wb)
                 (.setFillForegroundColor (.getIndex color))
                 (.setFillPattern FillPatternType/SOLID_FOREGROUND)))
        link-font (doto (.createFont wb)
                    (.setUnderline Font/U_SINGLE)
                    (.setColor (.getIndex IndexedColors/BLUE)))
        link (doto (.createCellStyle wb)
               (.setFont link-font))
        action-link (doto (.createCellStyle wb)
                      (.setFillForegroundColor (.getIndex IndexedColors/LIGHT_TURQUOISE))
                      (.setFillPattern FillPatternType/SOLID_FOREGROUND)
                      (.setFont link-font))]
    {:link          link
     :status-green  (fill IndexedColors/LIGHT_GREEN)
     :status-yellow (fill IndexedColors/LIGHT_YELLOW)
     :status-red    (fill IndexedColors/ROSE)
     :action        (fill IndexedColors/LIGHT_TURQUOISE)
     :action-link   action-link}))

(def ^:private status->style-key
  {:complete :status-green
   :partial  :status-yellow
   :missing  :status-red})

(defn needs-action?
  "Does this cell value need annotator attention?"
  [cell-value verify?]
  (or verify?
      (nil? cell-value)
      (str/blank? cell-value)
      (str/starts-with? (str cell-value) "unknown")))

(defn style-sheet!
  "Apply all styling to the sheet in a single pass over the data rows:
  hyperlinks on URI cells, status colours on lemma cells, action highlights
  on cells the annotator needs to fill in, and group-separating borders.
  Also sets up the header row, freeze pane, dropdowns, and column widths."
  [sheet output-rows]
  (let [wb       (.getWorkbook sheet)
        ch       (.getCreationHelper wb)
        styles   (make-styles wb)
        statuses (update-vals (group-by :lemma output-rows) group-status)
        id-cols  [(col-idx :sense-id) (col-idx :derived-from)]
        task-cols [(col-idx :qualia-role) (col-idx :relation)]
        n        (count output-rows)

        ;; Bordering clones an existing style and adds a bottom border.
        ;; We cache these to avoid creating duplicate styles (POI has a
        ;; limit of ~64k styles per workbook).
        border-cache (atom {})]
    (doseq [i (range n)
            :let [{:keys [lemma task]} (nth output-rows i)
                  sheet-row (.getRow sheet (inc i))
                  status    (statuses lemma)
                  verify?   (= "verify inferred IDs" task)
                  last?     (or (= i (dec n))
                                (not= lemma (:lemma (nth output-rows (inc i)))))]
            :when sheet-row]

      ;; Lemma cell — green/yellow/red by group status
      (when-let [cell (.getCell sheet-row (col-idx :lemma))]
        (.setCellStyle cell (styles (status->style-key status))))

      ;; Sense-id and derived-from cells get hyperlinks when they contain
      ;; URIs, and an action highlight when they need annotator attention
      ;; (unknown_*, empty, or flagged for verification).
      (doseq [ci id-cols
              :let [cell (.getCell sheet-row ci)]
              :when cell
              :let [v (when (= (.getCellType cell) CellType/STRING)
                        (.getStringCellValue cell))]]
        (when (and v (str/starts-with? v "http"))
          (let [hl (.createHyperlink ch HyperlinkType/URL)]
            (.setAddress hl v)
            (.setHyperlink cell hl)))
        (cond
          (and (needs-action? v verify?) (.getHyperlink cell))
          (.setCellStyle cell (:action-link styles))

          (needs-action? v verify?)
          (.setCellStyle cell (:action styles))

          (.getHyperlink cell)
          (.setCellStyle cell (:link styles))))

      ;; Qualia-role and relation cells — action highlight on non-root rows
      ;; (root rows have "-" in these fields and need no annotation).
      (doseq [ci task-cols
              :let [cell (.getCell sheet-row ci)]
              :when cell
              :let [v (when (= (.getCellType cell) CellType/STRING)
                        (.getStringCellValue cell))]
              :when (not= v "-")]
        (.setCellStyle cell (:action styles)))

      ;; A thin bottom border on the last row of each lemma group helps
      ;; the annotator see where one group ends and the next begins.
      (when last?
        (doseq [ci (range (count output-columns))
                :let [cell (or (.getCell sheet-row ci)
                               (.createCell sheet-row ci))
                      src   (.getCellStyle cell)
                      src-i (.getIndex src)]]
          (.setCellStyle cell
            (or (get @border-cache src-i)
                (let [s (doto (.createCellStyle wb)
                          (.cloneStyleFrom src)
                          (.setBorderBottom BorderStyle/THIN))]
                  (swap! border-cache assoc src-i s)
                  s))))))

    ;; Bold grey header row with a heavier bottom border, frozen in place
    ;; so it stays visible while scrolling.
    (let [header-font (doto (.createFont wb)
                        (.setBold true))
          header-style (doto (.createCellStyle wb)
                         (.setFont header-font)
                         (.setFillForegroundColor (.getIndex IndexedColors/GREY_25_PERCENT))
                         (.setFillPattern FillPatternType/SOLID_FOREGROUND)
                         (.setBorderBottom BorderStyle/MEDIUM))]
      (doseq [ci (range (count output-columns))
              :let [cell (.getCell (.getRow sheet 0) ci)]
              :when cell]
        (.setCellStyle cell header-style)))
    (.createFreezePane sheet 0 1)

    ;; Dropdown for qualia roles (Pustejovsky's Generative Lexicon theory).
    ;; The four roles: FORMAL (is-a), CONSTITUTIVE (part-whole),
    ;; TELIC (purpose), AGENTIVE (origin).
    (let [dvh        (XSSFDataValidationHelper. sheet)
          constraint (.createExplicitListConstraint dvh
                       (into-array String ["" "FORMAL" "CONSTITUTIVE" "TELIC" "AGENTIVE"]))
          qi         (col-idx :qualia-role)
          range      (CellRangeAddressList. 1 n qi qi)
          validation (.createValidation dvh constraint range)]
      (.setShowErrorBox validation false)
      (.addValidationData sheet validation))

    ;; Dropdown for synset relations, sourced from the same relation set
    ;; used in the radial diagram legend (shared/synset-rel-theme).
    ;; The list exceeds Excel's 255-char inline limit, so we put the
    ;; values on a hidden reference sheet and point the validation there.
    (let [rel-names  (->> (keys shared/synset-rel-theme)
                          (map #(str (namespace %) ":" (name %)))
                          (sort)
                          (into [""]))
          ref-sheet  (.createSheet wb "relations")
          _          (doseq [[i v] (map-indexed vector rel-names)]
                       (-> (.createRow ref-sheet i)
                           (.createCell 0)
                           (.setCellValue (str v))))
          _          (.setSheetHidden wb (.getSheetIndex wb "relations") true)
          dvh        (XSSFDataValidationHelper. sheet)
          constraint (.createFormulaListConstraint dvh
                       (str "relations!$A$1:$A$" (count rel-names)))
          ri         (col-idx :relation)
          range      (CellRangeAddressList. 1 n ri ri)
          validation (.createValidation dvh constraint range)]
      (.setShowErrorBox validation false)
      (.addValidationData sheet validation))

    (doseq [[i w] (map-indexed vector output-column-widths)]
      (.setColumnWidth sheet i (* w 256)))))

(defn export-spreadsheet!
  "Export `output-rows` as an Excel spreadsheet to `path`.
  Rows are sorted by group status (complete → partial → missing).
  URI values become clickable hyperlinks; cells needing annotation are
  highlighted. Lemma groups are separated by thin borders."
  [output-rows path]
  (io/make-parents path)
  (let [sorted (sort-by-status (note-input-issues output-rows))
        data   (into [output-headers]
                     (map (fn [row] (mapv #(get row %) output-columns)))
                     sorted)
        wb     (xl/create-workbook "ChainNet" data)
        sh     (.getSheetAt wb 0)]
    (style-sheet! sh sorted)
    (xl/save-workbook! (str path) wb)
    (println "Exported" (count output-rows) "rows to" (str path))))

(comment
  (require '[dk.cst.dannet.web.instance :as instance])

  (def model (:model @instance/db))

  ;; Fetch all senses (bulk, ~2 min)
  (def senses-by-lemma (fetch-all-senses model))

  ;; Load spreadsheet and generate output
  (def rows (load-dameta (annotated-lemmas old-export-dir)))
  (def output (vec (mapcat #(generate-rows % senses-by-lemma) rows)))
  (count output)

  ;; Inspect a lemma group
  (filter #(= "kulde" (:lemma %)) output)

  ;; Export
  (export-spreadsheet! output "export/chainnet/chainnet.xlsx")

  #_.)
