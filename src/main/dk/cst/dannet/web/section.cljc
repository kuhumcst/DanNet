(ns dk.cst.dannet.web.section
  (:require #?(:clj [clojure.core.memoize :as memo])
            [ont-app.vocabulary.lstr :refer [->LangStr #?(:cljs LangStr)]]
            [dk.cst.dannet.prefix :as prefix])
  #?(:clj (:import [ont_app.vocabulary.lstr LangStr])))

(defmulti primary-sections :rdf/type)

(defmethod primary-sections :default
  [entity]
  [[nil [:rdf/type
         :owl/sameAs
         :skos/definition
         :rdfs/comment
         :lexinfo/partOfSpeech
         :lexinfo/senseExample
         :dns/sentiment
         :dns/ontologicalType
         :vann/preferredNamespacePrefix
         :dc/description
         :dcat/downloadURL]]
   [#{(->LangStr "Lexical components" "en")
      (->LangStr "Leksikalske bestanddele" "da")}
    [:ontolex/writtenRep
     :ontolex/canonicalForm
     :ontolex/otherForm
     :ontolex/evokes
     :ontolex/isEvokedBy
     :ontolex/sense
     :ontolex/isSenseOf
     :ontolex/lexicalizedSense
     :ontolex/isLexicalizedSenseOf]]
   [#{(->LangStr "Semantic relations" "en")
      (->LangStr "Betydningsrelationer" "da")}
    (some-fn (prefix/with-prefix 'wn :except #{:wn/partOfSpeech})
             (comp #{:dns/usedFor
                     :dns/usedForObject
                     :dns/nearAntonym
                     :dns/orthogonalHyponym
                     :dns/orthogonalHypernym} first))]])

(defn add-other-section
  "Expand `sections` to include 'Other' (containing the remainder an entity)."
  [sections]
  (let [ks-defs     (map second sections)
        in-ks?      (fn [[k _]]
                      (get (set (apply concat (filter coll? ks-defs)))
                           k))
        in-section? (apply some-fn in-ks? (filter fn? ks-defs))]
    (conj sections [#{(->LangStr "Other" "en")
                      (->LangStr "Andet" "da")}
                    (complement in-section?)])))

;; Memoization unbounded in CLJS since core.memoize is CLJ-only!
#?(:clj  (alter-var-root #'add-other-section #(memo/lu % :lu/threshold 100))
   :cljs (def add-other-section (memoize add-other-section)))

(defn page-sections
  "Get page sections as a coll of [title ks] for the `entity`."
  [entity]
  (add-other-section (primary-sections entity)))
