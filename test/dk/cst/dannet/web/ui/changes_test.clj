(ns dk.cst.dannet.web.ui.changes-test
  "Tests for the list of the pending changes as data: which entries it has
  and which rows each of them has."
  (:require [clojure.test :refer [deftest is]]
            [dk.cst.dannet.change :as change]
            [dk.cst.dannet.web.ui.changes :as changes]))

(def ^:private hund #rdf/langstring ["hund" "da"])
(def ^:private hunde #rdf/langstring ["hunde" "da"])

(deftest entries
  (let [nodes   {'_b1 {:subject  :dn/word-1
                       :property :ontolex/canonicalForm}}
        pending (-> []
                    (change/retract [:dn/synset-1 :wn/lexfile "noun.animal"])
                    (change/replace ['_b1 :ontolex/writtenRep hund]
                                    ['_b1 :ontolex/writtenRep hunde])
                    (change/add [:dn/synset-1 :wn/hypernym :dn/synset-2])
                    (change/add [:dn/word-1 :wn/partOfSpeech :wn/noun]))]
    (is (= [:dn/synset-1 :dn/word-1]
           (map :entry (changes/entries pending nodes)))
        "the entries in the order of their first change, with a blank node
        under the entry that holds it")
    (is (= [{:subject  :dn/synset-1
             :property :wn/hypernym
             :fields   [{:property :wn/hypernym
                         :value    :dn/synset-2
                         :status   :added}]}
            {:subject  :dn/synset-1
             :property :wn/lexfile
             :fields   [{:property :wn/lexfile
                         :value    "noun.animal"
                         :status   :retracted}]}]
           (:rows (first (changes/entries pending nodes))))
        "a row for each property that is changed, with its values as fields")
    (is (= [{:subject  '_b1
             :holder   :ontolex/canonicalForm
             :property :ontolex/writtenRep
             :fields   [{:property :ontolex/writtenRep
                         :value    hunde
                         :status   :edited
                         :original hund}]}
            {:subject  :dn/word-1
             :property :wn/partOfSpeech
             :fields   [{:property :wn/partOfSpeech
                         :value    :wn/noun
                         :status   :added}]}]
           (:rows (second (changes/entries pending nodes))))
        "the row of a blank node names the property that holds the node, and
        a value that replaces another has the one that it replaces")
    (is (empty? (changes/entries [] nodes)))
    (is (empty? (changes/entries
                  (-> []
                      (change/add [:dn/word-1 :wn/partOfSpeech :wn/noun])
                      (change/retract [:dn/word-1 :wn/partOfSpeech :wn/noun]))
                  nodes))
        "changes that cancel each other out leave no entry")))
