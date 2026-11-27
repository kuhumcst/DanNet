(ns dk.cst.dannet.web.ui.edit.control-test
  "Tests for the buttons and the mark of a field in edit mode."
  (:require [clojure.test :refer [deftest is]]
            [dk.cst.dannet.web.ui.edit.control :as control]))

(deftest field-buttons
  (is (= [:edit :remove] (control/field-buttons false nil))
      "a value as the data has it can be edited and removed")
  (is (= [:edit :undo :remove] (control/field-buttons false :edited))
      "an edited value can also be put back")
  (is (= [:edit :remove] (control/field-buttons false :added))
      "an added value has no earlier state, so it is taken away again")
  (is (= [:edit :undo :remove] (control/field-buttons false :moved))
      "a moved value can be put back in its row, as an edited one can")
  (is (= [:undo] (control/field-buttons false :retracted))
      "a removed value can only be put back")
  (is (= [] (control/field-buttons true nil))
      "an input has no buttons of the field")
  (is (= [:undo] (control/field-buttons true :edited))
      "unless the field differs from the data")
  (is (= [:remove] (control/field-buttons true :added))
      "and an added value can be taken away from its input too"))

(deftest required-marks
  (let [[_ attrs] (control/required-mark ["en"] {:min 1 :max 1})]
    (is (= "The row needs exactly one value, so it can be edited but not removed"
           (:title attrs)
           (:aria-label attrs))
        "the tooltip of the mark says why the value stays"))
  (is (= "The row needs at least one value, so the last one can't be removed"
         (control/required-message ["en"] {:min 1}))))
