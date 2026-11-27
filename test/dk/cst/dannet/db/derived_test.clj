(ns dk.cst.dannet.db.derived-test
  "Tests for the values that follow from other values in the dn: graph."
  (:require [clojure.test :refer [deftest is testing]]
            [dk.cst.theophrastus :as theo]
            [dk.cst.dannet.db.bootstrap.metadata :as md]
            [dk.cst.dannet.db.derived :as derived]
            [dk.cst.dannet.editable :as editable]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.test-util :as util]))

(def entries
  "Two words with a sense each in one synset, as Turtle. The label of the
  synset leaves a sense out, one word lacks its link to the synset, and the
  other links to a synset that it has no sense in."
  "
@prefix ontolex: <http://www.w3.org/ns/lemon/ontolex#> .
@prefix rdfs:    <http://www.w3.org/2000/01/rdf-schema#> .
@prefix dn:      <https://wordnet.dk/dannet/data/> .
@prefix dns:     <https://wordnet.dk/dannet/schema/> .
dn:word-1 a ontolex:Word ; ontolex:sense dn:sense-1 ;
  ontolex:evokes dn:synset-1 , dn:synset-2 .
dn:word-2 a ontolex:Word ; ontolex:sense dn:sense-2 .
dn:sense-1 a ontolex:LexicalSense ; rdfs:label \"køter_§1\"@da .
dn:sense-2 a ontolex:LexicalSense ; rdfs:label \"hund_1§1\"@da .
dn:synset-1 a ontolex:LexicalConcept ; rdfs:label \"{køter_§1}\"@da ;
  ontolex:lexicalizedSense dn:sense-1 , dn:sense-2 .
dn:synset-2 a ontolex:LexicalConcept ; rdfs:label \"{TOP}\"@da .
dns:Gender a rdfs:Class .")

(defn graph
  "A db of the dn: graph of a new in-memory Dataset with the entries."
  []
  (theo/named-graph (util/ttl->dataset entries) prefix/dn-graph))

(def unchanged
  {:retract #{} :add #{}})

(def relabel
  {:retract #{[:dn/synset-1 :rdfs/label (md/da "{køter_§1}")]}
   :add     #{[:dn/synset-1 :rdfs/label (md/da "{hund_1§1; køter_§1}")]}})

(def relink
  {:retract #{[:dn/word-1 :ontolex/evokes :dn/synset-2]}
   :add     #{[:dn/word-2 :ontolex/evokes :dn/synset-1]}})

(deftest synset-labels
  (let [g (graph)]
    (is (= relabel (derived/synset-labels g))
        "a synset without senses keeps its label")
    (is (= relabel (derived/synset-labels g [:dn/synset-1 :dn/word-1])))
    (is (= unchanged (derived/synset-labels g [:dn/synset-2])))
    (is (= unchanged (derived/synset-labels g [])))
    (derived/write! g relabel)
    (is (= unchanged (derived/synset-labels g))
        "a label that says the same is left alone"))
  (testing "a synset without a label gets one"
    (let [g (graph)]
      (theo/retract! g (vec (:retract relabel)))
      (is (= (assoc relabel :retract #{}) (derived/synset-labels g))))))

(deftest evoked-synsets
  (let [g (graph)]
    (is (= relink (derived/evoked-synsets g)))
    (is (= (assoc relink :add #{}) (derived/evoked-synsets g [:dn/word-1])))
    (is (= (assoc relink :retract #{}) (derived/evoked-synsets g [:dn/word-2])))
    (is (= unchanged (derived/evoked-synsets g [])))
    (derived/write! g relink)
    (is (= unchanged (derived/evoked-synsets g)))))

(deftest in-scheme
  (let [g      (graph)
        scheme (fn [& subjects]
                 {:add (set (for [s subjects] [s :skos/inScheme md/<dn>]))})]
    (is (= (scheme :dn/word-1 :dn/word-2 :dn/sense-1 :dn/sense-2
                   :dn/synset-1 :dn/synset-2)
           (derived/in-scheme g prefix/dn-uri md/<dn>))
        "only the subjects in the namespace")
    (is (= (scheme :dn/word-1)
           (derived/in-scheme g prefix/dn-uri md/<dn>
                              [:dn/word-1 :dn/word-9 :dns/Gender]))
        "only the ones that the graph has")))

(deftest locked-properties
  (let [g       (graph)
        written (fn [{:keys [retract add] :as change}]
                  (into #{} (map second) (concat retract add)))
        {:keys [derived derived-by-class]} editable/rules]
    (is (= (disj derived :dns/inherited)
           (written (derived/in-scheme g prefix/dn-uri md/<dn>)))
        "what is written for every resource, besides the legacy dns:inherited")
    (is (= (disj (:ontolex/LexicalConcept derived-by-class) :dns/shortLabel)
           (written (derived/synset-labels g)))
        "for a synset, where the bootstrap makes the short label of the label")
    (doseq [class [:ontolex/Word :ontolex/MultiwordExpression :ontolex/Affix]]
      (is (= (get derived-by-class class)
             (written (derived/evoked-synsets g)))
          "and for a word of each class"))))

(deftest deriving
  (let [triples (fn [g s] (set (theo/triples g [s '_ '_])))]
    (testing "a sense counts for its synsets and its word"
      (let [g (graph)]
        (derived/derive! g [{:add #{[:dn/sense-2 :rdfs/comment "x"]}}])
        (is (contains? (triples g :dn/synset-1) (first (:add relabel))))
        (is (contains? (triples g :dn/word-2) (first (:add relink))))
        (is (contains? (triples g :dn/sense-2)
                       [:dn/sense-2 :skos/inScheme md/<dn>]))
        (is (contains? (triples g :dn/word-1) (first (:retract relink)))
            "a resource that the changes don't name is left alone")))
    (testing "the object of a changed triple counts too"
      (let [g (graph)]
        (derived/derive! g [{:retract #{[:dn/word-9 :ontolex/sense :dn/sense-1]}}])
        (is (not (contains? (triples g :dn/word-1) (first (:retract relink)))))))))
