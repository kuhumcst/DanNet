(ns dk.cst.dannet.db.csv
  ;; TODO: eventually, pull the CSV-generating code into this namespace too
  "CSV export functionality."
  (:require [clojure.walk :as walk]
            [clojure.data.json :as json]
            [dk.cst.dannet.prefix :as prefix]))

(defn- expand-kw
  [x]
  (cond
    (and (keyword? x)
         (namespace x))
    (prefix/kw->uri x)

    (symbol? x)
    (str "@" x)

    :else x))

(defn expand-kws
  "Expands any recognised namespaced keywords found in the input `m` to URIs."
  [m]
  (walk/postwalk expand-kw m))

(def synsets-metadata
  {'context "http://www.w3.org/ns/csvw"
   :tables  [{:url "synsets.csv"
              :tableSchema
              {:aboutUrl   (str prefix/dn-uri "{synset}")
               :primaryKey "synset"
               :columns    [{:name   "synset"
                             :titles "Synset"}

                            {:name        "definition"
                             :titles      "Definition"
                             :propertyUrl :skos/definition}

                            {:name        "ontotype"
                             :titles      "Ontological Type(s)"
                             :separator   "; "
                             :propertyUrl :dnc/ontologicalType}

                            {:virtual     true
                             :propertyUrl "rdf:type"
                             :valueUrl    :ontolex/LexicalConcept}]}}]
   :dialect {:header false}})

(def words-metadata
  {'context "http://www.w3.org/ns/csvw"
   :tables  [{:url "words.csv"
              :tableSchema
              {:aboutUrl   (str prefix/dn-uri "{word}")
               :primaryKey "word"
               :columns    [{:name   "word"
                             :titles "Word"}

                            {:name        "form"
                             :titles      "Written representation"
                             :propertyUrl :ontolex/writtenRep}

                            {:name        "pos"
                             :titles      "Part-of-speech"
                             :propertyUrl :lexinfo/partOfSpeech}

                            {:virtual     true
                             :propertyUrl "rdf:type"
                             :valueUrl    :ontolex/LexicalEntry}]}}]
   :dialect {:header false}})

(def senses-metadata
  {'context "http://www.w3.org/ns/csvw"
   :tables  [{:url "senses.csv"
              :tableSchema
              {:aboutUrl   (str prefix/dn-uri "{sense}")
               :primaryKey "sense"
               :columns    [{:name   "sense"
                             :titles "Sense"}

                            {:name        "synset"
                             :titles      "Synset"
                             :propertyUrl :ontolex/isLexicalizedSenseOf}

                            {:name        "word"
                             :titles      "Word"
                             :propertyUrl :ontolex/isSenseOf}

                            {:name        "note"
                             :titles      "Note"
                             :propertyUrl :lexinfo/usageNote}

                            {:virtual     true
                             :propertyUrl "rdf:type"
                             :valueUrl    :ontolex/LexicalSense}]}}]
   :dialect {:header false}})

(def inheritance-metadata
  {'context "http://www.w3.org/ns/csvw"
   :tables  [{:url "senses.csv"
              :tableSchema
              {:columns [{:name   "to"
                          :titles "To synset"}

                         {:name        "relation"
                          :propertyUrl :dns/inheritedRelation
                          :titles      "Inherited relation"}

                         {:name        "from"
                          :propertyUrl :dns/inheritedFrom
                          :titles      "From synset"}

                         {:virtual     true
                          :propertyUrl "rdf:type"
                          :valueUrl    :dns/Inheritance}]}}]
   :dialect {:header false}})

(def examples-metadata
  {'context "http://www.w3.org/ns/csvw"
   :tables  [{:url "examples.csv"
              :tableSchema
              {:columns [{:name   "sense"
                          :titles "Sense"}

                         {:name        "example"
                          :propertyUrl :lexinfo/senseExample
                          :titles      "Example"}]}}]
   :dialect {:header false}})

(def relations-metadata
  {'context "http://www.w3.org/ns/csvw"
   :tables  [{:url "examples.csv"
              :tableSchema
              {:columns [{:name   "from"
                          :titles "From synset"}

                         {:name   "relation"
                          :titles "Relation"}

                         {:name   "to"
                          :titles "To synset"}]}}]
   :dialect {:header false}})

(defn metadata->json
  "Convert a `metadata` map into the JSON-LD format used for CSVW metadata."
  [metadata]
  (->> (expand-kws metadata)
       (json/pprint)
       (with-out-str)))

(defn export-metadata!
  "Export a `metadata` map as the given `f`."
  [f metadata]
  (println "Exporting" f)
  (spit f (metadata->json metadata)))

(comment
  (expand-kws x)
  (spit "synsets-metadata.json" (metadata->json synsets-metadata))
  (export-metadata! "synsets-metadata.json" synsets-metadata)
  #_.)
