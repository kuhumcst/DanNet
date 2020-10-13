(ns dannet.db.neo4j
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.datafy :refer [datafy]]
            [clojure.core.protocols :as p]
            [clojure.reflect :refer [reflect]]              ; for REPL use
            [neo4j-clj.core :as neo4j]
            [clj-http.client :as client]
            [ubergraph.core :as uber]
            [dannet.io :as dio])
  (:import [java.net URI]
           [org.neo4j.driver QueryRunner]
           [org.neo4j.driver.internal InternalNode
                                      InternalRelationship
                                      InternalPath
                                      InternalRecord
                                      InternalResult]))

(defn fetch-path!
  "Fetch the `path` with a specific serialisation `fmt` in the given db `sess`."
  [fmt sess path]
  (let [query (str "call n10s.rdf.import.fetch(\"" path "\",\"" fmt "\")")]
    (neo4j/execute sess query)
    sess))

(defn import-rdf!
  "Import RDF files into a Neo4J db `sess` from a `source` map."
  [sess {:strs [owl rdf rdfs] :as source}]
  (let [files (->> (concat owl rdf rdfs)
                   (remove (partial re-find #"w3c")))]      ; TODO: don't hardcode
    (reduce (partial fetch-path! "RDF/XML") sess files)))

(defn load-graph!
  "Load an RDF `source` into the Neo4J `db` with `path-fn` conversion."
  [db source path-fn]
  (with-open [session (neo4j/get-session db)]
    (let [folder (io/file (io/resource source))
          source (dio/source-folder folder path-fn)]
      (import-rdf! session source))))

(defn q
  "Alternative to the execute function that doesn't autoconvert results."
  [^QueryRunner sess ^String cypher]
  (.run sess cypher))

(defn- keywordize
  [s]
  (let [[ns k] (str/split s #"__")]
    (if k
      (keyword ns k)
      (keyword s))))

;; Neo4j query results are made Datafiable.
;; The `neo4j-clj.compability/neo4->clj` fn both loses valuable data (e.g. ids)
;; and doesn't convert all types of data (e.g. Path). The fact that the
;; conversion is forced in `neo4j-clj.core/execute` also needlessly cuts the
;; link to the underlying Java objects.
(extend-protocol p/Datafiable
  InternalResult
  (datafy [result]
    (map datafy (doall (iterator-seq result))))

  InternalRecord
  (datafy [record]
    (into {} (for [[k v] (.asMap record)]
               [(keywordize k) (datafy v)])))

  ;; Represent Paths using the Ubergraph EDN representation.
  ;; The results come in order, so specifying start and end nodes is irrelevant.
  InternalPath
  (datafy [path]
    {:nodes          (map datafy (.nodes path))
     :directed-edges (map datafy (.relationships path))})

  ;; Represent Nodes as Ubergraph node vectors.
  InternalNode
  (datafy [node]
    (let [id         (.id node)
          labels     (map keywordize (.labels node))
          properties (into {} (for [[k v] (.asMap node)]
                                [(keywordize k) (datafy v)]))]
      [id (assoc properties
            'labels labels)]))

  ;; Represent Relationships as Ubergraph edge vectors.
  InternalRelationship
  (datafy [relationship]
    (let [id         (.id relationship)
          start      (.startNodeId relationship)
          end        (.endNodeId relationship)
          type       (keywordize (.type relationship))
          properties (into {} (.asMap relationship))]
      [start end (assoc properties
                   'type type
                   'id id)])))

(defn result->graph
  "Create an Ubergraph digraph from the datafied `results` of a Neo4j query."
  [& results]
  (->> (mapcat vals results)
       (map #(if (map? %)
               (uber/edn->ubergraph %)
               %))
       (apply uber/ubergraph false false)))


(comment
  ;; https://github.com/gorillalabs/neo4j-clj
  ;; https://github.com/neo4j-labs/neosemantics

  ;; A quick neo4j database instance launched using Docker.
  ;; Build and run a Docker container from the Dockerfile supplied in neo4j/.
  ;; Note: You must supply the neosemantics JAR in the Dockerfile directory!
  (def conn
    (neo4j/connect (URI. "bolt://localhost:7687")
                   "neo4j"
                   "1234"))

  ;; Neosemantics initialisation step for a completely new database. Run once.
  (do
    (neo4j/with-transaction conn tx
      (neo4j/execute tx
        "CREATE CONSTRAINT n10s_unique_uri
        ON (r:Resource)
        ASSERT r.uri IS UNIQUE"))
    (neo4j/with-transaction conn tx
      (doall (neo4j/execute tx
               "CALL n10s.graphconfig.init()"))))

  ;; Load DanNet and Princeton WordNet.
  ;; Note: The initialisation step must have run beforehand.
  (let [->docker-path #(str "file:///resources/" %1 "/" %2)]
    (load-graph! conn "dannet/rdf" (partial ->docker-path "dannet/rdf"))
    (load-graph! conn "wordnet/rdf" (partial ->docker-path "wordnet/rdf")))

  ;; Match against a subgraph and datafy the result.
  (neo4j/with-transaction conn tx
    (->> "MATCH p=()--()--()--({ns1__lexicalForm: 'computer'})
          RETURN p
          LIMIT 100"
         (q tx)
         (datafy)))

  ;; Create an Ubergraph from a result set and pretty-print it.
  (neo4j/with-transaction conn tx
    (let [res (q tx "MATCH p=(m)-[n]-(o{rdfs__label: 'hoved'})
                     RETURN m, n, o, p
                     LIMIT 100")]
      (->> res
           datafy
           (apply result->graph)
           ;uber/viz-graph)))
           uber/pprint)))

  ;; Accessing the Neo4j HTTP API directly.
  ;; Other than this basic helloe world, the HTTP API is underspecified,
  ;; overcomplicated, and obviously mostly meant for internal use.
  (client/get "http://localhost:7474/" {:accept :json})
  #_.)
