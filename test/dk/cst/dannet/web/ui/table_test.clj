(ns dk.cst.dannet.web.ui.table-test
  "Tests for the origins of the rows in the attribute-value table."
  (:require [clojure.test :refer [deftest is]]
            [dk.cst.dannet.web.ui.table :as table]))

(deftest row-origins
  (let [opts {:origins        {:wn/hyponym  :inferred
                               :wn/hypernym {:dn/synset-2 :inferred}
                               :rdfs/label  {"hund" #{:dn :other}}}
              :default-origin :dn}]
    (is (= #{:dn} (table/row-origins opts :skos/definition "et dyr"))
        "the default origin of a property without an entry")
    (is (= #{:inferred}
           (table/row-origins opts :wn/hyponym [:dn/synset-3 :dn/synset-4]))
        "one origin for every value")
    (is (= #{:dn :inferred}
           (table/row-origins opts :wn/hypernym #{:dn/synset-1 :dn/synset-2})))
    (is (= #{:dn} (table/row-origins opts :wn/hypernym #{:dn/synset-1}))
        "only the origins of the values shown")
    (is (= #{:dn :other} (table/row-origins opts :rdfs/label "hund"))
        "a value that several graphs hold")))

(deftest locks
  (let [opts {:origins        {:wn/hyponym      :inferred
                               :skos/definition {"a dog" :supplemented}
                               :rdfs/comment    #{:dn :other}
                               :rdfs/seeAlso    :other}
              :default-origin :dn}]
    (is (table/editable? opts :wn/hypernym :dn/synset-2)
        "a value of the graph that changes go to")
    (is (table/editable? opts :rdfs/comment "x")
        "also when another graph holds it too")
    (is (nil? (table/lock opts :skos/definition "en hund")))
    (is (= :inferred (table/lock opts :wn/hyponym :dn/synset-3)))
    (is (= :supplemented (table/lock opts :skos/definition "a dog")))
    (is (= :elsewhere (table/lock opts :rdfs/seeAlso :dn/synset-4)))
    (is (not (table/editable? {} :rdfs/label "hund"))
        "nothing without origins, as in a nested table")))
