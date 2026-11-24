(ns dk.cst.dannet.web.ui.edit.entry
  "The new entries of edit mode as data: the templates that make them, and
  what the pending changes say about one. A new entry goes by a temporary ID
  until a curator accepts it (see dk.cst.dannet.change).

  A template is a change with gaps. The rules of the editor have one for
  each class of new entry (see dk.cst.dannet.editable). It is a map:

  - :add, the triples of a new entry, with a ?symbol for each gap;
  - :new, the gaps that are new entries, each with the kind that its
    temporary ID names;
  - :ask, the gaps that are entries of DanNet, each with its class. The
    page of an entry fills its own gap, and the editor asks for the others;
  - :from, the gaps that start as a value of another gap, each with that
    gap, the property of the value and optionally a pattern, whose first
    group is kept;
  - :once, when two gaps share at most one new entry: the :link from one
    to the other in the data, and the :reason, in each language, why a
    second entry is refused;
  - :note, what the template adds besides a value of the row, in each
    language, which the editor says before anything is chosen."
  (:require [dk.cst.dannet.change :as change]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.theophrastus.term :as term]))

(defn for-row
  "The template in `templates` that makes the new values of the property
  `p`, as a map of the :template and the :subject, which is the gap that
  the entry of the row fills. Nil when the values of the row aren't new
  entries.

  A row takes new entries when a template has a triple of its property that
  points to a new entry. So the template of a sense covers the senses of a
  synset and the senses of a word."
  [templates p]
  (first (for [{:keys [new add] :as template} (vals templates)
               [s p' o] add
               :when (and (= p p') (contains? new o))]
           {:template template :subject s})))

(defn asked
  "The gaps of the `template` that the editor asks for when the gaps in
  `given` have values already, as a map of a gap to its class."
  [{:keys [ask] :as template} given]
  (apply dissoc ask given))

(defn- kept
  "The first group of the `pattern` in the text `v`, in the language of
  `v`, or nil when the pattern doesn't match. Without a pattern, `v`."
  [v pattern]
  (if pattern
    (when-let [[_ s] (re-find pattern (str v))]
      (if-let [lang (term/lang v)]
        (term/->LangString s lang)
        s))
    v))

(defn starting
  "The value that each :from gap of the `template` starts as, as a map of a
  gap to its value. The `entities` are a map of a gap to what the editor
  knows of its value: the entity of a page, or the result of a search."
  [{:keys [from] :as template} entities]
  (into {}
        (keep (fn [[gap [source p pattern]]]
                (when-some [v (first (shared/setify (get (get entities source)
                                                         p)))]
                  (when-some [v' (kept v pattern)]
                    [gap v']))))
        from))

(defn fill
  "The change that makes the new entries of the `template`, where `values`
  is a map of each other gap to its value. A new entry gets a temporary ID
  that the pending `changes` don't have yet. A triple with a gap that has
  no value is left out."
  [{:keys [new add] :as template} changes values]
  (let [ids     (update-vals new #(change/temporary-id changes %))
        values' (merge values ids)]
    {:add (into #{}
                (keep (fn [triple]
                        (let [triple' (mapv #(if (change/variable? %)
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
  [{:keys [new add] :as template} changes given value other]
  (let [added (:add (change/net changes))
        link  (fn [gap]
                (some (fn [[s p o]]
                        (when (and (= s gap) (contains? new o))
                          p))
                      add))
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
  map of its temporary ID to the label."
  [changes]
  (into {}
        (keep (fn [[s p o]]
                (when (and (= p :rdfs/label) (change/temporary? s))
                  [s o])))
        (:add (change/net changes))))

(defn entity
  "The new entry with the `id` as the pending `changes` leave it: its own
  values, and the resources that point to it, under the inverse of the
  property that they do it with, as `inverses` has it. Nil when the changes
  say nothing about the entry itself.

  A sense then shows its word and its synset, as the page of a sense in the
  data does."
  [inverses changes id]
  (let [{:keys [add]} (change/net changes)
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
                           [p (if (next vs) vs (first vs))])))))))

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
