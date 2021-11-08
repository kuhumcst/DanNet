(ns dk.wordnet.prefix
  "Prefix registration for the various schemas used by DanNet."
  (:require [arachne.aristotle.registry :as reg]
            [ont-app.vocabulary.core :as voc]
            [clojure.string :as str]))

(def dannet-root
  "http://www.wordnet.dk/dannet/2022/")

(def schemas
  {'rdf     {:uri "http://www.w3.org/1999/02/22-rdf-syntax-ns#"}
   'rdfs    {:uri "http://www.w3.org/2000/01/rdf-schema#"}
   'owl     {:uri "http://www.w3.org/2002/07/owl#"}
   'wn      {:uri "https://globalwordnet.github.io/schemas/wn#"
             :alt "schemas/wn-lemon-1.2.ttl"}
   'ontolex {:uri "http://www.w3.org/ns/lemon/ontolex#"}
   'lemon   {:uri "http://lemon-model.net/lemon#"
             :alt "schemas/lemon-model.ttl"}
   'semowl  {:uri "http://www.ontologydesignpatterns.org/cp/owl/semiotics.owl#"
             :alt "schemas/semiotics.owl"}
   'skos    {:uri "http://www.w3.org/2004/02/skos/core#"
             :alt "http://www.w3.org/TR/skos-reference/skos.rdf"}
   'lexinfo {:uri "http://www.lexinfo.net/ontology/3.0/lexinfo#"
             :alt "schemas/lexinfo-3.0.owl"}
   'dn      {:uri          (str dannet-root "instances/")
             :instance-ns? true}
   'dnc     {:uri (str dannet-root "concepts/")
             :alt "schemas/dannet-concepts-2022.ttl"}
   'dns     {:uri (str dannet-root "schema/")
             :alt "schemas/dannet-schema-2022.ttl"}

   ;; Various en->da translations included as additional data.
   'en->da  {:uri (str dannet-root "translations/")
             :alt "schemas/dannet-translations-2022.ttl"}})

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

(defn kw->qname
  [kw]
  (str/replace (subs (str kw) 1) #"/" ":"))
