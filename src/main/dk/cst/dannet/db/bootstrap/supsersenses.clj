(ns dk.cst.dannet.db.bootstrap.supsersenses
  (:require [clojure.string :as str]
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

(def supsersense->pos
  (comp first #(str/split % #"\.")))

;; NOTE: this issue only occurs for two rows:
;; Plant+Object+Comestible        136    noun.food; noun.plant
;; Plant+Object+Part+Comestible   324    noun.food; noun.plant
(defn one-to-many?
  [[_ supersenses]]
  (let [parts-of-speech (map supsersense->pos supersenses)]
    (when (> (count parts-of-speech) 1)
      (apply = parts-of-speech))))

;; TODO: create a mapping from supersense->#{synsets}, use it to populate graph
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
                                 (str/replace #"adj" "adjective")
                                 (str/split #";|,")))]))))

(defn create-mapping!
  [dataset rows]
  (reset! supersense->synsets {})
  (doseq [[ontotypes supersenses] rows]
    (let [g  (db/get-graph dataset prefix/dn-uri)
          ms (->> (sparql-query ontotypes)
                  (q/run g)
                  (group-by (comp name '?pos)))]
      (doseq [supersense supersenses]
        (prn supersense ontotypes)
        (if-let [ms' (get ms (first (str/split supersense #"\.")))]
          (swap! supersense->synsets update supersense concat ms')
          (prn '|--------> 'bad-match supersense 'vs ontotypes 'in (keys ms)))))))

;; TODO: figure out correct ontotype for noun.substance
(defn triples-to-add
  [supersense->synsets]
  (mapcat (fn [[supersense synsets]]
            (for [{:syms [?t]} synsets]
              ;; NOTE: we already use dc:subject for DDO subjects
              [?t :dn/supersense supersense]))
          (update-vals supersense->synsets set)))

(def en-diff-query
  (op/sparql
    "SELECT ?dnSynset ?enSynset ?dnSubject ?enSubject
     WHERE {
       ?dnSynset wn:eq_synonym ?enSynset .
       ?dnSynset dns:supersense ?dnSubject .
       ?enSynset <http://purl.org/dc/terms/subject> ?enSubject .
       FILTER (?dnSubject != ?enSubject)
     }"))

(comment
  (count (q/run (:graph @dk.cst.dannet.web.resources/db) en-diff-query))

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
