(ns rdf
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [arachne.aristotle :as aristotle]
            [arachne.aristotle.query :as q])
  (:import [java.io File]))

(defn source-folder
  "Load a `folder` as a source-map to be consumed by rdf/graph."
  [^File folder]
  (let [filenames (.list folder)
        filepaths (map (partial str (.getAbsolutePath folder) "/") filenames)
        extension (comp second #(str/split % #"\."))]
    (group-by extension filepaths)))

;; TODO: .rdfs files have no content-type, currently omitted - convert to .rdf?
(defn graph
  "Create a graph from a `source-map`. Optionally append to existing graph `g`."
  [{:strs [owl rdf rdfs] :as source-map} & [g]]
  (let [files (->> (concat owl rdf)
                   (remove (partial re-find #"w3c"))
                   (map io/file))
        g     (or g (aristotle/graph :simple))]
    (reduce aristotle/read g files)))

(defn load-graph
  "Create or append to a graph `g` given an RDF `source` folder."
  ([g source]
   (graph (-> source io/file source-folder) g))
  ([source]
   (load-graph nil source)))

(comment
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
  #_.)
