(ns dk.cst.dannet.db.export
  "Serialization of the graph data in various ways."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.data.csv :as csv]
            [clj-file-zip.core :refer [zip-files]]
            [arachne.aristotle.registry :as registry]
            [clojure.walk :as walk]
            [donatello.ttl :as ttl]
            [ont-app.vocabulary.core :as voc]
            [ont-app.vocabulary.lstr :as lstr]
            [dk.cst.dannet.db :as db]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.db.csv :as db.csv]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.query :as q]
            [dk.cst.dannet.query.operation :as op]
            [dk.cst.dannet.transaction :as txn])
  (:import [clojure.lang Symbol]
           [ont_app.vocabulary.lstr LangStr]
           [org.apache.jena.riot RDFDataMgr RDFFormat]
           [org.apache.jena.rdf.model Model Statement]
           [org.apache.jena.query Dataset]
           [java.io StringWriter]))

(defn add-registry-prefixes!
  "Adds prefixes in use from the Aristotle registry to the `model`."
  [^Model model & {:keys [prefixes]}]
  (doseq [[prefix m] (cond->> (:prefixes registry/*registry*)
                       prefixes (filter (comp prefixes symbol first)))]
    (.setNsPrefix model prefix (::registry/= m))))

(defn- ttl-path
  [path]
  (let [parts      (str/split path #"/")
        filename   (first (str/split (last parts) #"\."))
        parent-dir (str/join "/" (butlast parts))]
    (str parent-dir "/" filename ".ttl")))

(defn- non-zip-files
  [dir-path]
  (let [dir-file (io/file dir-path)]
    (->> (file-seq dir-file)
         (remove (partial = dir-file))
         (map #(.getPath %))
         (remove #(str/ends-with? % ".zip")))))

;; TODO: alternative RDF formats will not match filepath given by ttl-path
(defn export-rdf-model!
  "Export the `model` to the given zip file `path`. Content defaults to Turtle.

  The current prefixes in the Aristotle registry are used for the output,
  although a desired subset of :prefixes may also be specified.

  See: https://jena.apache.org/documentation/io/rdf-output.html"
  [path ^Model model & {:keys [fmt prefixes]
                        :or   {fmt RDFFormat/TURTLE_PRETTY}}]
  (let [ttl-file (ttl-path path)]
    (txn/transact-exec model
      ;; Clear potentially imported prefixes, e.g. from TTL files
      (.clearNsPrefixMap model)
      (println "Exporting" path (str "(" (.size model) ")")
               "with prefixes:" (or prefixes "ALL"))
      ;; Temporarily add prefixes for export
      (add-registry-prefixes! model :prefixes prefixes)
      (io/make-parents path)
      (RDFDataMgr/write (io/output-stream ttl-file) model ^RDFFormat fmt)
      (zip-files [ttl-file] path)
      ;; Clear temporarily added prefixes
      (.clearNsPrefixMap model)))
  nil)

(defn- export-prefixes
  [prefix]
  (get-in prefix/schemas [prefix :export]))

(defn export-rdf!
  "Export the models of the RDF `dataset` into `dir`.

  By default, the complete model is not exported. In the case of a typical
  inference-heavy DanNet instance, this would simply be too slow. To include the
  complete model as an export target, set :complete to true."
  ([{:keys [model dataset] :as dannet} dir & {:keys [complete]
                                              :or   {complete false}}]
   (let [in-dir       (partial str dir)
         merged-ttl   (in-dir (prefix/export-file "rdf" 'dn "merged"))
         complete-ttl (in-dir (prefix/export-file "rdf" 'dn "complete"))
         model-uris   (txn/transact dataset
                        (->> (iterator-seq (.listNames ^Dataset dataset))
                             (remove prefix/not-for-export)
                             (doall)))]
     (println "Beginning RDF export of DanNet into" dir)
     (println "----")

     ;; The individual models contained in the dataset.
     (doseq [model-uri model-uris
             :let [^Model model (db/get-model dataset model-uri)
                   prefix       (prefix/uri->prefix model-uri)
                   filename     (in-dir (prefix/export-file "rdf" prefix))]]
       (export-rdf-model! filename model :prefixes (export-prefixes prefix)))

     ;; The OEWN extension data is exported separately from the other models,
     ;; since it isn't connected to a separate prefix (= graph).
     (export-rdf-model!
       (in-dir (get-in prefix/oewn-extension [:download "rdf" :default]))
       (db/get-model dataset prefix/oewn-extension-uri)
       :prefixes (get prefix/oewn-extension :export))

     ;; The union of the input datasets.
     (let [union-model (.getUnionModel dataset)]
       (export-rdf-model! merged-ttl union-model))

     ;; The union of the input datasets and schemas + inferred triples.
     ;; This constitutes all data available in the DanNet web presence.
     (if complete
       (export-rdf-model! complete-ttl model)
       (println "(skipping export of complete.ttl)"))

     (println "----")
     (println "RDF Export of DanNet complete!")))
  ([^Dataset dataset]
   (export-rdf! dataset "export/rdf/")))

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
     (db.csv/export-metadata!
       (str dir "synsets-metadata.json")
       db.csv/synsets-metadata)

     (println "Fetching table rows:" words-ks)
     (export-csv-rows!
       (str dir "words.csv")
       (map csv-row (q/table-query g words-ks op/csv-words)))
     (db.csv/export-metadata!
       (str dir "words-metadata.json")
       db.csv/words-metadata)

     (println "Fetching table rows:" senses-ks)
     (export-csv-rows!
       (str dir "senses.csv")
       (map csv-row (q/table-query g senses-ks op/csv-senses)))
     (db.csv/export-metadata!
       (str dir "senses-metadata.json")
       db.csv/senses-metadata)

     (println "Fetching inheritance data...")
     (export-csv-rows!
       (str dir "inheritance.csv")
       (map (fn [{:syms [?synset ?rel ?from]}]
              [(name ?synset)
               (voc/uri-for ?rel)
               (name ?from)])
            (q/run g op/csv-inheritance)))
     (db.csv/export-metadata!
       (str dir "inheritance-metadata.json")
       db.csv/inheritance-metadata)

     (println "Fetching example data...")
     (export-csv-rows!
       (str dir "examples.csv")
       (map csv-row (q/run g '[?sense ?example] op/csv-examples)))
     (db.csv/export-metadata!
       (str dir "examples-metadata.json")
       db.csv/examples-metadata)

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
     (db.csv/export-metadata!
       (str dir "relations-metadata.json")
       db.csv/relations-metadata)

     (println "Zipping CSV files and associated metadata into" zip-path "...")
     (zip-files (non-zip-files dir) zip-path))

   (println "----")
   (println "CSV Export of DanNet complete!"))
  ([dannet]
   (export-csv! dannet "export/csv/")))

(def donatello-prefixes-base
  (into {} (map (fn [[k v]]
                  [(keyword k) (:uri v)])
                prefix/schemas)))

;; Donatello compatibility with Aristotle blank nodes and ont-app LangStrings.
(defmethod ttl/serialize Symbol [x] (str "_:" (subs (str x) 1)))
(defmethod ttl/serialize LangStr [x] (str \" (ttl/escape (str x)) "\"@" (lstr/lang x)))

(defn donatello-prefixes
  "Prepare prefixes in `entity` for Donatello TTL output."
  [entity]
  (let [prefixes (atom #{})]
    (walk/postwalk
      #(when (keyword? %)
         (swap! prefixes conj (namespace %)))
      entity)
    (->> (remove nil? @prefixes)
         (map keyword)
         (select-keys donatello-prefixes-base))))

(defn ttl-entity
  "Get the equivalent TTL output for `entity`."
  [entity & [base]]
  (with-open [sw (StringWriter.)]
    (when base
      (ttl/write-base! sw base))
    (ttl/write-prefixes! sw (donatello-prefixes entity))
    (ttl/write-triples! sw (:subject (meta entity)) entity)
    (str sw)))

(comment
  (def dataset (:dataset @dk.cst.dannet.web.resources/db))

  ;; Export individual models
  (export-rdf-model! "export/rdf/dannet.zip" (get-model dataset prefix/dn-uri)
                     :prefixes (export-prefixes 'dn))
  (export-rdf-model! "export/rdf/dds.zip" (get-model dataset prefix/dds-uri)
                     :prefixes (export-prefixes 'dds))
  (export-rdf-model! "export/rdf/cor.zip" (get-model dataset prefix/cor-uri)
                     :prefixes (export-prefixes 'cor))
  (export-rdf-model! "export/rdf/oewn-extension.zip"
                     (get-model dataset prefix/oewn-extension-uri)
                     :prefixes (get prefix/oewn-extension :export))

  ;; Export the entire dataset as RDF
  (export-rdf! dannet)
  (export-rdf! @dk.cst.dannet.web.resources/db)

  (export-rdf! @dk.cst.dannet.web.resources/db "export/rdf/" :complete true)

  ;; Test CSV table data
  (let [g (get-graph dataset prefix/dn-uri)]
    (->> (q/table-query g '[?synset ?definition ?ontotype ?sense] op/csv-synsets)
         (map csv-row)
         (take 10)))

  ;; Export DanNet as CSV
  (export-csv! dannet)
  (export-csv! @dk.cst.dannet.web.resources/db)
  #_.)
