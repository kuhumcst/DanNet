(ns dk.cst.dannet.prefix-test
  "Tests for reading a typed resource."
  (:require [clojure.test :refer [deftest is testing]]
            [dk.cst.dannet.prefix :as prefix]))

(deftest typed-resources
  (testing "a resource can be typed in several ways"
    (doseq [s ["dn:synset-3047"
               " dn:synset-3047 "
               "https://wordnet.dk/dannet/data/synset-3047"
               "<https://wordnet.dk/dannet/data/synset-3047>"
               "http://localhost:3456/dannet/data/synset-3047"
               "http://localhost:3456/dannet/data/synset-3047?x=1"]]
      (is (= :dn/synset-3047 (prefix/->resource s)) s)))
  (testing "in any namespace that DanNet knows"
    (is (= :dnt/Animal-Object
           (prefix/->resource
             "https://wordnet.dk/dannet/types/Animal-Object")))
    (is (= :ontolex/LexicalConcept
           (prefix/->resource
             "http://www.w3.org/ns/lemon/ontolex#LexicalConcept")))
    (is (= :wn/noun (prefix/->resource "wn:noun"))))
  (testing "anything else isn't a resource"
    (doseq [s ["hund" "noun.animal" "foo:bar" ""
               "https://wordnet.dk/dannet/data/" "https://example.com/x"]]
      (is (nil? (prefix/->resource s)) s))))
