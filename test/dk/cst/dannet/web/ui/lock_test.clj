(ns dk.cst.dannet.web.ui.lock-test
  "Tests for the origins of the values of a row and for which can be edited."
  (:require [clojure.test :refer [deftest is]]
            [dk.cst.dannet.editable :as editable]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.web.ui.lock :as lock]))

(deftest row-origins
  (let [opts {:origins        {:wn/hyponym  :inferred
                               :wn/hypernym {:dn/synset-2 :inferred}
                               :rdfs/label  {"hund" #{:dn :other}}}
              :default-origin :dn}]
    (is (= #{:dn} (lock/row-origins opts :skos/definition "et dyr"))
        "the default origin of a property without an entry")
    (is (= #{:inferred}
           (lock/row-origins opts :wn/hyponym [:dn/synset-3 :dn/synset-4]))
        "one origin for every value")
    (is (= #{:dn :inferred}
           (lock/row-origins opts :wn/hypernym #{:dn/synset-1 :dn/synset-2})))
    (is (= #{:dn} (lock/row-origins opts :wn/hypernym #{:dn/synset-1}))
        "only the origins of the values shown")
    (is (= #{:dn :other} (lock/row-origins opts :rdfs/label "hund"))
        "a value that several graphs hold")))

(deftest locks
  (let [opts   {:origins        {:wn/hyponym      :inferred
                                 :skos/definition {"a dog" :supplemented}
                                 :rdfs/comment    #{:dn :other}
                                 :rdfs/seeAlso    :other}
                :default-origin :dn}
        synset (assoc opts :entity {:rdf/type :ontolex/LexicalConcept})]
    (is (lock/editable? opts :wn/hypernym :dn/synset-2)
        "a value of the graph that changes go to")
    (is (lock/editable? opts :rdfs/comment "x")
        "also when another graph holds it too")
    (is (not (lock/editable? {} :rdfs/label "hund"))
        "nothing without origins, as in a nested table")
    (is (nil? (lock/lock opts :skos/definition "en hund")))
    (is (= :inferred (lock/lock opts :wn/hyponym :dn/synset-3)))
    (is (= :supplemented (lock/lock opts :skos/definition "a dog")))
    (is (= :elsewhere (lock/lock opts :rdfs/seeAlso :dn/synset-4)))
    (is (nil? (lock/lock synset :skos/inScheme :dn/x))
        "without rules, the origins alone decide")
    (is (= :yet (lock/lock opts :dns/sentiment {:marl/polarityValue 1}))
        "a blank node")
    (let [nested (assoc opts :blank-node {:rdf/about '_form
                                          :property  :ontolex/canonicalForm
                                          :lock      :yet})
          locked (assoc-in nested [:blank-node :lock] :supplemented)]
      (is (nil? (lock/lock nested :ontolex/writtenRep "hund"))
          "a value in the nested table of a blank node can be edited")
      (is (= :inferred (lock/lock nested :wn/hyponym :dn/synset-3))
          "unless it is locked itself")
      (is (= :supplemented (lock/lock locked :ontolex/writtenRep "hund"))
          "or its blank node is"))
    (is (lock/node? (with-meta {:rdf/about '_form} {:default-origin :dn}))
        "a blank node with the origins of its values has a nested table")
    (is (not (lock/node? {:rdf/about '_form})))))

(deftest edit-rules
  (let [rules  {:namespaces      #{"dn"}
                :derived         #{:skos/inScheme}
                :derived-by-type {:ontolex/LexicalConcept #{:rdfs/label}}}
        opts   (fn [subject & [entity]]
                 {:subject        subject
                  :entity         entity
                  :edit-rules     rules
                  :origins        {:wn/hyponym :inferred}
                  :default-origin :dn})
        word   (opts :dn/word-1 {:rdf/type :ontolex/LexicalEntry})
        synset (opts :dn/synset-1 {:rdf/type #{:ontolex/LexicalConcept
                                               :skos/Concept}})
        dnt    (opts :dnt/Animal-Object)]
    (is (= :derived (lock/lock word :skos/inScheme :dn/x))
        "a property that a build writes for every resource")
    (is (nil? (lock/lock word :rdfs/label "hund"))
        "the label of a word can be edited")
    (is (= :derived (lock/lock synset :rdfs/label "{hund_1§1}"))
        "but a build writes that of a synset")
    (is (lock/editable-resource? word)
        "a resource of a namespace that can be edited")
    (is (not (lock/editable-resource? dnt))
        "a resource of another namespace")
    (is (not (lock/editable-resource? (opts "<http://example.com/x>")))
        "or of none that is known")
    (is (lock/editable-resource? (assoc dnt :edit-rules {}))
        "any resource, when the rules name no namespaces")
    (is (not (lock/editable-resource? (assoc word :edit-rules
                                                  {:namespaces #{}})))
        "and none, when they name an empty set")
    (is (= :resource (lock/lock dnt :rdfs/label "Animal + Object")))
    (is (= :inferred (lock/lock dnt :wn/hyponym :dn/synset-3))
        "the origin of a value says more than its resource does")
    (is (= :derived (lock/lock dnt :skos/inScheme :dn/x))
        "and so does a property that a build writes")
    (is (nil? (lock/lock (assoc dnt :edit-rules {:namespaces #{"dn" "dnt"}})
                         :rdfs/label
                         "Animal + Object"))
        "the rules can let more be edited")))

(deftest dannet-rules
  (let [opts   (fn [subject type]
                 {:subject        subject
                  :entity         {:rdf/type type}
                  :edit-rules     editable/rules
                  :default-origin :dn})
        synset (opts :dn/synset-1 :ontolex/LexicalConcept)
        word   (opts :dn/word-1 :ontolex/LexicalEntry)]
    (is (nil? (lock/lock synset :skos/definition "et dyr")))
    (is (= [:derived :derived :derived :derived]
           (map #(lock/lock synset % "x")
                [:rdfs/label :dns/shortLabel :dns/inherited :skos/inScheme]))
        "what the release build writes for a synset")
    (is (nil? (lock/lock word :rdfs/label "hund"))
        "the label of a word is written by hand")
    (is (= :resource
           (lock/lock (opts :dnt/Animal-Object :dns/OntologicalType)
                      :rdfs/member
                      :dnc/Animal))
        "the schema isn't edited")))

(deftest members
  (let [opts {:entity         {:rdf/type :dns/OntologicalType
                               :rdf/_1   :dnc/Animal
                               :rdf/_2   #{:dnc/Object}
                               :rdf/_10  :dnc/Animal}
              :origins        {:rdf/_1  :other
                               :rdf/_2  :inferred
                               :rdf/_10 {:dnc/Animal #{:dn :other}}}
              :default-origin :dn}]
    (is (= [:rdf/_2] (lock/value-properties opts :rdfs/member :dnc/Object))
        "a member is held by its rdf:_N property")
    (is (= [:rdf/_1 :rdf/_10]
           (lock/value-properties opts :rdfs/member :dnc/Animal))
        "or by several, in the order of their ordinals")
    (is (= [:rdf/type] (lock/value-properties opts :rdf/type :dnc/Animal))
        "any other value is held by the property of its row")
    (is (= [:rdfs/member] (lock/value-properties opts :rdfs/member :dnc/Plant))
        "and so is a member that no rdf:_N property has")
    (is (= [true true false false false false]
           (map shared/member-property?
                [:rdf/_1 :rdf/_10 :rdf/_0 :rdf/_x :rdf/_ :rdfs/_1]))
        "only rdf:_ and a number from 1 is a member property")
    (is (= #{:dn :other} (lock/value-origins opts :rdfs/member :dnc/Animal))
        "a member has the origins of the properties that hold it")
    (is (= #{:dn :other :inferred}
           (lock/row-origins opts :rdfs/member [:dnc/Animal :dnc/Object])))
    (is (nil? (lock/lock opts :rdfs/member :dnc/Animal))
        "a member can be edited when one of its properties can be")
    (is (= :elsewhere (lock/lock opts :rdf/_1 :dnc/Animal))
        "each of its properties has a lock of its own")
    (is (nil? (lock/lock opts :rdf/_10 :dnc/Animal)))
    (is (= :inferred (lock/lock opts :rdfs/member :dnc/Object)))))
