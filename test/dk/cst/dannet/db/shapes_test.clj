(ns dk.cst.dannet.db.shapes-test
  "Fixture-based tests for the SHACL shapes. These are self-contained: they
  validate small in-memory graphs and do not require a running database."
  (:require [clojure.test :refer [deftest is testing]]
            [dk.cst.dannet.db.shapes :as shapes])
  (:import [org.apache.jena.graph Factory]
           [org.apache.jena.riot RDFParser Lang]
           [java.io ByteArrayInputStream]
           [java.nio.charset StandardCharsets]))

(defn- ttl->graph
  "Parse a Turtle `ttl` string into an in-memory Jena Graph."
  [^String ttl]
  (let [g (Factory/createDefaultGraph)]
    (.. (RDFParser/source (ByteArrayInputStream. (.getBytes ttl StandardCharsets/UTF_8)))
        (lang Lang/TTL)
        (parse g))
    g))

(def ^:private good-ttl "
@prefix ontolex: <http://www.w3.org/ns/lemon/ontolex#> .
@prefix rdfs:    <http://www.w3.org/2000/01/rdf-schema#> .
@prefix wn:      <https://globalwordnet.github.io/schemas/wn#> .
@prefix dn:      <https://wordnet.dk/dannet/data/> .
dn:entry-1 a ontolex:LexicalEntry ;
  rdfs:label \"hund\" ; wn:partOfSpeech wn:noun ;
  ontolex:sense dn:sense-1 ; ontolex:canonicalForm dn:form-1 .
dn:sense-1 a ontolex:LexicalSense ; rdfs:label \"hund\" .")

(def ^:private bad-ttl "
@prefix ontolex: <http://www.w3.org/ns/lemon/ontolex#> .
@prefix dn:      <https://wordnet.dk/dannet/data/> .
dn:sense-2 a ontolex:LexicalSense .")

(deftest shapes-detect-violations
  (testing "well-formed entry + sense conforms"
    (is (:conforms? (shapes/validate (ttl->graph good-ttl)))))
  (testing "a sense with no label is flagged with its focus node"
    (let [{:keys [conforms? entries]} (shapes/validate (ttl->graph bad-ttl))]
      (is (false? conforms?))
      (is (some #(= :dn/sense-2 (:focus-node %)) entries)))))
