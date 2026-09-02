(ns dk.cst.dannet.web.instance
  "The live DanNet database and the structures derived from it.

  Everything here is a process-wide singleton realised lazily on first deref."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.core.memoize :as memo]
            [cognitect.transit :as transit]
            [com.owoga.trie :as trie]
            [com.owoga.tightly-packed-trie :as tpt]
            [com.owoga.tightly-packed-trie.encoding :as encoding]
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
            [dk.cst.dannet.web.hyponymy :as hyponymy])
  (:import [com.owoga.tightly_packed_trie TightlyPackedTrie]
           [java.io File]
           [java.nio ByteBuffer]))

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
                  ;; critical path; logs a summary via Telemere. Skipped without
                  ;; an input dir, i.e. under --no-bootstrap: the check walks the
                  ;; entire base graph, which on a memory-constrained host evicts
                  ;; the page cache the service needs to serve requests.
                  (when (:input-dir opts)
                    (future
                      (try
                        (shapes/validate-db dannet)
                        (catch Exception e
                          (tel/error! {:id :dannet.shapes/validate-error} e)))))
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

(defn- cache-dir
  "Directory of the on-disk cache of derived structures, next to the database."
  []
  (io/file (.getParentFile (io/file (:db-path @dannet-opts))) "cache"))

(defn- cache-file
  "The on-disk cache file of the derived structure `nm`."
  [nm]
  (io/file (cache-dir) (str nm ".transit")))

(defn- read-cache
  "The derived structure data cached in `file`; nil when there is none or it
  can't be read."
  [^File file]
  (when (.exists file)
    (tel/trace! {:id      :dannet.graph/read-cache
                 :run-val :elided
                 :data    {:file (str file)}}
                (try
                  (with-open [in (io/input-stream file)]
                    (transit/read (transit/reader in :msgpack)))
                  (catch Exception e
                    (tel/error! {:id :dannet.graph/cache-read-error} e)
                    nil)))))

(defn- write-cache!
  "Cache the derived structure data `x` in `file` as transit msgpack."
  [^File file x]
  (io/make-parents file)
  (with-open [out (io/output-stream file)]
    (transit/write (transit/writer out :msgpack) x)))

(defn- clear-cache!
  "Delete the on-disk cache, e.g. when the database it derives from changes."
  []
  (run! io/delete-file (.listFiles (cache-dir))))

(defn- ->derived
  "Delay of the derived structure `nm`: read from the on-disk cache when one
  exists, otherwise built with `build` and written to the cache. `attach` adds
  the uncached parts (memoised fns)."
  [nm build attach]
  (delay
    (attach (or (read-cache (cache-file nm))
                (let [x (build)]
                  (write-cache! (cache-file nm) x)
                  x)))))

(defn- build-hypernym-graph
  "Build the cached part of `hypernym-graph` from the base graph."
  []
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
                 :orthogonal-only (:orthogonal-only (meta hg))})))

(defn- attach-ancestor-distances
  "Add `:ad` to hypernym graph data `m`, memoised so scoring one synset against
  many reuses each distance map."
  [{:keys [graph] :as m}]
  (assoc m :ad (memo/lru (fn [s] (sim/ancestor-distances graph s))
                         :lru/threshold 10000)))

(defn- ->hypernym-graph
  []
  (->derived "hypernym-graph" build-hypernym-graph attach-ancestor-distances))

(defonce hypernym-graph (->hypernym-graph))

;; Expose the similarity metrics as dnf:path / dnf:lch / dnf:wup SPARQL
;; functions. The graph is derefed lazily, on the first call.
(sim/register! (fn [] @hypernym-graph))

(defn- build-hyponym-graph
  "Build the cached part of `hyponym-graph` by inverting `hypernym-graph`."
  []
  (tel/trace! {:id :dannet.graph/hyponym-graph :run-val :elided}
              (let [{:keys [graph orthogonal-only]} @hypernym-graph]
                {:graph           (sim/build-hyponym-graph graph)
                 :orthogonal-only orthogonal-only})))

(defn- attach-descendant-count
  "Add `:descendant-count` to hyponym or meronym graph data `m`, memoised so
  repeated branch-size look-ups during a subtree build (and across requests)
  don't re-walk the same descendants."
  [{:keys [graph] :as m}]
  (assoc m :descendant-count
         (memo/lru (fn [s] (hyponymy/hyponym-descendant-count graph s))
                   :lru/threshold 10000)))

(defn- ->hyponym-graph
  []
  (->derived "hyponym-graph" build-hyponym-graph attach-descendant-count))

(defonce hyponym-graph (->hyponym-graph))

(defn- build-meronym-graph
  "Build the cached part of `meronym-graph` from the base graph.

  Same shape as `hyponym-graph` so `hyponymy/hyponym-tree` can build meronym
  sunburst trees over it unchanged; no meronym is orthogonal-only."
  []
  (tel/trace! {:id :dannet.graph/meronym-graph :run-val :elided}
              (let [bg (.getGraph (:base-model @db))]
                {:graph           (sim/build-meronym-graph bg)
                 :orthogonal-only #{}})))

(defn- ->meronym-graph
  []
  (->derived "meronym-graph" build-meronym-graph attach-descendant-count))

(defonce meronym-graph (->meronym-graph))

(defn- add-word-entry
  "Register the DanNet written representation `w` as a search word in `m`."
  [m w]
  (update m (shared/search-string w) merge {:s w :word? true}))

(defn- add-form-entry
  "Register the COR `[lemma rep]` inflection pair in `m`. Inflections identical
  to their lemma and compound stems (e.g. \"plejehjems-\") are skipped."
  [m [lemma rep]]
  (let [k (shared/search-string rep)]
    (if (or (= k (shared/search-string lemma))
            (str/ends-with? rep "-"))
      m
      (-> m
          (update-in [k :s] #(or % rep))
          (update-in [k :lemmas] (fnil conj #{}) lemma)))))

(defn- entry-id->bytes
  "Encode search trie `entry-id` as bytes; nil (an intermediate node) as 0."
  [entry-id]
  (encoding/encode (or entry-id 0)))

(defn- bytes->entry-id
  "Decode a search trie entry id from `bb`; nil for an intermediate node."
  [^ByteBuffer bb]
  (let [entry-id (encoding/decode bb)]
    (when-not (zero? entry-id) entry-id)))

(defn- pack-search-trie
  "Pack `entries` ({search-string entry}) into the bytes of a tightly packed
  trie keyed by char code, whose values index the returned `:entries` vector."
  [entries]
  (let [ks      (vec (keys entries))
        entries (into [nil] (map entries) ks)
        trie    (tpt/tightly-packed-trie
                  (apply trie/make-trie (mapcat (fn [id k] [(mapv int k) id])
                                                (iterate inc 1)
                                                ks))
                  entry-id->bytes
                  bytes->entry-id)]
    {:entries entries
     :trie    (.array ^ByteBuffer (.byte-buffer ^TightlyPackedTrie trie))}))

(defn- build-search-trie
  "Build the cached part of `search-trie`: the packed trie and its entries for
  every DanNet written representation and COR word form."
  []
  (let [dataset (:dataset @db)
        words   (->> (q/run (db/get-graph dataset prefix/dn-uri)
                            '[?writtenRep] op/written-representations)
                     (map (comp str first)))
        forms   (->> (q/run (db/get-graph dataset prefix/cor-uri)
                            op/cor-word-forms)
                     (map (juxt (comp str '?lemma) (comp str '?rep))))
        entries (reduce add-form-entry
                        (reduce add-word-entry {} words)
                        forms)]
    (tel/trace! {:id :dannet.graph/search-trie :run-val :elided}
                (pack-search-trie entries))))

(defn- ->search-trie
  []
  (->derived "search-trie" build-search-trie
             #(update % :trie tpt/load-tightly-packed-trie-from-file
                      bytes->entry-id)))

(defonce search-trie (->search-trie))

(def derived
  "The structures cached on disk (see `->derived`) and their constructors, in
  build order: `hyponym-graph` derives from `hypernym-graph`."
  [[#'hypernym-graph ->hypernym-graph]
   [#'hyponym-graph ->hyponym-graph]
   [#'meronym-graph ->meronym-graph]
   [#'search-trie ->search-trie]])

(defn- completion-items
  "Convert a search trie entry value `v` into autocompletion items: a string
  for a word match and `[lemma rep]` pairs for inflected form matches."
  [{:keys [s word? lemmas] :as v}]
  (cond-> (mapv (fn [lemma] [lemma s]) lemmas)
    word? (conj s)))

(defn- completion-sort-key
  "Sort key for autocompletion `item`: by the matched string, with word
  matches preceding inflected form matches."
  [item]
  (if (string? item)
    [(str/lower-case item) 0 item]
    [(str/lower-case (second item)) 1 (first item)]))

(defn- ->autocomplete
  []
  (memo/lu
    (fn [s]
      (let [{:keys [trie entries]} @search-trie]
        (->> (locking trie                                  ; shared byte buffer
               (into [] (keep second) (trie/lookup trie (map int s))))
             (map entries)
             (mapcat completion-items)
             (sort-by completion-sort-key)
             (take 200))))                                  ; cap the payload
    :lu/threshold 2000))

(defonce ^{:doc "Return auto-completions for `s` found in the graph,
capped at 200 items."}
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

(defn warm-entity-cache!
  "Pre-compute the expanded entities of the `n` synsets with most hyponyms.

  Their entity look-ups are by far the slowest (multiple seconds when cold),
  so warming the LRU cache at boot spares the first visitor of each page."
  [n]
  (let [g (:graph @db)]
    (tel/trace! {:id      :dannet.graph/warm-entity-cache
                 :run-val :elided
                 :data    {:n n}}
                (doseq [[synset _] (->> (:graph @hyponym-graph)
                                        (sort-by (comp count val) >)
                                        (take n))]
                  (q/expanded-entity g synset)))))

(defn warm-up!
  "Realise the derived structures from the on-disk cache, then rebuild each
  from the database, replacing the cached one and refreshing its cache."
  []
  (let [cached? (.exists (cache-dir))]
    (doseq [[v] derived]
      @@v)
    (when cached?
      (try
        (clear-cache!)
        (doseq [[v build] derived]
          (alter-var-root v (constantly (doto (build) deref))))
        (memo/memo-clear! autocomplete)
        (catch Exception e
          (tel/error! {:id :dannet.graph/warm-up-error} e))))))

(defn reset-indices!
  "Replace every structure derived from the db with a freshly built one.

  Needed whenever the db underneath them is replaced; a realised delay would
  otherwise keep serving structures derived from the previous database."
  []
  (clear-cache!)
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
