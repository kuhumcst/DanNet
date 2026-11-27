(ns dk.cst.dannet.web.ui.edit.removal-test
  "Tests for the flow that removes a resource of the data: its steps, and
  the change that the answers give."
  (:require [clojure.test :refer [deftest is testing]]
            [dk.cst.dannet.web.ui.edit.removal :as removal]))

(def parts
  "The removal of a synset whose sense is the only one of its word, as the
  server has it."
  {:triples    #{[:dn/synset-1 :rdf/type :ontolex/LexicalConcept]
                 [:dn/synset-1 :ontolex/lexicalizedSense :dn/sense-1]
                 [:dn/sense-1 :rdf/type :ontolex/LexicalSense]
                 [:dn/word-1 :ontolex/sense :dn/sense-1]}
   :references {:wn/hypernym 2 :dns/inheritedFrom 1}
   :nodes      {}
   :orphans    {:dn/word-1
                {:triples    #{[:dn/word-1 :rdf/type :ontolex/Word]
                               [:dn/word-1 :ontolex/canonicalForm '_b2]}
                 :references {}
                 :nodes      {'_b2 {:subject  :dn/word-1
                                    :property :ontolex/canonicalForm}}}}})

(deftest answers-and-changes
  (let [answers (removal/answers parts)
        whole   (into (:triples parts)
                      (get-in parts [:orphans :dn/word-1 :triples]))]
    (is (= {:dn/word-1 true} answers)
        "the orphans go too, until the editor says no")
    (is (= {:retract     whole
            :unreference #{:dn/synset-1 :dn/sense-1 :dn/word-1}}
           (removal/removal parts answers))
        "the change names what goes, and has a rule for what refers to it")
    (is (= {:retract     (:triples parts)
            :unreference #{:dn/synset-1 :dn/sense-1}}
           (removal/removal parts {:dn/word-1 false}))
        "an orphan that isn't chosen stays, and so do the references to it")
    (is (= #{'_b2} (set (keys (removal/nodes parts))))
        "the places of the blank nodes of every part are kept")))

(deftest steps
  (let [steps    #(removal/steps ["da" "en"] :dn/synset-1 %1 %2)
        answers  (removal/answers parts)
        [orphans confirm :as all] (steps parts answers)]
    (is (= ["Kan ikke stå alene" "Bekræft fjernelsen"] (map :heading all)))
    (testing "a step asks about the resources that can't stay alone"
      (is (= [{:value 1 :label "kan ikke stå alene"}] (:figures orphans)))
      (is (= [{:key :dn/word-1 :label :dn/word-1}] (:choices orphans))))
    (testing "the last step sums up what goes"
      (is (= [{:value 3 :label "ressourcer"}
              {:value 3
               :label "henvisninger"
               :title "Henvisninger fra andre ressourcer"}
              {:value 9 :label "værdier"}]
             (:figures confirm))
          "as figures of how much in all, with the references that go too")
      (is (= [{:summary "Vis ressourcerne"
               :items   [:dn/synset-1 :dn/sense-1 :dn/word-1]}
              {:summary "Vis henvisningerne"
               :items   [["2 ×" :wn/hypernym] ["1 ×" :dns/inheritedFrom]]}]
             (:details confirm))
          "with every resource as details, the one that was asked for
          first, and the references by property")
      (is (empty? (:choices confirm))
          "the references aren't a choice: they go with what they point to")
      (is (nil? (:warning confirm))))
    (testing "the steps follow the answers"
      (is (= [:dn/synset-1 :dn/sense-1]
             (:items (first (:details (peek (steps parts {}))))))
          "an orphan that isn't chosen stays"))
    (testing "a removal that nothing depends on only has to be confirmed"
      (let [alone {:triples #{[:dn/sense-1 :rdf/type :ontolex/LexicalSense]}}
            [confirm :as all] (steps alone {})]
        (is (= ["Bekræft fjernelsen"] (map :heading all)))
        (is (= [{:value 1 :label "ressource"} {:value 1 :label "værdi"}]
               (:figures confirm))
            "one of something is named in the singular")
        (is (empty? (:details confirm))
            "and one resource that nothing refers to has no details")))
    (testing "a large removal says so"
      (let [many (assoc parts :references {:wn/agent removal/large})]
        (is (some? (:warning (peek (steps many answers)))))))))

(deftest flows
  (let [{:keys [title about answers steps-fn done]}
        (removal/flow ["da" "en"] :dn/synset-1 parts)]
    (is (= ["Fjern ressourcen" :dn/synset-1 "Fjern"] [title about done])
        "the flow says what the wizard does, and to which resource")
    (is (= (removal/answers parts) answers))
    (is (= (removal/steps ["da" "en"] :dn/synset-1 parts answers)
           (steps-fn answers))
        "and its steps are those of the removal, by the answers so far")))
