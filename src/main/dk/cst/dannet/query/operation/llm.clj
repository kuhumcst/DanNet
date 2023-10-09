(ns dk.cst.dannet.query.operation.llm
  "Queries for extracting data to be used in LLM (e.g. ChatGPT) testing."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.data.csv :as csv]
            [clojure.math.combinatorics :as combo]
            [dk.ative.docjure.spreadsheet :as xl]
            [ham-fisted.api :as ham]
            [dk.cst.dannet.db :refer [get-graph]]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.prefix :refer [cor-uri]]
            [dk.cst.dannet.shared :refer [canonical
                                          sense-labels
                                          sense-label
                                          synset-sep]]
            [dk.cst.dannet.query :refer [run]]
            [dk.cst.dannet.query.operation :refer [sparql]]
            [dk.cst.dannet.web.resources :refer [db]]))

(def slang-query
  (sparql
    "SELECT DISTINCT (str(?rep) as ?lemma)
     WHERE {
       ?sense lexinfo:register lexinfo:slangRegister .
       ?word ontolex:sense ?sense ;
             ontolex:canonicalForm ?form .
       ?form ontolex:writtenRep ?rep .
     }"))

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

(def freq-query
  (sparql
    "SELECT (str(?rep) as ?label) ?freq
     WHERE {
       ?w dns:ddoFrequency ?freq .
       ?w ontolex:canonicalForm ?form .
       ?form ontolex:writtenRep ?rep .
     }"))

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

(def non-sports-activities-query
  (sparql
    "SELECT DISTINCT ?l
     WHERE {
       # activity
       dn:synset-17021 wn:hyponym ?activityGroup .

       # remove sports from activities (two synsets)
       # remove 'spil' (games) from activities too
       # (there is a fairly illogical split between games and sports in DanNet,
       # which otherwise makes examples that state that certain less physical
       # sports, e.g. 'bordfodbold' won't count while 'bordtennis' will)
       FILTER(?activityGroup NOT IN (
         dn:synset-1771,
         dn:synset-24138,
         dn:synset-24160
       ))

       ?activityGroup wn:hyponym+ ?p .

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

(def mero-part-query
  (sparql
    "SELECT ?sl ?ol
     WHERE {
       ?s wn:mero_part ?o .
       FILTER(STRSTARTS(str(?s), str(dn:))) .

       ?s dns:ontologicalType ?sot .
       ?o dns:ontologicalType ?oot .
       ?sot rdfs:member ?sotmember .
       ?oot rdfs:member ?ootmember .

       # remove the synset containing 'ord' (it is messing up many examples)
       FILTER (?o != dn:synset-7918)

       # remove the synset for 'krop; organisme; system' since this usage of
       # 'system' generally only results in bad examples.
       FILTER (?s != dn:synset-4202)

       # remove the synset for 'dyr; dyreart; kræ' since the three words are
       # used in too different contexts to reliable generate example sentences.
       FILTER (?s != dn:synset-3262)

       # remove the synset for 'plante; plantesort; plantevækst' since the words
       # are too apart contextually and the part-whole relationships too loose.
       FILTER (?s != dn:synset-559)

       # remove comestible parts when the parent isn't, e.g. dyrefilet
       FILTER NOT EXISTS {
         FILTER (?sotmember NOT IN (dnc:Comestible))
         FILTER (?ootmember IN (dnc:Comestible))
       }

       # remove ontological types which often result in poor generations
       FILTER NOT EXISTS {
         FILTER (?ootmember IN (dnc:LanguageRepresentation))
       }

       ?s rdfs:label ?sl .
       ?o rdfs:label ?ol .
     }"))

(defn ->used-for-query
  [synset]
  (sparql
    "SELECT ?l
     WHERE {
       ?s dns:usedFor " (prefix/kw->qname synset) ".
       ?s dns:ontologicalType ?ot .
       ?ot rdfs:member dnc:Artifact .
       ?ot rdfs:member dnc:Object .
       FILTER NOT EXISTS {
         ?ot rdfs:member dnc:Comestible .
       }
       ?s rdfs:label ?l .
     }"))

(def used-for-warm-query
  (->used-for-query :dn/synset-47011))

(def used-for-decorate-query
  (->used-for-query :dn/synset-43224))

(def used-for-cover-query
  (->used-for-query :dn/synset-2711))

(def used-for-dress-query
  (->used-for-query :dn/synset-2669))

(def used-for-protect-1-query
  (->used-for-query :dn/synset-2654))

(def used-for-protect-2-query
  (->used-for-query :dn/synset-44048))

(def slang-terms
  (delay
    (->> (run (:graph @db) slang-query)
         (map '?lemma)
         (set))))

(def articles
  (delay
    (->> (run (get-graph (:dataset @db) cor-uri) en-et-query)
         (map (fn [{:syms [?canonical ?singular]}]
                (if (str/ends-with? ?singular "en")
                  [?canonical "en"]
                  [?canonical "et"])))
         (into {}))))

(def lemma->frequency
  "Frequencies originally sourced from DDO."
  (delay
    (-> (group-by '?label (run (:graph @db) freq-query))
        (update-vals (fn [ms]
                       (apply max (map '?freq ms)))))))

(defn article-for
  "Return the most likely article for `s` when available."
  [s]
  (when-not (str/ends-with? s "tøj")                        ; no article
    (or
      (get @articles s)                                     ; primary source
      (cond                                                 ; other options
        (or (str/ends-with? s "træ")
            (str/ends-with? s "dyr"))
        "et"

        (or (str/ends-with? s "plante")
            (str/ends-with? s "dragt")
            (str/ends-with? s "bog"))
        "en"))))

(defn en|et
  [s]
  (if-let [article (article-for s)]
    (str/join " " [article s])
    s))

(def inference-patterns
  {"comestible liquid"
   [[artifact-comestible-liquid-query
     artifact-liquid-query]
    ['?l "er en spiselig væske"]
    [['?l "er ikke en spiselig væske"] true]
    [['?l "er en spiselig væske"] false]]

   "non-liquid"
   [[non-liquid-hyponym-query]
    [en|et '?pl "er en væske"]
    [[en|et '?tl "er ikke en væske"] true]
    [[en|et '?tl "er en væske"] false]]

   "period of time"
   [[period-of-time-query
     point-in-time-query]
    [en|et '?l "er et tidsrum"]
    [[en|et '?l "er ikke et tidsrum"] true]
    [[en|et '?l "er et tidsrum"] false]]

   "point in time"
   [[point-in-time-query
     period-of-time-query]
    [en|et '?l "er et tidspunkt"]
    [[en|et '?l "er ikke et tidspunkt"] true]
    [[en|et '?l "er et tidspunkt"] false]]

   "disciplines"
   [[disciplines-query
     activities-query]
    ['?l "er en disciplin"]
    [['?l "er ikke en disciplin"] true]
    [['?l "er en disciplin"] false]]

   "sports"
   [[non-sports-activities-query
     sports-query]
    ['?l "er ikke en type sport"]
    [['?l "er en type sport"] true]
    [['?l "er ikke en type sport"] false]]

   "feelings"
   [[feeling-query
     thought-query]
    ['?l "er en følelse"]
    [['?l "er ikke en følelse"] true]
    [['?l "er en følelse"] false]]

   "orthogonal animal hypernyms"
   [[orthogonal-hypernym-animal-object-query]
    [en|et '?pl "er et dyr"]
    [[en|et '?tl "er et dyr"] true]
    [[en|et '?tl "er en dyreart"] false]]

   "orthogonal plant hypernyms"
   [[orthogonal-hypernym-plant-object-query]
    [en|et '?pl "er en plante"]
    [[en|et '?tl "er en plante"] true]
    [[en|et '?tl "er en plantesort"] false]]

   "part-whole"
   [[mero-part-query]
    [en|et '?sl "kan have" en|et '?ol]
    [[en|et '?sl "kan ikke have" en|et '?ol] false]
    [[en|et '?sl "er" en|et '?ol] false]]

   "warm vs. decorate"
   [[used-for-warm-query
     used-for-decorate-query]
    ["man kan tage" en|et '?l "på for at holde sig varm"]
    [["man kan tage" en|et '?l "på for at holde sig varm"] false]]})

(defn split-labels
  "Flatten the `synset-label` into sense labels."
  [synset-label]
  (->> (str synset-label)
       (sense-labels synset-sep)
       (canonical)
       (map #(second (re-find sense-label %)))))

(defn cartesian-ms
  "Split a result row `m` with split labels into every possible single-value
  map combination of the constituent vals."
  [m]
  (->> (for [[k coll] m]
         (for [v coll]
           [k v]))
       (apply combo/cartesian-product)
       (map #(into {} %))))

(defn by-sense-label
  [ms]
  (->> (map #(update-vals % split-labels) ms)
       (mapcat cartesian-ms)))

(defn- real-v
  [m v]
  (if (symbol? v)
    (get m v)
    v))

(defn apply-template
  "Apply result row `m` to a `template` (a coll of strings, fns, or symbols).

  Strings are used directly; symbols are resolved by looking them up in the
  result row map; fns are applied to the next element and then disposed of."
  [template m]
  (let [f (atom nil)]
    (->> (reduce (fn [ret v]
                   (if (fn? v)
                     (do (reset! f v) ret)                  ; store fn
                     (conj ret (if-let [f* @f]
                                 (do
                                   (reset! f nil)           ; dispose of fn
                                   (f* (real-v m v)))       ; use fn
                                 (real-v m v)))))
                 []
                 template)
         (str/join " "))))

(def random-seed
  (atom nil))

(defn reproducible-shuffle
  "A reproducible version of 'shuffle' which increments a random seed in order
  to produce consistent, yet pseudorandom results.

  NOTE: the random seed must be set explicitly before calling!"
  [coll]
  (let [seed @random-seed]
    (if (integer? seed)
      (ham/shuffle coll {:seed (swap! random-seed inc)})
      (throw (ex-info "must init random-seed" {:seed seed})))))

(defn first-val
  "Used to presort each `m` in some rows by the value of the first key."
  [m]
  (let [first-key (-> m keys sort first)]
    (str (get m first-key))))

(defn sample
  "Take `n` randomly from `coll` in a reproducible way.

  NOTE: while the shuffle call itself is reproducible, the input coll should be
  also be sorted ahead of time e.g. using '(sort-by first-val rows)' to presort.
  Given an identical database graph, these two calls should together maximise
  reproducibility of the experiment and produce identical outputs."
  [n coll]
  (take n (reproducible-shuffle coll)))

(defn frequent?
  [lemma]
  (when-let [f (get @lemma->frequency lemma)]
    (> f 80)))

(def frequent-vals?
  (comp #(every? frequent? %) vals))

(defn slang-vals?
  [m]
  (some @slang-terms (vals m)))

(defn affix-vals?
  [m]
  (some #(re-find #"^-|-$" %) (vals m)))

(defn duplicate-vals?
  [m]
  (not= (count (vals m))
        (count (set (vals m)))))

(defn gen-rows
  "Generate rows for outputting to a CSV file based on database `queries`,
  a `prompt-template` for generating a prompt, and 1 or more `test-templates`."
  [queries prompt-template & test-templates]
  ;; The queries coll consists of either a [prompt-query test-query] pair or a
  ;; single [query] coll used to create both prompt-rows and test-rows.
  ;; The prompt-rows are also used separately to create true data points.
  (let [[prompt-rows test-rows] (if (= 2 (count queries))
                                  [(run (:graph @db) (first queries))
                                   (run (:graph @db) (second queries))]
                                  (let [rows (run (:graph @db) (first queries))]
                                    [rows rows]))

        ;; The data is synset labels and is split into rows of sense labels.
        ;; Prior to this, it is also presorted to facilitate reproducibility.
        prompt-rows        (by-sense-label (sort-by first-val prompt-rows))
        test-rows          (by-sense-label (sort-by first-val test-rows))

        ;; Find clashes between two sets of lemmas.
        clashes            (when (> (count queries) 1)
                             (set/intersection
                               (set (mapcat vals prompt-rows))
                               (set (mapcat vals test-rows))))

        ;; It should only be needed if using multiple queries as the source,
        ;; since the lemmas cannot be coordinated directly in the query.
        clashing-vals?     (fn [m]
                             (when clashes
                               (some clashes (vals m))))

        ;; Clean the data, removing e.g. infrequent words and potential clashes,
        ;; while also de-duplicating the rows to avoid repetitions.
        clean-rows         (fn [template rows]
                             (let [syms (filter symbol? template)]
                               (into #{}
                                     (comp
                                       (map #(select-keys % syms))
                                       (filter frequent-vals?)
                                       (remove slang-vals?)
                                       (remove clashing-vals?)
                                       (remove affix-vals?)
                                       (remove duplicate-vals?))
                                     rows)))

        ;; Generate the prompt data
        prompt-ms          (clean-rows prompt-template prompt-rows)
        m->prompt-sentence #(apply-template prompt-template %)
        prompt-sentences   (map m->prompt-sentence prompt-ms)]

    ;; Before we sample anything, we init the random seed to our chosen value.
    ;; The point is to have some sense of reproducibility.
    (reset! random-seed 42)

    (concat
      ;; add tests for test lemmas using the test templates
      (mapcat (fn [[template bool]]
                (for [m (sample 20 (clean-rows template test-rows))]
                  [(str/join "; " (sample 2 prompt-sentences))
                   (apply-template template m)
                   bool]))
              test-templates)

      ;; add additional true tests for the certified true prompt lemmas
      (for [m (sample 20 prompt-ms)]
        (let [other-ms (disj prompt-ms m)]
          [(str/join "; " (sample 2 (map m->prompt-sentence other-ms)))
           (apply-template prompt-template m)
           true])))))

(defn rows
  "Query the DanNet graph for the patterns described in `k` or return relevant
  rows for every pattern if none specified."
  [& [k]]
  (if k
    (if-let [pattern (get inference-patterns k)]
      (->> (apply gen-rows pattern)
           (map #(conj % k)))
      (throw (ex-info "pattern does not exist" {:k k})))
    (apply concat (for [k (sort (keys inference-patterns))]
                    (rows k)))))

(defn write-tsv!
  [title rows]
  (with-open [writer (io/writer (str title ".tsv"))]
    (csv/write-csv writer rows :separator \tab)))

(defn write-spreadsheet!
  [title rows]
  (let [data       (concat [["prompt" "test" "result" "comment"]] rows)
        wb         (xl/create-workbook "Tests" data)
        sheet      (xl/select-sheet "Tests" wb)
        header-row (first (xl/row-seq sheet))]
    (xl/set-row-style! header-row (xl/create-cell-style! wb {:font {:bold true}}))
    (xl/save-workbook! (str title ".xlsx") wb)))

(comment
  (@lemma->frequency "krummerik")

  (article-for "papegøje")                                  ; en
  (article-for "retskrav")                                  ; et
  (article-for 123)                                         ; nil

  (cartesian-ms '{?sl ("høreorgan" "øre")
                  ?ol ("det indre øre" "labyrint")})

  (count (run (:graph @db) non-sports-activities-query))
  (run (:graph @db) used-for-warm-query)
  (run (:graph @db) used-for-cover-query)
  (run (:graph @db) used-for-decorate-query)
  (run (:graph @db) used-for-dress-query)
  (run (:graph @db) used-for-protect-1-query)
  (run (:graph @db) used-for-protect-2-query)
  (run (:graph @db) period-of-time-query)
  (run (:graph @db) feeling-thought-query)
  (count (run (:graph @db) feeling-query))
  (count (run (:graph @db) thought-query))

  ;; Test query result
  (->> (run (:graph @db) orthogonal-hypernym-plant-object-query)
       (map (fn [{:syms [?pl ?tl]}]
              [(str ?pl) (str ?tl)])))

  (time (count (rows)))
  (count (rows))
  (count (rows "orthogonal plant hypernyms"))
  (count (rows "sports"))
  (count (rows "disciplines"))
  (count (rows "part-whole"))
  (count (rows "warm vs. decorate"))

  ;; Write a partial dataset to disk
  (write-tsv! "time" (rows "orthogonal animal hypernyms"))
  (write-spreadsheet! "ortho" (rows "feelings"))

  ;; Write the entire dataset to disk
  (let [data (rows)]
    (write-tsv! "inference" data)
    (write-spreadsheet! "inference" data))
  #_.)
