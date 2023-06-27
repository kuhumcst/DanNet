(ns dk.cst.dannet.db.bootstrap
  "Represent DanNet as an in-memory graph or within a persisted database (TDB).

  Inverse relations are not explicitly created, but rather handled by way of
  inference using a Jena OWL reasoner."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.math.combinatorics :as combo]
            [arachne.aristotle :as aristotle]
            [clj-file-zip.core :as zip]
            [ont-app.vocabulary.lstr :refer [->LangStr]]
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
           [org.apache.jena.query DatasetFactory]
           [org.apache.jena.rdf.model ModelFactory]
           [org.apache.jena.reasoner.rulesys GenericRuleReasoner Rule]
           [org.apache.jena.tdb TDBFactory]
           [org.apache.jena.tdb2 TDB2Factory]))

(defn da
  [& s]
  (->LangStr (apply str s) "da"))

(defn en
  [& s]
  (->LangStr (apply str s) "en"))

(defn fix-ellipsis
  [definition]
  (str/replace definition " ..." "…"))

(def <simongray>
  (prefix/uri->rdf-resource "https://simongray.dk"))

(def <cst>
  (prefix/uri->rdf-resource "https://cst.dk"))

(def <dsl>
  (prefix/uri->rdf-resource "https://dsl.dk"))

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
  "<https://ordregister.dk>")

(def dn-zip-basic-uri
  (prefix/dataset-uri "rdf" 'dn))

(def dn-zip-merged-uri
  (prefix/dataset-uri "rdf" 'dn "merged"))

(def dn-zip-complete-uri
  (prefix/dataset-uri "rdf" 'dn "complete"))

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

(def release-date
  "2023-06-01")

(def dc-issued-old
  "2013-01-03")

(defn see-also
  "Generate rdfs:seeAlso backlink triples for `rdf-resources`."
  [& rdf-resources]
  (set (for [[k v] (combo/permuted-combinations rdf-resources 2)]
         [k :rdfs/seeAlso v])))

(h/def metadata-triples
  "Metadata for the different datasets is defined here.

  The Dublin Core Terms NS is used below which supersedes
  the older DC namespace (see: https://www.dublincore.org/schemas/rdfs/ )."
  (set/union
    #{[<dns> :rdf/type :owl/Ontology]
      [<dns> :vann/preferredNamespacePrefix "dns"]
      [<dns> :vann/preferredNamespaceUri (prefix/prefix->uri 'dns)]
      [<dns> :dc/title (en "DanNet schema")]
      [<dns> :dc/title (da "DanNet-skema")]
      [<dns> :dc/description (en "Schema for DanNet-specific relations.")]
      [<dns> :dc/description (da "Skema for DanNet-specifikke relationer.")]
      [<dns> :dc/issued release-date]
      [<dns> :dc/contributor <simongray>]
      [<dns> :dc/contributor <cst>]
      [<dns> :dc/contributor <dsl>]
      [<dns> :dc/publisher <cst>]
      [<dns> :foaf/homepage "<https://cst.ku.dk/projekter/dannet>"]
      [<dns> :dc/rights (en "Copyright © Centre for Language Technology (University of Copenhagen) & The Society for Danish Language and Literature.")]
      [<dns> :dc/rights (da "Copyright © Center for Sprogteknologi (Københavns Universitet) & Det Danske Sprog- og Litteraturselskab.")]
      [<dns> :dc/license "<https://creativecommons.org/licenses/by-sa/4.0/>"]

      [<dnc> :rdf/type :owl/Ontology]                       ;TODO: :skos/ConceptScheme instead?
      [<dnc> :vann/preferredNamespacePrefix "dnc"]
      [<dnc> :vann/preferredNamespaceUri (prefix/prefix->uri 'dnc)]
      [<dnc> :dc/title (en "DanNet concepts")]
      [<dnc> :dc/title (da "DanNet-koncepter")]
      [<dnc> :dc/description (en "Schema containing all DanNet/EuroWordNet concepts.")]
      [<dnc> :dc/description (da "Skema der indholder alle DanNet/EuroWordNet-koncepter.")]
      [<dnc> :dc/issued release-date]
      [<dnc> :dc/contributor <simongray>]
      [<dnc> :dc/contributor <cst>]
      [<dnc> :dc/contributor <dsl>]
      [<dnc> :dc/publisher <cst>]
      [<dnc> :foaf/homepage "<https://cst.ku.dk/projekter/dannet>"]
      [<dnc> :dc/rights (en "Copyright © Centre for Language Technology (University of Copenhagen) & The Society for Danish Language and Literature.")]
      [<dnc> :dc/rights (da "Copyright © Center for Sprogteknologi (Københavns Universitet) & Det Danske Sprog- og Litteraturselskab.")]
      [<dnc> :dc/license "<https://creativecommons.org/licenses/by-sa/4.0/>"]

      [<dn> :rdf/type :dcat/Dataset]
      [<dn> :rdf/type :lime/Lexicon]
      [<dn> :vann/preferredNamespacePrefix "dn"]
      [<dn> :vann/preferredNamespaceUri (prefix/prefix->uri 'dn)]
      [<dn> :rdfs/label "DanNet"]
      [<dn> :dc/title "DanNet"]
      [<dn> :dc/language "da"]
      [<dn> :dc/description (en "The Danish WordNet.")]
      [<dn> :dc/description (da "Det danske WordNet.")]
      [<dn> :dc/issued release-date]
      [<dn> :dc/contributor <simongray>]
      [<dn> :dc/contributor <cst>]
      [<dn> :dc/contributor <dsl>]
      [<dn> :dc/publisher <cst>]
      [<dn> :foaf/homepage "<https://cst.ku.dk/projekter/dannet>"]
      [<dn> :schema/email "simongray@hum.ku.dk"]
      [<dn> :owl/versionInfo release-date]
      [<dn> :dc/rights (en "Copyright © Centre for Language Technology (University of Copenhagen) & "
                           "The Society for Danish Language and Literature; "
                           "licensed under CC BY-SA 4.0 (https://creativecommons.org/licenses/by-sa/4.0/).")]
      [<dn> :dc/rights (da "Copyright © Center for Sprogteknologi (Københavns Universitet) & "
                           "Det Danske Sprog- og Litteraturselskab; "
                           "udgives under CC BY-SA 4.0 (https://creativecommons.org/licenses/by-sa/4.0/).")]
      [<dn> :dc/license "<https://creativecommons.org/licenses/by-sa/4.0/>"]
      ["<https://creativecommons.org/licenses/by-sa/4.0/>" :rdfs/label "CC BY-SA 4.0"]

      [<dds> :rdfs/label "DDS"]
      [<dds> :dc/title "DDS"]
      [<dds> :dc/description (en "The Danish Sentiment Lexicon")]
      [<dds> :dc/description (da "Det Danske Sentimentleksikon")]
      [<dds> :dc/contributor <cst>]
      [<dds> :dc/contributor <dsl>]
      [<dds> :rdfs/seeAlso (prefix/uri->rdf-resource "https://github.com/dsldk/danish-sentiment-lexicon")]

      [<cor> :rdfs/label "COR"]
      [<cor> :dc/title "COR"]
      [<cor> :dc/contributor <cst>]
      [<cor> :dc/contributor <dsl>]
      [<cor> :dc/contributor <dsn>]
      [<cor> :dc/description (en "The Central Word Registry.")]
      [<cor> :dc/description (da "Det Centrale Ordregister.")]
      [<cor> :rdfs/seeAlso (prefix/uri->rdf-resource "https://dsn.dk/sprogets-udvikling/sprogteknologi-og-fagsprog/cor/")]

      ;; Contributors/publishers
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
      [<dsl> :foaf/homepage <dsl>]
      [<dsn> :rdf/type :foaf/Group]
      [<dsn> :foaf/name (da "Dansk Sprognævn")]
      [<dsn> :foaf/name (en "The Danish Language Council")]
      [<dsn> :foaf/homepage <dsn>]}

    (see-also <dn> <dns> <dnc>)
    (see-also <dn> <dds>)
    (see-also <dn> <cor>)
    (see-also <cst> <dsl> <dsn>)

    ;; Downloads
    #{[<dn> :dcat/downloadURL (prefix/uri->rdf-resource dn-zip-basic-uri)]
      [<dn> :dcat/downloadURL (prefix/uri->rdf-resource dn-zip-merged-uri)]
      [<dn> :dcat/downloadURL (prefix/uri->rdf-resource dn-zip-complete-uri)]
      [<dn> :dcat/downloadURL (prefix/uri->rdf-resource dn-zip-csv-uri)]
      [<cor> :dcat/downloadURL (prefix/uri->rdf-resource cor-zip-uri)]
      [<dds> :dcat/downloadURL (prefix/uri->rdf-resource dds-zip-uri)]
      [<dns> :dcat/downloadURL (prefix/uri->rdf-resource dns-schema-uri)]
      [<dnc> :dcat/downloadURL (prefix/uri->rdf-resource dnc-schema-uri)]}))

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
  (let [oewn-file     "bootstrap/other/english/english-wordnet-2022.ttl"
        oewn-changefn (fn [temp-model]
                        (let [princeton "<http://wordnet-rdf.princeton.edu/>"]
                          (println "... removing problematic entries")
                          (db/remove! temp-model [princeton :lime/entry '_])))
        ili-file      "bootstrap/other/english/ili.ttl"]
    (println "... creating temporary in-memory graph")
    (db/import-files dataset prefix/oewn-uri [oewn-file] oewn-changefn)
    (db/import-files dataset prefix/ili-uri [ili-file]))
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

(def reasoner
  (let [rules (Rule/parseRules (slurp (io/resource "etc/dannet.rules")))]
    (doto (GenericRuleReasoner. rules)
      (.setOWLTranslation true)
      (.setMode GenericRuleReasoner/HYBRID)
      (.setTransitiveClosureCaching true))))

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
                (db/import-files dataset model-uri [ttl-file])
                (zip/delete-file ttl-file)))

            (add-open-english-wordnet! dataset)
            (println new-entry)
            (spit log-path (str new-entry "\n----\n") :append true))))
      (println "WARNING: no input dir provided, dataset will be empty!"))

    ;; If schemas are provided, the returned model & graph contain inferences.
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
         :graph   graph}))))
