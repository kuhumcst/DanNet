(ns dk.cst.dannet.prefix
  "Prefix registration for the various schemas used by DanNet."
  (:require #?(:clj [arachne.aristotle.registry :as reg])
            [clojure.string :as str]
            [ont-app.vocabulary.core :as voc]
            [reitit.impl :refer [url-encode]]))             ; CLJC url-encode

;; NOTE: you must also edit the DanNet schema files when changing this!
(def dannet-root
  "http://www.wordnet.dk/dannet/")

(def sentiment-root
  "http://www.wordnet.dk/sentiment/")

(def download-root
  "http://www.wordnet.dk/download/dannet/")

(def schemas
  {'rdf       {:uri "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
               :alt "schemas/external/rdf.ttl"}
   'rdfs      {:uri "http://www.w3.org/2000/01/rdf-schema#"
               :alt "schemas/external/rdfs.ttl"}
   'owl       {:uri "http://www.w3.org/2002/07/owl#"
               :alt "schemas/external/owl.ttl"}
   'wn        {:uri "https://globalwordnet.github.io/schemas/wn#" ; https official
               :alt "schemas/external/wn-lemon-1.2.ttl"}
   'svs       {:uri "http://www.w3.org/2003/06/sw-vocab-status/ns#"
               :alt "schemas/external/svs.xml"}
   'ontolex   {:uri "http://www.w3.org/ns/lemon/ontolex#"
               :alt "schemas/external/ontolex.xml"}
   'lemon     {:uri "http://lemon-model.net/lemon#"
               :alt "schemas/external/lemon-model.ttl"}
   'semowl    {:uri "http://www.ontologydesignpatterns.org/cp/owl/semiotics.owl#"
               :alt "schemas/external/semiotics.owl"}
   'skos      {:uri "http://www.w3.org/2004/02/skos/core#"
               :alt "schemas/external/skos.rdf"}
   'lexinfo   {:uri "http://www.lexinfo.net/ontology/3.0/lexinfo#"
               :alt "schemas/external/lexinfo-3.0.owl"}
   'marl      {:uri "http://www.gsi.upm.es/ontologies/marl/ns#"
               :alt "schemas/external/marl.n3"}
   'olia      {:uri "http://purl.org/olia/olia.owl#"
               :alt "schemas/external/olia.owl"}

   ;; Metadata-related namespaces.
   'dcat      {:uri "http://www.w3.org/ns/dcat#"
               :alt "schemas/external/dcat2.ttl"}
   'vann      {:uri "http://purl.org/vocab/vann/"
               :alt "schemas/external/vann.ttl"}
   'foaf      {:uri "http://xmlns.com/foaf/0.1/"
               :alt "schemas/external/foaf.rdf"}
   'dc        {:uri "http://purl.org/dc/terms/"
               :alt "schemas/external/dublin_core_terms.ttl"}
   'dc11      {:uri "http://purl.org/dc/elements/1.1/"
               :alt "schemas/external/dublin_core_elements.ttl"}
   'cc        {:uri "http://creativecommons.org/ns#"
               :alt "schemas/external/cc.rdf"}

   ;; Used by Open English WordNet
   'ili       {:uri "http://ili.globalwordnet.org/ili/"
               :alt :no-schema}
   'lime      {:uri "http://www.w3.org/ns/lemon/lime#"
               :alt "schemas/external/lime.xml"}
   'schema    {:uri "http://schema.org/"
               :alt :no-schema}
   'synsem    {:uri "http://www.w3.org/ns/lemon/synsem#"
               :alt "schemas/external/synsem.xml"}
   'oewnlemma {:uri "https://en-word.net/lemma/"
               :alt :no-schema}
   'oewnid    {:uri "https://en-word.net/id/"
               :alt :no-schema}

   ;; The COR namespace
   'cor       {:uri    "http://ordregister.dk/id/COR."
               :export #{'dn 'cor
                         'rdf 'rdfs 'owl
                         'ontolex 'skos 'lexinfo}}

   ;; Sentiment data (unofficial) TODO
   'senti     {:uri    sentiment-root
               :export #{'dn 'dns 'marl}}

   ;; The three internal DanNet namespaces.
   'dn        {:uri    (str dannet-root "data/")
               :export #{'dn 'dnc 'dns
                         'rdf 'rdfs 'owl
                         'wn 'ontolex 'skos 'lexinfo
                         'dcat 'vann 'foaf 'dc}}

   'dnc       {:uri (str dannet-root "concepts/")
               :alt "schemas/internal/dannet-concepts-2022.ttl"}
   'dns       {:uri (str dannet-root "schema/")
               :alt "schemas/internal/dannet-schema-2022.ttl"}

   ;; Various en->da translations included as additional data.
   'tr        {:uri (str dannet-root "translations/")
               :alt "schemas/internal/dannet-translations-2022.ttl"}})



(def internal-prefixes
  #{'dn 'dnc 'dns})

(def prefixes
  (set (keys schemas)))

(def qname-re
  (re-pattern (str "(" (str/join "|" prefixes) "):(.+)")))

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

(def kw->uri
  voc/uri-for)

(defn qname->kw
  [kw]
  (let [[prefix local-name] (str/split kw #":")]
    (keyword prefix local-name)))

(defn qname->uri
  [qname]
  (subs qname 1 (dec (count qname))))

(defn- partition-str
  "Partition a string `s` by the character `c`. Works similar to str/split,
  except the splitting patterns are kept in the returned partitions."
  [c s]
  (map (partial apply str) (partition-by (partial = c) s)))

(defn- guess-parts
  [uri]
  (cond
    (re-find #"#" uri)
    (partition-str \# uri)

    (re-find #"/" uri)
    (partition-str \/ uri)))

(defn guess-local-name
  "Given a `qname` with an unknown namespace, attempt to guess the local name."
  [qname]
  (last (guess-parts (qname->uri qname))))

(defn guess-ns
  "Given a `qname` with an unknown namespace, attempt to guess the namespace."
  [qname]
  (str/join (butlast (guess-parts (qname->uri qname)))))

(defn prefix->uri
  "Return the URI registered for a `prefix`."
  [prefix]
  (-> schemas prefix :uri))

(defn uri->prefix
  "Return the URI registered for a `prefix`."
  [uri]
  (loop [[[k m] & schemas'] schemas]
    (if (= uri (:uri m))
      k
      (when (not-empty schemas')
        (recur schemas')))))

(def dn-uri
  (prefix->uri 'dn))

(def senti-uri
  (prefix->uri 'senti))

(def cor-uri
  (prefix->uri 'cor))

(defn- invert-map
  [m]
  (into {} (for [[group prefixes] m
                 prefix prefixes]
             [prefix group])))

(def prefix->class
  "Convert a `prefix` to a CSS class."
  (invert-map
    {"dannet"  #{'dn 'dnc 'dns}
     "w3c"     #{'rdf 'rdfs 'owl 'skos 'dcat}
     "meta"    #{'dc 'dc11 'vann 'cc}
     "ontolex" #{'ontolex 'lexinfo 'marl 'olia}
     "wordnet" #{'wn}}))

(defn with-prefix
  "Return predicate accepting keywords with `prefix` (`except` set of keywords).
  The returned predicate function is used to filter keywords based on prefixes."
  [prefix & {:keys [except]}]
  (fn [[k v]]
    (when (keyword? k)
      (and (not (except k))
           (= (namespace k) (name prefix))))))

;; TODO: make it work for # too
;; https://github.com/pedestal/pedestal/issues/477#issuecomment-256168954
(defn remove-trailing-slash
  [uri]
  (if (= \/ (last uri))
    (subs uri 0 (dec (count uri)))
    uri))

(def uri-parts
  "Splits a URI into [uri before-path protocol domain path]."
  #"((https?)://([^/]+))(.*)")

(defn uri->path
  "Remove every part of the `uri` aside from the path."
  [uri]
  (when-let [[_ _ _ _ path] (re-matches uri-parts uri)]
    path))

(defn download-uri
  "Convert a Resource `uri` into a dataset download URI."
  [uri]
  (when-let [[_ before-path _ _ path] (re-matches uri-parts uri)]
    (str before-path "/download" (remove-trailing-slash path))))

(defn uri->rdf-resource
  "Surround `uri` with < and > to indicate that it is an RDF resource."
  [uri]
  (str "<" uri ">"))

(defn rdf-resource->uri
  "Surround `uri` with < and > to indicate that it is an RDF resource."
  [uri]
  (subs uri 1 (dec (count uri))))

(defn rdf-resource?
  "Is `s` an RDF resource string?"
  [s]
  (and (string? s)
       (str/starts-with? s "<")
       (str/ends-with? s ">")))

(defn resolve-href
  "Given a namespaced `kw`, resolve the href for the resource."
  [kw]
  (let [prefix (symbol (namespace kw))]
    (if (get internal-prefixes prefix)
      (str (-> prefix schemas :uri uri->path) (name kw))
      (-> (str dannet-root "external/"
               (url-encode (namespace kw)) "/" (url-encode (name kw)))
          (uri->path)))))

(defn prefix->rdf-resource
  [prefix]
  (-> (prefix->uri prefix)
      (remove-trailing-slash)
      (uri->rdf-resource)))

(defn prefix->rdf-download
  [prefix]
  (-> (prefix->uri prefix)
      (download-uri)
      (uri->rdf-resource)))

(def external-path
  (str (uri->path dannet-root) "external"))

(def search-path
  (str (uri->path dannet-root) "search"))

(defn resource-path
  [rdf-resource]
  (str external-path "?subject=" (url-encode rdf-resource)))

(comment
  (re-matches uri-parts "http://glen.dk/path/to/file")
  (download-uri "http://wordnet.dk/dannet/data")

  ;; Download links as RDF resources
  (prefix->rdf-download 'dn)
  (prefix->rdf-download 'dns)
  (prefix->rdf-download 'dnc)
  #_.)