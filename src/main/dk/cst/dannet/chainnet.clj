(ns dk.cst.dannet.chainnet
  "Build a ChainNet annotation layer on top of DanNet.

  Provides tooling to match metaphorical senses from the METALLM input
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

;; Matching overview (SN-DDO sheet: 1068 rows, 1007 distinct lemmas)
;; ------------------------------------------------------------------
;; Matched    338 (31.6%) — DDO ref maps directly to a DanNet sense
;; Unmatched  353 (33.1%) — lemma in DanNet, but target sense missing
;; No senses  377 (35.3%) — lemma not in DanNet at all
;;   (of which 34 are recoverable by stripping trailing homograph digits
;;    and ~5 are multi-word expressions)

(defn truncate-title
  "Truncate title `s` to the maximum allowed 31 chars in Excel."
  [s]
  (subs s 0 (min (count s) 31)))

(def sheets
  (update-vals
    {:NS        "NS DaFig Korpusdata"
     :SN-DDO    "SN Metaforer DDO emnebaseret"
     :SN-adhoc  "SN ad hoc-metaf. fra ofø-citater i DDO (mest type2"
     :BSP       "BSP adhoc-metaforer fra Politikens anmeldelser"
     :SO        "SO Unikke danske metaforer fra NODALIDA-data og korpus.dk"
     :statistik "statistik"}
    truncate-title))

(def SN-columns
  {:A :lemma
   :D :entry
   :F :sentence})

(def normalize-entry
  {1.0    "1"
   "1.a." "1.a"})

(defn normalize-columns
  [{:keys [entry] :as m}]
  (update m :entry #(get normalize-entry % %)))

(defn load-sheet
  [id columns]
  (->> (io/file "bootstrap/other/chainnet/Danish_metaphor_benchmark.xlsx")
       (xl/load-workbook-from-file)
       (xl/select-sheet (get sheets id))
       (xl/select-columns columns)
       (rest)
       (map normalize-columns)
       ;; Deduplicate by lemma, preferring rows with a sentence
       (reduce (fn [acc {:keys [lemma] :as row}]
                 (let [existing (get acc lemma)]
                   (if (or (nil? existing)
                           (and (nil? (:sentence existing))
                                (some? (:sentence row))))
                     (assoc acc lemma row)
                     acc)))
               {})
       (vals)))

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

;; Root rows are marked with "-" in derived-from, qualia-role, and relation
;; since those fields only apply to the metaphor sense. Virtual sense IDs
;; (unknown_root, unknown_metaphor, unknown_sense) mark senses that don't
;; exist in DanNet yet.
(defn make-output-row
  [lemma sense-id description example derived-from task]
  (let [root? (= derived-from "-")]
    {:lemma        lemma
     :sense-id     sense-id
     :derived-from derived-from
     :task         task
     :description  description
     :example      example
     :annotator    nil
     :qualia-role  (when root? "-")
     :relation     (when root? "-")
     :comment      nil}))

(defn generate-rows
  "Generate output rows for a single spreadsheet `row` and `senses-by-lemma`.

  Each input row produces 2+ output rows forming a lemma group. Virtual
  sense IDs (unknown_root, unknown_metaphor, unknown_sense) are used when
  a sense doesn't exist in DanNet. The :derived-from field is pre-filled
  when the relationship is known, and :task explains provenance."
  [{:keys [lemma entry sentence]} senses-by-lemma]
  (let [senses    (dedup-senses (get senses-by-lemma (str lemma)))
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
      [(make-output-row lemma "unknown_root" nil nil "-" "not in DanNet")
       (make-output-row lemma "unknown_metaphor" nil sentence "unknown_root" "not in DanNet")]

      ;; both metaphor (e.g. §1a) and base (e.g. §1) found.
      (and metaphor base)
      [(make-output-row lemma (:sense base) (:def base) nil "-" nil)
       (make-output-row lemma (:sense metaphor) (:def metaphor) sentence (:sense base) nil)]

      ;; metaphor found (e.g. only §1a exists), but expected
      ;; root is missing from DanNet. Virtual root row.
      (and metaphor p-suffix (nil? base))
      [(make-output-row lemma "unknown_root" nil nil "-" "root not in DanNet")
       (make-output-row lemma (:sense metaphor) (:def metaphor) sentence "unknown_root" nil)]

      ;; metaphor found, no parent expected (entry="1").
      metaphor
      [(make-output-row lemma (:sense metaphor) (:def metaphor) sentence "-" nil)]

      ;; nil DDO ref but DanNet has §1/§1a pair.
      ;; Assume §1a = metaphor, §1 = root (e.g. festfyrværkeri, kalejdoskop).
      (and (nil? entry)
           (let [suffixes (set (keep by-suffix senses))]
             (and (contains? suffixes "§1") (contains? suffixes "§1a"))))
      (let [root (first (filter #(= "§1" (by-suffix %)) senses))
            met  (first (filter #(= "§1a" (by-suffix %)) senses))]
        [(make-output-row lemma (:sense root) (:def root) nil "-" "verify inferred IDs")
         (make-output-row lemma (:sense met) (:def met) sentence
                          (:sense root) "verify inferred IDs")])

      ;; seeking §1a but only §1 exists (e.g. gråhåret, fundament).
      ;; Known root + virtual metaphor row.
      (and (= suffix "§1a")
           (= 1 (count senses))
           (= "§1" (by-suffix (first senses))))
      (let [root (first senses)]
        [(make-output-row lemma (:sense root) (:def root) nil "-" nil)
         (make-output-row lemma "unknown_metaphor" nil sentence (:sense root)
                          "metaphor not in DanNet")])

      ;; single sense with no § in label (e.g. enøjet, grundmuret)
      ;; or single sense where suffix didn't match. Known sense + virtual row;
      ;; annotator decides which is root vs metaphor.
      (= 1 (count senses))
      (let [known (first senses)]
        [(make-output-row lemma (:sense known) (:def known) nil nil "assign roles")
         (make-output-row lemma "unknown_sense" nil sentence nil "assign roles")])

      ;; multiple senses, none matching the target suffix.
      ;; Includes _(N) split-senses (e.g. afpillet_(1)/_(2)), nil-entry
      ;; cases with §1/§2 (e.g. gen), and edge cases like bankbog seeking
      ;; §1b. Annotator assigns roles.
      :else
      (let [known (mapv (fn [s]
                          (make-output-row lemma (:sense s)
                                           (:def s) nil nil "assign roles"))
                        senses)]
        (update known 0 assoc :example sentence)))))

(def output-columns
  [:lemma :task :sense-id :derived-from :qualia-role :relation
   :description :example :annotator :comment])

(def output-headers
  ["lemma" "task" "sense ID" "derived from" "qualia role" "relation"
   "description" "example" "annotator" "comment"])

(def output-column-widths
  ;;  lemma  task  sense-id  derived-from  qualia  relation  description  example  annotator  comment
  [20       22    55        55            15      15        50           60       12         25])

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

(defn sort-by-status
  "Sort output rows by group status: complete → partial → missing."
  [output-rows]
  (let [statuses (update-vals (group-by :lemma output-rows) group-status)
        order    {:complete 0 :partial 1 :missing 2}]
    (->> (group-by :lemma output-rows)
         (sort-by (fn [[lemma _]] (order (statuses lemma))))
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
  (let [sorted (sort-by-status output-rows)
        data   (into [output-headers]
                     (map (fn [row] (mapv #(get row %) output-columns)))
                     sorted)
        wb     (xl/create-workbook "ChainNet SN-DDO" data)
        sh     (.getSheetAt wb 0)]
    (style-sheet! sh sorted)
    (xl/save-workbook! (str path) wb)
    (println "Exported" (count output-rows) "rows to" (str path))))

(comment
  (require '[dk.cst.dannet.web.resources :as res])

  (def model (:model @res/db))

  ;; Fetch all senses (bulk, ~2 min)
  (def senses-by-lemma (fetch-all-senses model))

  ;; Load spreadsheet and generate output
  (def rows (load-sheet :SN-DDO SN-columns))
  (def output (vec (mapcat #(generate-rows % senses-by-lemma) rows)))
  (count output)

  ;; Inspect a lemma group
  (filter #(= "kulde" (:lemma %)) output)

  ;; Export
  (export-spreadsheet! output "export/chainnet/chainnet-sn-ddo.xlsx")

  #_.)
