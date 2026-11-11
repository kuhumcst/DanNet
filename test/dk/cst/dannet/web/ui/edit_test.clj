(ns dk.cst.dannet.web.ui.edit-test
  "Tests for the order of the fields of an edit row."
  (:require [clojure.test :refer [deftest is]]
            [dk.cst.dannet.web.ui.edit :as edit]))

(deftest value-order
  (is (= ["a" "b"] (edit/value-order ["a" "b"] {}))
      "the values as they are, without changes")
  (is (= ["a2" "b" "d"]
         (edit/value-order ["a" "b"] {:added     #{"a2" "d"}
                                      :retracted #{"a" "b"}
                                      :replaced  {"a" ["a2"]}}))
      "a replacement in place of its value, a removed value in its place and
      the other additions last")
  (is (= ["b"]
         (edit/value-order ["a" "b"] {:added     #{"b"}
                                      :retracted #{"a"}
                                      :replaced  {"a" ["b"]}}))
      "a replacement that is already a value is listed once"))
