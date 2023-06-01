(ns dk.cst.dannet.query.operation
  "Pre-built Apache Jena query operation objects (Op)."
  (:require [clojure.string :as str]
            [arachne.aristotle.query :as q]
            [ont-app.vocabulary.core :as voc]
            [dk.cst.dannet.web.components :as com]

            ;; Prefix registration required for the queries below to build.
            [dk.cst.dannet.prefix :as prefix]))

(def sparql
  (comp q/parse voc/prepend-prefix-declarations str))

(def entity
  (q/build
    '[:bgp [?s ?p ?o]]))

(def expanded-entity
  (let [label-rels (str/join " " (map prefix/kw->qname com/label-keys))]
    (sparql
      "SELECT ?s ?p ?o ?pl ?ol ?plr ?olr
     WHERE {
       ?s ?p ?o .
       OPTIONAL {
         VALUES ?plr { " label-rels " }
         ?p ?plr ?pl .
       }
       OPTIONAL {
         VALUES ?olr { " label-rels " }
         ?o ?olr ?ol .
       }
     }")))

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
  (sparql
    "SELECT ?sense ?wlabel ?slabel
     WHERE {
       ?word ontolex:sense ?sense .
       FILTER NOT EXISTS { ?sense rdfs:label ?label }
       ?word rdfs:label ?wlabel .
       ?synset ontolex:lexicalizedSense ?sense .
       ?synset rdfs:label ?slabel .
     }"))

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

;; TODO: use for systematic polysemy?
(def synset-intersection
  (sparql
    "SELECT ?sense ?word ?label ?synset ?otherSynset
     WHERE {
        ?synset ontolex:lexicalizedSense ?sense .
        ?synset rdfs:label ?synsetLabel .
        ?synset skos:definition ?synsetDefinition .
        ?synset dns:ontologicalType ?ontotype .
        ?otherSynset ontolex:lexicalizedSense ?sense .
        ?sense rdfs:label ?label .
        ?word ontolex:sense ?sense .
        ?otherSynset rdfs:label ?otherSynsetLabel .
        ?otherSynset skos:definition ?otherSynsetDefinition .
        FILTER (?synset != ?otherSynset) .
        OPTIONAL {
          ?otherSynset dns:ontologicalType ?otherOntotype .
        }
     }"))

(def self-referential-hypernyms
  (q/build
    '[:union
      [:bgp [?synset :wn/hypernym ?synset]]
      [:bgp [?synset :wn/hyponym ?synset]]]))

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

(def synset-relabeling
  (q/build
    '[:bgp
      [?synset :rdf/type :ontolex/LexicalConcept]
      [?synset :rdfs/label ?synsetLabel]
      [?synset :ontolex/lexicalizedSense ?sense]
      [?sense :rdfs/label ?label]]))

(def duplicate-synsets
  "Duplicate synsets based on same label, definition, and ontological type."
  (q/build
    '[:filter (not= ?s1 ?s2)
      [:bgp
       [?s1 :rdf/type :ontolex/LexicalConcept]
       [?s2 :rdf/type :ontolex/LexicalConcept]
       [?s1 :rdfs/label ?label]
       [?s2 :rdfs/label ?label]
       [?s1 :skos/definition ?definition]
       [?s2 :skos/definition ?definition]
       [?s1 :dns/ontologicalType ?ontotype]
       [?s2 :dns/ontologicalType ?ontotype]]]))

(def missing-sense-sentiment
  (sparql
    "SELECT ?sense ?word ?opinion
     WHERE {
       ?sense rdf:type ontolex:LexicalSense .
       NOT EXISTS {
         ?sense dns:sentiment ?missing .
       }
       ?word ontolex:sense ?sense .
       ?word dns:sentiment ?opinion .
     }"))

(def missing-synset-sentiment
  (sparql
    "SELECT ?sense ?opinion ?pval ?pclass ?synset
     WHERE {
       ?sense rdf:type ontolex:LexicalSense .
       ?sense dns:sentiment ?opinion .
       ?opinion marl:hasPolarity ?pclass .
       ?opinion marl:polarityValue ?pval .
       ?synset ontolex:lexicalizedSense ?sense .
     }"))

(def sentiment-dsl-senses
  "Bridge sentiment data using old sense IDs and new sense IDs."
  (sparql
    "SELECT ?sense ?sentiment ?oldSense
     WHERE {
       ?sense dns:dslSense ?dslSense .
       BIND(IRI(CONCAT(\"" prefix/dn-uri "sense-\", STR(?dslSense))) as ?oldSense) .
       ?oldSense dns:sentiment ?sentiment .
     }"))

(def cor-dsl-senses
  "Bridge COR data using old sense IDs and new sense IDs."
  (sparql
    "SELECT ?sense ?oldSense ?corWord
     WHERE {
       ?sense dns:dslSense ?dslSense .
       BIND(IRI(CONCAT(\"" prefix/dn-uri "sense-\", STR(?dslSense))) as ?oldSense) .
       ?corWord ontolex:sense ?oldSense .
     }"))

(def oewn-label-targets
  (sparql
    "SELECT ?synset ?sense ?word ?rep
     WHERE {
       ?form ontolex:writtenRep ?rep .
       ?word ontolex:canonicalForm ?form .
       ?word ontolex:sense ?sense .
       ?sense ontolex:isLexicalizedSenseOf ?synset .
     }"))

(def missing-words
  (sparql
    "SELECT ?sense ?synset ?label
     WHERE {
       ?synset ontolex:lexicalizedSense ?sense .
       FILTER NOT EXISTS { ?word ontolex:sense ?sense }
       ?sense rdfs:label ?label
     }"))

(def missing-inheritance
  (sparql
    "SELECT ?synset ?ontotype ?hypernym
     WHERE {
       ?synset dns:inherited ?inherit .
       ?inherit dns:inheritedRelation wn:hypernym .
       ?inherit dns:inheritedFrom ?parent .
       ?parent dns:ontologicalType ?ontotype .
       ?parent wn:hypernym ?hypernym .
     }"))

(def unknown-inheritance
  (sparql
    "SELECT ?synset ?inherit
     WHERE {
       ?synset dns:inherited ?inherit .
       ?inherit dns:inheritedFrom ?parent .
       FILTER NOT EXISTS { ?parent ?anything ?atAll }
     }"))

(def superfluous-definitions
  "Synset definitions that are fully contained within other definitions;
  this situation occurs due to the merge of the old data with the 2023 data."
  (sparql
    "SELECT ?synset ?definition ?otherDefinition
     WHERE {
       ?synset skos:definition ?definition .
       FILTER(CONTAINS(?definition, \"…\"))
       ?synset skos:definition ?otherDefinition .
       FILTER(?definition != ?otherDefinition)
     }"))

(def undefined-synset-triples
  "Synsets that are objects of other synsets, but do not exist as subjects."
  (sparql
    "SELECT ?synset ?p ?otherResource
     WHERE {
       ?synset rdf:type ontolex:LexicalConcept .
       ?synset ?p ?otherResource .
       FILTER(isIRI(?otherResource)) .
       FILTER(STRSTARTS(str(?otherResource), str(dn:))) .
       NOT EXISTS {
         ?otherResource ?anything ?atAll .
       }
     }"))

(def orphan-dn-resources
  "The COR/DNS data references many resources that are undefined in Dannet."
  (sparql
    "SELECT ?resource
     WHERE {
       {
         ?corWord ontolex:sense ?resource .
         FILTER(isIRI(?resource)) .
       }
       UNION
       {
         ?corWord owl:sameAs ?resource .
         FILTER(isIRI(?resource)) .
       }
       UNION
       {
         ?resource dns:sentiment ?sentiment .
         FILTER(isIRI(?resource)) .
       }
       NOT EXISTS {
         ?resource rdf:type ?type .
       }
     }"))

(def sense-labels
  (sparql
    "SELECT ?sense ?label
     WHERE {
      ?synset ontolex:lexicalizedSense ?sense .
      ?sense rdfs:label ?label
     }"))

;; NOTE: similar query to 'sense-labels' above
(def synset-labels
  (sparql
    "SELECT ?synset ?label
     WHERE {
      ?synset ontolex:lexicalizedSense ?sense .
      ?sense rdfs:label ?label
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
      [?word :ontolex/canonicalForm ?form]
      [?form :ontolex/writtenRep ?written-rep]
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
