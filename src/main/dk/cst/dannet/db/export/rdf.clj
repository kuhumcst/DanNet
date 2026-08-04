(ns dk.cst.dannet.db.export.rdf
  "Serialization of the graph data in various ways."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-file-zip.core :as zip]
            [arachne.aristotle.registry :as registry]
            [clojure.walk :as walk]
            [donatello.ttl :as ttl]
            [quoll.rdf :refer [print-escape]]
            [ont-app.vocabulary.lstr :as lstr]
            [dk.cst.dannet.db :as db]
            [dk.cst.dannet.db.shapes :as shapes]
            [dk.cst.dannet.release :as release]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.db.transaction :as txn])
  (:import [clojure.lang Symbol]
           [ont_app.vocabulary.lstr LangStr]
           [org.apache.jena.datatypes.xsd XSDDateTime]
           [org.apache.jena.riot RDFDataMgr RDFFormat]
           [org.apache.jena.rdf.model Model]
           [org.apache.jena.query Dataset]
           [java.io File StringWriter]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn add-registry-prefixes!
  "Adds prefixes in use from the Aristotle registry to the `model`."
  [^Model model & {:keys [prefixes]}]
  (doseq [[prefix m] (cond->> (:prefixes registry/*registry*)
                       prefixes (filter (comp prefixes symbol first)))]
    (.setNsPrefix model prefix (::registry/= m))))

(defn- ttl-path
  [path]
  (let [parts      (str/split path #"/")
        filename   (first (str/split (last parts) #"\."))
        parent-dir (str/join "/" (butlast parts))]
    (str parent-dir "/" filename ".ttl")))

;; TODO: alternative RDF formats will not match filepath given by ttl-path
(defn export-rdf-model!
  "Export the `model` to the given zip file `path`. Content defaults to Turtle.

  The current prefixes in the Aristotle registry are used for the output,
  although a desired subset of :prefixes may also be specified.

  When :validate is true, the written .ttl file is SHACL-validated against
  the base shapes before zipping; validation failure aborts the export
  (see shapes/validate-export!).

  See: https://jena.apache.org/documentation/io/rdf-output.html"
  [path ^Model model & {:keys [fmt prefixes validate extra-files]
                        :or   {fmt RDFFormat/TURTLE_PRETTY}}]
  (let [ttl-file (ttl-path path)]
    (txn/transact-exec model
      ;; Clear potentially imported prefixes, e.g. from TTL files
      (.clearNsPrefixMap model)
      (println "Exporting" path (str "(" (.size model) ")")
               "with prefixes:" (or prefixes "ALL"))
      ;; Temporarily add prefixes for export
      (add-registry-prefixes! model :prefixes prefixes)
      (io/make-parents path)
      (RDFDataMgr/write (io/output-stream ttl-file) model ^RDFFormat fmt)
      ;; Release gate: validate exactly what ships, before it is zipped.
      (when validate
        (shapes/validate-export! ttl-file))
      (zip/zip-files (into [ttl-file] extra-files) path)
      ;; Clear temporarily added prefixes
      (.clearNsPrefixMap model)))
  nil)

(defn- export-prefixes
  [prefix]
  (get-in prefix/schemas [prefix :export]))

(def license-file
  "Map from CC licence keyword to the bundled plain-text licence resource."
  {:cc-by-sa "export/licenses/CC-BY-SA-4.0.txt"
   :cc-by    "export/licenses/CC-BY-4.0.txt"
   :cc0      "export/licenses/CC0-1.0.txt"})

(def export-licensing
  "Per download prefix: the licence + README template shipped inside its zip.

  Decisions locked in for issue #96: COR RDF is CC0 1.0, the OEWN label
  extension is CC BY 4.0, everything else is CC BY-SA 4.0."
  {'dn             {:license :cc-by-sa :readme "dannet.txt"}
   'dds            {:license :cc-by-sa :readme "dds.txt"}
   'cor            {:license :cc0 :readme "cor.txt"}
   'oewn-extension {:license :cc-by :readme "oewn-extension.txt"}})

(defn render-readme
  "Read the README template `readme` (under resources/export/readmes/) and fill
  in the `version` placeholders. The OEWN labels track the DanNet release, so
  {version} and {oewn-version} resolve to the same string."
  [readme version]
  (-> (slurp (io/resource (str "export/readmes/" readme)))
      (str/replace "{version}" version)
      (str/replace "{oewn-version}" version)))

(defn copy-license!
  "Copy the bundled licence text for `license-key` to `dest` (typically a file
  named LICENSE, so it ships under that name inside a zip)."
  [license-key dest]
  (with-open [in (io/input-stream (io/resource (license-file license-key)))]
    (io/copy in (io/file dest))))

(defn- delete-dir!
  "Recursively delete `dir` (children before parents)."
  [^File dir]
  (doseq [^File f (reverse (file-seq dir))]
    (.delete f)))

(defn stage-export-files!
  "Materialise LICENSE + README.txt for `prefix` in a fresh temp dir so both
  ship under those exact names inside the zip (clj-file-zip names entries by
  basename). Returns [tmp-dir extra-files] for use with :extra-files; the
  caller is responsible for deleting tmp-dir afterwards."
  [prefix version]
  (let [{:keys [license readme]} (get export-licensing prefix)
        tmp-dir (.toFile (Files/createTempDirectory
                           "dannet-export" (make-array FileAttribute 0)))
        lic     (io/file tmp-dir "LICENSE")
        rdm     (io/file tmp-dir "README.txt")]
    (copy-license! license lic)
    (spit rdm (render-readme readme version))
    [tmp-dir [(.getPath lic) (.getPath rdm)]]))

(defn export-rdf!
  "Export the models of the RDF `dataset` into `dir`.

  By default, the complete model is not exported. In the case of a typical
  inference-heavy DanNet instance, this would simply be too slow. To include the
  complete model as an export target, set :complete to true."
  ([{:keys [model dataset] :as dannet} dir & {:keys [complete]
                                              :or   {complete false}}]
   (let [in-dir       (partial str dir)
         version      release/to
         complete-ttl (in-dir (prefix/export-file "rdf" 'dn "complete"))
         model-uris   (txn/transact dataset
                        (->> (iterator-seq (.listNames ^Dataset dataset))
                             (remove prefix/not-for-export)
                             (doall)))]
     (println "Beginning RDF export of DanNet into" dir)
     (println "----")

     ;; The individual models contained in the dataset.
     (doseq [model-uri model-uris
             :let [^Model model (db/get-model dataset model-uri)
                   prefix       (prefix/uri->prefix model-uri)
                   filename     (in-dir (prefix/export-file "rdf" prefix))]]
       ;; TODO: also gate the other named models (dnc, dns, cor, ...) once
       ;; shapes targeting their namespaces exist; today the SPARQL-based
       ;; targets are scoped to dn:, so validating them would be a no-op.
       (let [[tmp-dir extra-files] (stage-export-files! prefix version)]
         (try
           (export-rdf-model! filename model
                              :prefixes (export-prefixes prefix)
                              :validate (= prefix 'dn)
                              :extra-files extra-files)
           (finally
             (delete-dir! tmp-dir)))))

     ;; The OEWN extension data is exported separately from the other models,
     ;; since it isn't connected to a separate prefix (= graph).
     (let [[tmp-dir extra-files] (stage-export-files! 'oewn-extension version)]
       (try
         (export-rdf-model!
           (in-dir (get-in prefix/oewn-extension [:download "rdf" :default]))
           (db/get-model dataset prefix/oewn-extension-uri)
           :prefixes (get prefix/oewn-extension :export)
           :extra-files extra-files)
         (finally
           (delete-dir! tmp-dir))))

     ;; The union of the input datasets.
     #_(let [union-model (.getUnionModel dataset)]
         (export-rdf-model! merged-ttl union-model))

     ;; The union of the input datasets and schemas + inferred triples.
     ;; This constitutes all data available in the DanNet web presence.
     (if complete
       (export-rdf-model! complete-ttl model)
       (println "(skipping export of complete.ttl)"))

     (println "----")
     (println "RDF Export of DanNet complete!")))
  ([^Dataset dataset]
   (export-rdf! dataset "export/rdf/")))

(def donatello-prefixes-base
  (into {} (map (fn [[k v]]
                  [(keyword k) (:uri v)])
                prefix/schemas)))

;; Donatello compatibility with Aristotle blank nodes and ont-app LangStrings.
(extend-protocol ttl/Serializable
  Symbol
  (serialize [x] (str "_:" (subs (str x) 1)))

  LangStr
  (serialize [x] (str \" (print-escape (str x)) "\"@" (lstr/lang x)))

  XSDDateTime
  (serialize [x] (str "\"" x "\"^^xsd:dateTime")))

(defn flatten-nested-sets
  "Flatten nested sets in `entity` values. Converts #{#{a b}} to #{a b}."
  [entity]
  (walk/postwalk
    (fn [x]
      (if (and (set? x)
               (= 1 (count x))
               (set? (first x)))
        (first x)
        x))
    entity))

(defn inline-blank-nodes
  "Replace blank node symbols (with metadata) in `entity` with their property
  maps, allowing Donatello to serialize them as inline [ ... ] blank nodes.
  Properties are sorted by key to ensure a deterministic output order."
  [entity]
  (walk/postwalk
    (fn [x]
      (if (and (symbol? x) (meta x))
        (into (array-map) (sort-by key (meta x)))
        x))
    entity))

(defn donatello-prefixes
  "Prepare prefixes in `entity` for Donatello TTL output."
  [entity]
  (let [prefixes (atom #{})]
    (walk/postwalk
      #(when (keyword? %)
         (swap! prefixes conj (namespace %)))
      entity)
    (->> (remove nil? @prefixes)
         (map keyword)
         (select-keys donatello-prefixes-base))))

(defn- label-entity-group
  "Categorise a label entity as :relations, :synsets, or :other."
  [[subject _]]
  (let [ns (when (keyword? subject) (namespace subject))
        n  (when (keyword? subject) (name subject))]
    (cond
      (or (and (= ns "dn") (str/starts-with? n "synset-"))
          (str/starts-with? (or n "") "oewn-"))
      :synsets

      ;; TODO: remove "inherit" once #182 has shipped (inheritance resources
      ;;       are now blank nodes, so named inherit-* resources no longer occur)
      (and (= ns "dn")
           (re-find #"^(word|sense|inherit)-" n))
      :other

      :else
      :relations)))

(defn- write-triple-group!
  "Write `entities` as triples to `sw`, prefixed by a `comment`."
  [sw comment entities]
  (when (seq entities)
    (.write sw (str "# " comment "\n"))
    (.write sw (-> (with-out-str
                     (doseq [[s p] entities]
                       (ttl/write-triples! *out* s p)))
                   (str/replace #"\n\n" "\n")))
    (.write sw "\n")))

(defn ttl-entity
  "Get TTL output for expanded `entity` with inlined blank nodes + labels."
  [entity & [base]]
  (let [entity*        (-> entity flatten-nested-sets inline-blank-nodes)
        label-entities (:entities (meta entity))
        {:keys [relations
                synsets
                other]} (group-by label-entity-group label-entities)
        all-data       (cons entity* (map second label-entities))
        prefixes       (reduce #(merge %1 (donatello-prefixes %2))
                               {} all-data)]
    (with-open [sw (StringWriter.)]
      (when base
        (ttl/write-base! sw base))
      (ttl/write-prefixes! sw prefixes)
      (ttl/write-triples! sw (:subject (meta entity)) entity*)
      (write-triple-group! sw "Relation labels" relations)
      (write-triple-group! sw "Synset labels" synsets)
      (write-triple-group! sw "Other resource labels" other)
      (str sw))))

(comment
  (def dataset (:dataset @dk.cst.dannet.web.resources/db))

  ;; Export individual models
  (export-rdf-model! "export/rdf/dannet.zip" (db/get-model dataset prefix/dn-uri)
                     :prefixes (export-prefixes 'dn))
  ;; ... with the SHACL release gate enabled, as done by export-rdf!
  (export-rdf-model! "export/rdf/dannet.zip" (db/get-model dataset prefix/dn-uri)
                     :prefixes (export-prefixes 'dn)
                     :validate true)
  (export-rdf-model! "export/rdf/dds.zip" (db/get-model dataset prefix/dds-uri)
                     :prefixes (export-prefixes 'dds))
  (export-rdf-model! "export/rdf/cor.zip" (db/get-model dataset prefix/cor-uri)
                     :prefixes (export-prefixes 'cor))
  (export-rdf-model! "export/rdf/oewn-extension.zip"
                     (db/get-model dataset prefix/oewn-extension-uri)
                     :prefixes (get prefix/oewn-extension :export))

  ;; Export the entire dataset as RDF
  (export-rdf! @dk.cst.dannet.web.resources/db)

  ;; Include inferred relations (WARNING: takes a very, very long time)
  (export-rdf! @dk.cst.dannet.web.resources/db "export/rdf/" :complete true)

  ;; Manually run the release gate against an exported dn: artifact (a plain
  ;; .ttl on disk, i.e. before zipping); throws when the baseline is exceeded.
  ;; export-rdf! runs this automatically for the dn: model via :validate.
  (shapes/validate-export! "export/rdf/dannet.ttl")
  #_.)
