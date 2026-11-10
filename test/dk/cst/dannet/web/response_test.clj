(ns dk.cst.dannet.web.response-test
  "Tests for the preferences that a page takes from the cookies of a request."
  (:require [clojure.test :refer [deftest is]]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.web.response :as resp]))

(deftest edit-mode-preference
  (let [edit-mode #(:edit-mode (resp/with-cookies {:cookies %} {}))
        on        {"edit-mode" {:value "true"}}]
    (with-redefs [shared/development? true]
      (is (true? (edit-mode on)) "the cookie turns edit mode on")
      (is (not (edit-mode {"edit-mode" {:value "false"}})))
      (is (not (edit-mode {})) "off without the cookie"))
    (with-redefs [shared/development? false]
      (is (not (edit-mode on)) "the cookie doesn't count in production"))))
