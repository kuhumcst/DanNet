(ns dk.cst.dannet.db.transaction
  "Support for Apache Jena transactions."
  (:import [org.apache.jena.rdf.model Model]
           [org.apache.jena.system Txn]
           [org.apache.jena.sparql.core Transactional]
           [org.apache.jena.graph Graph]
           [java.util.function Supplier]))

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
