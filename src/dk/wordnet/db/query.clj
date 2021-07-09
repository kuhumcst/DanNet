(ns dk.wordnet.db.query
  "Various pre-compiled Aristotle queries."
  (:require [clojure.string :as str]
            [arachne.aristotle.query :as q]
            [arachne.aristotle.registry :as reg]
            [ont-app.vocabulary.core :as voc])
  (:import [org.apache.jena.rdf.model Model]
           [org.apache.jena.system Txn]
           [org.apache.jena.sparql.core Transactional]
           [org.apache.jena.graph Graph]
           [java.util.function Supplier]))

(def schemas
  {'wn      {:uri "https://globalwordnet.github.io/schemas/wn#"
             :alt "https://raw.githubusercontent.com/globalwordnet/schemas/master/wn-lemon-1.1.rdf"}
   'ontolex {:uri "http://www.w3.org/ns/lemon/ontolex#"}
   'lemon   {:uri "http://lemon-model.net/lemon#"}
   'semowl  {:uri "http://www.ontologydesignpatterns.org/cp/owl/semiotics.owl#"}
   'skos    {:uri "http://www.w3.org/2004/02/skos/core#"
             :alt "http://www.w3.org/TR/skos-reference/skos.rdf"}
   'lexinfo {:uri "http://www.lexinfo.net/ontology/3.0/lexinfo#"}})

(defn register-prefix
  "Register `ns-prefix` for `uri` in both Aristotle and igraph."
  [ns-prefix uri]
  (reg/prefix ns-prefix uri)
  (let [prefix-str (name ns-prefix)]
    (when-not (get (voc/prefix-to-ns) prefix-str)
      (voc/put-ns-meta! ns-prefix {:vann/preferredNamespacePrefix prefix-str
                                   :vann/preferredNamespaceUri    uri}))))

(doseq [[ns-prefix {:keys [uri]}] schemas]
  (register-prefix ns-prefix uri))

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

(defn anonymous?
  [resource]
  (and (symbol? resource)
       (str/starts-with? resource "_")))

(defn only-uris
  "Exclude anonymous resource `results`, optionally keyed under `k`.

  The OWL reasoner produces a bunch of triples with anonymous resources in the
  object position. This is a way to remove these triples from the results of an
  Aristotle query."
  ([k results]
   (remove (comp anonymous? k) results))
  ([results]
   (remove (comp #(some anonymous? %) vals) results)))

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

(def registers
  (q/build
    '[:bgp
      [?sense :lexinfo/usageNote ?blank-node]
      [?blank-node :rdf/value ?register]]))

(def usage-targets
  "Used during initial graph creation to attach usages to senses."
  (q/build
    '[:bgp
      [?word :ontolex/evokes ?synset]
      [?word :ontolex/canonicalForm ?form]
      [?form :ontolex/writtenRep ?lemma]
      [?word :ontolex/sense ?sense]
      [?synset :ontolex/lexicalizedSense ?sense]]))

(def usages
  (q/build
    '[:bgp
      [?sense :ontolex/usage ?usage]
      [?usage :rdf/value ?usage-str]]))
