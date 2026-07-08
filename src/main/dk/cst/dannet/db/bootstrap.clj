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
            [dk.cst.dannet.hash :as h]
            [dk.cst.dannet.prefix :as prefix])
  (:import [java.io File]
           [java.time LocalDateTime]
           [java.time.format DateTimeFormatter]
           [org.apache.jena.query Dataset DatasetFactory]
           [org.apache.jena.rdf.model Model ModelFactory Resource]
           [org.apache.jena.reasoner.rulesys GenericRuleReasoner Rule]
           [org.apache.jena.tdb TDBFactory]
           [org.apache.jena.tdb2 TDB2Factory]
           [org.apache.jena.util ResourceUtils]
           [org.apache.jena.vocabulary RDF]))

(defn assert-expected-dannet-release!
  "Assert that the DanNet `model` is the expected release to bootstrap from.

  On mismatch, reports the version actually found so it's diagnosable: the
  dataset-level <dn> owl:versionInfo is authoritative, but older releases carry
  no such triple, so we fall back to a sample of any versionInfo values present."
  [model]
  (let [graph    (.getGraph ^Model model)
        expected (q/run graph [:bgp [md/<dn> :owl/versionInfo md/bootstrap-base-release]])]
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
                 :data  {:expected md/bootstrap-base-release
                         :actual   actual}}
                "Bootstrap files are not the expected release")
        (throw (ex-info (str "bootstrap files not the expected release. Expected "
                             (pr-str md/bootstrap-base-release) ", found "
                             (if (seq actual) (pr-str actual) "no owl:versionInfo")
                             ". Restart with refetch (--refetch, or restart-refetch "
                             "in the REPL) to download the expected release.")
                        {:expected md/bootstrap-base-release
                         :actual   actual}))))))

(h/defn add-open-english-wordnet-labels!
  "Generate appropriate labels for the (otherwise unlabeled) OEWN in `dataset`."
  [dataset]
  (t/trace! {:id :dannet.bootstrap/oewn-labels :run-val :elided}
    (let [oewn-graph   (db/get-graph dataset prefix/oewn-uri)
          label-graph  (db/get-graph dataset prefix/oewn-extension-uri)
          ms           (q/run oewn-graph op/oewn-label-targets)
          collect-rep  (fn [m {:syms [?synset ?rep]}]
                         (update m ?synset conj (str ?rep)))
          synset-label (fn [labels]
                         (as-> labels $
                               (set $)
                               (sort $)
                               (str/join "; " $)
                               (md/en "{" $ "}")))]
      (txn/transact-exec dataset
        (t/log! {:level :debug
                 :id    :dannet.bootstrap/oewn-synset-labels
                 :data  {:graph (str prefix/oewn-extension-uri)}}
                "Adding OEWN synset labels")
        (->> (reduce collect-rep {} ms)
             (map (fn [[synset labels]]
                    [synset :rdfs/label (synset-label labels)]))
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
             (aristotle/add label-graph))))))

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

(h/defn fix-meronym-directionality!
  "Delete the 14 dn: triples with reversed or contradictory part-whole
  directionality flagged by the meronymy SHACL shapes (see shapes/base.ttl and
  GitHub issue #200): pairs asserting the same relation in both directions,
  and pairs asserting both a mero_* and a holo_* relation in the same
  direction. In each case the correct direction is kept (or, for
  gaffel/bordkniv, supplied by the existing bestik memberships)."
  [dataset]
  (let [model       (db/get-model dataset prefix/dn-uri)
        bad-triples [;; X holo_part Y contradicting X mero_part Y (førerhus IS part of truck)
                     [:dn/synset-1996 :wn/holo_part :dn/synset-1986] ; truck -> førerhus
                     ;; sausages asserted as parts of {dyr} (contradicting mero_substance)
                     [:dn/synset-34919 :wn/holo_part :dn/synset-3262] ; kødpølse
                     [:dn/synset-34928 :wn/holo_part :dn/synset-3262] ; rullepølse
                     [:dn/synset-34929 :wn/holo_part :dn/synset-3262] ; salami
                     [:dn/synset-34930 :wn/holo_part :dn/synset-3262] ; spegepølse
                     [:dn/synset-47102 :wn/holo_part :dn/synset-3262] ; chorizo
                     [:dn/synset-67687 :wn/holo_part :dn/synset-3262] ; peperoni
                     ;; X holo_substance Y contradicting X mero_substance Y ({stof} is the substance)
                     [:dn/synset-59770 :wn/holo_substance :dn/synset-10735] ; armbind
                     [:dn/synset-59775 :wn/holo_substance :dn/synset-10735] ; sørgebind
                     ;; wrong half of mutually asserted pairs
                     [:dn/synset-9749 :wn/mero_part :dn/synset-15530] ; receiver -> stereoanlæg
                     [:dn/synset-38029 :wn/mero_part :dn/synset-1709] ; klaviatur -> klaver
                     [:dn/synset-29801 :wn/mero_member :dn/synset-5637] ; midtbanespiller -> midtbane
                     ;; neither is a member of the other; {bestik} already has both as members
                     [:dn/synset-17988 :wn/mero_member :dn/synset-39561] ; bordkniv -> gaffel
                     [:dn/synset-39561 :wn/mero_member :dn/synset-17988]]] ; gaffel -> bordkniv
    (txn/transact-exec model
      (t/log! {:level :info
               :id    :dannet.bootstrap/fix-meronym-directionality
               :data  {:count (count bad-triples)}}
              "Removing reversed/contradictory meronym triples")
      (doseq [triple bad-triples]
        (db/remove! model triple)))))

(h/defn fix-cross-pos-hypernymy!
  "Replace the dns:crossPoSHypernym bandaid relation (GitHub issue #146) with
  proper GWA relations -- or remove it -- based on an analysis of the 72
  distinct target synsets of the 5,636 asserted triples. The sources are all
  adjective synsets; the targets fall into four groups:

    1. quality/dimension nouns (~5,400 pairs) -> wn:attribute
    2. genuine taxonomic hypernyms of substantivised adjectives -> wn:classified_by
    3. mistagged verb phrases -> PoS corrected + relation restored as wn:hypernym
    4. pertainym-like and participle-like leftovers with no GWA-valid synset
       relation -> deleted

  Only the hypernym direction is asserted in the dataset; dns:crossPoSHyponym
  exists solely through OWL inference, so no inverse triples need removal."
  [dataset]
  (let [crosspos-query '[:bgp [?s :dns/crossPoSHypernym ?o]]

        ;; Quality/dimension nouns of which the source adjectives express
        ;; values, e.g. {frisk} -> {helbred} or {lavendelfarvet} -> {farve}.
        ;; This matches the GWA definition of wn:attribute: "a relation between
        ;; nominal and adjectival concepts where the concept A is an attribute
        ;; of concept B" -- and mirrors how the OEWN uses the relation.
        attribute-targets
                       #{:dn/synset-14959                   ; {beskaffenhed; egenskab; side}
                         :dn/synset-21666                   ; {karaktertræk; personlighedstræk}
                         :dn/synset-21732                   ; {form}
                         :dn/synset-21809                   ; {stilling; tilstand}
                         :dn/synset-42030                   ; {udseende}
                         :dn/synset-25985                   ; {dygtighed; evne; kapacitet; ...}
                         :dn/synset-14526                   ; {sindstilstand}
                         :dn/synset-14996                   ; {grøn; grønt}
                         :dn/synset-14954                   ; {farve; kulør; kulørt}
                         :dn/synset-69924                   ; {oprindelse}
                         :dn/synset-15180                   ; {farve}
                         :dn/synset-14978                   ; {brun}
                         :dn/synset-8360                    ; {størrelse; udstrækning}
                         :dn/synset-25969                   ; {dygtighed; gave}
                         :dn/synset-21810                   ; {helbred; konstitution; ...}
                         :dn/synset-15034                   ; {grå}
                         :dn/synset-21697                   ; {træk}
                         :dn/synset-14924                   ; {hårfarve}
                         :dn/synset-68263                   ; {evne}
                         :dn/synset-18060                   ; {tilstand}
                         :dn/synset-68918                   ; {mål}
                         :dn/synset-31257                   ; {vægt}
                         :dn/synset-14244                   ; {følelse; fornemmelse}
                         :dn/synset-21694                   ; {egenart; særpræg}
                         :dn/synset-14871                   ; {grundfarve; primærfarve}
                         :dn/synset-14915                   ; {hudfarve}
                         :dn/synset-8359                    ; {omfang; udstrækning}
                         :dn/synset-14694                   ; {lyst}
                         :dn/synset-8365                    ; {kvantitet; mængde}
                         :dn/synset-14914                   ; {farve; hudfarve}
                         :dn/synset-22215                   ; {skønhed}
                         :dn/synset-62217                   ; {mangel}
                         :dn/synset-45419                   ; {adfærd}
                         :dn/synset-14948                   ; {farvetone; nuance}
                         :dn/synset-8524                    ; {grad; styrke}
                         :dn/synset-8526                    ; {fart; hastighed}
                         :dn/synset-57665                   ; {funktion; gang}
                         :dn/synset-14937                   ; {purpurfarve; purpur}
                         :dn/synset-26104                   ; {sans}
                         :dn/synset-21827}                  ; {styrke}

        ;; Genuine taxonomic hypernyms whose hyponyms are substantivised
        ;; adjectives, e.g. {mongolsk} -> {sprog} or {hjemløs} -> {person}.
        ;; The taxonomy is real, but plain wn:hypernym would violate the
        ;; same-PoS restriction, so wn:classified_by is used instead.
        classified-by-targets
                       #{:dn/synset-8143                    ; {sprog} <- language names
                         :dn/synset-8091                    ; {dialekt; folkemål; mundart}
                         :dn/synset-8109                    ; {dansk; dansken}
                         :dn/synset-7878                    ; {bandeord; ed; kraftudtryk}
                         :dn/synset-8079                    ; {skældsord; ukvemsord}
                         :dn/synset-48279                   ; {ansat}
                         :dn/synset-2119                    ; {individ; menneske; person}
                         :dn/synset-6217                    ; {medarbejder}
                         :dn/synset-48734                   ; {forældre}
                         :dn/synset-1478                    ; {kaffe; mokka}
                         :dn/synset-116}]                   ; {drik; drikkevare}

    ;; === 1. Adjective values of quality nouns -> wn:attribute ===
    ;; Covers ~96% of all dns:crossPoSHypernym triples, the vast majority of
    ;; which target {egenskab} and {karaktertræk}.
    (t/log! {:level :info
             :id    :dannet.bootstrap/crosspos->attribute
             :data  {:targets (count attribute-targets)}}
            "Converting cross-PoS hypernyms of quality nouns to wn:attribute")
    (db/update-triples!
      prefix/dn-uri dataset crosspos-query
      (fn [{:syms [?s ?o]}]
        (when (attribute-targets ?o)
          [?s :wn/attribute ?o]))
      (fn [{:syms [?s ?o]}]
        (when (attribute-targets ?o)
          [?s :dns/crossPoSHypernym ?o])))

    ;; === 2. Substantivised adjectives -> wn:classified_by ===
    (t/log! {:level :info
             :id    :dannet.bootstrap/crosspos->classified-by
             :data  {:targets (count classified-by-targets)}}
            "Converting cross-PoS hypernyms of substantivised adjectives to wn:classified_by")
    (db/update-triples!
      prefix/dn-uri dataset crosspos-query
      (fn [{:syms [?s ?o]}]
        (when (classified-by-targets ?o)
          [?s :wn/classified_by ?o]))
      (fn [{:syms [?s ?o]}]
        (when (classified-by-targets ?o)
          [?s :dns/crossPoSHypernym ?o])))

    ;; === 3. Mistagged verb phrases: correct PoS, restore wn:hypernym ===
    ;; {gøre rent} and {gøre sig til gode} are verb phrases erroneously tagged
    ;; as adjectives (lexfile adj.ppl). With the PoS corrected, their original
    ;; hypernym relations no longer cross a PoS boundary and can be restored.
    ;; The new lexfiles are inherited from the respective hypernyms.
    (t/log! {:level :info
             :id    :dannet.bootstrap/crosspos-pos-fix
             :data  {:synsets [:dn/synset-27764 :dn/synset-74898]}}
            "Correcting PoS of mistagged verb phrases, restoring wn:hypernym")
    (let [model (db/get-model dataset prefix/dn-uri)
          g     (db/get-graph dataset prefix/dn-uri)]
      (txn/transact-exec model
        (doseq [triple [;; {gøre rent} -> {fjerne}
                        [:dn/synset-27764 :dns/crossPoSHypernym :dn/synset-27703]
                        [:dn/synset-27764 :wn/lexfile "adj.ppl"]
                        [:dn/word-11042803-23 :lexinfo/partOfSpeech :lexinfo/adjective]
                        [:dn/word-11042803-23 :wn/partOfSpeech :wn/adjective]
                        ;; {gøre sig til gode} -> {indtage}
                        [:dn/synset-74898 :dns/crossPoSHypernym :dn/synset-637]
                        [:dn/synset-74898 :wn/lexfile "adj.ppl"]
                        [:dn/word-11018326-23 :lexinfo/partOfSpeech :lexinfo/adjective]
                        [:dn/word-11018326-23 :wn/partOfSpeech :wn/adjective]]]
          (db/remove! model triple)))
      (txn/transact-exec g
        (db/safe-add! g [;; {gøre rent} -> {fjerne}
                         [:dn/synset-27764 :wn/hypernym :dn/synset-27703]
                         [:dn/synset-27764 :wn/lexfile "verb.motion"]
                         [:dn/word-11042803-23 :lexinfo/partOfSpeech :lexinfo/verb]
                         [:dn/word-11042803-23 :wn/partOfSpeech :wn/verb]
                         ;; {gøre sig til gode} -> {indtage}
                         [:dn/synset-74898 :wn/hypernym :dn/synset-637]
                         [:dn/synset-74898 :wn/lexfile "verb.consumption"]
                         [:dn/word-11018326-23 :lexinfo/partOfSpeech :lexinfo/verb]
                         [:dn/word-11018326-23 :wn/partOfSpeech :wn/verb]])))

    ;; === 4. Delete the remaining cross-PoS violations ===
    ;; What is left after sections 1-3 are pertainym-like cases (e.g.
    ;; {kvartårlig} -> {år}, {boligpolitisk} -> {boligpolitik}) and participial
    ;; adjectives pointing at verbs (e.g. {hjelmklædt} -> {klæde}). The correct
    ;; GWA relations (pertainym, participle) are sense-level relations, so no
    ;; valid synset-level replacement exists; the triples are simply deleted.
    (t/log! {:level :info
             :id    :dannet.bootstrap/crosspos-delete}
            "Deleting remaining pertainym/participle-like cross-PoS hypernyms")
    (db/update-triples!
      prefix/dn-uri dataset crosspos-query
      (constantly nil)
      (fn [{:syms [?s ?o]}]
        [?s :dns/crossPoSHypernym ?o]))))

(h/defn add-in-scheme!
  "Add skos:inScheme to all DanNet and COR resources (GitHub issue #175),
  mirroring how the OEWN marks scheme membership on its resources. Every URI
  subject residing in the namespace of its containing model is linked to the
  RDF resource of the relevant dataset. The DDS dataset is deliberately left
  out since it contains no resources of its own, only annotations of dn:
  resources; the OEWN label extensions don't need it either."
  [dataset]
  (doseq [[model-uri scheme] [[prefix/dn-uri md/<dn>]
                              [prefix/cor-uri md/<cor>]]]
    (let [model    (db/get-model dataset model-uri)
          g        (db/get-graph dataset model-uri)
          subjects (txn/transact model
                     (->> (iterator-seq (.listSubjects model))
                          (filter #(.isURIResource %))
                          (map #(.getURI %))
                          (filter #(str/starts-with? % model-uri))
                          (doall)))]
      (txn/transact-exec g
        (t/log! {:level :info
                 :id    :dannet.bootstrap/add-in-scheme
                 :data  {:scheme scheme
                         :count  (count subjects)}}
                "Adding skos:inScheme to resources")
        (db/safe-add! g (for [uri subjects]
                          [(prefix/uri->rdf-resource uri) :skos/inScheme scheme]))))))

(h/defn anonymize-inheritance!
  "Convert the named dn:inherit-* resources into anonymous resources, i.e.
  blank nodes (GitHub issue #182). Inheritance markings are just synset
  metadata, so there is no reason to mint a unique URI for each instance.
  ResourceUtils/renameResource moves every statement mentioning the resource
  -- in both subject and object position -- over to the new blank node.

  NOTE: must run BEFORE add-in-scheme! so that the inheritance resources do
  not get skos:inScheme triples attached prior to losing their dn: URIs."
  [dataset]
  (let [model (db/get-model dataset prefix/dn-uri)]
    (txn/transact-exec model
      (let [inheritance (.createResource model (prefix/kw->uri :dns/Inheritance))
            named       (->> (.listSubjectsWithProperty model RDF/type inheritance)
                             (iterator-seq)
                             (filter #(.isURIResource ^Resource %))
                             (doall))]
        (t/log! {:level :info
                 :id    :dannet.bootstrap/anonymize-inheritance
                 :data  {:count (count named)}}
                "Converting named inheritance resources to blank nodes")
        (doseq [^Resource r named]
          (ResourceUtils/renameResource r nil))))))

(h/defn make-release-changes!
  "This function tracks all changes made in this release, i.e. deletions and
  additions to either of the export datasets.

  This function survives between releases, but the functions it calls are all
  considered temporary and should be deleted when the release comes."
  [dataset]
  ;; Cleanup tripwire. This literal is a deliberate duplicate of (:from md/release).
  ;; It stays stable throughout a development cycle, so it never interferes with
  ;; everyday rebuilds. When the NEXT cycle is opened and :from is bumped to the
  ;; release that was just cut, this assertion fires -- forcing the now-shipped
  ;; temporary changes below to be cleared out and this marker bumped to match.
  (assert (= "2025-07-03" (:from md/release))
          (str "make-release-changes! still holds changes for the old release, "
               "but (:from release) is now " (:from md/release) ". "
               "Clear out the shipped changes and update this marker."))
  (t/log! {:level :info
           :id    :dannet.bootstrap/release-changes
           :data  {:version md/new-release}}
          "Applying release changes")

  ;; ==== The block of changes for this particular release. ====
  (fix-meronym-directionality! dataset)
  (fix-cross-pos-hypernymy! dataset)
  (anonymize-inheritance! dataset)
  (add-in-scheme! dataset))

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
      ;; real inference cost is realized later, on first traversal -- see the
      ;; :dannet.graph/* traces in dk.cst.dannet.web.resources.
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
      ;; Either refetch (wipe stale/version-bound datasets and re-download the
      ;; required versions) or assert the datasets are already present. Neither
      ;; downloads silently on a normal start; both run for side effects before
      ;; the file-seq below, which expects the inputs on disk.
      (let [_              (if refetch?
                             (downloads/refetch-datasets! input-dir (:from md/release))
                             (downloads/assert-datasets-present! input-dir))
            files          (->> (file-seq input-dir)
                                (filter #(re-find #"\.zip$" (.getName ^File %))))
            fn-hashes      [(:hash (meta #'add-open-english-wordnet!))
                            (:hash (meta #'add-open-english-wordnet-labels!))
                            (:hash (meta #'make-release-changes!))
                            (:hash (meta #'md/add-dataset-statistics!))
                            (:hash (meta #'md/metadata))
                            (:hash (meta #'md/update-metadata!))
                            (:hash (meta #'->dannet))
                            (hash prefix/schemas)
                            ;; The emitted version is baked into the dataset
                            ;; metadata but isn't captured by any hashed form
                            ;; above (those hash source, not values), so include
                            ;; it explicitly -- otherwise cutting a release
                            ;; (:to "SNAPSHOT" -> a real version, same :from)
                            ;; wouldn't change db-name and the stale, still
                            ;; SNAPSHOT-labelled database would be reused.
                            md/new-release
                            ;; The OEWN edition is likewise only a value: its
                            ;; ttl isn't among the hashed input zips, so it is
                            ;; included explicitly -- otherwise bumping the
                            ;; edition wouldn't trigger a rebuild.
                            downloads/oewn-version]
            ;; Undo potentially negative number by bit-shifting.
            files-hash     (h/pos-hash files)
            bootstrap-hash (h/pos-hash fn-hashes)
            db-name        (str files-hash "-" bootstrap-hash)
            full-db-path   (str db-path "/" db-name)
            zip-file?      (comp #(str/ends-with? % ".zip") #(.getName %))
            ttl-file?      (comp #(str/ends-with? % ".ttl") #(.getName %))
            db-exists?     (.exists (io/file full-db-path))
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
                ;; separator, so output-parent must be a dir path ending in "/"
                ;; -- otherwise entries get the zip's own path prepended (e.g.
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

              ;; Effectuate changes for the current release.
              ;; These are always tied to the current release and depend on the
              ;; former release, i.e. the contents of this function is versioned
              ;; together with every single formal release.
              (make-release-changes! dataset)

              ;; Runs after the release changes so that the dataset
              ;; statistics reflect the data actually being exported.
              (md/add-dataset-statistics! dataset)

              ;; The English is always explicitly added as it is not part of our
              ;; own latest export (only the DanNet-like labels we produce are).
              (add-open-english-wordnet! dataset)

              (t/log! {:level :info
                       :id    :dannet.bootstrap/db-created
                       :data  {:db-name db-name}}
                      "Database created")
              (spit log-path (str new-entry "\n----\n") :append true)
              (dataset->db dataset schema-uris)))))
      (let [db-name      (->> (slurp log-path)
                              (re-seq #"Location: (.+)")
                              (last)
                              (second))
            full-db-path (str db-path "/" db-name)
            dataset      (->dataset db-type full-db-path)]
        (t/log! {:level :warn :id :dannet.bootstrap/no-input-dir}
                "No input dir provided -- reusing existing database from log")
        (dataset->db dataset schema-uris)))))
