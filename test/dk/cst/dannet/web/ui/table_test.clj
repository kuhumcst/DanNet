(ns dk.cst.dannet.web.ui.table-test
  "Tests for the origins of the rows in the attribute-value table and for
  the pending changes of an edit row."
  (:require [clojure.test :refer [deftest is]]
            [dk.cst.dannet.change :as change]
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

(deftest pending-changes
  (let [triple  (fn [v] [:dn/synset-1 :rdfs/label v])
        opts    {:subject        :dn/synset-1
                 :origins        {:rdfs/label {"c" :inferred}}
                 :default-origin :dn
                 :changes        (-> []
                                     (change/replace (triple "a") (triple "a2"))
                                     (change/retract (triple "b"))
                                     (change/add (triple "d"))
                                     (change/retract [:dn/synset-2 :rdfs/label "x"])
                                     (change/retract [:dn/synset-1 :rdfs/comment "y"]))}
        pending (table/pending opts :rdfs/label)]
    (is (= {:added        #{"a2" "d"}
            :retracted    #{"a" "b"}
            :replacements {"a" ["a2"]}}
           pending)
        "only the changes to the property of the subject")
    (is (= ["a2" "b" "d" "c"]
           (table/edit-order opts :rdfs/label #{"a" "b" "c"} pending))
        "a replacement in place of its value, additions before the locked")
    (is (= ["b" "c"]
           (table/edit-order
             opts :rdfs/label #{"a" "b" "c"}
             {:added #{"b"} :retracted #{"a"} :replacements {"a" ["b"]}}))
        "a replacement that is already a value is listed once")))

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
