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

(defn- own-labels
  "The labels in `k->label` of the properties and resources of the
  `subentity`, which are the ones that a search result is shown with."
  [k->label subentity]
  (select-keys k->label (filter keyword? (tree-seq coll? seq subentity))))

(defn look-up
  "Look up synsets in `g` based on the given `lemma`. Each synset has its
  own label and those of its properties and resources in its metadata."
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
                                    (->> (q/expand-blank-nodes g)))
                      v         (with-meta subentity
                                           {:k->label    (assoc (own-labels
                                                                  k->label
                                                                  subentity)
                                                           ?synset ?label)
                                            ;; TODO: undo ugly hack
                                            :short-label ?shortLabel})]
                  [k v])))
         (sort-by (comp - #(get @q/synset-indegrees % 0) first))
         (into (fop/ordered-map)))))

(defn- homograph
  "The number that DDO tells words with the same lemma apart by, as the
  sense `label` has it, e.g. \"1\" in hund_1§1, or nil when it has none."
  [label]
  (second (re-find #"_(\d+)§" (str label))))

(defn words
  "The words of DanNet in `g` whose lemma is one of `lemmas`, in the order
  of the lemmas. A word is a map of its :rdf/about, :rdfs/label and :lemma,
  and of its :wn/partOfSpeech with the :labels of that, which are looked up
  in the graph `schema`, since the datasets don't hold them.

  Words that share a lemma also have their :homograph, the number that DDO
  tells them apart by, when their senses have one."
  [g schema lemmas]
  (let [rank    (zipmap lemmas (range))
        rows    (theo/q g op/lemma-words
                        {'?lemma (map #(->LangString % "da") lemmas)})
        labels  (when-let [parts (seq (distinct (keep '?pos rows)))]
                  (->> (theo/q schema op/same-as-labels {'?resource parts})
                       (reduce (fn [m {:syms [?resource ?label]}]
                                 (update m ?resource conj ?label))
                               (zipmap parts (repeat #{})))))
        words   (for [[word [{:syms [?lemma ?label ?pos]} :as rows]]
                      (group-by '?word rows)]
                  (cond-> {:rdf/about  word
                           :rdfs/label ?label
                           :lemma      ?lemma
                           :homograph  (some (comp homograph '?senseLabel)
                                             rows)}
                    ?pos (assoc :wn/partOfSpeech ?pos
                                :labels (select-keys labels [?pos]))))
        shared? (->> (frequencies (map :lemma words))
                     (keep (fn [[lemma n]] (when (< 1 n) lemma)))
                     (set))]
    (->> words
         (map #(if (and (shared? (:lemma %)) (:homograph %))
                 %
                 (dissoc % :homograph)))
         (sort-by (juxt (comp rank str :lemma)
                        (comp str :homograph)
                        (comp str :rdf/about))))))

(comment
  ;; Look up synsets based on the lemma "have"
  (look-up (:inference @dk.cst.dannet.web.instance/db) "have")
  (label-lookup (:inference @dk.cst.dannet.web.instance/db))
  #_.)
