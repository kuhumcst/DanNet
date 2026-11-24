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

(deftest pages-of-new-entries
  (let [sense   (entry/new-sense [] :dn/synset-1 :dn/word-1 vovse)
        example [:dn/new-sense-1 :lexinfo/senseExample "en vovse"]
        changes [sense
                 {:add #{example}}
                 {:add #{[:dn/new-sense-1 :lexinfo/senseExample "to vovser"]}}
                 {:add #{[:dn/synset-1 :skos/definition "et dyr"]}}]]
    (testing "a new entry is what the changes say about it"
      (is (= {:rdf/type                     :ontolex/LexicalSense
              :rdfs/label                   vovse
              :lexinfo/senseExample         #{"en vovse" "to vovser"}
              :ontolex/isSenseOf            :dn/word-1
              :ontolex/isLexicalizedSenseOf :dn/synset-1}
             (entry/entity changes :dn/new-sense-1))
          "with the word and the synset that point to it")
      (is (= "en vovse"
             (:lexinfo/senseExample
               (entry/entity (change/without-addition
                               changes
                               [:dn/new-sense-1 :lexinfo/senseExample
                                "to vovser"])
                             :dn/new-sense-1)))
          "a single value stands alone")
      (is (nil? (entry/entity changes :dn/new-sense-2)))
      (is (nil? (entry/entity (change/without-node changes :dn/new-sense-1)
                              :dn/new-sense-1))
          "an entry that is taken away is gone"))
    (testing "the labels of the new entries"
      (is (= {:dn/new-sense-1 vovse} (entry/labels changes)))
      (is (= {} (entry/labels [{:add #{[:dn/synset-1 :rdfs/label "{x}"]}}]))))
    (testing "the page has what the server knows of the type"
      (let [content {:constraints {:rdfs/label {:must {:min 1}}}
                     :entities    {:rdfs/label {:rdfs/label #{"label"}}}}
            data    (entry/page-data :dn/new-sense-1 content)]
        (is (= content (select-keys data [:constraints :entities])))
        (is (= :dn/new-sense-1 (:subject data)))
        (is (:draft? data))
        (is (some? (:default-origin data))
            "so that its values can be edited")
        (is (= :inferred (get-in data [:origins :ontolex/isSenseOf]))
            "a link to the entry is locked, as on the page of a sense")))))
