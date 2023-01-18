(ns dk.cst.dannet.db
  "Represent DanNet as an in-memory graph or within a persisted database (TDB)."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.data.csv :as csv]
            [arachne.aristotle :as aristotle]
            [arachne.aristotle.registry :as registry]
            [ont-app.igraph-jena.core :as igraph-jena]
            [ont-app.igraph.core :as igraph]
            [ont-app.vocabulary.core :as voc]
            [flatland.ordered.map :as fop]
            [ont-app.vocabulary.lstr :refer [->LangStr]]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.db.csv :as db.csv]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.web.components :as com]
            [dk.cst.dannet.bootstrap :as bootstrap]
            [dk.cst.dannet.hash :as h]
            [dk.cst.dannet.query :as q]
            [dk.cst.dannet.query.operation :as op]
            [dk.cst.dannet.transaction :as txn])
  (:import [ont_app.vocabulary.lstr LangStr]
           [org.apache.jena.riot RDFDataMgr RDFFormat]
           [org.apache.jena.tdb TDBFactory]
           [org.apache.jena.tdb2 TDB2Factory]
           [org.apache.jena.rdf.model ModelFactory Model ResourceFactory Statement]
           [org.apache.jena.query Dataset DatasetFactory]
           [org.apache.jena.reasoner.rulesys GenericRuleReasoner Rule]
           [java.time LocalDateTime]
           [java.time.format DateTimeFormatter]
           [java.io File]))

;; TODO: why doubling in http://localhost:3456/dannet/data/synset-12346 ?
;; TODO: duplicates? http://localhost:3456/dannet/data/synset-29293
;;       and http://localhost:3456/dannet/data/synset-29294

(def schema-uris
  "URIs where relevant schemas can be fetched."
  (->> (for [{:keys [alt uri export]} (vals prefix/schemas)]
         (when-not export
           (if alt
             (cond
               (= alt :no-schema)
               nil

               (or (str/starts-with? alt "http://")
                   (str/starts-with? alt "https://"))
               alt

               :else
               (io/resource alt))
             uri)))
       (filter some?)))

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

(def reasoner
  (let [rules (Rule/parseRules (slurp (io/resource "etc/dannet.rules")))]
    (doto (GenericRuleReasoner. rules)
      (.setOWLTranslation true)
      (.setMode GenericRuleReasoner/HYBRID)
      (.setTransitiveClosureCaching true))))

(defn ->sense-label-triples
  "Create label triples for the—otherwise unlabeled—senses of a DanNet `g`.

  These labels are borrowed from the existing synset labels, as those typically
  include all of the labeled senses contained in the synset."
  [g]
  (let [label-cache (atom {})]
    (->> (for [{:syms [?sense
                       ?wlabel
                       ?slabel]} (->> (q/run g op/sense-label-targets)
                                      (sort-by '?sense))
               :let [word        (-> (str ?wlabel)
                                     (subs 1 (dec (count (str ?wlabel)))))
                     compatible? (fn [sense]
                                   (= word (str/replace sense #"_[^ ,]+" "")))
                     labels      (->> (str ?slabel)
                                      (com/sense-labels com/synset-sep)
                                      (filter compatible?))]]
           (case (count labels)
             0 nil
             1 [?sense :rdfs/label (->LangStr (first labels) "da")]

             ;; If more than one compatible label exists, the sense labels are
             ;; picked up one-by-one, storing the remaining labels temporarily
             ;; in a 'label-cache'. This assumes correct ordering of both sense
             ;; triples and each original synset labels!
             ;; Relevant example: http://localhost:3456/dannet/data/synset-12346
             (if-let [label (first (get @label-cache word))]
               (do
                 (swap! label-cache update word rest)
                 [?sense :rdfs/label (->LangStr label "da")])
               (do
                 (swap! label-cache assoc word (rest labels))
                 [?sense :rdfs/label (->LangStr (first labels) "da")]))))
         (remove nil?)
         (into #{}))))

(defn ->example-triples
  "Create example triples from a DanNet `g` and the `examples` from 'imports'."
  [g examples]
  (for [[synset lemma] (keys examples)]
    (let [results (q/run g op/example-targets {'?synset synset
                                               '?lemma  lemma})
          example (get examples [synset lemma])]
      (apply set/union (for [{:syms [?sense]} results]
                         (when example
                           #{[?sense :lexinfo/senseExample example]}))))))

(defn inserted-by-DanNet-senses
  "Query the graph `g` for unlabeled, 'Inserted by DanNet' senses."
  [g]
  (update-vals (group-by '?synset (doall (q/run g op/unlabeled-senses)))
               (fn [results]
                 (let [from-dsl? #(re-find #"_" %)
                       labels    (-> (first results)
                                     (get '?label)
                                     (str)
                                     (str/replace #"\{|\}" "")
                                     (#(com/sense-labels #";" %)))]
                   [(remove from-dsl? labels)
                    (sort (map '?sense results))]))))

;; TODO: do not duplicate existing words, e.g. "kniv"
;;       http://localhost:3456/dannet/data/word-s24000051
;;       http://localhost:3456/dannet/data/word-11026643
(defn ->DN-triples
  "Synthesize triples for 'Inserted by DanNet' senses found in the graph `g`.
  This function *only* performs this function for this particular set of senses.

  For now, cases where both a sense prefixed with 'DN:' and another unlabeled
  sense exist within the same synset count as edge cases, i.e. they will not be
  synthesized. For this reason, the '->sense-label-triples' function should
  execute both and after synthesizing DN triples, as this ensures that senses
  that are not prefixed with 'DN:' are properly prelabeled. Running the
  '->sense-label-triples' function once more after synthesizing DN triples will
  label the newly synthesized DN triples separately.

  The word IDS synthesized from the sense IDs and are marked with an 's' for
  sense/synthetic."
  [g]
  (let [edge-case? (fn [[_ [labels senses]]]
                     (not= (count labels) (count senses)))
        ->triples  (fn [[synset [labels senses]]]
                     (map (fn [label sense]
                            (let [synset-id (re-find #"\d+" (str synset))
                                  sense-id  (re-find #"\d+" (str sense))
                                  word-id   (str "s" sense-id)]
                              (set/union (bootstrap/->sense-triples
                                           [sense-id word-id synset-id nil nil])
                                         (bootstrap/->word-triples
                                           [word-id label nil nil]))))
                          labels
                          senses))]
    (->> (inserted-by-DanNet-senses g)
         (remove edge-case?)
         (mapcat ->triples)
         (apply set/union))))

(defn ->superfluous-definition-triples
  "Return duplicate/superfluous :skos/definition triples found in Graph `g`.

  Basically, the merge of various data sources that occurred as part of the 2023
  data import results in duplicate definitions. In cases where a duplicate can
  be shown to be either identical to or contained within another definition,
  we can safely delete the duplicate triple."
  [g]
  (->> (q/run g op/superfluous-definitions)
       (map (fn [{:syms [?synset ?definition ?otherDefinition]}]
              (let [->ds (fn [d]
                           (when-let [d' (not-empty (str d))]
                             (subs d' 0 (dec (count d')))))
                    d1   (->ds ?definition)
                    d2   (->ds ?otherDefinition)]
                (when-let [d (cond
                               (nil? d1) ?definition
                               (nil? d2) ?otherDefinition
                               (str/starts-with? d2 d1) ?definition
                               (str/starts-with? d1 d2) ?otherDefinition)]
                  [?synset :skos/definition d]))))
       (remove nil?)))

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
  "Remove a `triple` from the Apache Jena `model`."
  [^Model model [s p o :as triple]]
  (.removeAll
    model
    (ResourceFactory/createResource (voc/uri-for s))
    (ResourceFactory/createProperty (voc/uri-for p))
    (cond
      (keyword? o)
      (ResourceFactory/createResource (voc/uri-for o))

      (instance? LangStr o)
      (ResourceFactory/createLangLiteral (str o) (.lang o))

      :else
      (ResourceFactory/createTypedLiteral o))))

(h/defn add-bootstrap-import!
  "Add the `bootstrap-imports` of the old DanNet CSV files to a Jena `dataset`."
  [dataset bootstrap-imports]
  (let [{:keys [examples]} (get bootstrap-imports prefix/dn-uri)
        dn-graph    (get-graph dataset prefix/dn-uri)
        senti-graph (get-graph dataset prefix/senti-uri)
        union-graph (.getGraph (.getUnionModel dataset))]

    (println "Beginning DanNet bootstrap import process")
    (println "----")

    ;; The individual graphs are populated with the bootstrap data,
    ;; except for the example sentence data which needs to be added separately.
    (doseq [[uri m] (update bootstrap-imports prefix/dn-uri dissoc :examples)]
      (println "Importing triples into the" uri "graph...")
      (doseq [[k row] m
              :let [g (get-graph dataset uri)]]
        (println "  ... adding" k "triples.")
        (txn/transact-exec g
          (->> (bootstrap/read-triples row)
               (remove nil?)
               (reduce aristotle/add g)))))

    (let [triples  (doall (->superfluous-definition-triples dn-graph))
          dn-model (get-model dataset prefix/dn-uri)]
      (println "Removing" (count triples) "superfluous definitions...")
      (txn/transact-exec dn-model
        (doseq [triple triples]
          (remove! dn-model triple))))

    ;; As ->example-triples needs to read the graph to create
    ;; triples, it must be done after the write transaction.
    ;; Clojure's laziness also has to be accounted for.
    (let [examples'       (apply merge (bootstrap/read-triples examples))
          example-triples (doall (->example-triples dn-graph examples'))]
      (println "Importing :examples triples...")
      (txn/transact-exec dn-graph
        (aristotle/add dn-graph example-triples)))

    ;; Missing words for the 2023 adjectives data are synthesized from senses.
    ;; This step cannot be performed as part of the basic bootstrap since we
    ;; must avoid synthesizing new words for existing senses in the data!
    (let [missing-words (doall (bootstrap/synthesize-missing-words dn-graph))]
      (println "Synthesizing missing words for 2023 adjectives...")
      (txn/transact-exec dn-graph
        (aristotle/add dn-graph missing-words)))

    ;; Missing words for the 2023 adjectives data are synthesized from senses.
    ;; This step cannot be performed as part of the basic bootstrap since we
    ;; must avoid synthesizing new words for existing senses in the data!
    (let [inheritance (doall (bootstrap/synthesize-inherited-relations dn-graph))]
      (println "Synthesizing inherited relations for 2023 adjectives...")
      (txn/transact-exec dn-graph
        (aristotle/add dn-graph inheritance)))

    ;; Senses are unlabeled in the raw dataset and also need to query the graph
    ;; to steal labels from the words they are senses of.
    (let [sense-label-triples (doall (->sense-label-triples dn-graph))]
      (println "Stealing sense labels...")
      (txn/transact-exec dn-graph
        (aristotle/add dn-graph sense-label-triples)))

    ;; Senses that have been 'Inserted by DanNet' have corresponding words and
    ;; other relevant triples synthesized. This must run *after* the initial
    ;; execution of '->sense-label-triples'.
    (let [DN-triples (doall (->DN-triples dn-graph))]
      (println "Synthesizing words...")
      (txn/transact-exec dn-graph
        (aristotle/add dn-graph DN-triples)))

    ;; The second run of ->sense-label-triples; see '->DN-triples' docstring;
    ;; labels the remaining triples, i.e. the ones created in the previous step.
    (let [sense-label-triples (doall (->sense-label-triples dn-graph))]
      (println "Label remaining triples...")
      (txn/transact-exec dn-graph
        (aristotle/add dn-graph sense-label-triples)))

    ;; In the sentiment data, several thousand senses do not have sense-level
    ;; sentiment data. In those case we can try to synthesize from the words
    ;; that *do* have.
    (let [senti-triples (->> (q/run union-graph op/missing-sense-sentiment)
                             (group-by '?word)
                             (filter #(= 1 (count (second %))))
                             (mapcat second)
                             (map (fn [{:syms [?sense ?opinion]}]
                                    [?sense :dns/sentiment ?opinion]))
                             (doall))
          n             (count senti-triples)]
      (println (str "Synthesizing " n " sense sentiment triples..."))
      (txn/transact-exec senti-graph
        (aristotle/add senti-graph senti-triples)))

    ;; Likewise, all synsets whose senses are collectively unambiguously will
    ;; have sentiment triples synthesized. If the senses have differing polarity
    ;; values, a basic 1 or -1 is chosen as the synset sentiment polarity.
    ;; In my testing, ~30 of the queried synsets had some ambiguity.
    (let [->triples (fn [[_ coll]]
                      (let [{:syms [?synset ?pval ?pclass]} (first coll)
                            polarity (cond
                                       (apply = (map '?pval coll)) ?pval
                                       (= ?pclass :marl/Negative) -1
                                       (= ?pclass :marl/positive) 1
                                       :else 0)
                            opinion  (symbol (str "_opinion-" (name ?synset)))]
                        #{[?synset :dns/sentiment opinion]
                          [opinion :marl/hasPolarity ?pclass]
                          [opinion :marl/polarityValue polarity]}))
          triples   (->> (q/run union-graph op/missing-synset-sentiment)
                         (group-by '?synset)
                         (filter #(apply = (map '?pclass (second %))))
                         (mapcat ->triples)
                         (doall))
          n         (count triples)]
      (println (str "Synthesizing " n " synset sentiment triples..."))
      (txn/transact-exec senti-graph
        (aristotle/add senti-graph triples)))

    (println "----")
    (println "DanNet bootstrap done!")

    dataset))

(h/defn add-open-english-wordnet!
  "Add the Open English WordNet to a Jena `dataset`."
  [dataset]
  (println "Importing Open English Wordnet...")
  (txn/transact-exec dataset
    (aristotle/read (get-graph dataset "https://en-word.net/")
                    "bootstrap/other/english/english-wordnet-2022.ttl"))
  (println "Open English Wordnet imported!"))

(defn ->dataset
  "Get a Dataset object of the given `db-type`. TDB also requires a `db-path`."
  [db-type & [db-path]]
  (case db-type
    :tdb1 (TDBFactory/createDataset ^String db-path)
    :tdb2 (TDB2Factory/connectDataset ^String db-path)
    :in-mem (DatasetFactory/create)
    :in-mem-txn (DatasetFactory/createTxnMem)))

(defn- bootstrap-files
  "Collect all bootstrap files found in `bootstrap-imports`."
  [bootstrap-imports]
  (let [files     (atom #{})
        add-file! (fn [x]
                    (when (and (string? x)
                               (str/starts-with? x "bootstrap/"))
                      (swap! files conj (io/file x))))]
    (clojure.walk/postwalk add-file! bootstrap-imports)
    @files))

(defn- log-entry
  [full-db-path files]
  (let [now       (LocalDateTime/now)
        formatter (DateTimeFormatter/ofPattern "yyyy/MM/dd HH:mm:ss")
        filenames (sort (map #(.getName ^File %) files))]
    (str
      "Location: " full-db-path "\n"
      "Created: " (.format now formatter) "\n"
      "Input data: " (str/join ", " filenames))))

(defn- pos-hash
  "Undo potentially negative number by bit-shifting when hashing `x`."
  [x]
  (unsigned-bit-shift-right (hash x) 1))

(defn ->dannet
  "Create a Jena Dataset from DanNet 2.2 imports based on the options:

    :bootstrap-imports - DanNet CSV imports (kvs of ->triple fns + table data).
    :db-type           - :tdb1, :tdb2, :in-mem, and :in-mem-txn are supported
    :db-path           - Where to persist the TDB1/TDB2 data.
    :schema-uris       - A collection of URIs containing schemas.

   TDB 1 does not require transactions until after the first transaction has
   taken place, while TDB 2 *always* requires transactions when reading from or
   writing to the database."
  [& {:keys [bootstrap-imports db-path db-type schema-uris]
      :or   {db-type :in-mem} :as opts}]
  (let [files          (bootstrap-files bootstrap-imports)
        fn-hashes      (conj bootstrap/hashes
                             (:hash (meta #'add-bootstrap-import!))
                             (:hash (meta #'add-open-english-wordnet!))
                             (hash prefix/schemas))
        ;; Undo potentially negative number by bit-shifting.
        files-hash     (pos-hash files)
        bootstrap-hash (pos-hash fn-hashes)
        db-name        (str files-hash "-" bootstrap-hash)
        full-db-path   (str db-path "/" db-name)
        log-path       (str db-path "/log.txt")
        db-exists?     (.exists (io/file full-db-path))
        new-entry      (log-entry full-db-path files)
        dataset        (->dataset db-type full-db-path)]

    ;; Mutating the graph will of course also mutate the model & dataset.
    (if bootstrap-imports
      (if db-exists?
        (println "Skipping build -- database already exists:" full-db-path)
        (do
          (println "Data input has changed -- rebuilding database...")
          (add-bootstrap-import! dataset bootstrap-imports)
          #_(add-open-english-wordnet! dataset)
          (println new-entry)
          (spit log-path (str new-entry "\n----\n") :append true)))
      (println "WARNING: no imports!"))

    ;; If schemas are provided, the returned model & graph contain inferences.
    (if schema-uris
      (let [schema    (->schema-model schema-uris)
            model     (.getUnionModel dataset)
            inf-model (ModelFactory/createInfModel reasoner schema model)
            inf-graph (.getGraph inf-model)]
        (println "Schema URIs found -- constructing inference model.")
        {:dataset dataset
         :model   inf-model
         :graph   inf-graph})
      (let [model (.getUnionModel dataset)
            graph (.getGraph model)]
        {:dataset dataset
         :model   model
         :graph   graph}))))

(defn add-registry-prefixes!
  "Adds prefixes in use from the Aristotle registry to the `model`."
  [^Model model & {:keys [prefixes]}]
  (doseq [[prefix m] (cond->> (:prefixes registry/*registry*)
                       prefixes (filter (comp prefixes symbol first)))]
    (.setNsPrefix model prefix (::registry/= m))))

(defn export-rdf-model!
  "Export the `model` to the file at the given `path`. Defaults to Turtle.

  The current prefixes in the Aristotle registry are used for the output,
  although a desired subset of :prefixes may also be specified.

  See: https://jena.apache.org/documentation/io/rdf-output.html"
  [path ^Model model & {:keys [fmt prefixes]
                        :or   {fmt RDFFormat/TURTLE_PRETTY}}]
  (println "Exporting" path (str "(" (.size model) ")")
           "with prefixes:" (or prefixes "ALL"))
  (txn/transact-exec model
    (add-registry-prefixes! model :prefixes prefixes)
    (io/make-parents path)
    (RDFDataMgr/write (io/output-stream path) model ^RDFFormat fmt)
    (.clearNsPrefixMap model))
  nil)

(defn- export-prefixes
  [prefix]
  (get-in prefix/schemas [prefix :export]))

(defn export-rdf!
  "Export the models of the RDF `dataset` into `dir`.

  By default, the complete model is not exported. In the case of a typical
  inference-heavy DanNet instance, this would simply be too slow. To include the
  complete model as an export target, set :complete to true."
  ([{:keys [model dataset] :as dannet} dir & {:keys [complete]
                                              :or   {complete false}}]
   (let [ttl-in-dir   (fn [dir filename] (str dir filename ".ttl"))
         merged-ttl   (ttl-in-dir dir "merged")
         complete-ttl (ttl-in-dir dir "complete")]
     (println "Beginning RDF export of DanNet into" dir)
     (println "----")

     ;; The individual models contained in the dataset.
     (doseq [model-uri (iterator-seq (.listNames ^Dataset dataset))
             :let [^Model model (get-model dataset model-uri)
                   prefix       (prefix/uri->prefix model-uri)
                   filename     (ttl-in-dir dir (or prefix model-uri))]]
       (export-rdf-model! filename model :prefixes (export-prefixes prefix)))

     ;; The union of the input datasets.
     (export-rdf-model! merged-ttl (.getUnionModel dataset))

     ;; The union of the input datasets and schemas + inferred triples.
     ;; This constitutes all data available in the DanNet web presence.
     (if complete
       (export-rdf-model! complete-ttl model)
       (println "(skipping export of complete.ttl)"))

     (println "----")
     (println "RDF Export of DanNet complete!")))
  ([^Dataset dataset]
   (export-rdf! dataset "export/rdf/")))

(defn- csv-table-cell
  ([separator x]
   (->> (shared/setify x)
        (map (fn [x]
               (cond
                 (keyword? x) (name x)
                 :else (str x))))
        (sort)
        (str/join separator)))
  ([x]
   (csv-table-cell "; " x)))

(defn- csv-row
  "Convert `row` values into CSVW-compatible strings."
  [row]
  (mapv csv-table-cell row))

(defn synset-rel-table
  "A performant way to fetch synset->synset relations for `synset` in `model`.

  The function basically exists because I wasn't able to perform a similar query
  in a performant way, e.g. doing this for all synsets would take ~45 minutes."
  [^Model model synset]
  (->> (.listProperties (.getResource model (voc/uri-for synset)))
       (iterator-seq)
       (keep (fn [^Statement statement]
               (let [prefix "http://www.wordnet.dk/dannet/data/synset-"
                     obj    (str (.getObject statement))]
                 (when (str/starts-with? obj prefix)
                   [synset
                    (str (.getPredicate statement))
                    (voc/keyword-for obj)]))))))

(defn export-csv-rows!
  "Write CSV `rows` to file `f`."
  [f rows]
  (println "Exporting" f)
  (io/make-parents f)
  (with-open [writer (io/writer f)]
    (csv/write-csv writer rows)))

(defn export-csv!
  "Write CSV `rows` to file `f`."
  ([{:keys [dataset] :as dannet} dir]
   (println "Beginning CSV export of DanNet into" dir)
   (println "----")
   (let [g          (get-graph dataset prefix/dn-uri)
         synsets-ks '[?synset ?definition ?ontotype]
         words-ks   '[?word ?written-rep ?pos ?rdf-type]
         senses-ks  '[?sense ?synset ?word ?note]]
     (println "Fetching table rows:" synsets-ks)
     (export-csv-rows!
       (str dir "synsets.csv")
       (map csv-row (q/table-query g synsets-ks op/csv-synsets)))
     (db.csv/export-metadata!
       (str dir "synsets-metadata.json")
       db.csv/synsets-metadata)

     (println "Fetching table rows:" words-ks)
     (export-csv-rows!
       (str dir "words.csv")
       (map csv-row (q/table-query g words-ks op/csv-words)))
     (db.csv/export-metadata!
       (str dir "words-metadata.json")
       db.csv/words-metadata)

     (println "Fetching table rows:" senses-ks)
     (export-csv-rows!
       (str dir "senses.csv")
       (map csv-row (q/table-query g senses-ks op/csv-senses)))
     (db.csv/export-metadata!
       (str dir "senses-metadata.json")
       db.csv/senses-metadata)

     (println "Fetching inheritance data...")
     (export-csv-rows!
       (str dir "inheritance.csv")
       (map (fn [{:syms [?synset ?rel ?from]}]
              [(name ?synset)
               (voc/uri-for ?rel)
               (name ?from)])
            (q/run g op/csv-inheritance)))
     (db.csv/export-metadata!
       (str dir "inheritance-metadata.json")
       db.csv/inheritance-metadata)

     (println "Fetching example data...")
     (export-csv-rows!
       (str dir "examples.csv")
       (map csv-row (q/run g '[?sense ?example] op/csv-examples)))
     (db.csv/export-metadata!
       (str dir "examples-metadata.json")
       db.csv/examples-metadata)

     (println "Fetching synset relations...")
     (let [model           (get-model dataset prefix/dn-uri)
           synset->triples (partial synset-rel-table model)
           synsets         (->> (q/run g op/synsets)
                                (map '?synset)
                                (set))]
       (export-csv-rows!
         (str dir "relations.csv")
         (->> (mapcat synset->triples synsets)
              (map csv-row))))
     (db.csv/export-metadata!
       (str dir "relations-metadata.json")
       db.csv/relations-metadata))

   (println "----")
   (println "CSV Export of DanNet complete!"))
  ([dannet]
   (export-csv! dannet "export/csv/")))

;; TODO: integrate with/copy some functionality from 'arachne.aristotle/add'
(defn add!
  "Add `content` to a `db`. The content can be a variety of things, including
  another DanNet instance."
  [{:keys [model] :as db} content]
  (txn/transact-exec model
    (.add model (if (map? content)
                  (:model content)
                  content))))

(defn synonyms
  "Return synonyms in Graph `g` of the word with the given `lemma`."
  [g lemma]
  (let [lemma (->LangStr lemma "da")]
    (->> {'?lemma lemma}
         (q/run g '[?synonym] op/synonyms)
         (apply concat)
         (remove #{lemma}))))

(defn alt-representations
  "Return alternatives in Graph `g` for the word with the given `written-rep`."
  [g written-rep]
  (let [written-rep (->LangStr written-rep "da")]
    (->> {'?written-rep written-rep}
         (q/run g '[?alt-rep] op/alt-representations)
         (apply concat)
         (remove #{written-rep}))))

(defn registers
  "Return all register values found in Graph `g`."
  [g]
  (->> (q/run g '[?register] op/registers)
       (apply concat)
       (sort)))

(defn synset-relations
  [g relation]
  (q/run g
         '[?l1 ?s1 ?relation ?s2 ?l2]
         op/synset-relations
         {'?relation relation}))

(def sym->kw
  {'?synset     :rdf/value
   '?definition :skos/definition
   '?ontotype   :dns/ontologicalType})

;; TODO: does this memoization even accomplish anything?
(def label-lookup
  (memoize
    (fn [g]
      (let [search-labels   (q/run g op/synset-search-labels)
            ontotype-labels (q/run g op/ontotype-labels)]
        (merge (set/rename-keys (apply merge-with q/set-merge search-labels)
                                sym->kw)
               (->> (for [{:syms [?ontotype ?label]} ontotype-labels]
                      {?ontotype ?label})
                    (apply merge-with q/set-merge)))))))

(def search-keyfn
  (let [m->label (fn [{:keys [rdf/value] :as m}]
                   (str (get (:k->label (meta m)) value)))]
    (comp (juxt (comp count m->label)
                (comp m->label)
                (comp str :skos/definition)
                (comp :rdf/value))
          second)))

;; TODO: need to also numerically order by synset key, not just alphabetically
(defn look-up
  "Look up synsets in Graph `g` based on the given `lemma`."
  [g lemma]
  (let [k->label (label-lookup g)
        lemma    (if (string? lemma)
                   (->LangStr lemma "da")
                   lemma)]
    (->> (q/run g op/synset-search {'?lemma lemma})
         (group-by '?synset)
         (map (fn [[k ms]]
                (let [{:syms [?label ?synset]
                       :as   base} (apply merge-with q/set-merge ms)
                      subentity (-> base
                                    (dissoc '?lemma
                                            '?form
                                            '?word
                                            '?label
                                            '?sense)
                                    (set/rename-keys sym->kw)
                                    (->> (q/attach-blank-entities g k)))
                      v         (with-meta subentity
                                           {:k->label (assoc k->label
                                                        ?synset ?label)})]
                  [k v])))
         (sort-by search-keyfn)
         (into (fop/ordered-map)))))

(comment
  (type (:graph dannet))                                    ; Check graph type

  ;; Create a new in-memory DanNet from the CSV imports with inference.
  (def dannet
    (->dannet
      :bootstrap-imports bootstrap/imports
      :schema-uris schema-uris))

  ;; Create a new in-memory DanNet from the CSV imports (no inference)
  (def dannet (->dannet :bootstrap-imports bootstrap/imports))

  ;; Load an existing TDB DanNet from disk.
  (def dannet (->dannet :db-path "resources/db/tdb1" :db-type :tdb1))
  (def dannet (->dannet :db-path "resources/db/tdb2" :db-type :tdb2))

  ;; Create a new TDB DanNet from the CSV imports.
  (def dannet
    (->dannet
      :bootstrap-imports bootstrap/imports
      :db-path "resources/db/tdb1"
      :db-type :tdb1))
  (def dannet
    (->dannet
      :bootstrap-imports bootstrap/imports
      :db-path "resources/db/tdb2"
      :db-type :tdb2))

  ;; Def everything used below.
  (do
    (def graph (:graph dannet))
    (def model (:model dannet))
    (def dataset (:dataset dannet))

    ;; Wrap the DanNet model with igraph.
    (def ig
      (igraph-jena/make-jena-graph model)))

  ;; Export individual models
  (export-rdf-model! "dn.ttl" (get-model dataset prefix/dn-uri)
                     :prefixes (export-prefixes 'dn))
  (export-rdf-model! "senti.ttl" (get-model dataset prefix/senti-uri)
                     :prefixes (export-prefixes 'senti))
  (export-rdf-model! "cor.ttl" (get-model dataset prefix/cor-uri)
                     :prefixes (export-prefixes 'cor))

  ;; Export the entire dataset as RDF
  (export-rdf! dannet)

  ;; Test CSV table data
  (let [g (get-graph dataset prefix/dn-uri)]
    (->> (q/table-query g '[?synset ?definition ?ontotype ?sense] op/csv-synsets)
         (map csv-row)
         (take 10)))

  ;; Export DanNet as CSV
  (export-csv! dannet)

  ;; Querying DanNet for various synonyms
  (synonyms graph "vand")
  (synonyms graph "sild")
  (synonyms graph "hoved")
  (synonyms graph "bil")
  (synonyms graph "ord")

  ;; TODO: missing in DanNet 2.5, but available in 2.2 -- why?
  ;;       The later DanNet export has more synsets, but fewer words. Why?
  ;; Querying DanNet for alternative written representations.
  (alt-representations graph "mørkets fyrste")
  (alt-representations graph "offentlig transport")
  (alt-representations graph "kaste håndklædet i ringen")

  ;; Checking various synset relations.
  (synset-relations graph :wn/instance_hypernym)
  (synset-relations graph :wn/co_agent_instrument)
  (synset-relations graph :wn/antonym)
  (synset-relations graph :wn/also)

  ;; Also works dataset and graph, despite accessing the model object.
  (txn/transact graph
    (take 10 (igraph/subjects ig)))
  (txn/transact model
    (take 10 (igraph/subjects ig)))
  ;; NOTE: only works with non-inference graph (the dataset is independent).
  (txn/transact dataset
    (take 10 (igraph/subjects ig)))

  ;; Look up "citrusfrugt" synset using igraph
  (txn/transact model
    (ig :dn/synset-514))

  ;; Find all hypernyms of a Synset in the graph ("birkes"; note: two paths).
  ;; Laziness and threading macros doesn't work well Jena transactions, so be
  ;; sure to transact database-accessing code while leaving out post-processing.
  (let [hypernym (igraph/transitive-closure :wn/hypernym)]
    (->> (txn/transact model
           (igraph/traverse ig hypernym {} [] [:dn/synset-999]))
         (map #(txn/transact model (ig %)))
         (map :rdfs/label)
         (map first)))

  ;; Test inference of :ontolex/isEvokedBy.
  (q/run graph
         '[:bgp
           [?form :ontolex/writtenRep #voc/lstr"vand@da"]
           [?word :ontolex/canonicalForm ?form]
           [?synset :ontolex/isEvokedBy ?word]])

  (q/only-uris
    (q/run graph
           '[:bgp
             [:dn/word-11007846 ?p ?o]]))

  ;; TODO: broken, fix?
  ;; Combining graph queries and regular Clojure data manipulation:
  ;;   1. Fetch all multi-word expressions in the graph.
  ;;   2. Fetch synonyms where applicable.
  ;;   3. Create a mapping from multi-word expression to synonyms
  (->> '[:bgp
         [?word :rdf/type :ontolex/MultiwordExpression]
         [?word :ontolex/canonicalForm ?form]
         [?form :ontolex/writtenRep ?lemma]]
       (q/run graph '[?lemma])
       (apply concat)
       (map (fn [lemma]
              (when-let [synonyms (not-empty (synonyms graph lemma))]
                [lemma synonyms])))
       (into {}))

  ;; Test retrieval of examples
  (q/run graph op/examples '{?sense :dn/sense-21011843})
  (q/run graph op/examples '{?sense :dn/sense-21011111})    ;TODO: missing

  ;; Retrieval of dataset metadata
  (q/run graph [:bgp [bootstrap/<dn> '?p '?o]])
  (q/run graph [:bgp [bootstrap/<simongray> '?p '?o]])

  ;; Find the part-of-speech classes used in Lexinfo.
  (->> (q/run (:graph @dk.cst.dannet.web.resources/db)
              [:bgp '[?s :rdf/type :lexinfo/PartOfSpeech]])
       (map '?s)
       (sort))

  ;; Memory measurements using clj-memory-meter, available using the :mm alias.
  ;; The JVM must be run with the JVM option '-Djdk.attach.allowAttachSelf'.
  ;; See: https://github.com/clojure-goes-fast/clj-memory-meter#usage
  (require '[clj-memory-meter.core :as mm])
  (mm/measure graph)

  ;; List all register values in db; helpful when extending ->register-triples.
  (registers graph)

  ;; TODO: broken, fix?
  ;; Mark the relevant lemma in all ~38539 example sentences.
  ;; I tried the same query (as SPARQL) in Python's rdflib and it was painfully
  ;; slow, to the point where I wonder how people even use that library...
  (map (fn [[?lemma ?example-str]]
         (let [marked-lemma (str "{" (str/upper-case ?lemma) "}")]
           (str/replace example-str ?lemma marked-lemma)))
       (q/run graph '[?lemma ?example-str] op/examples))

  ;; TODO: explore these ~30 synsets, figure out what's wrong
  ;; Find synsets with ambiguous implied sentiment in the senses.
  (->> op/missing-synset-sentiment
       (q/run (:graph @dk.cst.dannet.web.resources/db))
       (group-by '?synset)
       (filter #(apply not= (map '?pclass (second %))))
       (map first)
       (sort)
       (doall)
       (count))
  #_.)
