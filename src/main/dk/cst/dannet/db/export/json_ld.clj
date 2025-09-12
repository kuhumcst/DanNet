(ns dk.cst.dannet.db.export.json-ld
  "Convert DanNet RDF entities to JSON-LD format.
  
  Transforms entity maps with namespaced keywords into JSON-LD structure
  with proper @context and @id mappings."
  (:require [clojure.walk :as walk]
            [flatland.ordered.map :as fop]
            [dk.cst.dannet.web.i18n :as i18n]
            [dk.cst.dannet.prefix :as prefix])
  (:import [ont_app.vocabulary.lstr LangStr]
           [org.apache.jena.datatypes BaseDatatype$TypedValue]))

(defn entity->prefixes
  "Extract unique prefixes from namespaced keywords in RDF resource `entity`."
  [entity]
  (let [prefixes (atom #{})]
    (walk/postwalk #(when (keyword? %)
                      (when-let [prefix (namespace %)]
                        (swap! prefixes conj (symbol prefix))))
                   entity)
    @prefixes))

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

(def entity->context
  (comp prefixes->context entity->prefixes))

(defn transform-key
  "Transform `k` to a JSON-LD property name."
  [k]
  (if (keyword? k)
    (prefix/kw->qname k)
    k))

(declare entity->properties)

(defn- transform-value
  "Transform `v` into a valid JSON-LD representation."
  [v]
  (cond
    (instance? LangStr v)
    {"@value"    (str v)
     "@language" (i18n/lang v)}

    ;; Should handle e.g. XSDDateTime and other special values
    (instance? BaseDatatype$TypedValue v)
    {"@value" (.getLexicalValue v)
     "@type"  (str (.getDatatypeURI v))}

    (set? v)
    (if (= (count v) 1)
      (transform-value (first v))
      (map transform-value v))

    (keyword? v)
    (if-let [qname (prefix/kw->qname v)]
      qname
      (str v))

    ;; Symbols are used in the Clojure map to represent complex sub-entities.
    ;; Their metadata comprises the realised data and can be substituted.
    (symbol? v)
    (let [blank-node (entity->properties (meta v))]
      ;; Special handling of rdf:Bag type (sets) used for e.g. ontological type.
      (if (= (get blank-node "rdf:type") "rdf:Bag")
        {"@set" (->> (dissoc blank-node "rdf:type")
                     (sort-by first)
                     (map second))}
        blank-node))
    :else (str v)))

(defn- entity->properties
  "Transform RDF resource `entity` key-vals to JSON-LD format."
  [entity]
  (->> entity
       (reduce-kv (fn [result k v]
                    (assoc result
                      (transform-key k)
                      (transform-value v)))
                  {})
       (sort-by first)
       (into (sorted-map))))

(def non-rdf-ks
  #{:subject :inferred :languages :entities :synset-weights})

;; TODO: take lang into account
;; TODO: use nil entity to signal @graph entities only?
;;       this can be used to represent search results or other colls
(defn json-ld-ify
  "Convert DanNet `entity` map to a JSON-LD structure. Optionally, a coll of
  supporting `entities` may be supplied as a graph around the core entity.

  NOTE: outputs JSON-LD 1.1 which allows combining one RDF resource defined by
        @id with a @graph of other RDF resources. In JSON-LD 1.0 this would be
        either-or."
  [{:keys [dc/subject rdf/type rdfs/label rdfs/comment] :as entity} & [entities]]
  ;; TODO: include any dissoc'd parts that make sense (if at all possible)
  (let [subject        (or (:subject (meta entity))
                           subject)
        core-entity    (apply dissoc entity :dc/subject :rdf/type non-rdf-ks)
        core-context   (into (sorted-map) (entity->context core-entity))
        graph-entities (not-empty (map json-ld-ify entities))
        graph-contexts (map #(get % "@context") graph-entities)
        context        (apply merge core-context graph-contexts)
        properties     (entity->properties core-entity)]

    ;; NOTE: fop/ordered-map is needed to maintain insertion order, as array-map
    ;;       gets converted into a hash-map once it reaches a certain size.
    (cond-> (fop/ordered-map)

      ;; Semantic ordering: @context, @id, and @type come first.
      (seq context) (assoc "@context" context)
      subject (assoc "@id" (transform-value subject))
      type (assoc "@type" (transform-value type))

      ;; Important RDFS properties come next: label and comment.
      ;; NOTE: rdfs:comment is often used to explain what's inside the @graph.
      label (assoc "rdfs:label" (get properties "rdfs:label"))
      comment (assoc "rdfs:comment" (get properties "rdfs:comment"))

      ;; Then the other properties of the core resource/entity follow.
      properties (into (dissoc properties "rdfs:label" "rdfs:comment"))

      ;; Semantically, @graph always comes last as it often contains a list of
      ;; additional supporting data for the core RDF resource, e.g. a SynSet.
      ;; NOTE: the graph is presumed to be pre-sorted (e.g. search results),
      ;;       so they should *NOT* be sorted here!
      graph-entities (assoc "@graph" (map #(dissoc % "@context") graph-entities)))))

(comment
  ;; Test with simple entity structure
  (def test-entity
    (with-meta {:rdf/type    #{:ontolex/LexicalConcept}
                :rdfs/label  (dk.cst.dannet.db.bootstrap/da "test synset")
                :wn/hypernym #{:dn/synset-123}}
               {:subject :dn/synset-123}))

  (entity->kws test-entity)
  (entity->prefixes test-entity)
  (entity->context test-entity)
  (json-ld-ify test-entity)

  ;; testing with a real entity
  (let [entity         (dk.cst.dannet.query/expanded-entity
                         (:graph @dk.cst.dannet.web.resources/db)
                         :dn/synset-s50002104)
        graph-entities (map (fn [[subject entity]] (assoc entity :rdf/about subject))
                            (:entities (meta entity)))]
    (json-ld-ify entity graph-entities))
  #_.)
