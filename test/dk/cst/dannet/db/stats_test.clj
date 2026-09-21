(ns dk.cst.dannet.db.stats-test
  "Tests for the paper statistics on data small enough to count by hand."
  (:require [clojure.test :refer [deftest is testing]]
            [dk.cst.dannet.db.stats :as stats]
            [dk.cst.dannet.test-util :as util]))

(def legacy-relations
  "Legacy relations.csv rows: synset 1 under 2 (taxonomic), 3 under 2
  (nontaxonomic, i.e. orthogonal), 1 used for 3, and a Princeton link."
  [["1" "hyponymOf" "has_hyperonym" "2" "taxonomic" "" ""]
   ["3" "hyponymOf" "has_hyperonym" "2" "nontaxonomic" "" ""]
   ["1" "usedFor" "used_for" "3" "" "" ""]
   ["1" "eqSynonymOf" "eq_has_synonym" "dog%1:05:00::" "" "" ""]])

(deftest legacy-conversion
  (testing "relation names map onto the current relations"
    (is (= [["1" :wn/hypernym "2"]
            ["3" :dns/orthogonalHypernym "2"]
            ["1" :dns/usedFor "3"]]
           (stats/legacy-edges legacy-relations))))
  (testing "Princeton sense keys are not Danish senses"
    (is (stats/danish-sense? ["10" "20" "30" "" ""]))
    (is (not (stats/danish-sense? ["2" "None-None" "abnormal%3:00:00::" "" ""])))))

(deftest degrees
  (let [edges (stats/legacy-edges legacy-relations)
        d     (stats/degree-stats ["1" "2" "3" "4"] edges)]
    (is (= 4 (:synsets d)))
    (is (= 0.75 (:mean-out d)))
    (is (= 1.5 (:mean-degree d)))                           ; 1: 3, 2: 2, 3: 2, 4: 0
    (is (= 2.0 (:median-degree d)))
    (is (= 1 (:isolated d)))
    (is (= 1 (:taxonomic-only d))))                         ; 3 has only its orthogonal hypernym
  (testing "similar edges are split by their 2023 adjective endpoints"
    (is (= {2 1, 1 1}
           (stats/similar-breakdown [[:dn/synset-s1 :wn/similar :dn/synset-s2]
                                     [:dn/synset-1 :wn/similar :dn/synset-s2]
                                     [:dn/synset-1 :wn/hypernym :dn/synset-2]]))))
  (testing "the closure adds the missing inverses only"
    (let [inverses {:wn/hypernym :wn/hyponym :wn/hyponym :wn/hypernym}]
      (is (= 4 (stats/inverse-closure inverses [["1" :wn/hypernym "2"]
                                                ["2" :wn/hyponym "1"]
                                                ["3" :wn/hypernym "2"]]))))))

(def prefixes "
@prefix ontolex: <http://www.w3.org/ns/lemon/ontolex#> .
@prefix wn:      <https://globalwordnet.github.io/schemas/wn#> .
@prefix dn:      <https://wordnet.dk/dannet/data/> .
@prefix dns:     <https://wordnet.dk/dannet/schema/> .
@prefix dnt:     <https://wordnet.dk/dannet/types/> .
")

(def fixture
  "Three synsets: 1 and 3 under 2, 1 used for 3, an ontological type on 1
  (not a synset relation) and an ILI link on 2 (not an internal one)."
  "
dn:synset-1 a ontolex:LexicalConcept ; wn:hypernym dn:synset-2 ; dns:usedFor dn:synset-3 ;
  dns:ontologicalType dnt:Animal-Object .
dn:synset-2 a ontolex:LexicalConcept ; wn:ili <http://globalwordnet.org/ili/i1> .
dn:synset-3 a ontolex:LexicalConcept ; wn:hypernym dn:synset-2 .
")

(deftest current-queries
  (let [g (util/ttl->graph (str prefixes fixture))]
    (is (= {:wn/hypernym 2 :dns/usedFor 1}
           (frequencies (map second (stats/relation-edges g)))))
    (is (= 3 (stats/count-in g "?s a ontolex:LexicalConcept")))
    (is (= 2 (stats/count-in g "?s wn:hypernym ?o")))
    (is (= 1 (stats/count-in g "?s wn:hypernym ?o" "?o")))))

(deftest rendering
  (let [table {:header ["Measure" "Old" "New"]
               :rows   [["Synsets" 65670 70475]
                        ["Share" "1 (50.0%)" nil]]}]
    (is (= (str "| Measure | Old | New |\n"
                "| --- | --- | --- |\n"
                "| Synsets | 65670 | 70475 |\n"
                "| Share | 1 (50.0%) |  |")
           (stats/->markdown table)))
    (is (= (str "\\begin{tabular}{lrr}\n\\toprule\n"
                "Measure & Old & New \\\\\n\\midrule\n"
                "Synsets & 65670 & 70475 \\\\\n"
                "Share & 1 (50.0\\%) &  \\\\\n"
                "\\bottomrule\n\\end{tabular}")
           (stats/->latex table)))))
