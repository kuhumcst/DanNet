(ns dk.cst.dannet.similarity
  "Synset similarity metrics over the DanNet hypernym taxonomy.

  Ports the taxonomy-based measures from the `wn` Python tool
  (https://wn.readthedocs.io/en/latest/api/wn.similarity.html): `path`,
  `lch` (Leacock-Chodorow), and `wup` (Wu-Palmer). These need only the
  hypernym structure and work on the data we already have.

  Path-finding runs over a precomputed hypernym graph `hg`, a
  {child #{parents}} map built once from the base (asserted) graph by
  `build-hypernym-graph` and held by the caller (see
  `dk.cst.dannet.web.resources/hypernym-graph`). This is the single source
  of truth for hypernymy, replacing per-synset `q/entity` look-ups.

  Each measure comes in two flavours: a convenience `[hg a b]` arity, and a
  `*`-suffixed core that takes precomputed `ancestor-distances` maps `da`/`db`
  directly. Callers scoring one synset against many (e.g. the SPARQL functions)
  reuse a cached `da` for the constant and compute each `db` once via the core.

  Conventions follow `wn`:
   - `p` is an EDGE count, i.e. (count path-nodes) - 1
   - the taxonomy is a DAG (multiple inheritance), so a synset may have
     several hypernym paths to several roots; it is cleanly partitioned by
     language (i.e. no DanNet-OEWN hypernym edges)
   - disconnected synsets yield no path; `wn`'s `simulate_root` option
     (a virtual root joining all roots) is not yet implemented."
  (:require [clojure.string :as str]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.db.query :as q]
            [dk.cst.dannet.db.query.function :as function]))

;; TODO: ~27 hypernym edges are asserted only in the :wn/hyponym direction;
;;       folding in inverted hyponyms would recover them, but interacts with the
;;       orthogonal fallback (a recovered hypernym suppresses the stand-in), so
;;       it is left out for now.
(defn build-hypernym-graph
  "Build the {child #{parents}} hypernym adjacency map from base graph `g`.

  Uses asserted `:wn/hypernym`, falling back per-node to `:dns/orthogonalHypernym`
  when a synset has no proper hypernym (matching `q/hypernym-ancestry*`). Reads
  only asserted triples, so `g` should be the base model (the inferred hyponym
  relations aren't needed when we only traverse upwards)."
  [g]
  (let [adjacency (fn [pred]
                    ;; {child #{parents}} for a single predicate over `g`
                    (reduce (fn [m b]
                              (update m (get b '?s) (fnil conj #{}) (get b '?o)))
                            {}
                            (q/run g [:bgp ['?s pred '?o]])))
        H         (adjacency :wn/hypernym)
        O         (adjacency :dns/orthogonalHypernym)]
    (persistent!
      (reduce (fn [m c]
                ;; a real hypernym wins; orthogonal is only a stand-in
                (assoc! m c (if (seq (get H c))
                              (get H c)
                              (get O c #{}))))
              (transient {})
              (into #{} (mapcat keys) [H O])))))

;; DanNet stores PoS in :wn/lexfile; OEWN synsets store it in :wn/partOfSpeech.
(def ^:private wn-pos->pos
  {:wn/noun                "noun"
   :wn/verb                "verb"
   :wn/adjective           "adj"
   :wn/adjective_satellite "adj"
   :wn/adverb              "adv"})

(defn synset->pos
  "Map of {synset pos-string} for `g`, normalised to noun/verb/...

  DanNet synsets derive POS from the :wn/lexfile prefix (e.g. \"noun.person\");
  OEWN synsets have no lexfile and instead use :wn/partOfSpeech (e.g. :wn/noun).
  Both schemes are read; the type constraint keeps the partOfSpeech query to
  synsets, not words."
  [g]
  (merge
    (into {}
          (map (fn [b] [(get b '?s)
                        (first (str/split (str (get b '?lf)) #"\."))]))
          (q/run g '[:bgp [?s :wn/lexfile ?lf]]))
    (into {}
          (keep (fn [b] (when-let [pos (wn-pos->pos (get b '?p))]
                          [(get b '?s) pos])))
          (q/run g '[:bgp
                     [?s :rdf/type :ontolex/LexicalConcept]
                     [?s :wn/partOfSpeech ?p]]))))

(defn max-depth
  "Length of the longest hypernym path from `synset` up to a root in `hg`.

  This is `wn`'s `max_depth`: the *longest* climb to a root, not the shortest
  (`wn` distinguishes the two, and they diverge under multiple inheritance).
  Both Wu-Palmer and the per-language taxonomy depths measure depth this way.
  The walk is cycle-safe -- `seen` tracks the current path, since the data
  contains self-referential and cyclic hypernyms (cf. `self-referential-hypernyms`)."
  [hg synset]
  (letfn [(depth [seen s]
            (let [parents (remove seen (get hg s))]
              (if (empty? parents)
                0
                (inc (apply max (map #(depth (conj seen s) %) parents))))))]
    (depth #{} synset)))

(defn node-depths
  "Map of {synset max-depth} for every synset that has a hypernym in `hg` (see
  `max-depth`). Roots have none and are absent -- treat a missing depth as 0.

  Precompute once and reuse: both `taxonomy-depths` and `wup-similarity*` take
  this in place of a live `max-depth` walk, so the depths are computed exactly
  once over the whole graph."
  [hg]
  (persistent!
    (reduce (fn [m s] (assoc! m s (max-depth hg s)))
            (transient {})
            (keys hg))))

(defn taxonomy-depths
  "Nested map {prefix {pos max-depth}} from a {synset depth} map `nd` (see
  `node-depths`), where `pos` is looked up in the {synset pos} map
  `pos-by-synset` and prefix is the synset's namespace (\"dn\", \"en\").

  Scoping by namespace keeps the deep English taxonomy from inflating the
  Danish one. Feeds `lch-similarity`'s `d`."
  [nd pos-by-synset]
  (reduce (fn [m s]
            (update-in m [(namespace s) (pos-by-synset s "?")]
                       (fnil max 0) (nd s)))
          {} (keys nd)))

(defn synset-hypernyms
  "Direct hypernyms of `synset` in hypernym graph `hg` as a set."
  [hg synset]
  (get hg synset #{}))

;; A distance table (keyed by ancestor), not a path: the path/LCS functions
;; intersect two of these and look up distances, so they need it keyed. The
;; actual connecting route isn't recoverable from this -- add parent links if
;; a "how does A relate to B" feature ever needs it.
(defn ancestor-distances
  "Map of {ancestor edge-distance} for `synset` in `hg`, including `synset`
  itself at distance 0.

  Breadth-first walk up the hypernyms; the first (shortest) distance to each
  ancestor wins. Cycle-safe, since the data contains self-referential and
  cyclic hypernyms (cf. `self-referential-hypernyms`)."
  [hg synset]
  (loop [frontier #{synset}
         dist     {synset 0}
         d        0]
    (let [d+1  (inc d)
          nbrs (into #{}
                     (comp (mapcat #(synset-hypernyms hg %))
                           (remove dist))                   ; skip already-seen
                     frontier)]
      (if (empty? nbrs)
        dist
        (recur nbrs
               (into dist (map (fn [n] [n d+1])) nbrs)
               d+1)))))

(defn- common-ancestors
  "Ancestors shared by the `ancestor-distances` maps `da` and `db`. The `db`
  map doubles as a membership predicate over `da`'s keys."
  [da db]
  (filter db (keys da)))

;; TODO: support wn's simulate_root option -- a virtual root above all roots,
;; so any two synsets (incl. cross-tree / cross-POS) always share an ancestor.
(defn shortest-path-length*
  "Edge length of the shortest hypernym path between two synsets given their
  precomputed `ancestor-distances` maps `da` and `db`, or nil when they share
  no ancestor.

  The path runs up from each synset to a common ancestor, so the length is
  the smallest (dist-a + dist-b) over shared ancestors."
  [da db]
  (let [common (common-ancestors da db)]
    (when (seq common)
      ;; the shortest a->b route climbs to a shared ancestor and back down
      (apply min (map #(+ (da %) (db %)) common)))))

(defn shortest-path-length
  "Edge length of the shortest hypernym path between `a` and `b` in `hg`,
  or nil when they share no ancestor."
  [hg a b]
  (shortest-path-length* (ancestor-distances hg a) (ancestor-distances hg b)))

(defn lowest-common-subsumers*
  "Set of common ancestors minimizing combined path distance (the least common
  subsumers) given the `ancestor-distances` maps `da` and `db`, or nil when
  none exist.

  Usually a single synset, but multiple inheritance can yield several."
  [da db]
  (let [common (common-ancestors da db)]
    (when (seq common)
      (let [scored (map (fn [c] [c (+ (da c) (db c))]) common)
            best   (apply min (map second scored))]
        (into #{} (comp (filter #(= (second %) best)) (map first)) scored)))))

(defn lowest-common-subsumers
  "Least common subsumers of `a` and `b` in `hg`, or nil when none exist."
  [hg a b]
  (lowest-common-subsumers* (ancestor-distances hg a) (ancestor-distances hg b)))

(defn path-similarity*
  "Path similarity from precomputed `da`/`db`, i.e. 1/(p+1) for shortest path
  length `p`.

  Ranges over (0.0, 1.0]: 1.0 for a synset against itself, 0.0 when the two
  are disconnected."
  [da db]
  (if-let [p (shortest-path-length* da db)]
    ;; +1 so identical synsets (p=0) score 1.0, and never divide by zero
    (/ 1.0 (inc p))
    0.0))

(defn path-similarity
  "Path similarity of `a` and `b` in `hg` (see `path-similarity*`)."
  [hg a b]
  (path-similarity* (ancestor-distances hg a) (ancestor-distances hg b)))

(defn lch-similarity*
  "Leacock-Chodorow similarity from precomputed `da`/`db`: -log((p+1)/2d) for
  shortest path length `p`, where `d` is the taxonomy depth for their language
  and part of speech (see `taxonomy-depths`). Returns nil when disconnected."
  [da db d]
  (when-let [p (shortest-path-length* da db)]
    ;; -log of the normalised path length: closer synsets score higher
    (- (Math/log (/ (inc p) (* 2.0 d))))))

(defn lch-similarity
  "Leacock-Chodorow similarity of `a` and `b` in `hg`, where `d` is the
  taxonomy depth for their language and part of speech (see `taxonomy-depths`).

  Pass `d` explicitly (as `wn` does). Returns nil when `a` and `b` are
  disconnected."
  [hg a b d]
  (lch-similarity* (ancestor-distances hg a) (ancestor-distances hg b) d))

(defn wup-similarity*
  "Wu-Palmer similarity from precomputed `da`/`db`, i.e. 2k/(i+j+2k); `depth`
  maps a synset to its `max-depth` (e.g. a precomputed `node-depths` map).

  The LCS is the least common subsumer; `i`/`j` are the shortest edge distances
  from the two synsets to it, and `k` is the node count from the LCS to its
  furthest root. Returns nil when disconnected."
  [da db depth]
  (when-let [lcs (first (lowest-common-subsumers* da db))]
    (let [i (da lcs)                                        ; shortest a -> LCS
          j (db lcs)                                        ; shortest b -> LCS
          ;; wn measures k by the LCS's *longest* climb to a root (max_depth),
          ;; not its shortest -- the two differ under multiple inheritance.
          k (inc (depth lcs))]
      (/ (* 2.0 k) (+ i j (* 2 k))))))

(defn wup-similarity
  "Wu-Palmer similarity of `a` and `b` in `hg` (see `wup-similarity*`).
  Returns nil when `a` and `b` are disconnected."
  [hg a b]
  (wup-similarity* (ancestor-distances hg a) (ancestor-distances hg b)
                   #(max-depth hg %)))

;; NOTE: the information-content measures (`res`, `jcn`, `lin`) are stubbed out
;;       below. They depend on per-synset IC weights, and whether our structural
;;       weights are good enough is still an open question. They are deferred
;;       until the IC source has been evaluated.

;; TODO: intrinsic IC à la Seco et al. 2004: IC(c) = 1 - log(hypo(c)+1)/log(N),
;;       where hypo(c) is the descendant count and N the total synset count.
;;       Purely structural, no corpus required. Evaluate its quality before
;;       wiring res/jcn/lin.
(defn intrinsic-ic
  "Map of {synset information-content} for `hg` via intrinsic (structural) IC."
  [hg]
  (throw (ex-info "not implemented" {})))

;; TODO: res = max IC over common subsumers (i.e. IC of the highest-IC LCS).
(defn res-similarity
  "Resnik similarity of `a` and `b` in `hg` given IC weights `ic`."
  [hg ic a b]
  (throw (ex-info "not implemented" {})))

;; TODO: jcn = 1 / (IC(a) + IC(b) - 2*IC(lcs)); special-case 0 and infinity.
(defn jcn-similarity
  "Jiang-Conrath similarity of `a` and `b` in `hg` given IC weights `ic`."
  [hg ic a b]
  (throw (ex-info "not implemented" {})))

;; TODO: lin = 2*IC(lcs) / (IC(a) + IC(b)). NB: the wn HTML docs misprint the
;;       denominator as IC(a)+IC(lcs); the canonical/source form uses IC(a)+IC(b).
(defn lin-similarity
  "Lin similarity of `a` and `b` in `hg` given IC weights `ic`."
  [hg ic a b]
  (throw (ex-info "not implemented" {})))

;; SPARQL exposure: each scorer takes [ctx a b] and returns a double or nil
;; (nil -> unbound). Registered under the `dnf:` namespace, reading the
;; precomputed similarity context (see `register!`) rather than the Jena model,
;; so they're independent of the query's inference mode. `path` scores
;; disconnected pairs as 0.0 (as `wn` does); `lch`/`wup` leave them unbound.
;;
;; `lch` resolves its taxonomy depth from the first synset's language and part
;; of speech; cross-language / cross-POS pairs share no taxonomy depth (and no
;; path) and so come back unbound until `simulate_root` is implemented.
(def ^:private scorers
  {"path" (fn [{:keys [ad]} a b]
            (path-similarity* (ad a) (ad b)))
   "wup"  (fn [{:keys [ad node-depths]} a b]
            ;; roots aren't keys in the graph, so they're absent from
            ;; node-depths; their max-depth is 0
            (wup-similarity* (ad a) (ad b) #(node-depths % 0)))
   "lch"  (fn [{:keys [ad pos taxonomy]} a b]
            (when-let [d (get-in taxonomy [(namespace a) (pos a "?")])]
              (lch-similarity* (ad a) (ad b) d)))})

(defn register!
  "Register the similarity metrics (`path`, `lch`, `wup`) as ARQ SPARQL
  functions under the `dnf:` namespace, so any query may score synset pairs:

      SELECT ?s (dnf:wup(<https://wordnet.dk/dannet/data/synset-54219>, ?s) AS ?score)
      WHERE { ?s a ontolex:LexicalConcept }

  `ctx-fn` is a 0-arg fn returning the similarity context map (hypernym graph,
  per-node depths, taxonomy depths and a memoized `ancestor-distances`; see
  `dk.cst.dannet.web.resources/hypernym-graph`); it is derefed lazily, on first
  use."
  [ctx-fn]
  (function/register-functions!
    'dnf prefix/dnf-uri
    (update-vals scorers
                 (fn [score]
                   (function/->function ctx-fn function/node->keyword score)))))

(comment
  ;; Build the graph from the base (asserted) model; see
  ;; dk.cst.dannet.web.resources/hypernym-graph for the boot-time delay.
  (require '[dk.cst.dannet.web.resources :as res])
  (def bg (.getGraph (:base-model @res/db)))
  (def hg (build-hypernym-graph bg))                        ; ~1.9s, ~154k entries

  ;; Known chain: synset-54219 -> 5619 -> 2355 -> 20633 (root).
  (ancestor-distances hg :dn/synset-54219)
  ;; => {:dn/synset-54219 0, :dn/synset-5619 1, :dn/synset-2355 2, :dn/synset-20633 3}

  ;; 54219 and 56495 are siblings (both hyponyms of 5619).
  (shortest-path-length hg :dn/synset-54219 :dn/synset-56495) ; => 2
  (lowest-common-subsumers hg :dn/synset-54219 :dn/synset-56495) ; => #{:dn/synset-5619}

  ;; Taxonomy metrics, checked against hand-computed values.
  (path-similarity hg :dn/synset-54219 :dn/synset-56495)    ; => 1/3
  (wup-similarity hg :dn/synset-54219 :dn/synset-56495)     ; => 0.75
  (wup-similarity hg :dn/synset-54219 :dn/synset-54219)     ; => 1.0

  ;; Per-language, per-POS depths (for lch). ~1.4s over the whole map.
  (def depths (taxonomy-depths (node-depths hg) (synset->pos bg)))
  depths                                                    ; => {"dn" {"noun" 13, "verb" 10, "adj" 9, "?" 4}, "en" {"noun" 19, "verb" 17}}

  ;; lch with the right Danish noun depth.
  (lch-similarity hg :dn/synset-54219 :dn/synset-56495
                  (get-in depths ["dn" "noun"]))            ; => ~2.16

  #_.)
