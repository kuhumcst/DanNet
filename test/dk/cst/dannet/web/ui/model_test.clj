(ns dk.cst.dannet.web.ui.model-test
  "Tests for the opts that a page renders from."
  (:require [clojure.test :refer [deftest is]]
            [dk.cst.dannet.web.ui.edit.entry :as entry]
            [dk.cst.dannet.web.ui.model :as model]))

(def ^:private vovse #rdf/langstring ["vovse" "da"])

(deftest pages-of-new-entries
  (let [changes [(entry/new-sense [] :dn/synset-1 :dn/word-1 vovse)]
        state   {:languages ["da" "en" nil] :changes changes}
        draft   (entry/page-data :dn/new-sense-1 {:entities {}})
        opts    (model/render-opts draft state)]
    (is (= :ontolex/LexicalSense (get-in opts [:entity :rdf/type]))
        "the entity of a new entry is made from the pending changes")
    (is (= vovse (get-in opts [:k->label :dn/new-sense-1])))
    (is (nil? (:entity (model/render-opts draft (assoc state :changes []))))
        "and it follows them")
    (let [page (model/render-opts {:subject  :dn/synset-1
                                   :entity   {:rdfs/label "{hund}"}
                                   :entities {}}
                                  state)]
      (is (= {:rdfs/label "{hund}"} (:entity page))
          "the entity of any other page is the one in its data")
      (is (= vovse (get-in page [:k->label :dn/new-sense-1]))
          "which names a new entry by its label in the changes"))))
