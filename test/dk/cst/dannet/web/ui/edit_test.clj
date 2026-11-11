(ns dk.cst.dannet.web.ui.edit-test
  "Tests for the value kinds of edit mode and for reading a typed resource."
  (:require [clojure.test :refer [deftest is testing]]
            [dk.cst.dannet.web.ui.edit :as edit]))

(deftest typed-resources
  (testing "a resource can be typed in several ways"
    (doseq [s ["dn:synset-3047"
               " dn:synset-3047 "
               "https://wordnet.dk/dannet/data/synset-3047"
               "<https://wordnet.dk/dannet/data/synset-3047>"
               "http://localhost:3456/dannet/data/synset-3047"
               "http://localhost:3456/dannet/data/synset-3047?x=1"]]
      (is (= :dn/synset-3047 (edit/->resource s)) s)))
  (testing "in any namespace that DanNet knows"
    (is (= :dnt/Animal-Object
           (edit/->resource "https://wordnet.dk/dannet/types/Animal-Object")))
    (is (= :ontolex/LexicalConcept
           (edit/->resource
             "http://www.w3.org/ns/lemon/ontolex#LexicalConcept")))
    (is (= :wn/noun (edit/->resource "wn:noun"))))
  (testing "anything else isn't a resource"
    (doseq [s ["hund" "noun.animal" "foo:bar" ""
               "https://wordnet.dk/dannet/data/" "https://example.com/x"]]
      (is (nil? (edit/->resource s)) s))))

(deftest value-kinds
  (is (= edit/ontological-type
         (edit/value-kind :dns/ontologicalType [:dnt/Animal-Object]))
      "a property with a kind of its own")
  (testing "or else the kind that its values are of"
    (is (= edit/synset (edit/value-kind :wn/hypernym [:dn/synset-1])))
    (is (= edit/resource
           (edit/value-kind :rdf/type [:ontolex/LexicalConcept])))
    (is (= edit/resource
           (edit/value-kind :wn/ili [:dn/synset-1 :ili/i46360]))
        "resources that aren't all synsets")
    (is (= edit/text (edit/value-kind :skos/definition ["et dyr"])))
    (is (= edit/text (edit/value-kind :rdfs/comment [])))))

(deftest texts
  (let [parse (:parse-fn edit/text)]
    (is (= #rdf/langstring ["hunde" "da"]
           (parse " hunde " #rdf/langstring ["hund" "da"]))
        "a text keeps the language of the one it replaces")
    (is (= "noun.plant" (parse "noun.plant" "noun.animal")))
    (is (= "ny" (parse "ny" nil)) "a text that resembles none has no language")
    (is (nil? (parse "  " "noun.animal")) "an empty text isn't a value")))
