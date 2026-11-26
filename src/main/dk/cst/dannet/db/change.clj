(ns dk.cst.dannet.db.change
  "Changes (see dk.cst.dannet.change) resolved and made in the db of the
  graph that changes write to, the dn: graph in DanNet.

  A change resolves when the patterns of its :where and :retract match
  exactly once. A change that a later release has made stale then fails
  instead of touching the wrong node, and so does one whose patterns find
  duplicate blank nodes, which in DanNet point to an import bug."
  (:require [clojure.edn :as edn]
            [dk.cst.theophrastus :as theo]
            [dk.cst.theophrastus.convert :as convert]
            [dk.cst.theophrastus.term :as term]
            [dk.cst.theophrastus.txn :as txn]
            [dk.cst.dannet.change :as change])
  (:import [org.apache.jena.graph Triple]
           [org.apache.jena.query Query]
           [org.apache.jena.sparql.syntax ElementTriplesBlock]))

(defn read-changes
  "The vector of changes in the EDN `source`, e.g. a file or a resource."
  [source]
  (edn/read-string {:readers {'rdf/langstring term/read-langstring
                              'rdf/literal    term/read-literal}}
                   (slurp source)))

(defn- node-values
  "The values of the blank `node` in the graph `g`, as a map of a property
  to a set of values."
  [g node]
  (reduce (fn [m [_ p v]] (update m p (fnil conj #{}) v))
          {}
          (theo/triples g [node '_ '_])))

(defn- removal-parts
  "What removes the entries `ids` from the graph `g` by the `rules`, when
  the entries in `gone` are the ones that go in all: a map of the :entry,
  the :nodes and the :references, as removal has them."
  [g gone ids {:keys [links derived] :as rules}]
  (let [own      (mapcat #(theo/triples g [% '_ '_]) ids)
        nodes    (into {}
                       (for [[s p node] own
                             :when      (term/blank-node? node)]
                         [node {:subject  s
                                :property p
                                :values   (node-values g node)}]))
        ;; a blank node belongs to the entries that hold it
        stays?   (fn [s]
                   (not-any? gone (if (term/blank-node? s)
                                    (map first (theo/triples g ['_ '_ s]))
                                    [s])))
        incoming (for [x ids
                       t (theo/triples g ['_ '_ x])
                       :when (stays? (first t))]
                   t)
        part?    (fn [[_ p _]] (or (contains? links p) (contains? derived p)))]
    {:entry      (into #{}
                       (map vec)
                       (concat own
                               (filter part? incoming)
                               (for [[node {:keys [values]}] nodes
                                     [p vs] values
                                     v      vs]
                                 [node p v])))
     :nodes      nodes
     :references (frequencies (map second (remove part? incoming)))}))

(defn removal
  "What removes the entry `id` from the dn: graph `g` by the `rules`, as a
  map of the parts that an editor chooses between. Nil when no triple of
  the graph names the entry.

  The rules have the properties that are :links and the ones that are
  :derived. An entry goes with what it holds by a link, e.g. a word with
  its senses. The parts are:

  - :entry, the triples of them all, with the values of their blank nodes
    and with the links and the derived triples that point to them;
  - :nodes, the places of those blank nodes (see change/locate-nodes);
  - :references, how many triples of other entries point to them, as a map
    of a property to a number. A change that unreferences them retracts
    those (see resolve-change);
  - :orphans, the entries that hold nothing by a link once the entry is
    gone, e.g. the synset of the last sense of a word, each with the same
    parts of its own."
  [g id {:keys [links] :as rules}]
  (let [held    (fn [x p] (map peek (theo/triples g [x p '_])))
        ids     (distinct (tree-seq some? #(mapcat (partial held %) links) id))
        gone    (set ids)
        orphans (distinct (for [x       ids
                                [h p _] (theo/triples g ['_ '_ x])
                                :when   (and (contains? links p)
                                             (not (contains? gone h))
                                             (every? gone (held h p)))]
                            h))
        parts   (removal-parts g gone ids rules)]
    (when (seq (:entry parts))
      (assoc parts
        :orphans (into {}
                       (for [h orphans]
                         [h (removal-parts g (conj gone h) [h] rules)]))))))

(defn references
  "How many triples of other entries point to each of the entries `ids` in
  the graph `g`, by the `rules` (see removal), when all of `ids` go: a map
  of an entry to a map of a property to a number. An entry that nothing
  points to is left out."
  [g ids rules]
  (let [gone (set ids)]
    (into {}
          (for [id    ids
                :let  [counts (:references (removal-parts g gone [id] rules))]
                :when (seq counts)]
            [id counts]))))

(defn referring
  "The triples of the graph `g` that refer to the resources `ids`, a set:
  every triple that points to one of them. A blank node that only points to
  them by that property is of no use without them, so every triple that
  names it is among them too."
  [g ids]
  (let [incoming (mapcat #(theo/triples g ['_ '_ %]) ids)
        whole?   (fn [[node q _]]
                   (and (term/blank-node? node)
                        (every? ids (map peek (theo/triples g [node q '_])))))
        nodes    (distinct (map first (filter whole? incoming)))]
    (into #{}
          (map vec)
          (concat incoming
                  (mapcat (fn [node]
                            (concat (theo/triples g [node '_ '_])
                                    (theo/triples g ['_ '_ node])))
                          nodes)))))

(defn- where-query
  "A SELECT query of the [s p o] `patterns`, built from Jena nodes with
  `prefixes` for keywords, so that no value has to be escaped as SPARQL."
  ^Query [patterns prefixes]
  (let [block (ElementTriplesBlock.)]
    (doseq [pattern patterns]
      (let [[s p o] (map #(convert/node % prefixes) pattern)]
        (.addTriple block (Triple/create s p o))))
    (doto (Query.)
      (.setQuerySelectType)
      (.setQueryResultStar true)
      (.setQueryPattern block))))

(defn- fresh-nodes
  "A map of each blank node label that the `change` makes to a label that
  no other node has."
  [change]
  (into {}
        (map (fn [label] [label (symbol (str "_" (random-uuid)))]))
        (change/new-nodes change)))

(defn- joined
  "The `patterns`, which each have a ?variable, in groups that share none
  with each other."
  [patterns]
  (let [variables #(filter change/variable? %)
        root      (fn [parents v]
                    (let [parent (get parents v v)]
                      (if (= parent v) v (recur parents parent))))
        parents   (reduce (fn [parents pattern]
                            (let [[v & vs] (variables pattern)]
                              (reduce #(assoc %1 (root %1 %2) (root %1 v))
                                      parents
                                      vs)))
                          {}
                          patterns)]
    (vals (group-by #(root parents (first (variables %))) patterns))))

(defn resolve-change
  "The `change` as a plain change in `db`: the patterns of its :where and
  :retract together have to match exactly once, and its ?variables take the
  values of that match. Throws when the change has problems (see
  change/problems) or doesn't match exactly once.

  A blank node that it makes gets a label of its own, and it retracts the
  triples that refer to what it unreferences (see referring). The patterns
  are looked up in parts that share no ?variable, since Jena nests an
  iterator for each pattern of a query, and the thousands of a removal
  overflow the stack."
  [{:keys [prefixes] :as db} {:keys [where retract unreference] :as change}]
  (when-some [problems (not-empty (change/problems change))]
    (throw (ex-info "Not a valid change"
                    {:change change :problems problems})))
  (let [plain?    (fn [pattern] (not-any? change/variable? pattern))
        patterns  (into (vec where) retract)
        parts     (concat (partition-all 200 (filter plain? patterns))
                          (joined (remove plain? patterns)))
        solutions (map #(theo/q db (where-query % prefixes)) parts)
        n         (reduce * (map count solutions))]
    (if (= 1 n)
      (let [others (some->> (seq unreference) (set) (referring db))]
        (cond-> (change/bind-variables change (apply merge
                                                     (fresh-nodes change)
                                                     (map first solutions)))
          (seq others) (update :retract (fnil into #{}) others)))
      (throw (ex-info (str "The patterns of a change match " n " times, "
                           "not exactly once")
                      {:change change :matches n})))))

(defn apply-change!
  "Make the `change` in `db` in one write transaction, resolved as
  resolve-change does it, and return the plain change that was made."
  [db change]
  (txn/with-write-transaction db
    (let [{:keys [retract add] :as plain} (resolve-change db change)]
      (theo/retract! db (vec retract))
      (theo/transact! db (vec add))
      plain)))
