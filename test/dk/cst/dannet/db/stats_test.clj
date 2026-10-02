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

(deftest outcomes
  (let [inverses {:wn/hypernym :wn/hyponym :wn/hyponym :wn/hypernym}
        edges    [[:dn/synset-2 :wn/hyponym :dn/synset-1]
                  [:dn/synset-2 :dns/orthogonalHypernym :dn/synset-3]
                  [:dn/synset-1 :wn/attribute :dn/synset-3]]
        outcomes (stats/relation-outcomes inverses legacy-relations edges #{})]
    (testing "a relation is kept (also as its inverse), reversed or replaced"
      (is (= {"hyponymOf"              {:kept 1}
              "hyponymOf/nontaxonomic" {:reversed 1}
              "usedFor"                {#{:wn/attribute} 1}}
             outcomes)))
    (testing "a relation that no longer links its synsets is removed"
      (is (= {"hyponymOf"              {:removed 1}
              "hyponymOf/nontaxonomic" {:removed 1}
              "usedFor"                {:removed 1}}
             (stats/relation-outcomes inverses legacy-relations [] #{}))))
    (testing "a relation that the data now denies is negated"
      (is (= {"usedFor" {:negated 1}}
             (-> (stats/relation-outcomes inverses legacy-relations []
                                          #{[:dn/synset-1 :dns/usedFor
                                             :dn/synset-3]})
                 (select-keys ["usedFor"])))))
    (testing "the table lists the changes after the current relation"
      (is (= ["`used_for`" "1" "`wn:instrument`" "`dns:usedFor`"
              "`dns:usedFor`; 1 now `wn:attribute`"]
             (->> (:rows (stats/mapping-table legacy-relations outcomes "x"))
                  (some #(when (= "`used_for`" (first %)) %))
                  (take 5)))))))

(deftest mapping-groups
  (let [summary (stats/mapping-summary legacy-relations)]
    (testing "each DanNet 2.2 relation falls in one group"
      (is (= {:wn 23 :dns 6 :dropped 1}
             (update-vals (select-keys summary [:wn :dns :dropped]) :relations))))
    (testing "rows count per group and subgroup, and as a share of all rows"
      (is (= [4 1 1 2 50.0]
             [(get-in summary [:total :rows])
              (get-in summary [:wn :direct :rows])
              (get-in summary [:wn :pwn :rows])
              (get-in summary [:dns :dannet :rows])
              (get-in summary [:dns :percent])])))))

(deftest resource-links
  (let [current {:links    [["Senses with a DDO source" 5]]
                 :external 3
                 :ili      2}
        links   {:origins {:wn/eq_synonym {:legacy 1 :new 1}
                           :dns/eqSimilar {:new 1}
                           :wn/ili        {:legacy 1 :new 1}}}
        rows    (into {} (map (juxt :resource identity))
                      (stats/links-summary current links))]
    (testing "a resource counts its links by a link-counts label"
      (is (= 5 (get-in rows ["DDO" :links]))))
    (testing "the OEWN and ILI links split by their DanNet 2.2 origin"
      (is (= {:links 3 :legacy 1 :new 2}
             (select-keys (rows "OEWN") [:links :legacy :new])))
      (is (= {:links 2 :legacy 1 :new 1}
             (select-keys (rows "ILI") [:links :legacy :new]))))))

(deftest links
  (let [rows    [["1" "eqSynonymOf" "eq_has_synonym" "dog%1:05:00::" "" "" ""]
                 ["2" "eqSynonymOf" "eq_has_synonym" "ENG20-02084071-n" "" "" ""]
                 ["3" "eqSynonymOf" "eq_has_synonym" "cat%1:05:00::" "" "" ""]]
        targets {"dog%1:05:00::"    #{:en/oewn-1}
                 "ENG20-02084071-n" #{:ili/i1 :en/oewn-1}}
        links   [[:dn/synset-1 :wn/eq_synonym :en/oewn-1]
                 [:dn/synset-1 :wn/ili :ili/i1]
                 [:dn/synset-2 :wn/ili :ili/i1]
                 [:dn/synset-2 :dns/eqSimilar :en/oewn-1]
                 [:dn/synset-4 :wn/ili :ili/i2]]]
    (testing "the intended relation wins, then wn:ili; no target is unmapped"
      (is (= {"eqSynonymOf" {:wn/eq_synonym 1 :wn/ili 1 :unmapped 1}}
             (stats/legacy-link-outcomes targets links rows))))
    (testing "a current link is legacy when an old link had its concept"
      (is (= {:wn/eq_synonym {:legacy 1}
              :wn/ili        {:new 2 :legacy 1}
              :dns/eqSimilar {:legacy 1}}
             (stats/link-origins targets links rows))))))

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
@prefix owl:     <http://www.w3.org/2002/07/owl#> .
")

(def fixture
  "Three synsets: 1 and 3 under 2, 1 used for 3, an ontological type on 1
  (not a synset relation), an ILI link on 2 (not an internal one) and the id
  of a removed synset that 2 subsumed (not a relation)."
  "
dn:synset-1 a ontolex:LexicalConcept ; wn:hypernym dn:synset-2 ; dns:usedFor dn:synset-3 ;
  dns:ontologicalType dnt:Animal-Object .
dn:synset-2 a ontolex:LexicalConcept ; wn:ili <http://globalwordnet.org/ili/i1> ;
  dns:subsumed dn:synset-4 .
dn:synset-3 a ontolex:LexicalConcept ; wn:hypernym dn:synset-2 .
")

(deftest current-queries
  (let [g (util/ttl->graph (str prefixes fixture))]
    (is (= {:wn/hypernym 2 :dns/usedFor 1}
           (frequencies (map second (stats/relation-edges g)))))
    (is (= 3 (stats/count-in g "?s a ontolex:LexicalConcept")))
    (is (= 2 (stats/count-in g "?s wn:hypernym ?o")))
    (is (= 1 (stats/count-in g "?s wn:hypernym ?o" "?o"))))
  (testing "a negation surfaces as the edge it denies"
    (is (= [[:dn/synset-3 :dns/usedFor :dn/synset-1]]
           (stats/negated-edges (util/ttl->graph (str prefixes "
[] a owl:NegativePropertyAssertion ; owl:sourceIndividual dn:synset-3 ;
  owl:assertionProperty dns:usedFor ; owl:targetIndividual dn:synset-1 .")))))))

(deftest cleanup-comparisons
  (let [before (util/ttl->graph (str prefixes "
dn:synset-1 dns:crossPoSHypernym dn:synset-9 .
dn:synset-2 dns:crossPoSHypernym dn:synset-9 .
dn:synset-3 dns:crossPoSHypernym dn:synset-9 .
dn:synset-4 wn:mero_part dn:synset-5 .
dn:synset-6 ontolex:lexicalizedSense dn:sense-1 .
dn:synset-7 ontolex:lexicalizedSense dn:sense-1 ."))
        after  (util/ttl->graph (str prefixes "
dn:synset-1 wn:attribute dn:synset-9 .
dn:synset-2 dns:crossPoSHypernym dn:synset-9 ."))]
    (testing "a cross-PoS hypernym is grouped by what links its pair after"
      (is (= {:attribute [[:dn/synset-1 :dn/synset-9]]
              :kept      [[:dn/synset-2 :dn/synset-9]]
              :removed   [[:dn/synset-3 :dn/synset-9]]}
             (stats/crosspos-outcomes before after))))
    (testing "a triple that the later release no longer has is removed"
      (is (= #{[:dn/synset-4 :wn/mero_part :dn/synset-5]}
             (stats/removed-triples before after stats/part-whole-relations))))
    (testing "a sense of two synsets is shared"
      (is (= [[:dn/sense-1 #{:dn/synset-6 :dn/synset-7}]]
             (stats/shared-senses before))))
    (testing "a hypernym pair is cross-PoS when its words disagree in PoS"
      (is (= #{[:dn/synset-1 :dn/synset-2]}
             (stats/cross-pos-hypernyms (util/ttl->graph (str prefixes "
dn:synset-1 wn:hypernym dn:synset-2 ; ontolex:lexicalizedSense dn:sense-1 .
dn:synset-2 ontolex:lexicalizedSense dn:sense-2 .
dn:word-1 ontolex:sense dn:sense-1 ; wn:partOfSpeech wn:noun .
dn:word-2 ontolex:sense dn:sense-2 ; wn:partOfSpeech wn:verb ."))))))
    (testing "an item is legacy when all its synsets were in DanNet 2.2"
      (is (= {:count 2 :legacy 1 :new 1}
             (stats/by-origin #{"1" "9"} identity [[:dn/synset-1 :dn/synset-9]
                                                   [:dn/synset-2 :dn/synset-9]]))))))

(deftest new-synsets
  (let [g (util/ttl->graph (str prefixes "
dn:synset-1 a ontolex:LexicalConcept ; wn:similar dn:synset-s2 .
dn:synset-s2 a ontolex:LexicalConcept ; wn:ili <http://globalwordnet.org/ili/i1> ."))]
    (testing "a synset not in DanNet 2.2 is new, with its relations and links"
      (is (= {:synsets 1 :supplement-2023 1 :relations 1 :similar 1
              :mean-degree 1.0 :oewn-links 0 :ili-links 1}
             (stats/new-synset-stats g #{"1"}))))))

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
