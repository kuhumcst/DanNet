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

(deftest moves-and-taking-away
  (let [old   [:dn/synset-1 :wn/hypernym :dn/synset-2]
        new   [:dn/synset-1 :dns/orthogonalHypernym :dn/synset-2]
        other [:dn/synset-1 :wn/hypernym :dn/synset-3]
        moved (change/replace [] old new)]
    (is (= [{:value :dn/synset-2
             :from  :wn/hypernym
             :to    :dns/orthogonalHypernym}]
           (change/moves moved :dn/synset-1))
        "a change that retracts a value under one property and adds it under
        another moves the value")
    (is (empty? (change/moves (change/replace [] old other) :dn/synset-1))
        "a replaced value isn't moved")
    (is (empty? (change/moves moved :dn/synset-9))
        "and a move is of the subject that it is asked for")
    (is (= [{:retract #{old}}] (change/without-addition moved new))
        "taking a moved value away leaves it removed from its old row")
    (is (= [] (change/without-addition (change/add [] new) new))
        "taking an added value away drops its change")
    (is (= [{:retract #{old}}]
           (change/without-addition (change/replace [] old other) other))
        "taking an edited value away removes the value that it replaces")
    (is (= moved (change/without-addition moved other))
        "a triple that no change adds changes nothing")))

(deftest resources-of-changes
  (is (= #{:dn/synset-1 :wn/hypernym :dn/synset-2 :dn/synset-3
           :dn/word-1 :rdfs/label}
         (change/resources [{:retract #{old-hypernym} :add #{new-hypernym}}
                            {:add #{[:dn/word-1 :rdfs/label hund]}}]))
      "every subject, property and object that is a resource, not the texts"))

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
        "a typo")
    (testing "a blank node label in :add makes a new node"
      (is (= [] (change/problems
                  {:add #{[:dn/word-1 :ontolex/otherForm '_form]
                          ['_form :ontolex/writtenRep hunde]}})))
      (is (= #{'_form}
             (change/new-nodes
               {:add #{[:dn/word-1 :ontolex/otherForm '_form]
                       ['_form :ontolex/writtenRep hunde]}})))
      (is (= [{:problem :unlinked-node :node '_form}]
             (change/problems {:add #{['_form :ontolex/writtenRep hunde]}}))
          "which has to hang off a resource")
      (is (= [] (change/problems
                  {:where [[:dn/word-1 :ontolex/canonicalForm '?f]]
                   :add   #{['?f :rdfs/seeAlso '_note]
                            ['_note :rdf/value "x"]}}))
          "or off a node that a pattern finds")
      (is (= #{:blank-node :unlinked-node}
             (problems {:where [[:dn/word-1 :ontolex/otherForm '_form]]
                        :add   #{['_form :ontolex/writtenRep hunde]}}))
          "and that no pattern can name"))))

(deftest temporary-ids
  (let [synset  [:dn/new-synset-1 :rdf/type :ontolex/LexicalConcept]
        link    [:dn/new-synset-1 :ontolex/lexicalizedSense :dn/new-sense-1]
        changes [{:add #{synset link}}
                 {:where   [[:dn/new-synset-1 :wn/hypernym '?h]]
                  :retract #{['?h :wn/hyponym "<https://wordnet.dk/dannet/data/new-synset-2>"]}}]]
    (is (every? change/temporary? [:dn/new-synset-1
                                   "<https://wordnet.dk/dannet/data/new-word-12>"]))
    (is (not-any? change/temporary? [:dn/synset-1 :dn/newt-1 :dns/new-1 '_new1
                                     "new-1" "<https://example.com/new-1>"]))
    (is (= #{:dn/new-synset-1 :dn/new-sense-1
             "<https://wordnet.dk/dannet/data/new-synset-2>"}
           (change/temporary-ids changes))
        "in the patterns too")
    (is (= #{} (change/temporary-ids [{:add #{old-hypernym}}])))
    (testing "a new entry gets the first ID of its kind that isn't taken"
      (is (= :dn/new-synset-1 (change/temporary-id [] "synset")))
      (is (= :dn/new-sense-2 (change/temporary-id changes "sense")))
      (is (= :dn/new-synset-2 (change/temporary-id (take 1 changes) "synset")))
      (is (= :dn/new-word-1 (change/temporary-id changes "word"))))
    (is (empty? (mapcat change/problems changes))
        "a temporary ID is an IRI like any other")))

(deftest nodes-in-pending-changes
  (let [link    [:dn/word-1 :ontolex/otherForm '_form]
        place   {:subject  :dn/word-1
                 :property :ontolex/otherForm
                 :values   {:ontolex/writtenRep #{hund}}}
        edited  (change/replace [{:add #{new-hypernym}}]
                                ['_form :ontolex/writtenRep hund]
                                ['_form :ontolex/writtenRep hunde])
        removed (change/retract-node edited '_form place)]
    (is (= [{:add #{new-hypernym}}
            {:retract #{link ['_form :ontolex/writtenRep hund]}}]
           removed)
        "one change retracts a node and its values, in place of its edits")
    (is (= [{:add #{new-hypernym}}] (change/without-node removed '_form))
        "and dropping the changes that name the node puts it back")
    (is (= [] (change/without-node
                [{:add #{[:dn/word-1 :ontolex/otherForm '_new1]}}
                 {:add #{['_new1 :ontolex/writtenRep hunde]}}]
                '_new1))
        "or takes a new node away")
    (is (= [{:add #{[:dn/new-word-1 :rdf/type :ontolex/Word]}}]
           (change/without-node
             [{:add #{[:dn/new-word-1 :rdf/type :ontolex/Word]
                      [:dn/new-word-1 :ontolex/canonicalForm '_new1]
                      ['_new1 :ontolex/writtenRep hunde]}}]
             '_new1))
        "and a change keeps the triples that don't name the node")))

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
        "a change to a named resource stays as it is")
    (is (= [{:where   [place]
             :retract #{place form}}]
           (change/locate-nodes
             (change/retract-node [] '_form (get nodes '_form))
             nodes))
        "a removed node is found by its place and its values")
    (testing "the additions of a new node are one change"
      (let [nodes   (assoc nodes '_new1 {:subject  :dn/word-1
                                         :property :ontolex/otherForm
                                         :new?     true})
            link    [:dn/word-1 :ontolex/otherForm '_new1]
            written ['_new1 :ontolex/writtenRep hunde]
            located (change/locate-nodes [{:add #{link}}
                                          {:retract #{old-hypernym}}
                                          {:add #{written}}]
                                         nodes)]
        (is (= [{:add #{link written}} {:retract #{old-hypernym}}] located)
            "in the place of the first of them")
        (is (every? empty? (map change/problems located)))))
    (testing "the changes that name a new entry are one change"
      (let [word    [:dn/new-word-1 :rdf/type :ontolex/Word]
            form    [:dn/new-word-1 :ontolex/canonicalForm '_new1]
            written ['_new1 :ontolex/writtenRep hund]
            sense   [:dn/new-sense-1 :rdf/type :ontolex/LexicalSense]
            label   [:dn/new-sense-1 :rdfs/label hund]
            link    [:dn/new-word-1 :ontolex/sense :dn/new-sense-1]
            example [:dn/new-sense-1 :lexinfo/senseExample "en hund"]
            nodes   {'_new1 {:subject  :dn/new-word-1
                             :property :ontolex/canonicalForm
                             :new?     true}}
            located (change/locate-nodes
                      [{:add #{word form written}}
                       {:retract #{old-hypernym}}
                       {:add #{sense label}}
                       {:add #{example}}
                       {:add #{link}}
                       {:retract #{written} :add #{['_new1 :rdfs/label "x"]}}
                       {:retract #{label}}]
                      nodes)]
        (is (= [{:add #{word form sense link example ['_new1 :rdfs/label "x"]}}
                {:retract #{old-hypernym}}]
               located)
            "with the entries that they link it to, and with its new nodes,
            but without what a later change takes away again")
        (is (every? empty? (map change/problems located)))
        (is (= [{:add #{word}} {:add #{sense}}]
               (change/locate-nodes [{:add #{word}} {:add #{sense}}] nodes))
            "two new entries that no change links stay apart")))))
