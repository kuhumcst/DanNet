(ns dk.cst.dannet.prefix
  "Prefix registration for the various schemas used by DanNet."
  (:require #?(:clj [arachne.aristotle.registry :as reg])
            [ont-app.vocabulary.core :as voc]
            [clojure.string :as str]))

(def dannet-root
  "http://www.wordnet.dk/dannet/")

(def schemas
  {'rdf     {:uri "http://www.w3.org/1999/02/22-rdf-syntax-ns#"}
   'rdfs    {:uri "http://www.w3.org/2000/01/rdf-schema#"}
   'owl     {:uri "http://www.w3.org/2002/07/owl#"}
   'wn      {:uri "https://globalwordnet.github.io/schemas/wn#"
             :alt "schemas/wn-lemon-1.2.ttl"}
   'svs     {:uri "http://www.w3.org/2003/06/sw-vocab-status/ns#"}
   'ontolex {:uri "http://www.w3.org/ns/lemon/ontolex#"}
   'lemon   {:uri "http://lemon-model.net/lemon#"
             :alt "schemas/lemon-model.ttl"}
   'semowl  {:uri "http://www.ontologydesignpatterns.org/cp/owl/semiotics.owl#"
             :alt "schemas/semiotics.owl"}
   'skos    {:uri "http://www.w3.org/2004/02/skos/core#"
             :alt "http://www.w3.org/TR/skos-reference/skos.rdf"}
   'lexinfo {:uri "http://www.lexinfo.net/ontology/3.0/lexinfo#"
             :alt "schemas/lexinfo-3.0.owl"}

   ;; Metadata-related namespaces.
   'dcat    {:uri "http://www.w3.org/ns/dcat#"
             :alt "schemas/dcat2.ttl"}
   'cc      {:uri "https://creativecommons.org/ns#"
             :alt "schemas/cc.rdf"}
   'vann    {:uri "http://purl.org/vocab/vann/"
             :alt "schemas/vann.ttl"}
   'foaf    {:uri "http://xmlns.com/foaf/0.1/"
             :alt "schemas/foaf.rdf"}
   'dct     {:uri "http://purl.org/dc/terms/"
             :alt "schemas/dublin_core_terms.ttl"}

   ;; The three internal DanNet namespaces.
   'dn      {:uri     (str dannet-root "data/")
             :schema? false}
   'dnc     {:uri (str dannet-root "concepts/")
             :alt "schemas/dannet-concepts-2022.ttl"}
   'dns     {:uri (str dannet-root "schema/")
             :alt "schemas/dannet-schema-2022.ttl"}

   ;; Various en->da translations included as additional data.
   'en->da  {:uri (str dannet-root "translations/")
             :alt "schemas/dannet-translations-2022.ttl"}})

(def internal-prefixes
  #{'dn 'dnc 'dns})

(defn register
  "Register `ns-prefix` for `uri` in both Aristotle and igraph."
  [ns-prefix uri]
  #?(:clj (reg/prefix ns-prefix uri))
  (let [prefix-str (name ns-prefix)]
    (when-not (get (voc/prefix-to-ns) prefix-str)
      (voc/put-ns-meta! ns-prefix {:vann/preferredNamespacePrefix prefix-str
                                   :vann/preferredNamespaceUri    uri}))))

;; TODO: is registration necessary in CLJS?
(doseq [[ns-prefix {:keys [uri]}] schemas]
  (register ns-prefix uri))

(defn kw->qname
  [kw]
  (str/replace (subs (str kw) 1) #"/" ":"))

(defn prefix->uri
  "Return the URI registered for a `prefix`."
  [prefix]
  (-> schemas prefix :uri))

(defn rdf-resource
  "Surround `uri` with < and > to indicate that it is an RDF resource."
  [uri]
  (str "<" uri ">"))

(defn prefix-><uri>
  "Return the RDF resource URI registered for a `prefix`."
  [prefix]
  (rdf-resource (prefix->uri prefix)))

(defn uri->path
  "Remove every part of the `uri` aside from the path."
  [uri]
  (second (str/split uri #"http://[^/]+")))

(defn resolve-href
  "Given a namespaced `kw`, resolve the href for the resource."
  [kw]
  (let [prefix (symbol (namespace kw))]
    (if (get internal-prefixes prefix)
      (str (-> prefix schemas :uri uri->path) (name kw))
      (-> (str dannet-root "external/" (namespace kw) "/" (name kw))
          (uri->path)))))

;; https://github.com/pedestal/pedestal/issues/477#issuecomment-256168954
(defn remove-trailing-slash
  [uri]
  (if (= \/ (last uri))
    (subs uri 0 (dec (count uri)))
    uri))

(def <dn>
  "The RDF resource URI for the DanNet dataset."
  (-> (prefix->uri 'dn)
      (remove-trailing-slash)
      (rdf-resource)))
