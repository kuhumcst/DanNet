(ns dk.cst.dannet.web.ui.edit.input-type-test
  "Tests for the input types of edit mode."
  (:require [clojure.test :refer [deftest is testing]]
            [dk.cst.dannet.web.ui.edit.input-type :as input-type]))

(def synset
  "The input type of a class that has one of its own, with a search."
  (assoc input-type/resource :search {:title {:en "Find a synset"}}))

(def input-type-of
  "The input type of some values and their constraints, when a synset has
  an input type of its own."
  (partial input-type/input-type {:ontolex/LexicalConcept synset}))

(deftest input-types
  (is (= {:options [:wn/noun :wn/verb]}
         (input-type-of [:wn/noun] {:in [:wn/noun :wn/verb]}))
      "the values that a shape lets a property choose between")
  (is (= synset (input-type-of [:dn/synset-1] {:class :ontolex/LexicalConcept}))
      "the input type of the class that a shape gives the values")
  (is (= "Must be digits"
         (:description (input-type-of ["12"] {:pattern     "^[0-9]+$"
                                              :description "Must be digits"})))
      "a text that matches the pattern of a shape, with its description")
  (is (= input-type/resource (input-type-of [] {:iri? true}))
      "a value that has to be an IRI is a resource, also in a row without values")
  (is (= input-type/resource (input-type-of [] {:class :ontolex/LexicalSense}))
      "and so is a value of a class without an input type of its own")
  (testing "a resource that the pattern of a shape matches"
    (let [danish  (input-type-of
                    [] {:class       :ontolex/LexicalConcept
                        :pattern     "^https://wordnet\\.dk/dannet/data/"
                        :description "Must be a synset in DanNet"})
          english (input-type-of
                    [] {:class   :ontolex/LexicalConcept
                        :pattern "^https://en-word\\.net/id/"})]
      (is (= "Must be a synset in DanNet" (:description danish)))
      (is (= :dn/synset-1 ((:parse-fn danish) "dn:synset-1" nil)))
      (is (nil? ((:parse-fn danish) "en:oewn-1-n" nil))
          "a resource that the pattern doesn't match isn't a value")
      (is (= :en/oewn-1-n ((:parse-fn english) "en:oewn-1-n" nil)))
      (is (nil? ((:parse-fn english) "dn:synset-1" nil)))
      (is (some? (:search danish)) "a synset of DanNet can be searched for")
      (is (nil? (:search english)) "and an English one can only be typed")))
  (testing "or else the one that fits its values"
    (is (= input-type/resource (input-type-of [:dn/synset-1 :ili/i46360] nil))
        "resources are typed as an ID when no shape gives their class")
    (is (= input-type/text (input-type-of ["et dyr"] nil)))
    (is (= input-type/text (input-type-of [] nil)))))

(deftest texts
  (let [parse (:parse-fn input-type/text)]
    (is (= #rdf/langstring ["hunde" "da"] (parse " hunde " "da"))
        "a text is in the language of its field")
    (is (= "noun.plant" (parse "noun.plant" nil))
        "a field without a language gives a text without one")
    (is (nil? (parse "  " "da")) "an empty text isn't a value"))
  (let [parse (:parse-fn (input-type/matching "^\"[^\"]+\"$" nil))]
    (is (= #rdf/langstring ["\"hund\"" "da"] (parse " \"hund\" " "da"))
        "a text that the pattern of its shape matches is a value")
    (is (nil? (parse "hund" "da")) "and any other text isn't"))
  (is (some? ((:parse-fn (input-type/matching "und" nil)) "hund" nil))
      "a pattern can match anywhere in the text, as in SHACL")
  (is (:lang? input-type/text) "a text can be in a language")
  (is (not (:lang? input-type/resource)) "a resource can't"))
