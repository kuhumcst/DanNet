(ns dk.cst.dannet.web.hyponymy
  "Bounded, localised hyponym subtree construction for the synset sunburst.

  The inverted (hyponym) graph itself is assembled and cached in
  `dk.cst.dannet.web.resources` (it closes over the live database); these
  functions are pure and take that graph as an argument."
  (:require [clojure.string :as str]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.web.i18n :as i18n]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.db.query :as q]))

(defn hyponym-descendant-count
  "Number of distinct hyponym descendants of `synset` in inverted graph `hypo`
  (cycle-safe). Branches are ranked by this when capping breadth. Stays within
  the wordnet of `synset` (DanNet or OEWN); the graph isn't linked across them."
  [hypo synset]
  (let [ns   (namespace synset)
        seen (atom #{})]
    (letfn [(walk [n]
              (when-not (@seen n)
                (swap! seen conj n)
                (run! walk (filter #(= ns (namespace %)) (get hypo n)))))]
      (walk synset)
      (dec (count @seen)))))

(defn- rank-index
  "Map each item in `xs` to its 0-based rank (0 = best) after sorting by
  `keyfn` in descending order."
  [keyfn xs]
  (into {} (map-indexed (fn [i x] [x i])
                        (sort-by keyfn #(compare %2 %1) xs))))

(defn hyponym-priority
  "Rank hyponyms `xs` best-first by a Borda combination of `count-fn` (subtree
  size) and `indegree-fn` (corpus prominence), so descendant-heavy branches and
  prominent leaves both compete for the `:max-children` cap. Count-rank ties
  break on indegree first, keeping equal-sized branches deterministic."
  [count-fn indegree-fn xs]
  (let [count-rank    (rank-index (fn [x] [(count-fn x) (indegree-fn x)]) xs)
        indegree-rank (rank-index indegree-fn xs)]
    (sort-by #(+ (count-rank %) (indegree-rank %)) xs)))

(defn rank-candidates
  "Rank hyponyms `xs` best-first for the `:max-children` cap, demoting children
  reachable only via `:dns/orthogonalHypernym` (`orthogonal-only`) below every
  regular hyponym. Each group is ordered by `hyponym-priority`."
  [count-fn indegree-fn orthogonal-only xs]
  (let [rank             #(hyponym-priority count-fn indegree-fn %)
        {ortho   true
         regular false} (group-by (comp boolean orthogonal-only) xs)]
    (concat (rank regular) (rank ortho))))

(defn hyponym-subtree
  "Bounded hyponym subtree of `root` over inverted graph `hypo`, as a nested
  {:id :children} skeleton (labels are added separately).

  `root-filter` restricts `root`'s own direct hyponyms (e.g. regular vs
  `orthogonal-only`), letting one subject drive two independent diagrams.
  Children are ranked by `rank-candidates` and capped at `:max-children`,
  recursion stops at `:max-depth`, and `:max-nodes` bounds the total. Multiple
  inheritance is tree-ified; cycles along a path are broken via `path`."
  [hypo count-fn orthogonal-only root-filter root
   & {:keys [max-depth max-children max-nodes]
      :or   {max-depth 5 max-children 12 max-nodes 250}}]
  (let [budget      (atom max-nodes)
        ns          (namespace root)
        indegree-fn #(get @q/synset-indegrees % 0)]
    (letfn [(node [n depth path]
              (swap! budget dec)
              (let [kids (when (and (< depth max-depth) (pos? @budget))
                           (->> (get hypo n)
                                (filter #(= ns (namespace %)))
                                (remove path)
                                (filter (if (zero? depth) root-filter any?))
                                (rank-candidates count-fn indegree-fn orthogonal-only)
                                (take max-children)))]
                (cond-> {:id n}
                  (seq kids) (assoc :children (mapv #(node % (inc depth) (conj path n))
                                                    kids)))))]
      (node root 0 #{root}))))

(defn hyponym-tree
  "Labeled, localised hyponym subtree for synset `root`, ready for the sunburst;
  `root-filter` is forwarded to `hyponym-subtree`.

  Builds the bounded skeleton, batch-resolves labels over `g`, then localises to
  `languages` and trims each to a single clean lemma. Returns {:name :href
  :orthogonal :children}; childless synsets yield just the root so the diagram
  renders a 'no hyponyms' state rather than disappearing."
  [g {:keys [graph descendant-count orthogonal-only]} languages root-filter root & opts]
  (let [skeleton (apply hyponym-subtree graph descendant-count orthogonal-only root-filter root opts)
        ids      (loop [stack [skeleton]
                        acc   (transient #{})]
                   (if-let [n (peek stack)]
                     (recur (into (pop stack) (:children n))
                            (conj! acc (:id n)))
                     (persistent! acc)))
        k->label (let [f (shared/->entity-label-fn :normal)]
                   (update-vals (q/resource-labels g ids) f))
        localise (fn [k]
                   (-> (or (some-> (i18n/select-label languages (k->label k)) str not-empty)
                           (prefix/kw->qname k))
                       (str/replace #"[{}]" "")
                       (str/split #";")
                       first
                       (str/replace #"_[§\d].*$" "")
                       str/trim))]
    (letfn [(relabel [{:keys [id children]}]
              (cond-> {:name       (localise id)
                       :href       (prefix/resolve-href id)
                       :orthogonal (boolean (orthogonal-only id))}
                (seq children) (assoc :children (mapv relabel children))))]
      (relabel skeleton))))
