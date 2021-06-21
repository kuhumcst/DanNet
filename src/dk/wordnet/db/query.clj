(ns dk.wordnet.db.query
  "Various pre-compiled Aristotle queries."
  (:require [arachne.aristotle.query :as q]
            [arachne.aristotle.registry :as reg]
            [ont-app.vocabulary.core :as voc])
  (:import [org.apache.jena.rdf.model Model]
           [org.apache.jena.system Txn]
           [org.apache.jena.sparql.core Transactional]
           [org.apache.jena.graph Graph]
           [java.util.function Supplier]))

(defn register-prefix
  "Register `ns-prefix` for `uri` in both Aristotle and igraph."
  [ns-prefix uri]
  (reg/prefix ns-prefix uri)
  (let [prefix-str (name ns-prefix)]
    (when-not (get (voc/prefix-to-ns) prefix-str)
      (voc/put-ns-meta! ns-prefix {:vann/preferredNamespacePrefix prefix-str
                                   :vann/preferredNamespaceUri    uri}))))

(register-prefix 'wn "https://globalwordnet.github.io/schemas/wn#")
(register-prefix 'ontolex "http://www.w3.org/ns/lemon/ontolex#")
(register-prefix 'skos "http://www.w3.org/2004/02/skos#")
(register-prefix 'lexinfo "http://www.lexinfo.net/ontology/2.0/lexinfo#")
;; TODO: use new DanNet namespaces instead
(register-prefix 'dn "http://www.wordnet.dk/owl/instance/2009/03/instances/")
(register-prefix 'dns "http://www.wordnet.dk/owl/instance/2009/03/schema/")

(defn do-transaction!
  "Runs `f` as a transaction inside `db` which may be a Graph, Model, or
  Transactional (e.g. Dataset)."
  [db f]
  (let [supplier (reify Supplier (get [_] (f)))]
    (cond
      (instance? Graph db)
      (let [handler (.getTransactionHandler db)]
        (if (.transactionsSupported handler)
          (.calculate handler supplier)
          (f)))

      (instance? Model db)
      (if (.supportsTransactions db)
        (.calculateInTxn db supplier)
        (f))

      ;; Dataset implements the Transactional interface and is covered here.
      (instance? Transactional db)
      (Txn/calculate db supplier))))

(defmacro transact
  "Transact `body` within `db`."
  [db & body]
  (let [g (gensym)]
    `(let [~g ~db]
       (do-transaction! ~g #(do ~@body)))))

(defn run
  "Wraps the 'run' function from Aristotle, providing transactions when needed."
  [g & remaining-args]
  (transact g
    (apply q/run g remaining-args)))

(def synonyms
  (q/build
    '[:bgp
      [?form :ontolex/writtenRep ?lemma]
      [?word :ontolex/canonicalForm ?form]
      [?word :ontolex/evokes ?synset]
      [?word* :ontolex/evokes ?synset]
      [?word* :ontolex/canonicalForm ?form*]
      [?form* :ontolex/writtenRep ?synonym]]))
