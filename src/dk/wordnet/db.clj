(ns dk.wordnet.db
  (:require [clojure.java.io :as io]
            [arachne.aristotle :as aristotle]
            [ont-app.igraph-jena.core :as igraph-jena]
            [ont-app.igraph.core :as igraph]
            [dk.wordnet.csv :as dn-csv]
            [dk.wordnet.db.query :as q])
  (:import [org.apache.jena.riot RDFDataMgr RDFFormat]
           [org.apache.jena.graph Graph]
           [org.apache.jena.tdb TDBFactory]
           [org.apache.jena.rdf.model ModelFactory]))

(defn ->dannet
  "Create a Jena database based on the DanNet 2.2 `csv-imports`. If a `tdb-path`
  is supplied, will make use of a persistent TDB 1 instance.

  The returned database uses the new GWA relations rather than the old ones."
  [& {:keys [csv-imports tdb-path]}]
  (let [imports      (vals csv-imports)
        read-triples #(->> (dn-csv/read-triples %1 %2)
                           (remove nil?)
                           (remove dn-csv/unmapped?))
        add-triples  (fn [g [row->triples file]]
                       (aristotle/add g (read-triples row->triples file)))]
    (if tdb-path
      (let [dataset (TDBFactory/createDataset ^String tdb-path)
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
  "Return synonyms in `db` of the word with the given `lemma`."
  [db lemma]
  (->> (q/run db '[?synonym] q/synonyms {'?lemma lemma})
       (apply concat)
       (remove #{lemma})))

(comment
  ;; Load an existing TDB DanNet from disk.
  (def dannet
    (->dannet :tdb-path "resources/tdb"))

  ;; Create a new TDB1 DanNet from the CSV imports.
  (def dannet
    (->dannet
      :csv-imports dn-csv/csv-imports
      :tdb-path "resources/tdb"))

  ;; Create a new in-memory DanNet from the CSV imports.
  (def dannet
    (->dannet :csv-imports dn-csv/csv-imports))

  ;; Check the class of the DanNet graph.
  (type (:graph dannet))

  ;; Wrap the DanNet model with igraph.
  (def ig
    (-> (:model dannet)
        (igraph-jena/make-jena-graph)))

  ;; Export the contents of the db
  (export-db! "resources/dannet.ttl" dannet)

  ;; Querying DanNet for various synonyms
  (synonyms dannet "vand")
  (synonyms dannet "sild")
  (synonyms dannet "hoved")
  (synonyms dannet "bil")

  (take 30 (igraph/subjects ig))

  ;; Look up "citron" using igraph
  (-> (ig :dn/word-11007846)
      (igraph/flatten-description))

  ;; Find all hypernyms of a Synset in the graph ("birkes").
  ;; Note: contains two separate paths!
  (let [hypernym (igraph/transitive-closure :wn/hypernym)]
    (->> (igraph/traverse ig hypernym {} [] [:dn/synset-999])
         (map ig)
         (map :rdfs/label)
         (map first)))
  #_.)
