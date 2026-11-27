(ns dk.cst.dannet.web.ui.origin-test
  "Tests for the origins of the values of a row."
  (:require [clojure.test :refer [deftest is]]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.web.ui.origin :as origin]))

(deftest row-origins
  (let [opts {:origins        {:wn/hyponym  :inferred
                               :wn/hypernym {:dn/synset-2 :inferred}
                               :rdfs/label  {"hund" #{:dn :other}}}
              :default-origin :dn}]
    (is (= #{:dn} (origin/row-origins opts :skos/definition "et dyr"))
        "the default origin of a property without an entry")
    (is (= #{:inferred}
           (origin/row-origins opts :wn/hyponym [:dn/synset-3 :dn/synset-4]))
        "one origin for every value")
    (is (= #{:dn :inferred}
           (origin/row-origins opts :wn/hypernym #{:dn/synset-1 :dn/synset-2})))
    (is (= #{:dn} (origin/row-origins opts :wn/hypernym #{:dn/synset-1}))
        "only the origins of the values shown")
    (is (= #{:dn :other} (origin/row-origins opts :rdfs/label "hund"))
        "a value that several graphs hold")))

(deftest members
  (let [opts {:entity         {:rdf/type :dns/OntologicalType
                               :rdf/_1   :dnc/Animal
                               :rdf/_2   #{:dnc/Object}
                               :rdf/_10  :dnc/Animal}
              :origins        {:rdf/_1  :other
                               :rdf/_2  :inferred
                               :rdf/_10 {:dnc/Animal #{:dn :other}}}
              :default-origin :dn}]
    (is (= [:rdf/_2] (origin/value-properties opts :rdfs/member :dnc/Object))
        "a member is held by its rdf:_N property")
    (is (= [:rdf/_1 :rdf/_10]
           (origin/value-properties opts :rdfs/member :dnc/Animal))
        "or by several, in the order of their ordinals")
    (is (= [:rdf/type] (origin/value-properties opts :rdf/type :dnc/Animal))
        "any other value is held by the property of its row")
    (is (= [:rdfs/member]
           (origin/value-properties opts :rdfs/member :dnc/Plant))
        "and so is a member that no rdf:_N property has")
    (is (= [true true false false false false]
           (map shared/member-property?
                [:rdf/_1 :rdf/_10 :rdf/_0 :rdf/_x :rdf/_ :rdfs/_1]))
        "only rdf:_ and a number from 1 is a member property")
    (is (= #{:dn :other} (origin/value-origins opts :rdfs/member :dnc/Animal))
        "a member has the origins of the properties that hold it")
    (is (= #{:dn :other :inferred}
           (origin/row-origins opts :rdfs/member [:dnc/Animal :dnc/Object])))))
