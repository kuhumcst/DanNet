(ns dannet.db.neo4j
  (:require [clojure.java.io :as io]
            [neo4j-clj.core :as neo4j]
            [dannet.io :as dio])
  (:import [java.net URI]))

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
  (let [->docker-path #(str "file:///resources/" %1 "/" %2)
        source        "dannet/rdf"]
    (load-graph! conn "dannet/rdf" (partial ->docker-path "dannet/rdf"))
    (load-graph! conn "wordnet/rdf" (partial ->docker-path "wordnet/rdf")))
  #_.)
