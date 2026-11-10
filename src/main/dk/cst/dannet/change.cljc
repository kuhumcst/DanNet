(ns dk.cst.dannet.change
  "Changes to the DanNet data, as data: what an editor proposes and what a
  release applies.

  A change is a map of the triples to :add and to :retract, each a set of
  [s p o] vectors, and an optional :where: a vector of [s p o] patterns whose
  ?variables the triples can use. A :where reaches a blank node through the
  resource it hangs off, and it has to match exactly once when the change is
  applied (see dk.cst.dannet.db.change), e.g. for the written form of a word:

      {:where   [[:dn/word-11021628 :ontolex/canonicalForm '?f]
                 ['?f :ontolex/writtenRep #rdf/langstring [\"hund\" \"da\"]]]
       :retract #{['?f :ontolex/writtenRep #rdf/langstring [\"hund\" \"da\"]]}
       :add     #{['?f :ontolex/writtenRep #rdf/langstring [\"hunde\" \"da\"]]}}

  The pending changes of an editor are a vector of changes, one for each
  edit, so that each can be undone on its own.")

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

(defn bind-variables
  "The `change` with the ?variables of its :where replaced by their values in
  `solution`, a map of variable to value as theo/q returns it, and with the
  :where itself left out."
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

(defn preview
  "The `entity` of `subject` with the plain `changes` to its own triples made.
  Its metadata holds the values they add and retract, as maps of property to
  a set of values under ::added and ::retracted, so that the retracted values
  can still be shown. Changes with a :where are left out, since they reach
  nodes other than `subject`."
  [entity subject changes]
  (let [{:keys [add retract]} (net (remove :where changes))
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
