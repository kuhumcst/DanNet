(ns dk.cst.dannet.db.catalog
  (:require [clojure.string :as str]
            [flatland.ordered.map :as ordered]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.db.query :as q]
            [dk.cst.dannet.db.query.operation :as op]))

(defn find-resources
  "Find known schemas and datasets referenced in the graph `g`.

  Returns an ordered map of `{rdf-resource -> {:label ... :description ... :prefix ...}}`."
  [g]
  (let [results         (->> (q/run g op/catalog-resources)
                             (filter (comp (some-fn keyword? prefix/rdf-resource?) '?source))
                             (remove (fn [{:syms [?source]}]
                                       (when (string? ?source)
                                         (str/includes? ?source "www.w3.org/TR/"))))
                             (map (fn [{:syms [?source] :as m}]
                                    (if (keyword? ?source)
                                      (update m '?source prefix/kw->rdf-resource)
                                      m))))
        ;; Collect unique sources, normalized to remove trailing separators
        sources         (->> results
                             (map '?source)
                             (set))
        ;; NB: only sources ALREADY in normalized form count as canonical.
        ;; Deriving this set by normalizing every source would unconditionally
        ;; drop sources with trailing separators, e.g. the OEWN dataset
        ;; resource <https://en-word.net/> (GitHub issue #178).
        normalized-keys (set (filter #(= % (prefix/normalize-rdf-resource %))
                                     sources))
        ;; Remove entries with trailing separators when normalized version exists
        unique-sources  (remove (fn [src]
                                  (let [normalized (prefix/normalize-rdf-resource src)]
                                    (and (not= src normalized)
                                         (contains? normalized-keys normalized))))
                                sources)]
    ;; Fetch label, description, and prefix for each catalog resource
    (->> unique-sources
         (map (fn [rdf-resource]
                (let [uri    (prefix/rdf-resource->uri rdf-resource)
                      entity (q/entity g rdf-resource)
                      label  (or (:dc11/title entity)
                                 (:dc/title entity)
                                 (:rdfs/label entity))
                      desc   (reduce q/set-merge nil
                                     (keep entity [:rdfs/comment
                                                   :dc/description
                                                   :dc11/description]))
                      ;; Try to get prefix: from entity, from known schemas, or nil
                      pfx    (or (:vann/preferredNamespacePrefix entity)
                                 ;; Datasets whose resource URI doesn't share a
                                 ;; namespace with their resources, e.g. COR.
                                 (prefix/rdf-resource->prefix rdf-resource)
                                 (prefix/uri->prefix uri)
                                 (prefix/uri->prefix (str uri "#"))
                                 (prefix/uri->prefix (str uri "/")))]
                  [rdf-resource {:label       label
                                 :description desc
                                 :prefix      pfx}])))
         (sort-by (comp str first))
         (into (ordered/ordered-map)))))

(comment
  (find-resources (:graph @dk.cst.dannet.web.instance/db))
  #_.)
