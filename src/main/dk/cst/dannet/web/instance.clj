(ns dk.cst.dannet.web.instance
  "The live DanNet database and the structures derived from it.

  Everything here is a process-wide singleton realised lazily on first deref."
  (:require [clojure.string :as str]
            [clojure.core.memoize :as memo]
            [com.owoga.trie :as trie]
            [taoensso.telemere :as tel]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.release :as release]
            [dk.cst.dannet.similarity :as sim]
            [dk.cst.dannet.db :as db]
            [dk.cst.dannet.db.bootstrap :as bootstrap]
            [dk.cst.dannet.db.shapes :as shapes]
            [dk.cst.dannet.db.query :as q]
            [dk.cst.dannet.db.query.operation :as op]
            [dk.cst.dannet.web.section :as section]
            [dk.cst.dannet.web.sparql :as sparql]
            [dk.cst.dannet.web.hyponymy :as hyponymy]))

(def dannet-opts
  (atom {:db-type     :tdb2
         :db-path     "db/tdb2"
         :input-dir   (release/version-dir release/from)
         :schema-uris prefix/schema-uris}))

;; build-db! is a named fn (not inlined in the delay) so reset-db! can swap in a
;; fresh delay to force a rebuild -- a realised delay otherwise caches forever.
(defn build-db!
  "Realise the DanNet database from the current dannet-opts. With `refetch?`,
  wipe the stale/version-bound datasets and re-fetch the required versions
  first."
  [refetch?]
  (let [opts (assoc @dannet-opts :refetch? refetch?)]
    (sparql/reset-cache!)
    (tel/trace! {:id      :dannet.graph/build-db
                 :run-val :elided
                 :data    {:opts opts}}
                (let [dannet (bootstrap/->dannet opts)]
                  ;; Non-fatal SHACL check, run async to stay off the boot
                  ;; critical path; logs a summary via Telemere. Runs on every
                  ;; boot (fresh builds *and* reused databases) by design.
                  (future
                    (try
                      (shapes/validate-db dannet)
                      (catch Exception e
                        (tel/error! {:id :dannet.shapes/validate-error} e))))
                  dannet))))

(defonce db
  (delay (build-db! false)))

;; Map of property -> set of strict superproperties, derived from the
;; rdfs:subPropertyOf closure entailed by the inference model (see rdfs5 in
;; 'dannet.rules'). Cyclic pairs (equivalent properties) are excluded.
(defn- ->superproperty-closure
  []
  (delay
    (let [pairs (->> (q/run (:graph @db) '[:bgp [?p :rdfs/subPropertyOf ?q]])
                     (map (juxt '?p '?q))
                     (filter (fn [[p q]]
                               (and (keyword? p) (keyword? q) (not= p q))))
                     (set))]
      (->> pairs
           (remove (fn [[p q]] (contains? pairs [q p])))
           (reduce (fn [m [p q]] (update m p (fnil conj #{}) q)) {})))))

(defonce superproperty-closure (->superproperty-closure))

(defn- ->hypernym-graph
  []
  (delay
    (tel/trace! {:id :dannet.graph/hypernym-graph :run-val :elided}
                (let [bg  (.getGraph (:base-model @db))
                      hg  (sim/build-hypernym-graph bg)
                      nd  (sim/node-depths hg)
                      pos (sim/synset->pos bg)]
                  {:graph           hg
                   :node-depths     nd
                   :pos             pos
                   :taxonomy        (sim/taxonomy-depths nd pos)
                   ;; children with no real hypernym; see sim/build-hypernym-graph
                   :orthogonal-only (:orthogonal-only (meta hg))
                   ;; memoized so scoring one synset against many reuses each distance map
                   :ad              (memo/memo (fn [s] (sim/ancestor-distances hg s)))}))))

(defonce hypernym-graph (->hypernym-graph))

;; Expose the similarity metrics as dnf:path / dnf:lch / dnf:wup SPARQL
;; functions. The graph is derefed lazily, on the first call.
(sim/register! (fn [] @hypernym-graph))

(defn- ->hyponym-graph
  []
  (delay
    (tel/trace! {:id :dannet.graph/hyponym-graph :run-val :elided}
                (let [hypo (sim/build-hyponym-graph (:graph @hypernym-graph))]
                  {:graph            hypo
                   ;; memoized so repeated branch-size look-ups during a subtree build
                   ;; (and across requests) don't re-walk the same descendants
                   :descendant-count (memo/memo (fn [s] (hyponymy/hyponym-descendant-count hypo s)))
                   :orthogonal-only  (:orthogonal-only @hypernym-graph)}))))

(defonce hyponym-graph (->hyponym-graph))

(defn- ->meronym-graph
  []
  (delay
    (tel/trace! {:id :dannet.graph/meronym-graph :run-val :elided}
                (let [mero (sim/build-meronym-graph (.getGraph (:base-model @db)))]
                  ;; Same shape as `hyponym-graph` so `hyponymy/hyponym-tree`
                  ;; can build meronym sunburst trees over it unchanged.
                  {:graph            mero
                   :descendant-count (memo/memo (fn [s] (hyponymy/hyponym-descendant-count mero s)))
                   :orthogonal-only  (constantly false)}))))

(defonce meronym-graph (->meronym-graph))

;; TODO: ... include COR writtenRep too? Other labels?
;; TODO: should be transformed into a tightly packed tried (currently loose)
(defn- ->search-trie
  []
  (delay
    (let [g      (db/get-graph (:dataset @db) prefix/dn-uri)
          words  (q/run g '[?writtenRep] op/written-representations)
          lwords (map (partial map shared/search-string) words)]
      (tel/trace! {:id :dannet.graph/search-trie :run-val :elided}
                  (apply trie/make-trie (map str (mapcat concat lwords words)))))))

(defonce search-trie (->search-trie))

(defn- ->autocomplete
  []
  (memo/lu
    (fn [s]
      (->> (trie/lookup @search-trie s)
           (remove (comp nil? second))                      ; remove partial
           (map second)                                     ; grab full words
           (sort-by str/lower-case)))
    :lu/threshold 500))

(defonce ^{:doc "Return auto-completions for `s` found in the graph."}
  autocomplete
  (->autocomplete))

(defn- find-synset-relations
  "Find the synset relations in use in the graph `g`, including the relations
  used to link to other datasets (these are not typed as wn:SynsetRelType and
  therefore probed individually).

  Returns a map of `{rel entity}` where each entity is pruned to the label
  properties and rdfs:comment."
  [g]
  (let [in-use?    (fn [rel] (seq (q/run g (op/relation-usage-query rel))))
        cross-rels (filter in-use? (second section/cross-link-section))
        rels       (map '?rel (q/run g op/synset-relation-types))
        ks         (cons :rdfs/comment shared/label-keys-full)]
    (->> (concat rels cross-rels)
         (distinct)
         (map (fn [rel]
                [rel (select-keys (q/entity g rel) ks)]))
         (into {}))))

(defn- ->synset-rels
  []
  (delay
    (tel/trace! {:id :dannet.graph/synset-relations :run-val :elided}
                (find-synset-relations (:graph @db)))))

(defonce synset-rels (->synset-rels))

(defn reset-indices!
  "Replace every structure derived from the db with a freshly built one.

  Needed whenever the db underneath them is replaced; a realised delay would
  otherwise keep serving structures derived from the previous database."
  []
  (doseq [[v build] {#'superproperty-closure ->superproperty-closure
                     #'hypernym-graph        ->hypernym-graph
                     #'hyponym-graph         ->hyponym-graph
                     #'meronym-graph         ->meronym-graph
                     #'search-trie           ->search-trie
                     #'autocomplete          ->autocomplete
                     #'synset-rels           ->synset-rels}]
    (alter-var-root v (constantly (build)))))

(defn reset-db!
  "Replace the db delay so the next deref rebuilds, with `refetch?` controlling
  whether stale datasets are wiped and re-fetched first. Lets restart-refetch
  force a rebuild even when the db is already built."
  [refetch?]
  (alter-var-root #'db (constantly (delay (build-db! refetch?))))
  (reset-indices!))

(comment
  (autocomplete "sar")
  (autocomplete "spo")
  (autocomplete "tran")

  ;; Rebuild the derived structures without touching the database itself.
  (reset-indices!)

  ;; SHACL-validate the asserted graph against base shapes + baseline.
  ;; Logs a summary; :exceeded should be {} on a healthy system (~1 min).
  (shapes/validate-db @db)

  ;; Same, but keeping the result around for closer inspection.
  (def validation-result (shapes/validate-db @db))
  (shapes/by-shape (:entries validation-result))
  (shapes/by-severity (:entries validation-result))

  ;; SHACL-validate a single synset (cheap) -- e.g. against the editorial
  ;; shapes, the same call that will eventually gate writes.
  (require '[dk.cst.dannet.db.transaction :as txn])
  (txn/transact-read (:dataset @db)
    (shapes/validate-node (.getGraph (:base-model @db))
                          @shapes/editorial-shapes
                          :dn/synset-1522))

  ;; Full inferred-graph validation. EXPENSIVE: materializes inferences.
  (shapes/validate-inferred-db @db)
  #_.)
