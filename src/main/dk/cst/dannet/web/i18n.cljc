(ns dk.cst.dannet.web.i18n
  "Functions for working with RDF LangStrings."
  #?(:clj  (:require [ont-app.vocabulary.lstr :as lstr]
                     [clojure.core.memoize :as memo])
     :cljs (:require [ont-app.vocabulary.lstr :as lstr :refer [LangStr]]))
  #?(:clj (:import [ont_app.vocabulary.lstr LangStr])))

;; TODO: derive dynamically?
(def supported-languages
  ["da" "en"])

(defn lang-prefs
  "Get a coll of preferred languages in order based on `lang`."
  [lang]
  (case lang
    "da" ["da" "en" nil]
    "en" ["en" nil "da"]
    nil ["en" nil "da"]
    [lang "en" nil "da"]))

(defn da-en
  [languages da en]
  (if (= "da" (first languages))
    da
    en))

(def rdf-string?
  "Tests whether the input is an RDF string value."
  (some-fn string? #(instance? LangStr %)))

(defn lang
  "Return the language abbreviation of `s` if available or nil if not."
  [s]
  (when (instance? LangStr s)
    (lstr/lang s)))

;; TODO: compare with https://www.rfc-editor.org/info/bcp47
(defn select-label
  "Select a single label from set of labels `x` based on preferred `languages`.
  If `x` is a not a set, e.g. a string, it is just returned as-is."
  [languages x]
  (if (coll? x)
    (let [lang->s (into {} (map (juxt lang identity) x))]
      (loop [[head & tail] languages]
        (if-let [ret (lang->s head)]
          ret
          (if (not-empty tail)
            (recur tail)
            (lang->s nil)))))
    x))

(defn select-str
  "Select strings in a set of strings `x` based on preferred `languages`.
  If `x` is a not a set, e.g. a string, it is just returned as-is.
  This function differs from 'select-label' by allowing for multiple strings
  to be returned instead of just one."
  [languages x]
  (if (coll? x)
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

;; Memoization unbounded in CLJS since core.memoize is CLJ-only!
#?(:clj (do
          (alter-var-root #'select-label #(memo/lu % :lu/threshold 1000))
          (alter-var-root #'select-str #(memo/lu % :lu/threshold 200)))
   :cljs (do
           (def select-label (memoize select-label))
           (def select-str (memoize select-str))))
