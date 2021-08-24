(ns dk.wordnet.db
  "Represent DanNet as an in-memory graph or within a persisted database (TDB)."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [arachne.aristotle :as aristotle]
            [ont-app.igraph-jena.core :as igraph-jena]
            [ont-app.igraph.core :as igraph]
            [dk.wordnet.csv :as dn-csv]
            [dk.wordnet.query :as q])
  (:import [org.apache.jena.riot RDFDataMgr RDFFormat]
           [org.apache.jena.ontology OntModel OntModelSpec ProfileRegistry]
           [org.apache.jena.tdb TDBFactory]
           [org.apache.jena.tdb2 TDB2Factory]
           [org.apache.jena.rdf.model ModelFactory Model]
           [org.apache.jena.query Dataset]
           [org.apache.jena.reasoner ReasonerFactory]
           [org.apache.jena.reasoner.rulesys GenericRuleReasoner Rule]))

(def owl-uris
  "URIs where relevant OWL schemas can be fetched."
  (for [{:keys [alt uri]} (vals q/schemas)]
    (or alt uri)))

(def reasoner-factory
  "A reusable ReasonerFactory implementation which contains an instance of
  GenericRuleReasoner that applies a custom set of inference rules for DanNet."
  (let [rules (Rule/parseRules (slurp (io/resource "etc/dannet.rules")))]
    (reify ReasonerFactory
      (create [this configuration]
        (doto (GenericRuleReasoner. rules this)
          (.setOWLTranslation true)
          (.setMode GenericRuleReasoner/HYBRID)
          (.setTransitiveClosureCaching true)))
      (getCapabilities [this] nil)                          ; TODO: implement
      (getURI [this] "http://wordnet.dk/reasoners/DanNetReasoner"))))

;; TODO: the importModelMaker is here and redefined in the owl-model fn...?
(defn ->ont-model-spec
  "Create a new instance of an OntModelSpec using a custom DanNet reasoner."
  []
  (OntModelSpec.
    (ModelFactory/createMemModelMaker)
    nil
    reasoner-factory
    ProfileRegistry/OWL_LANG))

;; According to Dave Reynolds from the Apache Jena team, calling '.prepare' will
;; only compute the forward reasoning in advance, while the backward reasoning
;; will still run on-demand. In order to materialize every inferred triple,
;; in principle _EVERY_ triple should be queried in advance.
;;
;; An alternative to that is calling each expected query type in advance in
;; order to clear a path for future queries of the same type.
(defn owl-model
  "Create an OntModel with inference capabilities based on provided `owl-uris`.
  If needed, a `prepare-fn` may also be provided to post-process the model."
  [owl-uris & [prepare-fn]]
  (let [prepare-fn  (or prepare-fn #(doto ^OntModel % (.prepare)))
        model-maker (ModelFactory/createMemModelMaker)
        base        (.createDefaultModel model-maker)
        spec        (doto (->ont-model-spec)
                      (.setBaseModelMaker model-maker)
                      (.setImportModelMaker model-maker))]
    (prepare-fn
      (reduce (fn [model owl-uri]
                (.read model owl-uri))
              (ModelFactory/createOntologyModel spec base)
              owl-uris))))

(defn ->usage-triples
  "Create usage triples from a DanNet `g` and the `usages` from 'imports'."
  [g usages]
  (for [[synset lemma] (keys usages)]
    (let [results     (q/run g q/usage-targets {'?synset synset '?lemma lemma})
          usage-str   (get usages [synset lemma])
          blank-usage (symbol (str "_" (name lemma)
                                   "-" (name synset)
                                   "-usage"))]
      (apply set/union (for [{:syms [?sense]} results]
                         (when ?sense
                           #{[?sense :ontolex/usage blank-usage]
                             [blank-usage :rdf/value usage-str]}))))))

(defn add-imports!
  "Add `imports` from the old DanNet CSV files to a Jena Graph `g`."
  [g imports]
  (let [input  (vals (dissoc imports :usages))
        usages (when-let [raw-usages (:usages imports)]
                 (apply merge (dn-csv/read-triples raw-usages)))]
    (q/transact-exec g
      (->> (mapcat dn-csv/read-triples input)
           (remove dn-csv/unmapped?)
           (remove nil?)
           (reduce aristotle/add g)))

    ;; As ->usage-triples needs to read the graph to create
    ;; triples, it must be done after the write transaction.
    ;; Clojure's laziness also has to be accounted for.
    (let [usage-triples (doall (->usage-triples g usages))]
      (q/transact-exec g
        (aristotle/add g usage-triples)))

    g))

(defn ->dannet
  "Create a Jena Graph from DanNet 2.2 imports based on the options:

    :imports  - DanNet CSV imports (kvs of ->triple fns and table data).
    :db-type  - Both :tdb1 and :tdb2 are supported.
    :db-path  - If supplied, the data is persisted inside TDB.
    :owl-uris - A collection of URIs containing OWL schemas.

   TDB 1 does not require transactions until after the first transaction has
   taken place, while TDB 2 *always* requires transactions when reading from or
   writing to the database.

  The returned graph uses the GWA relations within the framework of Ontolex."
  [& {:keys [imports db-path db-type owl-uris]}]
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
    (if owl-uris
      (let [model (owl-model owl-uris)
            graph (add-imports! (.getGraph model) imports)]
        {:model model
         :graph graph})
      (let [graph (add-imports! (aristotle/graph :simple) imports)
            model (ModelFactory/createModelForGraph graph)]
        {:model model
         :graph graph}))))

(defn add-registry-prefixes!
  "Adds the prefixes from the Aristotle registry to the `model`."
  [model]
  (doseq [[prefix m] (:prefixes arachne.aristotle.registry/*registry*)]
    (.setNsPrefix ^Model model prefix (:arachne.aristotle.registry/= m))))

(defn export-db!
  "Export the `db` to the file with the given `filename`. Defaults to Turtle.
  The current prefixes in the Aristotle registry are used for the output.

  See: https://jena.apache.org/documentation/io/rdf-output.html"
  [filename {:keys [model] :as db} & {:keys [fmt]
                                      :or   {fmt RDFFormat/TURTLE_PRETTY}}]
  (q/transact-exec model
    (add-registry-prefixes! model)
    (RDFDataMgr/write (io/output-stream filename) ^Model model ^RDFFormat fmt)
    (.clearNsPrefixMap ^Model model)))

;; TODO: integrate with/copy some functionality from 'arachne.aristotle/add'
(defn add!
  "Add `content` to a `db`. The content can be a variety of things, including
  another DanNet instance."
  [{:keys [model] :as db} content]
  (q/transact-exec model
    (.add model (if (map? content)
                  (:model content)
                  content))))

(defn synonyms
  "Return synonyms in Graph `g` of the word with the given `lemma`."
  [g lemma]
  (->> (q/run g '[?synonym] q/synonyms {'?lemma lemma})
       (apply concat)
       (remove #{lemma})))

(defn registers
  "Return all register values found in Graph `g`."
  [g]
  (->> (q/run g '[?register] q/registers)
       (apply concat)
       (sort)))

(comment
  (type (:graph dannet))                                    ; Check graph type

  ;; Create a new in-memory DanNet from the CSV imports with OWL inference.
  (def dannet
    (->dannet
      :imports dn-csv/imports
      :owl-uris owl-uris))

  ;; Create a new in-memory DanNet from the CSV imports (no inference)
  (def dannet (->dannet :imports dn-csv/imports))

  ;; Load an existing TDB DanNet from disk.
  (def dannet (->dannet :db-path "resources/db/tdb1" :db-type :tdb1))
  (def dannet (->dannet :db-path "resources/db/tdb2" :db-type :tdb2))

  ;; Create a new TDB DanNet from the CSV imports.
  (def dannet
    (->dannet
      :imports dn-csv/imports
      :db-path "resources/db/tdb1"
      :db-type :tdb1))
  (def dannet
    (->dannet
      :imports dn-csv/imports
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
  (export-db! "resources/dannet.ttl" dannet)

  ;; Querying DanNet for various synonyms
  (synonyms graph "vand")
  (synonyms graph "sild")
  (synonyms graph "hoved")
  (synonyms graph "bil")
  (synonyms graph "ord")

  ;; Also works dataset and graph, despite accessing the model object.
  (q/transact graph
    (take 10 (igraph/subjects ig)))
  (q/transact model
    (take 10 (igraph/subjects ig)))
  (q/transact dataset
    (take 10 (igraph/subjects ig)))

  ;; Look up "citron" using igraph
  (q/transact model
    (-> (ig :dn/word-11007846)
        (igraph/flatten-description)))

  ;; Find all hypernyms of a Synset in the graph ("birkes"; note: two paths).
  ;; Laziness and threading macros doesn't work well Jena transactions, so be
  ;; sure to transact database-accessing code while leaving out post-processing.
  (let [hypernym (igraph/transitive-closure :wn/hypernym)]
    (->> (q/transact model
           (igraph/traverse ig hypernym {} [] [:dn/synset-999]))
         (map #(q/transact model (ig %)))
         (map :rdfs/label)
         (map first)))

  ;; Test inference of :ontolex/isEvokedBy.
  (q/run graph
         '[:bgp
           [?form :ontolex/writtenRep "vand"]
           [?word :ontolex/canonicalForm ?form]
           [?synset :ontolex/isEvokedBy ?word]])

  ;; Running this query caches most of the inferred relations. Takes ~10 minutes
  ;; on my Macbook with 'OntModelSpec/OWL_MEM_MICRO_RULE_INF', but after that
  ;; other queries all seem to run fast.
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

  ;; These triples were added as OWL inferences (excluding anonymous objects).
  ;; They are pretty much all redundant, solely existing as links to other
  ;; ontologies, e.g. lemon and semowl, or as meaningless roots like :owl/Thing.
  #_#{{?p :lexinfo/morphosyntacticProperty, ?o "Noun"}
      {?p :rdf/type, ?o :ontolex/LexicalEntry}
      {?p :ontolex/lexicalForm, ?o :dn/form-11007846-citron}
      {?p :lemon/property, ?o "Noun"}
      {?p :rdf/type, ?o :owl/Thing}
      {?p :rdf/type, ?o :semowl/Expression}
      {?p :rdf/type, ?o :lemon/LemonElement}
      {?p :rdf/type, ?o :rdfs/Resource}}

  ;; Test retrieval of usages
  (q/run graph q/usages '{?sense :dn/sense-21011843})
  (q/run graph q/usages '{?sense :dn/sense-21011111})

  ;; Memory measurements using clj-memory-meter, available using the :mm alias.
  ;; The JVM must be run with the JVM option '-Djdk.attach.allowAttachSelf'.
  ;; See: https://github.com/clojure-goes-fast/clj-memory-meter#usage
  (require '[clj-memory-meter.core :as mm])
  (mm/measure graph)

  ;; List all register values in db; helpful when extending ->register-triples.
  (registers graph)
  #_.)
