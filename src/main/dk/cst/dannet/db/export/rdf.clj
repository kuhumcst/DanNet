(ns dk.cst.dannet.db.export.rdf
  "Export of the RDF graphs as zipped Turtle files with licence and README.
  The web responses also use `ttl-entity` to render a single entity as Turtle."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-file-zip.core :as zip]
            [clojure.walk :as walk]
            [donatello.ttl :as ttl]
            [quoll.rdf :refer [print-escape]]
            [dk.cst.theophrastus :as theo]
            [dk.cst.theophrastus.term :as term]
            [dk.cst.theophrastus.txn :as txn]
            [dk.cst.dannet.db.shapes :as shapes]
            [dk.cst.dannet.release :as release]
            [dk.cst.dannet.prefix :as prefix])
  (:import [clojure.lang Symbol]
           [dk.cst.theophrastus.term LangString Literal]
           [org.apache.jena.rdf.model Model]
           [org.apache.jena.query Dataset]
           [java.io File StringWriter]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- ttl-path
  [path]
  (let [parts      (str/split path #"/")
        filename   (first (str/split (last parts) #"\."))
        parent-dir (str/join "/" (butlast parts))]
    (str parent-dir "/" filename ".ttl")))

(defn export-rdf-model!
  "Export `db` as Turtle to the given zip file `path`.

  The DanNet prefixes are used for the output, although a desired subset of
  :prefixes may also be specified.

  When :validate is true, the written .ttl file is SHACL-validated against
  the base shapes before zipping; validation failure aborts the export
  (see shapes/validate-export!)."
  [path db & {:keys [prefixes validate extra-files]}]
  (let [ttl-file     (ttl-path path)
        ^Model model (:model db)
        prefixes'    (cond->> prefix/prefixes
                       prefixes (into {} (filter (comp prefixes symbol key))))]
    (txn/with-transaction model
      (println "Exporting" path (str "(" (.size model) ")")
               "with prefixes:" (or prefixes "ALL"))
      (io/make-parents path)
      ;; The export prefixes temporarily replace those of the model, e.g. the
      ;; ones imported from TTL files.
      (theo/write (theo/db model {:prefixes prefixes'}) ttl-file)
      ;; Release gate: validate exactly what ships, before it is zipped.
      (when validate
        (shapes/validate-export! ttl-file))
      (zip/zip-files (into [ttl-file] extra-files) path)
      (.clearNsPrefixMap model)))
  nil)

(defn- export-prefixes
  [prefix]
  (get-in prefix/schemas [prefix :export]))

(def license-file
  "Map from CC licence keyword to the bundled plain-text licence resource."
  {:cc-by-sa "bundled/licenses/CC-BY-SA-4.0.txt"
   :cc-by    "bundled/licenses/CC-BY-4.0.txt"
   :cc0      "bundled/licenses/CC0-1.0.txt"})

(def export-licensing
  "Per download prefix: the licence + README template shipped inside its zip.

  Decisions locked in for issue #96: COR RDF is CC0 1.0, the OEWN label
  extension is CC BY 4.0, everything else is CC BY-SA 4.0."
  {'dn             {:license :cc-by-sa :readme "dannet.txt"}
   'dds            {:license :cc-by-sa :readme "dds.txt"}
   'cor            {:license :cc0 :readme "cor.txt"}
   'cor-sem        {:license :cc0 :readme "cor-sem.txt"}
   'frame          {:license :cc-by-sa :readme "framenet.txt"}
   'oewn-extension {:license :cc-by :readme "oewn-extension.txt"}})

(defn render-readme
  "Read the README template `readme` (under resources/bundled/readmes/) and fill
  in the `version` placeholders. {version} is the DanNet release, while the
  OEWN, COR and PreMOn editions are versioned upstream and resolve from
  dk.cst.dannet.release."
  [readme version]
  (-> (slurp (io/resource (str "bundled/readmes/" readme)))
      (str/replace "{version}" version)
      (str/replace "{oewn-version}" release/oewn-version)
      (str/replace "{cor-version}" release/cor-version)
      (str/replace "{cor-ext-version}" release/cor-ext-version)
      (str/replace "{cor-sem-version}" release/cor-sem-version)
      (str/replace "{premon-version}" release/premon-version)))

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
  "Export the models in the :dataset of the `dannet` db into `dir`.

  By default, the complete model is not exported. In the case of a typical
  inference-heavy DanNet instance, this would simply be too slow. To include the
  complete model as an export target, set :complete to true."
  ([{:keys [dataset inference] :as dannet} dir & {:keys [complete]
                                                  :or   {complete false}}]
   (let [in-dir       (partial str dir)
         version      release/to
         complete-ttl (in-dir (prefix/export-file "rdf" 'dn "complete"))
         ^Dataset ds  (:dataset dataset)
         model-uris   (txn/with-read-transaction ds
                        (->> (iterator-seq (.listNames ds))
                             (remove prefix/not-for-export)
                             (doall)))]
     (println "Beginning RDF export of DanNet into" dir)
     (println "----")

     ;; The individual models contained in the dataset.
     (doseq [model-uri model-uris
             :let [graph    (prefix/uri->rdf-resource model-uri)
                   prefix   (prefix/uri->prefix model-uri)
                   filename (in-dir (prefix/export-file "rdf" prefix))]]
       ;; TODO: also gate the other named models (dnc, dns, cor, ...) once
       ;; shapes targeting their namespaces exist; today the SPARQL-based
       ;; targets are scoped to dn:, so validating them would be a no-op.
       (let [[tmp-dir extra-files] (stage-export-files! prefix version)]
         (try
           (export-rdf-model! filename (theo/named-graph dataset graph)
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
           (theo/named-graph dataset prefix/oewn-extension-graph)
           :prefixes (get prefix/oewn-extension :export)
           :extra-files extra-files)
         (finally
           (delete-dir! tmp-dir))))

     ;; The union of the input datasets and schemas + inferred triples.
     ;; This constitutes all data available in the DanNet web presence.
     (if complete
       (export-rdf-model! complete-ttl inference)
       (println "(skipping export of complete.ttl)"))

     (println "----")
     (println "RDF Export of DanNet complete!")))
  ([dannet]
   (export-rdf! dannet "export/rdf/")))

(def donatello-prefixes-base
  (into {} (map (fn [[k v]]
                  [(keyword k) (:uri v)])
                prefix/schemas)))

;; Donatello compatibility with the blank nodes and literals of Theophrastus.
(extend-protocol ttl/Serializable
  Symbol
  (serialize [x] (str "_:" (subs (str x) 1)))

  LangString
  (serialize [x] (str \" (print-escape (str x)) "\"@" (term/lang x)))

  Literal
  (serialize [x] (str \" (print-escape (str x)) "\"^^" (term/datatype x))))

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
  "Replace the blank node maps in `entity` with their properties, sorted by
  key, which Donatello serializes as inline [ ... ] blank nodes in a
  deterministic order."
  [entity]
  (let [sorted (fn [m] (into (array-map) (sort-by key (dissoc m :rdf/about))))]
    (update-vals entity (partial walk/postwalk #(if (map? %) (sorted %) %)))))

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
  (def dataset (:dataset @dk.cst.dannet.web.instance/db))

  ;; Export individual models
  (export-rdf-model! "export/rdf/dannet.zip"
                     (theo/named-graph dataset prefix/dn-graph)
                     :prefixes (export-prefixes 'dn))
  ;; ... with the SHACL release gate enabled, as done by export-rdf!
  (export-rdf-model! "export/rdf/dannet.zip"
                     (theo/named-graph dataset prefix/dn-graph)
                     :prefixes (export-prefixes 'dn)
                     :validate true)
  (export-rdf-model! "export/rdf/dds.zip"
                     (theo/named-graph dataset prefix/dds-graph)
                     :prefixes (export-prefixes 'dds))
  (export-rdf-model! "export/rdf/cor.zip"
                     (theo/named-graph dataset prefix/cor-graph)
                     :prefixes (export-prefixes 'cor))
  (export-rdf-model! "export/rdf/oewn-extension.zip"
                     (theo/named-graph dataset prefix/oewn-extension-graph)
                     :prefixes (get prefix/oewn-extension :export))

  ;; Export the entire dataset as RDF
  (export-rdf! @dk.cst.dannet.web.instance/db)

  ;; Include inferred relations (WARNING: takes a very, very long time)
  (export-rdf! @dk.cst.dannet.web.instance/db "export/rdf/" :complete true)

  ;; Manually run the release gate against an exported dn: artifact (a plain
  ;; .ttl on disk, i.e. before zipping); throws when the baseline is exceeded.
  ;; export-rdf! runs this automatically for the dn: model via :validate.
  (shapes/validate-export! "export/rdf/dannet.ttl")
  #_.)
