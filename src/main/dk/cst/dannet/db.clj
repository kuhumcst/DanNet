(ns dk.cst.dannet.db
  "Represent DanNet as an in-memory graph or within a persisted database (TDB)."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [arachne.aristotle :as aristotle]
            [ont-app.igraph-jena.core :as igraph-jena]
            [ont-app.igraph.core :as igraph]
            [flatland.ordered.map :as fop]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.bootstrap :as bootstrap]
            [dk.cst.dannet.query :as q]
            [dk.cst.dannet.query.operation :as op]
            [dk.cst.dannet.transaction :as txn])
  (:import [org.apache.jena.riot RDFDataMgr RDFFormat]
           [org.apache.jena.tdb TDBFactory]
           [org.apache.jena.tdb2 TDB2Factory]
           [org.apache.jena.rdf.model ModelFactory Model]
           [org.apache.jena.query Dataset]
           [org.apache.jena.reasoner.rulesys GenericRuleReasoner Rule]))

(def schema-uris
  "URIs where relevant schemas can be fetched."
  (->> (for [{:keys [alt uri schema?]} (vals prefix/schemas)]
         (when-not (false? schema?)
           (if alt
             (if (or (str/starts-with? alt "http://")
                     (str/starts-with? alt "https://"))
               alt
               (str (io/resource alt)))
             uri)))
       (filter some?)))

(defn ->schema-model
  "Create a Model containing the schemas found at the given `uris`."
  [uris]
  (reduce (fn [model ^String schema-uri]
            (if (str/ends-with? schema-uri ".ttl")
              (.read model schema-uri "TURTLE")
              (.read model schema-uri "RDF/XML")))
          (ModelFactory/createDefaultModel)
          uris))

(def reasoner
  (let [rules (Rule/parseRules (slurp (io/resource "etc/dannet.rules")))]
    (doto (GenericRuleReasoner. rules)
      (.setOWLTranslation true)
      (.setMode GenericRuleReasoner/HYBRID)
      (.setTransitiveClosureCaching true))))

(defn ->sense-label-triples
  "Create label triples for the - otherwise unlabeled - senses of a DanNet `g`."
  [g]
  (->> (for [{:syms [?sense ?label]} (q/run g op/sense-label-targets)]
         [?sense :rdfs/label ?label])
       (into #{})))

(defn ->example-triples
  "Create example triples from a DanNet `g` and the `examples` from 'imports'."
  [g examples]
  (for [[synset lemma] (keys examples)]
    (let [results       (q/run g op/example-targets {'?synset synset
                                                     '?lemma  lemma})
          example-str   (get examples [synset lemma])
          blank-example (symbol (str "_" (name lemma)
                                     "-" (name synset)
                                     "-example"))]
      (apply set/union (for [{:syms [?sense]} results]
                         (when example-str
                           #{[?sense :lexinfo/senseExample blank-example]
                             [blank-example :rdf/value example-str]}))))))

(defn add-imports!
  "Add `imports` from the old DanNet CSV files to a Jena Graph `g`."
  [g imports]
  (let [input    (vals (dissoc imports :examples))
        examples (when-let [raw-examples (:examples imports)]
                   (apply merge (bootstrap/read-triples raw-examples)))]
    (txn/transact-exec g
      (->> (mapcat bootstrap/read-triples input)
           (remove nil?)
           (reduce aristotle/add g)))

    ;; As ->example-triples needs to read the graph to create
    ;; triples, it must be done after the write transaction.
    ;; Clojure's laziness also has to be accounted for.
    (let [example-triples (doall (->example-triples g examples))]
      (txn/transact-exec g
        (aristotle/add g example-triples)))

    ;; Senses are unlabeled in the raw dataset and also need to query the graph
    ;; to steal labels from the words they are senses of.
    (let [sense-label-triples (doall (->sense-label-triples g))]
      (txn/transact-exec g
        (aristotle/add g sense-label-triples)))

    g))

(defn ->dannet
  "Create a Jena Graph from DanNet 2.2 imports based on the options:

    :imports     - DanNet CSV imports (kvs of ->triple fns and table data).
    :db-type     - Both :tdb1 and :tdb2 are supported.
    :db-path     - If supplied, the data is persisted inside TDB.
    :schema-uris - A collection of URIs containing schemas.

   TDB 1 does not require transactions until after the first transaction has
   taken place, while TDB 2 *always* requires transactions when reading from or
   writing to the database.

  The returned graph uses the GWA relations within the framework of Ontolex."
  [& {:keys [imports db-path db-type schema-uris] :as opts}]
  (if db-path
    (let [dataset (case db-type
                    :tdb1 (TDBFactory/createDataset ^String db-path)
                    :tdb2 (TDB2Factory/connectDataset ^String db-path))
          model   (.getDefaultModel ^Dataset dataset)
          graph   (if imports
                    (add-imports! (.getGraph model) imports)
                    (.getGraph model))]
      {:dataset dataset
       :model   model
       :graph   graph})
    (if schema-uris
      (let [{:keys [model]} (->dannet (dissoc opts :schema-uris))
            schema    (->schema-model schema-uris)
            inf-model (ModelFactory/createInfModel reasoner schema model)]
        {:base-model   model
         :schema-model schema
         :model        inf-model
         :graph        (.getGraph inf-model)})
      (let [graph (add-imports! (aristotle/graph :simple) imports)]
        {:model (ModelFactory/createModelForGraph graph)
         :graph graph}))))

(defn add-registry-prefixes!
  "Adds the prefixes from the Aristotle registry to the `model`."
  [model]
  (doseq [[prefix m] (:prefixes arachne.aristotle.registry/*registry*)]
    (.setNsPrefix ^Model model prefix (:arachne.aristotle.registry/= m))))

(defn expanded-model
  "Remove the schemas from the model in the `db` map.

  There is no clear way in Apache Jena (AFAIK) to not mix schemas and data, but
  thankfully basic set operations can be applied, e.g. difference."
  [{:keys [model schema-model] :as db}]
  (.difference model schema-model))

(defn inferred-model
  "Return only the inferred data from the model in the `db` map."
  [{:keys [base-model] :as db}]
  (.difference (expanded-model db) base-model))

(defn export-model!
  "Export the `model` to the file with the given `filename`. Defaults to Turtle.
  The current prefixes in the Aristotle registry are used for the output.

  See: https://jena.apache.org/documentation/io/rdf-output.html"
  [filename model & {:keys [fmt]
                     :or   {fmt RDFFormat/TURTLE_PRETTY}}]
  (txn/transact-exec model
    (add-registry-prefixes! model)
    (RDFDataMgr/write (io/output-stream filename) ^Model model ^RDFFormat fmt)
    (.clearNsPrefixMap ^Model model))
  nil)

;; TODO: currently expects schema-model, base-model keys in db - fix
(defn export!
  "Exports multiple RDF datasets based on the data in the `db` into `dir`."
  ([{:keys [base-model] :as db} dir]
   (export-model! (str dir "dannet-base.ttl") base-model)
   (export-model! (str dir "dannet-expanded.ttl") (expanded-model db))
   (export-model! (str dir "dannet-inferred.ttl") (inferred-model db)))
  ([db]
   (export! db "resources/export/")))

;; TODO: integrate with/copy some functionality from 'arachne.aristotle/add'
(defn add!
  "Add `content` to a `db`. The content can be a variety of things, including
  another DanNet instance."
  [{:keys [model] :as db} content]
  (txn/transact-exec model
    (.add model (if (map? content)
                  (:model content)
                  content))))

(defn synonyms
  "Return synonyms in Graph `g` of the word with the given `lemma`."
  [g lemma]
  (->> (q/run g '[?synonym] op/synonyms {'?lemma lemma})
       (apply concat)
       (remove #{lemma})))

(defn alt-representations
  "Return alternatives in Graph `g` for the word with the given `written-rep`."
  [g written-rep]
  (->> (q/run g '[?alt-rep] op/alt-representations {'?written-rep written-rep})
       (apply concat)
       (remove #{written-rep})))

(defn registers
  "Return all register values found in Graph `g`."
  [g]
  (->> (q/run g '[?register] op/registers)
       (apply concat)
       (sort)))

(defn synset-relations
  [g relation]
  (q/run g
         '[?l1 ?s1 ?relation ?s2 ?l2]
         op/synset-relations
         {'?relation relation}))

(def sym->kw
  {'?synset     :rdf/value
   '?definition :skos/definition
   '?ontotype   :dns/ontologicalType})

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
  (let [k->label (label-lookup g)]
    (->> (q/run g op/synset-search {'?lemma lemma})
         (group-by '?synset)
         (map (fn [[k ms]]
                (let [{:syms [?label ?synset]
                       :as   base} (apply merge-with q/set-merge ms)
                      v (with-meta (-> base
                                       (dissoc '?lemma
                                               '?form
                                               '?word
                                               '?label
                                               '?sense)
                                       (set/rename-keys sym->kw))
                                   {:k->label (assoc k->label
                                                ?synset ?label)})]
                  [k v])))
         (sort-by search-keyfn)
         (into (fop/ordered-map)))))

(comment
  (type (:graph dannet))                                    ; Check graph type

  ;; Create a new in-memory DanNet from the CSV imports with inference.
  (def dannet
    (->dannet
      :imports bootstrap/imports
      :schema-uris schema-uris))

  ;; Create a new in-memory DanNet from the CSV imports (no inference)
  (def dannet (->dannet :imports bootstrap/imports))

  ;; Load an existing TDB DanNet from disk.
  (def dannet (->dannet :db-path "resources/db/tdb1" :db-type :tdb1))
  (def dannet (->dannet :db-path "resources/db/tdb2" :db-type :tdb2))

  ;; Create a new TDB DanNet from the CSV imports.
  (def dannet
    (->dannet
      :imports bootstrap/imports
      :db-path "resources/db/tdb1"
      :db-type :tdb1))
  (def dannet
    (->dannet
      :imports bootstrap/imports
      :db-path "resources/db/tdb2"
      :db-type :tdb2))

  ;; Def everything used below.
  (do
    (def graph (:graph dannet))
    (def model (:model dannet))
    (def dataset (:dataset dannet))

    ;; Wrap the DanNet model with igraph.
    (def ig
      (igraph-jena/make-jena-graph model)))

  ;; Export the contents of the db
  (export! dannet)

  ;; Querying DanNet for various synonyms
  (synonyms graph "vand")
  (synonyms graph "sild")
  (synonyms graph "hoved")
  (synonyms graph "bil")
  (synonyms graph "ord")

  ;; Querying DanNet for alternative written representations.
  (alt-representations graph "mørkets fyrste")
  (alt-representations graph "offentlig transport")
  (alt-representations graph "kaste håndklædet i ringen")

  ;; Checking various synset relations.
  (synset-relations graph :wn/instance_hypernym)
  (synset-relations graph :wn/co_agent_instrument)
  (synset-relations graph :wn/antonym)
  (synset-relations graph :wn/also)

  ;; Also works dataset and graph, despite accessing the model object.
  (txn/transact graph
    (take 10 (igraph/subjects ig)))
  (txn/transact model
    (take 10 (igraph/subjects ig)))
  (txn/transact dataset
    (take 10 (igraph/subjects ig)))

  ;; Look up "citrusfrugt" synset using igraph
  (txn/transact model
    (ig :dn/synset-514))

  ;; Find all hypernyms of a Synset in the graph ("birkes"; note: two paths).
  ;; Laziness and threading macros doesn't work well Jena transactions, so be
  ;; sure to transact database-accessing code while leaving out post-processing.
  (let [hypernym (igraph/transitive-closure :wn/hypernym)]
    (->> (txn/transact model
           (igraph/traverse ig hypernym {} [] [:dn/synset-999]))
         (map #(txn/transact model (ig %)))
         (map :rdfs/label)
         (map first)))

  ;; Test inference of :ontolex/isEvokedBy.
  (q/run graph
         '[:bgp
           [?form :ontolex/writtenRep "vand"]
           [?word :ontolex/canonicalForm ?form]
           [?synset :ontolex/isEvokedBy ?word]])

  (q/only-uris
    (q/run graph
           '[:bgp
             [:dn/word-11007846 ?p ?o]]))

  ;; Combining graph queries and regular Clojure data manipulation:
  ;;   1. Fetch all multi-word expressions in the graph.
  ;;   2. Fetch synonyms where applicable.
  ;;   3. Create a mapping from multi-word expression to synonyms
  (->> '[:bgp
         [?word :rdf/type :ontolex/MultiwordExpression]
         [?word :ontolex/canonicalForm ?form]
         [?form :ontolex/writtenRep ?lemma]]
       (q/run graph '[?lemma])
       (apply concat)
       (map (fn [lemma]
              (when-let [synonyms (not-empty (synonyms graph lemma))]
                [lemma synonyms])))
       (into {}))

  ;; Test retrieval of examples
  (q/run graph op/examples '{?sense :dn/sense-21011843})
  (q/run graph op/examples '{?sense :dn/sense-21011111})

  ;; Memory measurements using clj-memory-meter, available using the :mm alias.
  ;; The JVM must be run with the JVM option '-Djdk.attach.allowAttachSelf'.
  ;; See: https://github.com/clojure-goes-fast/clj-memory-meter#usage
  (require '[clj-memory-meter.core :as mm])
  (mm/measure graph)

  ;; List all register values in db; helpful when extending ->register-triples.
  (registers graph)

  ;; Mark the relevant lemma in all ~38539 example sentences.
  ;; I tried the same query (as SPARQL) in Python's rdflib and it was painfully
  ;; slow, to the point where I wonder how people even use that library...
  (map (fn [[?lemma ?example-str]]
         (let [marked-lemma (str "{" (str/upper-case ?lemma) "}")]
           (str/replace example-str ?lemma marked-lemma)))
       (q/run graph '[?lemma ?example-str] op/examples))
  #_.)
