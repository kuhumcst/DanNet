(ns dk.cst.dannet.db.search-test
  "Tests for the search for words."
  (:require [clojure.test :refer [deftest is]]
            [dk.cst.dannet.db.search :as search]
            [dk.cst.dannet.test-util :as util]))

(def words
  "Two words with the lemma hund, a word that starts with it, and a word of
  another dataset with the same lemma, as Turtle."
  "
@prefix ontolex: <http://www.w3.org/ns/lemon/ontolex#> .
@prefix rdfs:    <http://www.w3.org/2000/01/rdf-schema#> .
@prefix wn:      <https://globalwordnet.github.io/schemas/wn#> .
@prefix owl:     <http://www.w3.org/2002/07/owl#> .
@prefix lexinfo: <http://www.lexinfo.net/ontology/3.0/lexinfo#> .
@prefix dn:      <https://wordnet.dk/dannet/data/> .
@prefix cor:     <https://ordregister.dk/id/> .
dn:word-1 rdfs:label \"\\\"hund\\\"\"@da ; wn:partOfSpeech wn:noun ;
  ontolex:canonicalForm [ ontolex:writtenRep \"hund\"@da ] ;
  ontolex:sense dn:sense-1 , dn:sense-2 .
dn:word-2 rdfs:label \"\\\"hund\\\"\"@da ; wn:partOfSpeech wn:noun ;
  ontolex:canonicalForm [ ontolex:writtenRep \"hund\"@da ] ;
  ontolex:sense dn:sense-3 .
dn:word-3 rdfs:label \"\\\"hunde\\\"\"@da ; wn:partOfSpeech wn:verb ;
  ontolex:canonicalForm [ ontolex:writtenRep \"hunde\"@da ] ;
  ontolex:sense dn:sense-4 .
cor:COR.1 rdfs:label \"hund\"@da ;
  ontolex:canonicalForm [ ontolex:writtenRep \"hund\"@da ] .
dn:sense-1 rdfs:label \"hund_2§1\"@da .
dn:sense-2 rdfs:label \"hund_2§1a\"@da .
dn:sense-3 rdfs:label \"hund_1§1\"@da .
dn:sense-4 rdfs:label \"hunde_1§1\"@da .
wn:noun rdfs:label \"noun\"@en ; owl:sameAs lexinfo:noun .
lexinfo:noun rdfs:label \"substantiv\"@da .")

(deftest finding-words
  (let [g       (util/ttl->db words)
        hund    #rdf/langstring ["hund" "da"]
        label   #rdf/langstring ["\"hund\"" "da"]
        noun    {:wn/noun #{#rdf/langstring ["noun" "en"]
                            #rdf/langstring ["substantiv" "da"]}}
        results (search/words g g ["hunde" "hund" "kat"])]
    (is (= [:dn/word-3 :dn/word-2 :dn/word-1] (map :rdf/about results))
        "in the order of the lemmas, and then of the numbers from DDO")
    (is (= {:rdf/about        :dn/word-2
            :rdfs/label       label
            :lemma            hund
            :homograph        "1"
            :wn/partOfSpeech  :wn/noun
            :labels           noun}
           (second results)))
    (is (= "2" (:homograph (last results))))
    (is (= noun (:labels (second results)))
        "a part of speech has the labels of the value that it is the same as")
    (is (= {:wn/verb #{}} (:labels (first results)))
        "a part of speech can be without a label")
    (is (not (contains? (first results) :homograph))
        "a word that has its lemma to itself needs no number")
    (is (empty? (search/words g g ["kat"])))))
