(ns dk.wordnet.db
  (:require [clojure.java.io :as io]
            [arachne.aristotle :as aristotle]
            [arachne.aristotle.registry :as reg]
            [arachne.aristotle.query :as q]
            [ont-app.igraph-jena.core :as igraph-jena]
            [ont-app.vocabulary.core :as voc]
            [ont-app.igraph.core :as igraph]
            [dk.wordnet.csv :as dn-csv])
  (:import [org.apache.jena.riot RDFDataMgr RDFFormat]
           [org.apache.jena.graph Graph]
           [org.apache.jena.rdf.model ModelFactory]))

(defn reg-prefix
  "Register `ns-prefix` for `uri` in both Aristotle and igraph."
  [ns-prefix uri]
  (reg/prefix ns-prefix uri)
  (let [prefix-str (name ns-prefix)]
    (when-not (get (voc/prefix-to-ns) prefix-str)
      (voc/put-ns-meta! ns-prefix {:vann/preferredNamespacePrefix prefix-str
                                   :vann/preferredNamespaceUri    uri}))))

(reg-prefix 'wn "https://globalwordnet.github.io/schemas/wn#")
(reg-prefix 'ontolex "http://www.w3.org/ns/lemon/ontolex#")
(reg-prefix 'skos "http://www.w3.org/2004/02/skos#")
(reg-prefix 'lexinfo "http://www.lexinfo.net/ontology/2.0/lexinfo#")
;; TODO: use new DanNet namespaces instead
(reg-prefix 'dn "http://www.wordnet.dk/owl/instance/2009/03/instances/")
(reg-prefix 'dns "http://www.wordnet.dk/owl/instance/2009/03/schema/")

(defn ->dannet
  "Create an in-memory Jena database based on the DanNet 2.2 `csv-imports`.
  The returned database uses the new GWA relations rather than the old ones."
  [csv-imports]
  (let [read-triples #(->> (dn-csv/read-triples %1 %2)
                           (remove nil?)
                           (remove dn-csv/unmapped?))]
    (reduce
      (fn [g [row->triples file]]
        (aristotle/add g (read-triples row->triples file)))
      (aristotle/graph :simple)
      (vals csv-imports))))

;; TODO: exported resources need to be namespaced
;; TODO: RDFXML export causes OutOfMemoryError - investigate
;; https://jena.apache.org/documentation/io/rdf-output.html
(defn export-db!
  "Export the `db` to the file with the given `filename`."
  [filename db & {:keys [fmt]
                  :or   {fmt RDFFormat/TURTLE_PRETTY}}]
  (RDFDataMgr/write (io/writer filename) ^Graph db ^RDFFormat fmt))

(defn synonyms
  "Return synonyms in `db` of the word with the given `lemma`."
  [db lemma]
  (->> (q/run db
              '[?synonym]
              '[:bgp
                [?form :ontolex/writtenRep ?lemma]
                [?word :ontolex/canonicalForm ?form]
                [?word :ontolex/evokes ?synset]
                [?word* :ontolex/evokes ?synset]
                [?word* :ontolex/canonicalForm ?form*]
                [?form* :ontolex/writtenRep ?synonym]]
              {'?lemma lemma})
       (apply concat)
       (remove #{lemma})))

(comment
  (def dannet
    (->dannet dn-csv/csv-imports))

  (get (voc/prefix-to-ns) "skos")

  (def ig
    (-> (ModelFactory/createModelForGraph dannet)
        (igraph-jena/make-jena-graph)))

  ;; Export the contents of the db
  (export-db! "resources/dannet.ttl" dannet)

  ;; Querying DanNet for various synonyms
  (synonyms dannet "vand")
  (synonyms dannet "sild")
  (synonyms dannet "hoved")
  (synonyms dannet "bil")

  (take 30 (igraph/subjects ig))

  ;; Look up "citron" using igraph
  (-> (ig :dn/word-11007846)
      (igraph/flatten-description))

  ;; Find all hypernyms of a Synset in the graph ("birkes").
  ;; Note: contains two separate paths!
  (let [hypernym (igraph/transitive-closure :wn/hypernym)]
    (->> (igraph/traverse ig hypernym {} [] [:dn/synset-999])
         (map ig)
         (map :rdfs/label)
         (map first)))
  #_.)
