(ns dk.cst.dannet.db.catalog
  "Discovery of the schemas and datasets that a graph is composed of;
  used to populate the catalog page of the DanNet web app."
  (:require [clojure.string :as str]
            [flatland.ordered.map :as ordered]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.db.query :as q]
            [dk.cst.dannet.db.query.operation :as op]))

(defn- catalog-source?
  "True when the query result source `?source` belongs in the catalog, i.e.
  when it is an RDF resource rather than e.g. a W3C specification URL."
  [?source]
  (and ((some-fn keyword? prefix/rdf-resource?) ?source)
       (not (and (string? ?source)
                 (str/includes? ?source "www.w3.org/TR/")))))

(defn- source-resources
  "The set of RDF resources referenced as sources within the graph `g`."
  [g]
  (into #{}
        (comp (map '?source)
              (filter catalog-source?)
              (map #(cond-> % (keyword? %) prefix/kw->rdf-resource)))
        (q/run g op/catalog-resources)))

(defn- canonical-resources
  "The members of `sources` that are not mere separator variants of another
  member, e.g. <https://wordnet.dk/dannet/> where the separator-free
  <https://wordnet.dk/dannet> also occurs."
  [sources]
  ;; NB: only sources ALREADY in normalized form count as canonical.
  ;; Deriving this set by normalizing every source would unconditionally
  ;; drop sources with trailing separators, e.g. the OEWN dataset
  ;; resource <https://en-word.net/> (GitHub issue #178).
  (let [canonical (set (filter #(= % (prefix/normalize-rdf-resource %))
                               sources))]
    (remove (fn [src]
              (let [normalized (prefix/normalize-rdf-resource src)]
                (and (not= src normalized)
                     (contains? canonical normalized))))
            sources)))

(defn- resource-label
  "The most specific title available in the catalog resource `entity`."
  [entity]
  (or (:dc11/title entity)
      (:dc/title entity)
      (:rdfs/label entity)))

(defn- resource-description
  "The descriptions of the catalog resource `entity`, merged into one value."
  [entity]
  (reduce q/set-merge nil (keep entity [:rdfs/comment
                                        :dc/description
                                        :dc11/description])))

(defn- resource-prefix
  "The prefix of the catalog resource `rdf-resource`, as stated by its `entity`
  or else derived from its URI. Returns nil when no prefix is known."
  [rdf-resource entity]
  (let [uri (prefix/rdf-resource->uri rdf-resource)]
    (or (:vann/preferredNamespacePrefix entity)
        ;; Datasets whose resource URI doesn't share a namespace with their
        ;; resources, e.g. COR.
        (prefix/rdf-resource->prefix rdf-resource)
        (prefix/uri->prefix uri)
        (prefix/uri->prefix (str uri "#"))
        (prefix/uri->prefix (str uri "/")))))

(defn- ->catalog-entry
  "Build the [`rdf-resource` metadata] catalog entry from the graph `g`."
  [g rdf-resource]
  (let [entity (q/entity g rdf-resource)]
    [rdf-resource {:label       (resource-label entity)
                   :description (resource-description entity)
                   :prefix      (resource-prefix rdf-resource entity)}]))

(defn find-resources
  "Find known schemas and datasets referenced in the graph `g`.

  Returns an ordered map of
  {rdf-resource -> {:label ... :description ... :prefix ...}}."
  [g]
  (->> (source-resources g)
       (canonical-resources)
       (map #(->catalog-entry g %))
       (sort-by (comp str first))
       (into (ordered/ordered-map))))

(comment
  (find-resources (:graph @dk.cst.dannet.web.instance/db))

  ;; The separator variants of a source are dropped, the canonical one is kept.
  (canonical-resources #{"<https://wordnet.dk/dannet>"
                         "<https://wordnet.dk/dannet/>"
                         "<https://en-word.net/>"})
  ;; => ("<https://wordnet.dk/dannet>" "<https://en-word.net/>")

  #_.)
