(ns dk.cst.dannet.web.i18n
  "Functions for working with RDF LangStrings."
  #?(:clj  (:require [ont-app.vocabulary.lstr :as lstr])
     :cljs (:require [ont-app.vocabulary.lstr :as lstr :refer [LangStr]]))
  #?(:clj (:import [ont_app.vocabulary.lstr LangStr])))

(def rdf-string?
  "Tests whether the input is an RDF string value."
  (some-fn string? #(instance? LangStr %)))

(defn lang
  "Return the language abbreviation of `s` if available or nil if not."
  [s]
  (when (instance? LangStr s)
    (lstr/lang s)))

(defn select-label-slow
  "Select a single label from set of labels `x` based on preferred `languages`.
  If `x` is a not a set, e.g. a string, it is just returned as-is."
  [languages x]
  (if (set? x)
    (let [lang->s (into {} (map (juxt lang identity) x))]
      (or (loop [[head & tail] languages]
            (when head
              (if-let [ret (lang->s head)]
                ret
                (recur tail))))
          (lang->s nil)))
    x))

(defn select-str-slow
  "Select strings in a set of strings `x` based on preferred `languages`.
  If `x` is a not a set, e.g. a string, it is just returned as-is.

  This function differs from 'select-label' by allowing for multiple strings
  to be returned instead of just one."
  [languages x]
  (if (set? x)
    (let [lang->strs (group-by lang x)
          ret        (loop [[head & tail] languages]
                       (when head
                         (or (get lang->strs head)
                             (recur tail))))
          strs       (or ret (get lang->strs nil))]
      (if (= 1 (count strs))
        (first strs)
        strs))
    x))

;; TODO: use e.g. core.memoize rather than naïve memoisation
(def select-label (memoize select-label-slow))
(def select-str (memoize select-str-slow))
