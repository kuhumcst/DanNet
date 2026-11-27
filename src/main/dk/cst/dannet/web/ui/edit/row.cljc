(ns dk.cst.dannet.web.ui.edit.row
  "A row in edit mode as data: which fields it has and in which order, which
  property a change is made to, which language a new text gets, and which
  rows can be added to a table. The components that draw the fields are in
  ui.edit."
  (:require [dk.cst.dannet.change :as change]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.web.i18n :as i18n]
            [dk.cst.dannet.web.ui.lock :as lock]))

(defn value-order
  "The values `vs` of a property in the order of its fields, with the
  `property-changes` made (see change/property-changes): a replacement takes
  the place of its value, and the other added values come last."
  [vs {:keys [added replaced] :as property-changes}]
  (let [replacing (fn [v] (filter added (get replaced v)))]
    ;; distinct, as a value can be replaced by one that is already there
    (distinct (concat (mapcat #(or (seq (replacing %)) [%]) vs)
                      (sort-by str added)))))

;; The row of members lists the values of the rdf:_N properties of a
;; container (see shared/member-row), and a change is made to the property
;; that holds a value.

(defn properties
  "The properties whose values the row of the property `k` in `opts` lists:
  `k` itself, and before it in the row of members, the rdf:_N properties of
  the entity and its pending changes by ordinal."
  [{:keys [subject entity changes] :as opts} k]
  (if (= k shared/member-row)
    (-> (->> (concat (keys entity) (change/changed-properties changes subject))
             (filter shared/member-property?)
             (distinct)
             (sort-by shared/member-ordinal))
        (concat [k]))
    [k]))

(defn new-value-property
  "The property that a new value in the row of `k` is added to, given the
  `properties` of the row: `k` itself, or in the row of members, the rdf:_N
  property after the highest one.

  A removed member keeps its ordinal, so that undoing the removal can't
  give two members the same one."
  [k properties]
  (if (= k shared/member-row)
    (->> (filter shared/member-property? properties)
         (map shared/member-ordinal)
         (reduce max 0)
         (inc)
         (str "_")
         (keyword "rdf"))
    k))

(defn fields
  "The fields of a row as maps of a :property and a :value, with the pending
  `changes` to the `properties` of the subject `s` made. The `pairs` are
  those of a property and a value that can be edited.

  A changed value has its :status: :added, :retracted, :edited with the
  :original value that it replaces, or :moved with the property that it is
  :moved-from (see change/moves). In the row that a moved value left, it is
  :retracted with the property that it is :moved-to. A resource that the
  changes unreference is :retracted too. The fields are in the order of
  `properties` and then of value-order."
  [changes s properties pairs]
  (let [p->vs (update-vals (group-by first pairs) #(map second %))
        moves (change/moves changes s)
        gone  (change/unreferenced changes)]
    (for [p    properties
          :let [{:keys [added retracted replaced]
                 :as   pending} (change/property-changes changes s p)
                original (into {}
                               (for [[old replacing] replaced
                                     v               replacing]
                                 [v old]))
                moved-from (into {}
                                 (for [{:keys [value from to]} moves
                                       :when (= to p)]
                                   [value from]))
                moved-to   (into {}
                                 (for [{:keys [value from to]} moves
                                       :when (= from p)]
                                   [value to]))]
          v    (value-order (get p->vs p) pending)
          :let [status (cond
                         (or (retracted v) (contains? gone v)) :retracted
                         (contains? original v) :edited
                         (contains? moved-from v) :moved
                         (added v) :added)]]
      (cond-> {:property p :value v}
        status (assoc :status status)
        (= status :edited) (assoc :original (get original v))
        (= status :moved) (assoc :moved-from (get moved-from v))
        (and (= status :retracted) (contains? moved-to v))
        (assoc :moved-to (get moved-to v))))))

(defn node-values
  "The values of the blank node with the `label` as its nested table shows
  them: its `values` in the data, a map of a property to its values, with
  the pending `changes` made. A removed value is left out, a replacement
  takes the place of its value, and the values of a new row come last."
  [changes label values]
  (let [changed    (for [{:keys [retract add]} changes
                         [s p]                 (concat retract add)
                         :when                 (= s label)]
                     p)
        properties (distinct (concat (keys values) changed))]
    (for [p     properties
          field (fields changes label [p] (map #(vector p %) (get values p)))
          :when (not= :retracted (:status field))]
      (:value field))))

(defn lang-rank
  "A function that ranks a language by its place in `languages`, the
  reader's order of preference. A language that isn't among them comes last."
  [languages]
  (let [rank (zipmap languages (range))]
    #(get rank % (count rank))))

(defn new-text-lang
  "The language of a new text in a row: that of the `neighbour`, the text
  before it, when the property can be in it, or else the first of the
  languages that the property is `expected` in."
  [neighbour expected]
  (let [lang (i18n/lang neighbour)]
    (if (or (empty? expected) (some #{lang} expected))
      lang
      (first expected))))

(defn missing-lang
  "The first of the languages `langs` that none of the texts `vs` is in, if
  any. A nil among `langs` stands for no language and is never missing."
  [langs vs]
  (let [present (into #{} (map i18n/lang) vs)]
    (first (remove #(or (nil? %) (contains? present %)) langs))))

(defn addable
  "The properties that a row can be added for in a table with `opts`: those
  that the shapes describe for its entity or its blank node, as the
  :constraints have them.

  A property that a build writes for the entity is left out. No row can be
  added outside edit mode, to a resource that can't be edited or to a blank
  node that is locked."
  [{:keys [constraints edit-mode default-origin blank-node] :as opts}]
  (when (and edit-mode
             (some? default-origin)
             (lock/editable-resource? opts)
             (nil? (:lock blank-node)))
    (cond->> (keys constraints)
      (nil? blank-node) (remove #(lock/derived? opts %)))))

(defn expected
  "Whether an entry :must or :should have a value of the property `k`,
  according to the `constraints` on its properties, or nil when it can do
  without one."
  [constraints k]
  (let [{:keys [must should]} (get constraints k)]
    (cond
      (some-> (:min must) pos?) :must
      (some-> (:min should) pos?) :should)))

(defn open-rows
  "Those of the `addable` properties whose rows are open for a new value:
  first the ones that the pending `changes` to the subject `s` are made to,
  then the ones that the editor has `added`, in the order that they were
  added in."
  [addable changes s added]
  (let [addable? (set addable)
        pending  (->> (change/changed-properties changes s)
                      (filter addable?)
                      (sort-by str))]
    (distinct (concat (remove (set added) pending)
                      (filter addable? added)))))

(defn- value-shape
  "What the constraints that a property `must` meet say of a value on its
  own, or nil when they say nothing of it."
  [must]
  (not-empty (select-keys must [:class :iri? :pattern :datatype :languages
                                :in])))

(defn sole-property
  "The property of a blank node that only holds one value, by the
  `constraints` on its properties: the one property that its shape
  describes, when a node must have exactly one value of it, e.g. the
  polarity of a sentiment. Such a node is edited as that value."
  [constraints]
  (when (= 1 (count constraints))
    (let [[k {:keys [must]}] (first constraints)]
      (when (= 1 (:min must) (:max must))
        k))))

(defn room?
  "Can the `entity` take `n` more values of the property `k`, next to the
  ones that it has? Only the :max that the `constraints` give the property
  can say no."
  [constraints entity k n]
  (let [v    (get entity k)
        held (cond
               (nil? v) 0
               (shared/multi-valued? v) (count v)
               :else 1)]
    (not (some-> (get-in constraints [k :must :max]) (< (+ n held))))))

(defn alternatives
  "Those of the properties `ks` that the values of the property `k` can move
  to, according to the `constraints` on the properties of the `entity`: the
  ones whose values have the same shape as the values of `k`, and that take
  `n` more values next to the ones that the entity has.

  Only a relation moves, i.e. a property whose values are resources, and
  not when its row must keep a value."
  [constraints ks entity k n]
  (let [must  #(get-in constraints [% :must])
        shape (value-shape (must k))]
    (when (and (or (:class shape) (:iri? shape))
               (not= :must (expected constraints k)))
      (filter #(and (not= % k)
                    (= shape (value-shape (must %)))
                    (room? constraints entity % n))
              ks))))
