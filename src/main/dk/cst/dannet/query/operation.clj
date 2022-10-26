(ns dk.cst.dannet.query.operation
  "Pre-built Apache Jena query operation objects (Op)."
  (:require [arachne.aristotle.query :as q]
            [ont-app.vocabulary.core :as voc]

            ;; Prefix registration required for the queries below to build.
            [dk.cst.dannet.prefix]))

(def ^:private sparql
  (comp q/parse voc/prepend-prefix-declarations))

(def entity
  (q/build
    '[:bgp [?s ?p ?o]]))

(def expanded-entity
  (q/build
    '[:conditional
      [:conditional
       [:bgp [?s ?p ?o]]
       [:bgp [?p :rdfs/label ?pl]]]
      [:bgp [?o :rdfs/label ?ol]]]))

(def synonyms
  (q/build
    '[:bgp
      [?form :ontolex/writtenRep ?lemma]
      [?word :ontolex/canonicalForm ?form]
      [?word :ontolex/evokes ?synset]
      [?word* :ontolex/evokes ?synset]
      [?word* :ontolex/canonicalForm ?form*]
      [?form* :ontolex/writtenRep ?synonym]]))

(def alt-representations
  "Certain words contain alternative written representations."
  (q/build
    '[:bgp
      [?form :ontolex/writtenRep ?written-rep]
      [?form :ontolex/writtenRep ?alt-rep]]))

(def registers
  (q/build
    '[:bgp
      [?sense :lexinfo/usageNote ?register]]))

(def sense-label-targets
  "Used during initial graph creation to attach labels to senses."
  (q/build
    '[:bgp
      [?word :ontolex/sense ?sense]
      [?word :rdfs/label ?word-label]
      [?synset :ontolex/lexicalizedSense ?sense]
      [?synset :rdfs/label ?synset-label]]))

(def example-targets
  "Used during initial graph creation to attach examples to senses."
  (q/build
    '[:bgp
      [?word :ontolex/evokes ?synset]
      [?word :ontolex/canonicalForm ?form]
      [?form :ontolex/writtenRep ?lemma]
      [?word :ontolex/sense ?sense]
      [?synset :ontolex/lexicalizedSense ?sense]]))

(def examples
  (q/build
    '[:bgp
      [?form :ontolex/writtenRep ?lemma]
      [?word :ontolex/canonicalForm ?form]
      [?word :ontolex/sense ?sense]
      [?sense :lexinfo/senseExample ?example]
      [?example :rdf/value ?example-str]]))

(def synsets
  (q/build
    '[:bgp
      [?synset :rdf/type :ontolex/LexicalConcept]]))

(def synset-relations
  (q/build
    '[:bgp
      [?s1 :rdfs/label ?l1]
      [?s1 ?relation ?s2]
      [?s2 :rdfs/label ?l2]]))

(def synset-search
  "Look up synsets based on a lemma."
  (q/build
    [:conditional
     [:conditional
      [:conditional
       '[:bgp
         [?form :ontolex/writtenRep ?lemma]
         [?word :ontolex/canonicalForm ?form]
         [?word :ontolex/evokes ?synset]
         [?word :ontolex/evokes ?synset]]
       '[:bgp
         [?synset :rdfs/label ?label]]]
      '[:bgp
        [?synset :skos/definition ?definition]]]
     '[:bgp
       [?synset :dns/ontologicalType ?ontotype]]]))

(def synset-search-labels
  (q/build
    [:bgp
     [:rdf/value :rdfs/label '?synset]
     [:skos/definition :rdfs/label '?definition]
     [:dns/ontologicalType :rdfs/label '?ontotype]]))

(def ontotype-labels
  (q/build
    [:union
     [:bgp
      ['?ontotype :rdfs/label '?label]
      ['?ontotype :rdf/type :dns/DanNetConcept]]
     [:bgp
      ['?ontotype :rdfs/label '?label]
      ['?ontotype :rdf/type :dns/EuroWordNetConcept]]]))

(def written-representations
  (q/build
    [:bgp
     '[?form :ontolex/writtenRep ?writtenRep]]))

(def word-clones
  (q/build
    '[:filter (not= ?w1 ?w2)
      [:bgp
       [?w1 :ontolex/canonicalForm ?f1]
       [?f1 :ontolex/writtenRep ?writtenRep]
       [?f2 :ontolex/writtenRep ?writtenRep]
       [?w2 :ontolex/canonicalForm ?f2]
       [?w1 :lexinfo/partOfSpeech ?pos]
       [?w2 :lexinfo/partOfSpeech ?pos]]]))

(def unlabeled-senses
  (sparql
    "SELECT ?synset ?sense ?label
     WHERE {
       ?synset rdfs:label ?label .
       ?synset ontolex:lexicalizedSense ?sense .
       NOT EXISTS {
         ?sense rdfs:label ?missing .
       }
     }"))

(def csv-synsets
  "Columns to export for synsets.csv."
  (q/build
    '[:bgp
      [?synset :rdf/type :ontolex/LexicalConcept]
      [?synset :skos/definition ?definition]
      [?synset :dns/ontologicalType ?ontotype]]))

(def csv-words
  "Columns to export for words.csv."
  (q/build
    '[:bgp
      [?form :rdf/type :ontolex/Form]
      [?form :ontolex/writtenRep ?written-rep]
      [?word :ontolex/canonicalForm ?form]
      [?word :lexinfo/partOfSpeech ?pos]
      [?word :rdf/type ?rdf-type]]))

(def csv-senses
  (sparql
    "SELECT ?sense ?synset ?word ?note
     WHERE {
       ?sense rdf:type ontolex:LexicalSense .
       ?synset ontolex:lexicalizedSense ?sense .
       ?word ontolex:sense ?sense .
       OPTIONAL
         { ?sense lexinfo:usageNote ?note . }
     }"))

(def csv-inheritance
  (q/build
    '[:bgp
      [?synset :dns/inherited ?inherit]
      [?inherit :dns/inheritedFrom ?from]
      [?inherit :dns/inheritedRelation ?rel]]))

(def csv-examples
  (q/build
    '[:bgp
      [?sense :lexinfo/senseExample ?example]]))
