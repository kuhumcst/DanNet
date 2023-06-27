(ns dk.cst.dannet.db.search
  "Graph database search functionality."
  (:require [clojure.set :as set]
            [flatland.ordered.map :as fop]
            [ont-app.vocabulary.lstr :refer [->LangStr]]
            [dk.cst.dannet.query :as q]
            [dk.cst.dannet.query.operation :as op]))

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
  ;; Look up synsets based on the lemma "have"
  (look-up (:graph @dk.cst.dannet.web.resources/db) "have")
  (label-lookup (:graph @dk.cst.dannet.web.resources/db))
  #_.)
