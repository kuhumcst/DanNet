(ns dk.wordnet.db
  "Represent DanNet as an in-memory graph or within a persisted database (TDB)."
  (:require [clojure.java.io :as io]
            [arachne.aristotle :as aristotle]
            [ont-app.igraph-jena.core :as igraph-jena]
            [ont-app.igraph.core :as igraph]
            [dk.wordnet.csv :as dn-csv]
            [dk.wordnet.db.query :as q])
  (:import [org.apache.jena.riot RDFDataMgr RDFFormat]
           [org.apache.jena.ontology OntModel OntModelSpec]
           [org.apache.jena.graph Graph]
           [org.apache.jena.tdb TDBFactory]
           [org.apache.jena.tdb2 TDB2Factory]
           [org.apache.jena.rdf.model ModelFactory]))

(def owl-uris
  "URIs where relevant OWL schemas can be fetched."
  (for [{:keys [alt uri]} (vals q/schemas)]
    (or alt uri)))

;; TODO: is OWL_MEM_MICRO_RULE_INF sufficient?
;; TODO: inference for TDB models (need to reify a ModelMaker)
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
        spec        (doto (OntModelSpec. OntModelSpec/OWL_MEM_MICRO_RULE_INF)
                      (.setBaseModelMaker model-maker)
                      (.setImportModelMaker model-maker))]
    (prepare-fn
      (reduce (fn [model owl-uri]
                (.read model owl-uri))
              (ModelFactory/createOntologyModel spec base)
              owl-uris))))

(defn ->dannet
  "Create a Jena graph from DanNet 2.2 from an options map:

    :imports  - DanNet CSV imports (kvs of ->triple fns and table data).
    :db-type  - Both :tdb1 and :tdb2 are supported.
    :db-path  - If supplied, the data is persisted inside TDB.
    :owl-uris - A collection of URIs containing OWL schemas.

   TDB 1 does not require transactions until after the first transaction has
   taken place, while TDB 2 *always* requires transactions when reading from or
   writing to the database.

  The returned graph uses the new GWA relations rather than the old ones."
  [& {:keys [imports db-path db-type owl-uris]
      :or   {db-type :graph-mem}}]
  (let [imports      (vals imports)
        read-triples #(->> (dn-csv/read-triples %1 %2)
                           (remove nil?)
                           (remove dn-csv/unmapped?))
        add-triples  (fn [g [row->triples file]]
                       (if (= db-type :tdb2)
                         (q/transact g
                           (aristotle/add g (read-triples row->triples file)))
                         (aristotle/add g (read-triples row->triples file))))]
    (if db-path
      (let [dataset (case db-type
                      :tdb1 (TDBFactory/createDataset ^String db-path)
                      :tdb2 (TDB2Factory/connectDataset ^String db-path))
            model   (.getDefaultModel dataset)
            graph   (reduce add-triples (.getGraph model) imports)]
        {:dataset dataset
         :model   model
         :graph   graph})
      (if owl-uris
        (let [model (owl-model owl-uris)
              graph (reduce add-triples (.getGraph model) imports)]
          {:model model
           :graph graph})
        (let [graph (reduce add-triples (aristotle/graph :simple) imports)
              model (ModelFactory/createModelForGraph graph)]
          {:model model
           :graph graph})))))

;; TODO: exported resources need to be namespaced
;; TODO: RDFXML export causes OutOfMemoryError - investigate
;; https://jena.apache.org/documentation/io/rdf-output.html
(defn export-db!
  "Export the `db` to the file with the given `filename`."
  [filename {:keys [graph] :as db} & {:keys [fmt]
                                      :or   {fmt RDFFormat/TURTLE_PRETTY}}]
  (RDFDataMgr/write (io/output-stream filename) ^Graph graph ^RDFFormat fmt))

(defn synonyms
  "Return synonyms in Graph `g` of the word with the given `lemma`."
  [g lemma]
  (->> (q/run g '[?synonym] q/synonyms {'?lemma lemma})
       (apply concat)
       (remove #{lemma})))

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
  (def dannet (->dannet :db-path "resources/tdb1" :db-type :tdb1))
  (def dannet (->dannet :db-path "resources/tdb2" :db-type :tdb2))

  ;; Create a new TDB DanNet from the CSV imports.
  (def dannet
    (->dannet
      :imports dn-csv/imports
      :db-path "resources/tdb1"
      :db-type :tdb1))
  (def dannet
    (->dannet
      :imports dn-csv/imports
      :db-path "resources/tdb2"
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

  ;; Working replacement for igraph/subjects
  (defn subjects
    [jena-model]
    (->> (.listSubjects jena-model)
         (iterator-seq)
         (map ont-app.igraph-jena.core/interpret-binding-element)
         #_(lazy-seq)))

  ;; Also works dataset and graph, despite accessing the model object.
  (q/transact graph
    (take 10 (subjects model)))
  (q/transact model
    (take 10 (subjects model)))
  (q/transact dataset
    (take 10 (subjects model)))

  ;; TODO: doesn't work, TDBTransactionException: Not in a transaction
  ;; https://github.com/ont-app/igraph-jena/issues/2
  (q/transact model
    (take 10 (igraph/subjects ig)))

  ;; TODO: super slow with inferencing - fix
  ;; Look up "citron" using igraph
  (q/transact model
    (-> (ig :dn/word-11007846)
        (igraph/flatten-description)))

  ;; Find all hypernyms of a Synset in the graph ("birkes"; note: two paths).
  ;; Laziness and threading macros doesn't work well Jena transactions, so be
  ;; sure to transact database-accessing code while leaving out post-processing.
  (let [hypernym (igraph/transitive-closure :wn/hypernym)]
    (->> (q/transact model
           (->> (igraph/traverse ig hypernym {} [] [:dn/synset-999])))
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

  ;; Memory measurements using clj-memory-meter, available using the :mm alias.
  ;; The JVM must be run with the JVM option '-Djdk.attach.allowAttachSelf'.
  ;; See: https://github.com/clojure-goes-fast/clj-memory-meter#usage
  (require '[clj-memory-meter.core :as mm])
  (mm/measure graph)
  #_.)
