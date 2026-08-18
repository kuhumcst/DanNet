(ns dk.cst.dannet.db.query.operation
  "Pre-built Apache Jena query operation objects (Op)."
  (:require [clojure.string :as str]
            [arachne.aristotle.query :as q]
            [ont-app.vocabulary.core :as voc]
            [dk.cst.dannet.shared :as shared]

            ;; Prefix registration required for the queries below to build.
            [dk.cst.dannet.prefix :as prefix]))

(def sparql
  (comp q/parse voc/prepend-prefix-declarations str))

(def entity
  (q/build
    '[:bgp [?s ?p ?o]]))

(def expanded-entity
  (let [label-rels (str/join " " (map prefix/kw->qname shared/label-keys-short))]
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

(defn resource-labels-query
  "Build a SPARQL Op that fetches labels for a collection of keyword `resources`."
  [resources]
  (let [label-rels (str/join " " (map prefix/kw->qname shared/label-keys-short))
        values     (str/join " " (map #(str "<" (prefix/kw->uri %) ">") resources))]
    (sparql
      "SELECT ?resource ?labelRel ?label
       WHERE {
         VALUES ?resource { " values " }
         VALUES ?labelRel { " label-rels " }
         ?resource ?labelRel ?label .
       }")))

(def synsets
  (q/build
    '[:bgp
      [?synset :rdf/type :ontolex/LexicalConcept]]))

(defn synset-search-query
  "Look up synsets based on a `lemma`, incl. via the inflected forms of any
  COR words linked through owl:sameAs."
  [lemma]
  (sparql
    "SELECT ?form ?word ?synset ?label ?shortLabel ?definition ?ontoType ?lexfile
     WHERE {
       ?form ontolex:writtenRep \"" lemma "\"@da .
       ?word ontolex:canonicalForm|ontolex:otherForm|(owl:sameAs/(ontolex:canonicalForm|ontolex:otherForm)) ?form ;
             ontolex:evokes ?synset .
       OPTIONAL {
         ?synset rdfs:label ?label .
       }
       OPTIONAL {
         ?synset dns:shortLabel ?shortLabel .
       }
       OPTIONAL {
         ?synset skos:definition ?definition .
       }
       OPTIONAL {
         ?synset dns:ontologicalType ?ontoType .
       }
       OPTIONAL {
         ?synset wn:lexfile ?lexfile .
       }
     }"))

(def synset-search-labels
  (q/build
    [:bgp
     [:dc/subject :rdfs/label '?synset]
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

(def ddo-sources
  "All dns:source triples still pointing at the redesigned ordnet.dk
  (GitHub issue #192); the STRSTARTS filter makes rewriting idempotent."
  (sparql
    "SELECT ?s ?src
     WHERE {
       ?s dns:source ?src .
       FILTER(STRSTARTS(STR(?src), \"https://ordnet.dk/ddo\"))
     }"))

(def missing-dsl-sense-sources
  "Senses lacking a dns:source whose DDO deep link can be reconstructed from
  their dns:dslSense (= DDO def_id) and their word's dns:source (issue #192).
  Uses the asserted ontolex:sense direction since release changes run on the
  base model, where the inverse ontolex:isSenseOf is not yet inferred."
  (sparql
    "SELECT ?sense ?dslSense ?wordSource
     WHERE {
       ?sense dns:dslSense ?dslSense .
       ?word ontolex:sense ?sense ;
             dns:source ?wordSource .
       FILTER NOT EXISTS { ?sense dns:source ?senseSource }
     }"))

(def asserted-lexinfo-pos
  "Asserted lexinfo:partOfSpeech triples, which duplicate wn:partOfSpeech 1:1
  (GitHub issue #17); after their deletion the lexinfo triple is derived by
  the value-translating rules in dannet.rules instead."
  (q/build
    '[:bgp [?w :lexinfo/partOfSpeech ?pos]]))

(def missing-written-reps
  "Words whose ontolex:canonicalForm node lacks an ontolex:writtenRep (issue
  #203), i.e. the form is a dangling blank node; the word's rdfs:label binds
  as ?label since the missing representation can be recovered from it."
  (sparql
    "SELECT ?word ?label ?form
     WHERE {
       ?word wn:partOfSpeech ?pos ;
             rdfs:label ?label ;
             ontolex:canonicalForm ?form .
       FILTER NOT EXISTS { ?form ontolex:writtenRep ?rep }
     }"))

(def eq-ili-relations
  "dns:eq* relations targeting an Interlingual Index entry rather than an OEWN
  synset (GitHub issue #205). The eq* relations hold between concepts in
  separate datasets, so the ILI id should be swapped for the OEWN synset
  carrying it; wn:ili remains the only relation that may target the ILI."
  (sparql
    "SELECT ?synset ?rel ?ili
     WHERE {
       VALUES ?rel { dns:eqHypernym dns:eqHyponym dns:eqSimilar }
       ?synset ?rel ?ili .
       FILTER(STRSTARTS(STR(?ili), STR(ili:)))
     }"))

(def scaffolding-lexicalizations
  "The artificial words lexicalizing the EuroWordNet scaffolding synsets {TOP},
  {1stOrder} and {2ndOrder}, along with their senses. The words and senses are
  deleted while the synsets remain as synthetic parents."
  (sparql
    "SELECT ?sense ?word
     WHERE {
       VALUES ?synset { dn:synset-20633 dn:synset-42971 dn:synset-42970 }
       ?synset ontolex:lexicalizedSense ?sense .
       ?word ontolex:sense ?sense .
     }"))

(def temporary-words
  "Words carrying a placeholder dn:word-temporary_N identifier, i.e. words
  without a DDO lemma id in DSL's CSV exports; binds each word's single sense
  as ?sense since its stable id is used to mint a proper word id."
  (sparql
    "SELECT ?word ?sense
     WHERE {
       ?word ontolex:sense ?sense .
       FILTER(STRSTARTS(STR(?word), CONCAT(STR(dn:), \"word-temporary_\")))
     }"))

(def cor-word-forms
  "Inflected forms of the COR words linked to DanNet words via owl:sameAs;
  binds the COR canonical form as ?lemma and each inflected form as ?rep."
  (sparql
    "SELECT ?lemma ?rep
     WHERE {
       ?word owl:sameAs ?corWord .
       ?corWord ontolex:canonicalForm/ontolex:writtenRep ?lemma ;
                ontolex:otherForm/ontolex:writtenRep ?rep .
     }"))

(def word-clones
  (q/build
    '[:filter (not= ?w1 ?w2)
      [:bgp
       [?w1 :ontolex/canonicalForm ?f1]
       [?f1 :ontolex/writtenRep ?writtenRep]
       [?f2 :ontolex/writtenRep ?writtenRep]
       [?w2 :ontolex/canonicalForm ?f2]
       [?w1 :wn/partOfSpeech ?pos]
       [?w2 :wn/partOfSpeech ?pos]]]))

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

(def oewn-label-targets
  (sparql
    "SELECT ?synset ?sense ?word ?rep
     WHERE {
       ?form ontolex:writtenRep ?rep .
       ?word ontolex:canonicalForm ?form .
       ?word ontolex:sense ?sense .
       ?sense ontolex:isLexicalizedSenseOf ?synset .
     }"))

(def csv-synsets
  "Columns to export for synsets.csv."
  (q/build
    '[:bgp
      [?synset :rdf/type :ontolex/LexicalConcept]
      [?synset :skos/definition ?definition]
      [?synset :dns/ontologicalType ?ontotype]
      [?ontotype :rdfs/member ?onto]]))

(def csv-words
  "Columns to export for words.csv."
  (q/build
    '[:bgp
      [?word :ontolex/canonicalForm ?form]
      [?form :ontolex/writtenRep ?written-rep]
      [?word :wn/partOfSpeech ?pos]
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

;; The current version of the query came about after some help from quoll:
;; https://clojurians.slack.com/archives/C09GHBXRC/p1691768521526469?thread_ts=1691410647.536539&cid=C09GHBXRC
;; It takes <10 minutes to complete on my machine. ~sg
(def synset-indegree
  (sparql
    "SELECT ?o (COUNT(*) AS ?indegree)
     WHERE {
       ?o rdf:type ontolex:LexicalConcept .
       ?s ?p ?o .
       FILTER( EXISTS {?s rdf:type ontolex:LexicalConcept} ) .
     }
     GROUP BY ?o"))

;; As of 2023-09-08, 45 relations were in use out of 97 relations total.
;; i.e. a colour space of around 50 or so colours would be appropriate.
(def synset-relation-types
  (sparql
    "SELECT DISTINCT ?rel
     WHERE {
       ?rel rdf:type wn:SynsetRelType .
       ?s ?rel ?o .
     }"))

(defn relation-usage-query
  "Build a query checking whether `rel` is in use as a predicate."
  [rel]
  (sparql
    (str "SELECT ?s WHERE { ?s " (prefix/kw->qname rel) " ?o } LIMIT 1")))

(def synset-long-short-labels
  (sparql
    "SELECT ?synset ?label ?shortLabel
     WHERE {
       ?synset rdf:type ontolex:LexicalConcept .
       FILTER(STRSTARTS(str(?synset), str(dn:))) .
       ?synset rdfs:label ?label .
       OPTIONAL {
         ?synset dns:shortLabel ?shortLabel .
       }
     }"))

(def sense-label-polysemy
  "The polysemy (number of senses) of the word behind every dn: sense label;
  used as a proxy for word commonness when ranking canonical sense labels."
  (sparql
    "SELECT ?senseLabel (COUNT(DISTINCT ?otherSense) AS ?polysemy)
     WHERE {
       ?word ontolex:sense ?sense .
       FILTER(STRSTARTS(str(?word), str(dn:))) .
       ?sense rdfs:label ?senseLabel .
       ?word ontolex:sense ?otherSense .
     }
     GROUP BY ?senseLabel"))

(def cross-pos-hypernymy
  ;; NB: uses wn:partOfSpeech since it runs on the BASE graph, where the
  ;; lexinfo equivalent is no longer asserted (GitHub issue #17).
  (sparql
    "SELECT ?synset ?hypernym
     WHERE {
       ?w1 wn:partOfSpeech ?pos1 ;
           ontolex:evokes ?synset .
       ?synset wn:hypernym ?hypernym .

       # abstract top-level categories
       #FILTER (?hypernym NOT IN (dn:synset-42970, dn:synset-42971, dn:synset-3290))

       ?w2 ontolex:evokes ?hypernym .
       ?w2 wn:partOfSpeech ?pos2 .
       FILTER (?pos1 != ?pos2 )
     }"))

(def catalog-resources
  (sparql
    "SELECT DISTINCT ?source ?label
     WHERE {
       {
         # Nested subquery ensures DISTINCT is applied to catalog resources
         # before the optional label lookup, improving query performance
         SELECT DISTINCT ?source WHERE {
           { ?s rdfs:isDefinedBy ?source }
           UNION
           { ?source vann:preferredNamespaceUri ?uri }
           UNION
           { ?source vann:preferredNamespacePrefix ?prefix }
           UNION
           { ?s skos:inScheme ?source }
           UNION
           { ?source owl:imports ?o }
           UNION
           { ?source a owl:Ontology }
           UNION
           { ?source a skos:ConceptScheme }
           UNION
           { ?source a dcat:Dataset }
           UNION
           # Companion datasets that we cannot modify ourselves, e.g. the
           # OEWN, are typed lime:Lexicon rather than dcat:Dataset (#178).
           { ?source a lime:Lexicon }
           FILTER (!isBlank(?source))
         }
       }
       OPTIONAL {
         ?source rdfs:label|
                 <http://purl.org/dc/terms/title>|
                 <http://purl.org/dc/elements/1.1/title>|
                 foaf:name|
                 skos:prefLabel|
                 dcat:title
                 ?label
       }
     }"))

(def word-query
  (sparql
    "SELECT ?word ?pos ?writtenRep
     WHERE {
       ?word ontolex:canonicalForm ?form ;
             wn:partOfSpeech ?pos .
       ?form ontolex:writtenRep ?writtenRep .
     }"))

(def sense-query
  (sparql
    "SELECT ?word ?sense ?synset
     WHERE {
       ?word ontolex:sense ?sense .
       ?synset ontolex:lexicalizedSense ?sense .
     }"))

(def definition-query
  (sparql
    "SELECT ?synset ?definition
     WHERE { ?synset skos:definition ?definition . }"))

(def example-query
  (sparql
    "SELECT ?sense ?example
     WHERE { ?sense lexinfo:senseExample ?example . }"))

(def domain-query
  (sparql
    "SELECT ?synset ?domain
     WHERE { ?synset dc:subject ?domain . }"))

(def lexfile-query
  (sparql
    "SELECT ?synset ?lexfile
     WHERE { ?synset wn:lexfile ?lexfile . }"))

(def variant-query
  "The written variants of DanNet's own multiword expressions."
  (sparql
    "SELECT ?word ?variant
     WHERE {
       ?word ontolex:otherForm ?form .
       ?form ontolex:writtenRep ?variant .
     }"))

(def ontological-type-query
  (sparql
    "SELECT ?synset ?member ?class
     WHERE {
       ?synset dns:ontologicalType ?bag .
       ?bag ?member ?class .
       FILTER(STRSTARTS(str(?member), CONCAT(str(rdf:), \"_\"))) .
     }"))

(def synset-label-query
  (sparql
    "SELECT ?synset ?label
     WHERE {
       ?synset rdfs:label ?label .
       FILTER(STRSTARTS(str(?synset), CONCAT(str(dn:), \"synset\"))) .
     }"))

(def short-label-query
  (sparql
    "SELECT ?synset ?label
     WHERE { ?synset dns:shortLabel ?label . }"))

(def inverse-query
  (sparql
    "SELECT ?a ?b WHERE { ?a owl:inverseOf ?b . }"))

(defn relation-query
  "Build a query selecting the subject-object pairs of the relation `rel`."
  [rel]
  (sparql "SELECT ?subject ?object
           WHERE { ?subject " (prefix/kw->qname rel) " ?object . }"))

(def sense-label-query
  (sparql
    "SELECT ?sense ?property ?value
     WHERE {
       VALUES ?property { lexinfo:register lexinfo:dating lexinfo:frequency }
       ?sense ?property ?value .
     }"))

(def usage-note-query
  (sparql
    "SELECT ?sense ?note
     WHERE { ?sense lexinfo:usageNote ?note . }"))

(def gender-query
  (sparql
    "SELECT ?synset ?gender
     WHERE { ?synset dns:gender ?gender . }"))

(def ili-query
  (sparql
    "SELECT ?synset ?ili
     WHERE { ?synset wn:ili ?ili . }"))

(def ili-definition-query
  (sparql
    "SELECT ?ili ?definition
     WHERE { ?ili skos:definition ?definition . }"))

(def oewn-lemma-query
  "The English lemmas of the OEWN synsets; the ILI identifiers join them to
  the DanNet synsets."
  (sparql
    "SELECT ?ili ?lemma
     WHERE {
       ?synset wn:ili ?ili .
       ?sense ontolex:isLexicalizedSenseOf ?synset .
       ?word ontolex:sense ?sense ;
             ontolex:canonicalForm ?form .
       ?form ontolex:writtenRep ?lemma .
     }"))

(def cor-link-query
  "The owl:sameAs links from COR words to DanNet words, with the COR lemmas.
  COR states each link in both directions; selecting the COR-to-DanNet
  direction gives each pair once."
  (sparql
    "SELECT ?cor ?word ?lemma
     WHERE {
       ?cor owl:sameAs ?word ;
            ontolex:canonicalForm ?canonical .
       ?canonical ontolex:writtenRep ?lemma .
       FILTER(STRSTARTS(str(?word), str(dn:))) .
     }"))

(def cor-form-query
  (sparql
    "SELECT ?cor ?form ?writtenRep ?label
     WHERE {
       ?cor ontolex:otherForm ?form .
       ?form ontolex:writtenRep ?writtenRep ;
             rdfs:label ?label .
     }"))

(def sentiment-query
  (sparql
    "SELECT ?subject ?polarity ?value
     WHERE {
       ?subject dns:sentiment ?opinion .
       ?opinion marl:hasPolarity ?polarity .
       OPTIONAL { ?opinion marl:polarityValue ?value . }
     }"))
