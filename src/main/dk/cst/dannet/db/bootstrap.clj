(ns dk.cst.dannet.db.bootstrap
  "Build the DanNet database from the files of the release it is bootstrapped
  from (release/from); ->dannet is the entry point.

  The h/defn forms and values listed in fn-hashes in ->dannet are hashed into
  the database name, so changing their code or docstrings triggers a rebuild,
  while comments do not; a new build step must also be listed there. Inverse
  relations are not asserted but inferred by `reasoner`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-file-zip.core :as zip]
            [taoensso.telemere :as t]
            [dk.cst.theophrastus :as theo]
            [dk.cst.theophrastus.rules :as rules]
            [dk.cst.theophrastus.tdb2 :as tdb2]
            [dk.cst.theophrastus.txn :as txn]
            [dk.cst.dannet.db.query :as q]
            [dk.cst.dannet.db.query.operation :as op]
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
           [org.apache.jena.rdf.model Model]))

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
    (let [oewn-graph  (theo/named-graph dataset prefix/oewn-graph)
          label-graph (theo/named-graph dataset prefix/oewn-extension-graph)
          ms          (theo/q oewn-graph op/oewn-label-targets)
          collect-rep (fn [m {:syms [?synset ?rep]}]
                        (update m ?synset conj (str ?rep)))]
      (t/log! {:level :debug
               :id    :dannet.bootstrap/oewn-synset-labels
               :data  {:graph (str prefix/oewn-extension-uri)}}
              "Adding OEWN synset labels")
      (->> (reduce collect-rep {} ms)
           (map (fn [[synset labels]]
                  [synset :rdfs/label (synset-label md/en labels)]))
           (theo/transact! label-graph))
      (t/log! {:level :debug
               :id    :dannet.bootstrap/oewn-word-labels
               :data  {:graph (str prefix/oewn-extension-uri)}}
              "Adding OEWN sense and word labels")
      (->> ms
           (mapcat (fn [{:syms [?sense ?word ?rep]}]
                     [[?word :rdfs/label (md/en "\"" ?rep "\"")]
                      [?sense :rdfs/label ?rep]]))
           (theo/transact! label-graph))
      ;; Carry the CC BY 4.0 licence in the RDF itself, not just the export zip
      ;; (issue #96): the OEWN label extension is our derivative of the Open
      ;; English Wordnet, published under the same CC BY 4.0 licence.
      (t/log! {:level :debug
               :id    :dannet.bootstrap/oewn-license
               :data  {:graph (str prefix/oewn-extension-uri)}}
              "Adding OEWN extension licence metadata")
      (let [oewn-ext (prefix/uri->rdf-resource prefix/oewn-extension-uri)]
        (theo/transact!
          label-graph
          [[oewn-ext :dc/license "<https://creativecommons.org/licenses/by/4.0/>"]
           ["<https://creativecommons.org/licenses/by/4.0/>" :rdfs/label "CC BY 4.0"]
           [oewn-ext :dc/rights (md/en "DanNet-style labels for the Open English Wordnet; "
                                       "© the Open English Wordnet contributors, "
                                       "licensed under CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/).")]])))))

;; TODO: move to separate ns
(h/defn add-open-english-wordnet!
  "Add the Open English WordNet to the `dataset`."
  [dataset]
  (t/trace! {:id :dannet.bootstrap/import-oewn :run-val :elided}
    (let [oewn-changefn (fn [temp]
                          (t/log! {:level :debug :id :dannet.bootstrap/oewn-clean}
                                  "Removing problematic OEWN entries")
                          (theo/retract! temp
                                         [(prefix/uri->rdf-resource prefix/oewn-uri)
                                          :lime/entry '_]))]
      (db/import-files dataset prefix/oewn-graph [downloads/oewn-ttl-path] oewn-changefn)
      (db/import-files dataset prefix/ili-graph [downloads/ili-path])))
  (add-open-english-wordnet-labels! dataset))

(h/defn add-framenet!
  "Add the framenet graph to `dataset`: the FrameNet 1.7 frame inventory
  converted from the PreMOn dump by the premon ns."
  [dataset]
  (t/trace! {:id :dannet.bootstrap/import-framenet :run-val :elided}
    (let [graph (theo/named-graph dataset prefix/framenet-graph)]
      (doseq [chunk (partition-all 500000 (premon/source-triples))]
        (db/safe-add! graph chunk))
      (txn/with-transaction (:model graph)
        (md/update-metadata! (get (md/metadata) 'frame) graph)))))

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
  (doseq [[graph ns-uri scheme] [[prefix/dn-graph prefix/dn-uri md/<dn>]
                                 [prefix/cor-graph prefix/cor-uri md/<cor>]
                                 [prefix/cor-sem-graph (str prefix/cor-uri "COR.SEM.") md/<cor-sem>]
                                 [prefix/framenet-graph prefix/framenet-uri md/<framenet>]]]
    (let [g            (theo/named-graph dataset graph)
          ^Model model (:model g)
          subjects     (txn/with-read-transaction model
                         (->> (iterator-seq (.listSubjects model))
                              (filter #(.isURIResource %))
                              (map #(.getURI %))
                              (filter #(str/starts-with? % ns-uri))
                              (doall)))]
      (t/log! {:level :info
               :id    :dannet.bootstrap/add-in-scheme
               :data  {:scheme scheme
                       :count  (count subjects)}}
              "Adding skos:inScheme to resources")
      (db/safe-add! g (for [uri subjects]
                        [(prefix/uri->rdf-resource uri) :skos/inScheme scheme])))))

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
  (let [g          (theo/named-graph dataset prefix/dn-graph)
        sem-g      (theo/named-graph dataset prefix/cor-sem-graph)
        centrality (q/label-centralities
                     (theo/q sem-g op/centrality-query)
                     (theo/q sem-g op/eq-sense-match-query)
                     (theo/q g op/synset-sense-label-query))
        polysemy   (->> (theo/q g op/sense-label-polysemy)
                        (map (juxt (comp str '?senseLabel) '?polysemy))
                        (into {}))
        primary    (shared/centrality-primary centrality)
        tiebreak   (shared/polysemy-tiebreak polysemy)]
    (db/update-triples! dataset prefix/dn-graph op/synset-long-short-labels
      (fn [{:syms [?synset ?label]}]
        (when-let [short (shared/short-label primary tiebreak ?label)]
          [?synset :dns/shortLabel (md/da short)]))
      ;; Every existing short label is removed first, so none outlives a change
      ;; that makes rdfs:label suffice.
      (fn [{:syms [?synset ?shortLabel]}]
        (when ?shortLabel
          [?synset :dns/shortLabel ?shortLabel])))))

(def dangling-oewn-link-changes
  "The English links of the dn: synsets whose wn:eq_synonym target is missing
  from OEWN 2025, as the triples to remove and to add.

  OEWN 2025 moved proper nouns and genera to Open English Namenet. Their links
  are removed, but the synsets keep their wn:ili, except {vest_1§1}: it is the
  compass point and links to it already, so its ILI of the Occident goes too.
  {chimpanse_§1} links to the species instead of the genus. The OEWN merged
  the four verb synsets into others as duplicates (see its deprecations.csv),
  so their links move to the merged synset and their wn:ili becomes its ILI.
  {blive_§4} links to that synset already."
  {:remove [[:dn/synset-37515 :wn/eq_synonym :en/oewn-09381255-n] ; Moon
            [:dn/synset-26945 :wn/eq_synonym :en/oewn-09473312-n] ; Sun
            [:dn/synset-9045 :wn/eq_synonym :en/oewn-08580157-n]  ; Orient
            [:dn/synset-32201 :wn/eq_synonym :en/oewn-09293800-n] ; Earth
            [:dn/synset-38252 :wn/eq_synonym :en/oewn-08700313-n] ; Occident
            [:dn/synset-38252 :wn/ili :ili/i82570]
            [:dn/synset-3659 :wn/eq_synonym :en/oewn-02484260-n]  ; genus Pan
            [:dn/synset-3659 :wn/ili :ili/i48707]
            [:dn/synset-42945 :wn/eq_synonym :en/oewn-02736899-v] ; stay behind
            [:dn/synset-42945 :wn/ili :ili/i35320]
            [:dn/synset-44354 :wn/eq_synonym :en/oewn-02125976-v] ; feel pain
            [:dn/synset-44354 :wn/ili :ili/i32364]
            [:dn/synset-44354 :wn/ili :ili/i30704]
            [:dn/synset-33105 :wn/eq_synonym :en/oewn-00932330-v] ; call to mind
            [:dn/synset-33105 :wn/ili :ili/i26262]
            [:dn/synset-13901 :wn/eq_synonym :en/oewn-01643256-v] ; design
            [:dn/synset-13901 :wn/ili :ili/i29940]]
   :add    [[:dn/synset-3659 :wn/eq_synonym :en/oewn-02484454-n]  ; chimpanzee
            [:dn/synset-3659 :wn/ili :ili/i48708]
            [:dn/synset-44354 :wn/eq_synonym :en/oewn-00064841-v] ; be in pain
            [:dn/synset-44354 :wn/ili :ili/i22102]
            [:dn/synset-33105 :wn/eq_synonym :en/oewn-00022092-v] ; call to mind
            [:dn/synset-33105 :wn/ili :ili/i21876]
            [:dn/synset-13901 :wn/eq_synonym :en/oewn-01641910-v] ; devise
            [:dn/synset-13901 :wn/ili :ili/i29933]]})

(h/defn fix-dangling-oewn-links!
  "Replace the links of the dn: synsets in `dataset` to synsets missing from
  OEWN 2025, as listed in dangling-oewn-link-changes.

  The links come from the DanNet 2.2 links to Princeton WordNet. Must run
  after add-open-english-wordnet!, which supplies the new link targets."
  [dataset]
  (t/log! {:level :info
           :id    :dannet.bootstrap/fix-dangling-oewn-links}
          "Replacing links to synsets missing from OEWN 2025")
  (let [g        (theo/named-graph dataset prefix/dn-graph)
        oewn-g   (theo/named-graph dataset prefix/oewn-graph)
        changes  dangling-oewn-link-changes
        target?  (fn [[_ p o]]
                   (and (= :wn/eq_synonym p)
                        (seq (q/triples oewn-g [o :rdf/type :ontolex/LexicalConcept]))))
        counts   {:removed (count (filter #(seq (q/triples g %)) (:remove changes)))
                  :targets (count (filter target? (:add changes)))}
        expected {:removed 17 :targets 4}]
    (assert (= expected counts)
            (str "expected OEWN link changes " expected ", found " counts))
    (theo/retract! g (:remove changes))
    (db/safe-add! g (:add changes))))

(h/defn retype-long-literals!
  "Rewrite the xsd:long literals in the graphs of `dataset` as xsd:integer
  literals of the same value.

  Aristotle wrote a Clojure long as an xsd:long, while Theophrastus writes it
  as an xsd:integer, like a bare number in SPARQL and Turtle. The earlier
  releases hold xsd:long values in the cor-sem: and dds: graphs, which a query
  matching an xsd:integer by term would miss."
  [dataset]
  (t/log! {:level :info
           :id    :dannet.bootstrap/retype-long-literals}
          "Rewriting the xsd:long literals as xsd:integer")
  (let [longs    "SELECT (COUNT(*) AS ?n)
                  WHERE {
                    GRAPH ?g { ?s ?p ?o FILTER(datatype(?o) = xsd:long) }
                  }"
        found    (get (first (theo/q dataset longs)) '?n)
        expected 24549]
    (assert (= expected found)
            (str "expected " expected " xsd:long literals, found " found))
    (theo/update! dataset
                  "DELETE { GRAPH ?g { ?s ?p ?o } }
                   INSERT { GRAPH ?g { ?s ?p ?integer } }
                   WHERE {
                     GRAPH ?g { ?s ?p ?o FILTER(datatype(?o) = xsd:long) }
                     BIND(xsd:integer(?o) AS ?integer)
                   }")))

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
    (fix-dangling-oewn-links! dataset)
    (retype-long-literals! dataset)

    ;; ==== Derived data, regenerated for every release. NOT cleared out. ====
    (add-in-scheme! dataset)
    (regenerate-short-labels! dataset)))

(defn ->dataset
  "The db of a dataset of the given `db-type`, :tdb2 or :in-mem, with the
  DanNet prefixes. TDB2 also requires a `db-path`.

  NOTE: TDB2 *always* requires transactions when reading from or writing to
  the database."
  [db-type & [db-path]]
  (case db-type
    :tdb2 (tdb2/connect db-path {:prefixes prefix/prefixes})
    :in-mem (theo/db {:prefixes prefix/prefixes})))

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

(defn logged-db-name
  "The db-name of the last build recorded in the log at `log-path`, which a
  start without an input dir reopens."
  [log-path]
  (->> (slurp log-path)
       (re-seq #"Location: (.+)")
       (last)
       (second)))

(def reasoner
  "The custom reasoner inferring many triples present in the complete dataset.

  The rules in 'dannet.rules' are purpose-built for DanNet, covering only
  owl:inverseOf and rdfs:subPropertyOf entailment as tabled backward rules."
  (rules/reasoner (rules/parse (io/resource "etc/dannet.rules"))
                  {:mode                        :hybrid
                   :transitive-closure-caching? true}))

(defn dataset->db
  "The DanNet db map of the `dataset` db: the :dataset itself, the :base union
  of its named graphs, and the :inference db, which infers with the schemas
  of `schema-uris` when they are provided and is the :base db otherwise.

  The SPARQL endpoint queries the :base db, since inference is opt-in there."
  [dataset & [schema-uris]]
  (let [base (theo/union-graph dataset)]
    {:dataset   dataset
     :base      base
     :inference (if schema-uris
                  (let [schema (db/->schema-model schema-uris)]
                    ;; A plain :info log, not a trace! around createInfModel:
                    ;; Jena builds the InfModel lazily, so tracing here would
                    ;; report a near-zero runtime. The real inference cost is
                    ;; realized later, on first traversal; see the
                    ;; :dannet.graph/* traces in dk.cst.dannet.web.instance.
                    (t/log! {:level :info
                             :id    :dannet.graph/inference-model
                             :data  {:schema-count (count schema-uris)}}
                            "Constructing inference model")
                    (theo/db (rules/inf-model reasoner schema (:model base))
                             {:prefixes (:prefixes dataset)}))
                  base)}))

(defn prepare-input-dir!
  "Check that `input-dir` holds the datasets of release/from, or refetch them
  when `refetch?`, then reload the indegree cache they include."
  [input-dir refetch?]
  (downloads/assert-input-dir! input-dir release/from)
  (if refetch?
    (downloads/refetch-datasets! input-dir release/from)
    (downloads/assert-datasets-present! input-dir))
  ;; The indegree cache can arrive with the fetch above, i.e. after this
  ;; namespace was loaded, so the delay is re-derived here.
  (q/reload-synset-indegrees!))

(defn assert-expected-dannet-release!
  "Assert that the DanNet data in `graph` is the expected release to bootstrap
  from.

  On mismatch, reports the version actually found so it's diagnosable: the
  dataset-level <dn> owl:versionInfo is authoritative, but older releases carry
  no such triple, so we fall back to a sample of any versionInfo values present."
  [graph]
  (let [expected (q/triples graph [md/<dn> :owl/versionInfo release/from])]
    (when (empty? expected)
      (let [dn-vers (->> (q/triples graph [md/<dn> :owl/versionInfo '_])
                         (map #(str (get % '?o)))
                         (distinct)
                         (vec))
            actual  (if (seq dn-vers)
                      dn-vers
                      (->> (q/triples graph ['_ :owl/versionInfo '_])
                           (map #(str (get % '?o)))
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

(h/defn import-release-zips!
  "Import the release zips in `input-dir` into their graphs in `dataset`,
  replacing the dataset metadata of each with what `metadata` holds for its
  prefix.

  The DanNet graph is checked by assert-expected-dannet-release! before it is
  added to `dataset`."
  [dataset input-dir metadata]
  (let [zip-file? (comp #(str/ends-with? % ".zip") #(.getName %))
        ttl-file? (comp #(str/ends-with? % ".ttl") #(.getName %))]
    (doseq [zip-file (filter zip-file? (file-seq input-dir))]
      ;; A .ttl left behind by a failed import would be mistaken for this
      ;; zip's own, so any stray one is deleted first.
      (run! zip/delete-file (filter ttl-file? (file-seq input-dir)))
      ;; unzip writes to (str output-parent (.getName entry)) with no
      ;; separator, so output-parent must be a dir path ending in "/";
      ;; otherwise entries get the zip's own path prepended (e.g.
      ;; "oewn-extension.zipoewn-extension.ttl").
      (zip/unzip zip-file (str (.getParent ^File zip-file) "/"))
      ;; Each zip holds one .ttl (beside LICENSE and README.txt) that is deleted
      ;; after import, so the only .ttl in input-dir is the one just unzipped.
      (let [ttl-file  (first (filter ttl-file? (file-seq input-dir)))
            model-uri (prefix/zip-file->uri (.getName zip-file))
            prefix    (prefix/uri->prefix model-uri)
            update!   (when prefix
                        (partial md/update-metadata! (metadata prefix)))
            ;; Special behaviour to check bootstrap files version
            changefn  (if (= prefix 'dn)
                        (fn [temp]
                          (t/log! {:level :debug
                                   :id    :dannet.bootstrap/version-check
                                   :data  {:model (str model-uri)}}
                                  "Checking bootstrap version")
                          (assert-expected-dannet-release! temp)
                          (update! temp))
                        update!)]
        (db/import-files dataset (prefix/uri->rdf-resource model-uri) [ttl-file]
                         changefn)
        (zip/delete-file ttl-file)))))

(h/defn ->dannet
  "Create the DanNet db map from the release files in `input-dir`, reusing a
  completed build with the same db-name.

    :input-dir   - the release/from bootstrap dir as a File; when absent, the
                   database of the last build in the log is reopened
    :db-type     - :tdb2 or :in-mem; defaults to :in-mem
    :db-path     - where to persist the TDB2 data and the build log
    :schema-uris - the schemas behind the inference model
    :refetch?    - first wipe and re-download the release files in input-dir"
  [& {:keys [^File input-dir db-path db-type schema-uris refetch?]
      :or   {db-type :in-mem} :as opts}]
  (let [log-path (str db-path "/log.txt")]
    (if input-dir
      ;; Prepared first, since the file-seq below expects the inputs on disk.
      (let [_              (prepare-input-dir! input-dir refetch?)
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
                            (hash dangling-oewn-link-changes)
                            (:hash (meta #'fix-dangling-oewn-links!))
                            (:hash (meta #'retype-long-literals!))
                            (:hash (meta #'synset-label))
                            (:hash (meta #'add-in-scheme!))
                            (:hash (meta #'regenerate-short-labels!))
                            (:hash (meta #'md/add-dataset-statistics!))
                            (:hash (meta #'md/metadata))
                            (:hash (meta #'md/update-metadata!))
                            (:hash (meta #'->dannet))
                            (:hash (meta #'import-release-zips!))
                            (hash prefix/schemas)
                            ;; The hashes above cover code, not the values it
                            ;; reads, so a version bump also renames the db.
                            release/to
                            release/oewn-version
                            release/premon-version
                            release/cor-version
                            release/cor-ext-version
                            release/cor-sem-version]
            files-hash     (h/pos-hash files)
            bootstrap-hash (h/pos-hash fn-hashes)
            db-name        (str files-hash "-" bootstrap-hash)
            full-db-path   (str db-path "/" db-name)
            ;; Written as the final build step below; a directory without it
            ;; is a partial build (e.g. an exception mid-build) and is rebuilt.
            build-complete (io/file full-db-path "build-complete.txt")
            db-exists?     (.exists build-complete)
            new-entry      (log-entry db-name db-type input-dir)
            dataset        (->dataset db-type full-db-path)
            ;; Include the current build hash to make debugging easier
            metadata'      (update (md/metadata) 'dn conj [md/<dn> :dns/build db-name])]
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
              (import-release-zips! dataset input-dir metadata')

              ;; The English is always explicitly added as it is not part of our
              ;; own latest export (only the DanNet-like labels we produce are).
              ;; It is imported BEFORE the release changes, some of which may
              ;; need the OEWN graph for lookups.
              (add-open-english-wordnet! dataset)

              ;; The FrameNet frame inventory likewise arrives by download rather
              ;; than as part of our own export; add-in-scheme! in the release
              ;; changes marks it.
              (add-framenet! dataset)

              (make-release-changes! dataset)

              ;; Runs after the release changes so that the dataset
              ;; statistics reflect the data actually being exported.
              (md/add-dataset-statistics! dataset)

              (t/log! {:level :info
                       :id    :dannet.bootstrap/db-created
                       :data  {:db-name db-name}}
                      "Database created")
              (spit log-path (str new-entry "\n----\n") :append true)
              ;; An :in-mem db never creates the directory.
              (when (.isDirectory (io/file full-db-path))
                (spit build-complete new-entry))
              (dataset->db dataset schema-uris)))))
      (let [db-name      (logged-db-name log-path)
            full-db-path (str db-path "/" db-name)
            dataset      (->dataset db-type full-db-path)]
        (t/log! {:level :warn :id :dannet.bootstrap/no-input-dir}
                "No input dir provided; reusing existing database from log")
        (dataset->db dataset schema-uris)))))
