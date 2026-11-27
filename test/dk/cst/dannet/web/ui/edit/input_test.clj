(ns dk.cst.dannet.web.ui.edit.input-test
  "Tests for what an input of edit mode does with an entered text, and for
  its messages."
  (:require [clojure.test :refer [deftest is testing]]
            [dk.cst.dannet.web.ui.edit.input :as input]))

(deftest invalid-messages
  (let [description [#rdf/langstring ["Skal være cifre" "da"]
                     #rdf/langstring ["Must be digits" "en"]]]
    (is (= "Skal være cifre"
           (input/invalid-message ["da" "en"] {:description description}))
        "what a value has to be, in the language of the reader")
    (is (= "Must be digits"
           (input/invalid-message ["en"] {:description description})))
    (is (= "Not a valid value" (input/invalid-message ["en"] {}))
        "an input type without a description has a general message")))

(deftest entered-texts
  (let [entered #(input/entered-text %1 (assoc %2 :start "hund"))]
    (testing "an empty text"
      (is (= :empty (entered "" {:removable? true}))
          "takes away a value that can be removed")
      (is (= :refuse (entered "" {:required? true}))
          "is refused when the row must keep the value")
      (is (= :cancel (entered "" {:required? true :leaving? true}))
          "but nothing is refused when the focus leaves")
      (is (= :cancel (entered "" {}))
          "and is nothing new in the field of a new value"))
    (testing "a text"
      (is (= :cancel (entered "hund" {}))
          "is nothing new when the input started with it")
      (is (= :parse (entered "hund" {:retagged? true}))
          "unless another language is chosen for it")
      (is (= :parse (entered "hunde" {}))
          "and any other text is parsed as a value"))))

(deftest entered-values
  (let [removal {:retract #{[:dn/sense-1 :rdf/type :ontolex/LexicalSense]}}
        entered #(input/entered-value %1 (assoc %2 :taken [:dn/synset-2]))]
    (is (= :invalid (entered nil {}))
        "a text that isn't a value has no value")
    (is (= :value (entered :dn/synset-3 {}))
        "a value that the row doesn't have is entered")
    (testing "a value that the row has already"
      (is (= :refuse (entered :dn/synset-2 {}))
          "is refused on Enter")
      (is (= :cancel (entered :dn/synset-2 {:leaving? true}))
          "and dropped when the focus leaves"))
    (is (= :refuse (entered :dn/sense-1 {:changes [removal]}))
        "an entry that is being removed is refused too")))
