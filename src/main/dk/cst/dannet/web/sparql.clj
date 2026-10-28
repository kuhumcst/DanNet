(ns dk.cst.dannet.web.sparql
  "Read-only SPARQL endpoint for DanNet.

  Provides query validation, safe execution against a Jena model,
  and a result cache that coalesces concurrent identical requests
  (e.g. a classroom of students all running the same query)."
  (:require [clojure.core.cache :as cache]
            [clojure.core.cache.wrapped :as cw]
            [dk.cst.theophrastus :as theo]
            [dk.cst.theophrastus.txn :as txn]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.web.anomaly :as anomaly])
  (:import [java.util.concurrent SynchronousQueue ThreadPoolExecutor TimeUnit]
           [org.apache.jena.query Query QueryCancelledException
                                  QueryExecution]
           [org.apache.jena.rdf.model Model]
           [org.apache.jena.sparql.core Var]
           [org.apache.jena.sparql.engine ResultSetStream]
           [org.apache.jena.sparql.resultset ResultSetMem]
           [org.apache.jena.update UpdateFactory]))

(def ^:const max-timeout 30000)
(def ^:const max-results-limit 100)
(def ^:const max-query-length 5000)

;; ## Hard timeout
;;
;; Jena's built-in query timeout is cooperative: it sets a cancellation flag
;; that the query engine's iterator checks between bindings. This works fine for
;; queries against plain Models or TDB datasets.
;;
;; However, DanNet uses an InfModel backed by a GenericRuleReasoner (OWL, HYBRID
;; mode) for deriving inverse relations and transitive closures. When the query
;; engine asks the InfGraph for the next matching triple, the reasoner can spend
;; an arbitrarily long time in its internal forward-chaining rule engine, and
;; that code never checks the cancellation flag. A single .next() call on the
;; query iterator can block for minutes while the reasoner churns.
;;
;; The QueryExec builder API has the same limitation: its AlarmClock-based
;; timeout still relies on QueryIterator.cancel(), which only takes effect when
;; the iterator yields, i.e. it can't interrupt the reasoner.
;;
;; The solution is a Future-based hard timeout (txn/with-hard-timeout in
;; Theophrastus): we submit the work to a thread pool and call
;; Future.get(timeout). If the deadline passes, the caller gets a
;; TimeoutException immediately. The abandoned thread runs to completion on its
;; own; we intentionally do NOT interrupt it (cancel(false)), because
;; Thread.interrupt() would cause NIO's FileChannel.map() to throw
;; ClosedChannelException, permanently closing the underlying TDB2 file channels
;; and breaking all subsequent requests.
;;
;; Two fixed-size thread pools are used: a larger one for queries against the
;; base model (no inference, fast and predictable) and a smaller one for queries
;; that opt in to inference (where abandoned threads may linger). When all slots
;; in a pool are occupied, new submissions fail fast with a TimeoutException
;; rather than queueing up. The Jena timeout is kept as a belt-and-suspenders
;; measure for the (common) case where the query engine *does* check its flag
;; between bindings.

(defn- ->rejecting-executor
  "A pool of `n` threads with no queue, which rejects a call when all its
  threads are busy."
  [n]
  (ThreadPoolExecutor. n n 0 TimeUnit/MILLISECONDS (SynchronousQueue.)))

(defonce query-executor
  (->rejecting-executor 8))

(defonce inference-query-executor
  (->rejecting-executor 4))

(defn read-only?
  "Check if `query-obj` is safe for read-only execution."
  [query-obj]
  (or (.isSelectType query-obj)
      (.isAskType query-obj)
      (.isConstructType query-obj)
      (.isDescribeType query-obj)))

(defn validate
  "Validate that a SPARQL `query-str` is safe to execute.

  Returns a Jena Query object on success; throw on failure. The query is parsed
  with the DanNet prefixes, so it needs no PREFIX lines for them."
  [query-str]
  (when (> (count query-str) max-query-length)
    (throw (ex-info "Query too long"
                    {:type   :query-too-long
                     :max    max-query-length
                     :actual (count query-str)})))
  (try
    (let [query-obj (theo/query query-str prefix/prefixes)]
      (when-not (read-only? query-obj)
        (throw (ex-info "Only SELECT/ASK/CONSTRUCT/DESCRIBE allowed!"
                        {:type       :unsafe-query-type
                         :query-type (.queryType query-obj)})))
      query-obj)
    (catch Exception e
      (if (try (UpdateFactory/create query-str) true
               (catch Exception _ false))
        (throw (ex-info "UPDATE queries not allowed"
                        {:type :update-not-allowed}))
        (throw (ex-info "Query parsing failed"
                        {:type  :parse-error
                         :cause (.getMessage e)}
                        e))))))

(defn ensure-distinct!
  "Set DISTINCT on SELECT `query-obj` if not already set."
  [^Query query-obj]
  (when (and (.isSelectType query-obj)
             (not (.isDistinct query-obj)))
    (.setDistinct query-obj true))
  query-obj)

(defn limit-results!
  "Apply `results-limit` to SELECT `query-obj` to prevent resource exhaustion."
  [^Query query-obj results-limit]
  (when (and (.isSelectType query-obj)
             (or (= (.getLimit query-obj) Query/NOLIMIT)
                 (> (.getLimit query-obj) results-limit)))
    (.setLimit query-obj results-limit))
  query-obj)

(defn offset-results!
  "Apply `offset` to SELECT `query-obj` when the user hasn't set one already."
  [^Query query-obj offset]
  (when (and (.isSelectType query-obj)
             (pos? offset)
             (= (.getOffset query-obj) Query/NOLIMIT))
    (.setOffset query-obj offset))
  query-obj)

(defn has-user-pagination?
  "Check if `query-obj` contains a user-supplied LIMIT or OFFSET clause."
  [^Query query-obj]
  (and (.isSelectType query-obj)
       (or (not= (.getLimit query-obj) Query/NOLIMIT)
           (not= (.getOffset query-obj) Query/NOLIMIT))))

(defn user-limit
  "Return the explicit LIMIT on SELECT `query-obj`, or nil when none is set."
  [^Query query-obj]
  (when (and (.isSelectType query-obj)
             (not= (.getLimit query-obj) Query/NOLIMIT))
    (.getLimit query-obj)))

(defn execute
  "Execute validated SPARQL `query-obj` against `model` with safety constraints:
  a query `timeout` and a `results-limit`

  DISTINCT is applied to SELECT queries by default unless `distinct?` is false
  An optional `offset` is applied when the query doesn't already contain an
  OFFSET clause."
  [^Model model ^Query query-obj timeout results-limit
   & {:keys [distinct? offset]
      :or   {distinct? true offset 0}}]
  (txn/calculate-read model
    (fn []
      (let [query (cond-> query-obj
                    ;; TODO: is this necessary when we automatically apply limits?
                    ;; DISTINCT applies to all SELECT queries to reduce duplicates
                    ;; and improve performance.
                    distinct? (ensure-distinct!)
                    true (offset-results! offset)
                    true (limit-results! results-limit))
            qexec (-> (QueryExecution/model model)
                      (.query ^Query query-obj)
                      (.timeout (long timeout) TimeUnit/MILLISECONDS)
                      (.build))]
        (try
          (cond
            (.isSelectType query)
            {:sparql-type   :select
             :result-vars   (mapv (fn [v] (symbol (str "?" v)))
                                  (.getResultVars query))
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
          (catch QueryCancelledException e
            {:anomaly (anomaly/translate e)})
          (finally
            (.close qexec)))))))

(defn- run-query
  "Run SPARQL query `query-opts` against `model` using `executor` thread pool
  with a hard timeout. Return a result map or an error map."
  [model executor {:keys [query-obj timeout fetch-limit distinct? offset]
                   :as   query-opts}]
  (try
    (txn/with-hard-timeout executor timeout
      #(execute model query-obj timeout fetch-limit
                :distinct? distinct?
                :offset offset))
    (catch Exception e
      {:anomaly (anomaly/translate e)})))

(defn- empty-select-result?
  "Check if :sparql-result in the `result` map is an empty SELECT result set."
  [{:keys [sparql-type sparql-result] :as result}]
  (and (= sparql-type :select)
       (instance? ResultSetMem sparql-result)
       (zero? (.size ^ResultSetMem sparql-result))))

(defn- run-on-chosen-model
  "Check :inference? in `query-opts` to select appropriate model from `db`:

    - false forces base model
    - true forces inference model
    - otherwise: tries base first then retries with inference on empty result."
  [db {:keys [inference?] :as query-opts}]
  (let [{:keys [base-model model]} db]
    (case inference?
      false
      (assoc (run-query base-model query-executor query-opts)
        :inference? false)

      true
      (assoc (run-query model inference-query-executor query-opts)
        :inference? true)

      ;; When "auto" try base model first & retry with inference model if empty.
      (let [result (run-query base-model query-executor query-opts)]
        (if (empty-select-result? result)
          (assoc (run-query model inference-query-executor query-opts)
            :inference? true)
          (assoc result :inference? false))))))

(defn- trim-lookahead
  "When `lookahead?` is true and the result has more rows than `limit`, drop
  the N+1 lookahead row and mark `:has-more?` on the `result` instead, so that
  every response format returns `limit` rows."
  [limit lookahead? {:keys [sparql-result] :as result}]
  (if (and lookahead?
           (= (:sparql-type result) :select)
           (instance? ResultSetMem sparql-result)
           (> (.size ^ResultSetMem sparql-result) limit))
    (let [^ResultSetMem rs sparql-result
          vars     (map #(Var/alloc ^String %) (.getResultVars rs))
          bindings (do (.reset rs)
                       (doall (repeatedly limit #(.nextBinding rs))))]
      (assoc result
        :sparql-result (ResultSetMem. (ResultSetStream/create
                                        vars (.iterator ^Iterable bindings)))
        :has-more? true))
    result))

(defn- execute-query
  "Run a SPARQL query with model selection, inference retry,
  and the N+1 lookahead trim."
  [db {:keys [limit lookahead?] :as query-opts}]
  (->> (assoc query-opts :fetch-limit (if lookahead? (inc limit) limit))
       (run-on-chosen-model db)
       (trim-lookahead limit lookahead?)))

;; Caches query results keyed on the canonical parsed form + execution params.
;; The `lookup-or-miss` from core.cache.wrapped ensures the value-fn runs at
;; most once per key, preventing stampedes from concurrent identical requests.
(def ^:const cache-ttl-ms
  "How long cached SPARQL results stay valid (30 minutes).
  DanNet is read-only between releases, so this can be generous."
  (* 30 60 1000))

(defonce result-cache
  (cw/ttl-cache-factory {} :ttl cache-ttl-ms))

(defn reset-cache!
  "Clear the SPARQL result cache, e.g. after a dataset reload."
  []
  (reset! result-cache
          (cache/ttl-cache-factory {} :ttl cache-ttl-ms)))

(defn- cache-key
  "Build a whitespace-independent cache key from the `query-opts`.
  Uses Query.serialize() for canonical AST serialization."
  [{:keys [^Query query-obj inference? distinct? limit offset lookahead?]
    :as   query-opts}]
  [(.serialize query-obj)                                   ; normalized SPARQL
   inference? distinct? limit offset lookahead?])

(defn- copy-result
  "Return a copy of `result` with a fresh ResultSetMem iterator.

  The copy constructor shares the underlying rows but creates an independent
  iterator, making it safe for concurrent reads."
  [{:keys [sparql-result] :as result}]
  (if (instance? ResultSetMem sparql-result)
    (assoc result :sparql-result (ResultSetMem. ^ResultSetMem sparql-result))
    result))

(defn execute-cached
  "Run validated SPARQL `query-opts` against `db` with caching.

  Returns a map with :sparql-type, :sparql-result, :inference?, optionally
  :has-more? and :cached?. Error results include :anomaly instead."
  [db query-opts]
  (let [k       (cache-key query-opts)
        cached? (cache/has? @result-cache k)]
    ;; NOTE: small race window where an error result is briefly
    ;; visible in the cache between `lookup-or-miss` storing it
    ;; and the subsequent `evict`. Harmless in practice: a
    ;; concurrent request would see a real error, and the eviction
    ;; follows immediately so the next request retries.
    (-> (cw/lookup-or-miss
          result-cache k
          (fn [_]
            (let [result (execute-query db query-opts)]
              (if (:anomaly result)
                (do (cw/evict result-cache k) result)
                result))))
        (copy-result)
        (assoc :cached? cached?))))

(comment
  (validate "SELECT * WHERE { ?s ?p ?o } LIMIT 10")
  (validate "ELECT * WHERE { ?s ?p ?o } LIMIT 10")

  ;; Test with real model (requires data to be loaded)
  (let [db    @dk.cst.dannet.web.instance/db
        model (:model db)
        query (validate "SELECT ?s ?p ?o WHERE { ?s ?p ?o }")]
    (execute model query 10000 100))

  ;; Test cached execution (run twice; the second should be instant)
  (let [db @dk.cst.dannet.web.instance/db]
    (time (execute-cached db
                          {:query-obj  (validate "SELECT ?s WHERE { ?s a ?o }")
                           :timeout    10000
                           :limit      50
                           :offset     0
                           :distinct?  true
                           :lookahead? true
                           :inference? nil})))

  ;; Inspect/reset the cache
  (count @result-cache)
  (reset-cache!)
  #_.)
