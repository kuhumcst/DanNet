(ns dk.cst.dannet.db.export.csv
  "CSV export functionality."
  (:require [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [clojure.data.json :as json]
            [clj-file-zip.core :as zip]
            [ont-app.vocabulary.core :as voc]
            [dk.cst.dannet.db :as db]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.query :as q]
            [dk.cst.dannet.query.operation :as op]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.transaction :as txn])
  (:import [org.apache.jena.rdf.model Model Statement]))

(defn- expand-kw
  [x]
  (cond
    (and (keyword? x)
         (namespace x))
    (prefix/kw->uri x)

    (symbol? x)
    (str "@" x)

    :else x))

(defn expand-kws
  "Expands any recognised namespaced keywords found in the input `m` to URIs."
  [m]
  (walk/postwalk expand-kw m))

(def synsets-metadata
  {'context "http://www.w3.org/ns/csvw"
   :tables  [{:url "synsets.csv"
              :tableSchema
              {:aboutUrl   (str prefix/dn-uri "{synset}")
               :primaryKey "synset"
               :columns    [{:name   "synset"
                             :titles "Synset"}

                            {:name        "definition"
                             :titles      "Definition"
                             :propertyUrl :skos/definition}

                            {:name        "ontotype"
                             :titles      "Ontological Type(s)"
                             :separator   "; "
                             :propertyUrl :dnc/ontologicalType}

                            {:virtual     true
                             :propertyUrl "rdf:type"
                             :valueUrl    :ontolex/LexicalConcept}]}}]
   :dialect {:header false}})

(def words-metadata
  {'context "http://www.w3.org/ns/csvw"
   :tables  [{:url "words.csv"
              :tableSchema
              {:aboutUrl   (str prefix/dn-uri "{word}")
               :primaryKey "word"
               :columns    [{:name   "word"
                             :titles "Word"}

                            {:name        "form"
                             :titles      "Written representation"
                             :propertyUrl :ontolex/writtenRep}

                            {:name        "pos"
                             :titles      "Part-of-speech"
                             :propertyUrl :lexinfo/partOfSpeech}

                            {:virtual     true
                             :propertyUrl "rdf:type"
                             :valueUrl    :ontolex/LexicalEntry}]}}]
   :dialect {:header false}})

(def senses-metadata
  {'context "http://www.w3.org/ns/csvw"
   :tables  [{:url "senses.csv"
              :tableSchema
              {:aboutUrl   (str prefix/dn-uri "{sense}")
               :primaryKey "sense"
               :columns    [{:name   "sense"
                             :titles "Sense"}

                            {:name        "synset"
                             :titles      "Synset"
                             :propertyUrl :ontolex/isLexicalizedSenseOf}

                            {:name        "word"
                             :titles      "Word"
                             :propertyUrl :ontolex/isSenseOf}

                            {:name        "note"
                             :titles      "Note"
                             :propertyUrl :lexinfo/usageNote}

                            {:virtual     true
                             :propertyUrl "rdf:type"
                             :valueUrl    :ontolex/LexicalSense}]}}]
   :dialect {:header false}})

(def inheritance-metadata
  {'context "http://www.w3.org/ns/csvw"
   :tables  [{:url "senses.csv"
              :tableSchema
              {:columns [{:name   "to"
                          :titles "To synset"}

                         {:name        "relation"
                          :propertyUrl :dns/inheritedRelation
                          :titles      "Inherited relation"}

                         {:name        "from"
                          :propertyUrl :dns/inheritedFrom
                          :titles      "From synset"}

                         {:virtual     true
                          :propertyUrl "rdf:type"
                          :valueUrl    :dns/Inheritance}]}}]
   :dialect {:header false}})

(def examples-metadata
  {'context "http://www.w3.org/ns/csvw"
   :tables  [{:url "examples.csv"
              :tableSchema
              {:columns [{:name   "sense"
                          :titles "Sense"}

                         {:name        "example"
                          :propertyUrl :lexinfo/senseExample
                          :titles      "Example"}]}}]
   :dialect {:header false}})

(def relations-metadata
  {'context "http://www.w3.org/ns/csvw"
   :tables  [{:url "examples.csv"
              :tableSchema
              {:columns [{:name   "from"
                          :titles "From synset"}

                         {:name   "relation"
                          :titles "Relation"}

                         {:name   "to"
                          :titles "To synset"}]}}]
   :dialect {:header false}})

(defn metadata->json
  "Convert a `metadata` map into the JSON-LD format used for CSVW metadata."
  [metadata]
  (->> (expand-kws metadata)
       (json/pprint)
       (with-out-str)))

(defn export-metadata!
  "Export a `metadata` map as the given `f`."
  [f metadata]
  (println "Exporting" f)
  (spit f (metadata->json metadata)))


(defn- csv-table-cell
  ([separator x]
   (->> (shared/setify x)
        (map (fn [x]
               (cond
                 (keyword? x) (name x)
                 :else (str x))))
        (sort)
        (str/join separator)))
  ([x]
   (csv-table-cell "; " x)))

(defn- csv-row
  "Convert `row` values into CSVW-compatible strings."
  [row]
  (mapv csv-table-cell row))

(defn synset-rel-table
  "A performant way to fetch synset->synset relations for `synset` in `model`.

  The function basically exists because I wasn't able to perform a similar query
  in a performant way, e.g. doing this for all synsets would take ~45 minutes."
  [^Model model synset]
  (txn/transact model
    (->> (voc/uri-for synset)
         (.getResource model)
         (.listProperties)
         (iterator-seq)
         (keep (fn [^Statement statement]
                 (let [prefix (str prefix/dn-uri "synset-")
                       obj    (str (.getObject statement))]
                   (when (str/starts-with? obj prefix)
                     [synset
                      (str (.getPredicate statement))
                      (voc/keyword-for obj)]))))
         (doall))))

(defn export-csv-rows!
  "Write CSV `rows` to file `f`."
  [f rows]
  (println "Exporting" f)
  (io/make-parents f)
  (with-open [writer (io/writer f)]
    (csv/write-csv writer rows)))

(defn- non-zip-files
  [dir-path]
  (let [dir-file (io/file dir-path)]
    (->> (file-seq dir-file)
         (remove (partial = dir-file))
         (map #(.getPath %))
         (remove #(str/ends-with? % ".zip")))))

(defn export-csv!
  "Write CSV `rows` to file `f`."
  ([{:keys [dataset] :as dannet} dir]
   (println "Beginning CSV export of DanNet into" dir)
   (println "----")
   (let [g          (db/get-graph dataset prefix/dn-uri)
         synsets-ks '[?synset ?definition ?ontotype]
         words-ks   '[?word ?written-rep ?pos ?rdf-type]
         senses-ks  '[?sense ?synset ?word ?note]
         zip-path   (str dir (prefix/export-file "csv" 'dn))]
     (println "Fetching table rows:" synsets-ks)
     (export-csv-rows!
       (str dir "synsets.csv")
       (map csv-row (q/table-query g synsets-ks op/csv-synsets)))
     (export-metadata!
       (str dir "synsets-metadata.json")
       synsets-metadata)

     (println "Fetching table rows:" words-ks)
     (export-csv-rows!
       (str dir "words.csv")
       (map csv-row (q/table-query g words-ks op/csv-words)))
     (export-metadata!
       (str dir "words-metadata.json")
       words-metadata)

     (println "Fetching table rows:" senses-ks)
     (export-csv-rows!
       (str dir "senses.csv")
       (map csv-row (q/table-query g senses-ks op/csv-senses)))
     (export-metadata!
       (str dir "senses-metadata.json")
       senses-metadata)

     (println "Fetching inheritance data...")
     (export-csv-rows!
       (str dir "inheritance.csv")
       (map (fn [{:syms [?synset ?rel ?from]}]
              [(name ?synset)
               (voc/uri-for ?rel)
               (name ?from)])
            (q/run g op/csv-inheritance)))
     (export-metadata!
       (str dir "inheritance-metadata.json")
       inheritance-metadata)

     (println "Fetching example data...")
     (export-csv-rows!
       (str dir "examples.csv")
       (map csv-row (q/run g '[?sense ?example] op/csv-examples)))
     (export-metadata!
       (str dir "examples-metadata.json")
       examples-metadata)

     (println "Fetching synset relations...")
     (let [model           (db/get-model dataset prefix/dn-uri)
           synset->triples (partial synset-rel-table model)
           synsets         (->> (q/run g op/synsets)
                                (map '?synset)
                                (set))]
       (export-csv-rows!
         (str dir "relations.csv")
         (->> (mapcat synset->triples synsets)
              (map csv-row))))
     (export-metadata!
       (str dir "relations-metadata.json")
       relations-metadata)

     (println "Zipping CSV files and associated metadata into" zip-path "...")
     (zip/zip-files (non-zip-files dir) zip-path))

   (println "----")
   (println "CSV Export of DanNet complete!"))
  ([dannet]
   (export-csv! dannet "export/csv/")))

(comment
  (def dataset (:dataset @dk.cst.dannet.web.resources/db))

  ;; Export DanNet as CSV
  (export-csv! dannet)
  (export-csv! @dk.cst.dannet.web.resources/db)

  (expand-kws x)
  (spit "synsets-metadata.json" (metadata->json synsets-metadata))
  (export-metadata! "synsets-metadata.json" synsets-metadata)
  #_.)
