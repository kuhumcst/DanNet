(ns dk.wordnet.db.queries
  "Various pre-compiled Aristotle queries."
  (:require [arachne.aristotle.query :as q]
            [arachne.aristotle.registry :as reg]
            [ont-app.vocabulary.core :as voc]))

(defn register-prefix
  "Register `ns-prefix` for `uri` in both Aristotle and igraph."
  [ns-prefix uri]
  (reg/prefix ns-prefix uri)
  (let [prefix-str (name ns-prefix)]
    (when-not (get (voc/prefix-to-ns) prefix-str)
      (voc/put-ns-meta! ns-prefix {:vann/preferredNamespacePrefix prefix-str
                                   :vann/preferredNamespaceUri    uri}))))

(register-prefix 'wn "https://globalwordnet.github.io/schemas/wn#")
(register-prefix 'ontolex "http://www.w3.org/ns/lemon/ontolex#")
(register-prefix 'skos "http://www.w3.org/2004/02/skos#")
(register-prefix 'lexinfo "http://www.lexinfo.net/ontology/2.0/lexinfo#")
;; TODO: use new DanNet namespaces instead
(register-prefix 'dn "http://www.wordnet.dk/owl/instance/2009/03/instances/")
(register-prefix 'dns "http://www.wordnet.dk/owl/instance/2009/03/schema/")

(def synonyms
  (q/build
    '[:bgp
      [?form :ontolex/writtenRep ?lemma]
      [?word :ontolex/canonicalForm ?form]
      [?word :ontolex/evokes ?synset]
      [?word* :ontolex/evokes ?synset]
      [?word* :ontolex/canonicalForm ?form*]
      [?form* :ontolex/writtenRep ?synonym]]))
