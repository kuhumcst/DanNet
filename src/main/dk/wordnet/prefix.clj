(ns dk.wordnet.prefix
  "Prefix registration for the various schemas used by DanNet."
  (:require [clojure.java.io :as io]
            [arachne.aristotle.registry :as reg]
            [ont-app.vocabulary.core :as voc]))

(def schemas
  {'wn      {:uri "https://globalwordnet.github.io/schemas/wn#"
             :alt (str (io/resource "schemas/wn-lemon-1.2.ttl"))}
   'ontolex {:uri "http://www.w3.org/ns/lemon/ontolex#"}
   'lemon   {:uri "http://lemon-model.net/lemon#"}
   'semowl  {:uri "http://www.ontologydesignpatterns.org/cp/owl/semiotics.owl#"
             :alt (str (io/resource "schemas/semiotics.owl"))}
   'skos    {:uri "http://www.w3.org/2004/02/skos/core#"
             :alt "http://www.w3.org/TR/skos-reference/skos.rdf"}
   'lexinfo {:uri "http://www.lexinfo.net/ontology/3.0/lexinfo#"}
   'dn      {:uri          "http://www.wordnet.dk/dannet/2022/instances/"
             :instance-ns? true}
   'dns     {:uri "http://www.wordnet.dk/dannet/2022/schema/"
             :alt (str (io/resource "schemas/dannet-schema-2022.ttl"))}})

(defn register
  "Register `ns-prefix` for `uri` in both Aristotle and igraph."
  [ns-prefix uri]
  (reg/prefix ns-prefix uri)
  (let [prefix-str (name ns-prefix)]
    (when-not (get (voc/prefix-to-ns) prefix-str)
      (voc/put-ns-meta! ns-prefix {:vann/preferredNamespacePrefix prefix-str
                                   :vann/preferredNamespaceUri    uri}))))

(doseq [[ns-prefix {:keys [uri]}] schemas]
  (register ns-prefix uri))
