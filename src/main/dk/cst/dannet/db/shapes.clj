(ns dk.cst.dannet.db.shapes
  "SHACL validation of the DanNet graph.

  Bundles structural shapes (resources/schemas/internal/shapes/) with helpers
  to validate a graph, a single focus node, or a proposed set of changes, and
  return violations as Clojure data. The shapes are split by target:

    * base.ttl      - invariants of the asserted (base) graph
    * inferred.ttl  - relation completeness; INFERRED model only
    * editorial.ttl - stricter rules gating newly edited data (validate-node)

  Current uses:
    * clojure.test assertions over small fixtures (see test ns),
    * a non-fatal bootstrap/CI check that logs anomalies via Telemere,
      comparing violation counts to a known baseline (shapes-baseline.edn),
    * a release gate aborting RDF exports on baseline regressions
      (`validate-export!`, called from dk.cst.dannet.db.export.rdf), and
    * introspection of the shapes themselves as plain data (`shapes->spec`)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [arachne.aristotle.graph :as ag]
            [dk.cst.dannet.prefix]                          ; required for its side effects
            [ont-app.vocabulary.core :as voc]
            [dk.cst.dannet.db.transaction :as txn]
            [taoensso.telemere :as t])
  (:import [org.apache.jena.rdf.model Model]
           [org.apache.jena.shacl ShaclValidator Shapes]
           [org.apache.jena.shacl.engine Target]
           [org.apache.jena.shacl.engine.constraint ClassConstraint
                                                    HasValueConstraint
                                                    InConstraint
                                                    MaxCount
                                                    MinCount
                                                    NodeKindConstraint
                                                    PatternConstraint
                                                    SparqlConstraint]
           [org.apache.jena.shacl.parser Constraint PropertyShape Shape]
           [org.apache.jena.shacl.validation ReportEntry]
           [org.apache.jena.graph Graph GraphUtil Node NodeFactory Triple]
           [org.apache.jena.graph.compose Delta]
           [org.apache.jena.riot RDFDataMgr]
           [org.apache.jena.sparql.path Path P_Link]))

;; TODO: shapes are kept internal for now, but since SHACL shapes are plain
;; RDF, consider eventually publishing them as Linked Open Data under the dns:
;; namespace (alongside dannet-schema.ttl, cf. prefix.cljc) once they have
;; stabilised.
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
  ;; :shape is the stable identity used for baselines and grouping; the
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

;; TODO: remove once the aristotle fork's LangStr conversion is fixed.
(defn- normalize-triple
  "Rewrite an ill-formed language-tagged literal object of `t` (non-empty
  language but a datatype other than rdf:langString, as currently produced by
  aristotle's LangStr conversion) into a well-formed rdf:langString literal,
  i.e. the form parsers and TDB2 store, so that term comparisons hold."
  ^Triple [^Triple t]
  (let [o (.getObject t)]
    (if (and (.isLiteral o)
             (not-empty (.getLiteralLanguage o))
             (not= "http://www.w3.org/1999/02/22-rdf-syntax-ns#langString"
                   (.getLiteralDatatypeURI o)))
      (Triple/create (.getSubject t)
                     (.getPredicate t)
                     (NodeFactory/createLiteral (.getLiteralLexicalForm o)
                                                (.getLiteralLanguage o)))
      t)))

(defn validate-changes
  "Validate the result of applying `changes` to `graph` without mutating it,
  assuming a read transaction is already open. Returns the same result map as
  `validate`, so e.g. `blocking?` and `by-severity` compose directly.

  `changes` is a map of :add and/or :delete triple collections in any format
  accepted by aristotle, e.g. [s p o] vectors of prefixed keywords. The
  changes are overlaid on `graph` via a Delta (the base graph is untouched)
  and the subject of every changed triple is validated in the overlaid graph
  via `validate-node` against `shapes`, defaulting to the editorial shape set
  since this is meant to gate writes before they are committed."
  ([^Graph graph changes]
   (validate-changes graph @editorial-shapes changes))
  ([^Graph graph ^Shapes shapes {:keys [add delete]}]
   (let [added   (->> (some->> (not-empty add) ag/triples (map normalize-triple))
                      ;; Delta/find would return a triple present in both the
                      ;; base graph and the additions twice, breaking maxCount
                      ;; constraints, so re-added triples are skipped.
                      (remove #(.contains graph ^Triple %)))
         deleted (some->> (not-empty delete) ag/triples (map normalize-triple))
         delta   (Delta. graph)]
     (doseq [^Triple t added] (.add delta t))
     (doseq [^Triple t deleted] (.delete delta t))
     (let [entries (->> (concat added deleted)
                        (map #(.getSubject ^Triple %))
                        (distinct)
                        (into [] (mapcat #(:entries (validate-node delta shapes %)))))]
       {:conforms? (empty? entries)
        :n         (count entries)
        :entries   entries}))))

;; Shape introspection: the parsed Shapes exposed as plain data.
(defn- message->str
  "The lexical form of an sh:message `node`."
  [^Node node]
  (if (.isLiteral node)
    (.getLiteralLexicalForm node)
    (str node)))

(defn- constraint->map
  "Convert a Jena `Constraint` into a map whose :type is the SHACL constraint
  component, i.e. the same keyword as the :constraint of a violation entry.
  SPARQL-based constraints stay opaque (a query string is not a form field)."
  [^Constraint c]
  (let [m {:type (node->value (.getComponent c))}]
    (condp instance? c
      MinCount (assoc m :min-count (.getMinCount ^MinCount c))
      MaxCount (assoc m :max-count (.getMaxCount ^MaxCount c))
      ClassConstraint (assoc m :class (node->value (.getExpectedClass ^ClassConstraint c)))
      NodeKindConstraint (assoc m :node-kind (node->value (.getKind ^NodeKindConstraint c)))
      HasValueConstraint (assoc m :value (node->value (.getValue ^HasValueConstraint c)))
      PatternConstraint (assoc m :pattern (.getPattern ^PatternConstraint c))
      InConstraint (assoc m :values (mapv node->value (.getValues ^InConstraint c)))
      SparqlConstraint m
      (assoc m :description (str c)))))

(defn- target->map
  "Convert a Jena `target` into a map of target :type (e.g. :targetClass,
  :targetSubjectsOf, or :targetExtension for SPARQL-based targets) and the
  targeted :node."
  [^Target target]
  {:type (keyword (str (.getTargetType target)))
   :node (node->value (.getObject target))})

(defn- property-shape->map
  [^PropertyShape ps]
  (cond-> {:shape       (node->value (.getShapeNode ps))
           :path        (path->value (.getPath ps))
           :severity    (some-> (.getSeverity ps) .level node->value)
           :constraints (mapv constraint->map (.getConstraints ps))}
    (seq (.getMessages ps))
    (assoc :messages (mapv message->str (.getMessages ps)))))

(defn- shape->map
  [^Shape shape]
  (cond-> {:shape    (node->value (.getShapeNode shape))
           :severity (some-> (.getSeverity shape) .level node->value)
           :targets  (mapv target->map (.getTargets shape))}
    (seq (.getConstraints shape))
    (assoc :constraints (mapv constraint->map (.getConstraints shape)))
    (seq (.getMessages shape))
    (assoc :messages (mapv message->str (.getMessages shape)))
    (seq (.getPropertyShapes shape))
    (assoc :properties (mapv property-shape->map (.getPropertyShapes shape)))))

(defn shapes->spec
  "Convert parsed SHACL `shapes` (a Jena `Shapes`, e.g. @shapes) into plain
  Clojure data: one map per targeted node shape, sorted by :shape identity.

  Shape ids, property paths, severities, and constraint components use the
  same keywords as validation report entries, so a UI can join the two, e.g.
  marking a form field required from a :sh/MinCountConstraintComponent and
  localizing its violations by the stable :shape id."
  [^Shapes shapes]
  (->> (iterator-seq (.iterator shapes))
       (map shape->map)
       (sort-by :shape)
       (vec)))

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
  ;; Called async at boot from dk.cst.dannet.web.instance; deliberately
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

  ;; The base/editorial shapes as plain data, e.g. for deriving UI fields.
  (shapes->spec @shapes)
  (shapes->spec @editorial-shapes)

  ;; The shapes and baseline are parsed once into delays, so after editing
  ;; the .ttl shape files or the baseline EDN, reload this ns to pick them up.
  (require 'dk.cst.dannet.db.shapes :reload)
  #_.)
