(ns dk.wordnet.db
  (:require [clojure.java.io :as io]
            [arachne.aristotle :as aristotle]
            [arachne.aristotle.query :as q]
            [ont-app.igraph-jena.core :as igraph-jena]
            [ont-app.igraph.core :as igraph]
            [dk.wordnet.csv :as dn-csv]
            [dk.wordnet.db.queries :as queries])
  (:import [org.apache.jena.riot RDFDataMgr RDFFormat]
           [org.apache.jena.graph Graph]
           [org.apache.jena.tdb2 TDB2Factory]
           [org.apache.jena.rdf.model ModelFactory]
           [org.apache.jena.system Txn]))

(defn ->dannet
  "Create an in-memory Jena database based on the DanNet 2.2 `csv-imports`.
  The returned database uses the new GWA relations rather than the old ones."
  [csv-imports]
  (let [read-triples #(->> (dn-csv/read-triples %1 %2)
                           (remove nil?)
                           (remove dn-csv/unmapped?))]
    (reduce
      (fn [g [row->triples file]]
        (aristotle/add g (read-triples row->triples file)))
      (aristotle/graph :simple)
      (vals csv-imports))))

;; TODO: exported resources need to be namespaced
;; TODO: RDFXML export causes OutOfMemoryError - investigate
;; https://jena.apache.org/documentation/io/rdf-output.html
(defn export-db!
  "Export the `db` to the file with the given `filename`."
  [filename db & {:keys [fmt]
                  :or   {fmt RDFFormat/TURTLE_PRETTY}}]
  (RDFDataMgr/write (io/writer filename) ^Graph db ^RDFFormat fmt))

(defn synonyms
  "Return synonyms in `db` of the word with the given `lemma`."
  [db lemma]
  (->> (q/run db '[?synonym] queries/synonyms {'?lemma lemma})
       (apply concat)
       (remove #{lemma})))

(comment
  (def dannet
    (->dannet dn-csv/csv-imports))

  (get (voc/prefix-to-ns) "skos")

  (def ig
    (-> (ModelFactory/createModelForGraph dannet)
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
