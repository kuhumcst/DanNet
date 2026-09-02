(ns dk.cst.dannet.db.shapes-test
  "Fixture-based tests for the SHACL shapes. These are self-contained: they
  validate small in-memory graphs and do not require a running database.

  Every property shape in shapes/base.ttl has a negative fixture below, asserted on
  the stable [:shape :constraint] identity of the resulting violation --
  except the sh:sparql constraints, which are identified by :message instead
  (see the `hypernym-shapes` deftest)."
  (:require [clojure.test :refer [deftest is testing]]
            [dk.cst.dannet.db.shapes :as shapes]
            [dk.cst.dannet.test-util :as util])
  (:import [ont_app.vocabulary.lstr LangStr]))

(def ^:private prefixes "
@prefix rdf:     <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
@prefix ontolex: <http://www.w3.org/ns/lemon/ontolex#> .
@prefix rdfs:    <http://www.w3.org/2000/01/rdf-schema#> .
@prefix skos:    <http://www.w3.org/2004/02/skos/core#> .
@prefix wn:      <https://globalwordnet.github.io/schemas/wn#> .
@prefix dn:      <https://wordnet.dk/dannet/data/> .
@prefix dns:     <https://wordnet.dk/dannet/schema/> .
@prefix dnt:     <https://wordnet.dk/dannet/types/> .
@prefix dnc:     <https://wordnet.dk/dannet/concepts/> .
@prefix ili:     <http://globalwordnet.org/ili/> .
")

(defn- validate-ttl
  [ttl]
  (shapes/validate (util/ttl->graph (str prefixes ttl))))

(defn- index-by-shape
  "Index `maps` (shapes->spec node shapes or their :properties) by :shape."
  [maps]
  (into {} (map (juxt :shape identity)) maps))

(defn- shape+constraint
  "The set of [shape constraint] pairs among the violations for `ttl`."
  [ttl]
  (->> (validate-ttl ttl) :entries (map (juxt :shape :constraint)) set))

(deftest conforming-fixtures
  (testing "well-formed entry + sense + synset conforms"
    (is (:conforms? (validate-ttl "
dn:entry-1 a ontolex:LexicalEntry ;
  rdfs:label \"hund\" ; wn:partOfSpeech wn:noun ;
  ontolex:sense dn:sense-1 ; ontolex:canonicalForm dn:form-1 ;
  skos:inScheme <https://wordnet.dk/dannet/data> .
dn:form-1 ontolex:writtenRep \"hund\"@da .
dn:sense-1 a ontolex:LexicalSense ; rdfs:label \"hund\" ;
  skos:inScheme <https://wordnet.dk/dannet/data> .
dn:synset-1 a ontolex:LexicalConcept ; rdfs:label \"{hund}\" ;
  skos:inScheme <https://wordnet.dk/dannet/data> .")))))

(deftest lexical-entry-shape
  (testing "missing rdfs:label"
    (is (contains? (shape+constraint "
dn:e a ontolex:LexicalEntry ; wn:partOfSpeech wn:noun ;
  ontolex:sense dn:s ; ontolex:canonicalForm dn:f .")
                   [:dns/LexicalEntryShape-label
                    :sh/MinCountConstraintComponent])))
  (testing "missing wn:partOfSpeech"
    (is (contains? (shape+constraint "
dn:e a ontolex:LexicalEntry ; rdfs:label \"hund\" ;
  ontolex:sense dn:s ; ontolex:canonicalForm dn:f .")
                   [:dns/LexicalEntryShape-partOfSpeech
                    :sh/MinCountConstraintComponent])))
  (testing "missing ontolex:sense"
    (is (contains? (shape+constraint "
dn:e a ontolex:LexicalEntry ; rdfs:label \"hund\" ;
  wn:partOfSpeech wn:noun ; ontolex:canonicalForm dn:f .")
                   [:dns/LexicalEntryShape-sense
                    :sh/MinCountConstraintComponent])))
  (testing "missing ontolex:canonicalForm"
    (is (contains? (shape+constraint "
dn:e a ontolex:LexicalEntry ; rdfs:label \"hund\" ;
  wn:partOfSpeech wn:noun ; ontolex:sense dn:s .")
                   [:dns/LexicalEntryShape-canonicalForm
                    :sh/MinCountConstraintComponent])))
  (testing "two canonical forms"
    (is (contains? (shape+constraint "
dn:e a ontolex:LexicalEntry ; rdfs:label \"hund\" ;
  wn:partOfSpeech wn:noun ; ontolex:sense dn:s ;
  ontolex:canonicalForm dn:f1 , dn:f2 .")
                   [:dns/LexicalEntryShape-canonicalForm
                    :sh/MaxCountConstraintComponent])))
  (testing "canonical form must not be a literal"
    (is (contains? (shape+constraint "
dn:e a ontolex:LexicalEntry ; rdfs:label \"hund\" ;
  wn:partOfSpeech wn:noun ; ontolex:sense dn:s ;
  ontolex:canonicalForm \"hund\" .")
                   [:dns/LexicalEntryShape-canonicalForm
                    :sh/NodeKindConstraintComponent])))
  (testing "canonical form without a written representation (issue #203)"
    (is (contains? (shape+constraint "
dn:e a ontolex:LexicalEntry ; rdfs:label \"hund\" ;
  wn:partOfSpeech wn:noun ; ontolex:sense dn:s ;
  ontolex:canonicalForm dn:f .")
                   [:dns/LexicalEntryShape-writtenRep
                    :sh/MinCountConstraintComponent]))))

(deftest lexical-sense-shape
  (testing "missing rdfs:label, flagged with its focus node"
    (let [{:keys [conforms? entries]} (validate-ttl "
dn:sense-2 a ontolex:LexicalSense .")]
      (is (false? conforms?))
      (is (some #(= :dn/sense-2 (:focus-node %)) entries))
      (is (contains? (set (map (juxt :shape :constraint) entries))
                     [:dns/LexicalSenseShape-label
                      :sh/MinCountConstraintComponent])))))

(deftest lexical-concept-shape
  (testing "missing rdfs:label"
    (is (contains? (shape+constraint "
dn:synset-2 a ontolex:LexicalConcept .")
                   [:dns/LexicalConceptShape-label
                    :sh/MinCountConstraintComponent]))))

(deftest hypernym-shapes
  ;; SPARQL constraints all report :sh/SPARQLConstraintComponent and Jena
  ;; 4.5.0 does not populate ReportEntry#sourceConstraint, so the individual
  ;; constraint is identified by :severity + :message instead.
  (let [entry-info (fn [ttl]
                     (->> (validate-ttl ttl) :entries
                          (map (juxt :shape :severity :message)) set))]
    (testing "self-hypernymy is a violation"
      (is (contains? (entry-info "
dn:synset-2 a ontolex:LexicalConcept ; rdfs:label \"{a}\" ;
  wn:hypernym dn:synset-2 .")
                     [:dns/HypernymHierarchyShape :sh/Violation
                      "A synset must not be its own hypernym."])))
    (testing "mutual hypernymy is a violation"
      (is (contains? (entry-info "
dn:synset-2 a ontolex:LexicalConcept ; rdfs:label \"{a}\" ;
  wn:hypernym dn:synset-3 .
dn:synset-3 a ontolex:LexicalConcept ; rdfs:label \"{b}\" ;
  wn:hypernym dn:synset-2 .")
                     [:dns/HypernymHierarchyShape :sh/Violation
                      "Two synsets must not be hypernyms of each other."])))
    (testing "cross-POS hypernymy is only a warning"
      (let [ttl "
dn:synset-2 a ontolex:LexicalConcept ; rdfs:label \"{a}\" ;
  ontolex:lexicalizedSense dn:sense-2 ; wn:hypernym dn:synset-3 .
dn:synset-3 a ontolex:LexicalConcept ; rdfs:label \"{b}\" ;
  ontolex:lexicalizedSense dn:sense-3 .
dn:sense-2 a ontolex:LexicalSense ; rdfs:label \"a\" .
dn:sense-3 a ontolex:LexicalSense ; rdfs:label \"b\" .
dn:word-2 a ontolex:Word ; rdfs:label \"a\" ; wn:partOfSpeech wn:noun ;
  ontolex:sense dn:sense-2 ; ontolex:canonicalForm dn:form-2 .
dn:word-3 a ontolex:Word ; rdfs:label \"b\" ; wn:partOfSpeech wn:verb ;
  ontolex:sense dn:sense-3 ; ontolex:canonicalForm dn:form-3 .
dn:form-2 ontolex:writtenRep \"a\"@da .
dn:form-3 ontolex:writtenRep \"b\"@da ."
            result (validate-ttl ttl)]
        (is (contains? (->> (:entries result)
                            (map (juxt :shape :severity :focus-node)) set)
                       [:dns/HypernymPOSShape :sh/Warning :dn/synset-2]))
        ;; Warnings never block, cf. the write-gating/baseline semantics.
        (is (not (shapes/blocking? result)))))
    (testing "hierarchy with agreeing POS produces no hypernym entries"
      (is (empty? (filter (comp #{:dns/HypernymHierarchyShape
                                  :dns/HypernymPOSShape} :shape)
                          (:entries (validate-ttl "
dn:synset-2 a ontolex:LexicalConcept ; rdfs:label \"{a}\" ;
  wn:hypernym dn:synset-3 .
dn:synset-3 a ontolex:LexicalConcept ; rdfs:label \"{b}\" ."))))))))

(deftest ili-relation-shape
  ;; SPARQL constraint, so identified by :severity + :message like the
  ;; hypernym shapes above.
  (testing "eq* relation targeting an ILI entry is a violation (issue #205)"
    (is (contains? (->> (validate-ttl "
dn:synset-2 a ontolex:LexicalConcept ; rdfs:label \"{a}\" ;
  dns:eqHyponym ili:i48720 .")
                        :entries (map (juxt :shape :severity :focus-node)) set)
                   [:dns/IliRelationShape :sh/Violation :dn/synset-2])))
  (testing "wn:ili and eq* to a synset produce no ILI entries"
    (is (empty? (filter (comp #{:dns/IliRelationShape} :shape)
                        (:entries (validate-ttl "
dn:synset-2 a ontolex:LexicalConcept ; rdfs:label \"{a}\" ;
  wn:ili ili:i48720 ;
  dns:eqHyponym <https://en-word.net/id/oewn-02486953-n> .")))))))

(deftest validate-node-targeting
  (testing "targeted validation only checks the given focus node"
    (let [g (util/ttl->graph (str prefixes "
dn:entry-1 a ontolex:LexicalEntry ;
  rdfs:label \"hund\" ; wn:partOfSpeech wn:noun ;
  ontolex:sense dn:sense-1 ; ontolex:canonicalForm dn:form-1 ;
  skos:inScheme <https://wordnet.dk/dannet/data> .
dn:form-1 ontolex:writtenRep \"hund\"@da .
dn:sense-2 a ontolex:LexicalSense ;
  skos:inScheme <https://wordnet.dk/dannet/data> ."))]
      ;; the graph as a whole has a violation...
      (is (false? (:conforms? (shapes/validate g))))
      ;; ...but the well-formed entry validates clean in isolation
      (is (:conforms? (shapes/validate-node g :dn/entry-1)))
      ;; ...while the bad sense is still flagged when targeted directly
      (is (= [[:dns/LexicalSenseShape-label :sh/MinCountConstraintComponent]]
             (map (juxt :shape :constraint)
                  (:entries (shapes/validate-node g :dn/sense-2))))))))

(deftest validate-changes-gate
  (let [g (util/ttl->graph (str prefixes "
dn:entry-1 a ontolex:LexicalEntry ;
  rdfs:label \"hund\" ; wn:partOfSpeech wn:noun ;
  ontolex:sense dn:sense-1 ; ontolex:canonicalForm dn:form-1 ;
  skos:inScheme <https://wordnet.dk/dannet/data> .
dn:form-1 ontolex:writtenRep \"hund\"@da ."))
        size (.size g)]
    (testing "deleting a required triple blocks the write"
      (let [result (shapes/validate-changes
                     g {:delete [[:dn/entry-1 :rdfs/label "hund"]]})]
        (is (shapes/blocking? result))
        (is (= [[:dn/entry-1 :dns/LexicalEntryShape-label]]
               (map (juxt :focus-node :shape) (:entries result))))))
    (testing "a conforming edit passes and the base graph is never mutated"
      (is (:conforms? (shapes/validate-changes
                        g {:add [[:dn/entry-1 :ontolex/sense :dn/sense-2]]})))
      (is (:conforms? (shapes/validate-changes g {})))
      (is (= size (.size g))))))

(deftest editorial-rules
  ;; One well-formed entry + sense + synset, incl. an ontological type and a
  ;; hypernym; each testing block below then submits one offending edit.
  (let [g (util/ttl->graph (str prefixes "
dn:synset-1 a ontolex:LexicalConcept ; rdfs:label \"{hund}\"@da ;
  skos:definition \"et dyr\"@da ;
  ontolex:lexicalizedSense dn:sense-1 ;
  dns:ontologicalType dnt:Animal ;
  wn:hypernym dn:synset-2 ;
  skos:inScheme <https://wordnet.dk/dannet/data> .
dn:synset-2 a ontolex:LexicalConcept ; rdfs:label \"{dyr}\"@da .
dnt:Animal a dns:OntologicalType ; rdf:_1 dnc:Animal .
dn:sense-1 a ontolex:LexicalSense ; rdfs:label \"hund_§1\"@da ;
  skos:inScheme <https://wordnet.dk/dannet/data> .
dn:word-1 a ontolex:Word ; rdfs:label \"\\\"hund\\\"\"@da ;
  wn:partOfSpeech wn:noun ; ontolex:sense dn:sense-1 ;
  ontolex:canonicalForm dn:form-1 ;
  skos:inScheme <https://wordnet.dk/dannet/data> .
dn:form-1 ontolex:writtenRep \"hund\"@da ."))
        entry-set (fn [changes]
                    (->> (shapes/validate-changes g changes) :entries
                         (map (juxt :focus-node :shape :severity)) set))]
    (testing "a well-formed synset edit produces no entries"
      (is (:conforms? (shapes/validate-changes
                        g {:add [[:dn/synset-1 :wn/hypernym :dn/synset-2]]}))))
    (testing "an unwrapped word label blocks"
      (is (contains? (entry-set {:add [[:dn/word-2 :rdf/type :ontolex/Word]
                                       [:dn/word-2 :rdfs/label (LangStr. "kat" "da")]]})
                     [:dn/word-2 :dns/EditorialWordShape-label :sh/Violation])))
    (testing "a part of speech outside the policy set blocks"
      (is (contains? (entry-set {:add [[:dn/word-2 :rdf/type :ontolex/Word]
                                       [:dn/word-2 :wn/partOfSpeech :wn/adverb]]})
                     [:dn/word-2 :dns/EditorialWordShape-partOfSpeech :sh/Violation])))
    (testing "a dangling hypernym target blocks"
      (is (contains? (entry-set {:add [[:dn/synset-1 :wn/hypernym :dn/synset-404]]})
                     [:dn/synset-1 :dns/EditorialHypernymTargetShape :sh/Violation])))
    (testing "a non-dnc member in a new ontological type blocks"
      (is (contains? (entry-set {:add [[:dnt/Fish-Object :rdf/type :dns/OntologicalType]
                                       [:dnt/Fish-Object :rdf/_1 :dnc/Object]
                                       [:dnt/Fish-Object :rdf/_2 :dn/synset-1]]})
                     [:dnt/Fish-Object :dns/EditorialOntotypeShape :sh/Violation])))
    (testing "a brace-wrapped sense label blocks"
      (is (contains? (entry-set {:add [[:dn/sense-2 :rdf/type :ontolex/LexicalSense]
                                       [:dn/sense-2 :rdfs/label (LangStr. "{fisk_§1}" "da")]]})
                     [:dn/sense-2 :dns/EditorialSenseShape-label :sh/Violation])))
    (testing "a minimal new synset only warns (definition, ontotype, hypernym)"
      (let [result (shapes/validate-changes
                     g {:add [[:dn/synset-3 :rdf/type :ontolex/LexicalConcept]
                              [:dn/synset-3 :rdfs/label (LangStr. "{kat}" "da")]
                              [:dn/synset-3 :ontolex/lexicalizedSense :dn/sense-1]
                              [:dn/synset-3 :skos/inScheme
                               "<https://wordnet.dk/dannet/data>"]]})]
        (is (not (shapes/blocking? result)))
        (is (= #{:dns/EditorialConceptShape-definition
                 :dns/EditorialConceptShape-ontologicalType
                 :dns/EditorialConceptShape-hypernym}
               (set (map :shape (:entries result)))))))
    (testing "re-adding existing triples never breaks maxCount constraints"
      ;; covers both plain IRI objects and language-tagged literals, which
      ;; must be normalized to rdf:langString to match the stored terms
      (is (:conforms? (shapes/validate-changes
                        g {:add [[:dn/word-1 :ontolex/canonicalForm :dn/form-1]
                                 [:dn/word-1 :rdfs/label
                                  (LangStr. "\"hund\"" "da")]]}))))))

(deftest shapes-spec
  (let [by-shape (index-by-shape (shapes/shapes->spec @shapes/shapes))
        props    (comp index-by-shape :properties by-shape)]
    (testing "node shape targets come out as plain data"
      (is (= [{:type :targetExtension :node :dns/DanNetEntryTarget}]
             (:targets (by-shape :dns/LexicalEntryShape)))))
    (testing "constraints carry their component type and parameters"
      (let [{:keys [path severity constraints messages]}
            (get (props :dns/LexicalEntryShape) :dns/LexicalEntryShape-canonicalForm)]
        (is (= :ontolex/canonicalForm path))
        (is (= :sh/Violation severity))
        (is (= #{{:type :sh/MinCountConstraintComponent :min-count 1}
                 {:type :sh/MaxCountConstraintComponent :max-count 1}
                 {:type :sh/NodeKindConstraintComponent :node-kind :sh/BlankNodeOrIRI}}
               (set constraints)))
        (is (= ["LexicalEntry must have exactly one ontolex:canonicalForm."]
               messages))))
    (testing "SPARQL constraints stay opaque node-level constraints"
      (let [{:keys [constraints properties]} (by-shape :dns/HypernymHierarchyShape)]
        (is (= [{:type :sh/SPARQLConstraintComponent}
                {:type :sh/SPARQLConstraintComponent}]
               constraints))
        (is (nil? properties))))
    (testing "spec keywords join against validation report entries"
      (let [{:keys [entries]} (shapes/validate
                                (util/ttl->graph (str prefixes "
dn:e a ontolex:LexicalEntry ; wn:partOfSpeech wn:noun ;
  ontolex:sense dn:s ; ontolex:canonicalForm dn:f .
dn:f ontolex:writtenRep \"hund\"@da .")))
            {:keys [shape path constraint]}
            (first (filter (comp #{:dns/LexicalEntryShape-label} :shape) entries))
            spec-prop (get (props :dns/LexicalEntryShape) shape)]
        (is (= :dns/LexicalEntryShape-label shape))
        (is (= path (:path spec-prop)))
        (is (contains? (set (map :type (:constraints spec-prop))) constraint)))))
  (testing "editorial shapes use sh:targetClass and warn-level properties"
    (let [by-shape (index-by-shape (shapes/shapes->spec @shapes/editorial-shapes))
          props    (comp index-by-shape :properties by-shape)
          {:keys [path severity]}
          (get (props :dns/EditorialConceptShape)
               :dns/EditorialConceptShape-definition)]
      (is (= [{:type :targetClass :node :ontolex/LexicalConcept}]
             (:targets (by-shape :dns/EditorialConceptShape))))
      (is (= :skos/definition path))
      (is (= :sh/Warning severity))
      ;; sh:in values surface as plain data, e.g. for a UI dropdown
      (is (= [[:wn/noun :wn/verb :wn/adjective]]
             (->> (get (props :dns/EditorialWordShape)
                       :dns/EditorialWordShape-partOfSpeech)
                  :constraints
                  (keep :values)))))))

(deftest validate-export-gate
  (let [->ttl-file (fn [ttl]
                     (doto (java.io.File/createTempFile "shapes-test" ".ttl")
                       (.deleteOnExit)
                       (spit (str prefixes ttl))))]
    (testing "conforming export passes the gate"
      ;; The gate consults :exceeded, not :conforms?, so assert on that.
      (let [result (shapes/validate-export!
                     (.getPath (->ttl-file "
dn:entry-1 a ontolex:LexicalEntry ;
  rdfs:label \"hund\" ; wn:partOfSpeech wn:noun ;
  ontolex:sense dn:sense-1 ; ontolex:canonicalForm dn:form-1 ;
  skos:inScheme <https://wordnet.dk/dannet/data> .
dn:form-1 ontolex:writtenRep \"hund\"@da .
dn:sense-1 a ontolex:LexicalSense ; rdfs:label \"hund\" ;
  skos:inScheme <https://wordnet.dk/dannet/data> .")))]
        (is (empty? (:exceeded result)))
        (is (zero? (:violations result)))
        (is (:conforms? result))))
    (testing "violations exceeding the baseline abort the export"
      ;; A label-less entry violates several LexicalEntryShape constraints
      ;; that have no baseline allowance, so the gate must throw.
      (is (thrown? clojure.lang.ExceptionInfo
                   (shapes/validate-export!
                     (.getPath (->ttl-file "
dn:entry-1 a ontolex:LexicalEntry ."))))))))
