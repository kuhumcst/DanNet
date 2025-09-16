(ns dk.cst.dannet.web.sparql
  "Read-only SPARQL endpoint for DanNet with comprehensive safety measures."
  (:require [dk.cst.dannet.db.transaction :as tx]
            [ont-app.vocabulary.core :as voc])
  (:import [java.util.concurrent TimeUnit]
           [org.apache.jena.query Query QueryExecution QueryFactory QueryExecutionFactory]
           [org.apache.jena.rdf.model Model]
           [org.apache.jena.update UpdateFactory]))

;; TODO: some queries seem to run quite slow, I guess they are not really limited?

(def ^:const max-timeout 10000)
(def ^:const max-results-limit 100)
(def ^:const max-query-length 5000)

(defn read-only?
  "Check if `query-obj` is safe for read-only execution."
  [query-obj]
  (or (.isSelectType query-obj)
      (.isAskType query-obj)
      (.isConstructType query-obj)
      (.isDescribeType query-obj)))

(defn validate
  "Validate that a SPARQL `query` is safe to execute, known prefixes prepended."
  [query]
  (when (> (count query) max-query-length)
    (throw (ex-info "Query too long"
                    {:type   :query-too-long
                     :max    max-query-length
                     :actual (count query)})))

  (try
    (let [query-with-prefixes (voc/prepend-prefix-declarations query)
          query-obj           (QueryFactory/create ^String query-with-prefixes)]
      (when-not (read-only? query-obj)
        (throw (ex-info "Only SELECT/ASK/CONSTRUCT/DESCRIBE queries allowed!"
                        {:type       :unsafe-query-type
                         :query-type (.queryType query-obj)})))
      query-obj)
    (catch Exception e
      ;; Check if this might be an UPDATE query by trying to parse as such
      (if (try
            (UpdateFactory/create query)
            true
            (catch Exception _ false))
        (throw (ex-info "UPDATE queries not allowed"
                        {:type :update-not-allowed}))
        (throw (ex-info "Query parsing failed"
                        {:type  :parse-error
                         :cause (.getMessage e)}
                        e))))))

(defn limit-results!
  "Apply `results-limit` to SELECT `query-obj` to prevent resource exhaustion."
  [^Query query-obj results-limit]
  (when (and (.isSelectType query-obj)
             (or (nil? (.getLimit query-obj))
                 (> (.getLimit query-obj) results-limit)))
    (.setLimit query-obj results-limit))
  query-obj)

;; TODO: eventually use something like this for formatting ASK results
#_(defn format-ask-results
    "Format ASK query results based on requested format."
    [result format]
    (case format
      :json
      {:boolean result}

      :xml
      (str "<?xml version=\"1.0\"?>\n"
           "<sparql xmlns=\"http://www.w3.org/2005/sparql-results#\">\n"
           "  <head></head>\n"
           "  <boolean>" result "</boolean>\n"
           "</sparql>\n")))

(defn execute
  "Execute validated SPARQL `query-obj` against `model` with safety constraints
  by applying a query `timeout` and a `results-limit`."
  [^Model model ^Query query-obj timeout results-limit]
  (tx/transact-read model
    (let [query (limit-results! query-obj results-limit)
          qexec (doto ^QueryExecution (QueryExecutionFactory/create query-obj model)
                  (.setTimeout ^Long timeout TimeUnit/MILLISECONDS))]
      (try
        (cond
          (.isSelectType query)
          {:sparql-type   :select
           :sparql-result (.materialise (.execSelect qexec))}

          (.isAskType query)
          {:sparql-type   :ask
           :sparql-result (.materialise (.execAsk qexec))}

          (.isConstructType query)
          {:sparql-type   :construct
           :sparql-result (.execConstruct qexec)}

          (.isDescribeType query)
          {:sparql-type   :describe
           :sparql-result (.execDescribe qexec)})
        (finally
          (.close qexec))))))

(comment
  ;; Test query validation
  (validate "SELECT * WHERE { ?s ?p ?o } LIMIT 10")         ; should succeed
  (validate "ELECT * WHERE { ?s ?p ?o } LIMIT 10")          ; should fail

  ;; Test unsafe query rejection
  (try
    (validate "INSERT DATA { <http://example.org/s> <http://example.org/p> <http://example.org/o> }")
    #_(catch Exception e (ex-data e)))

  ;; Test with real model (requires data to be loaded)
  (let [db    @dk.cst.dannet.web.resources/db
        model (:model db)
        query (validate "SELECT ?s ?p ?o WHERE { ?s ?p ?o } LIMIT 5")]
    (-> (execute model query 10000 100)
        (dk.cst.dannet.web.resources/json-body-fn)))

  ;; Test simple Danish word query - variables become symbols
  (let [db    @dk.cst.dannet.web.resources/db
        model (:model db)
        query (validate "
          SELECT ?word WHERE {
            ?form ontolex:writtenRep ?word .
            FILTER(lang(?word) = 'da')
          } LIMIT 5")]
    (-> (execute model query 10000 100)
        (dk.cst.dannet.web.resources/json-body-fn)))
  #_.)
