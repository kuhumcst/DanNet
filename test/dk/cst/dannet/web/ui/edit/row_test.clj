(ns dk.cst.dannet.web.ui.edit.row-test
  "Tests for the order of the fields of an edit row, for the properties that
  its changes are made to, for the languages of its texts and for the rows
  that can be added to a table."
  (:require [clojure.test :refer [deftest is]]
            [dk.cst.dannet.change :as change]
            [dk.cst.dannet.web.ui.edit.row :as row]))

(deftest value-order
  (is (= ["a" "b"] (row/value-order ["a" "b"] {}))
      "the values as they are, without changes")
  (is (= ["a2" "b" "d"]
         (row/value-order ["a" "b"] {:added     #{"a2" "d"}
                                     :retracted #{"a" "b"}
                                     :replaced  {"a" ["a2"]}}))
      "a replacement in place of its value, a removed value in its place and
      the other additions last")
  (is (= ["b"]
         (row/value-order ["a" "b"] {:added     #{"b"}
                                     :retracted #{"a"}
                                     :replaced  {"a" ["b"]}}))
      "a replacement that is already a value is listed once"))

(def ^:private subject :dnt/Animal-Object)

(defn- member
  "The triple of the member `v` with the ordinal `n`."
  [n v]
  [subject (keyword "rdf" (str "_" n)) v])

(deftest properties
  (let [opts (fn [changes]
               {:subject subject
                :entity  {:rdf/type :dns/OntologicalType
                          :rdf/_10  :dnc/Object
                          :rdf/_2   :dnc/Animal}
                :changes changes})]
    (is (= [:rdf/type] (row/properties (opts []) :rdf/type))
        "a row lists the values of its own property")
    (is (= [:rdf/_2 :rdf/_10 :rdfs/member]
           (row/properties (opts []) :rdfs/member))
        "the row of members has the rdf:_N properties first, by ordinal")
    (is (= [:rdf/_2 :rdf/_10 :rdf/_11 :rdfs/member]
           (row/properties
             (opts [{:retract #{(member 2 :dnc/Animal)}}
                    {:add #{(member 11 :dnc/Plant)}}
                    {:add #{[subject :rdfs/comment "x"]}}
                    {:add #{[:dnt/Plant :rdf/_12 :dnc/Plant]}}])
             :rdfs/member))
        "and those of the pending changes to the entity, each once")))

(deftest new-value-property
  (is (= :rdf/type (row/new-value-property :rdf/type [:rdf/type]))
      "a new value is added to the property of its row")
  (is (= :rdf/_11
         (row/new-value-property :rdfs/member
                                 [:rdf/_2 :rdf/_10 :rdfs/member]))
      "a new member comes after the highest ordinal")
  (is (= :rdf/_1 (row/new-value-property :rdfs/member [:rdfs/member]))
      "the first member of a container"))

(deftest fields
  (let [entries [[:rdf/_1 :dnc/Animal] [:rdf/_2 :dnc/Object]]
        fields  (fn [changes]
                  (row/fields changes subject
                              [:rdf/_1 :rdf/_2 :rdf/_3]
                              entries))
        old     [:dn/synset-1 :wn/hypernym :dn/synset-2]
        new     [:dn/synset-1 :wn/hypernym :dn/synset-4]]
    (is (= [{:property :rdf/_1 :value :dnc/Animal}
            {:property :rdf/_2 :value :dnc/Object}]
           (fields []))
        "a field for every value, without changes")
    (is (= [{:property :wn/hypernym :value :dn/synset-2 :status :retracted}
            {:property :wn/hypernym :value :dn/synset-4 :status :added}]
           (row/fields [{:retract #{old}} {:add #{new}}]
                       :dn/synset-1
                       [:wn/hypernym]
                       [[:wn/hypernym :dn/synset-2]]))
        "the values of one property, with its additions last")
    (let [move {:retract #{old}
                :add     #{[:dn/synset-1 :dns/orthogonalHypernym :dn/synset-2]}}]
      (is (= [{:property :wn/hypernym
               :value    :dn/synset-2
               :status   :retracted
               :moved-to :dns/orthogonalHypernym}]
             (row/fields [move] :dn/synset-1 [:wn/hypernym]
                         [[:wn/hypernym :dn/synset-2]]))
          "a value that has moved to another row says where it went")
      (is (= [{:property   :dns/orthogonalHypernym
               :value      :dn/synset-2
               :status     :moved
               :moved-from :wn/hypernym}]
             (row/fields [move] :dn/synset-1 [:dns/orthogonalHypernym] []))
          "and in its new row it says where it came from"))
    (is (= [{:property :rdf/_1 :value :dnc/Animal :status :retracted}
            {:property :rdf/_2
             :value    :dnc/Plant
             :status   :edited
             :original :dnc/Object}
            {:property :rdf/_3 :value :dnc/Human :status :added}]
           (fields [{:retract #{(member 1 :dnc/Animal)}}
                    {:retract #{(member 2 :dnc/Object)}
                     :add     #{(member 2 :dnc/Plant)}}
                    {:add #{(member 3 :dnc/Human)}}]))
        "each member with the change to its own property")
    (is (= [{:property :rdf/_1
             :value    :dnc/Plant
             :status   :edited
             :original :dnc/Animal}
            {:property :rdf/_2
             :value    :dnc/Plant
             :status   :edited
             :original :dnc/Object}]
           (fields [{:retract #{(member 1 :dnc/Animal)}
                     :add     #{(member 1 :dnc/Plant)}}
                    {:retract #{(member 2 :dnc/Object)}
                     :add     #{(member 2 :dnc/Plant)}}]))
        "two members edited to the same value keep a field each")
    (is (= [{:property :rdf/_1
             :value    :dnc/Object
             :status   :edited
             :original :dnc/Animal}
            {:property :rdf/_2 :value :dnc/Object :status :retracted}]
           (fields [{:retract #{(member 2 :dnc/Object)}}
                    {:retract #{(member 1 :dnc/Animal)}
                     :add     #{(member 1 :dnc/Object)}}]))
        "a member edited to the value of a removed one")
    (is (= [{:property :rdf/_1 :value :dnc/Animal}
            {:property :rdf/_2 :value :dnc/Animal :status :retracted}]
           (row/fields [{:retract #{(member 2 :dnc/Animal)}}]
                       subject
                       [:rdf/_1 :rdf/_2]
                       [[:rdf/_1 :dnc/Animal] [:rdf/_2 :dnc/Animal]]))
        "a value that is a member twice has two fields")
    (is (= [{:property :wn/hypernym :value :dn/synset-2}
            {:property :wn/hypernym :value :dn/synset-3}]
           (row/fields []
                       :dn/synset-1
                       [:wn/hypernym]
                       [[:wn/hypernym :dn/synset-2]
                        [:wn/hypernym :dn/synset-3]]))
        "the values of one property keep their order")))

(deftest node-values
  (let [written  [:ontolex/writtenRep "snakke sammen"]
        stressed [:dns/stressedRep "snakke ˈsammen"]
        values   (array-map (first written) #{(second written)}
                            (first stressed) #{(second stressed)})
        triple   (fn [[p v]] ['_form p v])
        edited   (change/replace [] (triple written)
                                 ['_form :ontolex/writtenRep "snakke samme"])]
    (is (= ["snakke sammen" "snakke ˈsammen"]
           (row/node-values [] '_form values))
        "the values of the data, in the order of its rows")
    (is (= ["snakke samme" "snakke ˈsammen"]
           (row/node-values edited '_form values))
        "a replacement takes the place of its value")
    (is (= ["snakke sammen"]
           (row/node-values (change/retract [] (triple stressed)) '_form values))
        "a removed value is left out")
    (is (= ["sludre sammen" "sludre ˈsammen"]
           (row/node-values
             (-> []
                 (change/add ['_new1 :ontolex/writtenRep "sludre sammen"])
                 (change/add ['_new1 :dns/stressedRep "sludre ˈsammen"]))
             '_new1 nil))
        "a new node has the values of its changes, in their order")
    (is (= ["snakke sammen" "snakke ˈsammen"]
           (row/node-values edited '_other values))
        "and the changes to another node don't count")))

(deftest lang-rank
  (is (= ["en" nil "da"] (sort-by (row/lang-rank ["en" nil "da"])
                                  ["da" "en" nil]))
      "the languages in the order that the reader prefers")
  (is (= ["da" "en" "de"] (sort-by (row/lang-rank ["da" "en" nil])
                                   ["de" "da" "en"]))
      "a language that the reader has no preference for comes last"))

(deftest missing-lang
  (let [da #rdf/langstring ["zoologi" "da"]
        en #rdf/langstring ["zoology" "en"]]
    (is (= "en" (row/missing-lang ["da" "en" nil] [da]))
        "the language that has no text")
    (is (= "da" (row/missing-lang ["da" "en" nil] ["zoo"]))
        "the first one, when several have none")
    (is (nil? (row/missing-lang ["da" "en" nil] [da en]))
        "no text has to be without a language")
    (is (nil? (row/missing-lang [] [da])))))

(deftest new-text-lang
  (let [da #rdf/langstring ["zoologi" "da"]]
    (is (= "da" (row/new-text-lang da ["da" "en"]))
        "the language of the text before it")
    (is (= "da" (row/new-text-lang nil ["da"]))
        "or else the one that the property is expected in")
    (is (nil? (row/new-text-lang "noun.animal" []))
        "a code has no language")))

(deftest addable
  (let [opts {:subject        :dn/synset-1
              :edit-mode      true
              :default-origin :dn
              :entity         {:rdf/type :ontolex/LexicalConcept}
              :edit-rules     {:namespaces      #{"dn"}
                               :derived         #{:skos/inScheme}
                               :derived-by-type {:ontolex/LexicalConcept
                                                 #{:rdfs/label}}}
              :constraints    {:wn/mero_part          {:must {}}
                               :skos/definition       {:should {:min 1}}
                               :rdfs/label            {:must {:max 1}}
                               :skos/inScheme         {:should {:min 1}}
                               :ontolex/canonicalForm {:node {}}}}]
    (is (= #{:wn/mero_part :skos/definition :ontolex/canonicalForm}
           (set (row/addable opts)))
        "what the shapes describe, but not what a build writes")
    (is (empty? (row/addable (dissoc opts :edit-mode)))
        "no row is added outside edit mode")
    (is (empty? (row/addable (assoc opts :subject :dnt/Animal)))
        "or to a resource that can't be edited")
    (let [nested (assoc opts
                   :constraints {:ontolex/writtenRep {:must {:min 1}}
                                 :dns/stressedRep    {:must {:max 1}}
                                 :rdfs/label         {:must {:max 1}}}
                   :blank-node {:rdf/about '_form
                                :property  :ontolex/canonicalForm})]
      (is (= #{:ontolex/writtenRep :dns/stressedRep :rdfs/label}
             (set (row/addable nested)))
          "a blank node takes the rows that the shape of its values has,
          whatever a build writes for the entity")
      (is (empty? (row/addable (assoc-in nested [:blank-node :lock] :yet)))
          "unless it is locked"))))

(deftest expected
  (let [constraints {:ontolex/lexicalizedSense {:must {:min 1}}
                     :skos/definition          {:should {:min 1 :max 1}}
                     :wn/ili                   {:must {:max 1}}}]
    (is (= :must (row/expected constraints :ontolex/lexicalizedSense)))
    (is (= :should (row/expected constraints :skos/definition))
        "a rule that only warns says what an entry should have")
    (is (nil? (row/expected constraints :wn/ili))
        "a property without a minimum isn't expected")
    (is (nil? (row/expected constraints :wn/mero_part)))))

(deftest open-rows
  (let [addable [:wn/agent :wn/mero_part :wn/also]
        pending (-> []
                    (change/add [:dn/synset-1 :wn/mero_part :dn/synset-2])
                    (change/add [:dn/synset-1 :rdfs/label "x"])
                    (change/add [:dn/synset-9 :wn/also :dn/synset-2]))]
    (is (= [:wn/mero_part] (row/open-rows addable pending :dn/synset-1 nil))
        "a row that the changes to the subject add a value to")
    (is (= [:wn/mero_part :wn/also :wn/agent]
           (row/open-rows addable pending :dn/synset-1 [:wn/also :wn/agent]))
        "then the rows that the editor has added, in that order")
    (is (= [:wn/also :wn/mero_part]
           (row/open-rows addable pending :dn/synset-1
                          [:wn/also :wn/mero_part]))
        "an added row keeps its place when it gets a value")
    (is (empty? (row/open-rows addable [] :dn/synset-1 [:skos/definition]))
        "only a row that can be added is open")))

(deftest alternatives
  (let [synset      {:class :ontolex/LexicalConcept :pattern "^dn"}
        english     {:class :ontolex/LexicalConcept :pattern "^en"}
        constraints {:wn/hypernym           {:must synset :should {:min 1}}
                     :dns/orthogonalHypernym {:must synset}
                     :wn/instance_hypernym  {:must (assoc synset :max 1)}
                     :wn/eq_synonym         {:must english}
                     :ontolex/lexicalizedSense {:must {:min 1 :iri? true}}
                     :ontolex/isSenseOf     {:must {:iri? true}}
                     :lexinfo/senseExample  {:must {:languages ["da"]}}
                     :lexinfo/usageNote     {:must {:languages ["da"]}}
                     :wn/hyponym            {}}
        ks          (keys constraints)
        entity      {:wn/hypernym          #{:dn/synset-1 :dn/synset-2}
                     :wn/instance_hypernym :dn/synset-3}
        to          #(set (row/alternatives constraints ks entity %1 %2))]
    (is (= #{:dns/orthogonalHypernym} (to :wn/hypernym 2))
        "the properties whose values have the same shape, and room for them")
    (is (= #{:wn/hypernym} (to :dns/orthogonalHypernym 1))
        "a row that only should have a value can be moved from")
    (is (= #{:wn/hypernym :dns/orthogonalHypernym} (to :wn/instance_hypernym 1)))
    (is (empty? (to :wn/eq_synonym 1))
        "an English link has no other property to move to here")
    (is (empty? (to :ontolex/lexicalizedSense 1))
        "a row that must keep a value can't be moved")
    (is (empty? (to :wn/hyponym 1))
        "nor one whose values have no shape")
    (is (empty? (to :lexinfo/senseExample 1))
        "a text isn't moved, though another property takes the same texts")))
