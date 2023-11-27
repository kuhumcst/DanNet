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
   '?ontoType   :dns/ontologicalType})

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

(defn look-up
  "Look up synsets in Graph `g` based on the given `lemma`."
  [g lemma]
  (let [k->label (label-lookup g)]
    (->> (q/run g (op/synset-search-query lemma))
         (group-by '?synset)
         (map (fn [[k ms]]
                (let [{:syms [?label ?shortLabel ?synset]
                       :as   base} (apply merge-with q/set-merge ms)
                      subentity (-> base
                                    (dissoc '?lemma
                                            '?form
                                            '?word
                                            '?label
                                            '?shortLabel
                                            '?sense)
                                    (set/rename-keys sym->kw)
                                    (->> (q/attach-blank-entities g k)))
                      v         (with-meta subentity
                                           {:k->label    (assoc k->label
                                                           ?synset ?label)
                                            ;; TODO: undo ugly hack
                                            :short-label ?shortLabel})]
                  [k v])))
         (sort-by (comp - #(get @q/synset-indegrees % 0) first))
         (into (fop/ordered-map)))))

(comment
  ;; Look up synsets based on the lemma "have"
  (look-up (:graph @dk.cst.dannet.web.resources/db) "have")
  (label-lookup (:graph @dk.cst.dannet.web.resources/db))
  #_.)
