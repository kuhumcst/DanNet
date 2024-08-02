(ns dk.cst.dannet.db.bootstrap
  "Represent DanNet as an in-memory graph or within a persisted database (TDB).

  Inverse relations are not explicitly created, but rather handled by way of
  inference using a Jena OWL reasoner."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [arachne.aristotle :as aristotle]
            [clj-file-zip.core :as zip]
            [ont-app.vocabulary.lstr :refer [->LangStr]]
            [dk.cst.dannet.db.bootstrap.supersenses :as ss]
            [dk.cst.dannet.hash :as hash]
            [dk.cst.dannet.query :as q]
            [dk.cst.dannet.query.operation :as op]
            [dk.cst.dannet.transaction :as txn]
            [dk.cst.dannet.db :as db]
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

(defn da
  [& s]
  (->LangStr (apply str s) "da"))

(defn en
  [& s]
  (->LangStr (apply str s) "en"))

(def <simongray>
  "<https://simongray.dk>")

(def <cst>
  "<https://cst.dk>")

(def <dsl>
  "<https://dsl.dk>")

(def <dsn>
  "<https://dsn.dk>")

(def <dn>
  "The RDF resource URI for the DanNet dataset."
  (prefix/prefix->rdf-resource 'dn))

(def <dns>
  "The RDF resource URI for the DanNet schema."
  (prefix/prefix->rdf-resource 'dns))

(def <dnc>
  "The RDF resource URI for the DanNet/EuroWordNet concepts."
  (prefix/prefix->rdf-resource 'dnc))

(def <dds>
  "The RDF resource URI for the sentiment dataset."
  (prefix/prefix->rdf-resource 'dds))

(def <cor>
  "The RDF resource URI for the COR dataset."
  (prefix/prefix->rdf-resource 'cor))

(def dn-zip-uri
  (prefix/dataset-uri "rdf" 'dn))

(def dn-zip-csv-uri
  (prefix/dataset-uri "csv" 'dn))

(def cor-zip-uri
  (prefix/dataset-uri "rdf" 'cor))

(def dds-zip-uri
  (prefix/dataset-uri "rdf" 'dds))

(def dns-schema-uri
  (prefix/schema-uri 'dns))

(def dnc-schema-uri
  (prefix/schema-uri 'dnc))

;; Defines the release that the database should be bootstrapped from.
;; If making a new release, the zip files that are placed in /bootstrap/latest
;; need to match precisely this release.
(def old-release
  "2024-06-12")

(def current-release
  (str "2024-06-12" "-SNAPSHOT"))

(defn assert-expected-dannet-release!
  "Assert that the DanNet `model` is the expected release to boostrap from."
  [model]
  (let [result (q/run-basic (.getGraph ^Model model)
                            [:bgp [<dn> :owl/versionInfo old-release]])]
    (assert (not-empty result)
            (str "bootstrap files not the expected release (" old-release "). "
                 result))))

(defn see-also
  [source rdf-resources]
  (set (for [v rdf-resources]
         [source :rdfs/seeAlso v])))

(h/defn update-metadata!
  "Remove old dataset metadata from `model` and add current `dataset-metadata`."
  [dataset-metadata model]
  (println "... updating with current dataset metadata")
  (let [metadata-resources [<dn> <dns> <dnc>
                            <dds> <cor>
                            <cst> <dds> <dsl>
                            <simongray>]]
    (doseq [rdf-resource metadata-resources]
      (db/remove! model [rdf-resource '_ '_]))
    (db/safe-add! (.getGraph ^Model model) dataset-metadata)))

(h/def metadata
  {'dn  (set/union
          (see-also <dn> [<dns> <dnc> <dds> <cor>])
          (see-also <cst> [<dn> <dsl> <dsn>])
          #{[<dn> :rdf/type :dcat/Dataset]
            [<dn> :rdf/type :lime/Lexicon]
            [<dn> :vann/preferredNamespacePrefix "dn"]
            [<dn> :vann/preferredNamespaceUri (prefix/prefix->uri 'dn)]
            [<dn> :rdfs/label "DanNet"]
            [<dn> :dc/title "DanNet"]
            [<dn> :dc/language "da"]
            [<dn> :dc/description (en "The Danish WordNet.")]
            [<dn> :dc/description (da "Det danske WordNet.")]
            [<dn> :dc/issued current-release]
            [<dn> :dc/contributor <simongray>]
            [<dn> :dc/contributor <cst>]
            [<dn> :dc/contributor <dsl>]
            [<dn> :dc/publisher <cst>]
            [<dn> :foaf/homepage "<https://cst.ku.dk/projekter/dannet>"]
            [<dn> :schema/email "simongray@hum.ku.dk"]
            [<dn> :owl/versionInfo current-release]
            [<dn> :dc/rights (en "Copyright © Centre for Language Technology (University of Copenhagen) & "
                                 "The Society for Danish Language and Literature; "
                                 "licensed under CC BY-SA 4.0 (https://creativecommons.org/licenses/by-sa/4.0/).")]
            [<dn> :dc/rights (da "Copyright © Center for Sprogteknologi (Københavns Universitet) & "
                                 "Det Danske Sprog- og Litteraturselskab; "
                                 "udgives under CC BY-SA 4.0 (https://creativecommons.org/licenses/by-sa/4.0/).")]
            [<dn> :dc/license "<https://creativecommons.org/licenses/by-sa/4.0/>"]
            ["<https://creativecommons.org/licenses/by-sa/4.0/>" :rdfs/label "CC BY-SA 4.0"]
            [<dn> :dcat/downloadURL (prefix/uri->rdf-resource dn-zip-uri)]
            [<dn> :dcat/downloadURL (prefix/uri->rdf-resource dn-zip-csv-uri)]
            [<dns> :dcat/downloadURL (prefix/uri->rdf-resource dns-schema-uri)]
            [<dnc> :dcat/downloadURL (prefix/uri->rdf-resource dnc-schema-uri)]

            ;; Contributors
            [<simongray> :rdf/type :foaf/Person]
            [<simongray> :foaf/name "Simon Gray"]
            [<simongray> :foaf/workplaceHomepage "<https://nors.ku.dk/ansatte/?id=428973&vis=medarbejder>"]
            [<simongray> :foaf/homepage <simongray>]
            [<simongray> :foaf/weblog "<https://simon.grays.blog>"]
            [<cst> :rdf/type :foaf/Group]
            [<cst> :foaf/name (da "Center for Sprogteknologi")]
            [<cst> :foaf/name (en "Centre for Language Technology")]
            [<cst> :rdfs/comment (da "Centret er en del af Københavns universitet.")]
            [<cst> :rdfs/comment (en "The centre is part of the University of Copenhagen.")]
            [<cst> :foaf/homepage <cst>]
            [<cst> :foaf/homepage "<https://cst.ku.dk>"]
            [<cst> :foaf/member <simongray>]
            [<dsl> :rdf/type :foaf/Group]
            [<dsl> :foaf/name (da "Det Danske Sprog- og Litteraturselskab")]
            [<dsl> :foaf/name (en "The Society for Danish Language and Literature")]
            [<dsl> :foaf/homepage <dsl>]})
   'dds #{[<dds> :rdfs/label "DDS"]
          [<dds> :dc/title "DDS"]
          [<dds> :dc/description (en "The Danish Sentiment Lexicon")]
          [<dds> :dc/description (da "Det Danske Sentimentleksikon")]
          [<dds> :dc/contributor <cst>]
          [<dds> :dc/contributor <dsl>]
          [<dds> :rdfs/seeAlso (prefix/uri->rdf-resource "https://github.com/dsldk/danish-sentiment-lexicon")]
          [<dds> :dcat/downloadURL (prefix/uri->rdf-resource dds-zip-uri)]}
   'cor #{[<cor> :rdfs/label "COR"]
          [<cor> :dc/title "COR"]
          [<cor> :dc/contributor <cst>]
          [<cor> :dc/contributor <dsl>]
          [<cor> :dc/contributor <dsn>]
          [<cor> :dc/description (en "The Central Word Registry.")]
          [<cor> :dc/description (da "Det Centrale Ordregister.")]
          [<cor> :rdfs/seeAlso (prefix/uri->rdf-resource "https://dsn.dk/sprogets-udvikling/sprogteknologi-og-fagsprog/cor/")]
          [<dsn> :rdf/type :foaf/Group]
          [<dsn> :foaf/name (da "Dansk Sprognævn")]
          [<dsn> :foaf/name (en "The Danish Language Council")]
          [<dsn> :foaf/homepage <dsn>]
          [<cor> :dcat/downloadURL (prefix/uri->rdf-resource cor-zip-uri)]}})

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
  (let [oewn-file     "bootstrap/other/english/english-wordnet-2023.ttl"
        oewn-changefn (fn [temp-model]
                        (println "... removing problematic entries")
                        (db/remove! temp-model [prefix/oewn-uri :lime/entry '_]))
        ili-file      "bootstrap/other/english/ili.ttl"]
    (println "... creating temporary in-memory graph")
    (db/import-files dataset prefix/oewn-uri [oewn-file] oewn-changefn)
    (db/import-files dataset prefix/ili-uri [ili-file]))
  (println "Open English Wordnet imported!")
  (add-open-english-wordnet-labels! dataset))

(defn add-supersenses!
  [dataset]
  (let [g              (db/get-graph dataset prefix/dn-uri)
        _              (->> (ss/prepare-rows @ss/rows)
                            (ss/create-mapping! dataset))
        triples-to-add (ss/triples-to-add @ss/supersense->synsets)]
    (txn/transact-exec g
      (println "... adding" (count triples-to-add) "supersenses")
      (db/safe-add! g triples-to-add))))

(defn fix-verb-creation-supersenses!
  [dataset]
  (let [g                 (db/get-graph dataset prefix/dn-uri)
        model             (db/get-model dataset prefix/dn-uri)
        ancestors         (keys ss/ancestor->supersense)
        ancestor          (fn [{:syms [?synset]}]
                            (ss/by-ancestors g ancestors ?synset))
        groupings         (group-by ancestor (ss/by-dn-supersense g "verb.creation"))
        triples-to-remove (for [{:syms [?synset]} (mapcat second groupings)]
                            [?synset :dns/supersense '_])
        triples-to-add    (mapcat (fn [[k ms]]
                                    (for [{:syms [?synset]} ms]
                                      [?synset :dns/supersense (ss/ancestor->supersense k)]))
                                  groupings)]
    (txn/transact-exec model
      (println "... removing" (count triples-to-remove) "bad supersenses")
      (doseq [triple triples-to-remove]
        (db/remove! model triple)))
    (txn/transact-exec g
      (println "... adding" (count triples-to-add) "fixed supersenses")
      (db/safe-add! g triples-to-add))))

(defn add-remaining-supersenses!
  [dataset]
  (let [g                 (db/get-graph dataset prefix/dn-uri)
        triples-to-add    (ss/remaining-supersense-triples g)]
    (prn triples-to-add)
    (txn/transact-exec g
      (println "... adding" (count triples-to-add) "remaining supersenses")
      (db/safe-add! g triples-to-add))))

(defn update-oewn-links!
  [dataset]
  (let [graph             (db/get-graph dataset prefix/dn-uri)
        model             (db/get-model dataset prefix/dn-uri)
        ms                (->> (op/sparql
                                 "SELECT *
                                  WHERE {
                                    VALUES ?p { wn:eq_synonym dns:eqHyponym dns:eqHypernym }
                                    ?s ?p ?o .
                                  }")
                               (q/run graph)
                               (filter (comp #{"enold"} namespace '?o)))
        triples-to-remove (for [{:syms [?s ?p ?o]} ms]
                            [?s ?p ?o])
        triples-to-add    (for [{:syms [?s ?p ?o]} ms]
                            [?s ?p (keyword "en" (name ?o))])]
    (txn/transact-exec model
      (println "... removing" (count triples-to-remove) "OEWN links")
      (doseq [triple triples-to-remove]
        (db/remove! model triple)))
    (txn/transact-exec graph
      (println "... adding" (count triples-to-add) "OEWN links")
      (db/safe-add! graph triples-to-add))))

(h/defn make-release-changes!
  "This function tracks all changes made in this release, i.e. deletions and
  additions to either of the export datasets.

  This function survives between releases, but the functions it calls are all
  considered temporary and should be deleted when the release comes."
  [dataset]
  (let [expected-release "2024-06-12-SNAPSHOT"]
    (assert (= current-release expected-release))           ; another check
    (println "Applying release changes for" expected-release "...")

    ;; The block of changes for this particular release.
    (update-oewn-links! dataset)
    (add-supersenses! dataset)
    (fix-verb-creation-supersenses! dataset)
    (add-remaining-supersenses! dataset)

    (println "Release changes applied!")))

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
  "The custom reasoner inferring many triples present in the complete dataset."
  (let [rules (Rule/parseRules (slurp (io/resource "etc/dannet.rules")))]
    (doto (GenericRuleReasoner. rules)
      (.setOWLTranslation true)
      (.setMode GenericRuleReasoner/HYBRID)
      (.setTransitiveClosureCaching true))))

(defn dataset->db
  "Construct a database map from an Apache Jena `dataset`.

  If `schema-uris` are provided, the returned model & graph contain inferences;
  otherwise, the model/graph is of union of the models/graphs in the dataset."
  [^Dataset dataset & [schema-uris]]
  (if schema-uris
    (let [schema    (db/->schema-model schema-uris)
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
       :graph   graph})))

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
      (let [files          (remove #{input-dir} (file-seq input-dir))
            fn-hashes      [(:hash (meta #'add-open-english-wordnet!))
                            (:hash (meta #'add-open-english-wordnet-labels!))
                            (:hash (meta #'make-release-changes!))
                            (:hash (meta #'metadata))
                            (:hash (meta #'update-metadata!))
                            (:hash (meta #'->dannet))
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
            dataset        (->dataset db-type full-db-path)
            ;; Include the current build hash to make debugging easier
            metadata'      (update metadata 'dn conj [<dn> :dn/build db-name])]
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
                                (partial update-metadata! (metadata' prefix)))
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
