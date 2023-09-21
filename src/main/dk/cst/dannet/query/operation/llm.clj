(ns dk.cst.dannet.query.operation.llm
  "Queries for extracting data to be used in LLM (e.g. ChatGPT) testing."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.data.csv :as csv]
            [dk.ative.docjure.spreadsheet :as xl]
            [dk.cst.dannet.db :refer [get-graph]]
            [dk.cst.dannet.prefix :refer [cor-uri]]
            [dk.cst.dannet.shared :refer [sense-labels sense-label synset-sep]]
            [dk.cst.dannet.query :refer [run]]
            [dk.cst.dannet.query.operation :refer [sparql]]
            [dk.cst.dannet.web.resources :refer [db]]))

(def en-et-query
  (sparql
    "SELECT
      (str(?canonicalString) as ?canonical)
      (str(?singularString) as ?singular)
     WHERE {
       ?w rdf:type ontolex:Word ;
          lexinfo:partOfSpeech lexinfo:noun ;
          ontolex:canonicalForm ?canonicalForm ;
          ontolex:otherForm ?singularForm .

       ?canonicalForm ontolex:writtenRep ?canonicalString .

       ?singularForm rdfs:label ?singularLabel .
       FILTER regex(str(?singularLabel), \"sg.best[)]\") .
       ?singularForm ontolex:writtenRep ?singularString .
     }
     "))

(def artifact-comestible-liquid-query
  (sparql
    "SELECT ?l
     WHERE {
       ?t dns:ontologicalType ?to .
       ?to rdfs:member dnc:Artifact .
       ?to rdfs:member dnc:Comestible .
       ?to rdfs:member dnc:Liquid .
       FILTER NOT EXISTS {
         ?to rdfs:member ?member .
         FILTER (?member NOT IN(dnc:Artifact,
                                dnc:Comestible,
                                dnc:Liquid))
       }
       ?t rdfs:label ?l .
     }"))

(def artifact-liquid-query
  (sparql
    "SELECT ?l
     WHERE {
       ?t dns:ontologicalType ?to .
       ?to rdfs:member dnc:Artifact .
       ?to rdfs:member dnc:Liquid .
       FILTER NOT EXISTS {
         ?to rdfs:member ?member .
         FILTER (?member NOT IN(dnc:Artifact,
                                dnc:Liquid))
       }
       ?t rdfs:label ?l .
     }"))

(def non-liquid-hyponym-query
  (sparql
    "SELECT ?pl ?tl
     WHERE {
       ?p dns:ontologicalType ?po .
       ?po rdfs:member dnc:Artifact .
       ?po rdfs:member dnc:Liquid .
       ?t wn:hypernym ?p .
       ?t dns:ontologicalType ?to .
       FILTER NOT EXISTS {
         ?to rdfs:member ?member .
         FILTER (?member IN(dnc:Liquid))
       }

       # Remove synsets with polysemic lemmas that *can* actually be liquid,
       # e.g. juice(karton) vs juice(drik) is ambiguous and not useful.
       FILTER NOT EXISTS {
         ?w ontolex:evokes ?t .
         ?w ontolex:evokes ?o .
         ?o dns:ontologicalType ?oo .
         ?oo rdfs:member ?omember .
         FILTER (?omember IN(dnc:Liquid))
       }

       ?p rdfs:label ?pl .
       ?t rdfs:label ?tl .
     }"))

(def point-in-time-query
  (sparql
    "SELECT ?l
     WHERE {
       ?t wn:hypernym dn:synset-9961 .

       # Check lemma collision with 'tidsperiode' hyponyms
       FILTER NOT EXISTS {
         ?w ontolex:evokes ?t .
         ?w ontolex:evokes ?o .
         ?o wn:hypernym dn:synset-8523 .
       }

       ?t rdfs:label ?l .
     }"))

(def period-of-time-query
  (sparql
    "SELECT ?l
     WHERE {
       ?t wn:hypernym dn:synset-8523 .

       # Check lemma collision with 'tidspunkt' hyponyms
       FILTER NOT EXISTS {
         ?w ontolex:evokes ?t .
         ?w ontolex:evokes ?o .
         ?o wn:hypernym dn:synset-9961 .
       }

       ?t rdfs:label ?l .
     }"))

(def disciplines-query
  (sparql
    "SELECT ?l
     WHERE {
       dn:synset-3090 wn:hyponym ?t .

       ?t rdfs:label ?l .
     }"))

(def activities-query
  (sparql
    "SELECT ?l
     WHERE {
       dn:synset-17021 wn:hyponym ?p . #activity

       ?p rdfs:label ?l .
     }"))

;; NOTE: things that are commonly considered sports, such as "bordfodbold",
;; are considered "spil" in DanNet, which is a separate subtree of "aktivitet".
(def non-sports-activities-query
  (sparql
    "SELECT DISTINCT ?l
     WHERE {
       dn:synset-17021 wn:hyponym+ ?p . #activity

       # remove sports from activities
       NOT EXISTS {
         dn:synset-24138 wn:hyponym+ ?p .
       }

       ?p rdfs:label ?l .
     }"))

(def sports-query
  (sparql
    "SELECT DISTINCT ?l
     WHERE {
       dn:synset-24138 wn:hyponym+ ?t . #sport

       ?t rdfs:label ?l .
     }"))

(def feeling-query
  (sparql
    "SELECT DISTINCT ?l
     WHERE {
       dn:synset-14244 wn:hyponym ?t . #feeling
       dn:synset-17571 wn:hyponym+ ?p . #thought

       # Check lemma collision
       FILTER NOT EXISTS {
         ?w ontolex:evokes ?t .
         ?w ontolex:evokes ?p .
       }

       ?t rdfs:label ?l . # use feeling
     }"))

;; NOTE: identical to the feeling-query, just selects the other label.
(def thought-query
  (sparql
    "SELECT DISTINCT ?l
     WHERE {
       dn:synset-14244 wn:hyponym ?t . #feeling
       dn:synset-17571 wn:hyponym+ ?p . #thought

       # Check lemma collision
       FILTER NOT EXISTS {
         ?w ontolex:evokes ?t .
         ?w ontolex:evokes ?p .
       }

       ?p rdfs:label ?l . #use thought
     }"))

(def orthogonal-hypernym-animal-object-query
  (sparql
    "SELECT ?pl ?tl
     WHERE {
       ?t dns:orthogonalHypernym ?p .
       ?t dns:ontologicalType ?to .
       ?to rdfs:member dnc:Animal .
       ?to rdfs:member dnc:Object .
       FILTER NOT EXISTS {
         ?to rdfs:member ?member .
         FILTER (?member NOT IN(dnc:Animal, dnc:Object))
       }
       ?p rdfs:label ?pl .
       ?t rdfs:label ?tl .
     }"))

(def orthogonal-hypernym-plant-object-query
  (sparql
    "SELECT ?pl ?tl
     WHERE {
       ?t dns:orthogonalHypernym ?p .

       # check ontotype for ?p
       ?p dns:ontologicalType ?po .
       ?po rdfs:member dnc:Plant .
       ?po rdfs:member dnc:Object .
       FILTER NOT EXISTS {
         ?po rdfs:member ?member .
         FILTER (?member NOT IN(dnc:Plant, dnc:Object))
       }

       ?p rdfs:label ?pl .
       ?t rdfs:label ?tl .
     }"))

(def en-et*
  (delay
    (->> (run (get-graph (:dataset @db) cor-uri) en-et-query)
         (map (fn [{:syms [?canonical ?singular]}]
                (if (str/ends-with? ?singular "en")
                  [?canonical "en"]
                  [?canonical "et"])))
         (into {}))))

(defn en-et
  "Return the most likely gender for `s` when available."
  [s]
  (some-> (or
            ;; the primary source
            (get @en-et* s)

            ;; possible additional corrections based on affixes
            (cond
              (or (str/ends-with? s "træ")
                  (str/ends-with? s "dyr"))
              "et"

              (str/ends-with? s "plante")
              "en"))
          (str " ")))

(def inference-patterns
  "These patterns all map to the GP4_inference_nedarvningDanNet.xlsx file I was
  sent by Sussi. The keys of the map refer to the lines in that file and the
  values are the patterns used to generate data to test this pattern."
  {"comestible liquid"
   [[artifact-comestible-liquid-query
     artifact-liquid-query]
    ["" " er en spiselig væske"]
    [["" " er ikke en spiselig væske"] true]
    [["" " er en spiselig væske"] false]]

   "non-liquid"
   [[non-liquid-hyponym-query]
    ["" " er en væske"]
    [["" " er ikke en væske"] true]
    [["" " er en væske"] false]]

   "period of time"
   [[period-of-time-query
     point-in-time-query]
    [en-et " er et tidsrum"]
    [[en-et " er ikke et tidsrum"] true]
    [[en-et " er et tidsrum"] false]]

   "point in time"
   [[point-in-time-query
     period-of-time-query]
    [en-et " er et tidspunkt"]
    [[en-et " er ikke et tidspunkt"] true]
    [[en-et " er et tidspunkt"] false]]

   "disciplines"
   [[disciplines-query
     activities-query]
    ["" " er en disciplin"]
    [["" " er ikke en disciplin"] true]
    [["" " er en disciplin"] false]]

   "sports"
   [[non-sports-activities-query
     sports-query]
    ["" " er ikke en type sport"]
    [["" " er en type sport"] true]
    [["" " er ikke en type sport"] false]]

   "feelings"
   [[feeling-query
     thought-query]
    ["" " er en følelse"]
    [["" " er ikke en følelse"] true]
    [["" " er en følelse"] false]]

   "orthogonal animal hypernyms"
   [[orthogonal-hypernym-animal-object-query]
    [en-et " er et dyr"]
    [[en-et " er et dyr"] true]
    [[en-et " er en dyreart"] false]]

   "orthogonal plant hypernyms"
   [[orthogonal-hypernym-plant-object-query]
    [en-et " er en plante"]
    [[en-et " er en plante"] true]
    [[en-et " er en plantesort"] false]]})

(defn extract-labels
  "Flatten the synset labels for key `k` in the query result `rows` into sense
  labels."
  [k rows]
  (->> rows
       (mapcat (fn [m]
                 (->> (get m k)
                      (str)
                      (sense-labels synset-sep)
                      (map #(second (re-find sense-label %))))))
       (set)))

(defn apply-template
  "Apply `s` to a string `template` represented as a coll of strings."
  [template s]
  (let [prefix (first template)]
    (str
      (if (fn? prefix)
        (prefix s)
        prefix)
      s (second template))))

(defn- apply-queries
  "Return [prompt-rows test-rows prompt-ks test-ks] for `queries`."
  [[q1 q2 :as queries]]
  (if (= 2 (count queries))
    [(run (:graph @db) q1)
     (run (:graph @db) q2)
     '?l '?l]
    (let [rows (run (:graph @db) q1)]
      [rows rows '?pl '?tl])))

(defn symmetric-difference
  "The symmetric difference of two sets, also known as the disjunctive union and
  set sum, is the set of elements which are in either of the sets, but not in
  their intersection. "
  [coll1 coll2]
  (let [coll1' (set coll1)
        coll2' (set coll2)]
    [(set/difference coll1' coll2')
     (set/difference coll2' coll1')]))

(defn sample
  [n xs]
  (take n (shuffle xs)))

(defn gen-rows
  "Generate rows for outputting to a CSV file based on database `queries`,
  a `prompt-template` for generating the prompt, and 1 or more `test-templates`.

  Some conventions used for queries: ?t refers to the test resource while ?p
  refers to a prompt resource, i.e. the thing being tested vs. the thing(s)
  making up the preceding prompt. Since we want labels, ?tl and ?pl are the
  important bits actually being selected.

  The queries collection consists of either a [prompt-query test-query] pair or
  a single [query] collection used to query both prompt-rows and test-rows. When
  a pair is present there is no ambivalence, so ?l can be used as a label key."
  [queries prompt-template & test-templates]
  (let [[prompt-rows test-rows prompt-ks test-ks] (apply-queries queries)
        [prompt-lemmas test-lemmas] (symmetric-difference
                                      (extract-labels prompt-ks prompt-rows)
                                      (extract-labels test-ks test-rows))
        lemma->prompt-sentence (partial apply-template prompt-template)
        prompt-sentences       (map lemma->prompt-sentence prompt-lemmas)]

    (concat
      ;; add tests for test lemmas using the test templates
      (mapcat (fn [test-lemma]
                (for [[template bool] test-templates]
                  [(str/join "; " (sample 2 prompt-sentences))
                   (apply-template template test-lemma)
                   bool]))
              (sample 30 test-lemmas))

      ;; add additional true tests for the certified true prompt lemmas
      (for [prompt-lemma (sample 30 prompt-lemmas)]
        (let [other-lemmas (disj prompt-lemmas prompt-lemma)]
          [(str/join "; " (sample 2 (map lemma->prompt-sentence other-lemmas)))
           (apply-template prompt-template prompt-lemma)
           true])))))

(defn rows
  "Query the DanNet graph for the patterns described in `k` or return relevant
  rows for every pattern if none specified."
  [& [k]]
  (if k
    (->> (get inference-patterns k)
         (apply gen-rows)
         (map (fn [row] (conj row k))))
    (apply concat (for [k (keys inference-patterns)]
                    (rows k)))))

(defn write-csv!
  [title rows]
  (with-open [writer (io/writer (str title ".csv"))]
    (csv/write-csv writer rows :quote? (constantly true))))

(defn write-spreadsheet!
  [title rows]
  (let [data       (concat [["prompt" "test" "result" "comment"]] rows)
        wb         (xl/create-workbook "Tests" data)
        sheet      (xl/select-sheet "Tests" wb)
        header-row (first (xl/row-seq sheet))]
    (xl/set-row-style! header-row (xl/create-cell-style! wb {:font {:bold true}}))
    (xl/save-workbook! (str title ".xlsx") wb)))

(comment
  (en-et "papegøje")                                        ; en
  (en-et "retskrav")                                        ; en
  (en-et 123)                                               ; nil

  (run (:graph @db) point-in-time-query)
  (run (:graph @db) period-of-time-query)
  (run (:graph @db) feeling-thought-query)
  (count (run (:graph @db) feeling-query))
  (count (run (:graph @db) thought-query))

  ;; Test query result
  (->> (run (:graph @db) orthogonal-hypernym-plant-object-query)
       (map (fn [{:syms [?pl ?tl]}]
              [(str ?pl) (str ?tl)])))

  (count (rows))
  (count (rows "orthogonal plant hypernyms"))
  (count (rows "sports"))
  (count (rows "disciplines"))

  ;; Write a partial dataset to disk
  (write-csv! "time" (rows "orthogonal animal hypernyms"))
  (write-spreadsheet! "ortho" (rows "feelings"))

  ;; Write the entire dataset to disk
  (write-csv! "inference" (rows))
  (write-spreadsheet! "inference" (rows))
  #_.)
