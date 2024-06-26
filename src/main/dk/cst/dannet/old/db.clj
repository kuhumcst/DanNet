(ns dk.cst.dannet.old.db
  "Represent DanNet as an in-memory graph or within a persisted database (TDB)."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.data.csv :as csv]
            [clj-file-zip.core :refer [zip-files]]
            [arachne.aristotle :as aristotle]
            [arachne.aristotle.registry :as registry]
            [clojure.walk :as walk]
            [donatello.ttl :as ttl]
            [ont-app.igraph-jena.core :as igraph-jena]
            [ont-app.igraph.core :as igraph]
            [ont-app.vocabulary.core :as voc]
            [flatland.ordered.map :as fop]
            [ont-app.vocabulary.lstr :as lstr]
            [ont-app.vocabulary.lstr :refer [->LangStr]]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.db.export.csv :as export.csv]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.old.bootstrap :as bootstrap :refer [da]]
            [dk.cst.dannet.hash :as h]
            [dk.cst.dannet.query :as q]
            [dk.cst.dannet.query.operation :as op]
            [dk.cst.dannet.transaction :as txn])
  (:import [clojure.lang Symbol]
           [ont_app.vocabulary.lstr LangStr]
           [org.apache.jena.riot RDFDataMgr RDFFormat]
           [org.apache.jena.tdb TDBFactory]
           [org.apache.jena.tdb2 TDB2Factory]
           [org.apache.jena.rdf.model ModelFactory Model ResourceFactory Statement]
           [org.apache.jena.query Dataset DatasetFactory]
           [org.apache.jena.reasoner.rulesys GenericRuleReasoner Rule]
           [java.time LocalDateTime]
           [java.time.format DateTimeFormatter]
           [java.io File StringWriter]))

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
                                      (shared/sense-labels shared/synset-sep)
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

(defn relabel-synsets
  "Return [triples-to-remove triples-to-add] for relabeled synsets in `g`."
  [g]
  (let [kv->triple        (fn [[synset label]]
                            [synset :rdfs/label label])
        synset->results   (->> (q/run g op/synset-relabeling)
                               (group-by '?synset)
                               (into {}))
        synset->label     (update-vals synset->results
                                       (fn [ms]
                                         (get (first ms) '?synsetLabel)))
        synset->new-label (-> synset->results
                              (update-vals (fn [ms]
                                             (let [inner (->> (map '?label ms)
                                                              (set)
                                                              (sort-by str)
                                                              (str/join "; "))]
                                               (da (str "{" inner "}")))))
                              (set)
                              (set/difference (set synset->label))
                              (->> (into {})))]
    [(->> (keys synset->new-label)
          (select-keys synset->label)
          (map kv->triple)
          (set))
     (set (map kv->triple synset->new-label))]))

(defn find-duplicates
  "Find duplicate synsets in `g`, i.e. each predicate-object pair is identical."
  [g]
  (let [shared-vals (comp
                      set
                      vals
                      #(select-keys % '[?label ?definition ?ontotype]))
        sets-only   (fn [coll]
                      (->> coll
                           (mapcat (comp vals #(select-keys % '[?s1 ?s2])))
                           (set)))
        candidates  (-> (q/run g op/duplicate-synsets)
                        (->> (group-by shared-vals))
                        (update-vals sets-only)
                        (vals))]
    (filter (fn [ids]
              (apply = (map #(dissoc (q/entity-map g %) :dns/inherited) ids)))
            candidates)))

(defn find-intersections
  "Find synset intersections in `g`, i.e. illegally shared sense resources.
  Returns a mapping from [sense word label] to the set of relevant synsets."
  [g]
  (-> (group-by (juxt '?sense '?word '?label) (q/run g op/synset-intersection))
      (update-vals (fn [ms]
                     (reduce (fn [acc m]
                               (-> acc
                                   (conj (get m '?synset))
                                   (conj (get m '?otherSynset))))
                             #{} ms)))))

;; NOTE: these will also be relabeled as the penultimate step of the bootstrap!
(defn discrete-sense-triples
  "Generate new triples to add based on `synset-intersection`."
  [synset-intersection]
  (->> synset-intersection
       (mapcat (fn [[[sense word label] synsets]]
                 (let [sense-id (re-find #"\d+$" (name sense))]
                   (map-indexed
                     (fn [n synset]
                       (let [nid    (str "-i" (inc n))
                             sense' (bootstrap/sense-uri (str sense-id nid))
                             others (disj synsets synset)]
                         (into
                           #{[sense' :rdf/type :ontolex/LexicalSense]
                             [sense' :rdfs/label label]
                             [sense' :dns/dslSense sense-id] ; temporary link
                             [synset :ontolex/lexicalizedSense sense']
                             [word :ontolex/sense sense']}
                           (for [other others]
                             [synset :wn/similar other]))))
                     (sort synsets)))))
       (reduce set/union #{})))

(defn intersecting-sense-triples
  "Generate triple patterns to remove based on `synset-intersection`."
  [synset-intersection]
  (->> (keys synset-intersection)
       (map (fn [[sense _ _]]
              #{[sense '_ '_]
                ['_ '_ sense]}))
       (reduce set/union #{})))

(defn find-undefined-synset-triples
  [g]
  (->> (q/run g op/undefined-synset-triples)
       (map (juxt '?synset '?p '?otherResource))
       (set)))

(defn find-orphan-resources
  [union-graph]
  (->> (q/run union-graph op/orphan-dn-resources)
       (map '?resource)
       (set)))

(defn indexed-sense-triple
  "Create a uniquely labeled `sense` triple from `label` and index `n`."
  [label n sense]
  (let [match  #"(_[^ ]+)"
        n'     (str "(" (inc n) ")")
        label' (str/replace (str label) match (str "$1" n'))]
    [sense :rdfs/label (bootstrap/da (if (= label label')
                                       (str label "_" n')
                                       label'))]))

(defn unique-synset-label-triple
  [[synset labels]]
  [synset :rdfs/label (bootstrap/da (str "{" (str/join "; " labels) "}"))])

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
      (prn data (.getMessage e)))
    (finally
      g)))

(h/defn add-bootstrap-import!
  "Add the `bootstrap-imports` of the old DanNet CSV files to a Jena `dataset`."
  [dataset bootstrap-imports]
  (let [{:keys [examples]} (get bootstrap-imports prefix/dn-uri)
        dn-graph    (get-graph dataset prefix/dn-uri)
        dn-model    (get-model dataset prefix/dn-uri)
        cor-graph   (get-graph dataset prefix/cor-uri)
        cor-model   (get-model dataset prefix/cor-uri)
        senti-graph (get-graph dataset prefix/dds-uri)
        senti-model (get-model dataset prefix/dds-uri)
        union-model (.getUnionModel dataset)
        union-graph (.getGraph union-model)]

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
               (reduce safe-add! g)))))

    (let [triples (doall (->superfluous-definition-triples dn-graph))]
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
        (safe-add! dn-graph example-triples)))

    ;; Missing words for the 2023 adjectives data are synthesized from senses.
    ;; This step cannot be performed as part of the basic bootstrap since we
    ;; must avoid synthesizing new words for existing senses in the data!
    (let [missing (doall (bootstrap/synthesize-missing-words dn-graph))]
      (println "Synthesizing" (count missing) "missing words for 2023 data...")
      (txn/transact-exec dn-graph
        (safe-add! dn-graph missing)))

    ;; Missing inherited relations for the 2023 adjectives data are synthesized.
    (let [inherited (doall (bootstrap/synthesize-inherited-relations dn-graph))]
      (println "Synthesizing" (count inherited) "inherited relations for 2023 data...")
      (txn/transact-exec dn-graph
        (safe-add! dn-graph inherited)))

    ;; Senses are unlabeled in the raw dataset and also need to query the graph
    ;; to steal labels from the words they are senses of.
    (let [sense-label-triples (doall (->sense-label-triples dn-graph))]
      (println "Stealing" (count sense-label-triples) "sense labels...")
      (txn/transact-exec dn-graph
        (safe-add! dn-graph sense-label-triples)))

    ;; Remove self-referential hypernyms; this is just an obvious type of error.
    (let [ms (set (doall (q/run dn-graph op/self-referential-hypernyms)))]
      (println "Removing" (count ms) "self-referencing hypernyms.")
      (txn/transact-exec dn-model
        (doseq [{:syms [?synset]} ms]
          (remove! dn-model [?synset :wn/hyponym ?synset])
          (remove! dn-model [?synset :wn/hypernym ?synset]))))

    ;; Remove duplicate synsets (identical predicate-object pairs).
    ;; The lowest index synset is kept in every case; other synsets are removed.
    ;; Referencing triples and generated inheritance triples are also removed.
    (let [duplicates        (find-duplicates dn-graph)
          synset-ids        (set (mapcat (comp rest sort) duplicates))
          find-inherited    (fn [synset-id]
                              (->> [:bgp [synset-id :dns/inherited '?inherited]]
                                   (q/run-basic dn-graph '[?inherited])))
          inherit-triples   (->> synset-ids
                                 (mapcat find-inherited)
                                 (map (fn [[inherit-id]]
                                        [inherit-id '_ '_]))
                                 (doall))
          synset-triples    (for [synset synset-ids]
                              [synset '_ '_])
          reference-triples (for [synset synset-ids]
                              ['_ '_ synset])]
      (println "Removing" (count synset-ids) "duplicate synset-ids...")
      (txn/transact-exec dn-model
        (doseq [triple synset-triples]
          (remove! dn-model triple))
        (doseq [triple inherit-triples]
          (remove! dn-model triple))
        (doseq [triple reference-triples]
          (remove! dn-model triple))))

    ;; Some of the new adjective triples reference synsets that we do not have
    ;; any data for (usually because the IDs are fully synthesized).
    (let [triples-to-remove (find-undefined-synset-triples dn-graph)]
      (println "Removing" (count triples-to-remove) "references to undefined synsets...")
      (txn/transact-exec dn-model
        (doseq [triple triples-to-remove]
          (remove! dn-model triple))))

    ;; The COR/DNS data has references to resources that don't exist in DanNet.
    ;; We can use the union graph to discover these so that we may remove them.
    (let [undefined-resources (find-orphan-resources union-graph)
          triples-to-remove   (mapcat (fn [?resource]
                                        [[?resource '_ '_]
                                         ['_ '_ ?resource]])
                                      undefined-resources)]
      (println "Removing references to" (count undefined-resources) "undefined resources...")
      (txn/transact-exec cor-model
        (doseq [triple triples-to-remove]
          (remove! cor-model triple)))
      (txn/transact-exec senti-model
        (doseq [triple triples-to-remove]
          (remove! senti-model triple))))

    ;; Senses must not appear in multiple synsets, so new senses are generated
    ;; and the existing synset intersection is removed.
    (let [synset-intersection (doall (find-intersections dn-graph))
          triples-to-remove   (intersecting-sense-triples synset-intersection)
          triples-to-add      (discrete-sense-triples synset-intersection)]
      (println "Removing" (count triples-to-remove) "synset intersections...")
      (txn/transact-exec dn-model
        (doseq [triple triples-to-remove]
          (remove! dn-model triple))
        (remove! dn-model '[_ :dns/dslSense _]))
      (txn/transact-exec dn-graph
        (safe-add! dn-graph triples-to-add)))

    ;; The sentiment data is adjusted for split senses (synset intersection).
    (let [ms                (q/run union-graph op/sentiment-dsl-senses)
          triples-to-remove (for [{:syms [?oldSense]} ms]
                              [?oldSense '_ '_])
          triples-to-add    (for [{:syms [?sense ?sentiment]} ms]
                              [?sense :dns/sentiment ?sentiment])]
      (println "Adjusting" (count ms) "senses in the sentiment data...")
      (txn/transact-exec senti-model
        (doseq [triple triples-to-remove]
          (remove! senti-model triple)))
      (txn/transact-exec senti-graph
        (safe-add! senti-graph triples-to-add)))

    ;; The COR data is adjusted for split senses (synset intersection).
    (let [ms                (q/run union-graph op/cor-dsl-senses)
          triples-to-remove (for [{:syms [?oldSense]} ms]
                              ['_' '_ ?oldSense])
          triples-to-add    (for [{:syms [?corWord ?sense]} ms]
                              [?corWord :ontolex/sense ?sense])]
      (println "Adjusting" (count ms) "senses in the COR data...")
      (txn/transact-exec cor-model
        (doseq [triple triples-to-remove]
          (remove! cor-model triple)))
      (txn/transact-exec cor-graph
        (safe-add! cor-graph triples-to-add)))

    ;; Since sense labels come from a variety of sources and since the synset
    ;; labels have not been synced with sense labels in DSL's CSV export,
    ;; it is necessary to relabel each synset whose senses have changed label.
    #_(let [[triples-to-remove triples-to-add] (relabel-synsets dn-graph)]
        (println "Relabeling" (count triples-to-add) "synsets...")
        (txn/transact-exec dn-model
          (doseq [triple triples-to-remove]
            (remove! dn-model triple)))
        (txn/transact-exec dn-graph
          (safe-add! dn-graph triples-to-add)))

    ;; In the sentiment data, several thousand senses do not have sense-level
    ;; sentiment data. In those case we can try to synthesize from the words
    ;; that *do* have.
    (let [senti-triples (->> (q/run union-graph op/missing-sense-sentiment)
                             (group-by '?word)
                             (filter #(= 1 (count (second %))))
                             (mapcat second)
                             (map (fn [{:syms [?sense ?opinion]}]
                                    [?sense :dns/sentiment ?opinion]))
                             (doall))]
      (println (str "Synthesizing " (count senti-triples) " sense sentiment triples..."))
      (txn/transact-exec senti-graph
        (safe-add! senti-graph senti-triples)))

    ;; Likewise, all synsets whose senses are collectively unambiguous will
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
                         (doall))]
      (println (str "Synthesizing " (count triples) " synset sentiment triples..."))
      (txn/transact-exec senti-graph
        (safe-add! senti-graph triples)))

    ;; Some synsets are inheriting relations from a non-existent synsets.
    ;; These will have to be removed from the dataset.
    (let [triples-to-remove (->> (q/run union-graph op/unknown-inheritance)
                                 (mapcat (fn [{:syms [?synset ?inherit]}]
                                           [[?synset :dns/inherited ?inherit]
                                            [?inherit '_ '_]]))
                                 (doall))]
      (println "Removing" (/ (count triples-to-remove) 2) "inheritance markers...")
      (txn/transact-exec dn-model
        (doseq [triple triples-to-remove]
          (remove! dn-model triple))))

    ;; As a penultimate step, all labels are relabeled with duplicates indexed.
    (let [label->senses     (-> (group-by '?label (q/run dn-graph op/sense-labels))
                                (update-vals (comp sort set (partial map '?sense)))
                                (->> (remove (comp (partial > 2) count second))))
          relabel-senses    (fn [[label senses]]
                              (map-indexed (partial indexed-sense-triple label)
                                           senses))
          triples-to-add    (mapcat relabel-senses label->senses)
          triples-to-remove (mapcat (fn [[label senses]]
                                      (for [sense senses]
                                        [sense :rdfs/label label]))
                                    label->senses)]
      (println "Creating" (count triples-to-add) "new, unique sense labels...")
      (txn/transact-exec dn-model
        (doseq [triple triples-to-remove]
          (remove! dn-model triple)))
      (txn/transact-exec dn-graph
        (safe-add! dn-graph triples-to-add)))

    ;; Finally, all synsets are relabeled based on their composite senses.
    (let [sort-labels       (comp sort set (partial map (comp str '?label)))
          synset->labels    (-> (group-by '?synset (q/run dn-graph op/synset-labels))
                                (update-vals sort-labels))
          triples-to-add    (map unique-synset-label-triple synset->labels)
          triples-to-remove (for [[synset _] synset->labels]
                              [synset :rdfs/label '_])]
      (println "Creating" (count triples-to-add) "new, unique synset labels...")
      (txn/transact-exec dn-model
        (doseq [triple triples-to-remove]
          (remove! dn-model triple)))
      (txn/transact-exec dn-graph
        (safe-add! dn-graph triples-to-add)))

    (println "----")
    (println "DanNet bootstrap done!")

    dataset))

(h/defn add-open-english-wordnet!
  "Add the Open English WordNet to a Jena `dataset`."
  [dataset]
  (println "Importing Open English Wordnet...")
  (let [temp-model (ModelFactory/createDefaultModel)
        temp-graph (.getGraph temp-model)
        oewn-file  "bootstrap/other/english/english-wordnet-2022.ttl"
        ili-file   "bootstrap/other/english/ili.ttl"]
    (println "... creating temporary in-memory graph")
    (aristotle/read temp-graph oewn-file)
    (println "... removing problematic entries")
    ;; The WordNet resource itself has too many outgoing relations,
    (remove! temp-model ["<http://wordnet-rdf.princeton.edu/>" :lime/entry '_])
    (txn/transact-exec dataset
      (println "... persisting temporary graph")
      (aristotle/add (get-graph dataset prefix/oewn-uri) temp-graph))
    (txn/transact-exec dataset
      (println "... adding ILI data")
      (aristotle/read (get-graph dataset prefix/ili-uri) ili-file)))
  (println "Open English Wordnet imported!"))

(h/defn add-open-english-wordnet-labels!
  "Generate appropriate labels for the (otherwise unlabeled) OEWN in `dataset`."
  [dataset]
  (println "Adding labels to the Open English Wordnet...")
  (let [oewn-graph   (get-graph dataset prefix/oewn-uri)
        label-graph  (get-graph dataset prefix/oewn-extension-uri)
        ms           (q/run oewn-graph op/oewn-label-targets)
        collect-rep  (fn [m {:syms [?synset ?rep]}]
                       (update m ?synset conj (str ?rep)))
        synset-label (fn [labels]
                       (as-> labels $
                             (set $)
                             (sort $)
                             (str/join "; " $)
                             (bootstrap/en "{" $ "}")))]
    (txn/transact-exec dataset
      (println "... adding synset labels to" prefix/oewn-extension-uri)
      (->> (reduce collect-rep {} ms)
           (map (fn [[synset labels]]
                  [synset :rdfs/label (synset-label labels)]))
           (aristotle/add label-graph)))
    (txn/transact-exec dataset
      (println "... adding sense and word labels to" prefix/oewn-extension-uri)
      (->> ms
           (mapcat (fn [{:syms [?sense ?word ?rep]}]
                     [[?word :rdfs/label (bootstrap/en "\"" ?rep "\"")]
                      [?sense :rdfs/label ?rep]]))
           (aristotle/add label-graph))))
  (println "Labels added to the Open English WordNet!"))

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
  [db-name db-type files]
  (let [now       (LocalDateTime/now)
        formatter (DateTimeFormatter/ofPattern "yyyy/MM/dd HH:mm:ss")
        filenames (sort (map #(.getName ^File %) files))]
    (str
      "Location: " db-name "\n"
      "Type: " db-type "\n"
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
                             (:hash (meta #'add-open-english-wordnet-labels!))
                             (hash prefix/schemas))
        ;; Undo potentially negative number by bit-shifting.
        files-hash     (pos-hash files)
        bootstrap-hash (pos-hash fn-hashes)
        log-path       (str db-path "/log.txt")
        loc-re         #"Location: (.+)\n"
        db-name        (if bootstrap-imports
                         (str files-hash "-" bootstrap-hash)
                         (do
                           (println "No bootstrap -- using latest instead...")
                           (second (last (re-seq loc-re (slurp log-path))))))
        full-db-path   (str db-path "/" db-name)
        db-exists?     (.exists (io/file full-db-path))
        new-entry      (log-entry db-name db-type files)
        dataset        (->dataset db-type full-db-path)]
    (println "Database name:" db-name)

    ;; Mutating the graph will of course also mutate the model & dataset.
    (if bootstrap-imports
      (if db-exists?
        (println "Skipping build -- database already exists:" full-db-path)
        (do
          (println "Data input has changed -- rebuilding database...")
          (add-bootstrap-import! dataset bootstrap-imports)
          (add-open-english-wordnet! dataset)
          (add-open-english-wordnet-labels! dataset)
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

(defn- ttl-path
  [path]
  (let [parts      (str/split path #"/")
        filename   (first (str/split (last parts) #"\."))
        parent-dir (str/join "/" (butlast parts))]
    (str parent-dir "/" filename ".ttl")))

(defn- non-zip-files
  [dir-path]
  (let [dir-file (io/file dir-path)]
    (->> (file-seq dir-file)
         (remove (partial = dir-file))
         (map #(.getPath %))
         (remove #(str/ends-with? % ".zip")))))

;; TODO: alternative RDF formats will not match filepath given by ttl-path
(defn export-rdf-model!
  "Export the `model` to the given zip file `path`. Content defaults to Turtle.

  The current prefixes in the Aristotle registry are used for the output,
  although a desired subset of :prefixes may also be specified.

  See: https://jena.apache.org/documentation/io/rdf-output.html"
  [path ^Model model & {:keys [fmt prefixes]
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
      (zip-files [ttl-file] path)
      ;; Clear temporarily added prefixes
      (.clearNsPrefixMap model)))
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
   (let [in-dir       (partial str dir)
         merged-ttl   (in-dir (prefix/export-file "rdf" 'dn "merged"))
         complete-ttl (in-dir (prefix/export-file "rdf" 'dn "complete"))
         model-uris   (txn/transact dataset
                        (->> (iterator-seq (.listNames ^Dataset dataset))
                             (remove prefix/not-for-export)
                             (doall)))]
     (println "Beginning RDF export of DanNet into" dir)
     (println "----")

     ;; The individual models contained in the dataset.
     (doseq [model-uri model-uris
             :let [^Model model (get-model dataset model-uri)
                   prefix       (prefix/uri->prefix model-uri)
                   filename     (in-dir (prefix/export-file "rdf" prefix))]]
       (export-rdf-model! filename model :prefixes (export-prefixes prefix)))

     ;; The OEWN extension data is exported separately from the other models,
     ;; since it isn't connected to a separate prefix (= graph).
     (export-rdf-model!
       (in-dir (get-in prefix/oewn-extension [:download "rdf" :default]))
       (get-model dataset prefix/oewn-extension-uri)
       :prefixes (get prefix/oewn-extension :export))

     ;; The union of the input datasets.
     (let [union-model (.getUnionModel dataset)]
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
  (txn/transact model
    (->> (voc/uri-for synset)
         (.getResource model)
         (.listProperties)
         (iterator-seq)
         (keep (fn [^Statement statement]
                 (let [prefix (str prefix/dn-uri "synset-")
                       obj    (str (.getObject statement))]
                   (when (str/starts-with? obj prefix)
                     [synset
                      (str (.getPredicate statement))
                      (voc/keyword-for obj)]))))
         (doall))))

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
         senses-ks  '[?sense ?synset ?word ?note]
         zip-path   (str dir (prefix/export-file "csv" 'dn))]
     (println "Fetching table rows:" synsets-ks)
     (export-csv-rows!
       (str dir "synsets.csv")
       (map csv-row (q/table-query g synsets-ks op/csv-synsets)))
     (export.csv/export-metadata!
       (str dir "synsets-metadata.json")
       export.csv/synsets-metadata)

     (println "Fetching table rows:" words-ks)
     (export-csv-rows!
       (str dir "words.csv")
       (map csv-row (q/table-query g words-ks op/csv-words)))
     (export.csv/export-metadata!
       (str dir "words-metadata.json")
       export.csv/words-metadata)

     (println "Fetching table rows:" senses-ks)
     (export-csv-rows!
       (str dir "senses.csv")
       (map csv-row (q/table-query g senses-ks op/csv-senses)))
     (export.csv/export-metadata!
       (str dir "senses-metadata.json")
       export.csv/senses-metadata)

     (println "Fetching inheritance data...")
     (export-csv-rows!
       (str dir "inheritance.csv")
       (map (fn [{:syms [?synset ?rel ?from]}]
              [(name ?synset)
               (voc/uri-for ?rel)
               (name ?from)])
            (q/run g op/csv-inheritance)))
     (export.csv/export-metadata!
       (str dir "inheritance-metadata.json")
       export.csv/inheritance-metadata)

     (println "Fetching example data...")
     (export-csv-rows!
       (str dir "examples.csv")
       (map csv-row (q/run g '[?sense ?example] op/csv-examples)))
     (export.csv/export-metadata!
       (str dir "examples-metadata.json")
       export.csv/examples-metadata)

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
     (export.csv/export-metadata!
       (str dir "relations-metadata.json")
       export.csv/relations-metadata)

     (println "Zipping CSV files and associated metadata into" zip-path "...")
     (zip-files (non-zip-files dir) zip-path))

   (println "----")
   (println "CSV Export of DanNet complete!"))
  ([dannet]
   (export-csv! dannet "export/csv/")))

(def donatello-prefixes-base
  (into {} (map (fn [[k v]]
                  [(keyword k) (:uri v)])
                prefix/schemas)))

;; Donatello compatibility with Aristotle blank nodes and ont-app LangStrings.
(defmethod ttl/serialize Symbol [x] (str "_:" (subs (str x) 1)))
(defmethod ttl/serialize LangStr [x] (str \" (ttl/escape (str x)) "\"@" (lstr/lang x)))

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

(defn ttl-entity
  "Get the equivalent TTL output for `entity`."
  [entity & [base]]
  (with-open [sw (StringWriter.)]
    (when base
      (ttl/write-base! sw base))
    (ttl/write-prefixes! sw (donatello-prefixes entity))
    (ttl/write-triples! sw (:subject (meta entity)) entity)
    (str sw)))

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
  (def dataset (:dataset @dk.cst.dannet.web.resources/db))
  (export-rdf-model! "export/rdf/dannet.zip" (get-model dataset prefix/dn-uri)
                     :prefixes (export-prefixes 'dn))
  (export-rdf-model! "export/rdf/dds.zip" (get-model dataset prefix/dds-uri)
                     :prefixes (export-prefixes 'dds))
  (export-rdf-model! "export/rdf/cor.zip" (get-model dataset prefix/cor-uri)
                     :prefixes (export-prefixes 'cor))
  (export-rdf-model! "export/rdf/oewn-extension.zip"
                     (get-model dataset prefix/oewn-extension-uri)
                     :prefixes (get prefix/oewn-extension :export))

  ;; Export the entire dataset as RDF
  (export-rdf! dannet)
  (export-rdf! @dk.cst.dannet.web.resources/db)

  (export-rdf! @dk.cst.dannet.web.resources/db "export/rdf/" :complete true)

  ;; Test CSV table data
  (let [g (get-graph dataset prefix/dn-uri)]
    (->> (q/table-query g '[?synset ?definition ?ontotype ?sense] op/csv-synsets)
         (map csv-row)
         (take 10)))

  ;; Export DanNet as CSV
  (export-csv! dannet)
  (export-csv! @dk.cst.dannet.web.resources/db)

  ;; Querying DanNet for various synonyms
  (synonyms graph "vand")
  (synonyms graph "sild")
  (synonyms graph "hoved")
  (synonyms graph "bil")
  (synonyms graph "ord")

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
  (q/run graph op/examples '{?sense :dn/sense-21011111})

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
