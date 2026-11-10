(ns dk.cst.dannet.db.shapes-test
  "Fixture-based tests for the SHACL shapes. These are self-contained: they
  validate small in-memory graphs and do not require a running database.

  Every property shape in shapes/base.ttl has a negative fixture below, asserted on
  the stable [:source-shape :constraint-component] identity of the resulting
  violation, except the sh:sparql constraints, which are identified by
  :message or :focus-node instead (see the `hypernym-shapes` deftest)."
  (:require [clojure.test :refer [deftest is testing]]
            [dk.cst.theophrastus :as theo]
            [dk.cst.theophrastus.shacl :as shacl]
            [dk.cst.theophrastus.term :refer [->LangString]]
            [dk.cst.dannet.db.shapes :as shapes]
            [dk.cst.dannet.test-util :as util]))

(def ^:private prefixes "
@prefix rdf:     <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
@prefix ontolex: <http://www.w3.org/ns/lemon/ontolex#> .
@prefix owl:     <http://www.w3.org/2002/07/owl#> .
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
  (shapes/validate (util/ttl->db (str prefixes ttl))))

(defn- shape+constraint
  "The set of [shape constraint] pairs among the violations for `ttl`."
  [ttl]
  (->> (validate-ttl ttl)
       :results
       (map (juxt :source-shape :constraint-component))
       set))

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
    (let [{:keys [conforms? results]} (validate-ttl "
dn:sense-2 a ontolex:LexicalSense .")]
      (is (false? conforms?))
      (is (some #(= :dn/sense-2 (:focus-node %)) results))
      (is (contains? (set (map (juxt :source-shape :constraint-component)
                               results))
                     [:dns/LexicalSenseShape-label
                      :sh/MinCountConstraintComponent])))))

(deftest lexical-concept-shape
  (testing "missing rdfs:label"
    (is (contains? (shape+constraint "
dn:synset-2 a ontolex:LexicalConcept .")
                   [:dns/LexicalConceptShape-label
                    :sh/MinCountConstraintComponent]))))

(deftest hypernym-shapes
  ;; SPARQL constraints all report :sh/SPARQLConstraintComponent and Jena's
  ;; ReportEntry#sourceConstraint holds the shape, so the individual
  ;; constraint is identified by :severity + :message instead.
  (let [entry-info (fn [ttl]
                     (->> (validate-ttl ttl) :results
                          (map (juxt :source-shape :severity :message)) set))]
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
        (is (contains? (->> (:results result)
                            (map (juxt :source-shape :severity :focus-node)) set)
                       [:dns/HypernymPOSShape :sh/Warning :dn/synset-2]))
        ;; Warnings never block, cf. the write-gating/baseline semantics.
        (is (not (shacl/violations? result)))))
    (testing "hierarchy with agreeing POS produces no hypernym entries"
      (is (empty? (filter (comp #{:dns/HypernymHierarchyShape
                                  :dns/HypernymPOSShape} :source-shape)
                          (:results (validate-ttl "
dn:synset-2 a ontolex:LexicalConcept ; rdfs:label \"{a}\" ;
  wn:hypernym dn:synset-3 .
dn:synset-3 a ontolex:LexicalConcept ; rdfs:label \"{b}\" ."))))))))

(deftest ili-relation-shape
  ;; SPARQL constraint, so identified by :severity and :focus-node rather than
  ;; :constraint-component.
  (testing "eq* relation targeting an ILI entry is a violation (issue #205)"
    (is (contains? (->> (validate-ttl "
dn:synset-2 a ontolex:LexicalConcept ; rdfs:label \"{a}\" ;
  dns:eqHyponym ili:i48720 .")
                        :results
                        (map (juxt :source-shape :severity :focus-node))
                        set)
                   [:dns/IliRelationShape :sh/Violation :dn/synset-2])))
  (testing "wn:ili and eq* to a synset produce no ILI entries"
    (is (empty? (filter (comp #{:dns/IliRelationShape} :source-shape)
                        (:results (validate-ttl "
dn:synset-2 a ontolex:LexicalConcept ; rdfs:label \"{a}\" ;
  wn:ili ili:i48720 ;
  dns:eqHyponym <https://en-word.net/id/oewn-02486953-n> .")))))))

(deftest negation-contradiction-shape
  ;; SPARQL constraint, so identified by :severity and :focus-node rather than
  ;; :constraint-component.
  (let [negation "
[] a owl:NegativePropertyAssertion ;
  owl:sourceIndividual dn:synset-2 ;
  owl:assertionProperty wn:agent ;
  owl:targetIndividual dn:synset-3 .
dn:synset-2 a ontolex:LexicalConcept ; rdfs:label \"{a}\" .
dn:synset-3 a ontolex:LexicalConcept ; rdfs:label \"{b}\" ."]
    (testing "a relation that a negation denies is a violation"
      (is (contains? (->> (validate-ttl (str negation "
dn:synset-2 wn:agent dn:synset-3 ."))
                          :results
                          (map (juxt :source-shape :severity :focus-node))
                          set)
                     [:dns/NegationContradictionShape :sh/Violation :dn/synset-2])))
    (testing "a negation alone produces no negation entries"
      (is (empty? (filter (comp #{:dns/NegationContradictionShape} :source-shape)
                          (:results (validate-ttl negation))))))))

(deftest ili-shapes
  (let [entries (fn [ttl]
                  (->> (validate-ttl ttl) :results
                       (map (juxt :source-shape :severity :focus-node)) set))]
    (testing "several wn:ili on one synset are a violation"
      (is (contains? (entries "
dn:synset-2 a ontolex:LexicalConcept ; rdfs:label \"{a}\" ;
  wn:ili ili:i1 , ili:i2 .")
                     [:dns/LexicalConceptShape-ili :sh/Violation :dn/synset-2])))
    (testing "a wn:eq_synonym target without the synset's wn:ili is a warning"
      (is (contains? (entries "
dn:synset-2 a ontolex:LexicalConcept ; rdfs:label \"{a}\" ;
  wn:ili ili:i1 ; wn:eq_synonym <https://en-word.net/id/oewn-2-n> .
<https://en-word.net/id/oewn-2-n> wn:ili ili:i2 .")
                     [:dns/IliEqSynonymShape :sh/Warning :dn/synset-2])))
    (testing "agreeing wn:ili and wn:eq_synonym produce no ILI entries"
      (is (empty? (filter (comp #{:dns/LexicalConceptShape-ili
                                  :dns/IliEqSynonymShape} first)
                          (entries "
dn:synset-2 a ontolex:LexicalConcept ; rdfs:label \"{a}\" ;
  wn:ili ili:i1 ; wn:eq_synonym <https://en-word.net/id/oewn-1-n> .
<https://en-word.net/id/oewn-1-n> wn:ili ili:i1 .")))))))

(deftest eq-relation-shape
  (testing "two eq* relations to the same synset are a violation"
    (let [result (validate-ttl "
dn:synset-2 a ontolex:LexicalConcept ; rdfs:label \"{a}\" ;
  wn:eq_synonym <https://en-word.net/id/oewn-02486953-n> ;
  dns:eqSimilar <https://en-word.net/id/oewn-02486953-n> .")]
      (is (contains? (->> (:results result)
                          (map (juxt :source-shape :severity :focus-node)) set)
                     [:dns/EqRelationShape :sh/Violation :dn/synset-2]))
      (is (shacl/violations? result))))
  (testing "eq* relations to different synsets produce no eq* entries"
    (is (empty? (filter (comp #{:dns/EqRelationShape} :source-shape)
                        (:results (validate-ttl "
dn:synset-2 a ontolex:LexicalConcept ; rdfs:label \"{a}\" ;
  wn:eq_synonym <https://en-word.net/id/oewn-02486953-n> ;
  dns:eqHypernym <https://en-word.net/id/oewn-02084071-n> .")))))))

(deftest synset-type-shape
  (testing "a lexicalized synset missing its type is a violation"
    (is (contains? (shape+constraint "
dn:synset-2 rdfs:label \"{a}\" ; ontolex:lexicalizedSense dn:sense-2 .")
                   [:dns/SynsetTypeShape :sh/ClassConstraintComponent])))
  (testing "a typed synset produces no type entries"
    (is (empty? (filter (comp #{:dns/SynsetTypeShape} :source-shape)
                        (:results (validate-ttl "
dn:synset-2 a ontolex:LexicalConcept ; rdfs:label \"{a}\" ;
  ontolex:lexicalizedSense dn:sense-2 .")))))))

(deftest validate-node-targeting
  (testing "targeted validation only checks the given focus node"
    (let [g (util/ttl->db (str prefixes "
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
             (map (juxt :source-shape :constraint-component)
                  (:results (shapes/validate-node g :dn/sense-2))))))))

(deftest validate-changes-gate
  (let [g      (util/ttl->db (str prefixes "
dn:entry-1 a ontolex:LexicalEntry ;
  rdfs:label \"hund\" ; wn:partOfSpeech wn:noun ;
  ontolex:sense dn:sense-1 ; ontolex:canonicalForm dn:form-1 ;
  skos:inScheme <https://wordnet.dk/dannet/data> .
dn:form-1 ontolex:writtenRep \"hund\"@da .
dn:entry-2 a ontolex:LexicalEntry ;
  rdfs:label \"kat\" ; wn:partOfSpeech wn:noun ;
  ontolex:sense dn:sense-3 ;
  ontolex:canonicalForm [ ontolex:writtenRep \"kat\"@da ] ;
  skos:inScheme <https://wordnet.dk/dannet/data> ."))
        before (set (theo/triples g))
        kat    #rdf/langstring ["kat" "da"]
        form   [[:dn/entry-2 :ontolex/canonicalForm '?f]
                ['?f :ontolex/writtenRep kat]]]
    (testing "retracting a required triple blocks the write"
      (let [result (shapes/validate-changes
                     g [{:retract #{[:dn/entry-1 :rdfs/label "hund"]}}])]
        (is (shacl/violations? result))
        (is (= [[:dn/entry-1 :dns/LexicalEntryShape-label]]
               (map (juxt :focus-node :source-shape) (:results result))))))
    (testing "a blank node is checked through the resource its :where starts from"
      (let [result (shapes/validate-changes
                     g [{:where   form
                         :retract #{['?f :ontolex/writtenRep kat]}}])]
        (is (= [[:dn/entry-2 :dns/LexicalEntryShape-writtenRep]]
               (map (juxt :focus-node :source-shape) (:results result)))))
      (is (:conforms? (shapes/validate-changes
                        g [{:where   form
                            :retract #{['?f :ontolex/writtenRep kat]}
                            :add     #{['?f :ontolex/writtenRep
                                        #rdf/langstring ["katte" "da"]]}}]))))
    (testing "a stale :where fails rather than validating something else"
      (is (thrown? clojure.lang.ExceptionInfo
                   (shapes/validate-changes
                     g [{:where   [[:dn/entry-2 :ontolex/canonicalForm '?f]
                                   ['?f :ontolex/writtenRep
                                    #rdf/langstring ["hund" "da"]]]
                         :retract #{['?f :ontolex/writtenRep
                                     #rdf/langstring ["hund" "da"]]}}]))))
    (testing "a conforming edit passes and the base graph is never mutated"
      (is (:conforms? (shapes/validate-changes
                        g [{:add #{[:dn/entry-1 :ontolex/sense :dn/sense-2]}}])))
      (is (:conforms? (shapes/validate-changes g [])))
      (is (= before (set (theo/triples g)))))))

(deftest editorial-rules
  ;; One well-formed entry + sense + synset, incl. an ontological type and a
  ;; hypernym; each testing block below then submits one offending edit.
  (let [g (util/ttl->db (str prefixes "
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
        entry-set (fn [triples]
                    (->> (shapes/validate-changes g [{:add triples}]) :results
                         (map (juxt :focus-node :source-shape :severity)) set))]
    (testing "a well-formed synset edit produces no entries"
      (is (:conforms? (shapes/validate-changes
                        g [{:add #{[:dn/synset-1 :wn/hypernym :dn/synset-2]}}]))))
    (testing "an unwrapped word label blocks"
      (is (contains? (entry-set #{[:dn/word-2 :rdf/type :ontolex/Word]
                                  [:dn/word-2 :rdfs/label (->LangString "kat" "da")]})
                     [:dn/word-2 :dns/EditorialWordShape-label :sh/Violation])))
    (testing "a part of speech outside the policy set blocks"
      (is (contains? (entry-set #{[:dn/word-2 :rdf/type :ontolex/Word]
                                  [:dn/word-2 :wn/partOfSpeech :wn/adverb]})
                     [:dn/word-2 :dns/EditorialWordShape-partOfSpeech :sh/Violation])))
    (testing "a dangling hypernym target blocks"
      (is (contains? (entry-set #{[:dn/synset-1 :wn/hypernym :dn/synset-404]})
                     [:dn/synset-1 :dns/EditorialHypernymTargetShape :sh/Violation])))
    (testing "a non-dnc member in a new ontological type blocks"
      (is (contains? (entry-set #{[:dnt/Fish-Object :rdf/type :dns/OntologicalType]
                                  [:dnt/Fish-Object :rdf/_1 :dnc/Object]
                                  [:dnt/Fish-Object :rdf/_2 :dn/synset-1]})
                     [:dnt/Fish-Object :dns/EditorialOntotypeShape :sh/Violation])))
    (testing "a brace-wrapped sense label blocks"
      (is (contains? (entry-set #{[:dn/sense-2 :rdf/type :ontolex/LexicalSense]
                                  [:dn/sense-2 :rdfs/label (->LangString "{fisk_§1}" "da")]})
                     [:dn/sense-2 :dns/EditorialSenseShape-label :sh/Violation])))
    (testing "a minimal new synset only warns (definition, ontotype, hypernym)"
      (let [result (shapes/validate-changes
                     g [{:add #{[:dn/synset-3 :rdf/type :ontolex/LexicalConcept]
                                [:dn/synset-3 :rdfs/label (->LangString "{kat}" "da")]
                                [:dn/synset-3 :ontolex/lexicalizedSense :dn/sense-1]
                                [:dn/synset-3 :skos/inScheme
                                 "<https://wordnet.dk/dannet/data>"]}}])]
        (is (not (shacl/violations? result)))
        (is (= #{:dns/EditorialConceptShape-definition
                 :dns/EditorialConceptShape-ontologicalType
                 :dns/EditorialConceptShape-hypernym}
               (set (map :source-shape (:results result)))))))
    (testing "re-adding existing triples never breaks maxCount constraints"
      ;; both for an IRI object and for a language-tagged literal
      (is (:conforms? (shapes/validate-changes
                        g [{:add #{[:dn/word-1 :ontolex/canonicalForm :dn/form-1]
                                   [:dn/word-1 :rdfs/label
                                    (->LangString "\"hund\"" "da")]}}]))))))

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
dn:entry-1 a ontolex:LexicalEntry ."))))))
    (testing "predicates that no schema defines abort the export"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"schema definition"
                            (shapes/validate-export!
                              (.getPath (->ttl-file "
dn:entry-1 a ontolex:LexicalEntry ;
  rdfs:label \"hund\" ; wn:partOfSpeech wn:noun ;
  ontolex:sense dn:sense-1 ; ontolex:canonicalForm dn:form-1 ;
  skos:inScheme <https://wordnet.dk/dannet/data> .
dn:form-1 ontolex:writtenRep \"hund\"@da .
dn:sense-1 a ontolex:LexicalSense ; rdfs:label \"hund\" ;
  skos:inScheme <https://wordnet.dk/dannet/data> .
<https://wordnet.dk/dannet/data> dn:build \"1-2\" ."))))))))

(deftest undefined-predicates
  (let [schema (util/ttl->graph (str prefixes "
dns:build rdfs:label \"build\"@en ."))
        data   (util/ttl->graph (str prefixes "
dn:x dn:build \"1-2\" ; dns:build \"1-2\" ;
  <http://rdfs.org/ns/void#triples> 2 ;
  <http://www.w3.org/1999/02/22-rdf-syntax-ns#_1> dn:y ."))]
    (testing "a predicate is undefined unless the schema labels it or it is allowed"
      (is (= ["https://wordnet.dk/dannet/data/build"]
             (shapes/undefined-predicates data schema))))))
