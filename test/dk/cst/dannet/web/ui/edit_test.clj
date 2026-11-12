(ns dk.cst.dannet.web.ui.edit-test
  "Tests for the buttons of a field in edit mode."
  (:require [clojure.test :refer [deftest is]]
            [dk.cst.dannet.web.ui.edit :as edit]))

(deftest field-buttons
  (is (= [:edit :remove] (edit/field-buttons false nil))
      "a value as the data has it can be edited and removed")
  (is (= [:edit :undo :remove] (edit/field-buttons false :edited))
      "an edited value can also be put back")
  (is (= [:edit :undo] (edit/field-buttons false :added))
      "an added value isn't in the data, so it has nothing to remove")
  (is (= [:undo] (edit/field-buttons false :retracted))
      "a removed value can only be put back")
  (is (= [] (edit/field-buttons true nil))
      "an input has no buttons of the field")
  (is (= [[:undo] [:undo]]
         (map #(edit/field-buttons true %) [:edited :added]))
      "unless the field differs from the data"))
