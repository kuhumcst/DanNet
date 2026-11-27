(ns dk.cst.dannet.web.ui.edit.entry
  "The new entries of edit mode as data: the templates that make them, and
  what the pending changes say about one. A new entry goes by a temporary ID
  until a curator accepts it (see dk.cst.dannet.change). An entry of the
  data that a template describes is removed as a whole (see with-removal).

  A template is a change with gaps. The rules of the editor have one for
  each class of new entry (see dk.cst.dannet.editable). It is a map:

  - :add, the triples of a new entry, with a ?symbol for each gap;
  - :new, the gaps that are new entries, each with the kind that its
    temporary ID names;
  - :ask, the gaps that the editor asks for. A gap with classes is an
    entry of DanNet of one of them: the page of an entry fills its own gap,
    and the other is found, or made from the template of a class that the
    typed text fits. A gap with a map is a text, in its :lang and of its
    :pattern;
  - :from, the gaps that start as a value of another gap, each with that
    gap and the steps that lead from its value: a property to read, a
    pattern whose first group is kept, or a text with %s for the value;
  - :name, what a new entry of the class is called where the editor offers
    to make one, in each language;
  - :once, when two gaps share at most one new entry: the :link from one
    to the other in the data, and the :reason, in each language, why a
    second entry is refused;
  - :note, what the template adds besides a value of the row, in each
    language, which the editor says before anything is chosen."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [dk.cst.dannet.change :as change]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.web.i18n :as i18n]
            [dk.cst.theophrastus.term :as term]))

(defn described?
  "Is the `entity` of a class that has a template in `templates`?"
  [templates entity]
  (boolean (some #(contains? templates %)
                 (shared/setify (:rdf/type entity)))))

(defn link-triples
  "The triples of the `template` that point to one of its new entries, e.g.
  the one that gives a word its new sense."
  [{:keys [new add] :as template}]
  (filter (fn [[_ _ o]] (contains? new o)) add))

(defn additions
  "The triples that the pending `changes` add in all."
  [changes]
  (:add (change/net changes)))

(defn for-row
  "The template in `templates` that makes the new values of the property
  `p`, as a map of the :template and the :given gap, which is the one that
  the entry of the row fills. Nil when the values of the row aren't new
  entries.

  A row takes new entries when a template has a triple of its property that
  points to a new entry. So the template of a sense covers the senses of a
  synset and the senses of a word."
  [templates p]
  (first (for [template (vals templates)
               [s p']   (link-triples template)
               :when    (= p p')]
           {:template template :given s})))

(defn asked
  "The gaps of the `template` that the editor asks for when the gaps in
  `filled` have values already, as a map of a gap to its classes."
  [{:keys [ask] :as template} filled]
  (apply dissoc ask filled))

(defn- step
  "The value `v` after a step `x` of a :from, or nil when it gives none: a
  property of an entity, the first group of a pattern in a text, or a text
  with the value in place of its %s. A text stays in its language."
  [v x]
  (cond
    (keyword? x) (first (shared/setify (get v x)))
    (string? x) (i18n/with-lang (str/replace x "%s" (str v)) (i18n/lang v))
    :else (when-let [[_ s] (re-find x (str v))]
            (i18n/with-lang s (i18n/lang v)))))

(defn starting
  "The value that each :from gap of the `template` starts as, as a map of a
  gap to its value. The `entities` are a map of a gap to what the editor
  knows of its value: the entity of a page, the result of a search, or a
  text."
  [{:keys [from] :as template} entities]
  (into {}
        (keep (fn [[gap [source & steps]]]
                (when-some [v (reduce #(or (step %1 %2) (reduced nil))
                                      (get entities source)
                                      steps)]
                  [gap v])))
        from))

(defn fill
  "The change that makes the new entries of the `template`, where `values`
  is a map of each other gap to its value, and of each blank node of the
  template to a label that the session doesn't have. A new entry gets a
  temporary ID that the pending `changes` don't have yet. A triple with a
  gap that has no value is left out."
  [{:keys [new add] :as template} changes values]
  (let [ids     (update-vals new #(change/temporary-id changes %))
        values' (merge values ids)]
    {:add (into #{}
                (keep (fn [triple]
                        (let [triple' (mapv #(if (or (change/variable? %)
                                                     (contains? values' %))
                                               (get values' %)
                                               %)
                                            triple)]
                          (when (every? some? triple')
                            triple'))))
                add)}))

(defn linked
  "The values that the pending `changes` give the gap `other` of the
  `template`, in the new entries where the gap `given` is `value`, as a
  set."
  [template changes given value other]
  (let [added (additions changes)
        link  (fn [gap]
                (some (fn [[s p]] (when (= s gap) p))
                      (link-triples template)))
        made  (into #{}
                    (keep (fn [[s p o]]
                            (when (and (= s value)
                                       (= p (link given))
                                       (change/temporary? o))
                              o)))
                    added)]
    (into #{}
          (keep (fn [[s p o]]
                  (when (and (= p (link other)) (contains? made o))
                    s)))
          added)))

(defn known
  "The values that the data has for the gap `other` of the `template`, as
  a set, on the page of the `entity` that fills the gap `given`: the other
  ends of the :link of its :once. A link that points to the entity is read
  under the inverse of its property, as `inverses` has it."
  [{:keys [once] :as template} inverses entity given other]
  (let [[s p o] (:link once)]
    (set (shared/setify
           (cond
             (= [s o] [given other]) (get entity p)
             (= [s o] [other given]) (get entity (get inverses p)))))))

(defn properties
  "The properties that the `templates` name, as a set."
  [templates]
  (into #{}
        (mapcat (fn [{:keys [add once]}]
                  (map second (cond-> (seq add)
                                (:link once) (conj (:link once))))))
        (vals templates)))

(defn labels
  "The label of each new entry that the pending `changes` give one, as a
  map of its temporary ID to the label.

  With `derived`, the :derived-labels of the rules of the editor, a new
  entry of one of their classes has the label that the build will write,
  e.g. a new synset the labels of its senses."
  ([changes]
   (labels changes nil))
  ([changes derived]
   (let [added (additions changes)
         own   (into {}
                     (keep (fn [[s p o]]
                             (when (and (= p :rdfs/label) (change/temporary? s))
                               [s o])))
                     added)]
     (into own
           (keep (fn [[s p o]]
                   (when-let [[link label-fn] (and (= p :rdf/type)
                                                   (change/temporary? s)
                                                   (get derived o))]
                     [s (label-fn (keep (fn [[s' p' o']]
                                          (when (and (= s' s) (= p' link))
                                            (get own o')))
                                        added))])))
           added))))

(defn of-class
  "The new entries of the `class` that the pending `changes` make, as
  their temporary IDs in order."
  [changes class]
  (sort (keep (fn [[s p o]]
                (when (and (= p :rdf/type) (= o class) (change/temporary? s))
                  s))
              (additions changes))))

(defn held-by
  "The entries that hold the new entry with the `id` in the pending
  `changes`, by the template of its class in `templates`, besides the
  entry `s` whose row it is shown in. A new sense in the row of a word is
  held by its synset too.

  An entry that the label of the new entry starts as is left out, since the
  label names it already, e.g. the word of a new sense."
  [templates changes id s]
  (let [added (additions changes)
        class (some (fn [[s' p o]]
                      (when (and (= s' id) (= p :rdf/type))
                        o))
                    added)
        {:keys [from] :as template} (get templates class)
        named (into #{} (map first) (vals from))]
    (sort (for [[gap p]   (link-triples template)
                :when     (not (named gap))
                [x p' o'] added
                :when     (and (= p p') (= o' id) (not= x s))]
            x))))

(defn entity
  "The new entry with the `id` as the pending `changes` leave it, as a map
  of its own values. Nil when the changes say nothing about the entry itself.

  With `inverses`, it also has the resources that point to it, under the
  inverse of the property that they do it with. A sense then shows its word
  and its synset, as the page of a sense in the data does."
  ([changes id]
   (entity nil changes id))
  ([inverses changes id]
   (let [add (additions changes)
         own (reduce (fn [entity [s p o]]
                       (if (= s id)
                         (update entity p (fnil conj #{}) o)
                         entity))
                     {}
                     add)]
     (when (seq own)
       (->> (reduce (fn [entity [s p o]]
                      (if-let [inverse (and (= o id) (get inverses p))]
                        (update entity inverse (fnil conj #{}) s)
                        entity))
                    own
                    add)
            ;; a single value stands alone, as in the entity of a page
            (into {} (map (fn [[p vs]]
                            [p (if (next vs) vs (first vs))]))))))))

(defn new-entry
  "A new entry made from the `template` of its class, to be the value of a
  gap of another template: a map of its temporary :id and the :change that
  makes it. The `text` is the one that the template asks for, when it asks
  for one, and `node-labels` are labels for its blank nodes that the session
  doesn't have. Nil when the text doesn't fit the template.

  An entry that is made from a text also has the trimmed :text."
  [{:keys [new add ask] :as template} changes text node-labels]
  (let [[gap {:keys [lang pattern]}] (first (filter (comp map? val) ask))
        value  (when gap
                 (some-> text str/trim not-empty (i18n/with-lang lang)))
        blanks (distinct (filter term/blank-node? (apply concat add)))]
    (when (or (nil? gap)
              (and value (or (nil? pattern) (re-find pattern (str value)))))
      (cond-> {:id     (change/temporary-id changes (val (first new)))
               :change (fill template changes
                             (merge (when gap {gap value})
                                    (starting template {gap value})
                                    (zipmap blanks node-labels)))}
        value (assoc :text (str value))))))

(defn fill-around
  "The one change that makes the new entry `made` (see new-entry) and fills
  the `template` with it as the value of its `gap`. The `values` and the
  `entities` are those of the other gaps, as fill and starting take them."
  [template changes gap {:keys [id change] :as made} values entities]
  (let [entities' (assoc entities gap (entity [change] id))
        around    (fill template changes
                        (merge (starting template entities')
                               values
                               {gap id}))]
    {:add (into (:add change) (:add around))}))

(defn links
  "The properties that the `templates` link a new entry with, as a set:
  those of their triples that point to one, e.g. the property that gives a
  word its sense. An entry can't stay without what holds it by such a link."
  [templates]
  (into #{} (comp (mapcat link-triples) (map second)) (vals templates)))

(defn without
  "The pending `changes` without the new entry with the `id`: the edits to
  it (see change/without-entry), and then what can't stay without it, by
  the `templates`.

  That is its blank nodes and the new entries that a template links it to,
  e.g. the new senses of a new word. A new entry that only held the entry
  goes too, e.g. a new synset when its last sense is taken away. A new
  entry that still holds another one stays, with every value that it has."
  [templates changes id]
  (let [link? (links templates)]
    (loop [changes  (vec changes)
           [x & xs] [id]]
      (if (nil? x)
        changes
        (let [added   (additions changes)
              kept    (change/without-entry changes x)
              holds?  (fn [id]
                        (some (fn [[s p _]] (and (= s id) (link? p)))
                              (additions kept)))
              needing (for [[s p o] added
                            :when   (and (= s x)
                                         (or (term/blank-node? o)
                                             (and (link? p)
                                                  (change/temporary? o))))]
                        o)
              holders (for [[s p o] added
                            :when   (and (= o x)
                                         (link? p)
                                         (change/temporary? s))]
                        s)]
          (recur kept (distinct (concat xs needing
                                        (remove holds? holders)))))))))

(defn with-removal
  "The pending `changes` with the change `removal` made, which takes
  entries of the data out as a whole (see db.change/removal-parts).

  The edits made before to what it removes are dropped, with what can't
  stay without it (see without), but a link to it that was removed before
  stays removed. An earlier removal stays whole, so that each can be undone
  on its own, unless the new one covers it."
  [templates changes removal]
  (let [whole?   (comp seq change/removed)
        covered? #(set/subset? (:retract %) (:retract removal))
        nodes    (filter term/blank-node? (map peek (:retract removal)))
        edits    (reduce #(without templates %1 %2)
                         (remove whole? changes)
                         (change/removed removal))]
    (-> (reduce change/without-node edits nodes)
        (into (remove covered?) (filter whole? changes))
        (conj removal))))

(defn removes?
  "Does the `change` remove the entry `id` of the data as a whole?"
  [change id]
  (contains? (change/removed change) id))

(defn removed?
  "Do the pending `changes` remove the entry `id` of the data as a whole
  (see with-removal)?"
  [changes id]
  (boolean (some #(removes? % id) changes)))

(defn without-removal
  "The pending `changes` without the ones that remove the entry `id` of the
  data as a whole (see with-removal), which puts back what they removed."
  [changes id]
  (into [] (remove #(removes? % id)) changes))

(defn page-data
  "The data of the page of the new entry with the `id`, as the page of an
  entry in the data has it, when the server has given the :constraints,
  the :entities and the :inverses of its type as `content`. The page is a
  :draft?, so that its entity is made from the pending changes each time it
  is drawn."
  [id {:keys [inverses] :as content}]
  (assoc content
    :subject id
    :draft? true
    ;; the graph that changes go to holds what the entry will have
    :default-origin prefix/dn-graph
    ;; a link to the entry isn't a value of the entry itself
    :origins (zipmap (vals inverses) (repeat :inferred))))
