(ns dk.cst.dannet.editable.input-types
  "The input types of the classes of DanNet that have one of their own: a
  resource with a search for a synset, an ontological type or a word (see
  dk.cst.dannet.web.ui.edit.input-type for what an input type and a search are).

  The rules of the editor say which class each is for (see the :input-types
  of dk.cst.dannet.editable)."
  (:require [clojure.string :as str]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.web.i18n :as i18n]
            [dk.cst.dannet.web.ui.edit.input-type :as input-type]))

(def synset
  "A synset, typed as its ID or found by the start of one of its words,
  which the server looks up."
  (assoc input-type/resource
    :search {:title       {:da "Find et synset" :en "Find a synset"}
             :placeholder {:da "skriv et ord..." :en "write a word..."}
             :results-fn  (fn [s]
                            (when-let [lemma (not-empty (str/trim s))]
                              (input-type/fetch-data
                                prefix/synsets-path :synsets {:lemma lemma})))
             :value-fn    key
             ;; the labels of a result are in the metadata of its entity
             :label-fn    (fn [[k entity]]
                            (get (:k->label (meta entity)) k))
             :hiccup-fn   (fn [[k entity] {:keys [languages] :as opts}]
                            (let [{:keys [k->label]} (meta entity)
                                  label #(str (i18n/select-label languages %))
                                  type  (:dns/ontologicalType entity)]
                              [:<>
                               [:strong (label (get k->label k))]
                               [:span (label (:skos/definition entity))]
                               (when type
                                 [:small (label (get k->label type))])]))}))

;; The types are fetched once and then searched in the browser. A fetch that
;; gives none, e.g. one that a change of page aborts, is made again.
(defonce ^{:doc "Every ontological type with its label, once fetched."}
  ontological-types
  (atom nil))

(defn- matching-types
  "A promise of the ontological types whose label contains the text `s`, in
  any case."
  [s]
  #?(:cljs (let [s      (str/lower-case (str/trim s))
                 match? #(-> (str (:rdfs/label %))
                             (str/lower-case)
                             (str/includes? s))]
             (.then (if-let [types @ontological-types]
                      (js/Promise.resolve types)
                      (.then (input-type/fetch-data
                               prefix/ontological-types-path
                               :ontological-types)
                             #(reset! ontological-types %)))
                    #(filter match? %)))))

(def ontological-type
  "An ontological type, typed as its ID or found among the existing types,
  which the browser has."
  (assoc input-type/resource
    :search {:title       {:da "Find en ontologisk type"
                           :en "Find an ontological type"}
             :placeholder {:da "skriv et begreb..." :en "write a concept..."}
             :results-fn  matching-types
             :value-fn    :rdf/about
             :label-fn    :rdfs/label
             :hiccup-fn   (fn [{:keys [rdfs/label]} _]
                            [:strong (str label)])}))

(def word
  "A word of DanNet, typed as its ID or found by the start of its lemma,
  which the server looks up.

  A result is the lemma of a word and its part of speech. Words with the
  same lemma have the number that DDO tells them apart by."
  (assoc input-type/resource
    :search {:title       {:da "Find et ord" :en "Find a word"}
             :placeholder {:da "skriv et ord..." :en "write a word..."}
             :one-line?   true
             :results-fn  (fn [s]
                            (when-let [lemma (not-empty (str/trim s))]
                              (input-type/fetch-data
                                prefix/words-path :words {:lemma lemma})))
             :value-fn    :rdf/about
             :label-fn    :rdfs/label
             :hiccup-fn   (fn [{:keys [lemma homograph labels]
                                pos   :wn/partOfSpeech}
                               {:keys [languages]}]
                            [:<>
                             [:strong (str lemma) (when homograph
                                                    [:sup homograph])]
                             (when pos
                               [:span (str (or (i18n/select-label
                                                 languages (get labels pos))
                                               (name pos)))])])}))
