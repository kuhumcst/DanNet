(ns dk.cst.dannet.web.sparql
  "How DanNet runs the queries of its SPARQL endpoint, which comes from
  Theophrastus: on the base or the inference model, a page at a time for
  the editor, and through a cache that coalesces concurrent identical
  queries (e.g. a classroom of students all running the same query)."
  (:require [clojure.core.cache :as cache]
            [clojure.core.cache.wrapped :as cw]
            [clojure.string :as str]
            [dk.cst.theophrastus.endpoint :as endpoint])
  (:import [java.util.concurrent SynchronousQueue ThreadFactory
                                  ThreadPoolExecutor TimeUnit]
           [org.apache.jena.query Query]
           [org.apache.jena.sparql.core Var]
           [org.apache.jena.sparql.engine ResultSetStream]
           [org.apache.jena.sparql.resultset ResultSetMem]))

(def max-results
  "The most results that a query returns, whatever its LIMIT says."
  100)

(def graph-result-vars
  "The variables of the rows that the result of a CONSTRUCT or DESCRIBE query
  is shown as, one row per triple."
  '[?subject ?predicate ?object])

;; The rule reasoner of the inference model never checks Jena's own timeout,
;; so the queries run on pools with no queue, where the hard timeout of
;; endpoint/run holds (see txn/with-hard-timeout). A query that needs the
;; inference model gets a smaller pool, since its abandoned thread may run
;; on for minutes. The threads are daemons, so they never keep the JVM from
;; exiting, e.g. at the end of a test run.
(defn- ->rejecting-executor
  "A pool of `n` daemon threads with no queue, which rejects a call when all
  its threads are busy."
  [n]
  (ThreadPoolExecutor. n n 0 TimeUnit/MILLISECONDS (SynchronousQueue.)
                       (reify ThreadFactory
                         (newThread [_ r]
                           (doto (Thread. r) (.setDaemon true))))))

(defonce query-executor
  (->rejecting-executor 8))

(defonce inference-query-executor
  (->rejecting-executor 4))

(defn- paged?
  "Whether the editor pages through the results of the Query `q`: a SELECT
  with no LIMIT or OFFSET of its own."
  [^Query q]
  (and (.isSelectType q) (not (.hasLimit q)) (not (.hasOffset q))))

(defn page
  "The operation `op`, prepared without a limit, with its query restricted
  to the page that the `params` of the editor ask for.

  A query with a LIMIT or OFFSET of its own keeps them, up to max-results.
  Any other SELECT gets the page that the limit and offset parameters ask
  for, and one row more, which tells whether there is a next page, unless
  lookahead=false. The :limit, :offset and :lookahead? of the page go in
  `op` too."
  [{:keys [^Query sparql] :as op} {:keys [limit offset distinct lookahead]}]
  (let [page?      (paged? sparql)
        size       (-> (if page?
                         (some-> limit parse-long)
                         (when (.hasLimit sparql) (.getLimit sparql)))
                       (or max-results)
                       (min max-results)
                       (max 1))
        start      (max 0 (or (some-> offset parse-long) 0))
        lookahead? (and page? (not= "false" lookahead))]
    (assoc op
      :sparql (endpoint/restrict sparql
                                 {:limit     (cond-> size lookahead? inc)
                                  :offset    start
                                  :distinct? (= "true" distinct)})
      :limit size
      :offset start
      :lookahead? lookahead?)))

(defn normalize
  "The text of the Query `q` as Jena writes it, with the PREFIX lines of only
  the prefixes that it uses, since DanNet's own would make up most of a
  short query, and of the URL that the editor sends it in."
  [^Query q]
  (let [lines  (str/split-lines (str q))
        prefix #(second (re-matches #"PREFIX\s+(\S*:).*" %))
        body   (str/join "\n" (remove prefix lines))]
    (->> lines
         (filter #(if-let [p (prefix %)] (str/includes? body p) true))
         (str/join "\n"))))

(defn- empty-select-result?
  "Whether the operation `op` has the empty result of a SELECT."
  [{:keys [form result]}]
  (and (= :select form)
       (instance? ResultSetMem result)
       (zero? (.size ^ResultSetMem result))))

(defn- run-on-chosen-model
  "The operation `op` run on the model of `db` that its inference parameter
  asks for, with the `opts` of endpoint/run, and :inference? to tell which
  one it ran on:

    - false forces the base model
    - true forces the inference model
    - otherwise it tries the base model, and on the first page the inference
      model when that returns no results. The editor's links to the other
      pages force the model that the first one ran on."
  [db {:keys [params offset] :as op} opts]
  (let [run-on (fn [k executor]
                 (assoc (endpoint/run op (k db) (assoc opts :executor executor))
                   :inference? (= :inference k)))]
    (case (:inference params)
      "false" (run-on :base query-executor)
      "true" (run-on :inference inference-query-executor)
      (let [op' (run-on :base query-executor)]
        (if (and (empty-select-result? op') (zero? (or offset 0)))
          (run-on :inference inference-query-executor)
          op')))))

(defn- trim-lookahead
  "The operation `op` without the row that its page looked ahead by, and
  with :has-more? when there was one, so that every format returns the
  :limit of the page."
  [{:keys [lookahead? limit result] :as op}]
  (if (and lookahead?
           (instance? ResultSetMem result)
           (> (.size ^ResultSetMem result) limit))
    (let [^ResultSetMem rs result
          vars     (map #(Var/alloc ^String %) (.getResultVars rs))
          bindings (do (.reset rs)
                       (doall (repeatedly limit #(.nextBinding rs))))]
      (assoc op
        :result (ResultSetMem. (ResultSetStream/create
                                 vars (.iterator ^Iterable bindings)))
        :has-more? true))
    op))

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

(defn- copy-result
  "The operation `op` with a fresh iterator over its ResultSetMem, which
  shares the rows of the cached one, so that concurrent reads are safe."
  [{:keys [result] :as op}]
  (cond-> op
    (instance? ResultSetMem result)
    (assoc :result (ResultSetMem. ^ResultSetMem result))))

(def max-client-queries
  "The most queries that one client address runs at once, so that a single
  client can't take every thread of the pools."
  4)

(defonce client-queries
  (atom {}))

(defn- acquire!
  "Count one more query of the `client` as running, unless it already runs
  max-client-queries. Return whether it was counted."
  [client]
  (let [[old new] (swap-vals! client-queries
                              (fn [m]
                                (cond-> m
                                  (< (get m client 0) max-client-queries)
                                  (update client (fnil inc 0)))))]
    (not= old new)))

(defn- release!
  "Count one query of the `client` less as running."
  [client]
  (swap! client-queries
         (fn [m]
           (let [n (dec (get m client))]
             (if (pos? n) (assoc m client n) (dissoc m client))))))

(defn run-cached
  "The operation `op` run on `db` as run-on-chosen-model does, with the
  `opts` of endpoint/run, through the result cache.

  The cache key is what the result depends on: the query as Jena writes it,
  which the page has already restricted, the inference parameter and the
  graphs of the request. The row that a page looks ahead by is trimmed after
  the cache, so that a page and a query with the same LIMIT share a result.
  An :error isn't cached, and the result has :cached? when it came from the
  cache.

  A query that isn't in the cache counts against the max-client-queries of
  the :client of `op`, and gets an :error of 429 when there are too many."
  [db {:keys [^Query sparql params default-graph-uri named-graph-uri client]
       :as   op} opts]
  (let [k       [(.serialize sparql) (:inference params)
                 default-graph-uri named-graph-uri]
        cached? (cache/has? @result-cache k)]
    (if-not (or cached? (acquire! client))
      (assoc op :error {:status  429
                        :type    :too-many-queries
                        :message "Too many of your queries are running."})
      (try
        ;; NOTE: small race window where an error result is briefly
        ;; visible in the cache between `lookup-or-miss` storing it
        ;; and the subsequent `evict`. Harmless in practice: a
        ;; concurrent request would see a real error, and the eviction
        ;; follows immediately so the next request retries.
        (-> (merge op (cw/lookup-or-miss
                        result-cache k
                        (fn [_]
                          (let [run (-> (run-on-chosen-model db op opts)
                                        (select-keys [:result :vars :error
                                                      :inference?]))]
                            (when (:error run)
                              (cw/evict result-cache k))
                            run))))
            (copy-result)
            (trim-lookahead)
            (assoc :cached? cached?))
        (finally
          (when-not cached?
            (release! client)))))))

(comment
  ;; A page of a query, run on a live db; run it twice to hit the cache.
  (let [db @dk.cst.dannet.web.instance/db
        q  (java.net.URLEncoder/encode "SELECT * { ?s a ?o }" "UTF-8")
        op (-> (endpoint/operation {:request-method :get
                                    :headers        {}
                                    :query-string   (str "query=" q)})
               (endpoint/prepare {:prefixes dk.cst.dannet.prefix/prefixes})
               (page {:limit "10"}))]
    (time (dissoc (run-cached db op {}) :sparql :result)))

  ;; Inspect/reset the cache
  (count @result-cache)
  (reset-cache!)
  #_.)
