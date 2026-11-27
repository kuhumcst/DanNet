(ns dk.cst.dannet.web.ui.edit-test
  "Tests for what the table reads from edit mode about a row and about the
  rows that it adds."
  (:require [clojure.test :refer [deftest is testing]]
            [dk.cst.dannet.web.ui.edit :as edit]))

(def opts
  {:edit-mode      true
   :subject        :dn/synset-1
   :default-origin :dn
   :origins        {:wn/hyponym :inferred
                    :wn/lexfile :other}
   :constraints    {:wn/lexfile {:must {:max 1}}}
   :comments       {:locked {:inferred "inferred" :elsewhere "elsewhere"}}})

(deftest row-locks
  (is (nil? (edit/row-lock (dissoc opts :edit-mode) :skos/definition "et dyr"))
      "a table that isn't edited has no fields and no locks")
  (is (:edit? (edit/row-lock opts :skos/definition "et dyr"))
      "a row with a value that can be edited lists its values as fields")
  (let [{:keys [edit? locked? why]}
        (edit/row-lock opts :wn/hyponym #{:dn/synset-2 :dn/synset-3})]
    (is (not edit?))
    (is locked? "a row without one is faded")
    (is (= "inferred" why) "and its padlock says why, once for each reason"))
  (is (:edit? (edit/row-lock (assoc opts :open-row? true) :wn/hyponym []))
      "an open row takes a new value though it has none to edit")
  (testing "a row of blank nodes that no shape describes"
    (let [node (with-meta {:rdf/about '_b1 :rdfs/label "x"}
                          {:default-origin :dn})
          {:keys [edit? locked?]} (edit/row-lock opts :dns/unknown node)]
      (is (not edit?))
      (is (not locked?)
          "isn't locked when the values of its nodes can be edited"))))

(deftest table-rows
  (let [addable [:skos/definition :wn/hypernym :wn/lexfile]
        opts    (assoc opts :addable addable)]
    (testing "a table without edits"
      (let [rows (edit/table-rows opts [] (atom {}))]
        (is (empty? (:open rows)))
        (is (empty? (:new rows)))
        (is (= addable (:addable rows))
            "a row can be added for every property that the table lacks")))
    (testing "a table with a pending change and a row that the editor added"
      (let [added (atom {:dn/synset-1 [:skos/definition]})
            rows  (edit/table-rows
                    (assoc opts :changes [{:add #{[:dn/synset-1 :wn/hypernym
                                                   :dn/synset-2]}}])
                    [[:rdfs/label "{hund_1§1}"]]
                    added)]
        (is (= [:wn/hypernym :skos/definition] (:open rows))
            "the changed row is open, and then the added one")
        (is (= (:open rows) (:new rows))
            "neither has a row in the data, so both are listed last")
        (is (= [:wn/lexfile] (:addable rows))
            "an open row isn't offered again")
        ((:on-add rows) :wn/lexfile)
        (is (= {:dn/synset-1 [:skos/definition :wn/lexfile]} @added)
            "adding a row keeps it by the subject of its table")))
    (is (= [:skos/definition :wn/hypernym]
           (:addable (edit/table-rows opts
                                      [[:wn/lexfile "noun.animal"]]
                                      (atom {}))))
        "a row that is full of values that can't be edited takes no more")))
