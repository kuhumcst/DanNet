(ns dk.cst.dannet.web.ui.edit.row-test
  "Tests for the order of the fields of an edit row, for the properties that
  its changes are made to and for the languages of its texts."
  (:require [clojure.test :refer [deftest is]]
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
