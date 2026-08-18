(ns dk.cst.dannet.db.shapes-test
  "Fixture-based tests for the SHACL shapes. These are self-contained: they
  validate small in-memory graphs and do not require a running database.

  Every property shape in shapes/base.ttl has a negative fixture below, asserted on
  the stable [:shape :constraint] identity of the resulting violation --
  except the sh:sparql constraints, which are identified by :message instead
  (see the `hypernym-shapes` deftest)."
  (:require [clojure.test :refer [deftest is testing]]
            [dk.cst.dannet.db.shapes :as shapes]
            [dk.cst.dannet.test-util :as util]))

(def ^:private prefixes "
@prefix ontolex: <http://www.w3.org/ns/lemon/ontolex#> .
@prefix rdfs:    <http://www.w3.org/2000/01/rdf-schema#> .
@prefix skos:    <http://www.w3.org/2004/02/skos/core#> .
@prefix wn:      <https://globalwordnet.github.io/schemas/wn#> .
@prefix dn:      <https://wordnet.dk/dannet/data/> .
@prefix dns:     <https://wordnet.dk/dannet/schema/> .
@prefix ili:     <http://globalwordnet.org/ili/> .
")

(defn- validate-ttl
  [ttl]
  (shapes/validate (util/ttl->graph (str prefixes ttl))))

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
