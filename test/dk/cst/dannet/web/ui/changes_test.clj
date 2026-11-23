(ns dk.cst.dannet.web.ui.changes-test
  "Tests for the list of the pending changes as data: which entries it has,
  which rows each of them has, and the text that is submitted."
  (:require [clojure.test :refer [deftest is]]
            [dk.cst.theophrastus :as theo]
            [dk.cst.dannet.change :as change]
            [dk.cst.dannet.db.change :as db.change]
            [dk.cst.dannet.db.shapes :as shapes]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.test-util :as util]
            [dk.cst.dannet.web.ui.changes :as changes])
  (:import [java.io StringReader]))

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
    (let [nodes   (assoc nodes
                    '_b2 {:subject  :dn/word-1
                          :property :ontolex/otherForm
                          :values   {:ontolex/writtenRep #{hund}}}
                    '_new1 {:subject  :dn/word-1
                            :property :ontolex/otherForm
                            :new?     true})
          pending (-> []
                      (change/retract-node '_b2 (get nodes '_b2))
                      (change/add [:dn/word-1 :ontolex/otherForm '_new1])
                      (change/add ['_new1 :ontolex/writtenRep hunde]))
          row     (fn [status v]
                    [{:subject  (if (= status :added) '_new1 '_b2)
                      :property :ontolex/writtenRep
                      :fields   [{:property :ontolex/writtenRep
                                  :value    v
                                  :status   status}]}])]
      (is (= [{:entry :dn/word-1
               :rows  [{:subject  :dn/word-1
                        :property :ontolex/otherForm
                        :fields   [{:property :ontolex/otherForm
                                    :value    '_b2
                                    :status   :retracted
                                    :rows     (row :retracted hund)}
                                   {:property :ontolex/otherForm
                                    :value    '_new1
                                    :status   :added
                                    :rows     (row :added hunde)}]}]}]
             (changes/entries pending nodes))
          "a blank node that is added or removed has its rows in its field"))
    (is (empty? (changes/entries [] nodes)))
    (is (empty? (changes/entries
                  (-> []
                      (change/add [:dn/word-1 :wn/partOfSpeech :wn/noun])
                      (change/retract [:dn/word-1 :wn/partOfSpeech :wn/noun]))
                  nodes))
        "changes that cancel each other out leave no entry")))

(deftest problems
  (let [form    {:focus-node :dn/word-1 :path :ontolex/canonicalForm}
        pos     {:focus-node :dn/word-1 :path :wn/partOfSpeech}
        cycle   {:focus-node :dn/word-1 :path nil}
        other   {:focus-node :dn/synset-1 :path :wn/partOfSpeech}
        results [form pos cycle other]
        rows    [{:subject  '_b1
                  :holder   :ontolex/canonicalForm
                  :property :ontolex/writtenRep}
                 {:subject :dn/word-1 :property :wn/partOfSpeech}]]
    (is (= [form] (changes/row-problems results :dn/word-1 (first rows)))
        "a result about a blank node has the property that holds the node")
    (is (= [pos] (changes/row-problems results :dn/word-1 (second rows)))
        "a result about a row has its property, and its entry")
    (is (= [cycle] (changes/entry-problems results :dn/word-1 rows))
        "a result about no row is about the entry")))

(deftest submission
  (let [db      (util/ttl->dataset "
@prefix dn: <https://wordnet.dk/dannet/data/> .
@prefix ontolex: <http://www.w3.org/ns/lemon/ontolex#> .
dn:word-1 ontolex:canonicalForm [ ontolex:writtenRep \"hund\"@da ] .")
        dn      (theo/named-graph db prefix/dn-graph)
        [[_ _ form]] (theo/triples dn '[:dn/word-1 :ontolex/canonicalForm _])
        nodes   {form {:subject  :dn/word-1
                       :property :ontolex/canonicalForm
                       :values   {:ontolex/writtenRep #{hund}}}}
        pending (-> []
                    (change/replace [form :ontolex/writtenRep hund]
                                    [form :ontolex/writtenRep hunde])
                    (change/add [:dn/word-1 :wn/partOfSpeech :wn/noun]))
        text    (changes/submission pending nodes)
        read    (db.change/read-changes (StringReader. text))]
    (is (= (change/locate-nodes pending nodes) read)
        "the text reads back as the changes with their blank nodes located")
    (is (= (str "[{:where   [[:dn/word-1 :ontolex/canonicalForm ?node]]\n"
                "  :retract #{[?node :ontolex/writtenRep"
                " #rdf/langstring [\"hund\" \"da\"]]}\n"
                "  :add     #{[?node :ontolex/writtenRep"
                " #rdf/langstring [\"hunde\" \"da\"]]}}\n"
                " {:add #{[:dn/word-1 :wn/partOfSpeech :wn/noun]}}]")
           text)
        "each key of a change is on a line of its own")
    (is (:conforms? (shapes/validate-changes db read))
        "and the changes can be made to the data that they were made on")
    (let [nodes   (assoc nodes '_new1 {:subject  :dn/word-1
                                       :property :ontolex/otherForm
                                       :new?     true})
          pending (-> []
                      (change/add [:dn/word-1 :ontolex/otherForm '_new1])
                      (change/add ['_new1 :ontolex/writtenRep hunde])
                      (change/retract-node form (get nodes form)))
          text    (changes/submission pending nodes)]
      (is (= (str "[{:add #{[:dn/word-1 :ontolex/otherForm _new1]"
                  " [_new1 :ontolex/writtenRep"
                  " #rdf/langstring [\"hunde\" \"da\"]]}}\n"
                  " {:where   [[:dn/word-1 :ontolex/canonicalForm ?node]]\n"
                  "  :retract #{[:dn/word-1 :ontolex/canonicalForm ?node]"
                  " [?node :ontolex/writtenRep"
                  " #rdf/langstring [\"hund\" \"da\"]]}}]")
             text)
          "a new blank node is one change, and a removed one another")
      (is (= (change/locate-nodes pending nodes)
             (db.change/read-changes (StringReader. text)))))))
