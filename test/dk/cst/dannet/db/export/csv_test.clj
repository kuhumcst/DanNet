(ns dk.cst.dannet.db.export.csv-test
  "Fixture-based tests for the CSV export. These are self-contained: a small
  dn: graph is exported into a temporary directory and the files are read back,
  with no database in sight."
  (:require [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.db.export.csv :as export]
            [dk.cst.dannet.test-util :as util])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [org.apache.jena.query DatasetFactory]
           [org.apache.jena.rdf.model ModelFactory]))

(def prefixes "
@prefix rdf:     <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
@prefix rdfs:    <http://www.w3.org/2000/01/rdf-schema#> .
@prefix skos:    <http://www.w3.org/2004/02/skos/core#> .
@prefix ontolex: <http://www.w3.org/ns/lemon/ontolex#> .
@prefix wn:      <https://globalwordnet.github.io/schemas/wn#> .
@prefix dn:      <https://wordnet.dk/dannet/data/> .
@prefix dns:     <https://wordnet.dk/dannet/schema/> .
@prefix dnc:     <https://wordnet.dk/dannet/concepts/> .
@prefix dnt:     <https://wordnet.dk/dannet/types/> .
")

(def fixture
  "Four synsets covering every combination of a present or absent definition
  and ontological type, each evoked by one word; synset-1 relates to synset-4,
  the synset with neither."
  "
dn:synset-1 a ontolex:LexicalConcept ; rdfs:label \"{hund_1}\" ;
  skos:definition \"husdyr\"@da ; dns:ontologicalType dnt:Animal-Object ;
  ontolex:lexicalizedSense dn:sense-1 ; wn:hypernym dn:synset-4 .
dn:synset-2 a ontolex:LexicalConcept ; rdfs:label \"{kat_1}\" ;
  dns:ontologicalType dnt:Animal-Object ; ontolex:lexicalizedSense dn:sense-2 .
dn:synset-3 a ontolex:LexicalConcept ; rdfs:label \"{mus_1}\" ;
  skos:definition \"gnaver\"@da ; ontolex:lexicalizedSense dn:sense-3 .
dn:synset-4 a ontolex:LexicalConcept ; rdfs:label \"{dyr_1}\" ;
  ontolex:lexicalizedSense dn:sense-4 .
dnt:Animal-Object a dns:OntologicalType ; rdfs:label \"Animal + Object\"@en ;
  rdf:_1 dnc:Animal ; rdf:_2 dnc:Object .
dn:sense-1 a ontolex:LexicalSense ; rdfs:label \"hund_1\" .
dn:sense-2 a ontolex:LexicalSense ; rdfs:label \"kat_1\" .
dn:sense-3 a ontolex:LexicalSense ; rdfs:label \"mus_1\" .
dn:sense-4 a ontolex:LexicalSense ; rdfs:label \"dyr_1\" .
dn:word-1 a ontolex:Word ; wn:partOfSpeech wn:noun ;
  ontolex:sense dn:sense-1 ; ontolex:canonicalForm dn:form-1 .
dn:word-2 a ontolex:Word ; wn:partOfSpeech wn:noun ;
  ontolex:sense dn:sense-2 ; ontolex:canonicalForm dn:form-2 .
dn:word-3 a ontolex:Word ; wn:partOfSpeech wn:noun ;
  ontolex:sense dn:sense-3 ; ontolex:canonicalForm dn:form-3 .
dn:word-4 a ontolex:Word ; wn:partOfSpeech wn:noun ;
  ontolex:sense dn:sense-4 ; ontolex:canonicalForm dn:form-4 .
dn:form-1 ontolex:writtenRep \"hund\"@da .
dn:form-2 ontolex:writtenRep \"kat\"@da .
dn:form-3 ontolex:writtenRep \"mus\"@da .
dn:form-4 ontolex:writtenRep \"dyr\"@da .
")

(defn- ttl->dataset
  "An in-memory dataset holding `ttl` as its dn: graph."
  [ttl]
  (doto (DatasetFactory/createTxnMem)
    (.addNamedModel prefix/dn-uri
                    (ModelFactory/createModelForGraph
                      (util/ttl->graph (str prefixes ttl))))))

(defn- export-tables!
  "Export `ttl` as CSV into a fresh temporary directory and read the tables
  back as a map from table name to rows."
  [ttl]
  (let [dir (Files/createTempDirectory "dannet-csv" (make-array FileAttribute 0))]
    (try
      (with-out-str
        (export/export-csv! {:dataset (ttl->dataset ttl)} (str dir "/")))
      (into {}
            (for [table ["synsets" "words" "senses" "relations"]]
              [table (with-open [reader (io/reader (str dir "/" table ".csv"))]
                       (doall (csv/read-csv reader)))]))
      (finally
        (run! io/delete-file (reverse (file-seq (.toFile dir))))))))

(deftest synsets-table
  (let [{:strs [synsets senses relations words]} (export-tables! fixture)
        synset-ids (set (map first synsets))]
    (testing "one row per synset, with or without definition and ontotype"
      (is (= 4 (count synsets)))
      (is (= {"synset-1" ["husdyr" "Animal; Object"]
              "synset-2" ["" "Animal; Object"]
              "synset-3" ["gnaver" ""]
              "synset-4" ["" ""]}
             (into {} (map (juxt first (comp vec rest))) synsets))))
    (testing "every synset referenced by the other tables has a row"
      (is (= 4 (count senses)))
      (is (every? synset-ids (map second senses)))
      (is (= [["synset-1" (prefix/kw->uri :wn/hypernym) "synset-4"]] relations))
      (is (every? synset-ids (mapcat (juxt first last) relations))))
    (testing "every word referenced by the senses has a row"
      (is (every? (set (map first words)) (map #(nth % 2) senses))))))
