(ns dk.cst.dannet.web.ui.edit.node-test
  "Tests for the text that names a blank node in edit mode."
  (:require [clojure.test :refer [deftest is]]
            [dk.cst.dannet.web.ui.edit.node :as node]))

(deftest node-texts
  (let [opts {:languages ["da"]
              :k->label  {:marl/Negative  #rdf/langstring ["negativ" "da"]
                          :dns/sentiment  #rdf/langstring ["sentiment" "da"]}}]
    (is (= "snakke sammen, snakke ˈsammen"
           (node/node-text opts :ontolex/otherForm
                           ["snakke sammen" "snakke ˈsammen" "snakke sammen"]))
        "a node is named by its texts, each of them once")
    (is (= "negativ" (node/node-text opts :dns/sentiment [:marl/Negative]))
        "a resource among its values by its label")
    (is (= "sentiment" (node/node-text opts :dns/sentiment []))
        "and a node without values by the name of its row")))
