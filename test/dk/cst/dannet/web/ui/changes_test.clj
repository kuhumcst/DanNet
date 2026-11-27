(ns dk.cst.dannet.web.ui.changes-test
  "Tests for the list of the pending changes as data: which sections it has,
  which rows each of them has, and the text that is submitted."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rum.core :as rum]
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

(deftest sections
  (let [nodes   {'_b1 {:subject  :dn/word-1
                       :property :ontolex/canonicalForm}}
        pending (-> []
                    (change/retract [:dn/synset-1 :wn/lexfile "noun.animal"])
                    (change/replace ['_b1 :ontolex/writtenRep hund]
                                    ['_b1 :ontolex/writtenRep hunde])
                    (change/add [:dn/synset-1 :wn/hypernym :dn/synset-2])
                    (change/add [:dn/word-1 :wn/partOfSpeech :wn/noun]))]
    (is (= [:dn/synset-1 :dn/word-1]
           (map :resource (changes/sections pending nodes)))
        "the resources in the order of their first change, with a blank node
        under the resource that holds it")
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
           (:rows (first (changes/sections pending nodes))))
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
           (:rows (second (changes/sections pending nodes))))
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
      (is (= [{:resource :dn/word-1
               :rows     [{:subject  :dn/word-1
                           :property :ontolex/otherForm
                           :fields   [{:property :ontolex/otherForm
                                       :value    '_b2
                                       :status   :retracted
                                       :rows     (row :retracted hund)}
                                      {:property :ontolex/otherForm
                                       :value    '_new1
                                       :status   :added
                                       :rows     (row :added hunde)}]}]}]
             (changes/sections pending nodes))
          "a blank node that is added or removed has its rows in its field"))
    (is (empty? (changes/sections [] nodes)))
    (is (empty? (changes/sections
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
        "a result about a row has its property, and its resource")
    (is (= [cycle] (changes/section-problems results :dn/word-1 rows))
        "a result about no row is about the resource")
    (is (= {:dn/synset-1 [other]}
           (changes/other-problems results
                                   [{:resource :dn/word-1 :rows rows}]))
        "a result about a resource without changes is listed by that one")
    (is (empty? (changes/other-problems results [{:resource :dn/word-1}
                                                 {:resource :dn/synset-1}])))))

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
             (db.change/read-changes (StringReader. text)))))
    (is (= (str "[{:add #{[:dn/new-sense-1 :rdf/type :ontolex/LexicalSense]"
                " [:dn/new-sense-1 :rdfs/label"
                " #rdf/langstring [\"hund\" \"da\"]]"
                " [:dn/word-1 :ontolex/sense :dn/new-sense-1]}}\n"
                " {:add #{[:dn/word-1 :wn/partOfSpeech :wn/noun]}}]")
           (changes/submission
             [{:add #{[:dn/new-sense-1 :rdf/type :ontolex/LexicalSense]
                      [:dn/word-1 :ontolex/sense :dn/new-sense-1]}}
              {:add #{[:dn/word-1 :wn/partOfSpeech :wn/noun]}}
              {:add #{[:dn/new-sense-1 :rdfs/label hund]}}]
             {}))
        "a new entry is one change, with what is added to it later")))

(deftest removed-entries
  (let [removal {:retract #{[:dn/sense-1 :rdf/type :ontolex/LexicalSense]
                            [:dn/sense-1 :rdfs/label hund]
                            [:dn/word-1 :ontolex/sense :dn/sense-1]
                            [:dn/synset-1 :ontolex/lexicalizedSense
                             :dn/sense-1]}}
        pending [{:add #{[:dn/synset-1 :skos/definition "et dyr"]}} removal]
        rows    (into {}
                      (map (juxt :resource :rows))
                      (changes/sections pending {}))
        removed (fn [entry]
                  (for [{:keys [property fields]} (get rows entry)
                        {:keys [value status]}    fields
                        :when                     (= status :retracted)]
                    [property value]))]
    (is (= [[:rdf/type :ontolex/LexicalSense] [:rdfs/label hund]]
           (removed :dn/sense-1))
        "an entry that is removed as a whole lists every value that it had")
    (is (= [[:ontolex/sense :dn/sense-1]] (removed :dn/word-1))
        "and each entry that pointed to it lists the link that it loses")
    (is (= [[:ontolex/lexicalizedSense :dn/sense-1]] (removed :dn/synset-1)))
    (is (= (str "[{:add #{[:dn/synset-1 :skos/definition \"et dyr\"]}}\n"
                " {:retract #{[:dn/sense-1 :rdf/type :ontolex/LexicalSense]"
                " [:dn/sense-1 :rdfs/label #rdf/langstring [\"hund\" \"da\"]]"
                " [:dn/synset-1 :ontolex/lexicalizedSense :dn/sense-1]"
                " [:dn/word-1 :ontolex/sense :dn/sense-1]}}]")
           (changes/submission pending {}))
        "the removal is submitted as one change")
    (testing "two removals that share an entry"
      (let [word    (update removal :retract conj
                            [:dn/word-1 :rdf/type :ontolex/Word])
            synset  (update removal :retract conj
                            [:dn/synset-1 :rdf/type :ontolex/LexicalConcept])
            pending [word synset]
            shown   (for [{:keys [rows]} (changes/sections pending {})
                          {:keys [subject property fields]} rows
                          {:keys [value]} fields]
                      [subject property value])]
        (is (= (into (:retract word) (:retract synset)) (set shown)))
        (is (apply distinct? shown)
            "are listed with each of their triples once")
        (is (= 1 (count (re-seq #":retract" (changes/submission pending {}))))
            "and are submitted as one change")))))

(deftest unreferenced-resources
  (let [removal {:retract     #{[:dn/synset-1 :rdf/type
                                 :ontolex/LexicalConcept]}
                 :unreference #{:dn/synset-1}}
        opts    {:languages ["da" "en"]
                 :changes   [removal]
                 :nodes     {}
                 :k->label  {:wn/hypernym "har overbegreb"}}
        html    #(rum/render-static-markup (changes/changes-list %))
        result  (fn [n]
                  {:focus-node (keyword "dn" (str "synset-9" n))
                   :severity   :sh/Warning
                   :message    "should have a hypernym"})]
    (is (= [:dn/synset-1] (map :resource (changes/sections [removal] {})))
        "a resource that only refers to a removed one has no section")
    (is (str/includes? (changes/submission [removal] {})
                       ":unreference #{:dn/synset-1}")
        "the submitted change has the rule, and not the references")
    (is (not (str/includes? (html opts) "Henvisninger hertil"))
        "the references aren't counted before the server has said")
    (is (str/includes?
          (html (assoc opts :references
                            {:ids    #{:dn/synset-1}
                             :counts {:dn/synset-1 {:wn/hypernym 39
                                                    :wn/also     2}}}))
          "Henvisninger hertil fjernes også: 39 × har overbegreb, 2 × also")
        "then the removed resource says how many go, by property")
    (is (not (str/includes?
               (html (assoc opts
                       :changes [(dissoc removal :unreference)]
                       :references {:ids    #{:dn/synset-1}
                                    :counts {:dn/synset-1 {:wn/hypernym 39}}}))
               "Henvisninger hertil"))
        "a count that was made for other changes isn't shown")
    (testing "what the check says about other resources"
      (let [few  (html (assoc opts :check {:results (map result (range 3))}))
            many (html (assoc opts :check {:results (map result (range 50))}))]
        (is (= 3 (count (re-seq #"should have a hypernym" few)))
            "is listed for each of a few")
        (is (= 1 (count (re-seq #"should have a hypernym" many)))
            "and summed up by message for many")
        (is (str/includes? many "Ressourcer: 50"))))))
