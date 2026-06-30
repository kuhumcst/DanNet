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

(defn hyponym-subtree
  "Bounded hyponym subtree of `root` over inverted graph `hypo`, as a nested
  {:id :children} skeleton (labels are added separately).

  Children are ranked by `count-fn` so the largest branches survive the
  `:max-children` cap, recursion stops at `:max-depth`, and a `:max-nodes`
  budget bounds the total (top concepts can otherwise explode). Multiple
  inheritance is tree-ified — a synset reached through several parents is
  repeated — while cycles along a single path are broken via `path`."
  [hypo count-fn root & {:keys [max-depth max-children max-nodes]
                         :or   {max-depth 5 max-children 12 max-nodes 250}}]
  (let [budget (atom max-nodes)
        ns     (namespace root)]
    (letfn [(node [n depth path]
              (swap! budget dec)
              (let [kids (when (and (< depth max-depth) (pos? @budget))
                           (->> (get hypo n)
                                (filter #(= ns (namespace %)))
                                (remove path)
                                (sort-by count-fn >)
                                (take max-children)))]
                (cond-> {:id n}
                  (seq kids) (assoc :children (mapv #(node % (inc depth) (conj path n))
                                                    kids)))))]
      (node root 0 #{root}))))

(defn hyponym-tree
  "Labeled, localised hyponym subtree for synset `root`, ready for the sunburst.

  Builds the bounded skeleton over the inverted `hypo` graph, collects every
  node id, resolves their labels in one batched query over `g`, then localises
  to `languages` and trims each to a single clean lemma (sunburst arcs read
  better without the full sense notation). Returns {:name :href :children};
  childless synsets yield just the root so the diagram can still render a
  'no hyponyms' state rather than disappearing."
  [g {:keys [graph descendant-count]} languages root & opts]
  (let [skeleton (apply hyponym-subtree graph descendant-count root opts)
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
              (cond-> {:name (localise id)
                       :href (prefix/resolve-href id)}
                (seq children) (assoc :children (mapv relabel children))))]
      (relabel skeleton))))
