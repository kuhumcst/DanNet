(ns dk.cst.dannet.db
  "General database access functions.

  Refer to 'dk.cst.dannet.db.boostrap' for how to bootstrap an instance."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.telemere :as t]
            [dk.cst.theophrastus :as theo]
            [dk.cst.theophrastus.tdb2 :as tdb2]
            [dk.cst.theophrastus.txn :as txn]
            [dk.cst.dannet.db.query :as q]
            [dk.cst.dannet.hash :as h])
  (:import [java.io File]
           [org.apache.jena.rdf.model ModelFactory Model]
           [org.apache.jena.query Dataset]))

;; https://jena.apache.org/documentation/io/index.html
(defn ->schema-model
  "Create a Model containing the schemas found at the given `uris`."
  [uris]
  (reduce (fn [^Model model schema-uri]
            ;; Using an InputStream will ensure that the URI can be an internal
            ;; resource of a JAR file. If the input is instead a string, Jena
            ;; attempts to load it as a File which JAR file resources cannot be.
            (let [s  (str schema-uri)
                  in (io/input-stream schema-uri)]
              (cond
                (str/ends-with? s ".ttl")
                (.read model in nil "TURTLE")

                (str/ends-with? s ".n3")
                (.read model in nil "N3")

                :else
                (.read model in nil "RDF/XML"))))
          (ModelFactory/createDefaultModel)
          uris))

(defn get-model
  "Idempotently get the model in the `dataset` for the given `model-uri`."
  [^Dataset dataset ^String model-uri]
  (txn/calculate dataset
    (fn []
      (if (.containsNamedModel dataset model-uri)
        (.getNamedModel dataset model-uri)
        (-> (.addNamedModel dataset model-uri (ModelFactory/createDefaultModel))
            (.getNamedModel model-uri))))))

(defn get-graph
  "Idempotently get the graph in the `dataset` for the given `model-uri`."
  [^Dataset dataset ^String model-uri]
  (.getGraph (get-model dataset model-uri)))

(defn compact!
  "Compact the TDB2 database backing `dataset`, reclaiming the space left by
  in-place updates, and delete the superseded generation.

  Run before zipping a release database: TDB2 only reclaims this space when
  compacted, and a cycle of `make-release-changes!` can leave the majority of
  the files as dead space."
  [^Dataset dataset]
  (tdb2/compact! dataset))

(defn remove!
  "Remove the triples that the `patterns` match from the Apache Jena `model`
  in one write transaction: one [s p o] pattern or a coll of them, in which _
  matches any value, as in theophrastus/retract!."
  [^Model model patterns]
  (theo/retract! (q/->db model) patterns))

(defn safe-add!
  "Add `data` to `g` in a failsafe way."
  [g data]
  (try
    (theo/transact! (q/->db g) data)
    (catch Exception e
      (t/log! {:level :warn
               :id    :dannet.db/safe-add-failed
               :error e
               :data  {:data-sample (subs (str data) 0 (min 240 (count (str data))))}}
              "Failed to add data; continuing"))
    (finally
      g)))

(h/defn import-files
  "Import `files` into a the model defined by `model-uri` in `dataset` in a safe
  way, optionally applying a `changefn` to the temporary model before import."
  ([dataset model-uri files]
   (import-files dataset model-uri files nil))
  ([dataset model-uri files changefn]
   (t/log! {:level :info
            :id    :dannet.db/import-files
            :data  {:model model-uri
                    :files (mapv #(if (instance? File %) (.getName ^File %) %) files)}}
           "Importing files")
   (let [temp-model (ModelFactory/createDefaultModel)]
     (t/log! {:level :debug :id :dannet.db/temp-graph-create} "Creating temporary in-memory graph")
     (theo/read! (q/->db temp-model) files)

     (when changefn
       (t/log! {:level :debug :id :dannet.db/temp-graph-changes} "Applying changes to temporary graph")
       (changefn temp-model))

     (t/log! {:level :debug
              :id    :dannet.db/temp-graph-persist
              :data  {:model model-uri}}
             "Persisting temporary graph")
     (theo/transact! (q/->db (get-graph dataset model-uri)) temp-model))))

(defn update-triples!
  "Update triples in named model `uri` of `dataset` by mapping `f` to `query`
  result maps producing new triples. The `query` may also be a [query bindings]
  pair, with bindings as in q/run.

  Optionally, supply one or more `triples-to-remove` which may either be generic
  triple patterns -OR- if only a one `removal` is supplied it may serve as an fn
  applied to the result maps (like `f`) producing removal triples instead."
  [uri dataset query f & [removal :as triples-to-remove]]
  (let [g                  (get-graph dataset uri)
        model              (get-model dataset uri)
        ms                 (set (apply q/run g (if (vector? query) query [query])))
        triples-to-add     (remove nil? (map f ms))
        triples-to-remove' (if (fn? removal)
                             (remove nil? (map removal ms))
                             triples-to-remove)]
    (when (not (empty? triples-to-remove'))
      (t/log! {:level :debug
               :id    :dannet.db/remove-triples
               :data  {:count   (count triples-to-remove')
                       :example (first triples-to-remove')}}
              "Removing triples")
      (remove! model triples-to-remove'))
    (t/log! {:level :debug
             :id    :dannet.db/add-triples
             :data  {:added   (count triples-to-add)
                     :results (count ms)
                     :query   query}}
            "Adding updated triples")
    (safe-add! g triples-to-add)))
