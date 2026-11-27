(ns dk.cst.dannet.change
  "Changes to the DanNet data, as data: what an editor proposes and what a
  release applies.

  A change is a map with up to four keys. Three hold [s p o] patterns, and
  the fourth holds resources:

  - :where, a vector of patterns that lead to what the change touches;
  - :retract, a set of the triples to retract;
  - :add, a set of the triples to add;
  - :unreference, a set of resources that nothing shall refer to.

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

  The :unreference of a change is a rule and not a list. It retracts every
  triple that points to one of its resources, as the graph has them when
  the change is made, however many they are. A blank node that only pointed
  to them by that property goes too, with its values. So the removal of a
  resource names its own triples, which have to match, and leaves what
  refers to it to the rule:

      {:retract     #{[:dn/sense-1 :rdf/type :ontolex/LexicalSense] ...}
       :unreference #{:dn/sense-1}}

  The problems function checks all of this. The match binds the ?variables
  and gives a plain change, of triples only.

  A new entry goes by a temporary ID until a curator accepts the change
  that makes it (see temporary?). The pending changes of an editor are a
  vector of changes, one for each edit, so that each can be undone on its
  own. They name a blank node by its label, and locate-nodes makes them
  into changes that can be applied."
  (:refer-clojure :exclude [replace])
  (:require [clojure.string :as str]
            [dk.cst.theophrastus.term :as term]
            [dk.cst.dannet.prefix :as prefix]))

;; An edit made twice is made once, so that a field which enters its value
;; twice, e.g. on Enter and again as it loses the focus, leaves one change.

(defn with-change
  "The pending `changes` with the `change` made, once: as a change of its
  own, or by dropping an earlier change that is its `opposite`."
  [changes change opposite]
  (cond
    (some #{opposite} changes) (into [] (remove #{opposite}) changes)
    (some #{change} changes) (vec changes)
    :else (conj (vec changes) change)))

(defn add
  "The pending `changes` with the triple `t` added: as a change of its own, or
  by dropping an earlier change that only retracted `t`."
  [changes t]
  (with-change changes {:add #{t}} {:retract #{t}}))

(defn retract
  "The pending `changes` with the triple `t` retracted: as a change of its own,
  or by dropping an earlier change that only added `t`."
  [changes t]
  (with-change changes {:retract #{t}} {:add #{t}}))

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
  `t`, which undoes the edit that `t` is part of. A change that unreferences
  what `t` points to retracts it too."
  [changes t]
  (into []
        (remove #(or (contains? (:add %) t)
                     (contains? (:retract %) t)
                     (contains? (:unreference %) (peek t))))
        changes))

(defn without-triples
  "The pending `changes` without the triples that are `named?`, a function
  of the key that a triple is under and the triple. A change keeps its
  other triples, and what it unreferences stays as it is."
  [changes named?]
  (let [others (fn [change]
                 (into (select-keys change [:unreference])
                       (keep (fn [[k triples]]
                               (when-let [kept (seq (remove #(named? k %)
                                                            triples))]
                                 [k (set kept)])))
                       (dissoc change :unreference)))]
    (into [] (comp (map others) (filter seq)) changes)))

(defn without-addition
  "The pending `changes` without the addition of the triple `t`. A change
  that only adds `t` is dropped, and one that does more keeps the rest, so
  that taking a value away puts nothing back."
  [changes t]
  (without-triples changes (fn [k t'] (and (= k :add) (= t' t)))))

(defn without-node
  "The pending `changes` without the triples that name the blank node
  `label` as a subject or an object. That takes a new node away, and it
  puts a removed one back. A change keeps its other triples, e.g. the ones
  of a new word that was made with the node."
  [changes label]
  (without-triples changes (fn [_ [s _ o]] (or (= s label) (= o label)))))

(defn without-entry
  "The pending `changes` without the edits to the entry `id`: the triples
  that it is the subject of, and the additions that point to it. That takes
  a new entry away.

  A retraction that points to the entry stays. It is an edit to the entry
  that had the link, whatever happens to the one that it pointed to."
  [changes id]
  (without-triples changes (fn [k [s _ o]]
                             (or (= s id)
                                 (and (= o id) (not= k :retract))))))

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

(defn variable?
  "Is `x` a ?variable?"
  [x]
  (and (symbol? x) (= \? (first (name x)))))

(defn- iri?
  "Is `x` an IRI, as a keyword with a namespace or in angle brackets?"
  [x]
  (or (qualified-keyword? x) (term/iri-string? x)))

(defn triple-form?
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
  [{:keys [where retract add unreference] :as change}]
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
                 :when (not (contains? #{:where :retract :add :unreference}
                                       k))]
             {:problem :unknown-key :key k})
           (when (and (empty? retract) (empty? add) (empty? unreference))
             [{:problem :nothing-to-change}])
           (for [x     unreference
                 :when (not (iri? x))]
             {:problem :not-a-resource :resource x})
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
  "The patterns that find the blank node at `place` as `?node`: the :subject
  and :property that hold it, and each of its `values` that the change
  doesn't name in `retracted`, as pairs of a property and a value."
  [{:keys [subject property] :as place} ?node values retracted]
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
      (cond-> (assoc (select-keys change [:unreference])
                :where (into (vec where) (mapcat find) labels))
        (seq retract) (assoc :retract (rename retract))
        (seq add) (assoc :add (rename add))))))

(defn- changed-values
  "The `values` of blank nodes, as a map of a label to a map of a property
  to a set of values, with the triples of `retract` and `add` made."
  [values retract add]
  (as-> values m
        (reduce (fn [m [s p o]] (update-in m [s p] disj o)) m retract)
        (reduce (fn [m [s p o]] (update-in m [s p] (fnil conj #{}) o)) m add)))

(defn unreferenced
  "The resources that the `changes` unreference, as a set."
  [changes]
  (into #{} (mapcat :unreference) changes))

(defn net
  "The plain `changes` taken together, as one change of the triples to :add and
  to :retract. A triple that a change adds and a later one retracts cancels
  out, and so does the other way round; within a change, the retractions come
  first, as in a SPARQL update. The resources that they unreference are kept
  under :unreference when there are some."
  [changes]
  (letfn [(retract* [{:keys [add] :as acc} t]
            (if (contains? add t)
              (update acc :add disj t)
              (update acc :retract conj t)))
          (add* [{:keys [retract] :as acc} t]
            (if (contains? retract t)
              (update acc :retract disj t)
              (update acc :add conj t)))]
    (cond-> (reduce (fn [acc {:keys [add retract]}]
                      (reduce add* (reduce retract* acc retract) add))
                    {:add #{} :retract #{}}
                    changes)
      (some :unreference changes)
      (assoc :unreference (unreferenced changes)))))

(defn removed
  "The entries that the `change` removes as a whole, as a set: the named
  subjects whose type it retracts.

  No other edit retracts a type. A change that does retracts every triple of
  the data that names the entry (see dk.cst.dannet.db.change/removal-parts)."
  [{:keys [retract] :as change}]
  (into #{}
        (keep (fn [[s p _]]
                (when (and (keyword? s) (= p :rdf/type))
                  s)))
        retract))

(defn- gather
  "The pending `changes` with the ones that name the same thing that is
  `new?` taken together as one change (see net), in the place of the first
  of them. Two changes are also taken together when a third one names a new
  thing of each, e.g. a new word and its new sense."
  [changes new?]
  (let [names  (fn [{:keys [retract add]}]
                 (into #{}
                       (comp (mapcat (juxt first peek)) (filter new?))
                       (concat retract add)))
        ;; each new thing with all the ones that it shares a change with
        groups (reduce (fn [groups named]
                         (let [group (into named (mapcat groups) named)]
                           (into groups (map #(vector % group)) group)))
                       {}
                       (map names changes))
        group  #(some-> (first (names %)) groups)
        parts  (group-by group changes)
        whole  (fn [change]
                 (let [together (some->> (group change) (get parts))]
                   (if (next together)
                     (into {} (filter (comp seq val)) (net together))
                     change)))]
    ;; each part becomes the whole, which is then kept once
    (remove empty? (distinct (map whole changes)))))

(defn locate-nodes
  "The pending `changes` as they can be applied, when `nodes` is a map of the
  label of each blank node in them to its place.

  A place is the :subject and :property that hold the node, and its :values
  in the data as a map of a property to a set of values. A change to such
  a node gets a ?variable for the label, and a :where with the place and
  with the values as the changes before it leave them.

  The changes that name a :new? node, which an editor has made, become one
  change. So do the ones that name a new entry, and the ones that name an
  entry which one of them removes as a whole, e.g. the removals of a word
  and of the synset of its sense, which retract the same triples."
  [changes nodes]
  (let [new?     #(:new? (get nodes %))
        removed? (into #{} (mapcat removed) changes)
        nodes    (into {} (remove (comp :new? val)) nodes)
        of-node? #(contains? nodes (first %))]
    (first
      (reduce (fn [[located values] {:keys [retract add] :as change}]
                [(conj located (locate nodes values change))
                 (changed-values values
                                 (filter of-node? retract)
                                 (filter of-node? add))])
              [[] (update-vals nodes :values)]
              (gather changes (some-fn new? temporary? removed?))))))

(defn resources
  "The IRIs in the pending `changes`, as a set: every subject, property and
  object of their triples that is a keyword, and every resource that they
  unreference."
  [changes]
  (into (unreferenced changes)
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
