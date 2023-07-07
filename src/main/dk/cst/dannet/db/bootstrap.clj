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
            [dk.cst.dannet.old.bootstrap :as old.bootstrap]
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

;; Defines the release that the database should be bootstrapped from.
(def old-release
  "2023-06-01")

(def current-release
  (str "2023-07-07"#_#_old-release"-SNAPSHOT"))

(defn assert-expected-dannet-release!
  "Assert that the DanNet `model` is the expected release to boostrap from."
  [model]
  (let [result (q/run-basic (.getGraph ^Model model)
                            [:bgp [<dn> :owl/versionInfo old-release]])]
    (assert (not-empty result)
            (str "bootstrap files were not the expected release (" old-release "). "
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
            [<dn> :dcat/downloadURL (prefix/uri->rdf-resource dn-zip-basic-uri)]
            [<dn> :dcat/downloadURL (prefix/uri->rdf-resource dn-zip-merged-uri)]
            [<dn> :dcat/downloadURL (prefix/uri->rdf-resource dn-zip-complete-uri)]
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

(h/defn ->missing-english-link-triples
  "Convert a `row` from 'relations.csv' to triples.

  Note: certain rows are unmapped, so the relation will remain a string!"
  [[subj-id _ rel obj-id _ _ :as row]]
  ;; Ignores eq_has_hyponym and eq_has_hyperonym, no equivalent in GWA schema.
  ;; This loses us 123 of the original 5000l links to the Princton WordNet.
  (when-let [new-rel (get {"eq_has_hyponym"   :dns/eqHyponym
                           "eq_has_hyperonym" :dns/eqHypernym} rel)]
    (if-let [obj (get @old.bootstrap/senseidx->english-synset obj-id)]
      #{[(old.bootstrap/synset-uri subj-id) new-rel obj]}
      (when-let [ili-obj (get @old.bootstrap/wn20-id->ili obj-id)]
        #{[(old.bootstrap/synset-uri subj-id) new-rel ili-obj]}))))

;; WRONG and not actually needed
(def rel-id->label
  (delay
    (with-open [reader (clojure.java.io/reader "bootstrap/other/dannet-new/wordnetloom/application_localised_string.csv")]
      (into {} (clojure.data.csv/read-csv reader)))))

(def rel-guess
  {"200" :wn/ili
   "201" :dns/eqHyponym
   "202" :dns/eqHypernym
   "203" :dns/eqSimilar})

(def synset-id->ili-id
  (delay
    (with-open [reader (clojure.java.io/reader "bootstrap/other/dannet-new/wordnetloom/synset_attributes.csv")]
      (->> (clojure.data.csv/read-csv reader)
           (map (fn [[synset-id princeton-id ili-id]]
                  (when (not= ili-id "\\N")
                    [synset-id (keyword "ili" ili-id)])))
           (remove nil?)
           (into {})))))

(defn dannet-id?
  [s]
  (and (str/starts-with? s "888")
       (str/ends-with? s "000")))

(defn dannet-synset
  [synset-id]
  (keyword "dn" (str "synset-" (subs synset-id 3 (- (count synset-id) 3)))))

;; TODO: remove for next release
(h/def new-english-link-triples
  (delay
    (with-open [reader (clojure.java.io/reader "bootstrap/other/dannet-new/wordnetloom/synset_relation.csv")]
      (->> (clojure.data.csv/read-csv reader)
           (map (fn [[_ child-synset-id parent-synset-id rel-id]]
                  (when-let [rel (rel-guess rel-id) #_(@rel-id->label rel-id)]
                    (cond
                      ;; A few of the triples are the wrong way around (en->dn)
                      (and (dannet-id? child-synset-id)
                           (not (dannet-id? parent-synset-id)))
                      [(@synset-id->ili-id parent-synset-id)
                       rel
                       (dannet-synset child-synset-id)]

                      ;; The majority are of course dn->en
                      (and (dannet-id? parent-synset-id)
                           (not (dannet-id? child-synset-id)))
                      [(dannet-synset parent-synset-id)
                       rel
                       (@synset-id->ili-id child-synset-id)]))))
           (remove nil?)
           ;; A few corrections for flipped triples in the dataset.
           (map (fn [[s p o :as triple]]
                  (if (= "ili" (namespace s))
                    (cond
                      (= p :wn/ili) [o p s]
                      (= p :dns/eqHypernym) [o :dns/eqHyponym s]
                      :else triple)
                    triple)))
           (doall)))))

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
    (db/import-files dataset prefix/ili-uri [ili-file])

    ;; TODO: remove again after next release
    (println "... add missing old English links (hypernyms and hyponyms)")
    (let [g       (.getGraph (db/get-model dataset prefix/dn-uri))
          triples (->> [->missing-english-link-triples
                        "bootstrap/dannet/DanNet-2.5.1_csv/relations.csv"]
                       (old.bootstrap/read-triples)
                       (remove nil?)
                       (apply set/union))]
      (txn/transact-exec g
        (db/safe-add! g triples)))
    (println "... add new old English links")
    (let [g (.getGraph (db/get-model dataset prefix/dn-uri))]
      (txn/transact-exec g
        (db/safe-add! g @new-english-link-triples))))

  (println "Open English Wordnet imported!")
  #_(add-open-english-wordnet-labels! dataset))

(h/defn ->synset-attribute-triples
  [[synset-id rel v :as row]]
  (let [synset      (old.bootstrap/synset-uri synset-id)
        sex->gender {"male"   :dns/Male
                     "female" :dns/Female}]
    (cond
      (= rel "domain")
      #{[synset :dc/subject (da v)]}

      (= rel "sex")
      (when-let [gender (sex->gender v)]
        #{[synset :dns/gender gender]}))))

(h/defn add-gender-and-domain!
  [dataset]
  (println "Importing data from synset_attributes.csv...")
  (let [g       (.getGraph (db/get-model dataset prefix/dn-uri))
        triples (->> [->synset-attribute-triples
                      "bootstrap/dannet/DanNet-2.5.1_csv/synset_attributes.csv"]
                     (old.bootstrap/read-triples)
                     (remove nil?)
                     (apply set/union))]
    (println "... triples to be added:" (count triples))
    (txn/transact-exec g
      (db/safe-add! g triples)))
  (println "synset_attributes.csv imported!"))

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
                            (:hash (meta #'->missing-english-link-triples))
                            (:hash (meta #'new-english-link-triples))
                            (:hash (meta #'add-gender-and-domain!))
                            (:hash (meta #'->synset-attribute-triples))
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
            dataset        (->dataset db-type full-db-path)]
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
                                (partial update-metadata! (metadata prefix)))
                    ;; Special behaviour to check bootstrap files version
                    changefn  (if (= prefix 'dn)
                                (fn [model]
                                  (println "... checking version" model-uri)
                                  (assert-expected-dannet-release! model)
                                  (update! model))
                                update!)]
                (db/import-files dataset model-uri [ttl-file] changefn)
                (zip/delete-file ttl-file)))

            (add-gender-and-domain! dataset)
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

(comment
  @new-english-link-triples
  @rel-id->label
  @synset-id->ili-id
  (dannet-synset "888glen000")
  #_.)
