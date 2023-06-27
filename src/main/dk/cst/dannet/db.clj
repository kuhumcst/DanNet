(ns dk.cst.dannet.db
  "Represent DanNet as an in-memory graph or within a persisted database (TDB)."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.data.csv :as csv]
            [clj-file-zip.core :refer [zip-files]]
            [arachne.aristotle :as aristotle]
            [arachne.aristotle.registry :as registry]
            [clojure.walk :as walk]
            [donatello.ttl :as ttl]
            [ont-app.vocabulary.core :as voc]
            [flatland.ordered.map :as fop]
            [ont-app.vocabulary.lstr :as lstr]
            [ont-app.vocabulary.lstr :refer [->LangStr]]
            [clj-file-zip.core :as zip]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.db.csv :as db.csv]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.db.bootstrap :refer [en]]
            [dk.cst.dannet.hash :as h]
            [dk.cst.dannet.query :as q]
            [dk.cst.dannet.query.operation :as op]
            [dk.cst.dannet.transaction :as txn])
  (:import [clojure.lang Symbol]
           [ont_app.vocabulary.lstr LangStr]
           [org.apache.jena.riot RDFDataMgr RDFFormat]
           [org.apache.jena.tdb TDBFactory]
           [org.apache.jena.tdb2 TDB2Factory]
           [org.apache.jena.rdf.model ModelFactory Model ResourceFactory Statement]
           [org.apache.jena.query Dataset DatasetFactory]
           [org.apache.jena.reasoner.rulesys GenericRuleReasoner Rule]
           [java.time LocalDateTime]
           [java.time.format DateTimeFormatter]
           [java.io File StringWriter]))

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

(defn- pos-hash
  "Undo potentially negative number by bit-shifting when hashing `x`."
  [x]
  (unsigned-bit-shift-right (hash x) 1))

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
        files-hash     (pos-hash files)
        bootstrap-hash (pos-hash fn-hashes)
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
             :let [^Model model (get-model dataset model-uri)
                   prefix       (prefix/uri->prefix model-uri)
                   filename     (in-dir (prefix/export-file "rdf" prefix))]]
       (export-rdf-model! filename model :prefixes (export-prefixes prefix)))

     ;; The OEWN extension data is exported separately from the other models,
     ;; since it isn't connected to a separate prefix (= graph).
     (export-rdf-model!
       (in-dir (get-in prefix/oewn-extension [:download "rdf" :default]))
       (get-model dataset prefix/oewn-extension-uri)
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
   (let [g          (get-graph dataset prefix/dn-uri)
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
     (let [model           (get-model dataset prefix/dn-uri)
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

(def sym->kw
  {'?synset     :rdf/value
   '?definition :skos/definition
   '?ontotype   :dns/ontologicalType})

;; TODO: does this memoization even accomplish anything?
(def label-lookup
  (memoize
    (fn [g]
      (let [search-labels   (q/run g op/synset-search-labels)
            ontotype-labels (q/run g op/ontotype-labels)]
        (merge (set/rename-keys (apply merge-with q/set-merge search-labels)
                                sym->kw)
               (->> (for [{:syms [?ontotype ?label]} ontotype-labels]
                      {?ontotype ?label})
                    (apply merge-with q/set-merge)))))))

(def search-keyfn
  (let [m->label (fn [{:keys [rdf/value] :as m}]
                   (str (get (:k->label (meta m)) value)))]
    (comp (juxt (comp count m->label)
                (comp m->label)
                (comp str :skos/definition)
                (comp :rdf/value))
          second)))

;; TODO: need to also numerically order by synset key, not just alphabetically
(defn look-up
  "Look up synsets in Graph `g` based on the given `lemma`."
  [g lemma]
  (let [k->label (label-lookup g)
        lemma    (if (string? lemma)
                   (->LangStr lemma "da")
                   lemma)]
    (->> (q/run g op/synset-search {'?lemma lemma})
         (group-by '?synset)
         (map (fn [[k ms]]
                (let [{:syms [?label ?synset]
                       :as   base} (apply merge-with q/set-merge ms)
                      subentity (-> base
                                    (dissoc '?lemma
                                            '?form
                                            '?word
                                            '?label
                                            '?sense)
                                    (set/rename-keys sym->kw)
                                    (->> (q/attach-blank-entities g k)))
                      v         (with-meta subentity
                                           {:k->label (assoc k->label
                                                        ?synset ?label)})]
                  [k v])))
         (sort-by search-keyfn)
         (into (fop/ordered-map)))))

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