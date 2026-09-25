(ns dk.cst.dannet.db.bootstrap
  "Represent DanNet as an in-memory graph or within a persisted database (TDB).

  Inverse relations are not explicitly created, but rather handled by way of
  inference using a Jena OWL reasoner."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [arachne.aristotle :as aristotle]
            [clj-file-zip.core :as zip]
            [taoensso.telemere :as t]
            [dk.cst.dannet.db.query :as q]
            [dk.cst.dannet.db.query.operation :as op]
            [dk.cst.dannet.db.transaction :as txn]
            [dk.cst.dannet.db :as db]
            [dk.cst.dannet.db.bootstrap.downloads :as downloads]
            [dk.cst.dannet.db.bootstrap.metadata :as md]
            [dk.cst.dannet.db.bootstrap.premon :as premon]
            [dk.cst.dannet.hash :as h]
            [dk.cst.dannet.release :as release]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.prefix :as prefix])
  (:import [java.io File]
           [java.time LocalDateTime]
           [java.time.format DateTimeFormatter]
           [org.apache.jena.query Dataset DatasetFactory]
           [org.apache.jena.rdf.model Model ModelFactory]
           [org.apache.jena.reasoner.rulesys GenericRuleReasoner Rule]
           [org.apache.jena.tdb TDBFactory]
           [org.apache.jena.tdb2 TDB2Factory]))

(defn assert-expected-dannet-release!
  "Assert that the DanNet `model` is the expected release to bootstrap from.

  On mismatch, reports the version actually found so it's diagnosable: the
  dataset-level <dn> owl:versionInfo is authoritative, but older releases carry
  no such triple, so we fall back to a sample of any versionInfo values present."
  [model]
  (let [graph    (.getGraph ^Model model)
        expected (q/run graph [:bgp [md/<dn> :owl/versionInfo release/from]])]
    (when (empty? expected)
      (let [dn-vers (->> (q/run graph [:bgp [md/<dn> :owl/versionInfo '?v]])
                         (map #(str (get % '?v)))
                         (distinct)
                         (vec))
            actual  (if (seq dn-vers)
                      dn-vers
                      (->> (q/run graph '[:bgp [?s :owl/versionInfo ?v]])
                           (map #(str (get % '?v)))
                           (distinct)
                           (take 5)
                           (vec)))]
        (t/log! {:level :error
                 :id    :dannet.bootstrap/unexpected-release
                 :data  {:expected release/from
                         :actual   actual}}
                "Bootstrap files are not the expected release")
        (throw (ex-info (str "bootstrap files not the expected release. Expected "
                             (pr-str release/from) ", found "
                             (if (seq actual) (pr-str actual) "no owl:versionInfo")
                             ". Restart with refetch (--refetch, or restart-refetch "
                             "in the REPL) to download the expected release.")
                        {:expected release/from
                         :actual   actual}))))))

(h/defn synset-label
  "The label of a synset given the `labels` of its senses, i.e. the distinct
  labels sorted and joined, e.g. {hund_1§1; køter_§1}, in the language of
  `->lang` (md/da or md/en)."
  [->lang labels]
  (->lang "{" (str/join "; " (sort (distinct (map str labels)))) "}"))

(h/defn add-open-english-wordnet-labels!
  "Generate appropriate labels for the (otherwise unlabeled) OEWN in `dataset`."
  [dataset]
  (t/trace! {:id :dannet.bootstrap/oewn-labels :run-val :elided}
    (let [oewn-graph  (db/get-graph dataset prefix/oewn-uri)
          label-graph (db/get-graph dataset prefix/oewn-extension-uri)
          ms          (q/run oewn-graph op/oewn-label-targets)
          collect-rep (fn [m {:syms [?synset ?rep]}]
                        (update m ?synset conj (str ?rep)))]
      (txn/transact-exec dataset
        (t/log! {:level :debug
                 :id    :dannet.bootstrap/oewn-synset-labels
                 :data  {:graph (str prefix/oewn-extension-uri)}}
                "Adding OEWN synset labels")
        (->> (reduce collect-rep {} ms)
             (map (fn [[synset labels]]
                    [synset :rdfs/label (synset-label md/en labels)]))
             (aristotle/add label-graph)))
      (txn/transact-exec dataset
        (t/log! {:level :debug
                 :id    :dannet.bootstrap/oewn-word-labels
                 :data  {:graph (str prefix/oewn-extension-uri)}}
                "Adding OEWN sense and word labels")
        (->> ms
             (mapcat (fn [{:syms [?sense ?word ?rep]}]
                       [[?word :rdfs/label (md/en "\"" ?rep "\"")]
                        [?sense :rdfs/label ?rep]]))
             (aristotle/add label-graph)))
      ;; Carry the CC BY 4.0 licence in the RDF itself, not just the export zip
      ;; (issue #96): the OEWN label extension is our derivative of the Open
      ;; English Wordnet, published under the same CC BY 4.0 licence.
      (txn/transact-exec dataset
        (t/log! {:level :debug
                 :id    :dannet.bootstrap/oewn-license
                 :data  {:graph (str prefix/oewn-extension-uri)}}
                "Adding OEWN extension licence metadata")
        (let [oewn-ext (prefix/uri->rdf-resource prefix/oewn-extension-uri)]
          (aristotle/add
            label-graph
            [[oewn-ext :dc/license "<https://creativecommons.org/licenses/by/4.0/>"]
             ["<https://creativecommons.org/licenses/by/4.0/>" :rdfs/label "CC BY 4.0"]
             [oewn-ext :dc/rights (md/en "DanNet-style labels for the Open English Wordnet; "
                                         "© the Open English Wordnet contributors, "
                                         "licensed under CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/).")]]))))))

;; TODO: move to separate ns
(h/defn add-open-english-wordnet!
  "Add the Open English WordNet to a Jena `dataset`."
  [dataset]
  (t/trace! {:id :dannet.bootstrap/import-oewn :run-val :elided}
    (let [oewn-changefn (fn [temp-model]
                          (t/log! {:level :debug :id :dannet.bootstrap/oewn-clean}
                                  "Removing problematic OEWN entries")
                          (db/remove! temp-model [prefix/oewn-uri :lime/entry '_]))]
      (db/import-files dataset prefix/oewn-uri [downloads/oewn-ttl-path] oewn-changefn)
      (db/import-files dataset prefix/ili-uri [downloads/ili-path])))
  (add-open-english-wordnet-labels! dataset))

(h/defn add-framenet!
  "Add the framenet graph to `dataset`: the FrameNet 1.7 frame inventory
  converted from the PreMOn dump by the premon ns."
  [dataset]
  (t/trace! {:id :dannet.bootstrap/import-framenet :run-val :elided}
    (let [graph (db/get-graph dataset prefix/framenet-uri)
          model (db/get-model dataset prefix/framenet-uri)]
      (doseq [chunk (partition-all 500000 (premon/source-triples))]
        (txn/transact-exec graph
          (db/safe-add! graph chunk)))
      (txn/transact-exec model
        (md/update-metadata! (get md/metadata 'frame) model)))))

(h/defn add-in-scheme!
  "Add skos:inScheme to all DanNet, COR and COR.SEM resources (GitHub issue
  #175), mirroring how the OEWN marks scheme membership on its resources.
  Every URI subject residing in the resource namespace of its containing model
  is linked to the RDF resource of the relevant dataset. The COR.SEM IDs share
  the cor: namespace, so the sense inventory is set apart by its full COR.SEM.
  prefix; this also leaves the cor: words and frame: resources appearing as
  subjects in the cor-sem: graph unmarked, as they belong to other schemes
  (the frame: resources are marked in the framenet graph instead).
  The DDS dataset is deliberately left out since it contains no resources of
  its own, only annotations of dn: resources; the OEWN label extensions don't
  need it either."
  [dataset]
  (doseq [[model-uri ns-uri scheme] [[prefix/dn-uri prefix/dn-uri md/<dn>]
                                     [prefix/cor-uri prefix/cor-uri md/<cor>]
                                     [prefix/cor-sem-uri (str prefix/cor-uri "COR.SEM.") md/<cor-sem>]
                                     [prefix/framenet-uri prefix/framenet-uri md/<framenet>]]]
    (let [model    (db/get-model dataset model-uri)
          g        (db/get-graph dataset model-uri)
          subjects (txn/transact model
                     (->> (iterator-seq (.listSubjects model))
                          (filter #(.isURIResource %))
                          (map #(.getURI %))
                          (filter #(str/starts-with? % ns-uri))
                          (doall)))]
      (txn/transact-exec g
        (t/log! {:level :info
                 :id    :dannet.bootstrap/add-in-scheme
                 :data  {:scheme scheme
                         :count  (count subjects)}}
                "Adding skos:inScheme to resources")
        (db/safe-add! g (for [uri subjects]
                          [(prefix/uri->rdf-resource uri) :skos/inScheme scheme]))))))

(h/defn regenerate-short-labels!
  "Regenerate every dns:shortLabel from its synset's rdfs:label, ranking the
  senses by COR.SEM centrality first and the shared/canonical entry-ID
  heuristic among equally central senses, breaking remaining ties by word
  polysemy (a proxy for word commonness). A short label is only emitted when
  canonical omits senses, with the \"…\" marker appended; otherwise
  rdfs:label suffices as-is.

  The centrality values reach the dn: senses through the sense matches of the
  cor-sem: graph, so this must run after that graph is imported."
  [dataset]
  (t/log! {:level :info
           :id    :dannet.bootstrap/regenerate-short-labels}
          "Regenerating short synset labels")
  (let [g          (db/get-graph dataset prefix/dn-uri)
        sem-g      (db/get-graph dataset prefix/cor-sem-uri)
        centrality (q/label-centralities
                     (q/run sem-g op/centrality-query)
                     (q/run sem-g op/eq-sense-match-query)
                     (q/run g op/synset-sense-label-query))
        polysemy   (->> (q/run g op/sense-label-polysemy)
                        (map (juxt (comp str '?senseLabel) '?polysemy))
                        (into {}))
        primary    (shared/centrality-primary centrality)
        tiebreak   (shared/polysemy-tiebreak polysemy)]
    (db/update-triples! prefix/dn-uri dataset op/synset-long-short-labels
      (fn [{:syms [?synset ?label]}]
        (when-let [short (shared/short-label primary tiebreak ?label)]
          [?synset :dns/shortLabel (md/da short)]))
      (fn [{:syms [?synset ?shortLabel]}]
        (when ?shortLabel
          [?synset :dns/shortLabel ?shortLabel])))))

(def dannet-2-owl-dir
  "The DanNet 2.2 OWL release, the only source of the DanNet 2 negations.

  It is not downloaded automatically. Get DanNet-2.2_owl.zip from
  https://repository.clarin.dk/items/db6c5063-1d5d-435a-8ccc-8239fd826542
  and unzip it into bootstrap/dannet/, next to the DanNet 2.2 CSV release."
  "bootstrap/dannet/DanNet-2.2_owl")

(def negated-relation-predicates
  "The current predicate of each DanNet 2.2 relation that has negations, keyed
  by its name in the DanNet 2.2 OWL release."
  {"concerns"          :wn/also
   "locationMeronymOf" :wn/holo_location
   "madeofHolonymOf"   :wn/mero_substance
   "memberHolonymOf"   :wn/mero_member
   "memberMeronymOf"   :wn/holo_member
   "partHolonymOf"     :wn/mero_part
   "roleAgent"         :wn/agent
   "rolePatient"       :wn/patient
   "usedFor"           :dns/usedFor})

(h/defn dannet-2-negations
  "Read the negated relations in the DanNet 2.2 OWL release in `dir` as
  [synset relation target from] tuples of DanNet 2.2 ids and relation names.

  `from` is the synset that the negation is inherited from, or nil for a
  direct negation. Only an XML comment gives this synset, and RDF parsers
  discard comments, so the files are read as text."
  [dir]
  (let [negation-re (re-pattern
                      (str "<owl:NegativeObjectPropertyAssertion>"
                           "(?:<!-- Inherited from synset with id (\\d+) .*?-->)?"
                           "\\s*<rdf:subject rdf:resource=\"&dn;synset-(\\d+)\"/>"
                           "<rdf:predicate rdf:resource=\"&\\w+;(\\w+)\"/>"
                           "<rdf:object rdf:resource=\"&dn;synset-(\\d+)\"/>"))]
    (->> (.listFiles (io/file dir))
         (filter #(str/ends-with? (.getName ^File %) ".rdf"))
         (mapcat #(re-seq negation-re (slurp % :encoding "ISO-8859-1")))
         (map (fn [[_ from synset relation target]]
                [synset relation target from])))))

(h/defn negation-triples
  "The triples of an owl:NegativePropertyAssertion that denies [`s` `p` `o`],
  naming the synset `from` that the negation is inherited from, if any."
  [[s p o] from]
  (let [negation (symbol (str "_negation-" (name s) "-" (name p) "-" (name o)))]
    (cond-> #{[negation :rdf/type :owl/NegativePropertyAssertion]
              [negation :owl/sourceIndividual s]
              [negation :owl/assertionProperty p]
              [negation :owl/targetIndividual o]}
      from (conj [negation :dns/inheritedFrom from]))))

(h/defn remove-inheritance-source!
  "Remove `from` from the sources of the `p` relations that `s` inherits in
  `model`, and remove the inheritance mark when no source remains."
  [^Model model s p from]
  (let [res       #(.createResource model ^String (prefix/kw->uri %))
        prop      #(.createProperty model ^String (prefix/kw->uri %))
        from-prop (prop :dns/inheritedFrom)]
    (doseq [stmt (vec (iterator-seq (.listProperties (res s) (prop :dns/inherited))))
            :let [mark (.getResource stmt)]
            :when (.hasProperty mark (prop :dns/inheritedRelation) (res p))]
      (.remove model mark from-prop (res from))
      (when-not (.hasProperty mark from-prop)
        (.removeAll model mark nil nil)
        (.remove model stmt)))))

(h/defn replace-negated-relations!
  "Replace the relations in the dn: graph of `dataset` that DanNet 2.2 negated
  with owl:NegativePropertyAssertion resources (GitHub issue #216).

  Most of the 268 negations define a concept by what it lacks, for example
  {ikkeryger} is not the agent of {ryge}. 203 of them are inherited from the
  7 synsets that state them directly, 184 from {lokale}. The DanNet 2.2 CSV
  release shows them as ordinary relations, so the conversion of that release
  asserted all 268. An inheritance mark stays only while its synset inherits
  other targets of the relation from the same synset.

  The counts are asserted so that a future bootstrap dataset silently growing
  or shrinking this set fails loudly instead."
  [dataset]
  (t/log! {:level :info
           :id    :dannet.bootstrap/replace-negated-relations}
          "Replacing the relations that DanNet 2.2 negated")
  (let [g         (db/get-graph dataset prefix/dn-uri)
        model     (db/get-model dataset prefix/dn-uri)
        synset    #(keyword "dn" (str "synset-" %))
        negations (for [[s relation o from] (dannet-2-negations dannet-2-owl-dir)]
                    [[(synset s) (negated-relation-predicates relation) (synset o)]
                     (some-> from synset)])
        inherited (filter second negations)]
    (assert (= 268 (count negations))
            (str "expected 268 negations, found " (count negations)))
    (assert (= 203 (count inherited))
            (str "expected 203 inherited negations, found " (count inherited)))
    (assert (every? #(seq (q/run g [:bgp (first %)])) negations)
            "expected every negated relation to be asserted")
    (txn/transact-exec model
      (doseq [[triple] negations]
        (db/remove! model triple)))
    (txn/transact-exec g
      (db/safe-add! g (mapcat #(apply negation-triples %) negations)))
    (let [sources (for [[[s p] from] inherited
                        :when (empty? (q/run g [:bgp [s p '?o] [from p '?o]]))]
                    [s p from])]
      (t/log! {:level :info
               :id    :dannet.bootstrap/remove-inheritance-sources
               :data  {:count (count sources)}}
              "Removing inheritance sources that only negations justified")
      (txn/transact-exec model
        (doseq [[s p from] sources]
          (remove-inheritance-source! model s p from))))))

(h/defn make-release-changes!
  "Apply the changes that produce this release, i.e. deletions and additions
  to either of the export datasets.

  Nothing runs while `to` equals `from`: the database then reproduces the
  release it was bootstrapped from and must stay faithful to it. Setting `to`
  cuts a release and enables the changes below, which are cleared out once that
  release has shipped."
  [dataset]
  (when (not= release/from release/to)
    (t/log! {:level :info
             :id    :dannet.bootstrap/release-changes
             :data  {:from release/from :to release/to}}
            "Applying release changes")

    ;; ==== Changes for this particular release. ====
    (replace-negated-relations! dataset)

    ;; ==== Derived data, regenerated for every release. NOT cleared out. ====
    (add-in-scheme! dataset)
    (regenerate-short-labels! dataset)))

(defn ->dataset
  "Get a Dataset object of the given `db-type`. TDB also requires a `db-path`.

  NOTE: TDB 1 does not require transactions until after the first transaction
  has taken place, while TDB 2 *always* requires transactions when reading from
  or writing to the database."
  [db-type & [db-path]]
  (case db-type
    :tdb1 (TDBFactory/createDataset ^String db-path)
    :tdb2 (TDB2Factory/connectDataset ^String db-path)
    :in-mem (DatasetFactory/create)
    :in-mem-txn (DatasetFactory/createTxnMem)))

(defn- log-entry
  [db-name db-type input-dir]
  (let [now       (LocalDateTime/now)
        formatter (DateTimeFormatter/ofPattern "yyyy/MM/dd HH:mm:ss")
        filenames (sort (->> (file-seq input-dir)
                             (remove #{input-dir})
                             (map #(.getName ^File %))))]
    (str
      "Location: " db-name "\n"
      "Type: " db-type "\n"
      "Created: " (.format now formatter) "\n"
      "Input data: " (str/join ", " filenames))))

(def reasoner
  "The custom reasoner inferring many triples present in the complete dataset.

  The rules in 'dannet.rules' are purpose-built for DanNet, covering only
  owl:inverseOf and rdfs:subPropertyOf entailment as tabled backward rules."
  (let [rules (Rule/parseRules (slurp (io/resource "etc/dannet.rules")))]
    (doto (GenericRuleReasoner. rules)
      (.setMode GenericRuleReasoner/HYBRID)
      (.setTransitiveClosureCaching true))))

(defn dataset->db
  "Construct a database map from an Apache Jena `dataset`.

  If `schema-uris` are provided, the returned model & graph contain inferences;
  otherwise, the model/graph is of union of the models/graphs in the dataset.

  The base (non-inference) model is always available as :base-model for use by
  the SPARQL endpoint, where inference is opt-in."
  [^Dataset dataset & [schema-uris]]
  (if schema-uris
    (let [schema    (db/->schema-model schema-uris)
          model     (.getUnionModel dataset)
          inf-model (ModelFactory/createInfModel reasoner schema model)
          inf-graph (.getGraph inf-model)]
      ;; A plain :info log, not a trace! around createInfModel: Jena builds the
      ;; InfModel lazily, so tracing here would report a near-zero runtime. The
      ;; real inference cost is realized later, on first traversal; see the
      ;; :dannet.graph/* traces in dk.cst.dannet.web.instance.
      (t/log! {:level :info
               :id    :dannet.graph/inference-model
               :data  {:schema-count (count schema-uris)}}
              "Constructing inference model")
      {:dataset    dataset
       :base-model model
       :model      inf-model
       :graph      inf-graph})
    (let [model (.getUnionModel dataset)
          graph (.getGraph model)]
      {:dataset    dataset
       :base-model model
       :model      model
       :graph      graph})))

(h/defn ->dannet
  "Create a Jena database from the latest DanNet export.

    :input-dir         - Previous DanNet version TTL export as a File directory.
    :db-type           - :tdb1, :tdb2, :in-mem, and :in-mem-txn are supported
    :db-path           - Where to persist the TDB1/TDB2 data.
    :schema-uris       - A collection of URIs containing schemas."
  [& {:keys [^File input-dir db-path db-type schema-uris refetch?]
      :or   {db-type :in-mem} :as opts}]
  (let [log-path (str db-path "/log.txt")]
    (if input-dir
      ;; Either refetch or assert the datasets are already present. Neither
      ;; downloads silently on a normal start; both run for side effects before
      ;; the file-seq below, which expects the inputs on disk.
      (let [_              (downloads/assert-input-dir! input-dir release/from)
            _              (if refetch?
                             (downloads/refetch-datasets! input-dir release/from)
                             (downloads/assert-datasets-present! input-dir))
            ;; The indegree cache can arrive with the fetch above, i.e. after
            ;; this namespace was loaded, so the delay is re-derived here.
            _              (q/reload-synset-indegrees!)
            files          (->> (file-seq input-dir)
                                (filter #(re-find #"\.zip$" (.getName ^File %))))
            fn-hashes      [(:hash (meta #'add-open-english-wordnet!))
                            (:hash (meta #'add-open-english-wordnet-labels!))
                            (:hash (meta #'add-framenet!))
                            (:hash (meta #'premon/premon-index))
                            (:hash (meta #'premon/source-triples))
                            (hash premon/fe-status)
                            (hash premon/frame-relations)
                            (hash premon/fe-relations)
                            (:hash (meta #'make-release-changes!))
                            (:hash (meta #'synset-label))
                            (:hash (meta #'add-in-scheme!))
                            (:hash (meta #'regenerate-short-labels!))
                            (:hash (meta #'replace-negated-relations!))
                            (:hash (meta #'dannet-2-negations))
                            (:hash (meta #'negation-triples))
                            (:hash (meta #'remove-inheritance-source!))
                            (hash negated-relation-predicates)
                            (:hash (meta #'md/add-dataset-statistics!))
                            (:hash (meta #'md/metadata))
                            (:hash (meta #'md/update-metadata!))
                            (:hash (meta #'->dannet))
                            (hash prefix/schemas)
                            ;; The emitted version is baked into the dataset
                            ;; metadata but isn't captured by any hashed form
                            ;; above (those hash source, not values), so include
                            ;; it explicitly -- otherwise cutting a release
                            ;; (:to bumped away from :from) wouldn't change
                            ;; db-name and the stale database would be reused.
                            release/to
                            ;; The OEWN edition is likewise only a value: its
                            ;; ttl isn't among the hashed input zips, so it is
                            ;; included explicitly; otherwise bumping the
                            ;; edition wouldn't trigger a rebuild. The same
                            ;; goes for the PreMOn release behind the framenet
                            ;; graph.
                            downloads/oewn-version
                            release/premon-version
                            ;; The COR editions likewise: they only figure as
                            ;; values, in the dc:hasVersion dataset metadata.
                            release/cor-version
                            release/cor-ext-version
                            release/cor-sem-version]
            ;; Undo potentially negative number by bit-shifting.
            files-hash     (h/pos-hash files)
            bootstrap-hash (h/pos-hash fn-hashes)
            db-name        (str files-hash "-" bootstrap-hash)
            full-db-path   (str db-path "/" db-name)
            zip-file?      (comp #(str/ends-with? % ".zip") #(.getName %))
            ttl-file?      (comp #(str/ends-with? % ".ttl") #(.getName %))
            ;; Written as the final build step below; a directory without it
            ;; is a partial build (e.g. an exception mid-build) and is rebuilt.
            build-complete (io/file full-db-path "build-complete.txt")
            db-exists?     (.exists build-complete)
            new-entry      (log-entry db-name db-type input-dir)
            dataset        (->dataset db-type full-db-path)
            ;; Include the current build hash to make debugging easier
            metadata'      (update md/metadata 'dn conj [md/<dn> :dn/build db-name])]
        (t/log! {:level :debug
                 :id    :dannet.bootstrap/db-path
                 :data  {:path full-db-path}}
                "Resolved database path")
        (if db-exists?
          (do
            (t/event! :dannet.bootstrap/build-skipped
                      {:level :info
                       :msg   "Skipping build (database already exists)"
                       :data  {:db-name db-name}})
            (dataset->db dataset schema-uris))
          (t/trace! {:id      :dannet.bootstrap/build
                     :run-val :elided
                     :data    {:db-name db-name
                               :db-type db-type
                               :input   (.getName input-dir)
                               :path    full-db-path}}
            (do
              (t/log! {:level :info
                       :id    :dannet.bootstrap/build-started
                       :data  {:db-name db-name :input (.getName input-dir)}}
                      "Building new database")
              (doseq [zip-file (filter zip-file? (file-seq input-dir))]
                ;; unzip writes to (str output-parent (.getName entry)) with no
                ;; separator, so output-parent must be a dir path ending in "/";
                ;; otherwise entries get the zip's own path prepended (e.g.
                ;; "oewn-extension.zipoewn-extension.ttl").
                (zip/unzip zip-file (str (.getParent ^File zip-file) "/"))
                (let [ttl-file  (first (filter ttl-file? (file-seq input-dir)))
                      model-uri (prefix/zip-file->uri (.getName zip-file))
                      prefix    (prefix/uri->prefix model-uri)
                      update!   (when prefix
                                  (partial md/update-metadata! (metadata' prefix)))
                      ;; Special behaviour to check bootstrap files version
                      changefn  (if (= prefix 'dn)
                                  (fn [model]
                                    (t/log! {:level :debug
                                             :id    :dannet.bootstrap/version-check
                                             :data  {:model (str model-uri)}}
                                            "Checking bootstrap version")
                                    (assert-expected-dannet-release! model)
                                    (update! model))
                                  update!)]
                  (db/import-files dataset model-uri [ttl-file] changefn)
                  (zip/delete-file ttl-file)))

              ;; The English is always explicitly added as it is not part of our
              ;; own latest export (only the DanNet-like labels we produce are).
              ;; It is imported BEFORE the release changes, some of which may
              ;; need the OEWN graph for lookups.
              (add-open-english-wordnet! dataset)

              ;; The FrameNet frame inventory likewise arrives by download
              ;; rather than as part of our own export; the dns:frame links
              ;; added by the release changes below resolve against it.
              (add-framenet! dataset)

              ;; Effectuate changes for the current release.
              ;; These are always tied to the current release and depend on the
              ;; former release, i.e. the contents of this function is versioned
              ;; together with every single formal release.
              (make-release-changes! dataset)

              ;; Runs after the release changes so that the dataset
              ;; statistics reflect the data actually being exported.
              (md/add-dataset-statistics! dataset)

              (t/log! {:level :info
                       :id    :dannet.bootstrap/db-created
                       :data  {:db-name db-name}}
                      "Database created")
              (spit log-path (str new-entry "\n----\n") :append true)
              ;; The :in-mem db types never create the directory.
              (when (.isDirectory (io/file full-db-path))
                (spit build-complete new-entry))
              (dataset->db dataset schema-uris)))))
      (let [db-name      (->> (slurp log-path)
                              (re-seq #"Location: (.+)")
                              (last)
                              (second))
            full-db-path (str db-path "/" db-name)
            dataset      (->dataset db-type full-db-path)]
        (t/log! {:level :warn :id :dannet.bootstrap/no-input-dir}
                "No input dir provided; reusing existing database from log")
        (dataset->db dataset schema-uris)))))
