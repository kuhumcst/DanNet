(ns dk.cst.dannet.prefix
  "Prefix registration for the various schemas used by DanNet."
  (:require #?(:clj [arachne.aristotle.registry :as reg])
            [clojure.string :as str]
            [ont-app.vocabulary.core :as voc]
            [reitit.impl :refer [url-encode]]))             ; CLJC url-encode

;; NOTE: you must also edit the DanNet schema files when changing this!
(def dannet-root
  "https://wordnet.dk/dannet/")

(def sentiment-root
  "https://wordnet.dk/sentiment/")

(def schema-root
  "https://wordnet.dk/schema/")

(def export-root
  "https://wordnet.dk/export/")

(def schemas
  {'rdf     {:uri "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
             :alt "schemas/external/rdf.ttl"}
   'rdfs    {:uri "http://www.w3.org/2000/01/rdf-schema#"
             :alt "schemas/external/rdfs.ttl"}
   'owl     {:uri "http://www.w3.org/2002/07/owl#"
             :alt "schemas/external/owl.ttl"}
   'wn      {:uri "https://globalwordnet.github.io/schemas/wn#" ; https official
             :alt "schemas/external/wn-lemon-1.2.ttl"}
   'svs     {:uri "http://www.w3.org/2003/06/sw-vocab-status/ns#"
             :alt "schemas/external/svs.xml"}
   'ontolex {:uri "http://www.w3.org/ns/lemon/ontolex#"
             :alt "schemas/external/ontolex.xml"}
   'lemon   {:uri "http://lemon-model.net/lemon#"
             :alt "schemas/external/lemon-model.ttl"}
   'semowl  {:uri "http://www.ontologydesignpatterns.org/cp/owl/semiotics.owl#"
             :alt "schemas/external/semiotics.owl"}
   'skos    {:uri "http://www.w3.org/2004/02/skos/core#"
             :alt "schemas/external/skos.rdf"}
   'lexinfo {:uri "http://www.lexinfo.net/ontology/3.0/lexinfo#"
             :alt "schemas/external/lexinfo-3.0.owl"}
   'marl    {:uri "http://www.gsi.upm.es/ontologies/marl/ns#"
             :alt "schemas/external/marl.n3"}
   'olia    {:uri "http://purl.org/olia/olia.owl#"
             :alt "schemas/external/olia.owl"}

   ;; Metadata-related namespaces.
   'dcat    {:uri "http://www.w3.org/ns/dcat#"
             :alt "schemas/external/dcat2.ttl"}
   'vann    {:uri "http://purl.org/vocab/vann/"
             :alt "schemas/external/vann.ttl"}
   'foaf    {:uri "http://xmlns.com/foaf/0.1/"
             :alt "schemas/external/foaf.rdf"}
   'dc      {:uri "http://purl.org/dc/terms/"
             :alt "schemas/external/dublin_core_terms.ttl"}
   'dc11    {:uri "http://purl.org/dc/elements/1.1/"
             :alt "schemas/external/dublin_core_elements.ttl"}
   'cc      {:uri "http://creativecommons.org/ns#"
             :alt "schemas/external/cc.rdf"}

   ;; Used by Open English WordNet
   'ili     {:uri "http://globalwordnet.org/ili/"
             :alt :no-schema}
   'lime    {:uri "http://www.w3.org/ns/lemon/lime#"
             :alt "schemas/external/lime.xml"}
   'schema  {:uri "http://schema.org/"
             :alt :no-schema}
   'synsem  {:uri "http://www.w3.org/ns/lemon/synsem#"
             :alt "schemas/external/synsem.xml"}
   'enl     {:uri "http://wordnet-rdf.princeton.edu/lemma/"
             :alt :no-schema}
   'en      {:uri "http://wordnet-rdf.princeton.edu/id/"
             :alt :no-schema}

   ;; The COR namespace
   'cor     {:uri      "https://ordregister.dk/id/"
             :export   #{'dn 'cor
                         'rdf 'rdfs 'owl
                         'ontolex 'skos 'lexinfo}
             :download {"rdf" {:default "cor.zip"}}}

   ;; Sentiment data
   'dds     {:uri      sentiment-root
             :export   #{'dn 'dns 'marl}
             :download {"rdf" {:default "dds.zip"}}}

   ;; The three internal DanNet namespaces.
   'dn      {:uri      (str dannet-root "data/")
             :export   #{'dn 'dnc 'dns
                         'rdf 'rdfs 'owl
                         'wn 'ontolex 'skos 'lexinfo
                         'dcat 'vann 'foaf 'dc}
             :download {"rdf" {:default   "dannet.zip"
                               "merged"   "dannet-dds-cor.zip"
                               "complete" "dannet-complete.zip"}
                        "csv" {:default "dannet-csv.zip"}}}

   'dnc     {:uri (str dannet-root "concepts/")
             :alt "schemas/internal/dannet-concepts-2022.ttl"}
   'dns     {:uri (str dannet-root "schema/")
             :alt "schemas/internal/dannet-schema-2022.ttl"}

   ;; Various en->da translations included as additional data.
   'tr      {:uri (str dannet-root "translations/")
             :alt "schemas/internal/dannet-translations-2022.ttl"}})

(def oewn-extension
  "Our extension of the OEWN containing labels for words, senses, synsets."
  {:uri      "http://wordnet.dk/oewn"
   :export   #{'rdfs 'en 'enl}
   :download {"rdf" {:default "oewn-extension.zip"}}})

(def internal-prefixes
  #{'dn 'dnc 'dns})

(def prefixes
  (set (keys schemas)))

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
  (str/replace-first (subs (str kw) 1) #"/" ":"))

(def kw->uri
  voc/uri-for)

(defn qname->kw
  [qname]
  (when qname
    (let [[prefix local-name] (str/split qname #":")]
      (keyword prefix local-name))))

(defn rdf-resource->uri
  "Remove < and > from `resource` to return its URI."
  [resource]
  (subs resource 1 (dec (count resource))))

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
  "Given a `resource` with an unknown namespace, attempt to guess the local name."
  [resource]
  (last (guess-parts (rdf-resource->uri resource))))

(defn guess-ns
  "Given a `resource` with an unknown namespace, attempt to guess the namespace."
  [resource]
  (str/join (butlast (guess-parts (rdf-resource->uri resource)))))

(defn export-file
  "Return filename registered for `prefix` and `type`; accepts `variant` too."
  [type prefix & [variant]]
  (doto (get-in schemas [prefix :download type (or variant :default)])
    (assert)))

(defn prefix->schema-path
  "Return resource path for schema registered at `prefix`."
  [prefix]
  (doto (get-in schemas [prefix :alt])
    (assert)))

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

;; NOTE: graph covers both oewn and oewnl prefixes
(def oewn-uri
  "http://wordnet-rdf.princeton.edu/")

(def oewn-extension-uri
  (:uri oewn-extension))

(def ili-uri
  (prefix->uri 'ili))

(def dds-uri
  (prefix->uri 'dds))

(def cor-uri
  (prefix->uri 'cor))

(def not-for-export
  #{oewn-uri
    oewn-extension-uri                                      ; exports separately
    ili-uri})

(defn- invert-map
  [m]
  (into {} (for [[group prefixes] m
                 prefix prefixes]
             [prefix group])))

(def prefix->class
  "Convert a `prefix` to a CSS class."
  (invert-map
    {"dannet"  #{'dn 'dnc 'dns}
     "w3c"     #{'dcat 'foaf 'owl 'rdf 'rdfs 'skos 'svs}
     "meta"    #{'cc 'dc 'dc11 'vann 'schema}
     "ontolex" #{'ontolex 'lexinfo 'lime 'marl 'olia}
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

(defn uri->rdf-resource
  "Surround `uri` with < and > to indicate that it is an RDF resource."
  [uri]
  (str "<" uri ">"))

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

(defn dataset-uri
  "Prepare a download URL for the dataset defined by `prefix` in specified
  `type` and of a possible `variant`."
  [type prefix & [variant]]
  (assert (get-in schemas [prefix :download type (or variant :default)]))
  (str export-root type "/" prefix (when variant
                                     (str "?variant=" variant))))

(defn schema-uri
  "Convert a Resource `uri` into a dataset download URI."
  [prefix]
  (assert (get-in schemas [prefix :alt]))
  (str schema-root prefix))

(def external-path
  (str (uri->path dannet-root) "external"))

(def search-path
  (str (uri->path dannet-root) "search"))

(def markdown-path
  (str (uri->path dannet-root) "page/:document"))

(defn resource-path
  [rdf-resource]
  (str external-path "?subject=" (url-encode rdf-resource)))

(comment
  (re-matches uri-parts "http://glen.dk/path/to/file")
  (download-uri "https://wordnet.dk/dannet/data")

  ;; Download links as RDF resources
  (dataset-uri "rdf" 'dn)
  (dataset-uri "rdf" 'dn "merged")
  (dataset-uri "rdf" 'dn "asdads")                          ; should throw
  (schema-uri 'dns)
  (schema-uri 'glen)
  #_.)