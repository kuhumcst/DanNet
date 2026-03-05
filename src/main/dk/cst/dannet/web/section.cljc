(ns dk.cst.dannet.web.section
  (:require #?(:clj [clojure.core.memoize :as memo])
            [ont-app.vocabulary.lstr :refer [->LangStr #?(:cljs LangStr)]])
  #?(:clj (:import [ont_app.vocabulary.lstr LangStr])))

(defmulti defined-sections :rdf/type)

(def lexical-title
  #{(->LangStr "Lexical context" "en")
    (->LangStr "Leksikalsk kontekst" "da")})

(def semantic-title
  #{(->LangStr "Semantic relations" "en")
    (->LangStr "Betydningsrelationer" "da")})

(def cross-link-title
  #{(->LangStr "External links" "en")
    (->LangStr "Eksterne forbindelser" "da")})

(defn with-prefix
  "Return predicate accepting keywords with `prefix` (`except` set of keywords).
  The returned predicate function is used to filter keywords based on prefixes."
  [prefix & {:keys [except]}]
  (fn [[k v]]
    (when (keyword? k)
      (and (not (except k))
           (= (namespace k) (name prefix))))))

(def semantic-rels?
  (some-fn (with-prefix 'wn :except #{:wn/partOfSpeech
                                      :wn/definition
                                      :wn/ili
                                      :wn/eq_synonym
                                      :wn/lexfile
                                      :wn/example})
           (comp #{:dns/usedFor
                   :dns/usedForObject
                   :dns/nearAntonym
                   :dns/crossPoSHyponym
                   :dns/crossPoSHypernym
                   :dns/orthogonalHyponym
                   :dns/orthogonalHypernym} first)))

(def top-section
  [nil [:rdf/type
        :skos/definition
        :wn/definition
        :dns/ontologicalType
        :rdfs/comment
        :vann/preferredNamespacePrefix
        :dc/description
        :dcat/downloadURL]])

(def semantic-section
  [semantic-title semantic-rels?])

(def cross-link-section
  [cross-link-title
   [:owl/sameAs
    :wn/ili
    :dns/linkedConcept                                      ; inverse of wn:ili
    :wn/eq_synonym
    :dns/eqHyponym
    :dns/eqHypernym
    :dns/eqSimilar]])

(def lexical-section
  [lexical-title
   [:wn/partOfSpeech
    :wn/example
    :lexinfo/partOfSpeech
    :lexinfo/senseExample
    :lexinfo/frequency
    :lexinfo/usageNote
    :dns/sentiment
    :dns/gender
    :ontolex/writtenRep
    :ontolex/canonicalForm
    :ontolex/otherForm
    :ontolex/evokes
    :ontolex/isEvokedBy
    :ontolex/sense
    :ontolex/isSenseOf
    :ontolex/lexicalizedSense
    :ontolex/isLexicalizedSenseOf]])

(defmethod defined-sections :ontolex/LexicalConcept
  [entity]
  [[nil [:rdf/type                                          ; needed, not shown
         :skos/definition
         :wn/definition                                     ; used by OEWN
         :wn/lexfile
         :dns/ontologicalType]]
   semantic-section
   lexical-section
   cross-link-section])

(defmethod defined-sections :default
  [entity]
  [top-section
   semantic-section
   lexical-section
   cross-link-section])

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
  (add-other-section (defined-sections entity)))
