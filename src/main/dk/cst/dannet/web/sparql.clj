(ns dk.cst.dannet.web.sparql
  "Read-only SPARQL endpoint for DanNet with comprehensive safety measures."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [io.pedestal.interceptor :as interceptor]
            [dk.cst.dannet.db.transaction :as tx]
            [dk.cst.dannet.prefix :as prefix]
            [ont-app.vocabulary.core :as voc]
            [ont-app.vocabulary.lstr :as lstr])
  (:import [org.apache.jena.query Query QueryFactory QueryExecutionFactory
                                  ResultSet]
           [org.apache.jena.rdf.model Model RDFNode]
           [org.apache.jena.riot ResultSetMgr]
           [org.apache.jena.riot.resultset ResultSetLang]
           [org.apache.jena.update UpdateFactory]
           [java.io ByteArrayOutputStream]))



;; TODO: clean up this namespace
;; TODO: return the result-set as the content body and convert it in the response-body-ic

;; Configuration constants
(def ^:const default-timeout-ms 10000)
(def ^:const default-limit 100)
(def ^:const max-query-length 5000)

;; Content type mappings for W3C SPARQL Protocol compliance
(def sparql-result-formats
  {"application/sparql-results+json" :json
   "application/json"                :json
   "application/sparql-results+xml"  :xml
   "application/xml"                 :xml
   "text/xml"                        :xml
   "text/csv"                        :csv
   "text/tab-separated-values"       :tsv})

(def rdf-result-formats
  {"text/turtle"           :turtle
   "application/rdf+xml"   :rdf-xml
   "text/n3"               :n3
   "application/n-triples" :n-triples
   "application/ld+json"   :json-ld})

(defn safe-query-type?
  "Check if query type is safe for read-only execution."
  [query-obj]
  (or (.isSelectType query-obj)
      (.isAskType query-obj)
      (.isConstructType query-obj)
      (.isDescribeType query-obj)))

(defn validate-sparql-query
  "Validates that a SPARQL string is safe to execute.
  
  Uses existing DanNet patterns with voc/prepend-prefix-declarations.
  Returns validated Query object or throws ex-info on validation failure."
  [sparql-string]
  (when (> (count sparql-string) max-query-length)
    (throw (ex-info "Query too long"
                    {:type   :query-too-long
                     :max    max-query-length
                     :actual (count sparql-string)})))

  (try
    ;; Use QueryFactory with prefix declarations like the rest of DanNet
    (let [query-with-prefixes (voc/prepend-prefix-declarations sparql-string)
          query               (QueryFactory/create ^String query-with-prefixes)]
      (when-not (safe-query-type? query)
        (throw (ex-info "Only SELECT, ASK, CONSTRUCT, and DESCRIBE queries allowed"
                        {:type       :unsafe-query-type
                         :query-type (.getQueryType query)})))
      query)
    (catch Exception e
      ;; Check if this might be an UPDATE query by trying to parse as such
      (if (try
            (UpdateFactory/create sparql-string)
            true
            (catch Exception _ false))
        (throw (ex-info "UPDATE queries not allowed"
                        {:type :update-not-allowed}))
        (throw (ex-info "Query parsing failed"
                        {:type  :parse-error
                         :cause (.getMessage e)}
                        e))))))

(defn limit-results!
  "Apply result limit to SELECT queries to prevent resource exhaustion."
  [query-obj max-results]
  (when (and (.isSelectType query-obj)
             (or (nil? (.getLimit query-obj))
                 (> (.getLimit query-obj) max-results)))
    (.setLimit query-obj max-results))
  query-obj)

(defn apply-timeout
  "Apply timeout to query execution context.
  
  Note: Query timeout will be handled at execution level via QueryExecutionFactory."
  [query-obj timeout-ms]
  ;; In this Jena version, we can't set timeout on Query directly
  ;; Timeout handling should be done at QueryExecution level
  query-obj)

(defn rdf-node->value
  "Convert RDFNode to simple Clojure value following DanNet conventions.
  
  URIs become keywords like :rdfs/domain, language literals become LangStr objects."
  [^RDFNode node]
  (cond
    (.isLiteral node)
    (let [literal (.asLiteral node)
          value   (.getString literal)
          lang    (.getLanguage literal)]
      (if (and lang (not (str/blank? lang)))
        ;; Return language-tagged literal as LangStr (following DanNet pattern)
        (lstr/->LangStr value lang)
        ;; Simple literal value
        value))

    (.isResource node)
    (let [uri (.getURI (.asResource node))]
      ;; Convert URI to keyword using voc/keyword-for (like :rdfs/domain)
      (try
        (voc/keyword-for uri)
        (catch Exception _
          ;; Fallback to angle-bracket format for unknown URIs
          (str "<" uri ">"))))

    :else
    ;; Blank nodes as strings
    (.toString node)))

(defn query-solution->map
  "Convert Jena QuerySolution to Clojure map.
  
  Variable names become symbols with ? prefix, preserving SPARQL syntax."
  [var-names solution]
  (reduce (fn [acc var-name]
            (if-let [node (.get solution var-name)]
              (assoc acc (symbol (str "?" var-name)) (rdf-node->value node))
              acc))
          {}
          var-names))

(defn format-select-results
  "Format SELECT query results based on requested format."
  [result-set format]
  (case format
    :json
    ;; https://www.w3.org/TR/sparql11-results-json/
    (let [out (ByteArrayOutputStream.)]
      (ResultSetMgr/write out ^ResultSet result-set ResultSetLang/RS_JSON)
      (.toString out "UTF-8"))

    ;; else
    "UNSUPPORTED FORMAT"))

(defn format-ask-results
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

(defn format-construct-describe-results
  "Format CONSTRUCT/DESCRIBE query results based on requested format."
  [model format]
  (let [out (ByteArrayOutputStream.)]
    (case format
      :turtle
      (.write model out "TURTLE")

      :rdf-xml
      (.write model out "RDF/XML")

      :n3
      (.write model out "N3")

      :n-triples
      (.write model out "N-TRIPLES")

      :json-ld
      (.write model out "JSON-LD"))
    (.toString out "UTF-8")))

(defn execute-sparql-query
  "Execute validated SPARQL query-obj against model with safety constraints."
  [^Model model ^Query query-obj timeout max-results]
  (tx/transact-read model
                    (let [query (-> query-obj
                                    (apply-timeout timeout) ; TODO: this is currently noop
                                    (limit-results! max-results))
                          qexec (QueryExecutionFactory/create query-obj model)]
                      (try
                        (cond
                          (.isSelectType query)
                          {:sparql-type   :select
                           :sparql-result (.materialise (.execSelect qexec))}

                          (.isAskType query)
                          {:sparql-type   :ask
                           :sparql-result (.execAsk qexec)}

                          (.isConstructType query)
                          {:sparql-type   :construct
                           :sparql-result (.execConstruct qexec)}

                          (.isDescribeType query)
                          {:sparql-type   :describe
                           :sparql-result (.execDescribe qexec)})
                        (finally
                          (.close qexec))))))

(defn determine-response-format
  "Determine response format from Accept header and query type."
  [accept-header query-type]
  (let [accepts        (or accept-header "application/sparql-results+json")
        ;; Parse Accept header for format preference (simplified)
        preferred-type (first (str/split accepts #","))]
    (case query-type
      (:select :ask)
      (get sparql-result-formats preferred-type :json)

      (:construct :describe)
      (get rdf-result-formats preferred-type :turtle))))

(defn get-content-type
  "Get HTTP content type for format."
  [format query-type]
  (case [query-type format]
    [:select :json] "application/sparql-results+json"
    [:select :xml] "application/sparql-results+xml"
    [:select :csv] "text/csv"
    [:select :tsv] "text/tab-separated-values"
    [:ask :json] "application/sparql-results+json"
    [:ask :xml] "application/sparql-results+xml"
    [:construct :turtle] "text/turtle"
    [:construct :rdf-xml] "application/rdf+xml"
    [:construct :n3] "text/n3"
    [:construct :n-triples] "application/n-triples"
    [:construct :json-ld] "application/ld+json"
    [:describe :turtle] "text/turtle"
    [:describe :rdf-xml] "application/rdf+xml"
    [:describe :n3] "text/n3"
    [:describe :n-triples] "application/n-triples"
    [:describe :json-ld] "application/ld+json"
    "application/sparql-results+json"))

(defn sparql-options-handler
  "Handle CORS preflight requests."
  [_request]
  {:status  200
   :headers {"Access-Control-Allow-Origin"  "*"
             "Access-Control-Allow-Methods" "GET, POST, OPTIONS"
             "Access-Control-Allow-Headers" "Content-Type, Accept"
             "Access-Control-Max-Age"       "86400"}
   :body    ""})

(comment
  ;; Example usage for testing

  ;; Test query validation
  (validate-sparql-query "SELECT * WHERE { ?s ?p ?o } LIMIT 10")

  ;; Test unsafe query rejection
  (try
    (validate-sparql-query "INSERT DATA { <http://example.org/s> <http://example.org/p> <http://example.org/o> }")
    (catch Exception e (ex-data e)))

  ;; Test with real model (requires data to be loaded)
  (let [db    @dk.cst.dannet.web.resources/db
        model (:model db)
        query (validate-sparql-query "SELECT ?s ?p ?o WHERE { ?s ?p ?o } LIMIT 5")]
    (execute-sparql-query model query 10000 100))

  ;; Test simple Danish word query - variables become symbols
  (let [db    @dk.cst.dannet.web.resources/db
        model (:model db)
        query (validate-sparql-query "
          SELECT ?word WHERE { 
            ?form ontolex:writtenRep ?word .
            FILTER(lang(?word) = 'da')
          } LIMIT 5")]
    (execute-sparql-query model query 10000 100))
  ;; => [{?word #voc/lstr "komme noget til@da"} ...]

  #_.)
