(ns dk.cst.dannet.entity
  "Display-only reduction of entity data: de-duplication of relations entailed
  by others and truncation of the large ones. Never affects negotiated RDF."
  ;; TODO: the web.section dependency is the only thing tying this namespace to
  ;;       web; resolve if section is ever split.
  (:require [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.web.section :as section]))

(defn prune-entailed-superproperties
  "Remove relations from `entity` whose values are fully covered by present
  subproperty relations, e.g. skos:broader rows entailed from wn:hypernym.

  Returns a map with:
    :entity - entity without the fully covered superproperty relations
    :folded - {surviving relation -> set of removed superproperties}"
  [k->supers entity]
  (let [->set   (fn [v] (cond
                          (set? v) v
                          (coll? v) (set v)
                          :else #{v}))
        subs-of (reduce (fn [m rel]
                          (reduce (fn [m super]
                                    (if (contains? entity super)
                                      (update m super (fnil conj #{}) rel)
                                      m))
                                  m
                                  (k->supers rel)))
                        {}
                        (keys entity))
        pruned  (set (for [[super subs] subs-of
                           :let [covered (reduce #(into %1 (->set (entity %2)))
                                                 #{}
                                                 subs)
                                 super-v (entity super)]
                           :when (if (coll? super-v)
                                   (every? covered super-v)
                                   (contains? covered super-v))]
                       super))
        folded  (reduce (fn [m super]
                          (reduce (fn [m rel]
                                    (if (pruned rel)
                                      m
                                      (update m rel (fnil conj #{}) super)))
                                  m
                                  (subs-of super)))
                        {}
                        pruned)]
    {:entity (apply dissoc entity pruned)
     :folded folded}))

(defn prune-embedded-entities
  "Apply 'prune-entailed-superproperties' to the blank node entity maps
  embedded as metadata on symbols using `k->supers` within `entity` values.

  Each pruned inner entity retains its own :folded map as metadata, which is
  read by the frontend when rendering nested attr-val tables (see issue #195)."
  [k->supers entity]
  (let [prune-sym (fn [x]
                    (if-let [m (and (symbol? x) (not-empty (meta x)))]
                      (let [{inner  :entity
                             folded :folded} (prune-entailed-superproperties
                                               k->supers m)]
                        (if (not-empty folded)
                          (with-meta x (with-meta inner {:folded folded}))
                          x))
                      x))
        prune-val (fn [v]
                    (cond
                      (symbol? v) (prune-sym v)

                      ;; Values without blank nodes -- e.g. large collections
                      ;; of synset relations -- are returned as-is rather than
                      ;; being needlessly rebuilt.
                      (not (and (coll? v) (some symbol? v))) v

                      (set? v) (into (empty v) (map prune-sym) v)
                      :else (with-meta (mapv prune-sym v) (meta v))))]
    (update-vals entity prune-val)))

(defn truncate-semantic-relations
  "Truncate semantic relation values in `entity`.

  Returns a map with:
    :truncated    - entity with values capped at the limit
    :deferred     - entity containing only the overflow values
    :has-deferred - true if any relation exceeded the limit"
  [entity]
  (let [has-deferred? (volatile! false)]
    (loop [[[k v] & more] (seq entity)
           truncated (transient {})
           deferred  (transient {})]
      (if (nil? k)
        {:truncated    (persistent! truncated)
         :deferred     (persistent! deferred)
         :has-deferred @has-deferred?}
        (if (and (section/semantic-rels? [k])
                 (coll? v)
                 (> (count v) shared/semantic-relation-limit))
          ;; Use subvec for O(1) splitting when possible, avoiding full traversal.
          (let [v'    (if (vector? v) v (vec v))
                trunc (subvec v' 0 shared/semantic-relation-limit)
                defer (subvec v' shared/semantic-relation-limit)]
            (vreset! has-deferred? true)
            (recur more
                   (assoc! truncated k trunc)
                   (assoc! deferred k defer)))
          (recur more
                 (assoc! truncated k v)
                 deferred))))))
