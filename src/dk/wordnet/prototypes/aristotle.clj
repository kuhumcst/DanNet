(ns dk.wordnet.prototypes.aristotle
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [arachne.aristotle :as aristotle]
            [arachne.aristotle.registry :as reg]
            [arachne.aristotle.graph :as graph]
            [arachne.aristotle.query :as q]
            [dk.wordnet.io :as dio])
  (:import [org.apache.jena.graph NodeFactory]
           [org.apache.jena.datatypes.xsd XSDDatatype]
           [org.apache.jena.riot Lang LangBuilder RDFParserRegistry]))

(def RDFXML+rdfs
  "A modified Lang/RDFXML that also accepts .rdfs file extensions."
  (-> (LangBuilder/create (.getName Lang/RDFXML)
                          (.getContentType (.getContentType Lang/RDFXML)))
      (.addAltNames (into-array String (.getAltNames Lang/RDFXML)))
      (.addFileExtensions (->> (.getFileExtensions Lang/RDFXML)
                               (concat ["rdfs"])
                               (into-array String)))
      (.build)))

;; Since .rdfs file extensions are not picked up by Apache Jena for RDF/XML,
;; the RDFXML parser must be modified to also accept that file extension.
(when (RDFParserRegistry/isRegistered Lang/RDFXML)
  (let [factory (RDFParserRegistry/getFactory Lang/RDFXML)]
    (RDFParserRegistry/removeRegistration Lang/RDFXML)
    (RDFParserRegistry/registerLangTriples RDFXML+rdfs factory)))

;; Interpret literals of the format "string@lang" as a string encoded in lang.
;; Uses existing implementation as a template; only deviates when @ is found.
(extend-protocol graph/AsNode
  String
  (node [obj]
    (if-let [uri (second (re-find #"^<(.*)>$" obj))]
      (NodeFactory/createURI uri)
      (let [[s lang] (str/split obj #"@")]
        (if (not-empty lang)
          (NodeFactory/createLiteral ^String s ^String lang)
          (NodeFactory/createLiteralByValue obj XSDDatatype/XSDstring))))))

(defn import-rdf
  "Create a graph from a `source` map. Optionally append to existing graph `g`."
  [{:strs [owl rdf rdfs] :as source} & [g]]
  (let [files (->> (concat owl rdf rdfs)
                   (remove (partial re-find #"w3c"))        ; TODO: don't hardcode
                   (map io/file))
        g     (or g (aristotle/graph :simple))]
    (reduce aristotle/read g files)))

(defn load-graph
  "Create or append to a graph `g` given an RDF `source` folder."
  ([g source]
   (import-rdf (-> source io/file dio/source-folder) g))
  ([source]
   (load-graph nil source)))

(comment
  (do
    (reg/prefix 'dn "http://www.wordnet.dk/owl/instance/2009/03/instances/")
    (reg/prefix 'dns "http://www.wordnet.dk/owl/instance/2009/03/schema/")
    (reg/prefix 'wn "http://www.w3.org/2006/03/wn/wn20/instances/")
    (reg/prefix 'wns "http://www.w3.org/2006/03/wn/wn20/schema/"))

  (def dannet
    (load-graph (io/resource "dannet/rdf")))

  (def wordnet
    (load-graph (io/resource "wordnet/rdf")))

  (def unified
    (->> ["dannet/rdf" "wordnet/rdf"]
         (map io/resource)
         (reduce load-graph nil)))

  ;; Warning: Returns all triples in graph - DO NOT RUN, EVER!!
  #_(q/run dannet '[:bgp [?subject ?predicate ?object]])
  #_(q/run wordnet '[:bgp [?subject ?predicate ?object]])
  #_(q/run unified '[:bgp [?subject ?predicate ?object]])

  ;; Fetch all triples where synset 1337 is the subject in DanNet.
  (q/run dannet '[:bgp ["<http://www.wordnet.dk/owl/instance/2009/03/instances/synset-1337>" ?predicate ?object]])
  (q/run unified '[:bgp ["<http://www.wordnet.dk/owl/instance/2009/03/instances/synset-1337>" ?predicate ?object]])

  ;; Fetch all triples where {dumbfounded-...} is the subject in WordNet.
  (q/run wordnet '[:bgp ["<http://www.w3.org/2006/03/wn/wn20/instances/synset-dumbfounded-adjectivesatellite-1>" ?predicate ?object]])
  (q/run unified '[:bgp ["<http://www.w3.org/2006/03/wn/wn20/instances/synset-dumbfounded-adjectivesatellite-1>" ?predicate ?object]])

  ;; Using prefixes for more readable code and results  (run reg/prefix first)
  (q/run unified '[:bgp [:dn/synset-1337 ?predicate ?object]])
  (q/run unified '[:bgp [:wn/synset-dumbfounded-adjectivesatellite-1 ?predicate ?object]])

  ;; Princeton Wordnet uses en-US language-encoded string literals.
  ;; These language-encoded strings can be accessed using Jena methods directly
  ;; or by appending "@en-US" to the string. Searching for the string directly
  ;; will unfortunately not work for language-encoded string literals.
  (q/run wordnet [:bgp ['?subject '?predicate (NodeFactory/createLiteral "dumbfounded" "en-US")]])
  (q/run wordnet [:bgp '[?subject ?predicate "dumbfounded@en-US"]])
  (q/run wordnet [:bgp '[?subject ?predicate "dumbfounded"]]) ; this won't work

  ;; The filter operations are not actually Clojure functions, but Clojure-like
  ;; code mimicking Sparql. See: `arachne.aristotle.query.compiler/expr-class`.
  ;; In this way, the language-encoding can be sidestepped, although the query
  ;; runs much slower (several seconds).
  (q/run wordnet '[:filter (regex ?object "^dumbfounded$" "i")
                   [:bgp [?subject ?predicate ?object]]])
  #_.)
