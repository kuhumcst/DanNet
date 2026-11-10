(ns dk.cst.dannet.db.change-test
  (:require [clojure.test :refer [deftest is testing]]
            [dk.cst.theophrastus.term :as term]
            [dk.cst.dannet.db.change :as db.change]
            [dk.cst.dannet.test-util :as util]))

(def ^:private g
  (util/ttl->db "
@prefix dn: <https://wordnet.dk/dannet/data/> .
@prefix ontolex: <http://www.w3.org/ns/lemon/ontolex#> .
dn:word-1 a ontolex:Word ;
  ontolex:canonicalForm [ ontolex:writtenRep \"hund\"@da ] .
dn:word-2 ontolex:canonicalForm [ ontolex:writtenRep \"kat\"@da ] ,
                                [ ontolex:writtenRep \"kat\"@da ] ."))

(def ^:private hund #rdf/langstring ["hund" "da"])
(def ^:private hunde #rdf/langstring ["hunde" "da"])

(defn- matches
  "The number of matches that made `change` fail to resolve in g."
  [change]
  (try
    (db.change/resolve-change g change)
    nil
    (catch clojure.lang.ExceptionInfo e
      (:matches (ex-data e)))))

(deftest resolving-changes
  (testing "a :where that matches once binds its variables"
    (let [{:keys [retract add]} (db.change/resolve-change
                                  g {:where   [[:dn/word-1 :ontolex/canonicalForm '?f]
                                               ['?f :ontolex/writtenRep hund]]
                                     :retract #{['?f :ontolex/writtenRep hund]}
                                     :add     #{['?f :ontolex/writtenRep hunde]}})
          [[form _ _]] (seq retract)]
      (is (term/blank-node? form))
      (is (= #{[form :ontolex/writtenRep hund]} retract))
      (is (= #{[form :ontolex/writtenRep hunde]} add))))
  (testing "a plain change stays as it is"
    (is (= {:add #{[:dn/word-1 :ontolex/sense :dn/sense-1]}}
           (db.change/resolve-change
             g {:add #{[:dn/word-1 :ontolex/sense :dn/sense-1]}}))))
  (testing "a :where without variables checks that its triples are there"
    (is (= {:add #{[:dn/word-1 :ontolex/sense :dn/sense-1]}}
           (db.change/resolve-change
             g {:where [[:dn/word-1 :rdf/type :ontolex/Word]]
                :add   #{[:dn/word-1 :ontolex/sense :dn/sense-1]}})))
    (is (= 0 (matches {:where [[:dn/word-2 :rdf/type :ontolex/Word]]
                       :add   #{[:dn/word-2 :ontolex/sense :dn/sense-2]}}))))
  (testing "a stale change matches nothing"
    (is (= 0 (matches {:where   [[:dn/word-1 :ontolex/canonicalForm '?f]
                                 ['?f :ontolex/writtenRep hunde]]
                       :retract #{['?f :ontolex/writtenRep hunde]}}))))
  (testing "duplicate blank nodes match more than once"
    (is (= 2 (matches {:where   [[:dn/word-2 :ontolex/canonicalForm '?f]]
                       :retract #{['?f :ontolex/writtenRep
                                   #rdf/langstring ["kat" "da"]]}})))))
