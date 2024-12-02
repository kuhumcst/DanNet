(ns dk.cst.dannet.db
  "General database access functions.

  Refer to 'dk.cst.dannet.db.boostrap' for how to bootstrap an instance."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [arachne.aristotle :as aristotle]
            [ont-app.vocabulary.core :as voc]
            [dk.cst.dannet.query :as q]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.hash :as h]
            [dk.cst.dannet.transaction :as txn])
  (:import [java.io File]
           [ont_app.vocabulary.lstr LangStr]
           [org.apache.jena.rdf.model ModelFactory Model ResourceFactory]
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
  (txn/transact dataset
    (if (.containsNamedModel dataset model-uri)
      (.getNamedModel dataset model-uri)
      (-> (.addNamedModel dataset model-uri (ModelFactory/createDefaultModel))
          (.getNamedModel model-uri)))))

(defn get-graph
  "Idempotently get the graph in the `dataset` for the given `model-uri`."
  [^Dataset dataset ^String model-uri]
  (.getGraph (get-model dataset model-uri)))

(defn remove!
  "Remove a `triple` from the Apache Jena `model`.

  NOTE: as with Aristotle queries, _ works as a wildcard value."
  [^Model model [s p o :as triple]]
  {:pre [(some? s) (some? p) (some? o)]}
  (.removeAll
    model
    (when (not= s '_)
      (cond
        (keyword? s)
        (ResourceFactory/createResource (voc/uri-for s))

        (prefix/rdf-resource? s)
        (ResourceFactory/createResource (subs s 1 (dec (count s))))))
    (when (not= p '_)
      (cond
        (keyword? p)
        (ResourceFactory/createProperty (voc/uri-for p))

        (prefix/rdf-resource? p)
        (ResourceFactory/createProperty (subs p 1 (dec (count p))))))
    (when (not= o '_)
      (cond
        (keyword? o)
        (ResourceFactory/createResource (voc/uri-for o))

        (prefix/rdf-resource? o)
        (ResourceFactory/createResource (subs o 1 (dec (count o))))

        (instance? LangStr o)
        (ResourceFactory/createLangLiteral (str o) (.lang o))

        :else
        (ResourceFactory/createTypedLiteral o)))))

(defn safe-add!
  "Add `data` to `g` in a failsafe way."
  [g data]
  (try
    (aristotle/add g data)
    (catch Exception e
      (println (.getMessage e) "->" (subs (str data) 0 240)))
    (finally
      g)))

(h/defn import-files
  "Import `files` into a the model defined by `model-uri` in `dataset` in a safe
  way, optionally applying a `changefn` to the temporary model before import."
  ([dataset model-uri files]
   (import-files dataset model-uri files nil))
  ([dataset model-uri files changefn]
   (println "Importing files:" (str/join ", " (map #(if (instance? File %)
                                                      (.getName %)
                                                      %)
                                                   files)))
   (let [temp-model (ModelFactory/createDefaultModel)
         temp-graph (.getGraph temp-model)]
     (println "... creating temporary in-memory graph")
     (doseq [file files]
       (aristotle/read temp-graph file))

     (when changefn
       (println "... applying changes to temporary graph ")
       (changefn temp-model))

     (println "... persisting temporary graph:" model-uri)
     (txn/transact-exec dataset
       (aristotle/add (get-graph dataset model-uri) temp-graph)))))

(defn update-triples!
  "Update triples in named model `uri` of `dataset` by mapping `f` to `query`
  result maps producing new triples. Optionally, supply one or more
  `triples-to-remove` which may be generic triple patterns."
  [uri dataset query f & [removal :as triples-to-remove]]
  (let [g              (get-graph dataset uri)
        model          (get-model dataset uri)
        ms             (q/run g query)
        triples-to-add (remove nil? (map f ms))]
    (when (not (empty? triples-to-remove))
      (txn/transact-exec model
        (println "... removing triples:"
                 (if (second triples-to-remove)
                   (str removal "... (" (count triples-to-remove) ")")
                   removal))
        (doseq [triple triples-to-remove]
          (remove! model triple))))
    (txn/transact-exec g
      (println "... adding" (count triples-to-add) "updated triples"
               "based on" (count ms) "results for query:" query)
      (safe-add! g triples-to-add))))
