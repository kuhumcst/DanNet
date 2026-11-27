(ns dk.cst.dannet.db.derived
  "The values in the dn: graph that follow from other values in it: the
  label of a synset, the synsets of a word and the scheme of a resource.

  Each is found as a plain change (see dk.cst.dannet.change) that brings
  the graph up to date, for the whole graph or for some resources.

  The editor has no fields for these values: its rules lock their
  properties (see :derived and :derived-by-class in dk.cst.dannet.editable)."
  (:require [clojure.set :as set]
            [dk.cst.theophrastus :as theo]
            [dk.cst.theophrastus.term :as term]
            [dk.cst.theophrastus.txn :as txn]
            [dk.cst.dannet.db.bootstrap.metadata :as md]
            [dk.cst.dannet.db.query.operation :as op]
            [dk.cst.dannet.hash :as h]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.shared :as shared]))

(defn- by
  "The values of `v` in the `rows` for each value of `k`, as a map of sets."
  [rows k v]
  (reduce (fn [m row]
            (if-some [x (get row v)]
              (update m (get row k) (fnil conj #{}) x)
              m))
          {}
          rows))

(defn- pairs
  "The values of `k` and `v` in the `rows`, as a set of pairs."
  [rows k v]
  (into #{} (map (juxt k v)) rows))

(h/defn synset-labels
  "The change that brings the label of each synset in the dn: graph `g` up
  to date, or of the `synsets` only.

  The label of a synset is made from the labels of its senses (see
  shared/synset-label). A synset without senses keeps the label it has."
  ([g]
   (synset-labels g nil))
  ([g synsets]
   (let [rows    (theo/q g op/synset-and-sense-labels
                         (when synsets {'?synset synsets}))
         current (by rows '?synset '?label)
         stale   (for [[synset labels] (by rows '?synset '?senseLabel)
                       :let  [label (md/da (shared/synset-label labels))
                              old   (get current synset)]
                       :when (not= #{label} old)]
                   [synset old label])]
     {:retract (set (for [[synset old] stale
                          label        old]
                      [synset :rdfs/label label]))
      :add     (set (for [[synset _ label] stale]
                      [synset :rdfs/label label]))})))

(h/defn evoked-synsets
  "The change that brings the ontolex:evokes links of each word in the dn:
  graph `g` up to date, or of the `words` only.

  A word evokes the synsets that its senses are in."
  ([g]
   (evoked-synsets g nil))
  ([g words]
   (let [bindings (when words {'?word words})
         link     (fn [[word synset]] [word :ontolex/evokes synset])
         current  (pairs (theo/q g op/evoked-synsets bindings) '?word '?synset)
         expected (pairs (theo/q g op/sense-synsets bindings) '?word '?synset)]
     {:retract (set (map link (set/difference current expected)))
      :add     (set (map link (set/difference expected current)))})))

(h/defn in-scheme
  "The change that adds the `scheme` to each subject in the graph `g` whose
  IRI starts with `ns-uri`, or to those among the `resources` only."
  ([g ns-uri scheme]
   (in-scheme g ns-uri scheme nil))
  ([g ns-uri scheme resources]
   (let [subjects (op/sparql
                    "SELECT DISTINCT ?s
                     WHERE {
                       ?s ?p ?o .
                       FILTER(isIRI(?s) && STRSTARTS(STR(?s), \"" ns-uri "\"))
                     }")]
     {:add (into #{}
                 (map (fn [{:syms [?s]}] [?s :skos/inScheme scheme]))
                 (theo/q g subjects (when resources {'?s resources})))})))

(h/defn write!
  "Make the plain `change` in the graph `g`, in one write transaction."
  [g {:keys [retract add] :as change}]
  (txn/with-write-transaction g
    (theo/retract! g (vec retract))
    (theo/transact! g (vec add))))

(defn- holders
  "The subjects in the graph `g` that have one of the `resources` as a value
  of the property `p`."
  [g p resources]
  (into #{}
        (mapcat (fn [r] (map first (theo/triples g ['_ p r]))))
        resources))

(defn derive!
  "Write the derived values of the resources in the plain `changes` to the
  dn: graph `g`, as the release build would leave them. A check of the
  changes then doesn't report a new entry for lacking what the build adds.

  A resource is the subject or the object of a changed triple. A sense
  among them also counts for its synsets and its word, since their derived
  values follow from it."
  [g changes]
  (let [iris (into #{}
                   (comp (mapcat (juxt :add :retract))
                         cat
                         (mapcat (juxt first peek))
                         (filter (some-fn keyword? term/iri-string?)))
                   changes)]
    (write! g (in-scheme g prefix/dn-uri md/<dn> iris))
    (write! g (synset-labels
                g (into iris (holders g :ontolex/lexicalizedSense iris))))
    (write! g (evoked-synsets
                g (into iris (holders g :ontolex/sense iris))))))
