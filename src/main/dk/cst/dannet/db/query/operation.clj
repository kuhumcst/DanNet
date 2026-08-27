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

(defn resource-labels-query
  "Build a SPARQL Op that fetches labels for a collection of `resources`,
  given as keywords or bracketed RDF resource strings."
  [resources]
  (let [label-rels (str/join " " (map prefix/kw->qname shared/label-keys-short))
        values     (->> resources
                        (map #(if (keyword? %) (prefix/kw->rdf-resource %) %))
                        (str/join " "))]
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
  ;; NB: Aristotle's :union is strictly binary and silently DROPS any clause
  ;; beyond the second, so the nesting below is load-bearing.
  (q/build
    [:union
     [:union
      [:bgp
       ['?ontotype :rdfs/label '?label]
       ['?ontotype :rdf/type :dns/DanNetConcept]]
      [:bgp
       ['?ontotype :rdfs/label '?label]
       ['?ontotype :rdf/type :dns/EuroWordNetConcept]]]
     [:bgp
      ['?ontotype :rdfs/label '?label]
      ['?ontotype :rdf/type :dns/OntologicalType]]]))

(def written-representations
  (q/build
    [:bgp
     '[?form :ontolex/writtenRep ?writtenRep]]))

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
  "Columns to export for synsets.csv.

  The members are matched on the rdf:_N properties directly: ARQ's rdfs:member
  expansion only recognises containers by an asserted rdf:Bag typing, which
  the dnt: types leave to schema inference."
  (sparql
    "SELECT ?synset ?definition ?onto
     WHERE {
       ?synset rdf:type ontolex:LexicalConcept .
       ?synset skos:definition ?definition .
       ?synset dns:ontologicalType ?ontotype .
       ?ontotype ?member ?onto .
       FILTER(STRSTARTS(str(?member), CONCAT(str(rdf:), \"_\"))) .
     }"))

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

(def synset-sense-label-query
  "The rdfs:label of every member sense of a synset; the raw material for the
  native sense order behind the synset labels."
  (sparql
    "SELECT ?synset ?sense ?label
     WHERE {
       ?synset ontolex:lexicalizedSense ?sense .
       ?sense rdfs:label ?label .
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

(def source-query
  "The DDO source URL of a sense or a word."
  (sparql
    "SELECT ?s ?source
     WHERE { ?s dns:source ?source . }"))

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
    "SELECT ?synset ?bag ?member ?class
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
    "SELECT ?cor ?form ?writtenRep ?label ?comment
     WHERE {
       ?cor ontolex:otherForm ?form .
       ?form ontolex:writtenRep ?writtenRep ;
             rdfs:label ?label .
       OPTIONAL { ?form rdfs:comment ?comment }
     }"))

(def sentiment-query
  (sparql
    "SELECT ?subject ?polarity ?value
     WHERE {
       ?subject dns:sentiment ?opinion .
       ?opinion marl:hasPolarity ?polarity .
       OPTIONAL { ?opinion marl:polarityValue ?value . }
     }"))

;; The queries below run on the cor-sem: graph and carry the COR.SEM payload
;; of the DMLex export and the short-label ranking (regenerate-short-labels!);
;; dns:eqSense joins the two sense inventories.

(def eq-sense-query
  "The COR.SEM senses and the DanNet senses they exactly match."
  (sparql
    "SELECT ?corsem ?sense
     WHERE { ?corsem dns:eqSense ?sense . }"))

(def eq-sense-match-query
  "The COR.SEM senses and the DanNet senses they match, exactly or nearly;
  the channel through which COR.SEM centrality reaches the DanNet senses."
  (sparql
    "SELECT ?corsem ?sense
     WHERE { ?corsem dns:eqSense|dns:eqNearSense ?sense . }"))

(def corsem-frame-query
  (sparql
    "SELECT ?corsem ?frame
     WHERE { ?corsem dns:frame ?frame . }"))

(def linked-synset-query
  (sparql
    "SELECT ?corsem ?synset
     WHERE { ?corsem dns:linkedSynset ?synset . }"))

(def polysemy-pattern-query
  (sparql
    "SELECT ?corsem ?pattern
     WHERE { ?corsem dns:polysemyPattern ?pattern . }"))

(def pattern-label-query
  (sparql
    "SELECT ?pattern ?label
     WHERE {
       ?pattern rdf:type dns:PolysemyPattern ;
                rdfs:label ?label .
     }"))

(def centrality-query
  (sparql
    "SELECT ?corsem ?centrality
     WHERE { ?corsem dns:centrality ?centrality . }"))

(def simple-ontotype-query
  (sparql
    "SELECT ?corsem ?ontotype
     WHERE { ?corsem dns:simpleOntologicalType ?ontotype . }"))

(def ontotype-members-query
  "The member concepts of the named ontological types, matched on the rdf:_N
  properties like ontological-type-query."
  (sparql
    "SELECT ?ontotype ?member ?class
     WHERE {
       ?ontotype rdf:type dns:OntologicalType .
       ?ontotype ?member ?class .
       FILTER(STRSTARTS(str(?member), CONCAT(str(rdf:), \"_\"))) .
     }"))
