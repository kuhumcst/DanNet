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
  (testing "an edit made twice is made once"
    (is (= [{:add #{new-hypernym}}]
           (-> [] (change/add new-hypernym) (change/add new-hypernym))))
    (is (= [{:retract #{old-hypernym}}]
           (-> [] (change/retract old-hypernym) (change/retract old-hypernym))))
    (is (= [{:retract #{old-hypernym} :add #{new-hypernym}}]
           (-> []
               (change/replace old-hypernym new-hypernym)
               (change/replace old-hypernym new-hypernym)))))
  (testing "undo drops the change at an index"
    (is (= [{:add #{new-hypernym}}]
           (change/undo [{:retract #{old-hypernym}} {:add #{new-hypernym}}]
                        0))))
  (testing "or the changes that a triple is part of"
    (is (= [{:retract #{old-hypernym}}]
           (-> []
               (change/retract old-hypernym)
               (change/replace [:dn/synset-1 :rdfs/label "a"]
                               [:dn/synset-1 :rdfs/label "b"])
               (change/without-triple [:dn/synset-1 :rdfs/label "b"]))))))

(deftest changes-to-a-property
  (let [label   (fn [v] [:dn/synset-1 :rdfs/label v])
        changes (-> []
                    (change/replace (label "a") (label "a2"))
                    (change/retract (label "b"))
                    (change/add (label "d"))
                    (change/retract [:dn/synset-2 :rdfs/label "x"])
                    (change/retract [:dn/synset-1 :rdfs/comment "y"]))]
    (is (= {:added     #{"a2" "d"}
            :retracted #{"a" "b"}
            :replaced  {"a" ["a2"]}}
           (change/property-changes changes :dn/synset-1 :rdfs/label))
        "only the changes to the property of the subject")
    (is (= {:added #{} :retracted #{} :replaced {}}
           (change/property-changes changes :dn/synset-3 :rdfs/label)))
    (is (= #{:rdfs/label :rdfs/comment}
           (change/changed-properties changes :dn/synset-1))
        "the properties of the subject that have a change")
    (is (= #{} (change/changed-properties changes :dn/synset-3)))
    (is (= #{:wn/hypernym}
           (change/changed-properties
             [{:where   [[:dn/synset-1 :ontolex/canonicalForm '?f]]
               :retract #{['?f :ontolex/writtenRep "a"] old-hypernym}
               :add     #{['?f :ontolex/writtenRep "b"] new-hypernym}}]
             :dn/synset-1))
        "not those of a subject that a pattern leads to")))

(deftest replacements
  (let [newer-hypernym [:dn/synset-1 :wn/hypernym :dn/synset-4]
        replaced       (change/replace [] old-hypernym new-hypernym)]
    (is (= [{:retract #{old-hypernym} :add #{new-hypernym}}] replaced)
        "one change retracts the old triple and adds the new one")
    (is (= [{:retract #{old-hypernym} :add #{newer-hypernym}}]
           (change/replace replaced new-hypernym newer-hypernym))
        "replacing the new triple rewrites that change")
    (is (= [] (change/replace replaced new-hypernym old-hypernym))
        "replacing it with the old triple drops the change")
    (is (= [{:add #{newer-hypernym}}]
           (change/replace [{:add #{new-hypernym}}] new-hypernym newer-hypernym))
        "an added triple is still only added")))

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
                    :add   #{['?h :rdfs/comment "?"]}}
                   {:retract #{[:dn/synset-1 :wn/hyponym '?x]}}]
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
    (testing "other subjects and changes that aren't plain are left out"
      (is (not (contains? previewed :rdfs/comment))))))

(deftest checking-changes
  (let [problems #(set (map :problem (change/problems %)))]
    (is (= [] (change/problems
                {:where   [[:dn/word-1 :ontolex/canonicalForm '?f]]
                 :retract #{['?f :ontolex/writtenRep hund]}
                 :add     #{['?f :ontolex/writtenRep hunde]}})))
    (is (= [] (change/problems {:add #{new-hypernym}})) "a plain change")
    (is (= #{:unknown-key} (problems {:delete #{old-hypernym}
                                      :add    #{new-hypernym}})))
    (is (= #{:nothing-to-change}
           (problems {:where [[:dn/synset-1 :wn/hypernym '?h]]})))
    (is (= #{:not-a-triple} (problems {:add #{[:dn/synset-1 :wn/hypernym]}})))
    (is (= #{:not-a-subject} (problems {:add #{["x" :wn/hypernym :dn/s]}})))
    (is (= #{:not-a-predicate}
           (problems {:retract #{[:dn/synset-1 '?p :dn/synset-2]}})))
    (is (= #{:blank-node} (problems {:retract #{['_b1 :ontolex/writtenRep hund]}})))
    (is (= #{:unrooted-variable}
           (problems {:retract #{['?f :ontolex/writtenRep hund]}})))
    (is (= [{:problem :unbound-variable :variable '?g}]
           (change/problems
             {:where [[:dn/word-1 :ontolex/canonicalForm '?f]]
              :add   #{['?g :ontolex/writtenRep hunde]}}))
        "a typo")))

(deftest locating-nodes
  (let [form    ['?node :ontolex/writtenRep hund]
        place   [:dn/word-1 :ontolex/canonicalForm '?node]
        nodes   {'_form {:subject  :dn/word-1
                         :property :ontolex/canonicalForm
                         :values   {:ontolex/writtenRep #{hund}}}}
        edit    {:retract #{['_form :ontolex/writtenRep hund]}
                 :add     #{['_form :ontolex/writtenRep hunde]}}
        label   {:add #{['_form :rdfs/label "x"]}}
        located (change/locate-nodes [edit label] nodes)]
    (is (= {:where   [place]
            :retract #{form}
            :add     #{['?node :ontolex/writtenRep hunde]}}
           (first located))
        "a node is found by its place, and by the value that is retracted")
    (is (= {:where [place ['?node :ontolex/writtenRep hunde]]
            :add   #{['?node :rdfs/label "x"]}}
           (second located))
        "and by its values as the changes before it leave them")
    (is (= [{:where [place form] :add #{['?node :rdfs/label "x"]}}]
           (change/locate-nodes [label] nodes)))
    (is (every? empty? (map change/problems located)))
    (is (= [{:retract #{old-hypernym}}]
           (change/locate-nodes [{:retract #{old-hypernym}}] nodes))
        "a change to a named resource stays as it is")))
