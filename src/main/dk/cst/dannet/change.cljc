(ns dk.cst.dannet.change
  "Changes to the DanNet data, as data: what an editor proposes and what a
  release applies.

  A change is a map of [s p o] patterns under up to three keys:

  - :where, a vector of patterns that lead to what the change touches;
  - :retract, a set of the triples to retract;
  - :add, a set of the triples to add.

  It means what the SPARQL update WITH dn: DELETE {retract} INSERT {add}
  WHERE {where . retract} means, on one condition: the patterns of :where
  and :retract together have to match exactly once in the dn: graph, or the
  change fails (see dk.cst.dannet.db.change). So the retracted triples must
  exist, and a :where only has to lead to them, e.g. for the written form of
  a word:

      {:where   [[:dn/word-11021628 :ontolex/canonicalForm '?f]]
       :retract #{['?f :ontolex/writtenRep #rdf/langstring [\"hund\" \"da\"]]}
       :add     #{['?f :ontolex/writtenRep #rdf/langstring [\"hunde\" \"da\"]]}}

  A subject is an IRI or a ?variable, a predicate is an IRI, and an object
  is an IRI, a ?variable or a literal. A ?variable as a subject has to be
  reached from an IRI through the objects of other patterns, so that a
  change only touches what hangs off a named resource, and every ?variable
  in :add has to occur in a pattern. Blank nodes are only reached through
  ?variables, since their labels don't last from one release to the next.

  A blank node label in :add, e.g. _form, makes a new blank node. The label
  only means something within its change, and each change that is made gets
  a node of its own for it. A new node has to be the object of an added
  triple, so that it hangs off a resource:

      {:add #{[:dn/word-11021628 :ontolex/otherForm '_form]
              ['_form :ontolex/writtenRep #rdf/langstring [\"hunden\" \"da\"]]}}

  The problems function checks all of this.

  A new entry, e.g. a synset, has no ID until a curator accepts the change
  that makes it. Until then it goes by a temporary ID in the dn: namespace,
  e.g. :dn/new-synset-1. A temporary ID is an IRI like any other: the
  shapes cover the entry, and later changes can name it. A release applies
  no change that still has one.

  The match binds the ?variables and gives a plain change, of triples only.
  The pending changes of an editor are a vector of changes, one for each
  edit, so that each can be undone on its own. A pending change names a
  blank node by the label that the page has it under, or by a label that
  the editor made for a new one. When the changes are submitted,
  locate-nodes writes the ?variable and the :where for a node in the data,
  and gathers the additions of a new node into one change."
  (:refer-clojure :exclude [replace])
  (:require [clojure.string :as str]
            [dk.cst.theophrastus.term :as term]
            [dk.cst.dannet.prefix :as prefix]))

;; An edit made twice is made once, so that a field which enters its value
;; twice, e.g. on Enter and again as it loses the focus, leaves one change.

(defn add
  "The pending `changes` with the triple `t` added: as a change of its own, or
  by dropping an earlier change that only retracted `t`."
  [changes t]
  (let [addition   {:add #{t}}
        retraction {:retract #{t}}]
    (cond
      (some #{retraction} changes) (into [] (remove #{retraction}) changes)
      (some #{addition} changes) (vec changes)
      :else (conj (vec changes) addition))))

(defn retract
  "The pending `changes` with the triple `t` retracted: as a change of its own,
  or by dropping an earlier change that only added `t`."
  [changes t]
  (let [addition   {:add #{t}}
        retraction {:retract #{t}}]
    (cond
      (some #{addition} changes) (into [] (remove #{addition}) changes)
      (some #{retraction} changes) (vec changes)
      :else (conj (vec changes) retraction))))

(defn replace
  "The pending `changes` with the triple `old` replaced by the triple `new`,
  as one change that retracts `old` and adds `new`.

  When an earlier change added `old`, that change adds `new` in its place,
  so that replacing a value twice leaves one change. It is dropped when it
  then retracts what it adds."
  [changes old new]
  (cond
    (some #(contains? (:add %) old) changes)
    (into []
          (comp (map (fn [{:keys [add] :as change}]
                       (if (contains? add old)
                         (assoc change :add (-> add (disj old) (conj new)))
                         change)))
                (remove (fn [{:keys [add retract]}]
                          (and (seq add) (= add retract)))))
          changes)

    (some #{{:retract #{old} :add #{new}}} changes)
    (vec changes)

    :else
    (conj (vec changes) {:retract #{old} :add #{new}})))

(defn without-triple
  "The pending `changes` without the ones that add or retract the triple
  `t`, which undoes the edit that `t` is part of."
  [changes t]
  (into []
        (remove #(or (contains? (:add %) t) (contains? (:retract %) t)))
        changes))

(defn without-addition
  "The pending `changes` without the addition of the triple `t`. A change
  that only adds `t` is dropped, and one that does more keeps the rest, so
  that taking a value away puts nothing back."
  [changes t]
  (into []
        (keep (fn [{:keys [add retract] :as change}]
                (if (contains? add t)
                  (let [add (disj add t)]
                    (cond
                      (seq add) (assoc change :add add)
                      (seq retract) (dissoc change :add)))
                  change)))
        changes))

(defn without-node
  "The pending `changes` without the ones that name the blank node `label`
  as a subject or an object. That takes a new node away, and it puts a
  removed one back. The temporary ID of a new entry in place of the label
  takes that entry away."
  [changes label]
  (into []
        (remove (fn [{:keys [add retract]}]
                  (some (fn [[s _ o]] (or (= s label) (= o label)))
                        (concat add retract))))
        changes))

(defn retract-node
  "The pending `changes` with the blank node `label` removed from its
  `place`: the :subject and :property that hold it, and its :values as a
  map of a property to a set of values. One change retracts the node and
  all its values, and the changes made to the node before are dropped."
  [changes label {:keys [subject property values] :as place}]
  (conj (without-node changes label)
        {:retract (into #{[subject property label]}
                        (for [[p vs] values
                              v      vs]
                          [label p v]))}))

(defn undo
  "The pending `changes` without the change at index `i`."
  [changes i]
  (let [changes (vec changes)]
    (into (subvec changes 0 i) (subvec changes (inc i)))))

(defn variable?
  "Is `x` a ?variable?"
  [x]
  (and (symbol? x) (= \? (first (name x)))))

(defn- iri?
  "Is `x` an IRI, as a keyword with a namespace or in angle brackets?"
  [x]
  (or (qualified-keyword? x) (term/iri-string? x)))

(defn- triple-form?
  "Is `x` an [s p o] vector of terms?"
  [x]
  (and (vector? x) (= 3 (count x)) (not-any? coll? x)))

;; TODO: the dn: graph also has blank nodes that nothing points to, mostly
;; owl:NegativePropertyAssertion nodes, and no change can reach them. To
;; edit them, a pattern with an IRI as its object would have to root its
;; subject too, with a cap on the matches, since such a pattern can match
;; many nodes. validate-changes would have to check such a node itself.
(defn- reached
  "The objects of the `triples` that are a `node?` and that a chain of the
  triples reaches from a subject that is a `root?`, through such objects."
  [triples root? node?]
  (loop [found #{}]
    (let [found' (into found
                       (keep (fn [[s _ o]]
                               (when (and (node? o) (or (root? s) (found s)))
                                 o)))
                       triples)]
      (if (= found found')
        found
        (recur found')))))

(defn new-nodes
  "The labels of the blank nodes that the `change` makes, as a set: the
  blank node labels in its :add."
  [{:keys [add]}]
  (into #{}
        (comp (filter triple-form?) cat (filter term/blank-node?))
        add))

(defn temporary?
  "Is `x` the temporary ID of a new entry, e.g. :dn/new-synset-1?"
  [x]
  (cond
    (keyword? x) (and (= "dn" (namespace x))
                      (str/starts-with? (name x) "new-"))
    (string? x) (str/starts-with? x (str "<" prefix/dn-uri "new-"))
    :else false))

(defn temporary-ids
  "The temporary IDs in the `changes`, as a set."
  [changes]
  (into #{}
        (comp (mapcat (juxt :where :retract :add))
              cat
              (filter triple-form?)
              cat
              (filter temporary?))
        changes))

(defn temporary-id
  "A temporary ID for a new entry of the `kind`, e.g. \"synset\", that the
  pending `changes` don't have yet: :dn/new-synset-1, then :dn/new-synset-2."
  [changes kind]
  (->> (iterate inc 1)
       (map #(keyword "dn" (str "new-" kind "-" %)))
       (remove (temporary-ids changes))
       (first)))

(defn problems
  "The problems of the `change`, as maps of a :problem and what it concerns,
  e.g. [{:problem :unbound-variable :variable ?g}], or an empty vector when
  it has none. The namespace docstring says what a change can hold."
  [{:keys [where retract add] :as change}]
  (let [triples  (filter triple-form? (concat where retract add))
        patterns (filter triple-form? (concat where retract))
        rooted   (reached patterns iri? variable?)
        linked   (reached (filter triple-form? add)
                          (some-fn iri? variable?)
                          term/blank-node?)
        bound    (into #{} (comp cat (filter variable?)) patterns)]
    (into []
          cat
          [(for [k (keys change)
                 :when (not (contains? #{:where :retract :add} k))]
             {:problem :unknown-key :key k})
           (when (and (empty? retract) (empty? add))
             [{:problem :nothing-to-change}])
           (for [t     (concat where retract add)
                 :when (not (triple-form? t))]
             {:problem :not-a-triple :triple t})
           (for [[s p :as t] triples
                 problem     [(when-not (or (iri? s)
                                            (variable? s)
                                            (term/blank-node? s))
                                :not-a-subject)
                              (when-not (iri? p) :not-a-predicate)]
                 :when       problem]
             {:problem problem :triple t})
           (for [t     patterns
                 :when (some term/blank-node? t)]
             {:problem :blank-node :triple t})
           (for [[s :as t] patterns
                 :when     (and (variable? s) (not (rooted s)))]
             {:problem :unrooted-variable :variable s :triple t})
           (for [v     (distinct (filter variable? (apply concat add)))
                 :when (not (bound v))]
             {:problem :unbound-variable :variable v})
           (for [node  (sort (new-nodes change))
                 :when (not (linked node))]
             {:problem :unlinked-node :node node})])))

(defn bind-variables
  "The `change` with its ?variables replaced by their values in `solution`,
  a map of variable to value as theo/q returns it, and with the :where left
  out. A `solution` can map the label of a new node to a node in the same
  way."
  [{:keys [add retract]} solution]
  (let [bind (fn [triples]
               (into #{} (map (fn [t] (mapv #(get solution % %) t))) triples))]
    (cond-> {}
      add (assoc :add (bind add))
      retract (assoc :retract (bind retract)))))

(defn- node-patterns
  "The patterns that find a blank node as `?node`: its place, i.e. the
  :subject and :property that hold it, and each of its `values` that the
  change doesn't name in `retracted`, as pairs of a property and a value."
  [{:keys [subject property]} ?node values retracted]
  (into [[subject property ?node]]
        ;; sorted, so that the same change always has the same :where
        (sort-by pr-str (for [[p vs] values
                              v      vs
                              :when  (not (contains? retracted [p v]))]
                          [?node p v]))))

(defn- locate
  "The `change` with its blank nodes located by patterns, when `nodes` has
  the place of each and `values` has the values of each at that point."
  [nodes values {:keys [where retract add] :as change}]
  (let [labels (->> (concat retract add)
                    (mapcat (juxt first peek))
                    (filter #(contains? nodes %))
                    (distinct))
        ?node  (zipmap labels
                       (cons '?node
                             (map #(symbol (str "?node" %)) (iterate inc 2))))
        rename (fn [triples]
                 (into #{}
                       (map (fn [[s p o]] [(get ?node s s) p (get ?node o o)]))
                       triples))
        find   (fn [label]
                 (node-patterns (get nodes label)
                                (?node label)
                                (get values label)
                                (into #{}
                                      (keep (fn [[s p o]]
                                              (when (= s label) [p o])))
                                      retract)))]
    (if (empty? labels)
      change
      (cond-> {:where (into (vec where) (mapcat find) labels)}
        (seq retract) (assoc :retract (rename retract))
        (seq add) (assoc :add (rename add))))))

(defn- changed-values
  "The `values` of blank nodes, as a map of a label to a map of a property
  to a set of values, with the triples of `retract` and `add` made."
  [values retract add]
  (as-> values m
        (reduce (fn [m [s p o]] (update-in m [s p] disj o)) m retract)
        (reduce (fn [m [s p o]] (update-in m [s p] (fnil conj #{}) o)) m add)))

(defn- gather
  "The pending `changes` with the ones that name a blank node that is `new?`
  merged into one change for each node, in the place of the first of them."
  [changes new?]
  (let [label  (fn [{:keys [add]}]
                 (first (filter new? (mapcat (juxt first peek) add))))
        merged (reduce (fn [m change]
                         (if-let [node (label change)]
                           (update m node #(merge-with into % change))
                           m))
                       {}
                       changes)]
    ;; each change of a node becomes the merged one, which is then kept once
    (distinct (map #(get merged (label %) %) changes))))

(defn locate-nodes
  "The pending `changes` as they can be applied, when `nodes` is a map of the
  label of each blank node in them to its place.

  A place is the :subject and :property that hold the node, and its :values
  in the data as a map of a property to a set of values. A change to such
  a node gets a ?variable for the label, and a :where with the place and
  with the values as the changes before it leave them. A node that an
  editor has made is :new?, and its additions become one change."
  [changes nodes]
  (let [new?     #(:new? (get nodes %))
        nodes    (into {} (remove (comp :new? val)) nodes)
        of-node? #(contains? nodes (first %))]
    (first
      (reduce (fn [[located values] {:keys [retract add] :as change}]
                [(conj located (locate nodes values change))
                 (changed-values values
                                 (filter of-node? retract)
                                 (filter of-node? add))])
              [[] (update-vals nodes :values)]
              (gather changes new?)))))

(defn net
  "The plain `changes` taken together, as one change of the triples to :add and
  to :retract. A triple that a change adds and a later one retracts cancels
  out, and so does the other way round; within a change, the retractions come
  first, as in a SPARQL update."
  [changes]
  (letfn [(retract* [{:keys [add] :as acc} t]
            (if (contains? add t)
              (update acc :add disj t)
              (update acc :retract conj t)))
          (add* [{:keys [retract] :as acc} t]
            (if (contains? retract t)
              (update acc :retract disj t)
              (update acc :add conj t)))]
    (reduce (fn [acc {:keys [add retract]}]
              (reduce add* (reduce retract* acc retract) add))
            {:add #{} :retract #{}}
            changes)))

(defn resources
  "The IRIs in the triples of the pending `changes`, as a set: every subject,
  property and object that is a keyword."
  [changes]
  (into #{}
        (comp (mapcat (juxt :add :retract)) cat cat (filter keyword?))
        changes))

(defn changed-properties
  "The properties of the subject `s` whose values the pending `changes` add
  or retract, as a set."
  [changes s]
  (into #{}
        (comp (mapcat (juxt :add :retract))
              cat
              (filter #(= s (first %)))
              (map second))
        changes))

(defn moves
  "The values that the pending `changes` move from one property of the
  subject `s` to another, as maps of the :value, the property that it moves
  :from and the one that it moves :to. A move is a change that retracts a
  value under one property and adds the same value under another."
  [changes s]
  (for [{:keys [add retract]} changes
        [s' from v] retract
        [s'' to v'] add
        :when       (and (= s s' s'') (= v v') (not= from to))]
    {:value v :from from :to to}))

(defn property-changes
  "What the pending `changes` do to the values of the property `p` of the
  subject `s`: the values that they have :added and :retracted in all, as
  sets, and the ones :replaced, as a map of a retracted value to the values
  that its change adds."
  [changes s p]
  (let [values (fn [triples]
                 (into #{}
                       (keep (fn [[s' p' o]]
                               (when (and (= s' s) (= p' p))
                                 o)))
                       triples))
        {:keys [add retract]} (net changes)]
    {:added     (values add)
     :retracted (values retract)
     :replaced  (reduce (fn [m {:keys [add retract]}]
                          (if-let [added (not-empty (values add))]
                            (reduce #(update %1 %2 (fnil into []) added)
                                    m
                                    (values retract))
                            m))
                        {}
                        changes)}))

(defn- with-value
  "The `entity` with `v` among the values of the property `p`."
  [entity p v]
  (let [old (get entity p)]
    (assoc entity p (cond
                      (nil? old) v
                      (set? old) (conj old v)
                      (sequential? old) (if (some #{v} old) old (conj (vec old) v))
                      (= old v) old
                      :else #{old v}))))

(defn- without-value
  "The `entity` without `v` among the values of the property `p`."
  [entity p v]
  (let [old       (get entity p)
        remaining (cond
                    (set? old) (not-empty (disj old v))
                    (sequential? old) (not-empty (into [] (remove #{v}) old))
                    (= old v) nil
                    :else old)]
    (if (nil? remaining)
      (dissoc entity p)
      (assoc entity p remaining))))

(defn- plain?
  "Is the `change` plain, of triples only, without a :where or ?variables?"
  [{:keys [where retract add]}]
  (and (empty? where)
       (not-any? variable? (concat (apply concat retract) (apply concat add)))))

(defn preview
  "The `entity` of `subject` with the plain `changes` to its own triples made.
  Its metadata holds the values they add and retract, as maps of property to
  a set of values under ::added and ::retracted, so that the retracted values
  can still be shown. Changes that aren't plain are left out, since only the
  db can resolve them."
  [entity subject changes]
  (let [{:keys [add retract]} (net (filter plain? changes))
        own       (fn [triples]
                    (reduce (fn [m [s p o]]
                              (if (= s subject)
                                (update m p (fnil conj #{}) o)
                                m))
                            {}
                            triples))
        added     (own add)
        retracted (own retract)
        apply-all (fn [entity f by-property]
                    (reduce-kv (fn [entity p vs] (reduce #(f %1 p %2) entity vs))
                               entity
                               by-property))]
    (-> entity
        (apply-all without-value retracted)
        (apply-all with-value added)
        (vary-meta merge {::added added ::retracted retracted}))))
