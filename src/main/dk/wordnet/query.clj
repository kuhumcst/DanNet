(ns dk.wordnet.query
  "Various pre-compiled Aristotle queries."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.core.protocols :as p]
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
   'semowl  {:uri "http://www.ontologydesignpatterns.org/cp/owl/semiotics.owl#"
             :alt (str (io/resource "schemas/semiotics.owl"))}
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
  [db f & {:keys [return?]}]
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
      (if return?
        (Txn/calculate db action)
        (Txn/execute db action)))))

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

(defn anonymous?
  [resource]
  (and (symbol? resource)
       (str/starts-with? resource "_")))

;; TODO: currently removes usages which is not desirable - fix?
(defn only-uris
  "Exclude anonymous resource `results`, optionally keyed under `k`.

  The OWL reasoner produces a bunch of triples with anonymous resources in the
  object position. This is a way to remove these triples from the results of an
  Aristotle query."
  ([k results]
   (remove (comp anonymous? k) results))
  ([results]
   (remove (comp #(some anonymous? %) vals) results)))

;; TODO: what about symbols? Some are ?vars, others are _blank-resources
(defn entity?
  "Is `x` an RDF entity?"
  [x]
  (or (keyword? x)
      (and (string? x)
           (re-matches #"<.+>" x))))

(declare entity)
(declare run)

(defn- nav-subjects
  "Helper function for 'nav-meta'."
  [g & subject-triples]
  (let [results (->> (into [:bgp] subject-triples)
                     (run g)
                     (only-uris)
                     (mapcat vals)
                     (into #{}))]
    (if (= 1 (count results))
      (entity g (first results))
      results)))

(defn nav-meta
  "Create metadata with a generalised Navigable implementation for a Graph `g`.

  For RDF entities, returns the entity description as a map.
  For literals, returns all the subjects of the same relation to the literal.
  For one-to-many relations, returns all subjects with the same set of objects."
  [g]
  {`p/nav (fn [coll k v]
            (cond
              (entity? v)
              (entity g v)

              (set? v)
              (with-meta (apply nav-subjects g (for [o v] [(gensym "?") k o]))
                         (nav-meta g))

              :else
              (with-meta (nav-subjects g ['?s k v])
                         (nav-meta g))))})

(defn- set-nav-merge
  "Helper function for merge-with in 'entity'."
  [g]
  (fn [v1 v2]
    (if (set? v1)
      (with-meta (conj v1 v2) (meta v1))
      (vary-meta (hash-set v1 v2) merge (nav-meta g)))))

(defn entity
  "Return the entity description of `subject` in Graph `g`."
  [g subject]
  (when-let [e (->> (run g [:bgp [subject '?p '?o]])
                    (only-uris)
                    (map (comp (partial apply hash-map) (juxt '?p '?o)))
                    (apply merge-with (set-nav-merge g)))]
    (with-meta e (assoc (nav-meta g)
                   :entity subject))))

(defn run
  "Wraps the 'run' function from Aristotle, providing transactions when needed.
  The results are also made Navigable using for use with e.g. Reveal or REBL."
  [g & remaining-args]
  (->> (transact g
         (apply q/run g remaining-args))
       (map #(vary-meta % merge (nav-meta g)))))

(def synonyms
  (q/build
    '[:bgp
      [?form :ontolex/writtenRep ?lemma]
      [?word :ontolex/canonicalForm ?form]
      [?word :ontolex/evokes ?synset]
      [?word* :ontolex/evokes ?synset]
      [?word* :ontolex/canonicalForm ?form*]
      [?form* :ontolex/writtenRep ?synonym]]))

(def alt-representations
  "Certain words contain alternative written representations."
  (q/build
    '[:bgp
      [?form :ontolex/writtenRep ?written-rep]
      [?form :ontolex/writtenRep ?alt-rep]]))

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
