(ns dk.wordnet.db
  (:require [arachne.aristotle :as aristotle]
            [arachne.aristotle.registry :as reg]
            [arachne.aristotle.query :as q]
            [dk.wordnet.csv :as dn-csv]))

(reg/prefix 'wn "https://globalwordnet.github.io/schemas/wn#")
(reg/prefix 'ontolex "http://www.w3.org/ns/lemon/ontolex#")
(reg/prefix 'skos "http://www.w3.org/2004/02/skos#")
(reg/prefix 'lexinfo "http://www.lexinfo.net/ontology/2.0/lexinfo#")

;; TODO: use new DanNet namespaces instead
(reg/prefix 'dn "http://www.wordnet.dk/owl/instance/2009/03/instances/")
(reg/prefix 'dns "http://www.wordnet.dk/owl/instance/2009/03/schema/")

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

(defn synonyms
  "Return synonyms of the word with the given `lemma`."
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

  ;; Querying DanNet for various synonyms
  (synonyms dannet "vand")
  (synonyms dannet "sild")
  (synonyms dannet "hoved")
  (synonyms dannet "bil")
  #_.)
