(ns dk.cst.dannet.db.query-test
  "Tests for the entities that the pages show."
  (:require [clojure.test :refer [deftest is]]
            [dk.cst.dannet.db.query :as q]))

(deftest supplemented-origins
  (let [form    {:rdf/about '_form :ontolex/writtenRep "hund"}
        origins {:rdfs/seeAlso {:dn/word-3 :inferred}}
        read    (with-meta {:rdf/about             :dn/word-1
                            :rdfs/label            "hund"
                            :rdfs/seeAlso          #{:dn/word-2 :dn/word-3}
                            :ontolex/canonicalForm form}
                           {:origins origins :default-origin :dn})
        entity  (-> (dissoc read :rdf/about)
                    (update :rdfs/seeAlso conj :dn/word-4)
                    (assoc :ontolex/otherForm #{"hunde"}))]
    (is (= {:rdfs/seeAlso      {:dn/word-3 :inferred :dn/word-4 :supplemented}
            :ontolex/otherForm :supplemented}
           (#'q/supplemented-origins entity read))
        "a value that was read keeps its origin, and the others are new")))
