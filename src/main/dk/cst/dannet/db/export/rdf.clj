(ns dk.cst.dannet.db.export.rdf
  "Serialization of the graph data in various ways."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-file-zip.core :as zip]
            [arachne.aristotle.registry :as registry]
            [clojure.walk :as walk]
            [donatello.ttl :as ttl]
            [quoll.rdf :refer [print-escape]]
            [ont-app.vocabulary.lstr :as lstr]
            [dk.cst.dannet.db :as db]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.db.transaction :as txn])
  (:import [clojure.lang Symbol]
           [ont_app.vocabulary.lstr LangStr]
           [org.apache.jena.datatypes.xsd XSDDateTime]
           [org.apache.jena.riot RDFDataMgr RDFFormat]
           [org.apache.jena.rdf.model Model]
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
      (zip/zip-files [ttl-file] path)
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
         #_#_merged-ttl   (in-dir (prefix/export-file "rdf" 'dn "merged"))
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
     #_(let [union-model (.getUnionModel dataset)]
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

(def donatello-prefixes-base
  (into {} (map (fn [[k v]]
                  [(keyword k) (:uri v)])
                prefix/schemas)))

;; Donatello compatibility with Aristotle blank nodes and ont-app LangStrings.
(extend-protocol ttl/Serializable
  Symbol
  (serialize [x] (str "_:" (subs (str x) 1)))

  LangStr
  (serialize[x] (str \" (print-escape (str x)) "\"@" (lstr/lang x)))

  XSDDateTime
  (serialize [x] (str "\"" x "\"^^xsd:dateTime")))

(defn flatten-nested-sets
  "Flatten nested sets in `entity` values. Converts #{#{a b}} to #{a b}."
  [entity]
  (walk/postwalk
    (fn [x]
      (if (and (set? x)
               (= 1 (count x))
               (set? (first x)))
        (first x)
        x))
    entity))

(defn collect-blank-nodes
  "Collect symbols (blank node refs) with non-nil metadata from `entity`."
  [entity]
  (let [blanks (volatile! [])]
    (walk/postwalk
      (fn [x]
        (when (and (symbol? x) (meta x))
          (vswap! blanks conj [x (meta x)]))
        x)
      entity)
    @blanks))

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
  "Get the equivalent TTL output for `entity`, with blank nodes realized as
  separate triple blocks below the main entity."
  [entity & [base]]
  (let [entity*     (flatten-nested-sets entity)
        blank-nodes (collect-blank-nodes entity*)
        all-data    (cons entity* (map second blank-nodes))
        prefixes    (reduce (fn [acc e]
                              (merge acc (donatello-prefixes e)))
                            {}
                            all-data)]
    (with-open [sw (StringWriter.)]
      (when base
        (ttl/write-base! sw base))
      (ttl/write-prefixes! sw prefixes)
      (ttl/write-triples! sw (:subject (meta entity)) entity*)
      (doseq [[sym props] blank-nodes]
        (ttl/write-triples! sw sym props))
      (str sw))))

(comment
  (def dataset (:dataset @dk.cst.dannet.web.resources/db))

  ;; Export individual models
  (export-rdf-model! "export/rdf/dannet.zip" (db/get-model dataset prefix/dn-uri)
                     :prefixes (export-prefixes 'dn))
  (export-rdf-model! "export/rdf/dds.zip" (db/get-model dataset prefix/dds-uri)
                     :prefixes (export-prefixes 'dds))
  (export-rdf-model! "export/rdf/cor.zip" (db/get-model dataset prefix/cor-uri)
                     :prefixes (export-prefixes 'cor))
  (export-rdf-model! "export/rdf/oewn-extension.zip"
                     (db/get-model dataset prefix/oewn-extension-uri)
                     :prefixes (get prefix/oewn-extension :export))

  ;; Export the entire dataset as RDF
  (export-rdf! dannet)
  (export-rdf! @dk.cst.dannet.web.resources/db)

  (export-rdf! @dk.cst.dannet.web.resources/db "export/rdf/" :complete true)
  #_.)
