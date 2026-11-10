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
  The problems function checks all of this.

  The match binds the ?variables and gives a plain change, of triples only.
  The pending changes of an editor are a vector of changes, one for each
  edit, so that each can be undone on its own."
  (:require [dk.cst.theophrastus.term :as term]))

(defn add
  "The pending `changes` with the triple `t` added: as a change of its own, or
  by dropping an earlier change that only retracted `t`."
  [changes t]
  (let [retraction {:retract #{t}}]
    (if (some #{retraction} changes)
      (into [] (remove #{retraction}) changes)
      (conj (vec changes) {:add #{t}}))))

(defn retract
  "The pending `changes` with the triple `t` retracted: as a change of its own,
  or by dropping an earlier change that only added `t`."
  [changes t]
  (let [addition {:add #{t}}]
    (if (some #{addition} changes)
      (into [] (remove #{addition}) changes)
      (conj (vec changes) {:retract #{t}}))))

(defn undo
  "The pending `changes` without the change at index `i`."
  [changes i]
  (let [changes (vec changes)]
    (into (subvec changes 0 i) (subvec changes (inc i)))))

(defn- variable?
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
(defn- rooted
  "The ?variables that a chain of the `patterns` reaches from an IRI subject,
  through the objects of the patterns."
  [patterns]
  (loop [reached #{}]
    (let [reached' (into reached
                         (keep (fn [[s _ o]]
                                 (when (and (variable? o)
                                            (or (iri? s) (reached s)))
                                   o)))
                         patterns)]
      (if (= reached reached')
        reached
        (recur reached')))))

(defn problems
  "The problems of the `change`, as maps of a :problem and what it concerns,
  e.g. [{:problem :unbound-variable :variable ?g}], or an empty vector when
  it has none. The namespace docstring says what a change can hold."
  [{:keys [where retract add] :as change}]
  (let [triples  (filter triple-form? (concat where retract add))
        patterns (filter triple-form? (concat where retract))
        reached  (rooted patterns)
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
                              (when-not (iri? p) :not-a-predicate)
                              (when (some term/blank-node? t) :blank-node)]
                 :when       problem]
             {:problem problem :triple t})
           (for [[s :as t] patterns
                 :when     (and (variable? s) (not (reached s)))]
             {:problem :unrooted-variable :variable s :triple t})
           (for [v     (distinct (filter variable? (apply concat add)))
                 :when (not (bound v))]
             {:problem :unbound-variable :variable v})])))

(defn bind-variables
  "The `change` with its ?variables replaced by their values in `solution`,
  a map of variable to value as theo/q returns it, and with the :where left
  out."
  [{:keys [add retract]} solution]
  (let [bind (fn [triples]
               (into #{} (map (fn [t] (mapv #(get solution % %) t))) triples))]
    (cond-> {}
      add (assoc :add (bind add))
      retract (assoc :retract (bind retract)))))

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
