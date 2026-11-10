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
