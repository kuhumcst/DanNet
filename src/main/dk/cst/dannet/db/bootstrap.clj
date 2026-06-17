(ns dk.cst.dannet.db.bootstrap
  "Represent DanNet as an in-memory graph or within a persisted database (TDB).

  Inverse relations are not explicitly created, but rather handled by way of
  inference using a Jena OWL reasoner."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [arachne.aristotle :as aristotle]
            [clj-file-zip.core :as zip]
            [dk.cst.dannet.db.query :as q]
            [dk.cst.dannet.db.query.operation :as op]
            [dk.cst.dannet.db.transaction :as txn]
            [dk.cst.dannet.db :as db]
            [dk.cst.dannet.db.bootstrap.downloads :as downloads]
            [dk.cst.dannet.db.bootstrap.metadata :as md]
            [dk.cst.dannet.hash :as h]
            [dk.cst.dannet.prefix :as prefix])
  (:import [java.io File]
           [java.time LocalDateTime]
           [java.time.format DateTimeFormatter]
           [org.apache.jena.query Dataset DatasetFactory]
           [org.apache.jena.rdf.model Model ModelFactory]
           [org.apache.jena.reasoner.rulesys GenericRuleReasoner Rule]
           [org.apache.jena.tdb TDBFactory]
           [org.apache.jena.tdb2 TDB2Factory]))

(defn assert-expected-dannet-release!
  "Assert that the DanNet `model` is the expected release to bootstrap from."
  [model]
  (let [result (q/run (.getGraph ^Model model)
                      [:bgp [md/<dn> :owl/versionInfo md/bootstrap-base-release]])]
    (assert (not-empty result)
            (str "bootstrap files not the expected release (" md/bootstrap-base-release "). "
                 result))))

(h/defn add-open-english-wordnet-labels!
  "Generate appropriate labels for the (otherwise unlabeled) OEWN in `dataset`."
  [dataset]
  (println "Adding labels to the Open English Wordnet...")
  (let [oewn-graph   (db/get-graph dataset prefix/oewn-uri)
        label-graph  (db/get-graph dataset prefix/oewn-extension-uri)
        ms           (q/run oewn-graph op/oewn-label-targets)
        collect-rep  (fn [m {:syms [?synset ?rep]}]
                       (update m ?synset conj (str ?rep)))
        synset-label (fn [labels]
                       (as-> labels $
                             (set $)
                             (sort $)
                             (str/join "; " $)
                             (md/en "{" $ "}")))]
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
                     [[?word :rdfs/label (md/en "\"" ?rep "\"")]
                      [?sense :rdfs/label ?rep]]))
           (aristotle/add label-graph))))
  (println "Labels added to the Open English WordNet!"))

;; TODO: move to separate ns
(h/defn add-open-english-wordnet!
  "Add the Open English WordNet to a Jena `dataset`."
  [dataset]
  (println "Importing Open English Wordnet...")
  (let [oewn-changefn (fn [temp-model]
                        (println "... removing problematic entries")
                        (db/remove! temp-model [prefix/oewn-uri :lime/entry '_]))]
    (println "... creating temporary in-memory graph")
    (db/import-files dataset prefix/oewn-uri [downloads/oewn-ttl-path] oewn-changefn)
    (db/import-files dataset prefix/ili-uri [downloads/ili-path]))
  (println "Open English Wordnet imported!")
  (add-open-english-wordnet-labels! dataset))

(h/defn make-release-changes!
  "This function tracks all changes made in this release, i.e. deletions and
  additions to either of the export datasets.

  This function survives between releases, but the functions it calls are all
  considered temporary and should be deleted when the release comes."
  [dataset]
  ;; Cleanup tripwire. This literal is a deliberate duplicate of (:from md/release).
  ;; It stays stable throughout a development cycle, so it never interferes with
  ;; everyday rebuilds. When the NEXT cycle is opened and :from is bumped to the
  ;; release that was just cut, this assertion fires -- forcing the now-shipped
  ;; temporary changes below to be cleared out and this marker bumped to match.
  (assert (= "2025-07-03" (:from md/release))
          (str "make-release-changes! still holds changes for the old release, "
               "but (:from release) is now " (:from md/release) ". "
               "Clear out the shipped changes and update this marker."))
  (println "Applying release changes for" md/new-release "...")

  ;; ==== The block of changes for this particular release. ====
  ;; TODO: add release changes for next release

  (println "Release changes applied!"))

(defn ->dataset
  "Get a Dataset object of the given `db-type`. TDB also requires a `db-path`.

  NOTE: TDB 1 does not require transactions until after the first transaction
  has taken place, while TDB 2 *always* requires transactions when reading from
  or writing to the database."
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

(def reasoner
  "The custom reasoner inferring many triples present in the complete dataset.

  The rules in 'dannet.rules' are purpose-built for DanNet, covering only
  owl:inverseOf and rdfs:subPropertyOf entailment as tabled backward rules."
  (let [rules (Rule/parseRules (slurp (io/resource "etc/dannet.rules")))]
    (doto (GenericRuleReasoner. rules)
      (.setMode GenericRuleReasoner/HYBRID)
      (.setTransitiveClosureCaching true))))

(defn dataset->db
  "Construct a database map from an Apache Jena `dataset`.

  If `schema-uris` are provided, the returned model & graph contain inferences;
  otherwise, the model/graph is of union of the models/graphs in the dataset.

  The base (non-inference) model is always available as :base-model for use by
  the SPARQL endpoint, where inference is opt-in."
  [^Dataset dataset & [schema-uris]]
  (if schema-uris
    (let [schema    (db/->schema-model schema-uris)
          model     (.getUnionModel dataset)
          inf-model (ModelFactory/createInfModel reasoner schema model)
          inf-graph (.getGraph inf-model)]
      (println "Schema URIs found -- constructing inference model.")
      {:dataset    dataset
       :base-model model
       :model      inf-model
       :graph      inf-graph})
    (let [model (.getUnionModel dataset)
          graph (.getGraph model)]
      {:dataset    dataset
       :base-model model
       :model      model
       :graph      graph})))

(h/defn ->dannet
  "Create a Jena database from the latest DanNet export.

    :input-dir         - Previous DanNet version TTL export as a File directory.
    :db-type           - :tdb1, :tdb2, :in-mem, and :in-mem-txn are supported
    :db-path           - Where to persist the TDB1/TDB2 data.
    :schema-uris       - A collection of URIs containing schemas."
  [& {:keys [^File input-dir db-path db-type schema-uris]
      :or   {db-type :in-mem} :as opts}]
  (let [log-path (str db-path "/log.txt")]
    (if input-dir
      (let [_              (downloads/ensure-bootstrap-datasets! input-dir (:from md/release))
            _              (downloads/ensure-synset-indegrees! (:from md/release))
            _              (downloads/ensure-english-datasets!)
            files          (->> (file-seq input-dir)
                                (filter #(re-find #"\.zip$" (.getName ^File %))))
            fn-hashes      [(:hash (meta #'add-open-english-wordnet!))
                            (:hash (meta #'add-open-english-wordnet-labels!))
                            (:hash (meta #'make-release-changes!))
                            (:hash (meta #'md/metadata))
                            (:hash (meta #'md/update-metadata!))
                            (:hash (meta #'->dannet))
                            (hash prefix/schemas)]
            ;; Undo potentially negative number by bit-shifting.
            files-hash     (h/pos-hash files)
            bootstrap-hash (h/pos-hash fn-hashes)
            db-name        (str files-hash "-" bootstrap-hash)
            full-db-path   (str db-path "/" db-name)
            zip-file?      (comp #(str/ends-with? % ".zip") #(.getName %))
            ttl-file?      (comp #(str/ends-with? % ".ttl") #(.getName %))
            db-exists?     (.exists (io/file full-db-path))
            new-entry      (log-entry db-name db-type input-dir)
            dataset        (->dataset db-type full-db-path)
            ;; Include the current build hash to make debugging easier
            metadata'      (update md/metadata 'dn conj [md/<dn> :dn/build db-name])]
        (println "Full database path:" full-db-path)
        (if db-exists?
          (do
            (println "Skipping build -- database already exists:" full-db-path)
            (dataset->db dataset schema-uris))
          (do
            (println "Creating new database from:" (.getName input-dir))
            (doseq [zip-file (filter zip-file? (file-seq input-dir))]
              (zip/unzip zip-file zip-file)
              (let [ttl-file  (first (filter ttl-file? (file-seq input-dir)))
                    model-uri (prefix/zip-file->uri (.getName zip-file))
                    prefix    (prefix/uri->prefix model-uri)
                    update!   (when prefix
                                (partial md/update-metadata! (metadata' prefix)))
                    ;; Special behaviour to check bootstrap files version
                    changefn  (if (= prefix 'dn)
                                (fn [model]
                                  (println "... checking version" model-uri)
                                  (assert-expected-dannet-release! model)
                                  (update! model))
                                update!)]
                (db/import-files dataset model-uri [ttl-file] changefn)
                (zip/delete-file ttl-file)))

            ;; Effectuate changes for the current release.
            ;; These are always tied to the current release and depend on the
            ;; former release, i.e. the contents of this function is versioned
            ;; together with every single formal release.
            (make-release-changes! dataset)

            ;; The English is always explicitly added as it is not part of our
            ;; own latest export (only the DanNet-like labels we produce are).
            (add-open-english-wordnet! dataset)

            (println new-entry)
            (spit log-path (str new-entry "\n----\n") :append true)
            (dataset->db dataset schema-uris))))
      (let [db-name      (->> (slurp log-path)
                              (re-seq #"Location: (.+)")
                              (last)
                              (second))
            full-db-path (str db-path "/" db-name)
            dataset      (->dataset db-type full-db-path)]
        (println "WARNING: no input dir provided!")
        (dataset->db dataset schema-uris)))))
