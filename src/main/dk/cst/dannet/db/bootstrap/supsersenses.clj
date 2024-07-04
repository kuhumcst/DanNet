(ns dk.cst.dannet.db.bootstrap.supsersenses
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

(comment
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
