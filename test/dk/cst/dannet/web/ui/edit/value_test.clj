(ns dk.cst.dannet.web.ui.edit.value-test
  "Tests for the notes of a value in edit mode."
  (:require [clojure.test :refer [deftest is testing]]
            [dk.cst.dannet.web.ui.edit.value :as value]))

(deftest removed-notes
  (let [removal {:retract #{[:dn/sense-1 :rdf/type :ontolex/LexicalSense]
                            [:dn/synset-1 :ontolex/lexicalizedSense
                             :dn/sense-1]}}
        opts    {:languages ["da" "en"]}
        opts'   (assoc opts :changes [removal])]
    (testing "a removed link to an entry that is removed as a whole"
      (is (= "Hele ressourcen fjernes"
             (peek (value/removed-note opts' :dn/sense-1)))
          "says that the whole entry goes, since undoing it puts that back")
      (is (nil? (value/removed-note opts' :dn/synset-1)))
      (is (nil? (value/removed-note opts :dn/sense-1))))))
