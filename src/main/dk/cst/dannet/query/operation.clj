(ns dk.cst.dannet.query.operation
  "Pre-built Apache Jena query operation objects (Op)."
  (:require [arachne.aristotle.query :as q]

            ;; Prefix registration required for the queries below to build.
            [dk.cst.dannet.prefix]))

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
      [?sense :lexinfo/usageNote ?blank-node]
      [?blank-node :rdf/value ?register]]))

(def sense-label-targets
  "Used during initial graph creation to attach labels to senses."
  (q/build
    '[:bgp
      [?word :rdfs/label ?label]
      [?word :ontolex/sense ?sense]]))

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