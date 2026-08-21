(ns dk.cst.dannet.web.section
  (:require #?(:clj [clojure.core.memoize :as memo])
            [ont-app.vocabulary.lstr :refer [->LangStr #?(:cljs LangStr)]]
            [dk.cst.dannet.shared :as shared])
  #?(:clj (:import [ont_app.vocabulary.lstr LangStr])))

(def core-shapes
  "The core shapes that have custom section definitions."
  #{:ontolex/LexicalConcept
    :ontolex/LexicalSense
    :ontolex/LexicalEntry
    :ontolex/Word
    :ontolex/MultiwordExpression
    :dns/OntologicalType
    :dns/PolysemyPattern})

(def shape-hierarchy
  "Hierarchy used to dispatch subclasses of the core OntoLex shapes,
  e.g. DanNet words are ontolex:Word while the OEWN just uses
  ontolex:LexicalEntry directly. FrameNet frame elements assert one of the
  four pmofn: status classes, dispatched as pmofn:FrameElement."
  (-> (make-hierarchy)
      (derive :ontolex/Word :ontolex/LexicalEntry)
      (derive :ontolex/MultiwordExpression :ontolex/LexicalEntry)
      (derive :pmofn/CoreFrameElement :pmofn/FrameElement)
      (derive :pmofn/PeripheralFrameElement :pmofn/FrameElement)
      (derive :pmofn/ExtraThematicFrameElement :pmofn/FrameElement)
      (derive :pmofn/CoreUnexpressedFrameElement :pmofn/FrameElement)))

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

(def usage-title
  #{(->LangStr "Usage" "en")
    (->LangStr "Anvendelse" "da")})

(def patterns-title
  "Shared by the systematic polysemy rows on sense/synset pages and the
  frame-to-frame relations on frame pages."
  #{(->LangStr "Systematic patterns" "en")
    (->LangStr "Systematiske mønstre" "da")})

(def frame-elements-title
  #{(->LangStr "Frame elements" "en")
    (->LangStr "Rammeelementer" "da")})

(def frame-relations
  "The semantic FrameNet frame-to-frame relations, including our declared
  dns:inheritedByFrame inverse; PreMOn's editorial relations are never
  asserted."
  [:pmofn/inheritsFrom
   :dns/inheritedByFrame
   :pmofn/uses
   :pmofn/subframeOf
   :pmofn/perspectiveOn
   :pmofn/precedes
   :pmofn/isCausativeOf
   :pmofn/isInchoativeOf])

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
    :dns/iliOf                                              ; inverse of wn:ili
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
         :pmofn/semType                                     ; used by frames
         :lexinfo/senseExample                              ; supplemented (DanNet)
         :wn/example                                        ; used by OEWN
         :dns/frameOf]]                                     ; used by frames
   semantic-section
   ;; dns:frame covers the frames supplemented onto synsets from the COR.SEM
   ;; senses linking them (see query/supplement-synset).
   [patterns-title (into [:dns/frame
                          :dns/alternatesTo
                          :dns/alternatesFrom
                          :dns/alternatesWith]
                         frame-relations)]
   [frame-elements-title [:pmo/semRole
                          :pmofn/feCoreSet]]
   synset-lexical-section
   ;; dns:source (the DDO deep links supplemented from the senses) and
   ;; dns:linkedSynsetOf are kept out of the shared cross-link-section, which
   ;; doubles as the synset relation probe in web.instance/find-synset-relations.
   (update cross-link-section 1 #(conj (into [:dns/source] %) :dns/linkedSynsetOf))])

(defmethod defined-sections :ontolex/LexicalSense
  [entity]
  [[nil [:rdf/type
         :skos/definition
         :dns/simpleOntologicalType
         :dns/frame
         :lexinfo/senseExample
         :skos/note]]
   semantic-section                                         ; OEWN sense-level rels
   [patterns-title [:dns/polysemyPattern
                    :dns/alternatesTo
                    :dns/alternatesFrom
                    :dns/alternatesWith]]
   [lexical-title [:ontolex/isLexicalizedSenseOf
                   :ontolex/isSenseOf
                   :dns/sentiment
                   :dns/centrality
                   :lexinfo/frequency
                   :lexinfo/usageNote]]
   [cross-link-title [:dns/source                           ; DDO deep link
                      :owl/sameAs
                      :dns/linkedSynset
                      :dns/linkedSenseOf
                      :dns/eqSense
                      :dns/eqNearSense
                      :dns/hypernymAnchor]]])

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
                      :dns/linkedSense
                      :dns/source]]])

(defmethod defined-sections :dns/OntologicalType
  [entity]
  ;; The rdf:_N membership rows join the top section, where the frontend
  ;; table folds them into a single rdfs:member row.
  [[nil (into [:rdf/type] (filter shared/member-property? (keys entity)))]
   [usage-title [:dns/ontologicalTypeOf
                 :dns/simpleOntologicalTypeOf]]])

(defmethod defined-sections :dns/PolysemyPattern
  [entity]
  ;; The rdf:_N membership rows join the top section, where the frontend
  ;; table folds them into a single rdfs:member row.
  [[nil (into [:rdf/type]
              (concat (filter shared/member-property? (keys entity))
                      [:dns/patternGroup
                       :rdfs/comment]))]
   [usage-title [:dns/polysemyPatternOf]]])

(defmethod defined-sections :pmofn/FrameElement
  [entity]
  [[nil [:rdf/type
         :skos/definition
         :dns/roleOf
         :pmofn/semType
         :pmo/abbreviation]]
   [patterns-title [:pmofn/inheritsFromFER
                    :dns/inheritedByRole
                    :pmofn/usesFER
                    :pmofn/subframeOfFER
                    :pmofn/perspectiveOnFER
                    :pmofn/precedesFER
                    :pmofn/isCausativeOfFER
                    :pmofn/isInchoativeOfFER
                    :pmofn/excludesFrameElement
                    :pmofn/requiresFrameElement]]
   cross-link-section])

(defmethod defined-sections :pmofn/SemType
  [entity]
  [[nil [:rdf/type
         :skos/definition
         :pmofn/subTypeOf]]
   [usage-title [:dns/semTypeOf]]
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
