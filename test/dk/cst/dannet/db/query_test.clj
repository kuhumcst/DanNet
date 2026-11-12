(ns dk.cst.dannet.db.query-test
  "Tests for the entities that the pages show."
  (:require [clojure.test :refer [deftest is]]
            [dk.cst.theophrastus :as theo]
            [dk.cst.dannet.db.query :as q]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.test-util :as util]))

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


(deftest blank-node-origins
  (let [db   (util/ttl->dataset "
@prefix dn: <https://wordnet.dk/dannet/data/> .
@prefix ontolex: <http://www.w3.org/ns/lemon/ontolex#> .
dn:word-1 ontolex:canonicalForm [ ontolex:writtenRep \"hund\"@da ] .")
        form (-> (q/expanded-entity (theo/union-graph db) :dn/word-1)
                 :ontolex/canonicalForm)]
    (is (= #{#rdf/langstring ["hund" "da"]} (:ontolex/writtenRep form)))
    (is (= prefix/dn-graph (:default-origin (meta form)))
        "a blank node has the origins of its own values")))
