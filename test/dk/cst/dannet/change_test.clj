(ns dk.cst.dannet.change-test
  (:require [clojure.test :refer [deftest is testing]]
            [dk.cst.dannet.change :as change]))

(def ^:private hund #rdf/langstring ["hund" "da"])
(def ^:private hunde #rdf/langstring ["hunde" "da"])

(def ^:private old-hypernym [:dn/synset-1 :wn/hypernym :dn/synset-2])
(def ^:private new-hypernym [:dn/synset-1 :wn/hypernym :dn/synset-3])

(deftest pending-changes
  (testing "each edit is a change of its own"
    (is (= [{:retract #{old-hypernym}} {:add #{new-hypernym}}]
           (-> [] (change/retract old-hypernym) (change/add new-hypernym)))))
  (testing "adding a retracted triple back drops the retraction"
    (is (= [] (-> [] (change/retract old-hypernym) (change/add old-hypernym)))))
  (testing "retracting an added triple drops the addition"
    (is (= [{:retract #{old-hypernym}}]
           (-> []
               (change/retract old-hypernym)
               (change/add new-hypernym)
               (change/retract new-hypernym)))))
  (testing "undo drops the change at an index"
    (is (= [{:add #{new-hypernym}}]
           (change/undo [{:retract #{old-hypernym}} {:add #{new-hypernym}}] 0)))))

(deftest net-changes
  (is (= {:add #{new-hypernym} :retract #{old-hypernym}}
         (change/net [{:retract #{old-hypernym}} {:add #{new-hypernym}}])))
  (testing "a triple added and then retracted cancels out"
    (is (= {:add #{} :retract #{}}
           (change/net [{:add #{new-hypernym}} {:retract #{new-hypernym}}]))))
  (testing "within a change, the retractions come first"
    (is (= {:add #{} :retract #{}}
           (change/net [{:retract #{old-hypernym} :add #{old-hypernym}}])))))

(deftest binding-variables
  (is (= {:retract #{['_b1 :ontolex/writtenRep hund]}
          :add     #{['_b1 :ontolex/writtenRep hunde]}}
         (change/bind-variables
           {:where   [[:dn/word-1 :ontolex/canonicalForm '?f]
                      ['?f :ontolex/writtenRep hund]]
            :retract #{['?f :ontolex/writtenRep hund]}
            :add     #{['?f :ontolex/writtenRep hunde]}}
           {'?f '_b1}))))

(deftest previews
  (let [entity    {:wn/hypernym              :dn/synset-2
                   :ontolex/lexicalizedSense #{:dn/sense-1 :dn/sense-2}
                   :wn/hyponym               [:dn/synset-4 :dn/synset-5]}
        changes   [{:retract #{old-hypernym}}
                   {:add #{new-hypernym}}
                   {:retract #{[:dn/synset-1 :ontolex/lexicalizedSense :dn/sense-2]}}
                   {:add #{[:dn/synset-1 :wn/hyponym :dn/synset-6]}}
                   {:add #{[:dn/synset-9 :wn/hypernym :dn/synset-1]}}
                   {:where [[:dn/synset-1 :wn/hypernym '?h]]
                    :add   #{['?h :rdfs/comment "?"]}}]
        previewed (change/preview entity :dn/synset-1 changes)]
    (testing "the subject's own triples are changed"
      (is (= :dn/synset-3 (:wn/hypernym previewed)))
      (is (= #{:dn/sense-1} (:ontolex/lexicalizedSense previewed))))
    (testing "an ordered relation keeps its order"
      (is (= [:dn/synset-4 :dn/synset-5 :dn/synset-6] (:wn/hyponym previewed))))
    (testing "the metadata marks what was added and retracted"
      (is (= {:wn/hypernym #{:dn/synset-3}
              :wn/hyponym  #{:dn/synset-6}}
             (::change/added (meta previewed))))
      (is (= {:wn/hypernym              #{:dn/synset-2}
              :ontolex/lexicalizedSense #{:dn/sense-2}}
             (::change/retracted (meta previewed)))))
    (testing "other subjects and changes with a :where are left out"
      (is (not (contains? previewed :rdfs/comment))))))
