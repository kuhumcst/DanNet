(ns dk.cst.dannet.db.bootstrap-test
  "Tests for the changes that a release build makes."
  (:require [clojure.test :refer [deftest is testing]]
            [dk.cst.theophrastus :as theo]
            [dk.cst.dannet.change :as change]
            [dk.cst.dannet.db.bootstrap :as bootstrap]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.test-util :as util])
  (:import [clojure.lang ExceptionInfo]
           [org.apache.jena.tdb2 TDB2Factory]))

(def entry
  "A word that conforms to the editorial shapes, as Turtle."
  "
@prefix ontolex: <http://www.w3.org/ns/lemon/ontolex#> .
@prefix rdfs:    <http://www.w3.org/2000/01/rdf-schema#> .
@prefix skos:    <http://www.w3.org/2004/02/skos/core#> .
@prefix wn:      <https://globalwordnet.github.io/schemas/wn#> .
@prefix dn:      <https://wordnet.dk/dannet/data/> .
dn:entry-1 a ontolex:LexicalEntry ;
  rdfs:label \"hund\" ; wn:partOfSpeech wn:noun ;
  ontolex:sense dn:sense-1 ; ontolex:canonicalForm dn:form-1 ;
  skos:inScheme <https://wordnet.dk/dannet/data> .
dn:form-1 ontolex:writtenRep \"hund\"@da .")

(def synset
  "A synset of two senses, as Turtle. Its label leaves one of them out, and
  the word of that sense lacks its link to the synset."
  "
@prefix ontolex: <http://www.w3.org/ns/lemon/ontolex#> .
@prefix rdfs:    <http://www.w3.org/2000/01/rdf-schema#> .
@prefix dn:      <https://wordnet.dk/dannet/data/> .
dn:synset-1 a ontolex:LexicalConcept ; rdfs:label \"{køter_§1}\"@da ;
  ontolex:lexicalizedSense dn:sense-1 , dn:sense-2 .
dn:sense-1 rdfs:label \"køter_§1\"@da .
dn:sense-2 rdfs:label \"hund_1§1\"@da .
dn:word-1 ontolex:sense dn:sense-1 ; ontolex:evokes dn:synset-1 .
dn:word-2 ontolex:sense dn:sense-2 .")

(defn datasets
  "A db of an in-memory Dataset and one of TDB2, both with the Turtle `ttl`
  in their dn: graph."
  [ttl]
  (for [db [(theo/db {:prefixes prefix/prefixes})
            (theo/db (TDB2Factory/createDataset) {:prefixes prefix/prefixes})]]
    (do (theo/transact! (theo/named-graph db prefix/dn-graph)
                        (util/ttl->graph ttl))
        db)))

(defn entry-datasets
  "The datasets with the entry in their dn: graph."
  []
  (datasets entry))

(defn senses
  "The senses of the entry in the dn: graph of `dataset`."
  [dataset]
  (set (map last (theo/triples (theo/named-graph dataset prefix/dn-graph)
                               [:dn/entry-1 :ontolex/sense '_]))))

(deftest release-changes
  (is (empty? (mapcat change/problems (bootstrap/release-changes)))
      "every change of this release is well-formed"))

(deftest applying-changes
  (let [add-sense     {:add #{[:dn/entry-1 :ontolex/sense :dn/sense-2]}}
        replace-sense {:retract #{[:dn/entry-1 :ontolex/sense :dn/sense-2]}
                       :add     #{[:dn/entry-1 :ontolex/sense :dn/sense-3]}}
        remove-label  {:retract #{[:dn/entry-1 :rdfs/label "hund"]}}
        stale         {:retract #{[:dn/entry-1 :rdfs/label "kat"]}}]
    (doseq [dataset (entry-datasets)]
      (testing "the changes are made in order"
        (bootstrap/apply-changes! dataset [add-sense replace-sense])
        (is (= #{:dn/sense-1 :dn/sense-3} (senses dataset)))))
    (doseq [dataset (entry-datasets)]
      (testing "a change that breaks an editorial rule stops them all"
        (is (thrown-with-msg? ExceptionInfo #"break the editorial rules"
                              (bootstrap/apply-changes!
                                dataset [add-sense remove-label])))
        (is (= #{:dn/sense-1} (senses dataset)))))
    (doseq [dataset (entry-datasets)]
      (testing "a stale change stops them all"
        (is (thrown-with-msg? ExceptionInfo #"not exactly once"
                              (bootstrap/apply-changes!
                                dataset [add-sense stale])))
        (is (= #{:dn/sense-1} (senses dataset)))))))

(deftest derived-values
  (doseq [dataset (datasets synset)]
    (bootstrap/add-in-scheme! dataset)
    (bootstrap/regenerate-synset-labels! dataset)
    (bootstrap/regenerate-evoked-synsets! dataset)
    (let [g      (theo/named-graph dataset prefix/dn-graph)
          values (fn [s p] (set (map last (theo/triples g [s p '_]))))]
      (is (= #{#rdf/langstring ["{hund_1§1; køter_§1}" "da"]}
             (values :dn/synset-1 :rdfs/label)))
      (is (= #{:dn/synset-1} (values :dn/word-2 :ontolex/evokes)))
      (is (= #{"<https://wordnet.dk/dannet/data>"}
             (values :dn/sense-2 :skos/inScheme))))))
