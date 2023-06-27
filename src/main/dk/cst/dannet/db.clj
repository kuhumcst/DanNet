(ns dk.cst.dannet.db
  "Represent DanNet as an in-memory graph or within a persisted database (TDB)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [arachne.aristotle :as aristotle]
            [ont-app.vocabulary.core :as voc]
            [clj-file-zip.core :as zip]
            [dk.cst.dannet.hash :as hash]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.db.bootstrap :refer [en]]
            [dk.cst.dannet.hash :as h]
            [dk.cst.dannet.query :as q]
            [dk.cst.dannet.query.operation :as op]
            [dk.cst.dannet.transaction :as txn])
  (:import [ont_app.vocabulary.lstr LangStr]
           [org.apache.jena.tdb TDBFactory]
           [org.apache.jena.tdb2 TDB2Factory]
           [org.apache.jena.rdf.model ModelFactory Model ResourceFactory]
           [org.apache.jena.query Dataset DatasetFactory]
           [org.apache.jena.reasoner.rulesys GenericRuleReasoner Rule]
           [java.time LocalDateTime]
           [java.time.format DateTimeFormatter]
           [java.io File]))

;; https://jena.apache.org/documentation/io/index.html
(defn ->schema-model
  "Create a Model containing the schemas found at the given `uris`."
  [uris]
  (reduce (fn [^Model model schema-uri]
            ;; Using an InputStream will ensure that the URI can be an internal
            ;; resource of a JAR file. If the input is instead a string, Jena
            ;; attempts to load it as a File which JAR file resources cannot be.
            (let [s  (str schema-uri)
                  in (io/input-stream schema-uri)]
              (cond
                (str/ends-with? s ".ttl")
                (.read model in nil "TURTLE")

                (str/ends-with? s ".n3")
                (.read model in nil "N3")

                :else
                (.read model in nil "RDF/XML"))))
          (ModelFactory/createDefaultModel)
          uris))

(def reasoner
  (let [rules (Rule/parseRules (slurp (io/resource "etc/dannet.rules")))]
    (doto (GenericRuleReasoner. rules)
      (.setOWLTranslation true)
      (.setMode GenericRuleReasoner/HYBRID)
      (.setTransitiveClosureCaching true))))

(defn get-model
  "Idempotently get the model in the `dataset` for the given `model-uri`."
  [^Dataset dataset ^String model-uri]
  (txn/transact dataset
    (if (.containsNamedModel dataset model-uri)
      (.getNamedModel dataset model-uri)
      (-> (.addNamedModel dataset model-uri (ModelFactory/createDefaultModel))
          (.getNamedModel model-uri)))))

(defn get-graph
  "Idempotently get the graph in the `dataset` for the given `model-uri`."
  [^Dataset dataset ^String model-uri]
  (.getGraph (get-model dataset model-uri)))

(defn remove!
  "Remove a `triple` from the Apache Jena `model`.

  NOTE: as with Aristotle queries, _ works as a wildcard value."
  [^Model model [s p o :as triple]]
  {:pre [(some? s) (some? p) (some? o)]}
  (.removeAll
    model
    (when (not= s '_)
      (cond
        (keyword? s)
        (ResourceFactory/createResource (voc/uri-for s))

        (prefix/rdf-resource? s)
        (ResourceFactory/createResource (subs s 1 (dec (count s))))))
    (when (not= p '_)
      (cond
        (keyword? p)
        (ResourceFactory/createProperty (voc/uri-for p))

        (prefix/rdf-resource? p)
        (ResourceFactory/createProperty (subs p 1 (dec (count p))))))
    (when (not= o '_)
      (cond
        (keyword? o)
        (ResourceFactory/createResource (voc/uri-for o))

        (prefix/rdf-resource? o)
        (ResourceFactory/createResource (subs o 1 (dec (count o))))

        (instance? LangStr o)
        (ResourceFactory/createLangLiteral (str o) (.lang o))

        :else
        (ResourceFactory/createTypedLiteral o)))))

(defn safe-add!
  "Add `data` to `g` in a failsafe way."
  [g data]
  (try
    (aristotle/add g data)
    (catch Exception e
      (prn data (.getMessage e)))
    (finally
      g)))

(h/defn import-files
  "Import `files` into a the model defined by `model-uri` in `dataset` in a safe
  way, optionally applying a `changefn` to the temporary model before import."
  ([dataset model-uri files]
   (import-files dataset model-uri files nil))
  ([dataset model-uri files changefn]
   (println "Importing files:" (str/join ", " (map #(if (instance? File %)
                                                      (.getName %)
                                                      %)
                                                   files)))
   (let [temp-model (ModelFactory/createDefaultModel)
         temp-graph (.getGraph temp-model)]
     (println "... creating temporary in-memory graph")
     (doseq [file files]
       (aristotle/read temp-graph file))

     (when changefn
       (println "... applying changes to temporary graph ")
       (changefn temp-model))

     (println "... persisting temporary graph:" model-uri)
     (txn/transact-exec dataset
       (aristotle/add (get-graph dataset model-uri) temp-graph)))))

;; TODO: move to separate ns
(h/defn add-open-english-wordnet-labels!
  "Generate appropriate labels for the (otherwise unlabeled) OEWN in `dataset`."
  [dataset]
  (println "Adding labels to the Open English Wordnet...")
  (let [oewn-graph   (get-graph dataset prefix/oewn-uri)
        label-graph  (get-graph dataset prefix/oewn-extension-uri)
        ms           (q/run oewn-graph op/oewn-label-targets)
        collect-rep  (fn [m {:syms [?synset ?rep]}]
                       (update m ?synset conj (str ?rep)))
        synset-label (fn [labels]
                       (as-> labels $
                             (set $)
                             (sort $)
                             (str/join "; " $)
                             (en "{" $ "}")))]
    (txn/transact-exec dataset
      (println "... adding synset labels to" prefix/oewn-extension-uri)
      (->> (reduce collect-rep {} ms)
           (map (fn [[synset labels]]
                  [synset :rdfs/label (synset-label labels)]))
           (aristotle/add label-graph)))
    (txn/transact-exec dataset
      (println "... adding sense and word labels to" prefix/oewn-extension-uri)
      (->> ms
           (mapcat (fn [{:syms [?sense ?word ?rep]}]
                     [[?word :rdfs/label (en "\"" ?rep "\"")]
                      [?sense :rdfs/label ?rep]]))
           (aristotle/add label-graph))))
  (println "Labels added to the Open English WordNet!"))

;; TODO: move to separate ns
(h/defn add-open-english-wordnet!
  "Add the Open English WordNet to a Jena `dataset`."
  [dataset]
  (println "Importing Open English Wordnet...")
  (let [oewn-file     "bootstrap/other/english/english-wordnet-2022.ttl"
        oewn-changefn (fn [temp-model]
                        (let [princeton "<http://wordnet-rdf.princeton.edu/>"]
                          (println "... removing problematic entries")
                          (remove! temp-model [princeton :lime/entry '_])))
        ili-file      "bootstrap/other/english/ili.ttl"]
    (println "... creating temporary in-memory graph")
    (import-files dataset prefix/oewn-uri [oewn-file] oewn-changefn)
    (import-files dataset prefix/ili-uri [ili-file]))
  (println "Open English Wordnet imported!")
  #_(add-open-english-wordnet-labels! dataset))

(defn ->dataset
  "Get a Dataset object of the given `db-type`. TDB also requires a `db-path`."
  [db-type & [db-path]]
  (case db-type
    :tdb1 (TDBFactory/createDataset ^String db-path)
    :tdb2 (TDB2Factory/connectDataset ^String db-path)
    :in-mem (DatasetFactory/create)
    :in-mem-txn (DatasetFactory/createTxnMem)))

(defn- log-entry
  [db-name db-type input-dir]
  (let [now       (LocalDateTime/now)
        formatter (DateTimeFormatter/ofPattern "yyyy/MM/dd HH:mm:ss")
        filenames (sort (->> (file-seq input-dir)
                             (remove #{input-dir})
                             (map #(.getName ^File %))))]
    (str
      "Location: " db-name "\n"
      "Type: " db-type "\n"
      "Created: " (.format now formatter) "\n"
      "Input data: " (str/join ", " filenames))))

(defn ->dannet
  "Create a Jena Dataset from DanNet 2.2 imports based on the options:

    :input-dir         - Previous DanNet version TTL export as a File directory.
    :db-type           - :tdb1, :tdb2, :in-mem, and :in-mem-txn are supported
    :db-path           - Where to persist the TDB1/TDB2 data.
    :schema-uris       - A collection of URIs containing schemas.

   TDB 1 does not require transactions until after the first transaction has
   taken place, while TDB 2 *always* requires transactions when reading from or
   writing to the database."
  [& {:keys [input-dir db-path db-type schema-uris]
      :or   {db-type :in-mem} :as opts}]
  (let [log-path       (str db-path "/log.txt")
        files          (remove #{input-dir} (file-seq input-dir))
        fn-hashes      [(:hash (meta #'add-open-english-wordnet!))
                        (:hash (meta #'add-open-english-wordnet-labels!))
                        (hash prefix/schemas)]
        ;; Undo potentially negative number by bit-shifting.
        files-hash     (hash/pos-hash files)
        bootstrap-hash (hash/pos-hash fn-hashes)
        db-name        (str files-hash "-" bootstrap-hash)
        full-db-path   (str db-path "/" db-name)
        zip-file?      (comp #(str/ends-with? % ".zip") #(.getName %))
        ttl-file?      (comp #(str/ends-with? % ".ttl") #(.getName %))
        db-exists?     (.exists (io/file full-db-path))
        new-entry      (log-entry db-name db-type input-dir)
        dataset        (->dataset db-type full-db-path)]
    (println "Database name:" db-name)

    (if input-dir
      (if db-exists?
        (println "Skipping build -- database already exists:" full-db-path)
        (time
          (do
            (println "Creating new database from: " input-dir)
            (doseq [zip-file (filter zip-file? (file-seq input-dir))]
              (zip/unzip zip-file zip-file)
              (let [ttl-file  (first (filter ttl-file? (file-seq input-dir)))
                    model-uri (prefix/zip-file->uri (.getName zip-file))]
                (import-files dataset model-uri [ttl-file])
                (zip/delete-file ttl-file)))

            (add-open-english-wordnet! dataset)
            (println new-entry)
            (spit log-path (str new-entry "\n----\n") :append true))))
      (println "WARNING: no input dir provided, dataset will be empty!"))

    ;; If schemas are provided, the returned model & graph contain inferences.
    (if schema-uris
      (let [schema    (->schema-model schema-uris)
            model     (.getUnionModel dataset)
            inf-model (ModelFactory/createInfModel reasoner schema model)
            inf-graph (.getGraph inf-model)]
        (println "Schema URIs found -- constructing inference model.")
        {:dataset dataset
         :model   inf-model
         :graph   inf-graph})
      (let [model (.getUnionModel dataset)
            graph (.getGraph model)]
        {:dataset dataset
         :model   model
         :graph   graph}))))
