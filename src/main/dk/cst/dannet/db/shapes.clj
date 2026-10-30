(ns dk.cst.dannet.db.shapes
  "SHACL validation of the DanNet graph.

  Bundles structural shapes (resources/schemas/internal/shapes/) with helpers
  to validate a graph (or a single focus node) and return violations as
  Clojure data. The shapes are split by target:

    * base.ttl      - invariants of the asserted (base) graph
    * inferred.ttl  - relation completeness; INFERRED model only
    * editorial.ttl - stricter rules gating newly edited data (validate-node)

  Current uses:
    * clojure.test assertions over small fixtures (see test ns),
    * a non-fatal bootstrap/CI check that logs anomalies via Telemere,
      comparing violation counts to a known baseline (shapes-baseline.edn), and
    * a release gate aborting RDF exports on baseline regressions
      (`validate-export!`, called from dk.cst.dannet.db.export.rdf)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [dk.cst.theophrastus :as theo]
            [dk.cst.theophrastus.shacl :as shacl]
            [dk.cst.dannet.db :as db]
            [dk.cst.dannet.prefix :as prefix]
            [taoensso.telemere :as t])
  (:import [org.apache.jena.rdf.model Model]
           [org.apache.jena.shacl Shapes]
           [org.apache.jena.graph Graph Node NodeFactory Triple]
           [org.apache.jena.riot RDFDataMgr]))

;; TODO: shapes are kept internal for now, but since SHACL shapes are plain
;; RDF, consider eventually publishing them as Linked Open Data under the dns:
;; namespace (alongside dannet-schema.ttl, cf. prefix.cljc) once they have
;; stabilised, e.g. letting the future editing UI derive required fields from
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
  (shacl/parse (map io/resource (cons resource resources))))

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

;; The report results have DanNet keywords for IRIs, so they can be diffed
;; against the EDN baseline. :source-shape is the stable identity used for
;; baselines and grouping; the reason every property shape in the TTL has an
;; explicit IRI instead of the usual blank node. :sh/Violation blocks a write (see
;; shacl/violations?); :sh/Warning only surfaces in the UI.
(defn validate
  "Validate `db` against `shapes` (a Jena `Shapes`) in a read transaction,
  returning

    {:conforms? boolean, :results [{:focus-node ...} ...]}."
  ([db]
   (validate db @shapes))
  ([db ^Shapes shapes]
   (shacl/validate db shapes)))

(defn validate-node
  "Validate a single focus `node` of `db` against `shapes` in a read
  transaction. Only shapes targeting the node are checked, making this cheap
  enough to gate individual writes (e.g. an RDF Patch).

  The `node` may be a Jena Node, a prefixed keyword (e.g. :dn/synset-999), or
  an IRI in angle brackets. Returns the same result map as `validate`."
  ([db node]
   (validate-node db @shapes node))
  ([db ^Shapes shapes node]
   (shacl/validate db shapes node)))

(def baseline-resource
  "schemas/internal/shapes-baseline.edn")

(def baseline
  "Known violation counts per property shape, i.e. the accepted status quo.
  Counts *exceeding* the baseline indicate a regression; counts below it mean
  data was fixed (update the baseline file accordingly)."
  (delay (some-> (io/resource baseline-resource) slurp edn/read-string)))

(defn by-shape
  "Frequencies of violation `results` grouped by their source property shape."
  [results]
  (frequencies (map :source-shape results)))

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
  results to the known `baseline`; warnings never count against it.
  Also adds :violations (the blocking result count) for logging purposes."
  [result]
  (let [violations (:sh/Violation (shacl/by-severity result))]
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
  (let [{:keys [violations exceeded] :as result}
        (against-baseline (validate (:base db)))]
    (t/log! {:level (cond (seq exceeded) :error
                          (not (:conforms? result)) :warn
                          :else :info)
             :id    :dannet.shapes/validate
             :data  {:conforms?  (:conforms? result)
                     :violations violations
                     :by-shape   (by-shape (:results result))
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
  (let [result (validate (:inference db) @inferred-shapes)
        counts (by-shape (:results result))]
    (t/log! {:level (if (:conforms? result) :info :warn)
             :id    :dannet.shapes/validate-inferred
             :data  {:conforms?  (:conforms? result)
                     :violations (count (:sh/Violation
                                          (shacl/by-severity result)))
                     :by-shape   counts}}
            "SHACL validation of inferred graph")
    result))

(def schema-graph
  "The schemas that DanNet loads (prefix/schema-uris) as one graph, parsed once."
  (delay (.getGraph ^Model (db/->schema-model prefix/schema-uris))))

(def unschematized-predicates
  "Predicates that the DanNet graph may use although no loaded schema gives
  them a label: the VoID and schema.org vocabularies are not loaded."
  #{"http://rdfs.org/ns/void#triples"
    "http://schema.org/email"})

(defn undefined-predicates
  "The predicates in the `data` graph that the `schema` graph gives no
  rdfs:label, e.g. a dn: predicate written in place of a dns: one.

  The unschematized-predicates and the RDF container properties rdf:_1,
  rdf:_2, ... are not included."
  [^Graph data ^Graph schema]
  (let [label (NodeFactory/createURI "http://www.w3.org/2000/01/rdf-schema#label")]
    (->> (iterator-seq (.find data Node/ANY Node/ANY Node/ANY))
         (map #(.getPredicate ^Triple %))
         (distinct)
         (remove #(.contains schema ^Node % label Node/ANY))
         (map str)
         (remove unschematized-predicates)
         (remove #(re-matches #"http://www\.w3\.org/1999/02/22-rdf-syntax-ns#_\d+" %))
         (sort))))

(defn validate-export!
  "Validate the exported Turtle file at `path` (a plain .ttl on disk) against
  the default base shapes, comparing :sh/Violation counts to the known
  baseline. A release gate: throws ex-info when violations exceed the
  baseline, or when the file uses predicates that no loaded schema defines
  (see undefined-predicates); warnings and baselined violations only log.

  Returns the result map with added :violations, :exceeded and :undefined
  keys when the gate passes."
  [path]
  ;; Loads the artifact into a fresh in-memory graph so the gate validates
  ;; exactly what ships, not the live TDB2 graph it was exported from.
  (let [path      (str path)
        graph     (RDFDataMgr/loadGraph path)
        undefined (undefined-predicates graph @schema-graph)
        {:keys [violations exceeded] :as result}
        (against-baseline (validate (theo/db graph {:prefixes prefix/prefixes})))]
    (t/log! {:level (cond (or (seq exceeded) (seq undefined)) :error
                          (not (:conforms? result)) :warn
                          :else :info)
             :id    :dannet.shapes/validate-export
             :data  {:path       path
                     :conforms?  (:conforms? result)
                     :violations violations
                     :exceeded   exceeded
                     :undefined  undefined}}
            "SHACL validation of exported artifact")
    (when (seq exceeded)
      (throw (ex-info (str "SHACL violations exceed baseline in " path)
                      {:path     path
                       :exceeded exceeded})))
    (when (seq undefined)
      (throw (ex-info (str "Predicates without a schema definition in " path)
                      {:path      path
                       :undefined undefined})))
    (assoc result :undefined undefined)))

(comment
  ;; Inspect the currently accepted violation counts.
  @baseline

  ;; The shapes and baseline are parsed once into delays, so after editing
  ;; the .ttl shape files or the baseline EDN, reload this ns to pick them up.
  (require 'dk.cst.dannet.db.shapes :reload)
  #_.)
