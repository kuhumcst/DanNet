(ns dk.cst.dannet.db.stats
  "Statistics comparing the current DanNet release with the legacy DanNet 2.2,
  rendered as the tables of a paper.

  The legacy side is read from the DanNet 2.2 CSV release (the @-separated
  files in bootstrap/dannet/DanNet-2.2_csv), the current side from the graphs
  of a live db map, with the old relation names mapped onto the relations they
  became during the 2023 conversion so the two inventories line up. Run

    (export-stats! @dk.cst.dannet.web.instance/db)

  to write export/stats/<version>/stats.{edn,md,tex}."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [dk.cst.dannet.db :as db]
            [dk.cst.dannet.db.query :as q]
            [dk.cst.dannet.db.query.operation :as op]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.release :as release]))

(def taxonomic-relations
  "The relations placing a synset in a hierarchy; a synset with no other
  relation counts as taxonomic-only."
  #{:wn/hypernym :wn/hyponym
    :wn/instance_hypernym :wn/instance_hyponym
    :dns/orthogonalHypernym :dns/orthogonalHyponym
    :dns/crossPoSHypernym :dns/crossPoSHyponym})

(defn- median
  [xs]
  (let [sorted (vec (sort xs))
        n      (count sorted)]
    (when (pos? n)
      (if (even? n)
        (/ (+ (sorted (dec (quot n 2))) (sorted (quot n 2))) 2.0)
        (double (sorted (quot n 2)))))))

(defn degree-stats
  "Degree statistics of the `synsets` given the [source relation target]
  `edges` between them; a synset's degree counts the edges in either
  direction, i.e. the relations navigable from it once every relation has
  its inverse."
  [synsets edges]
  (let [out     (frequencies (map first edges))
        in      (frequencies (map peek edges))
        non-tax (frequencies (map first (remove (comp taxonomic-relations second)
                                                edges)))
        degrees (map #(+ (get out % 0) (get in % 0)) synsets)
        n       (count synsets)]
    {:synsets        n
     :mean-out       (double (/ (count edges) n))
     :mean-degree    (double (/ (reduce + degrees) n))
     :median-degree  (median degrees)
     :isolated       (count (filter zero? degrees))
     :taxonomic-only (count (filter #(and (pos? (get out % 0))
                                          (zero? (get non-tax % 0)))
                                    synsets))}))

(defn inverse-closure
  "The number of `edges` plus the inverse edges implied by the owl:inverseOf
  pairs `inverses` that are not already asserted."
  [inverses edges]
  (let [asserted (set edges)]
    (+ (count edges)
       (count (for [[s p o] edges
                    :let [q (get inverses p)]
                    :when (and q (not (asserted [o q s])))]
                1)))))

(def legacy-dir
  "The DanNet 2.2 CSV release, the baseline the rebuild is compared with."
  "bootstrap/dannet/DanNet-2.2_csv")

(def legacy-relations
  "The DanNet 2.2 relation names and the relations they were converted to;
  nil for the names that were dropped. hyponymOf rows flagged nontaxonomic
  became dns:orthogonalHypernym, see legacy-relation."
  {"hyponymOf"          :wn/hypernym
   "hypernymOf"         :wn/hyponym
   "instanceOf"         :wn/instance_hypernym
   "domain"             :wn/domain_topic
   "roleAgent"          :wn/agent
   "involvedAgent"      :wn/co_instrument_agent
   "involvedInstrument" :wn/co_agent_instrument
   "involvedPatient"    :wn/involved_patient
   "rolePatient"        :wn/patient
   "madeBy"             :wn/result
   "partHolonymOf"      :wn/mero_part
   "partMeronymOf"      :wn/holo_part
   "madeofHolonymOf"    :wn/mero_substance
   "madeofMeronymOf"    :wn/holo_substance
   "memberHolonymOf"    :wn/mero_member
   "memberMeronymOf"    :wn/holo_member
   "locationMeronymOf"  :wn/holo_location
   "locationHolonymOf"  :wn/mero_location
   "meronymOf"          :wn/holonym
   "nearSynonymOf"      :wn/similar
   "xposNearSynonymOf"  :wn/similar
   "concerns"           :wn/also
   "nearAntonymOf"      :dns/nearAntonym
   "usedFor"            :dns/usedFor
   "usedForObject"      :dns/usedForObject
   "usedForQualifiedBy" nil
   "eqSynonymOf"        nil
   "eqHypernymOf"       nil
   "eqHyponymOf"        nil})

(def cross-lingual-relations
  "The legacy relation names linking synsets to Princeton WordNet."
  #{"eqSynonymOf" "eqHypernymOf" "eqHyponymOf"})

(def placeholder-gloss
  "The gloss DanNet 2.2 gave synsets without a definition."
  "(ingen definition)")

(defn read-legacy-rows
  "The rows of the @-separated legacy file `f`, each a vector of fields."
  [f]
  (with-open [reader (io/reader f :encoding "ISO-8859-1")]
    (->> (line-seq reader)
         (remove str/blank?)
         (mapv #(str/split % #"@" -1)))))

(defn legacy-relation
  "The current relation of a legacy relations.csv `row`; nil when dropped."
  [[_ name _ _ taxonomic]]
  (if (and (= name "hyponymOf") (= taxonomic "nontaxonomic"))
    :dns/orthogonalHypernym
    (get legacy-relations name)))

(defn legacy-edges
  "The internal relations of legacy relations.csv `rows` as [source relation
  target] triples, dropped names and cross-lingual links excluded."
  [rows]
  (for [[source _ _ target :as row] rows
        :let [relation (legacy-relation row)]
        :when relation]
    [source relation target]))

(defn danish-sense?
  "Whether a legacy wordsenses.csv `row` is a Danish sense rather than a
  Princeton WordNet sense key used as a link placeholder."
  [[_ _ synset]]
  (re-matches #"\d+" synset))

(defn legacy-stats
  "The statistics of the legacy CSV release in `dir`, the owl:inverseOf pairs
  `inverses` of the current schemas deciding its inverse closure."
  [dir inverses]
  (let [synsets   (read-legacy-rows (io/file dir "synsets.csv"))
        senses    (filter danish-sense?
                          (read-legacy-rows (io/file dir "wordsenses.csv")))
        relations (read-legacy-rows (io/file dir "relations.csv"))
        edges     (legacy-edges relations)
        defined?  (fn [[_ _ gloss]]
                    (let [gloss (str/trim (or gloss ""))]
                      (not (or (= gloss "")
                               (str/starts-with? gloss placeholder-gloss)))))]
    {:synsets     (count synsets)
     :senses      (count (distinct (map first senses)))
     :words       (count (distinct (map second senses)))
     :definitions (count (filter defined? synsets))
     :relations   (frequencies (map second edges))
     :external    (count (filter (comp cross-lingual-relations second) relations))
     :degrees     (degree-stats (map first synsets) edges)
     :closure     (inverse-closure inverses edges)}))

(defn- synset-filter
  [var]
  (str "FILTER(STRSTARTS(STR(" var "), \"" prefix/dn-uri "synset-\"))"))

(defn- dannet-filter
  [var]
  (str "FILTER(STRSTARTS(STR(" var "), \"" prefix/dn-uri "\"))"))

(defn count-in
  "The number of solutions of the SPARQL `where` clause in graph `g`, or the
  number of distinct bindings of `var` when given."
  ([g where]
   (count-in g where nil))
  ([g where var]
   (let [aggregate (if var (str "COUNT(DISTINCT " var ")") "COUNT(*)")
         query     (op/sparql "SELECT (" aggregate " AS ?n) WHERE { " where " }")]
     (or (some-> (q/run g query) first (get '?n)) 0))))

(defn relation-edges
  "The asserted [source relation target] triples of wn: and dns: relations
  between dn: synsets in the base graph `g`."
  [g]
  (->> (q/run g (op/sparql "SELECT ?s ?p ?o WHERE { ?s ?p ?o . "
                           (synset-filter "?s") (synset-filter "?o") " }"))
       (keep (fn [{:syms [?s ?p ?o]}]
               (when (#{"wn" "dns"} (namespace ?p))
                 [?s ?p ?o])))))

(defn similar-breakdown
  "The wn:similar `edges` by how many of their endpoints (0, 1 or 2) are
  synsets of the 2023 adjective supplement, recognisable by their synthesised
  synset-s ids; the supplement is the source of most of these edges."
  [edges]
  (frequencies (for [[s p o] edges
                     :when (= p :wn/similar)]
                 (count (filter #(re-find #"synset-s" (str %)) [s o])))))

(defn inverse-relations
  "The owl:inverseOf pairs declared by the schemas in graph `g`, as a map in
  both directions."
  [g]
  (->> (q/run g (op/sparql "SELECT ?p ?q WHERE { ?p owl:inverseOf ?q }"))
       (mapcat (fn [{:syms [?p ?q]}] [[?p ?q] [?q ?p]]))
       (into {})))

(defn navigable-relations
  "The synset `relations` and their `inverses` counted in the inference graph
  `g`, i.e. the relations a user can follow on wordnet.dk."
  [g inverses relations]
  (into {}
        (for [p (distinct (concat relations (keep inverses relations)))]
          [p (count-in g (str "?s " (prefix/kw->qname p) " ?o . "
                              (synset-filter "?s") (synset-filter "?o")))])))

(def link-counts
  "The links reaching beyond the synset graph: a label, the SPARQL to count
  and the variable to count distinct values of (nil counts every solution)."
  [["Senses with a DDO source"
    (str "?s a ontolex:LexicalSense ; dns:source ?o . " (dannet-filter "?s")) "?s"]
   ["Words with a DDO source"
    (str "?s ontolex:canonicalForm ?f ; dns:source ?o . " (dannet-filter "?s")) "?s"]
   ["Words linked to a COR word"
    (str "?s ontolex:canonicalForm ?f ; owl:sameAs ?o . " (dannet-filter "?s")) "?s"]
   ["Synsets with an ILI concept"
    (str "?s wn:ili ?o . " (synset-filter "?s")) "?s"]
   ["Distinct ILI concepts linked"
    (str "?s wn:ili ?o . " (synset-filter "?s")) "?o"]
   ["Synsets sharing their ILI concept with another synset"
    (str "?s wn:ili ?o . ?t wn:ili ?o . FILTER(?s != ?t) " (synset-filter "?s")
         (synset-filter "?t")) "?s"]
   ["Synset links to OEWN synsets (eq*)"
    (str "VALUES ?p { wn:eq_synonym dns:eqHypernym dns:eqHyponym dns:eqSimilar } "
         "?s ?p ?o . " (synset-filter "?s")) nil]
   ["Synsets with a supersense"
    (str "?s wn:lexfile ?o . " (synset-filter "?s")) "?s"]
   ["Synsets with an ontological type"
    (str "?s dns:ontologicalType ?o . " (synset-filter "?s")) "?s"]
   ["Synsets with sentiment"
    (str "?s dns:sentiment ?o . " (synset-filter "?s")) "?s"]
   ["Senses with sentiment"
    (str "?s a ontolex:LexicalSense ; dns:sentiment ?o . " (dannet-filter "?s")) "?s"]
   ["Inheritance markings"
    (str "?s dns:inherited ?o . " (synset-filter "?s")) nil]
   ["Senses with an example"
    (str "?s a ontolex:LexicalSense ; lexinfo:senseExample ?o . " (dannet-filter "?s")) "?s"]
   ["COR.SEM senses linked to a synset" "?s dns:linkedSynset ?o" "?s"]
   ["COR.SEM senses with a hypernym anchor" "?s dns:hypernymAnchor ?o" "?s"]
   ["COR.SEM senses matched to a DanNet sense" "?s dns:eqSense ?o" "?s"]
   ["COR.SEM senses with a FrameNet frame" "?s dns:frame ?o" "?s"]])

(defn count-links
  "The link-counts of the base union graph `g` as a vector of [label count]."
  [g]
  (vec (for [[label where var] link-counts]
         [label (count-in g where var)])))

(defn dataset-stats
  "The lime and void statistics every dataset declares about itself in the
  base union graph `g`."
  [g]
  (->> (q/run g (op/sparql
                  "SELECT DISTINCT ?d ?triples ?entries ?concepts ?lexicalizations
                   WHERE {
                     VALUES ?type { dcat:Dataset lime:Lexicon }
                     ?d a ?type .
                     OPTIONAL { ?d void:triples ?triples }
                     OPTIONAL { ?d lime:lexicalEntries ?entries }
                     OPTIONAL { ?d lime:concepts ?concepts }
                     OPTIONAL { ?d lime:lexicalizations ?lexicalizations }
                   }"))
       (map (fn [{:syms [?d ?triples ?entries ?concepts ?lexicalizations]}]
              [(str ?d) ?triples ?entries ?concepts ?lexicalizations]))
       (sort-by first)
       (vec)))

(defn current-stats
  "The statistics of the live db map `dannet`: the asserted dn: graph for the
  counts, the base union graph for the links to other datasets, and the
  inference graph for the navigable relations."
  [{:keys [dataset graph] :as dannet}]
  (let [dn        (db/get-graph dataset prefix/dn-uri)
        base      (.getGraph (.getUnionModel dataset))
        edges     (relation-edges dn)
        synsets   (->> (q/run dn '[:bgp [?s :rdf/type :ontolex/LexicalConcept]])
                       (map '?s))
        relations (frequencies (map second edges))
        inverses  (inverse-relations graph)]
    {:version     release/to
     :synsets     (count synsets)
     :senses      (count-in dn "?s a ontolex:LexicalSense")
     :words       (count-in dn "?w ontolex:canonicalForm ?f")
     :definitions (count-in dn (str "?s skos:definition ?d . " (synset-filter "?s")) "?s")
     :relations   relations
     :external    (count-in dn (str "VALUES ?p { wn:ili wn:eq_synonym dns:eqHypernym "
                                    "dns:eqHyponym dns:eqSimilar } ?s ?p ?o . "
                                    (synset-filter "?s")))
     :degrees     (degree-stats synsets edges)
     :similar     (similar-breakdown edges)
     :closure     (inverse-closure inverses edges)
     :navigable   (navigable-relations graph inverses (keys relations))
     :links       (count-links base)
     :datasets    (dataset-stats base)}))

(defn- total
  [relations]
  (reduce + 0 (vals relations)))

(defn- decimals
  "Format `x` with two decimals and a period, whatever the JVM locale."
  [x]
  (String/format java.util.Locale/ROOT "%.2f" (to-array [(double x)])))

(defn- share
  [part whole]
  (String/format java.util.Locale/ROOT "%d (%.1f%%)"
                 (to-array [part (* 100.0 (/ part whole))])))

(defn size-table
  "Size of the two releases side by side."
  [legacy current]
  {:header ["Measure" "DanNet 2.2" (str "DanNet " (:version current))]
   :rows   [["Synsets" (:synsets legacy) (:synsets current)]
            ["Senses" (:senses legacy) (:senses current)]
            ["Words" (:words legacy) (:words current)]
            ["Synsets with a definition" (:definitions legacy) (:definitions current)]
            ["Asserted synset relations" (total (:relations legacy)) (total (:relations current))]
            ["Relation types" (count (:relations legacy)) (count (:relations current))]
            ["Links to other wordnets" (:external legacy) (:external current)]]})

(defn relation-table
  "Every synset relation with its count in both releases, largest first."
  [legacy current]
  (let [relations (distinct (concat (keys (:relations current))
                                    (keys (:relations legacy))))]
    {:header ["Relation" "DanNet 2.2" (str "DanNet " (:version current))]
     :rows   (->> relations
                  (map (fn [p] [(str (namespace p) ":" (name p))
                                (get-in legacy [:relations p] 0)
                                (get-in current [:relations p] 0)]))
                  (sort-by (fn [[_ old new]] [(- new) (- old)]))
                  (vec))}))

(defn connectivity-table
  "Connectivity of the synset graph in both releases: degrees count asserted
  relations in either direction, the closure adds the inverses the schemas
  imply, and the inference model is what wordnet.dk serves."
  [legacy current]
  (let [old (:degrees legacy)
        new (:degrees current)]
    {:header ["Measure" "DanNet 2.2" (str "DanNet " (:version current))]
     :rows   [["Asserted synset relations" (total (:relations legacy)) (total (:relations current))]
              ["Relations with inverses (closure)" (:closure legacy) (:closure current)]
              ["Relations in the inference model" nil (total (:navigable current))]
              ["Mean outgoing relations per synset" (decimals (:mean-out old))
               (decimals (:mean-out new))]
              ["Mean degree" (decimals (:mean-degree old)) (decimals (:mean-degree new))]
              ["Median degree" (:median-degree old) (:median-degree new)]
              ["Synsets without relations" (:isolated old) (:isolated new)]
              ["Synsets with taxonomic relations only"
               (share (:taxonomic-only old) (:synsets old))
               (share (:taxonomic-only new) (:synsets new))]]}))

(defn similar-table
  "The wn:similar edges of the current release by how many of their endpoints
  belong to the 2023 adjective supplement."
  [current]
  (let [similar (:similar current)]
    {:header ["wn:similar edges" "Count"]
     :rows   [["Between two 2023 adjective synsets" (get similar 2 0)]
              ["One endpoint a 2023 adjective synset" (get similar 1 0)]
              ["Between older synsets" (get similar 0 0)]]}))

(defn link-table
  "The links beyond the synset graph in the current release."
  [current]
  {:header ["Link" "Count"]
   :rows   (:links current)})

(defn dataset-table
  "The self-declared size of every dataset in the store."
  [current]
  {:header ["Dataset" "Triples" "Entries" "Concepts" "Lexicalizations"]
   :rows   (:datasets current)})

(defn tables
  "All tables of the paper, keyed by name, from the `legacy` and `current`
  stats maps."
  [legacy current]
  {:size         (size-table legacy current)
   :relations    (relation-table legacy current)
   :connectivity (connectivity-table legacy current)
   :similar      (similar-table current)
   :links        (link-table current)
   :datasets     (dataset-table current)})

(defn- cell
  [x]
  (if (nil? x) "" (str x)))

(defn ->markdown
  "Render a `table` of :header and :rows as a Markdown table."
  [{:keys [header rows]}]
  (let [row (fn [cells] (str "| " (str/join " | " (map cell cells)) " |"))]
    (str/join "\n" (concat [(row header)
                            (str "|" (str/join "|" (repeat (count header) " --- ")) "|")]
                           (map row rows)))))

(defn- latex-escape
  [s]
  (str/replace s #"[%&_#]" #(str "\\" %)))

(defn ->latex
  "Render a `table` of :header and :rows as a booktabs tabular, the first
  column left-aligned and the rest right-aligned."
  [{:keys [header rows]}]
  (let [row (fn [cells] (str (str/join " & " (map (comp latex-escape cell) cells)) " \\\\"))]
    (str/join "\n" (concat [(str "\\begin{tabular}{l" (apply str (repeat (dec (count header)) "r")) "}")
                            "\\toprule"
                            (row header)
                            "\\midrule"]
                           (map row rows)
                           ["\\bottomrule"
                            "\\end{tabular}"]))))

(defn- render-all
  [heading render tables]
  (str/join "\n\n" (for [[k table] tables]
                     (str heading (name k) "\n" (render table)))))

(defn export-stats!
  "Write the statistics of the live db map `dannet` against the legacy CSV in
  `legacy` to `dir` as stats.edn (every number), stats.md and stats.tex (the
  tables); by default export/stats/<version>/ and the DanNet 2.2 release."
  ([dannet]
   (export-stats! dannet (str "export/stats/" release/to "/") legacy-dir))
  ([dannet dir legacy]
   (println "Computing statistics against" legacy)
   (let [new    (current-stats dannet)
         old    (legacy-stats legacy (inverse-relations (:graph dannet)))
         tables (tables old new)
         in-dir (partial str dir)]
     (io/make-parents (in-dir "stats.edn"))
     (spit (in-dir "stats.edn") (pr-str {:legacy old :current new}))
     (spit (in-dir "stats.md") (render-all "## " ->markdown tables))
     (spit (in-dir "stats.tex") (render-all "% " ->latex tables))
     (println "Statistics written to" dir)
     tables)))

(comment
  (legacy-stats legacy-dir (inverse-relations (:graph @dk.cst.dannet.web.instance/db)))
  (current-stats @dk.cst.dannet.web.instance/db)
  (export-stats! @dk.cst.dannet.web.instance/db)
  #_.)
