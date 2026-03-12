(ns dk.cst.dannet.db.transaction
  "Support for Apache Jena transactions."
  (:import [org.apache.jena.rdf.model Model]
           [org.apache.jena.system Txn]
           [org.apache.jena.sparql.core Transactional]
           [org.apache.jena.graph Graph]
           [java.util.function Supplier]
           [java.util.concurrent
            ExecutionException ExecutorService Executors Future
            RejectedExecutionException TimeUnit TimeoutException]))

;; ## Hard timeout
;;
;; Jena's built-in query timeout (via QueryExecution.setTimeout) is cooperative:
;; it sets a cancellation flag that the query engine's iterator checks between
;; bindings. This works fine for queries against plain Models or TDB datasets.
;;
;; However, DanNet uses an InfModel backed by a GenericRuleReasoner (OWL, HYBRID
;; mode) for deriving inverse relations and transitive closures. When the query
;; engine asks the InfGraph for the next matching triple, the reasoner can spend
;; an arbitrarily long time in its internal forward-chaining rule engine — and
;; that code never checks the cancellation flag. A single .next() call on the
;; query iterator can block for minutes while the reasoner churns.
;;
;; The newer QueryExec builder API (Jena 4.x) has the same limitation: its
;; AlarmClock-based timeout still relies on QueryIterator.cancel(), which only
;; takes effect when the iterator yields — i.e. it can't interrupt the reasoner.
;;
;; The solution is a Future-based hard timeout: we submit the work to a thread
;; pool and call Future.get(timeout). If the deadline passes, the caller gets a
;; TimeoutException immediately. The abandoned thread runs to completion on its
;; own — we intentionally do NOT interrupt it (cancel(false)), because
;; Thread.interrupt() would cause NIO's FileChannel.map() to throw
;; ClosedChannelException, permanently closing the underlying TDB2 file channels
;; and breaking all subsequent requests.
;;
;; Two fixed-size thread pools are used: a larger one for queries against the
;; base model (no inference, fast and predictable) and a smaller one for queries
;; that opt in to inference (where abandoned threads may linger). When all slots
;; in a pool are occupied, new submissions fail fast with a TimeoutException
;; rather than queueing up. The Jena setTimeout is kept as a belt-and-suspenders
;; measure for the (common) case where the query engine *does* check its flag
;; between bindings.

(defonce query-executor
  (Executors/newFixedThreadPool 32))

(defonce inference-query-executor
  (Executors/newFixedThreadPool 8))

(defn with-hard-timeout
  "Execute `f` on `executor`, enforcing a hard `timeout` (in ms).
  Returns the result of `f`. Throws TimeoutException if the deadline passes or
  if the thread pool is saturated. Any exception thrown by `f` is unwrapped from
  the ExecutionException and rethrown to the caller.

  NOTE: the future is cancelled with cancel(false) — no thread interrupt — to
  avoid corrupting shared state (see comment block above)."
  [^ExecutorService executor timeout f]
  (let [fut (try
              (.submit executor ^Callable f)
              (catch RejectedExecutionException _
                (throw (TimeoutException. "Query pool saturated"))))]
    (try
      (.get ^Future fut timeout TimeUnit/MILLISECONDS)
      (catch TimeoutException e
        (.cancel fut false)
        (throw e))
      (catch ExecutionException e
        (throw (.getCause e))))))

(defn do-transaction!
  "Runs `f` as a transaction inside `db` which may be a Graph, Model, or
  Transactional (e.g. Dataset).

    :read-only? - create a read-only transaction (cannot be promoted to write).
    :return?    - no return values."
  [db f & {:keys [return? read-only?]}]
  (let [action (if return?
                 (reify Supplier (get [_] (f)))
                 (reify Runnable (run [_] (f))))]
    (cond
      (instance? Graph db)
      (let [handler (.getTransactionHandler db)]
        (if (.transactionsSupported handler)
          (if return?
            (.calculate handler action)
            (.execute handler action))
          (f)))

      (instance? Model db)
      (if (.supportsTransactions db)
        (if return?
          (.calculateInTxn db action)
          (.executeInTxn db action))
        (f))

      ;; Dataset implements the Transactional interface and is covered here.
      (instance? Transactional db)
      (if read-only?
        (Txn/calculateRead db action)
        (if return?
          (Txn/calculate db action)
          (Txn/execute db action))))))

(defmacro transact-exec
  "Transact `body` within `db`. Only executes - does not return the result!"
  [db & body]
  (let [g (gensym)]
    `(let [~g ~db]
       (do-transaction! ~g #(do ~@body)))))

(defmacro transact
  "Transact `body` within `db` and return the result. Use with queries."
  [db & body]
  (let [g (gensym)]
    `(let [~g ~db]
       (do-transaction! ~g #(do ~@body) :return? true))))

(defmacro transact-read
  "Transact `body` within `db` in read-only transaction and return the result."
  [db & body]
  (let [g (gensym)]
    `(let [~g ~db]
       (do-transaction! ~g #(do ~@body) :return? true :read-only? true))))
