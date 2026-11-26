(ns dk.cst.dannet.web.ui.edit.random-test
  "Random mixes of the edits that make new entries, take them away and
  remove entries of the data, with what has to hold after each edit.

  A walk makes its edits with the functions that the editor calls: new
  senses, synsets and words that are linked to each other and to entries in
  the data, new blank nodes, values, and the removal of any of these or of
  an entry in the data. A seed gives the same walk each time, so a failure
  names its seed and the edits that led to it."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is]]
            [dk.cst.theophrastus.term :as term]
            [dk.cst.dannet.change :as change]
            [dk.cst.dannet.db.change :as db.change]
            [dk.cst.dannet.editable :as editable]
            [dk.cst.dannet.web.i18n :as i18n]
            [dk.cst.dannet.web.ui.changes :as changes]
            [dk.cst.dannet.web.ui.edit :as edit]
            [dk.cst.dannet.web.ui.edit.entry :as entry])
  (:import [java.io StringReader]
           [java.util Random]))

(def templates
  (:entries editable/rules))

(def sense
  (:ontolex/LexicalSense templates))

(def words
  [:dn/word-1 :dn/word-2 :dn/word-3])

(def synsets
  [:dn/synset-1 :dn/synset-2 :dn/synset-3])

(def senses
  "The senses of the data, each with its word and its synset."
  {:dn/sense-1 [:dn/word-1 :dn/synset-1]
   :dn/sense-2 [:dn/word-1 :dn/synset-2]
   :dn/sense-3 [:dn/word-2 :dn/synset-2]
   :dn/sense-4 [:dn/word-3 :dn/synset-3]})

(defn- da
  "The text `s` in Danish."
  [s]
  (i18n/with-lang s "da"))

(def relation
  "The relation between two synsets of the data."
  [:dn/synset-2 :wn/hypernym :dn/synset-1])

(def data
  "The triples of the data that a walk can touch: the type, the label and
  three values of each entry, the links of each sense, and a relation
  between two synsets."
  (set (concat
         (for [[type ids] {:ontolex/Word           words
                           :ontolex/LexicalConcept synsets
                           :ontolex/LexicalSense   (keys senses)}
               id         ids
               t          (into [[id :rdf/type type]
                                 [id :rdfs/label (da (name id))]]
                                (for [n (range 3)]
                                  [id :wn/lexfile (str "old" n)]))]
           t)
         (for [[id [w x]] senses
               t          [[w :ontolex/sense id]
                           [x :ontolex/lexicalizedSense id]]]
           t)
         [relation])))

(defn- removal
  "The change that removes the entry `id` of the data with the senses that
  it holds, as the editor makes it of what the server has (see
  db.change/removal): it names their triples and the links to them, and
  unreferences them."
  [id]
  (let [link? (entry/links templates)
        ids   (into #{id}
                    (keep (fn [[s p o]] (when (and (= s id) (link? p)) o)))
                    data)]
    {:retract     (into #{}
                        (filter (fn [[s p o]]
                                  (or (ids s) (and (ids o) (link? p)))))
                        data)
     :unreference ids}))

(defn- removed
  "The entries of the data that the `changes` remove, as a set."
  [changes]
  (into (sorted-set) (change/removed (change/net changes))))

(defn- pick
  "One of `coll` by the next number of `rng`, or nil when it is empty."
  [^Random rng coll]
  (when (seq coll)
    (nth (vec coll) (.nextInt rng (count coll)))))

(defn- made
  "The new entries that the `changes` make, as a map of an ID to its type."
  [changes]
  (into (sorted-map)
        (keep (fn [[s p o]]
                (when (and (= p :rdf/type) (change/temporary? s))
                  [s o])))
        (:add (change/net changes))))

(defn- entries
  "The entries of the `type` that an edit can be made to: the ones `known`
  in the data that aren't removed, and the new ones that the `changes`
  make."
  [changes type known]
  (concat (remove (removed changes) known)
          (keep (fn [[id t]] (when (= t type) id)) (made changes))))

(defn- links
  "The words and the synsets that point to each new sense in the `changes`,
  as a map of a sense to a pair of sets."
  [changes]
  (let [add     (:add (change/net changes))
        holders (fn [p sense]
                  (into #{}
                        (keep (fn [[s p' o]]
                                (when (and (= p p') (= o sense))
                                  s)))
                        add))]
    (into {}
          (for [[id type] (made changes)
                :when     (= type :ontolex/LexicalSense)]
            [id [(holders :ontolex/sense id)
                 (holders :ontolex/lexicalizedSense id)]]))))

(defn- new-nodes
  "The labels of the new blank nodes that the `changes` name, by `nodes`,
  the places of the blank nodes of the session."
  [changes nodes]
  (->> (:add (change/net changes))
       (mapcat (juxt first peek))
       (filter #(:new? (get nodes %)))
       (distinct)
       (sort)))

(defn- with-change
  "The `state` with the `change` made, and with the place of each blank
  node that it makes, as the editor keeps them."
  [state change]
  (reduce (fn [state [s p o]]
            (cond-> state
              (term/blank-node? o)
              (assoc-in [:nodes o] {:subject s :property p :new? true})))
          (update state :changes conj change)
          (:add change)))

;; The rule of entry/without, written out for the three classes: a word or
;; a synset takes its senses with it, and it goes with its last sense.
(defn- gone
  "The new entries that go when the entries `ids` are taken out of the
  `changes`, with those entries, as a set."
  [changes ids]
  (let [links (links changes)
        made  (made changes)]
    (loop [gone (set ids)]
      (let [gone' (into gone
                        (for [[sense [ws xs]] links
                              :when (or (some gone ws) (some gone xs))]
                          sense))
            held  (into #{}
                        (mapcat (fn [[sense [ws xs]]]
                                  (when-not (gone' sense)
                                    (concat ws xs))))
                        links)
            gone' (into gone'
                        (for [[id type] made
                              :when (and (not= type :ontolex/LexicalSense)
                                         (not (held id)))]
                          id))]
        (if (= gone gone')
          gone
          (recur gone'))))))

(defn- left
  "The net of the `changes` without the entries in `gone`: the triples that
  name none of them and none of their new blank nodes. A retraction that
  points to one of them is left too, since it is an edit to another entry."
  [changes gone]
  (let [{:keys [add retract unreference]} (change/net changes)
        nodes  (loop [nodes #{}]
                 (let [nodes' (into nodes
                                    (keep (fn [[s _ o]]
                                            (when (and (term/blank-node? o)
                                                       (or (gone s) (nodes s)))
                                              o)))
                                    add)]
                   (if (= nodes nodes')
                     nodes
                     (recur nodes'))))
        named? (fn [[s _ o]] (some (some-fn gone nodes) [s o]))]
    (cond-> {:add     (into #{} (remove named?) add)
             :retract (into #{} (remove (comp (some-fn gone nodes) first))
                            retract)}
      unreference (assoc :unreference unreference))))

;; An edit takes the state and the random numbers, and gives a description
;; of what it did with the new state, or nil when it has nothing to act on.

(defn- new-sense
  "A new sense for a word in a synset that has none of its senses, new or
  in the data. A removed sense of the data counts, since it can be put back."
  [{:keys [changes] :as state} rng]
  (let [w (pick rng (entries changes :ontolex/Word words))
        x (pick rng (entries changes :ontolex/LexicalConcept synsets))]
    (when (and w x
               (not-any? (fn [[ws xs]] (and (ws w) (xs x)))
                         (vals (links changes)))
               (not-any? #{[w x]} (vals senses)))
      [[:new-sense w x]
       (with-change state (entry/fill sense changes
                                      {'?word   w
                                       '?synset x
                                       '?label  (da (name w))}))])))

(defn- new-synset
  "A new synset for a new sense of a word."
  [{:keys [changes] :as state} rng]
  (when-let [w (pick rng (entries changes :ontolex/Word words))]
    (let [label (da (str "\"" (name w) "\""))
          made  (entry/new-entry (:ontolex/LexicalConcept templates)
                                 changes nil nil)]
      [[:new-synset w]
       (with-change state (entry/fill-around sense changes '?synset made
                                             {'?word w}
                                             {'?word {:rdfs/label label}}))])))

(defn- new-word
  "A new word for a new sense in a synset, with its canonical form."
  [{:keys [changes nodes] :as state} rng]
  (when-let [x (pick rng (entries changes :ontolex/LexicalConcept synsets))]
    (let [made (entry/new-entry (:ontolex/Word templates) changes
                                (str "ord" (.nextInt rng 1000))
                                (edit/new-labels nodes))]
      [[:new-word x]
       (with-change state (entry/fill-around sense changes '?word made
                                             {'?synset x} {}))])))

(defn- new-value
  "A value of an entry, new or in the data, that isn't removed."
  [{:keys [changes] :as state} rng]
  (when-let [s (pick rng (entries changes nil
                                  (concat words synsets (sort (keys senses)))))]
    (let [t [s
             (pick rng [:skos/definition :rdfs/comment :lexinfo/senseExample])
             (str "v" (.nextInt rng 1000))]]
      [[:new-value t] (update state :changes change/add t)])))

(defn- new-label
  "Another label for a new entry."
  [{:keys [changes] :as state} rng]
  (when-let [[s _ o :as t] (->> (:add (change/net changes))
                                (filter (fn [[s p _]]
                                          (and (= p :rdfs/label)
                                               (change/temporary? s))))
                                (sort-by pr-str)
                                (pick rng))]
    (let [t' [s :rdfs/label (da (str o "x"))]]
      [[:new-label t'] (update state :changes change/replace t t')])))

(defn- remove-value
  "A pending value taken away with its cross. That is no type, and no new
  entry or blank node, since those have fields of their own."
  [{:keys [changes] :as state} rng]
  (when-let [t (->> (:add (change/net changes))
                    (remove (fn [[_ p o]]
                              (or (= p :rdf/type)
                                  (change/temporary? o)
                                  (term/blank-node? o))))
                    (sort-by pr-str)
                    (pick rng))]
    [[:remove-value t] (update state :changes change/without-addition t)]))

(defn- retraction
  "A value of an entry in the data removed, when the entry isn't."
  [{:keys [changes] :as state} rng]
  (when-let [s (pick rng (remove (removed changes) (concat words synsets)))]
    (let [t [s :wn/lexfile (str "old" (.nextInt rng 3))]]
      [[:retraction t] (update state :changes change/retract t)])))

(defn- unlink
  "The relation of a synset in the data removed, when neither of its
  synsets is. A removal that unreferences the other synset strikes the
  relation through, and then it has no cross."
  [{:keys [changes] :as state} rng]
  (when-not (or (some (removed changes) [(first relation) (peek relation)])
                (contains? (:retract (change/net changes)) relation))
    [[:unlink relation] (update state :changes change/retract relation)]))

(defn- undo
  "A removed value put back."
  [{:keys [changes] :as state} rng]
  (when-let [t (pick rng (sort-by pr-str (:retract (change/net changes))))]
    [[:undo t] (update state :changes change/without-triple t)]))

(defn- new-node
  "Another form of a word, as a new blank node with a value."
  [{:keys [changes nodes] :as state} rng]
  (when-let [w (pick rng (entries changes :ontolex/Word words))]
    (let [label (first (edit/new-labels nodes))
          form  (da (str "form" (.nextInt rng 100)))]
      [[:new-node w label]
       (with-change state {:add #{[w :ontolex/otherForm label]
                                  [label :ontolex/writtenRep form]}})])))

(defn- node-value
  "A value of a new blank node."
  [{:keys [changes nodes] :as state} rng]
  (when-let [label (pick rng (new-nodes changes nodes))]
    (let [t [label :ontolex/phoneticRep (str "p" (.nextInt rng 100))]]
      [[:node-value t] (update state :changes change/add t)])))

(defn- remove-node
  "A new blank node taken away, e.g. the canonical form of a new word."
  [{:keys [changes nodes] :as state} rng]
  (when-let [label (pick rng (new-nodes changes nodes))]
    [[:remove-node label] (update state :changes change/without-node label)]))

(defn- remove-entry
  "A new entry taken away. The state gets the changes that should be :left."
  [{:keys [changes] :as state} rng]
  (when-let [id (pick rng (keys (made changes)))]
    [[:remove-entry id]
     (assoc state
       :changes (entry/without templates changes id)
       :left (left changes (gone changes #{id})))]))

(defn- orphans
  "The entries of the data that hold nothing by a link once the `change`
  is made, e.g. the word of its last sense, as the server finds them (see
  db.change/removal)."
  [change]
  (let [gone  (change/removed change)
        link? (entry/links templates)]
    (sort (distinct (for [[h p o] data
                          :when   (and (link? p) (gone o) (not (gone h))
                                       (every? gone
                                               (for [[h' p' o'] data
                                                     :when (= [h p] [h' p'])]
                                                 o')))]
                      h)))))

(defn- retract-entry
  "An entry of the data removed as a whole, with the senses that it holds
  and with the edits made to any of them before. Half of the time, the
  entries that it leaves as orphans go in the same change. The state gets
  the changes that should be :left: the edits that name none of them, and
  every removal."
  [{:keys [changes] :as state} rng]
  (when-let [id (pick rng (remove (removed changes)
                                  (concat words synsets (sort (keys senses)))))]
    (let [alone  (removal id)
          change (if (zero? (.nextInt rng 2))
                   alone
                   (apply merge-with into alone
                          (map removal (orphans alone))))
          whole? (comp seq change/removed)
          wholes (conj (filter whole? changes) change)
          kept   (left (remove whole? changes)
                       (gone changes (change/removed change)))]
      [[:retract-entry id]
       (assoc state
         :changes (entry/retract templates changes change)
         :left (-> (update kept :retract into (mapcat :retract) wholes)
                   (assoc :unreference (change/unreferenced wholes))))])))

(defn- restore-entry
  "A removed entry of the data put back with the button of its page. The
  state says which entry is :restored, and what was pending before."
  [{:keys [changes] :as state} rng]
  (when-let [id (pick rng (removed changes))]
    [[:restore-entry id]
     (assoc state
       :changes (entry/restored changes id)
       :restored [id (change/net changes)])]))

(def edits
  "The edits of a walk. An edit that is here twice is made twice as often."
  [new-sense new-sense new-synset new-word new-word new-value new-value
   new-label remove-value retraction unlink undo new-node node-value
   remove-node remove-entry remove-entry retract-entry retract-entry
   restore-entry])

(defn- shown
  "The triples that the changes page shows for the `changes`, with the
  status of each: those of its rows, of the values that they replace, and
  of the rows in the field of a blank node."
  [changes nodes]
  (letfn [(triples [rows]
            (for [{:keys [subject property fields]} rows
                  {:keys [value status original] :as field} fields
                  triple (concat [[[subject property value] status]]
                                 (when (some? original)
                                   [[[subject property original] :retracted]])
                                 (triples (:rows field)))]
              triple))]
    (mapcat (comp triples :rows) (changes/entries changes nodes))))

(defn- problems
  "What is wrong with the `state` after an edit, as vectors of a keyword
  and what it is about. Empty when nothing is."
  [{:keys [changes nodes left restored] :as state}]
  (let [{:keys [add retract] :as net} (change/net changes)
        made    (made changes)
        links   (links changes)
        ids     (change/temporary-ids changes)
        labels  (new-nodes changes nodes)
        removed (removed changes)
        held    (into #{} (map peek) add)
        fields  (shown changes nodes)
        pending (into add retract)
        linked  #(entry/linked sense changes %1 %2 %3)
        derived (entry/labels changes (:derived-labels editable/rules))
        located (vec (change/locate-nodes changes nodes))
        names   (fn [{:keys [add retract]}]
                  (into #{} (mapcat (juxt first peek)) (concat add retract)))
        text    (changes/submission changes nodes)]
    (concat
      ;; every new entry is made, and every new blank node is held
      (for [id ids :when (not (contains? made id))]
        [:not-made id])
      (for [label labels :when (not (held label))]
        [:not-held label])
      ;; a sense has one word and one synset, and each of those has a sense
      (for [[id [ws xs]] links :when (not= [1 1] [(count ws) (count xs)])]
        [:sense-links id ws xs])
      (for [[id type] made
            :when (and (not= type :ontolex/LexicalSense)
                       (not-any? (fn [[ws xs]] (or (ws id) (xs id)))
                                 (vals links)))]
        [:no-sense id])
      ;; a removal takes what it should, and nothing else
      (when (and left (not= left net))
        [[:removal {:too-much   (set/difference (:add left) add)
                    :too-little (set/difference add (:add left))
                    :retract    [(set/difference (:retract left) retract)
                                 (set/difference retract (:retract left))]}]])
      ;; an entry of the data is removed as a whole or not at all, with the
      ;; senses that it holds and with a rule for what refers to it, and
      ;; nothing else of the data is retracted
      (for [id removed
            :when (not (set/subset? (:retract (removal id)) retract))]
        [:half-removed id])
      (when (not= removed (change/unreferenced changes))
        [[:other-rule (change/unreferenced changes)]])
      (when (not= removed (change/unreferenced located))
        [[:lost-rule (change/unreferenced located)]])
      (for [[s p o :as t] retract
            :when (not (or (= p :wn/lexfile) (= t relation)
                           (removed s) (removed o)))]
        [:stray-retraction t])
      ;; a removed entry is in one change, which retracts no triple twice,
      ;; and nothing is added to it
      (for [id    removed
            :let  [n (count (filter #(contains? (names %) id) located))]
            :when (not= 1 n)]
        [:not-one-change id n])
      (for [[t n] (frequencies (mapcat :retract located)) :when (< 1 n)]
        [:retracted-twice t])
      (for [id removed
            t  add
            :when (some #{id} [(first t) (peek t)])]
        [:added-to-removed t])
      ;; an entry that is put back is no longer removed, and that removes
      ;; nothing more and changes no addition
      (when-let [[id before] restored]
        (when (or (contains? removed id)
                  (not= add (:add before))
                  (not (set/subset? retract (:retract before))))
          [[:not-restored id]]))
      ;; a word has one sense in a synset, also when a removed one is put back
      (for [[id [ws xs]] links
            :when (some (fn [[w x]] (and (ws w) (xs x))) (vals senses))]
        [:second-sense id])
      ;; the dialog refuses a word that the synset has a new sense of
      (for [[id [ws xs]] links
            w ws
            x xs
            :when (not (and (contains? (linked '?word w '?synset) x)
                            (contains? (linked '?synset x '?word) w)))]
        [:not-refused id w x])
      ;; a new entry has a page, and a new synset the label of its senses
      (for [id (keys made) :when (nil? (entry/entity nil changes id))]
        [:no-page id])
      (for [[id type] made
            :when (and (= type :ontolex/LexicalConcept)
                       (not (string? (get derived id))))]
        [:no-label id])
      ;; the changes page shows every pending triple once
      (for [[t n] (frequencies (map first fields)) :when (not= 1 n)]
        [:shown-twice t])
      (for [t pending :when (not-any? #(= t (first %)) fields)]
        [:not-shown t])
      (for [[t status] fields :when (not (contains? pending t))]
        [:shown-in-error t status])
      (for [[[s] status] fields
            :when (and (change/temporary? s) (not= status :added))]
        [:not-added s status])
      ;; the changes can be submitted, with each new thing in one change
      (for [change located, problem (change/problems change)]
        [:problem problem])
      (for [x (concat ids labels)
            :let [n (count (filter #(contains? (names %) x) located))]
            :when (not= 1 n)]
        [:not-one-change x n])
      (when (not= net (change/net located))
        [[:other-triples]])
      (when (not= located (db.change/read-changes (StringReader. text)))
        [[:other-text]]))))

(defn- walk
  "The first of `n` random edits from the `seed` that leaves a problem, as
  a map of the :seed, the :edits up to it and the :problems. Nil when none
  of them does."
  [seed n]
  (let [rng (Random. seed)]
    (loop [state {:changes [] :nodes {}}
           done  []
           i     0]
      (when (< i n)
        (if-let [[edit state'] ((pick rng edits) state rng)]
          (if-let [found (seq (problems state'))]
            {:seed seed :edits (conj done edit) :problems (vec found)}
            (recur (dissoc state' :left :restored)
                   (conj done edit)
                   (inc i)))
          (recur state done (inc i)))))))

(deftest mixed-edits
  (is (empty? (keep #(walk % 40) (range 60)))
      "no mix of additions and removals leaves half an entry, takes too much
      away or gives changes that can't be submitted"))

(deftest faults
  (let [rng   (Random. 1)
        state (-> {:changes [] :nodes {}}
                  (new-word rng) (peek)
                  (new-synset rng) (peek)
                  (new-node rng) (peek))
        found (fn [change]
                (set (map first (problems (update state :changes conj
                                                  change)))))]
    (is (empty? (problems state)))
    (is (contains? (found {:add #{[:dn/new-sense-9 :rdfs/label "x"]}})
                   :not-made)
        "a value of an entry that nothing makes")
    (is (contains? (found {:add #{[:dn/new-sense-9 :rdf/type
                                   :ontolex/LexicalSense]
                                  [:dn/word-1 :ontolex/sense
                                   :dn/new-sense-9]}})
                   :sense-links)
        "a sense without a synset")
    (is (contains? (found {:add #{[:dn/new-word-9 :rdf/type :ontolex/Word]}})
                   :no-sense)
        "a word without a sense")
    (is (= #{:removal}
           (set (map first (problems (assoc state :left {:add     #{}
                                                         :retract #{}})))))
        "a removal that leaves more than it should")
    (is (contains? (found {:retract #{[:dn/sense-1 :rdf/type
                                       :ontolex/LexicalSense]}})
                   :half-removed)
        "a sense of the data that only loses its type")
    (is (contains? (found {:retract (set (filter #(= :dn/word-3 (first %))
                                                 data))})
                   :half-removed)
        "a word of the data that is removed without its sense")
    (is (contains? (found {:retract #{[:dn/word-1 :ontolex/sense :dn/sense-1]}})
                   :stray-retraction)
        "a link that is retracted while both of its ends stay")
    (is (contains? (found {:retract (:retract (removal :dn/sense-1))
                           :add     #{[:dn/sense-1 :rdfs/comment "x"]}})
                   :added-to-removed)
        "a value of a sense that is removed")))
