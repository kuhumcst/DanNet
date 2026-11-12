(ns dk.cst.dannet.web.ui.edit.row
  "A row in edit mode as data: which fields it has and in which order, which
  property a change is made to, and which language a new text gets. The
  components that draw the fields are in ui.edit."
  (:require [dk.cst.dannet.change :as change]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.web.i18n :as i18n]))

(defn value-order
  "The values `vs` of a property in the order of its fields, with the
  pending `changes` made (see change/property-changes): a replacement takes
  the place of its value, and the other added values come last."
  [vs {:keys [added replaced] :as changes}]
  (let [replacing (fn [v] (filter added (get replaced v)))]
    ;; distinct, as a value can be replaced by one that is already there
    (distinct (concat (mapcat #(or (seq (replacing %)) [%]) vs)
                      (sort-by str added)))))

;; The row of members lists the values of the rdf:_N properties of a
;; container (see shared/member-row), and a change is made to the property
;; that holds a value. So these functions take pairs of a property and a value.

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
  `changes` to the `properties` of the subject `s` made. The `entries` are
  the pairs of a property and a value that can be edited.

  A changed value has its :status (:added, :retracted or :edited), and an
  edited one the :original value that it replaces. The fields are in the
  order of `properties` and then of value-order."
  [changes s properties entries]
  (let [p->vs (update-vals (group-by first entries) #(map second %))]
    (for [p    properties
          :let [{:keys [added retracted replaced]
                 :as   pending} (change/property-changes changes s p)
                original (into {}
                               (for [[old replacing] replaced
                                     v               replacing]
                                 [v old]))]
          v    (value-order (get p->vs p) pending)
          :let [status (cond
                         (retracted v) :retracted
                         (contains? original v) :edited
                         (added v) :added)]]
      (cond-> {:property p :value v}
        status (assoc :status status)
        (= status :edited) (assoc :original (get original v))))))

(defn lang-rank
  "A function that ranks a language by its place in `languages`, the
  reader's order of preference. A language that isn't among them comes last."
  [languages]
  (let [rank (zipmap languages (range))]
    #(get rank % (count rank))))

(defn missing-lang
  "The first of the languages `langs` that none of the texts `vs` is in, if
  any. A nil among `langs` stands for no language and is never missing."
  [langs vs]
  (let [present (into #{} (map i18n/lang) vs)]
    (first (remove #(or (nil? %) (contains? present %)) langs))))
