(ns dk.cst.dannet.entity
  "Display-only reduction of entity data: de-duplication of relations entailed
  by others and truncation of the large ones. Never affects negotiated RDF."
  (:require [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.shared :as shared]))

(defn- ->set
  "Coerce the entity value `v` into a set, wrapping single values."
  [v]
  (cond
    (set? v) v
    (coll? v) (set v)
    :else #{v}))

(defn- entailing-relations
  "Map every superproperty present in `entity` to the relations of `entity`
  that entail it, as determined by the `k->supers` closure.

     (entailing-relations {:wn/hypernym #{:skos/broader}}
                          {:wn/hypernym #{:a} :skos/broader #{:a}})
     ;; => {:skos/broader #{:wn/hypernym}}"
  [k->supers entity]
  (reduce (fn [m rel]
            (reduce (fn [m super]
                      (cond-> m
                        (contains? entity super)
                        (update super (fnil conj #{}) rel)))
                    m
                    (k->supers rel)))
          {}
          (keys entity)))

(defn- entailed?
  "True when every value of `super` in `entity` also occurs among the values of
  the entailing relations `subs`, i.e. when the `super` row displays nothing
  the `subs` rows do not already display."
  [entity super subs]
  (let [covered (into #{} (mapcat (comp ->set entity)) subs)
        super-v (entity super)]
    (if (coll? super-v)
      (every? covered super-v)
      (contains? covered super-v))))

(defn- folded-relations
  "Invert the `subs-of` map into {surviving relation -> set of superproperties}
  for the `pruned` superproperties, skipping relations that were pruned too."
  [subs-of pruned]
  (reduce (fn [m super]
            (reduce (fn [m rel]
                      (cond-> m
                        (not (pruned rel))
                        (update rel (fnil conj #{}) super)))
                    m
                    (subs-of super)))
          {}
          pruned))

(defn prune-entailed-superproperties
  "Remove relations from `entity` whose values are fully covered by present
  subproperty relations according to `k->supers`, e.g. skos:broader rows
  entailed from wn:hypernym.

  Returns a map with:
    :entity - entity without the fully covered superproperty relations
    :folded - {surviving relation -> set of removed superproperties}"
  [k->supers entity]
  (let [subs-of (entailing-relations k->supers entity)
        pruned  (into #{}
                      (filter #(entailed? entity % (subs-of %)))
                      (keys subs-of))]
    {:entity (apply dissoc entity pruned)
     :folded (folded-relations subs-of pruned)}))

(defn- prune-blank-node
  "Prune the entity map carried as metadata by the blank node symbol `x` using
  `k->supers`, returning `x` unchanged when nothing was pruned.

  The pruned entity retains its own :folded map as metadata, which is read by
  the frontend when rendering nested attr-val tables (see issue #195)."
  [k->supers x]
  (if-let [m (and (symbol? x) (not-empty (meta x)))]
    (let [{:keys [entity folded]} (prune-entailed-superproperties k->supers m)]
      (if (not-empty folded)
        (with-meta x (with-meta entity {:folded folded}))
        x))
    x))

(defn- prune-blank-nodes
  "Prune every blank node within the entity value `v` using `k->supers`,
  preserving the type and metadata of `v`."
  [k->supers v]
  (let [prune #(prune-blank-node k->supers %)]
    (cond
      (symbol? v) (prune v)

      ;; Values without blank nodes -- e.g. large collections of synset
      ;; relations -- are returned as-is rather than being needlessly rebuilt.
      (not (and (coll? v) (some symbol? v))) v

      (set? v) (into (empty v) (map prune) v)
      :else (with-meta (mapv prune v) (meta v)))))

(defn prune-embedded-entities
  "Apply 'prune-entailed-superproperties' with `k->supers` to the blank node
  entity maps embedded as metadata on symbols within the values of `entity`."
  [k->supers entity]
  (update-vals entity #(prune-blank-nodes k->supers %)))

(defn displayed-resources
  "Collect the set of resources rendered for `entity`: its relation keys, the
  keyword and RDF-resource values, and the resources found inside blank node
  entity maps attached as metadata on symbols.

  Used to trim the label map sent to the client down to the displayed part."
  [entity]
  (let [xf (comp (mapcat ->set)
                 (mapcat #(if-let [m (and (symbol? %) (meta %))]
                            (concat (keys m) (mapcat ->set (vals m)))
                            [%]))
                 (filter prefix/resource?))]
    (into (set (filter prefix/resource? (keys entity)))
          xf
          (vals entity))))

(defn- split-relation
  "Split the value `v` of the relation `k` at 'semantic-relation-limit' into
  [kept overflow], or return nil when `k` is not an oversized semantic
  relation and so should be displayed in full."
  [k v]
  (when (and (or (shared/semantic-rels? [k])
                 (shared/oversized-rels? k))
             (coll? v)
             (> (count v) shared/semantic-relation-limit))
    ;; Use subvec for O(1) splitting when possible, avoiding full traversal.
    (let [v' (if (vector? v) v (vec v))]
      [(subvec v' 0 shared/semantic-relation-limit)
       (subvec v' shared/semantic-relation-limit)])))

(defn truncate-semantic-relations
  "Truncate the semantic relation values in `entity`.

  Returns a map with:
    :truncated    - entity with values capped at the limit
    :deferred     - entity containing only the overflow values
    :has-deferred - true if any relation exceeded the limit"
  [entity]
  (loop [[[k v] & more] (seq entity)
         truncated (transient {})
         deferred  (transient {})]
    (if (nil? k)
      (let [deferred (persistent! deferred)]
        {:truncated    (persistent! truncated)
         :deferred     deferred
         :has-deferred (boolean (seq deferred))})
      (if-let [[kept overflow] (split-relation k v)]
        (recur more
               (assoc! truncated k kept)
               (assoc! deferred k overflow))
        (recur more
               (assoc! truncated k v)
               deferred)))))

(comment
  ;; skos:broader is fully entailed by wn:hypernym and therefore folded away.
  (prune-entailed-superproperties
    {:wn/hypernym #{:skos/broader}}
    {:wn/hypernym #{:a :b} :skos/broader #{:a :b} :rdfs/label "x"})
  ;; => {:entity {:wn/hypernym #{:a :b}, :rdfs/label "x"}
  ;;     :folded {:wn/hypernym #{:skos/broader}}}

  ;; A superproperty with values of its own survives untouched.
  (prune-entailed-superproperties
    {:wn/hypernym #{:skos/broader}}
    {:wn/hypernym #{:a} :skos/broader #{:a :c}})

  (-> (truncate-semantic-relations {:wn/hyponym (vec (range 200))})
      (update :deferred update-vals count))
  #_.)
