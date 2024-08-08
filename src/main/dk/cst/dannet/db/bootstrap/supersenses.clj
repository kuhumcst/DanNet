(ns dk.cst.dannet.db.bootstrap.supersenses
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [dk.ative.docjure.spreadsheet :as xl]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.db :as db]
            [dk.cst.dannet.query :as q]
            [dk.cst.dannet.query.operation :as op]))

(def workbook
  "bootstrap/other/dannet-new/Mapning af ontotype til supersense.xlsx")

(def rows
  (delay (->> (xl/load-workbook workbook)
              (xl/select-sheet "Ark1")
              (xl/row-seq)
              (map (comp #(map xl/read-cell %) xl/cell-seq))
              (rest)
              (butlast)
              (map (fn [[ontotype _freq supersense _comment]]
                     ;; TODO: have this confirmed
                     ;; correcting an error in the original data
                     (if (= ontotype "noun.food")
                       ["Natural+Substance" supersense]
                       [ontotype supersense]))))))

(def ontotype-corrections
  {"1stOrderEntity" "FirstOrderEntity"
   "2ndOrderEntity" "SecondOrderEntity"
   "3rdOrderEntity" "ThirdOrderEntity"})

(def multi-type
  #"\(+(.+)\)")

(defonce supersense->synsets
  (atom {}))

(defn sparql-query
  [ontotypes]
  (->> (concat
         ["SELECT ?t ?l ?pos WHERE { ?t dns:ontologicalType ?to . ?w ontolex:evokes ?t . ?w wn:partOfSpeech ?pos ."]
         (for [ontotype ontotypes]
           (str "?to rdfs:member dnc:" ontotype " . "))
         ["FILTER NOT EXISTS { ?to rdfs:member ?member . FILTER (?member NOT IN("]
         (for [ontotype (butlast ontotypes)]
           (str "dnc:" ontotype ","))
         [(str "dnc:" (last ontotypes))]
         ["))} ?t rdfs:label ?l . }"])
       (apply str)
       (op/sparql)))

(defn prepare-rows
  [rows]
  (->> rows

       ;; explode multi-types
       (mapcat (fn [[ontotype supersense]]
                 (if (re-find multi-type ontotype)
                   [[(str/replace ontotype #"\(+(.+)\)" "") supersense]
                    [(str/replace ontotype #"\(+(.+)\)" "$1") supersense]]
                   [[ontotype supersense]])))

       ;; split cells
       (map (fn [[ontotype supersense]]
              [(map #(get ontotype-corrections % %) (str/split ontotype #"\+"))
               (map str/trim (-> supersense
                                 (str/replace #":" ".")
                                 (str/split #";|,")))]))))

(defn create-mapping!
  [dataset rows]
  (reset! supersense->synsets {})
  (doseq [[ontotypes supersenses] rows]
    (let [g  (db/get-graph dataset prefix/dn-uri)
          ms (set/rename-keys
               (->> (sparql-query ontotypes)
                    (q/run g)
                    (group-by (comp name '?pos)))
               {"adjective" "adj"})]
      (doseq [supersense supersenses]
        (prn supersense ontotypes)
        (if-let [ms' (get ms (first (str/split supersense #"\.")))]
          (swap! supersense->synsets update supersense concat ms')
          (prn '|--------> 'bad-match supersense 'vs ontotypes 'in (keys ms)))))))

(defn triples-to-add
  [supersense->synsets]
  (mapcat (fn [[supersense synsets]]
            (for [{:syms [?t]} synsets]
              ;; NOTE: we already use dc:subject for DDO subjects
              [?t :dns/supersense supersense]))
          (update-vals supersense->synsets set)))

(def en-diff-query
  (op/sparql
    "SELECT ?dnSynset ?dnLabel ?enSynset ?enLabel ?dnSubject ?enSubject
     WHERE {
       ?dnSynset wn:eq_synonym ?enSynset ;
                 dns:supersense ?dnSubject ;
                 rdfs:label ?dnLabel .
       ?enSynset <http://purl.org/dc/terms/subject> ?enSubject ;
                 rdfs:label ?enLabel .
       FILTER (?dnSubject != ?enSubject)
     }"))

(defn ->dn-supersense-query
  [supersense]
  (op/sparql
    "SELECT ?synset ?label ?hypernym ?hypernymLabel ?ot
     WHERE {
       ?synset dns:supersense \"" supersense "\" ;
               rdfs:label ?label ;
               dns:ontologicalType ?ontotype .
       OPTIONAL {
         ?synset  wn:hypernym ?hypernym .
         ?hypernym rdfs:label ?hypernymLabel .
       }
       ?ontotype rdfs:member ?ot .
     }"))


(defn ->en-supersense-query
  [supersense]
  (op/sparql
    "SELECT ?synset ?label ?hypernym ?hypernymLabel
     WHERE {
       ?synset <http://purl.org/dc/terms/subject> \"" supersense "\" ;
               rdfs:label ?label .
       OPTIONAL {
         ?synset  wn:hypernym ?hypernym .
         ?hypernym rdfs:label ?hypernymLabel .
       }
     }"))

(defn ->ancestor-query
  [synset ancestors]
  (let [candidates (str/join " " (map prefix/kw->qname ancestors))]
    (op/sparql
      "SELECT ?synset ?ancestor
       WHERE {
         BIND (" (prefix/kw->qname synset) " AS ?synset)
         VALUES ?ancestor {" candidates "}
         ?synset wn:hypernym+ ?ancestor .
       }")))

(defn by-dn-supersense
  [g supersense]
  (-> (q/run g (->dn-supersense-query supersense))
      (->> (group-by '?synset))
      (update-vals (fn glen [ms]
                     (assoc (dissoc (first ms) '?ot)
                       '?ontotype (sort (map '?ot ms)))))
      (vals)
      (->> (sort-by (comp parse-long #(re-find #"\d+" %) name '?synset)))))

(defn by-en-supersense
  [g supersense]
  (->> (q/run g (->en-supersense-query supersense))
       (sort-by (comp parse-long #(re-find #"\d+" %) name '?synset))))

(defn by-ancestors
  [g ancestors synset]
  (if (get (set ancestors) synset)
    synset
    (-> (q/run g (->ancestor-query synset ancestors))
        (first)
        (get '?ancestor))))

(def ancestor->supersense
  {:dn/synset-637   "verb.consumption"                      ; indtage
   :dn/synset-22598 "verb.creation"                         ; frembringe
   :dn/synset-2331  "verb.contact"                          ; gøre/handle
   :dn/synset-2220  "verb.creation"                         ; sy
   :dn/synset-47468 "verb.creation"                         ; reparere
   :dn/synset-38679 "verb.creation"                         ; bygge
   :dn/synset-33227 "verb.creation"                         ; danne
   :dn/synset-31753 "verb.motion"                           ; bevæge sig
   :dn/synset-2012  "verb.change"                           ; male
   :dn/synset-29369 "verb.change"                           ; forandre
   :dn/synset-43046 "verb.change"                           ; fremskaffe
   :dn/synset-22785 "verb.consumption"                      ; dinere
   :dn/synset-74863 "verb.consumption"                      ; tage (stoffer)

   ;; mapping the remaining synsets directly
   :dn/synset-30836 "verb.change"
   :dn/synset-42227 "verb.contact"
   :dn/synset-48739 "verb.possession"
   :dn/synset-48473 "verb.emotion"
   :dn/synset-48441 "verb.motion"
   :dn/synset-73598 "verb.creation"
   :dn/synset-50035 "verb.motion"
   :dn/synset-47321 "verb.change"
   :dn/synset-74861 "verb.body"
   #_.})

(def sense-inventories-file
  "bootstrap/other/elexis-wsd-1.1/sense_inventories/elexis-wsd-da_sense-inventory.tsv")

(def conllu-file
  "bootstrap/other/elexis-wsd-1.1/corpora/elexis-wsd-da_corpus.conllu")

(def sense-inventories-rows
  (delay
    (-> (slurp sense-inventories-file)
        (str/split #"\n")
        (->> (map #(str/split % #"\t"))))))

(def sense+defs
  (delay
    (->> @sense-inventories-rows
         (map (fn [[_ _ id definition]]
                (when-let [sense-id (re-find #"@_(.+)" id)]
                  [(keyword "dn" (str "sense-" (second sense-id)))
                   (when definition
                     (str/replace definition #" \.\.\." "…"))])))
         (remove nil?))))

(defn sense-information
  [g]
  (->> (for [[sense definition] @sense+defs]
         (let [ms (when definition
                    (q/run g
                           (let [alt-definition (str/replace definition #"[()]" "")]
                             (op/sparql
                               "SELECT *
                              WHERE {
                                VALUES ?definition {\"" definition "\"@da \"" alt-definition "\"@da}
                                  ?synset skos:definition ?definition ;
                                          ontolex:lexicalizedSense ?sense ;
                                          dns:supersense ?supersense .
                                  FILTER (strstarts(str(?sense), '" (prefix/kw->uri sense) "'))
                                }"))))]
           (if (not-empty ms)
             (first ms)
             (if-let [ms (not-empty (q/run g
                                           (op/sparql
                                             "SELECT *
                                              WHERE {
                                                ?synset ontolex:lexicalizedSense " (prefix/kw->qname sense) " ;
                                                          dns:supersense ?supersense .
                                                }")))]
               (first ms)
               (str "MISSING: " sense " - " definition)))))))

(def missing-supersense
  (op/sparql
    "SELECT ?synset ?ontotype ?label ?pos
     WHERE {
       ?synset dns:ontologicalType ?ot .
       FILTER NOT EXISTS { ?synset dns:supersense ?_ }
       ?ot rdfs:member ?ontotype .
       ?synset rdfs:label ?label .
       ?word ontolex:evokes ?synset ;
             wn:partOfSpeech ?pos .
     }"))

(defn synsets-without-supersenses
  "Find remaining synsets without supersenses in graph `g`."
  [g]
  (-> (->> (q/run g missing-supersense)
           (group-by '?synset))
      (update-vals (fn [ms]
                     (let [{:syms [?ontotype] :as res} (first ms)]
                       (assoc res
                         '?ontotype (set (conj (map '?ontotype (rest ms))
                                               ?ontotype))))))
      (vals)
      (->> (group-by (juxt '?ontotype '?pos)))))

(defn by-synset-count
  [ontotype+pos->synsets]
  (->> ontotype+pos->synsets
       (sort-by (comp count second))
       (reverse)))

(defn remaining-supersense-rows
  "Load remaining data from the xlsx file filled out by BSP."
  []
  (->> (xl/load-workbook "bootstrap/other/dannet-new/missing_supersenses_BSP.xlsx")
       (xl/select-sheet "missing supersenses")
       (xl/row-seq)
       (map (comp #(map xl/read-cell %) xl/cell-seq))
       (rest)))

(defn remaining-supersense-triples
  [g]
  (let [rows   (remaining-supersense-rows)
        groups (synsets-without-supersenses g)]
    (->> rows
         (mapcat (fn [[ontotype pos supersense]]
                   (let [ontotype' (->> (str/split ontotype #"\+")
                                        (map (partial keyword "dnc"))
                                        (set))
                         pos'      (keyword "wn" pos)]
                     (->> (get groups [ontotype' pos'])
                          (map (fn [{:syms [?synset]}]
                                 (when supersense
                                   [?synset :dns/supersense supersense])))))))
         (remove nil?)
         (set))))

(defn supersense-mapping
  [g]
  (->> (op/sparql
         "SELECT ?sense ?supersense
          WHERE {
             ?synset dns:supersense ?supersense ;
                     ontolex:lexicalizedSense ?sense .
          }")
       (q/run g)
       (map (juxt '?sense '?supersense))
       (into {})))

(defn rewrite-conllu
  [dataset f]
  (let [g                 (db/get-graph dataset prefix/dn-uri)
        sense->supersense (supersense-mapping g)
        missing-wsd       (atom {})
        mark-missing-wsd! (fn [lemma wsd s]
                            (swap! missing-wsd assoc wsd lemma)
                            s)]
    (with-meta
      (-> (slurp f)
          (str/split #"\n")
          (->> (map (fn [s]
                      (let [row (str/split s #"\t")]
                        (if (< (count row) 10)                ; ignore comments/blank lines
                          s
                          (let [attr-str (nth row 9)
                                lemma    (nth row 2)
                                attr     (when (not= attr-str "_")
                                           (-> attr-str
                                               (str/split #"\|")
                                               (->> (map #(str/split % #"="))
                                                    (map (fn [[k v]]
                                                           [k (or v "")]))
                                                    (into {}))))
                                wsd      (get attr "WSD")]
                            (if wsd
                              (if (or (= wsd "non-content-word") (empty? wsd))
                                s
                                (if-let [[_ id] (re-matches #".+@_(\d+)" wsd)]
                                  (if-let [supersense (get sense->supersense (keyword "dn" (str "sense-" id)))]
                                    (str/join "\t" (conj (subvec row 0 9)
                                                         (str attr-str "|supersense=" supersense)))
                                    (mark-missing-wsd! lemma wsd s))
                                  (mark-missing-wsd! lemma wsd s)))
                              s))))))
               (doall)))
      {:missing-wsd @missing-wsd})))

(comment
  (slurp sense-inventories-file)
  (slurp conllu-file)

  ;; find all sense IDs not present in DanNet, i.e. no supersenses (count: 3141)
  ;; and write to file
  (-> (rewrite-conllu (:dataset @dk.cst.dannet.web.resources/db) conllu-file)
      (meta)
      (get :missing-wsd)
      (->> (sort-by (juxt (comp str/lower-case second) first))
           (map (fn [[id lemma]]
                  (str lemma "\t" id "\n")))
           (str/join)
           (spit "export/missing_ids.tsv")))

  (->> (rewrite-conllu (:dataset @dk.cst.dannet.web.resources/db) conllu-file)
       (str/join "\n")
       (spit "export/elexis-wsd-da_sense-inventory_WITH_SUPERSENSES.conllu"))


  (supersense-mapping (db/get-graph (:dataset @dk.cst.dannet.web.resources/db) prefix/dn-uri))

  (count (q/run (:graph @dk.cst.dannet.web.resources/db)
                (op/sparql
                  "SELECT*
                   WHERE {
                      ?synset dns:supersense ?supersense .
                   }")))

  (update-vals (synsets-without-supersenses (db/get-graph (:dataset @dk.cst.dannet.web.resources/db) prefix/dn-uri)) count)

  (remaining-supersense-triples (db/get-graph (:dataset @dk.cst.dannet.web.resources/db) prefix/dn-uri))

  ;; Create a spreadsheet with remaining groupings of missing supersenses
  ;; To be filled out with supersense (third column)
  (let [rows       (for [[[ontotype pos] ms] (-> (:graph @dk.cst.dannet.web.resources/db)
                                                 (synsets-without-supersenses)
                                                 (by-synset-count))
                         :let [sample (take 10 (shuffle ms))]]
                     [(str/join "+" (sort (map name ontotype)))
                      (name pos)
                      nil
                      (count ms)
                      (str/join " " (map (comp str '?label) sample))
                      (str/join " " (map (comp prefix/kw->uri '?synset) sample))])
        wb         (xl/create-workbook
                     "missing supersenses"
                     (concat [["ontotype" "pos" "supersense" "count" "labels (sample)" "ids (sample)"]] rows))
        sheet      (xl/select-sheet "missing supersenses" wb)
        header-row (first (xl/row-seq sheet))]
    (xl/set-row-style! header-row (xl/create-cell-style! wb {:font {:bold true}}))
    (xl/save-workbook! "missing_supersenses.xlsx" wb))

  ;; Retrieve the correct senses from the graph
  (count (->> (filter string? (sense-information (:graph @dk.cst.dannet.web.resources/db)))
              (filter (partial re-find #"hyponymOf"))))

  ;; for comparing supersenses vs. ontotypes
  (->> (by-dn-supersense (:graph @dk.cst.dannet.web.resources/db) "verb.contact")
       (group-by '?hypernym)
       (sort-by (comp count second))
       (reverse)
       (map (fn [[k vs]]
              [k (count vs) (first vs)])))

  (->> (by-en-supersense (:graph @dk.cst.dannet.web.resources/db) "verb.consumption")
       (count))


  (by-ancestors (:graph @dk.cst.dannet.web.resources/db)
                [:dn/synset-637]
                :dn/synset-74870)

  ;; Grouping by a closed list of ancestors
  ;; trying to make the most obvious groupings based on supersenses
  (let [g         (:graph @dk.cst.dannet.web.resources/db)
        ancestors (keys ancestor->supersense)
        ancestor  (fn [{:syms [?synset]}]
                    (by-ancestors g ancestors ?synset))]
    (-> (group-by ancestor (by-dn-supersense g "verb.creation")) ; apply known groupings
        (get nil)                                           ; find remaining
        ((fn [ms]
           (println (count ms) "not accounted for")
           ms))
        (->> (group-by (juxt '?hypernym '?hypernymLabel))   ; organise by most pressing first
             (sort-by (comp count second))
             (reverse))))

  (keys @supersense->synsets)
  (count (triples-to-add @supersense->synsets))

  ;; test retrival of ontotypes
  (->> '("Property" "Time")
       (sparql-query)
       (q/run (:graph @dk.cst.dannet.web.resources/db))
       #_(count))

  ;; |--------> bad-match "noun.act" vs ("BoundedEvent" "Agentive" "Experience" "Condition" "Purpose") in ("verb")
  ;; |--------> bad-match "noun.act" vs ("BoundedEvent" "Agentive" "Time") in ("verb")
  ;; |--------> bad-match "noun.substance" vs ("noun.food") in nil
  ;;
  ;; BELOW is not relevant since they also have noun variants.
  ;; |--------> bad-match "noun.attribute" vs ("Property" "LanguageRepresentation") in ("adjective")
  ;; |--------> bad-match "noun.time" vs ("Property" "Time") in ("adjective")
  (->> (prepare-rows @rows)
       (create-mapping! (:dataset @dk.cst.dannet.web.resources/db)))
  #_.)
