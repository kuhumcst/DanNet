(ns dk.cst.dannet.web.ui.edit.entry-test
  "Tests for the changes that make new entries."
  (:require [clojure.test :refer [deftest is testing]]
            [dk.cst.dannet.change :as change]
            [dk.cst.dannet.web.ui.edit.entry :as entry]))

(def ^:private vovse #rdf/langstring ["vovse" "da"])

(deftest new-senses
  (let [sense   (entry/new-sense [] :dn/synset-1 :dn/word-1 vovse)
        changes [sense
                 {:add #{[:dn/synset-1 :skos/definition "et dyr"]}}]
        second  (entry/new-sense changes :dn/synset-2 :dn/word-2 vovse)]
    (is (= {:add #{[:dn/new-sense-1 :rdf/type :ontolex/LexicalSense]
                   [:dn/new-sense-1 :rdfs/label vovse]
                   [:dn/word-1 :ontolex/sense :dn/new-sense-1]
                   [:dn/synset-1 :ontolex/lexicalizedSense :dn/new-sense-1]}}
           sense)
        "a sense is the link between a word and a synset")
    (is (empty? (change/problems sense)))
    (is (contains? (:add second)
                   [:dn/synset-2 :ontolex/lexicalizedSense :dn/new-sense-2])
        "under an ID that the pending changes don't have yet")
    (testing "the label of a new entry is the one that the changes leave it"
      (is (= vovse (entry/label changes :dn/new-sense-1)))
      (is (= "vovse_§2"
             (entry/label (change/replace changes
                                          [:dn/new-sense-1 :rdfs/label vovse]
                                          [:dn/new-sense-1 :rdfs/label "vovse_§2"])
                          :dn/new-sense-1)))
      (is (nil? (entry/label changes :dn/new-sense-2))))
    (testing "the words that get a new sense in a synset"
      (is (= #{:dn/word-1} (entry/sense-words changes :dn/synset-1)))
      (is (= #{} (entry/sense-words changes :dn/synset-2)))
      (is (= #{:dn/word-2}
             (entry/sense-words (conj changes second) :dn/synset-2)))
      (is (= #{}
             (entry/sense-words (change/without-node changes :dn/new-sense-1)
                                :dn/synset-1))
          "not one whose new sense is taken away"))))
