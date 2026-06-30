(ns dk.cst.dannet.db.shapes
  "SHACL validation of the asserted DanNet graph.

  Bundles a small set of structural shapes (resources/schemas/internal/shapes.ttl)
  with helpers to validate a graph and return violations as Clojure data.

  Two intended uses, neither of which is a hard gate yet:
    * a clojure.test assertion over small fixtures (see test ns), and
    * a non-fatal bootstrap/CI check that logs anomalies via Telemere.

  Validate the BASE (asserted) model. Relation-completeness shapes belong on the
  inferred model instead - see the note in shapes.ttl."
  (:require [ont-app.vocabulary.core :as voc]
            [dk.cst.dannet.db.transaction :as txn]
            [taoensso.telemere :as t])
  (:import [org.apache.jena.shacl ShaclValidator Shapes]
           [org.apache.jena.shacl.validation ReportEntry]
           [org.apache.jena.graph Graph Node]
           [org.apache.jena.riot RDFDataMgr]))

(def shapes-resource
  "schemas/internal/shapes.ttl")

(defn load-shapes
  "Parse the SHACL shapes at classpath `resource` into a Jena `Shapes`."
  [resource]
  (Shapes/parse (RDFDataMgr/loadGraph resource)))

(def shapes
  "The default DanNet shapes, parsed once."
  (delay (load-shapes shapes-resource)))

(defn- node->value
  "A DanNet keyword for an IRI `node`, falling back to its string form."
  [^Node node]
  (when node
    (if (.isURI node)
      (try (voc/keyword-for (.getURI node))
           (catch Throwable _ (.getURI node)))
      (str node))))

(defn- entry->map
  [^ReportEntry e]
  {:focus-node (node->value (.focusNode e))
   :path       (some-> (.resultPath e) str)
   :value      (node->value (.value e))
   :message    (.message e)
   :severity   (some-> (.severity e) .level .getLocalName)})

(defn validate
  "Validate `graph` against `shapes` (a Jena `Shapes`), assuming a read
  transaction is already open. Returns

    {:conforms? boolean, :n long, :entries [{:focus-node ...} ...]}."
  ([^Graph graph]
   (validate graph @shapes))
  ([^Graph graph ^Shapes shapes]
   (let [report  (.validate (ShaclValidator/get) shapes graph)
         entries (mapv entry->map (.getEntries report))]
     {:conforms? (.conforms report)
      :n         (count entries)
      :entries   entries})))

(defn validate-db
  "Validate the asserted (base) graph of `db` against the default shapes within a
  read transaction, log a summary via Telemere, and return the result map.

  Non-fatal by design: a bootstrap/CI check, not (yet) an acceptance gate."
  [db]
  (let [graph  (.getGraph ^org.apache.jena.rdf.model.Model (:base-model db))
        result (txn/transact-read (:dataset db) (validate graph @shapes))]
    (t/log! {:level (if (:conforms? result) :info :warn)
             :id    :dannet.shapes/validate
             :data  {:conforms?  (:conforms? result)
                     :violations (:n result)
                     :by-message (frequencies (map :message (:entries result)))}}
            "SHACL validation of asserted graph")
    result))
