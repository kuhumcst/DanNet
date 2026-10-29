(ns dk.cst.dannet.db.search
  "Graph database search functionality."
  (:require [clojure.set :as set]
            [flatland.ordered.map :as fop]
            [dk.cst.theophrastus :as theo]
            [dk.cst.theophrastus.term :refer [->LangString]]
            [dk.cst.dannet.db.query :as q]
            [dk.cst.dannet.db.query.operation :as op]))

;; The relations a search result shows the query values under; the synset
;; itself is the result's dc:subject (see web.ui.search/result).
(def sym->kw
  {'?synset     :dc/subject
   '?definition :skos/definition
   '?ontoType   :dns/ontologicalType
   '?lexfile    :wn/lexfile})

;; TODO: optimize; seems to include every ontotype label on every request
;; TODO: does this memoization even accomplish anything?
(def label-lookup
  (memoize
    (fn [g]
      (let [search-labels   (theo/q g op/synset-search-labels)
            ontotype-labels (theo/q g op/ontotype-labels)]
        (merge (set/rename-keys (apply merge-with q/set-merge search-labels)
                                sym->kw)
               (->> (for [{:syms [?ontotype ?label]} ontotype-labels]
                      {?ontotype ?label})
                    (apply merge-with q/set-merge)))))))

(defn look-up
  "Look up synsets in `g` based on the given `lemma`."
  [g lemma]
  (let [k->label (label-lookup g)]
    (->> (theo/q g op/synset-search {'?lemma (->LangString lemma "da")})
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
                                    (->> (q/attach-blank-nodes g)))
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
  (look-up (:inference @dk.cst.dannet.web.instance/db) "have")
  (label-lookup (:inference @dk.cst.dannet.web.instance/db))
  #_.)
