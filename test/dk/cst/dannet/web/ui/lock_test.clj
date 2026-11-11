(ns dk.cst.dannet.web.ui.lock-test
  "Tests for the origins of the values of a row and for which can be edited."
  (:require [clojure.test :refer [deftest is]]
            [dk.cst.dannet.web.ui.lock :as lock]))

(deftest row-origins
  (let [opts {:origins        {:wn/hyponym  :inferred
                               :wn/hypernym {:dn/synset-2 :inferred}
                               :rdfs/label  {"hund" #{:dn :other}}}
              :default-origin :dn}]
    (is (= #{:dn} (lock/row-origins opts :skos/definition "et dyr"))
        "the default origin of a property without an entry")
    (is (= #{:inferred}
           (lock/row-origins opts :wn/hyponym [:dn/synset-3 :dn/synset-4]))
        "one origin for every value")
    (is (= #{:dn :inferred}
           (lock/row-origins opts :wn/hypernym #{:dn/synset-1 :dn/synset-2})))
    (is (= #{:dn} (lock/row-origins opts :wn/hypernym #{:dn/synset-1}))
        "only the origins of the values shown")
    (is (= #{:dn :other} (lock/row-origins opts :rdfs/label "hund"))
        "a value that several graphs hold")))

(deftest locks
  (let [opts   {:origins        {:wn/hyponym      :inferred
                                 :skos/definition {"a dog" :supplemented}
                                 :rdfs/comment    #{:dn :other}
                                 :rdfs/seeAlso    :other}
                :default-origin :dn}
        synset (assoc opts :entity {:rdf/type :ontolex/LexicalConcept})]
    (is (lock/editable? opts :wn/hypernym :dn/synset-2)
        "a value of the graph that changes go to")
    (is (lock/editable? opts :rdfs/comment "x")
        "also when another graph holds it too")
    (is (not (lock/editable? {} :rdfs/label "hund"))
        "nothing without origins, as in a nested table")
    (is (nil? (lock/lock opts :skos/definition "en hund")))
    (is (= :inferred (lock/lock opts :wn/hyponym :dn/synset-3)))
    (is (= :supplemented (lock/lock opts :skos/definition "a dog")))
    (is (= :elsewhere (lock/lock opts :rdfs/seeAlso :dn/synset-4)))
    (is (= :derived (lock/lock opts :skos/inScheme :dn/x))
        "a property that the release build writes")
    (is (nil? (lock/lock opts :rdfs/label "hund"))
        "a label can be edited")
    (is (= :derived (lock/lock synset :rdfs/label "{hund_1§1}"))
        "but not that of a synset")
    (is (= :yet (lock/lock opts :dns/sentiment {:marl/polarityValue 1}))
        "a blank node")
    (is (= :yet (lock/lock opts :rdfs/member :dnc/Animal))
        "a folded member of a container")))
