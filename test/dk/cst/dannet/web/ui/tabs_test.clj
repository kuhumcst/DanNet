(ns dk.cst.dannet.web.ui.tabs-test
  "Tests for the view that a frame of tabs shows."
  (:require [clojure.test :refer [deftest is]]
            [dk.cst.dannet.web.app :as app]
            [dk.cst.dannet.web.ui.tabs :as tabs]))

(deftest selected-view
  (let [tablist  {:views [{:id :summary} {:id :table :editable? true}]}
        selected #(:id (tabs/selected-view :top tablist %))
        chosen   (assoc-in {} (app/display-path :top) :table)]
    (is (= :summary (selected {})) "the first view")
    (is (= :table (selected chosen)) "the one chosen in the session")
    (is (= :table (selected {:edit-mode true}))
        "the editable one in edit mode")
    (is (= :summary (:id (tabs/selected-view :top
                                             {:views [{:id :summary}
                                                      {:id :diagram}]}
                                             {:edit-mode true})))
        "unless the tablist has none")))
