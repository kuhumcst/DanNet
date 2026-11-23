(ns dk.cst.dannet.web.ui.edit-test
  "Tests for the buttons, the mark and the messages of a field in edit mode."
  (:require [clojure.test :refer [deftest is]]
            [dk.cst.dannet.web.ui.edit :as edit]))

(deftest field-buttons
  (is (= [:edit :remove] (edit/field-buttons false nil))
      "a value as the data has it can be edited and removed")
  (is (= [:edit :undo :remove] (edit/field-buttons false :edited))
      "an edited value can also be put back")
  (is (= [:edit :remove] (edit/field-buttons false :added))
      "an added value has no earlier state, so it is taken away again")
  (is (= [:edit :undo :remove] (edit/field-buttons false :moved))
      "a moved value can be put back in its row, as an edited one can")
  (is (= [:undo] (edit/field-buttons false :retracted))
      "a removed value can only be put back")
  (is (= [] (edit/field-buttons true nil))
      "an input has no buttons of the field")
  (is (= [:undo] (edit/field-buttons true :edited))
      "unless the field differs from the data")
  (is (= [:remove] (edit/field-buttons true :added))
      "and an added value can be taken away from its input too"))

(deftest required-marks
  (let [[_ attrs] (edit/required-mark ["en"] {:min 1 :max 1})]
    (is (= "The row needs exactly one value, so it can be edited but not removed"
           (:title attrs)
           (:aria-label attrs))
        "the tooltip of the mark says why the value stays"))
  (is (= "The row needs at least one value, so the last one can't be removed"
         (edit/required-message ["en"] {:min 1}))))

(deftest invalid-messages
  (let [description [#rdf/langstring ["Skal være cifre" "da"]
                     #rdf/langstring ["Must be digits" "en"]]]
    (is (= "Skal være cifre"
           (edit/invalid-message ["da" "en"] {:description description}))
        "what a value has to be, in the language of the reader")
    (is (= "Must be digits"
           (edit/invalid-message ["en"] {:description description})))
    (is (= "Not a valid value" (edit/invalid-message ["en"] {}))
        "an input type without a description has a general message")))
