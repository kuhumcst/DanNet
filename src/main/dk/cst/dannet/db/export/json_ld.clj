(ns dk.cst.dannet.db.export.json-ld
  "Convert DanNet RDF entities to JSON-LD format.
  
  Transforms entity maps with namespaced keywords into JSON-LD structure
  with proper @context and @id mappings."
  (:require [clojure.walk :as walk]
            [dk.cst.dannet.web.i18n :as i18n]
            [dk.cst.dannet.prefix :as prefix])
  (:import [ont_app.vocabulary.lstr LangStr]))

(defn entity->kws
  "Return the keywords contained in the RDF resource `entity` at any level."
  [entity]
  (let [kws (atom #{})]
    (walk/postwalk #(when (keyword? %)
                      (swap! kws conj %))
                   entity)
    @kws))

(defn entity->prefixes
  "Extract unique prefixes from namespaced keywords in RDF resource `entity`."
  [entity]
  (into #{}
        (comp
          (keep namespace)
          (map symbol))
        (entity->kws entity)))

(defn prefixes->context
  "Build JSON-LD @context map from `prefixes` set.
  
  Maps each prefix to its full URI using the prefix system."
  [prefixes]
  (->> prefixes
       (reduce (fn [context prefix]
                 (if-let [uri (prefix/prefix->uri prefix)]
                   (assoc context (str prefix) uri)
                   context))
               {})))

(defn transform-key
  "Transform `k` to a JSON-LD property name."
  [k]
  (if (keyword? k)
    (prefix/kw->qname k)
    k))

(defn- transform-value
  "Transform `v` into a valid JSON-LD representation."
  [v]
  (cond
    (instance? LangStr v)
    {"@value"    (str v)
     "@language" (i18n/lang v)}

    (set? v)
    (->> v (map transform-value) vec)

    (keyword? v)
    (if-let [qname (prefix/kw->qname v)]
      qname
      (str v))

    (coll? v)
    (mapv transform-value v)

    ;; TODO: other transformations needed?
    :else (str v)))

(defn- transform-entity-key-vals
  "Transform RDF resource `entity` key-vals to JSON-LD format."
  [entity]
  (->> entity
       (reduce-kv (fn [result k v]
                    (assoc result
                      (transform-key k)
                      (transform-value v)))
                  {})))

(def non-rdf-keys
  #{:subject :inferred :languages :entities :synset-weights})

(defn json-ld-ify
  "Convert DanNet `entity` map to a JSON-LD structure."
  [entity]
  ;; TODO: include any dissoc'd parts that make sense (if at all possible)
  (let [rdf-entity (apply dissoc entity non-rdf-keys)
        subject    (or (:rdf/about rdf-entity)
                       (:subject (meta rdf-entity)))
        context    (-> rdf-entity entity->prefixes prefixes->context)
        properties (transform-entity-key-vals rdf-entity)]
    (cond-> properties
      (seq context) (assoc "@context" context)
      subject (assoc "@id" (transform-value subject)))))

(comment
  ;; Test with simple entity structure
  (def test-entity
    (with-meta {:rdf/type    #{:ontolex/LexicalConcept}
                :rdfs/label  (dk.cst.dannet.db.bootstrap/da "test synset")
                :wn/hypernym #{:dn/synset-123}}
               {:subject :dn/synset-123}))

  (entity->kws test-entity)
  (entity->prefixes test-entity)
  (json-ld-ify test-entity)

  ;; testing with a real entity
  (json-ld-ify (dk.cst.dannet.query/entity
                 (:graph @dk.cst.dannet.web.resources/db)
                 :dn/synset-s50002104))
  #_.)
