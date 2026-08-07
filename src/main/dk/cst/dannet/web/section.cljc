(ns dk.cst.dannet.web.section
  (:require #?(:clj [clojure.core.memoize :as memo])
            [ont-app.vocabulary.lstr :refer [->LangStr #?(:cljs LangStr)]]
            [dk.cst.dannet.shared :as shared])
  #?(:clj (:import [ont_app.vocabulary.lstr LangStr])))

(def core-shapes
  "The core OntoLex shapes that have custom section definitions."
  #{:ontolex/LexicalConcept
    :ontolex/LexicalSense
    :ontolex/LexicalEntry
    :ontolex/Word
    :ontolex/MultiwordExpression})

(def shape-hierarchy
  "Hierarchy used to dispatch subclasses of the core OntoLex shapes,
  e.g. DanNet words are ontolex:Word while the OEWN just uses
  ontolex:LexicalEntry directly."
  (-> (make-hierarchy)
      (derive :ontolex/Word :ontolex/LexicalEntry)
      (derive :ontolex/MultiwordExpression :ontolex/LexicalEntry)))

(defn shape-dispatch
  "Return the core shape of `entity` based on its rdf:type (or :default).
  Handles both single keyword and collection values for rdf:type."
  [{:keys [rdf/type] :as entity}]
  (if (coll? type)
    (or (some core-shapes type)
        :default)
    type))

(defmulti defined-sections shape-dispatch :hierarchy #'shape-hierarchy)

(def lexical-title
  #{(->LangStr "Lexical context" "en")
    (->LangStr "Leksikalsk kontekst" "da")})

(def semantic-title
  #{(->LangStr "Semantic relations" "en")
    (->LangStr "Betydningsrelationer" "da")})

(def cross-link-title
  #{(->LangStr "External links" "en")
    (->LangStr "Eksterne forbindelser" "da")})

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
  [semantic-title shared/semantic-rels?])

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

(def synset-lexical-section
  "Like `lexical-section`, but omitting the usage examples which are instead
  displayed as part of the synset summary."
  (let [[title ks] lexical-section]
    [title (vec (remove #{:lexinfo/senseExample :wn/example} ks))]))

(defmethod defined-sections :ontolex/LexicalConcept
  [entity]
  [[nil [:rdf/type                                          ; needed, not shown
         :skos/definition
         :wn/definition                                     ; used by OEWN
         :wn/lexfile
         :dns/ontologicalType
         :lexinfo/senseExample                              ; supplemented (DanNet)
         :wn/example]]                                      ; used by OEWN
   semantic-section
   synset-lexical-section
   cross-link-section])

(defmethod defined-sections :ontolex/LexicalSense
  [entity]
  [[nil [:rdf/type
         :skos/definition
         :lexinfo/senseExample
         :skos/note]]
   semantic-section                                         ; OEWN sense-level rels
   [lexical-title [:ontolex/isLexicalizedSenseOf
                   :ontolex/isSenseOf
                   :dns/sentiment]]
   [cross-link-title [:dns/source                           ; DDO deep link
                      :owl/sameAs]]])

(defmethod defined-sections :ontolex/LexicalEntry
  [entity]
  ;; Also covers ontolex:Word and ontolex:MultiwordExpression (DanNet) through
  ;; `shape-hierarchy`; the OEWN types its entries as ontolex:LexicalEntry.
  [[nil [:rdf/type
         :lexinfo/partOfSpeech
         :wn/partOfSpeech
         :dns/gender]]
   semantic-section
   [lexical-title [:ontolex/canonicalForm
                   :ontolex/otherForm
                   :ontolex/lexicalForm
                   :ontolex/writtenRep
                   :lexinfo/morphosyntacticProperty
                   :ontolex/sense
                   :ontolex/evokes]]
   [cross-link-title [:owl/sameAs
                      :dns/source]]])

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
