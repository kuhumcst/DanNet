(ns dk.cst.dannet.prototypes.igraph
  (:require [clojure.java.io :as io]
            [ont-app.igraph-jena.core :as igraph-jena]
            [ont-app.vocabulary.core :as voc]
            [ont-app.igraph.core :as igraph]
            [dk.cst.dannet.prototypes.aristotle :as dannet-aristotle])
  (:import [org.apache.jena.rdf.model ModelFactory]))

(comment
  (do
    (voc/put-ns-meta!
      'dn
      {:vann/preferredNamespacePrefix "dn"
       :vann/preferredNamespaceUri    "http://www.wordnet.dk/owl/instance/2009/03/instances/"})
    (voc/put-ns-meta!
      'dns
      {:vann/preferredNamespacePrefix "dns"
       :vann/preferredNamespaceUri    "http://www.wordnet.dk/owl/instance/2009/03/schema/"})
    (voc/put-ns-meta!
      'wn
      {:vann/preferredNamespacePrefix "wn"
       :vann/preferredNamespaceUri    "http://www.w3.org/2006/03/wn/wn20/instances/"})
    (voc/put-ns-meta!
      'wns
      {:vann/preferredNamespacePrefix "wns"
       :vann/preferredNamespaceUri    "http://www.w3.org/2006/03/wn/wn20/schema/"}))

  (def dannet
    (-> (io/resource "dannet/rdf")
        (dannet-aristotle/load-graph)                       ; uses aristotle
        (ModelFactory/createModelForGraph)
        (igraph-jena/make-jena-graph)))

  (def wordnet
    (-> (io/resource "wordnet/rdf")
        (dannet-aristotle/load-graph)                       ; uses aristotle
        (ModelFactory/createModelForGraph)
        (igraph-jena/make-jena-graph)))

  (def unified
    (-> (->> ["dannet/rdf" "wordnet/rdf"]
             (map io/resource)
             (reduce dannet-aristotle/load-graph nil))      ; uses aristotle
        (ModelFactory/createModelForGraph)
        (igraph-jena/make-jena-graph)))

  ;; TODO: currently produces some illegal keywords with ns _ - investigate
  ;; Retrieve 30 different resources.
  (take 30 (igraph/subjects dannet))
  (take 30 (igraph/subjects wordnet))
  (take 30 (igraph/subjects unified))

  ;; Return a Synset using both the full URI and the namespaced version.
  (dannet (keyword "http://www.wordnet.dk/owl/instance/2009/03/instances/word-11035159"))
  (dannet :dn/synset-999)

  ;; Find all hyponyms of a Synset in the graph ("birkes").
  ;; Note: contains two separate paths!
  (->> (igraph/traverse dannet (igraph/transitive-closure :wns/hyponymOf) {} [] [:dn/synset-999])
       (map dannet)
       (map :rdfs/label)
       (map first))

  ;; Looking up a Synset in the Princeton Wordnet.
  (wordnet :wn/synset-continue-verb-1)

  ;; Traversal in the unified graph ("pointed-leaf maple")
  (->> (igraph/traverse unified (igraph/transitive-closure :wns/hyponymOf) {} [] [:wn/synset-pointed-leaf_maple-noun-1])
       (map unified)
       (map :rdfs/label)
       (map first))
  #_.)
