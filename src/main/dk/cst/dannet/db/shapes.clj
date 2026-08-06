(ns dk.cst.dannet.db.shapes
  "SHACL validation of the DanNet graph.

  Bundles structural shapes (resources/schemas/internal/shapes/) with helpers
  to validate a graph — or a single focus node — and return violations as
  Clojure data. The shapes are split by target:

    * base.ttl      — invariants of the asserted (base) graph
    * inferred.ttl  — relation completeness; INFERRED model only
    * editorial.ttl — stricter rules gating newly edited data (validate-node)

  Current uses:
    * clojure.test assertions over small fixtures (see test ns),
    * a non-fatal bootstrap/CI check that logs anomalies via Telemere,
      comparing violation counts to a known baseline (shapes-baseline.edn), and
    * a release gate aborting RDF exports on baseline regressions
      (`validate-export!`, called from dk.cst.dannet.db.export.rdf)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [dk.cst.dannet.prefix]                          ; required for its side effects
            [ont-app.vocabulary.core :as voc]
            [dk.cst.dannet.db.transaction :as txn]
            [taoensso.telemere :as t])
  (:import [org.apache.jena.rdf.model Model]
           [org.apache.jena.shacl ShaclValidator Shapes]
           [org.apache.jena.shacl.validation ReportEntry]
           [org.apache.jena.graph Graph GraphUtil Node NodeFactory]
           [org.apache.jena.riot RDFDataMgr]
           [org.apache.jena.sparql.path Path P_Link]))

;; TODO: shapes are kept internal for now, but since SHACL shapes are plain
;; RDF, consider eventually publishing them as Linked Open Data under the dns:
;; namespace (alongside dannet-schema.ttl, cf. prefix.cljc) once they have
;; stabilised — e.g. letting the future editing UI derive required fields from
;; the same shapes that gate writes.
(def shapes-resources
  "SHACL shape files by target; :inferred and :editorial extend :base."
  {:base      "schemas/internal/shapes/base.ttl"
   :inferred  "schemas/internal/shapes/inferred.ttl"
   :editorial "schemas/internal/shapes/editorial.ttl"})

(defn load-shapes
  "Parse the SHACL shapes at one or more classpath `resources` into a single
  Jena `Shapes`, merging the shape graphs."
  [resource & resources]
  (let [graph (RDFDataMgr/loadGraph resource)]
    (doseq [r resources]
      (GraphUtil/addInto graph (RDFDataMgr/loadGraph r)))
    (Shapes/parse graph)))

(def shapes
  "The default shapes for the asserted (base) graph, parsed once."
  (delay (load-shapes (:base shapes-resources))))

;; NOTE: all shapes use SPARQL-based targets scoped to the dn: namespace --
;; DanNet only validates the dataset it controls, so companion datasets (COR,
;; OEWN) are deliberately not targeted. See the SCOPE note in base.ttl.
(def inferred-shapes
  "Base + relation-completeness shapes; only valid against the INFERRED model."
  (delay (load-shapes (:base shapes-resources)
                      (:inferred shapes-resources))))

(def editorial-shapes
  "Base + stricter editorial shapes; meant for gating newly edited data via
  targeted `validate-node` calls, never for validating the full graph."
  (delay (load-shapes (:base shapes-resources)
                      (:editorial shapes-resources))))

;; Everything below converts Jena's ValidationReport into plain Clojure data:
;; IRIs become DanNet keywords (via ont-app/vocabulary) so that callers never
;; touch Jena classes and results can be diffed against the EDN baseline.
(defn- node->value
  "A DanNet keyword for an IRI `node`, falling back to its string form."
  [^Node node]
  (when node
    (if (.isURI node)
      (try (voc/keyword-for (.getURI node))
           (catch Exception _ (.getURI node)))
      (str node))))

(defn- path->value
  "A DanNet keyword for a simple predicate `path`, falling back to its string
  form for complex SHACL property paths."
  [^Path path]
  (when path
    (if (instance? P_Link path)
      (node->value (.getNode ^P_Link path))
      (str path))))

(defn- entry->map
  [^ReportEntry e]
  ;; :shape is the stable identity used for baselines and grouping -- the
  ;; reason every property shape in the TTL has an explicit IRI instead of
  ;; the usual blank node.
  {:focus-node (node->value (.focusNode e))
   :path       (path->value (.resultPath e))
   :value      (node->value (.value e))
   :shape      (node->value (.source e))
   :constraint (node->value (.sourceConstraintComponent e))
   :message    (.message e)
   ;; :sh/Violation blocks a write; :sh/Warning only surfaces in the UI.
   :severity   (some-> (.severity e) .level node->value)})

(defn- report->result
  "Convert a Jena `ValidationReport` into the standard result map."
  [^org.apache.jena.shacl.ValidationReport report]
  (let [entries (mapv entry->map (.getEntries report))]
    {:conforms? (.conforms report)
     :n         (count entries)
     :entries   entries}))

(defn validate
  "Validate `graph` against `shapes` (a Jena `Shapes`), assuming a read
  transaction is already open. Returns

    {:conforms? boolean, :n long, :entries [{:focus-node ...} ...]}."
  ([^Graph graph]
   (validate graph @shapes))
  ([^Graph graph ^Shapes shapes]
   (report->result (.validate (ShaclValidator/get) shapes graph))))

(defn- ->node
  "Coerce `x` (a Jena Node, a prefixed keyword, or an IRI string) into a Node."
  ^Node [x]
  (cond
    (instance? Node x) x
    (keyword? x) (NodeFactory/createURI (voc/uri-for x))
    (string? x) (NodeFactory/createURI x)
    :else (throw (IllegalArgumentException.
                   (str "Cannot coerce to Node: " (pr-str x))))))

(defn validate-node
  "Validate a single focus `node` of `graph` against `shapes`, assuming a read
  transaction is already open. Only shapes targeting the node are checked,
  making this cheap enough to gate individual writes (e.g. an RDF Patch).

  The `node` may be a Jena Node, a prefixed keyword (e.g. :dn/synset-999), or
  an IRI string. Returns the same result map as `validate`."
  ([^Graph graph node]
   (validate-node graph @shapes node))
  ([^Graph graph ^Shapes shapes node]
   (report->result (.validate (ShaclValidator/get) shapes graph (->node node)))))

(defn by-severity
  "Violation `entries` grouped by :severity, e.g. for separating blocking
  violations (:sh/Violation) from surfaceable warnings (:sh/Warning)."
  [entries]
  (group-by :severity entries))

(defn blocking?
  "Does the validation `result` contain entries that should block a write?
  Only :sh/Violation entries block; :sh/Warning and :sh/Info do not."
  [result]
  (boolean (some #(= :sh/Violation (:severity %)) (:entries result))))

(def baseline-resource
  "schemas/internal/shapes-baseline.edn")

(def baseline
  "Known violation counts per property shape, i.e. the accepted status quo.
  Counts *exceeding* the baseline indicate a regression; counts below it mean
  data was fixed (update the baseline file accordingly)."
  (delay (some-> (io/resource baseline-resource) slurp edn/read-string)))

(defn by-shape
  "Frequencies of violation `entries` grouped by their source property shape."
  [entries]
  (frequencies (map :shape entries)))

(defn exceeding-baseline
  "The part of a `by-shape` frequency map exceeding the `baseline`, as a map of
  shape to [baseline-count actual-count]. Empty when nothing regressed."
  [by-shape baseline]
  (into {}
        (keep (fn [[shape n]]
                (let [m (get baseline shape 0)]
                  (when (> n m)
                    [shape [m n]]))))
        by-shape))

(defn- against-baseline
  "Add :exceeded to a validation `result` by comparing its :sh/Violation
  entries to the known `baseline`; warnings never count against it.
  Also adds :violations (the blocking entry count) for logging purposes."
  [result]
  (let [violations (:sh/Violation (by-severity (:entries result)))]
    (assoc result
      :violations (count violations)
      :exceeded (exceeding-baseline (by-shape violations) @baseline))))

(defn validate-db
  "Validate the asserted (base) graph of `db` against the default shapes within a
  read transaction, log a summary via Telemere, and return the result map with
  added :violations and :exceeded keys comparing blocking violation counts to
  the known baseline (warnings surface in the logged counts only).

  Non-fatal by design: a bootstrap/CI check, not (yet) an acceptance gate.
  Logs :error when the baseline is exceeded, :warn on known violations, and
  :info when the graph conforms."
  [db]
  ;; Called async at boot from dk.cst.dannet.web.instance -- deliberately
  ;; non-fatal so a validation problem can never take the service down.
  (let [graph (.getGraph ^Model (:base-model db))
        {:keys [violations exceeded] :as result}
        (against-baseline (txn/transact-read (:dataset db) (validate graph)))]
    (t/log! {:level (cond (seq exceeded) :error
                          (not (:conforms? result)) :warn
                          :else :info)
             :id    :dannet.shapes/validate
             :data  {:conforms?  (:conforms? result)
                     :violations violations
                     :by-shape   (by-shape (:entries result))
                     :exceeded   exceeded}}
            "SHACL validation of asserted graph")
    result))

(defn validate-inferred-db
  "Validate the inference graph of `db` against the inferred shape set within a
  read transaction, log a summary via Telemere, and return the result map.

  EXPENSIVE and opt-in by design (never wired into boot): SHACL validation
  traverses the entire inference graph, forcing materialization of inferred
  triples. Expect this to take a long time on the full dataset."
  [db]
  (let [result (txn/transact-read (:dataset db)
                 (validate (:graph db) @inferred-shapes))
        counts (by-shape (:entries result))]
    (t/log! {:level (if (:conforms? result) :info :warn)
             :id    :dannet.shapes/validate-inferred
             :data  {:conforms?  (:conforms? result)
                     :violations (:n result)
                     :by-shape   counts}}
            "SHACL validation of inferred graph")
    result))

(defn validate-export!
  "Validate the exported Turtle file at `path` (a plain .ttl on disk) against
  the default base shapes, comparing :sh/Violation counts to the known
  baseline. A release gate: throws ex-info when violations exceed the
  baseline; warnings and baselined violations only log.

  Returns the result map with added :violations and :exceeded keys when the
  gate passes."
  [path]
  ;; Loads the artifact into a fresh in-memory graph so the gate validates
  ;; exactly what ships, not the live TDB2 graph it was exported from.
  (let [path (str path)
        {:keys [violations exceeded] :as result}
        (against-baseline (validate (RDFDataMgr/loadGraph path)))]
    (t/log! {:level (cond (seq exceeded) :error
                          (not (:conforms? result)) :warn
                          :else :info)
             :id    :dannet.shapes/validate-export
             :data  {:path       path
                     :conforms?  (:conforms? result)
                     :violations violations
                     :exceeded   exceeded}}
            "SHACL validation of exported artifact")
    (when (seq exceeded)
      (throw (ex-info (str "SHACL violations exceed baseline in " path)
                      {:path     path
                       :exceeded exceeded})))
    result))

(comment
  ;; Inspect the currently accepted violation counts.
  @baseline

  ;; The shapes and baseline are parsed once into delays, so after editing
  ;; the .ttl shape files or the baseline EDN, reload this ns to pick them up.
  (require 'dk.cst.dannet.db.shapes :reload)
  #_.)
