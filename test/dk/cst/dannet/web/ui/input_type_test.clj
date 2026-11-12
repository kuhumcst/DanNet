(ns dk.cst.dannet.web.ui.input-type-test
  "Tests for the input types of edit mode and for reading a typed resource."
  (:require [clojure.test :refer [deftest is testing]]
            [dk.cst.dannet.web.ui.input-type :as input-type]))

(deftest typed-resources
  (testing "a resource can be typed in several ways"
    (doseq [s ["dn:synset-3047"
               " dn:synset-3047 "
               "https://wordnet.dk/dannet/data/synset-3047"
               "<https://wordnet.dk/dannet/data/synset-3047>"
               "http://localhost:3456/dannet/data/synset-3047"
               "http://localhost:3456/dannet/data/synset-3047?x=1"]]
      (is (= :dn/synset-3047 (input-type/->resource s)) s)))
  (testing "in any namespace that DanNet knows"
    (is (= :dnt/Animal-Object
           (input-type/->resource
             "https://wordnet.dk/dannet/types/Animal-Object")))
    (is (= :ontolex/LexicalConcept
           (input-type/->resource
             "http://www.w3.org/ns/lemon/ontolex#LexicalConcept")))
    (is (= :wn/noun (input-type/->resource "wn:noun"))))
  (testing "anything else isn't a resource"
    (doseq [s ["hund" "noun.animal" "foo:bar" ""
               "https://wordnet.dk/dannet/data/" "https://example.com/x"]]
      (is (nil? (input-type/->resource s)) s))))

(deftest input-types
  (is (= input-type/ontological-type
         (input-type/input-type :dns/ontologicalType [:dnt/Animal-Object]))
      "a property with an input type of its own")
  (testing "or else the one that fits its values"
    (is (= input-type/synset
           (input-type/input-type :wn/hypernym [:dn/synset-1])))
    (is (= input-type/resource
           (input-type/input-type :rdf/type [:ontolex/LexicalConcept])))
    (is (= input-type/resource
           (input-type/input-type :wn/ili [:dn/synset-1 :ili/i46360]))
        "resources that aren't all synsets")
    (is (= input-type/text
           (input-type/input-type :skos/definition ["et dyr"])))
    (is (= input-type/text (input-type/input-type :rdfs/comment [])))))

(deftest texts
  (let [parse (:parse-fn input-type/text)]
    (is (= #rdf/langstring ["hunde" "da"] (parse " hunde " "da"))
        "a text is in the language of its field")
    (is (= "noun.plant" (parse "noun.plant" nil))
        "a field without a language gives a text without one")
    (is (nil? (parse "  " "da")) "an empty text isn't a value"))
  (is (:lang? input-type/text) "a text can be in a language")
  (is (not (:lang? input-type/resource)) "a resource can't"))
