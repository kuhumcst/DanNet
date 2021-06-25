(ns dk.wordnet.db
  "Represent DanNet as an in-memory graph or within a persisted database (TDB)."
  (:require [clojure.java.io :as io]
            [arachne.aristotle :as aristotle]
            [ont-app.igraph-jena.core :as igraph-jena]
            [ont-app.igraph.core :as igraph]
            [dk.wordnet.csv :as dn-csv]
            [dk.wordnet.db.query :as q])
  (:import [org.apache.jena.riot RDFDataMgr RDFFormat]
           [org.apache.jena.graph Graph]
           [org.apache.jena.tdb TDBFactory]
           [org.apache.jena.tdb2 TDB2Factory]
           [org.apache.jena.rdf.model ModelFactory]))

(defn ->dannet
  "Create a Jena graph from DanNet 2.2 `imports`, a `db-type`, and a `db-path`.

   The `db-path` is optional. If supplied, the data is persisted inside TDB.
   Both :tdb1 and :tdb2 are supported. TDB 1 does not require transactions until
   after the first transaction has taken place, while TDB 2 *always* requires
   transactions when reading from or writing to the database.

  The returned graph uses the new GWA relations rather than the old ones."
  [& {:keys [imports db-path db-type]
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
      (let [graph (reduce add-triples (aristotle/graph :simple) imports)
            model (ModelFactory/createModelForGraph graph)]
        {:model model
         :graph graph}))))

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

  ;; Create a new in-memory DanNet from the CSV imports.
  (def dannet (->dannet :imports dn-csv/csv-imports))

  ;; Load an existing TDB DanNet from disk.
  (def dannet (->dannet :db-path "resources/tdb1" :db-type :tdb1))
  (def dannet (->dannet :db-path "resources/tdb2" :db-type :tdb2))

  ;; Create a new TDB1 DanNet from the CSV imports.
  (def dannet
    (->dannet
      :imports dn-csv/csv-imports
      :db-path "resources/tdb1"
      :db-type :tdb1))
  (def dannet
    (->dannet
      :imports dn-csv/csv-imports
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
  #_.)
