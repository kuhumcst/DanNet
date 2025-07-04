(ns dk.cst.dannet.db.bootstrap
  "Represent DanNet as an in-memory graph or within a persisted database (TDB).

  Inverse relations are not explicitly created, but rather handled by way of
  inference using a Jena OWL reasoner."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [arachne.aristotle :as aristotle]
            [clj-file-zip.core :as zip]
            [dk.cst.dannet.shared :as shared]
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
(def bootstrap-base-release
  "2024-08-09")

(def new-release
  (str "2025-07-03" #_"-SNAPSHOT"))

(defn assert-expected-dannet-release!
  "Assert that the DanNet `model` is the expected release to boostrap from."
  [model]
  (let [result (q/run-basic (.getGraph ^Model model)
                            [:bgp [<dn> :owl/versionInfo bootstrap-base-release]])]
    (assert (not-empty result)
            (str "bootstrap files not the expected release (" bootstrap-base-release "). "
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
            [<dn> :dc/issued new-release]
            [<dn> :dc/contributor <simongray>]
            [<dn> :dc/contributor <cst>]
            [<dn> :dc/contributor <dsl>]
            [<dn> :dc/publisher <cst>]
            [<dn> :foaf/homepage "<https://cst.ku.dk/projekter/dannet>"]
            [<dn> :schema/email "simongray@hum.ku.dk"]
            [<dn> :owl/versionInfo new-release]
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
  (let [oewn-file     "bootstrap/other/english/english-wordnet-2024.ttl"
        oewn-changefn (fn [temp-model]
                        (println "... removing problematic entries")
                        (db/remove! temp-model [prefix/oewn-uri :lime/entry '_]))
        ili-file      "bootstrap/other/english/ili.ttl"]
    (println "... creating temporary in-memory graph")
    (db/import-files dataset prefix/oewn-uri [oewn-file] oewn-changefn)
    (db/import-files dataset prefix/ili-uri [ili-file]))
  (println "Open English Wordnet imported!")
  (add-open-english-wordnet-labels! dataset))

(defn new-reps
  [label]
  (let [clean     (fn [lstr]
                    (-> (str lstr)
                        (str/replace #"(^| )'+" "$1")       ; infixed apostrophe
                        (str/replace "\"" "")))
        label-str (clean label)
        reps      (first (re-find #"[^ \(\)]+(/[^ \(\)]+)+" label-str))]
    (when reps
      (for [part (str/split reps #"/")]
        (da (str/replace label-str reps part))))))

(defn fix-canonical-reps!
  [dataset]
  (let [g     (db/get-graph dataset prefix/dn-uri)
        q     (op/sparql
                "SELECT ?w ?form ?label ?rep
                 WHERE {
                   ?w ontolex:canonicalForm ?form .
                   ?form ontolex:writtenRep ?rep .
                   ?form ontolex:writtenRep ?rep2 .
                   FILTER (?rep != ?rep2) .
                   ?w rdfs:label ?label .
                 }")
        w->ms (group-by '?w (q/run g q))
        ms    (map (fn [[?word [{:syms [?label]} :as ms]]]
                     (when-let [reps (new-reps ?label)]
                       {:add    (if (= ?word :dn/word-51001426) ; m/k'er special case
                                  [[:dn/word-51001426 :ontolex/canonicalForm '_mker_form]
                                   ['_mker_form :ontolex/writtenRep (da "m/k'er")]]
                                  (into
                                    (let [cf (symbol (str "_form_" (name ?word)))]
                                      [[?word :ontolex/canonicalForm cf]
                                       [cf :ontolex/writtenRep (first reps)]])
                                    (apply concat (map-indexed
                                                    (fn [n rep]
                                                      (let [of (symbol (str "_form_" (name ?word) "_" n))]
                                                        [[?word :ontolex/otherForm of]
                                                         [of :ontolex/writtenRep rep]]))
                                                    (rest reps)))))
                        :remove (into [[?word :ontolex/canonicalForm '_]]
                                      (for [rep (set (map '?rep ms))]
                                        ['_ :ontolex/writtenRep rep]))}))
                   w->ms)]
    (let [g                 (db/get-graph dataset prefix/dn-uri)
          model             (db/get-model dataset prefix/dn-uri)
          triples-to-add    (mapcat :add ms)
          triples-to-remove (mapcat :remove ms)]
      (txn/transact-exec model
        (println "... removing old form triples:" (count triples-to-remove))
        (doseq [triple triples-to-remove]
          (db/remove! model triple)))
      (txn/transact-exec g
        (println "... adding" (count triples-to-add) "updated form triples")
        (db/safe-add! g triples-to-add)))))

(defn merge-entities
  [g subjects]
  (let [normalize-entity #(update-vals (q/entity g %) shared/setify)]
    (->> (map normalize-entity subjects)
         (apply merge-with set/union))))

(defn set-primary-label
  [{:keys [skos/altLabel] :as m}]
  (let [primary-label (-> altLabel shared/canonical first da)
        generic-label (some->> altLabel
                               (map (comp (partial re-matches shared/sense-label) str))
                               (remove #(nth % 2))
                               (ffirst)
                               (da))
        altLabel'     (disj altLabel primary-label generic-label)
        m'            (assoc m :rdfs/label primary-label)]
    (if (empty? altLabel')
      (dissoc m' :skos/altLabel)
      (assoc m' :skos/altLabel altLabel'))))

(defn sense-mergers
  [dataset]
  (let [g (db/get-graph dataset prefix/dn-uri)
        q (op/sparql
            "SELECT *
             WHERE {
               ?synset ontolex:lexicalizedSense ?s1 ;
                       ontolex:lexicalizedSense ?s2 .
               FILTER (?s1 != ?s2)
               ?w ontolex:evokes ?synset ;
                  ontolex:sense ?s1 ;
                  ontolex:sense ?s2 .
             }")]
    (-> (group-by '?w (q/run g q))
        (update-vals (fn [ms]
                       (->> (map (fn [{:syms [?s1 ?s2] :as m}]
                                   (assoc (dissoc m '?s1 '?s2)
                                     '?senses #{?s1 ?s2}))

                                 ms)
                            (apply merge-with #(if (set? %1)
                                                 (into %1 %2)
                                                 %))
                            ((fn [{:syms [?senses] :as m}]
                               (let [uri    (->> (map name ?senses)
                                                 (map #(subs % 6))
                                                 (interpose "_")
                                                 (apply str "sense-")
                                                 (keyword "dn"))
                                     merged (-> (merge-entities g ?senses)
                                                (set/rename-keys {:rdfs/label :skos/altLabel})
                                                (set-primary-label)
                                                (assoc :dns/subsumed ?senses)
                                                (assoc :rdf/about uri))]
                                 (-> m
                                     (assoc :uri uri)
                                     (assoc :merged merged)))))))))))

(defn merge-senses!
  [dataset]
  (let [g                 (db/get-graph dataset prefix/dn-uri)
        model             (db/get-model dataset prefix/dn-uri)
        word->m           (sense-mergers dataset)
        triples-to-remove (->> (mapcat '?senses (vals word->m))
                               (mapcat (fn [sense]
                                         [[sense '_ '_]
                                          ['_ '_ sense]])))
        triples-to-add    (mapcat (fn [[_ {:keys [uri] :syms [?w ?synset]}]]
                                    [[?w :ontolex/sense uri]
                                     [?synset :ontolex/lexicalizedSense uri]])
                                  word->m)
        maps-to-add       (map (comp :merged second) word->m)]
    (txn/transact-exec model
      (println "... removing old sense triples:" (count triples-to-remove))
      (doseq [triple triples-to-remove]
        (db/remove! model triple)))
    (txn/transact-exec g
      (println "... adding" (count maps-to-add) "merged senses")
      (db/safe-add! g maps-to-add))
    (txn/transact-exec g
      (println "... adding" (count triples-to-add) "updated sense links")
      (db/safe-add! g triples-to-add))))

(defn sense-labels->synset-label
  [labels]
  (da (str "{" (->> labels
                    (sort-by str)
                    (str/join "; "))
           "}")))

(defn relabel-synsets!
  [dataset]
  (let [g                   (db/get-graph dataset prefix/dn-uri)
        model               (db/get-model dataset prefix/dn-uri)
        synset->ms          (-> (q/run g '[:bgp
                                           [?synset :ontolex/lexicalizedSense ?sense]
                                           [?sense :dns/subsumed ?oldSense]
                                           [?sense :rdfs/label ?label]])
                                (->> (group-by '?synset))
                                (update-vals (fn [ms] (set (map '?label ms)))))
        triples-to-remove   (for [synset (keys synset->ms)]
                              [synset :rdfs/label '_])
        synset->label       (update-vals synset->ms sense-labels->synset-label)
        synset->short-label (update-vals synset->ms (comp sense-labels->synset-label shared/canonical))
        triples-to-add      (concat
                              (for [[synset label] synset->label]
                                [synset :rdfs/label label])
                              (for [[synset short-label] synset->short-label]
                                [synset :dns/shortLabel short-label]))]
    (txn/transact-exec model
      (println "... removing old synset labels:" (count triples-to-remove))
      (doseq [triple triples-to-remove]
        (db/remove! model triple)))
    (txn/transact-exec g
      (println "... adding" (count triples-to-add) "updated labels & short labels")
      (db/safe-add! g triples-to-add))))

(defn relink-cor!
  [dataset]
  (let [g                 (db/get-graph dataset prefix/cor-uri)
        model             (db/get-model dataset prefix/cor-uri)
        ms                (q/run (.getGraph (.getUnionModel dataset))
                                 (op/sparql
                                   "SELECT *
                                     WHERE {
                                       ?sense dns:subsumed ?oldSense .
                                       ?x ?rel ?oldSense .
                                       FILTER (?rel != dns:subsumed)
                                       ?x rdf:type ?type .
                                     }"))
        triples-to-remove (set (map (fn [{:syms [?x ?rel ?oldSense]}]
                                      [?x ?rel ?oldSense])
                                    ms))
        triples-to-add    (set (map (fn [{:syms [?x ?rel ?sense]}]
                                      [?x ?rel ?sense])
                                    ms))]
    (txn/transact-exec model
      (println "... removing old COR sense links:" (count triples-to-remove))
      (doseq [triple triples-to-remove]
        (db/remove! model triple)))
    (txn/transact-exec g
      (println "... adding" (count triples-to-add) "updated COR sense links")
      (db/safe-add! g triples-to-add))))

(defn remove-self-references!
  [dataset]
  (let [g                 (db/get-graph dataset prefix/dn-uri)
        model             (db/get-model dataset prefix/dn-uri)
        ms                (q/run g (op/sparql
                                     "SELECT *
                                      WHERE {
                                        ?synset ?rel ?synset .
                                        FILTER (strstarts(str(?synset), 'https://wordnet.dk/dannet/data/synset-'))
                                      }"))
        triples-to-remove (for [{:syms [?synset ?rel]} ms]
                            [?synset ?rel ?synset])]
    (txn/transact-exec model
      (println "... removing self-referencing synsets:" (count triples-to-remove))
      (doseq [triple triples-to-remove]
        (db/remove! model triple)))))

(comment
  (let [ms                (-> (:dataset @dk.cst.dannet.web.resources/db)
                              (db/get-graph prefix/dn-uri)
                              (q/run op/adj-cross-pos-hypernymy)
                              (set))
        triples-to-remove (for [{:syms [?synset ?hypernym]} ms]
                            [?synset :wn/hypernym ?hypernym])
        triples-to-add    (for [{:syms [?synset ?hypernym]} ms]
                            [?synset :dn/crossPoSHypernym ?hypernym])]
    (count triples-to-remove))

  (let [ms (q/run (db/get-graph (:dataset @dk.cst.dannet.web.resources/db)
                                prefix/dn-uri)
                  op/cross-pos-hypernymy)]
    (->> ms
         #_(remove (comp #{:lexinfo/adjective} '?pos1))
         (set)
         (take 10)
         #_(count)))

  #_.)

(h/defn make-release-changes!
  "This function tracks all changes made in this release, i.e. deletions and
  additions to either of the export datasets.

  This function survives between releases, but the functions it calls are all
  considered temporary and should be deleted when the release comes."
  [dataset]
  (let [expected-release (str "2025-07-03")]
    (assert (= new-release expected-release))               ; another check
    (println "Applying release changes for" expected-release "...")

    ;; ==== The block of changes for this particular release. ====

    (remove-self-references! dataset)

    ;; Replace synsets with duplicate lemmas with new merged senses #146
    (merge-senses! dataset)
    (relabel-synsets! dataset)
    (relink-cor! dataset)

    ;; Remove duplicate canonical forms, add other forms instead #148
    (fix-canonical-reps! dataset)

    ;; Rename dns:supersense -> wn:lexfile #146
    (db/update-triples! prefix/dn-uri dataset
                        '[:bgp
                          [?synset :dns/supersense ?supersense]]
                        (fn [{:syms [?synset ?supersense]}]
                          [?synset :wn/lexfile ?supersense])
                        '[_ :dns/supersense _])

    ;; Rename wn:hypernym -> dns:crossPoSHypernym for adjectives #146
    (db/update-triples! prefix/dn-uri dataset
                        op/adj-cross-pos-hypernymy
                        (fn [{:syms [?synset ?hypernym]}]
                          [?synset :dns/crossPoSHypernym ?hypernym])
                        (fn [{:syms [?synset ?hypernym]}]
                          [?synset :wn/hypernym ?hypernym]))

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
      (let [files          (->> (file-seq input-dir)
                                (filter #(re-find #"\.zip$" (.getName ^File %))))
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
