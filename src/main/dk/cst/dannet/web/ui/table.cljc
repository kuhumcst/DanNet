(ns dk.cst.dannet.web.ui.table
  "Table rendering functions for RDF data."
  (:require [clojure.string :as str]
            [rum.core :as rum]
            [dk.cst.theophrastus.origin :as origin]
            [dk.cst.dannet.change :as change]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.web.app :as app]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.web.i18n :as i18n]
            #?(:clj  [dk.cst.dannet.web.ui.error :as error]
               :cljs [dk.cst.dannet.web.ui.error :as error :include-macros true])
            [dk.cst.dannet.web.ui.edit :as edit]
            [dk.cst.dannet.web.ui.rdf :as rdf]
            [dk.cst.dannet.web.ui.visualization :as viz]))

(def cloud-cutoff
  "Relations with more values than this can be shown as a word cloud."
  4)

(rum/defc value-cell
  "A table cell of an 'attr-val-table' containing the single value `v`, e.g. a
  literal or an inlined table (i.e. a blank RDF node)."
  [opts v]
  [:td (cond-> (rdf/value-attrs v)
         ;; fixes the alignment of a resource's prefix badge and link
         (and (keyword? v) (not-empty (name v))) (assoc :class "attr-combo"))
   (rdf/value opts v)])

(defn display-cloud?
  "Can the values `v` of the predicate in `opts` be shown as a word cloud?"
  [{:keys [attr-key] :as opts} v]
  (and (coll? v)
       (> (count v) cloud-cutoff)
       ;; A word cloud is only relevant in cases where the content has been
       ;; presorted by weight, e.g. synset relations currently in use in DanNet.
       (shared/weight-sorted-rel? attr-key)))

(defn row-origins
  "The origins of the values `v` of the property `k`, as the page data in
  `opts` has them, e.g. #{:inferred} for a row of inferred values."
  [{:keys [origins default-origin] :as opts} k v]
  (let [as-set #(if (set? %) % #{%})
        o      (get origins k)]
    (if (map? o)
      (into #{}
            (mapcat #(as-set (origin/origin opts k %)))
            (if (shared/multi-valued? v) v [v]))
      (as-set (or o default-origin)))))

(defn value-origins
  "The origins of the value `v` of the property `k` as a set, as the page
  data in `opts` has them."
  [opts k v]
  (let [o (origin/origin opts k v)]
    (if (set? o) o #{o})))

(defn editable?
  "Can the value `v` of the property `k` be edited? It can when the graph
  that changes go to, the :default-origin of `opts`, is one of its origins."
  [{:keys [default-origin] :as opts} k v]
  (and (some? default-origin)
       (contains? (value-origins opts k v) default-origin)))

(def derived-properties
  "The properties that the release build writes for every resource, so that
  their values aren't edited by hand."
  #{:dns/inherited :skos/inScheme})

(def derived-synset-properties
  "The properties that the release build writes for a synset: its labels,
  which are made from its senses."
  #{:rdfs/label :dns/shortLabel})

(defn derived?
  "Does the release build write the property `k` of the entity in `opts`?"
  [{:keys [entity] :as opts} k]
  (or (contains? derived-properties k)
      (and (contains? derived-synset-properties k)
           (contains? (shared/setify (:rdf/type entity))
                      :ontolex/LexicalConcept))))

(defn lock
  "Why the value `v` of the property `k` in `opts` can't be edited, as a key
  of the :locked comments, or nil when it can be. The property is :derived;
  the value is :inferred or :supplemented, or it is :elsewhere, in a graph
  that changes don't go to; or it is a blank node or a folded member of a
  container, which can't be edited :yet."
  [opts k v]
  (cond
    (derived? opts k)
    :derived

    (not (editable? opts k v))
    (let [origins (value-origins opts k v)]
      (cond
        (contains? origins :inferred)     :inferred
        (contains? origins :supplemented) :supplemented
        :else                             :elsewhere))

    (or (map? v) (symbol? v) (= k :rdfs/member))
    :yet))

(defn padlock
  "A padlock icon whose tooltip and accessible name is `why`, the reason that
  the row or value it marks can't be edited."
  [why]
  [:span.padlock {:role "img" :aria-label why :title why}])

(defn pending
  "The pending changes to the values of the property `k` of the subject in
  `opts`: the values :added and :retracted as sets, and the :replacements, a
  map of each retracted value to the values that its change adds."
  [{:keys [subject changes] :as opts} k]
  (let [own (fn [triples]
              (into #{}
                    (keep (fn [[s p o]]
                            (when (and (= s subject) (= p k))
                              o)))
                    triples))
        {:keys [add retract]} (change/net changes)]
    {:added        (own add)
     :retracted    (own retract)
     :replacements (reduce (fn [m {:keys [add retract]}]
                             (if-let [added (not-empty (own add))]
                               (reduce #(update %1 %2 (fnil into []) added)
                                       m
                                       (own retract))
                               m))
                           {}
                           changes)}))

(defn edit-order
  "The values `vs` of the property `k` in `opts` in the order of an edit row,
  with the values that the `pending` changes add. The values that can be
  edited come first, in their usual order, with a replaced value giving its
  place to the value that replaces it, and then the other added values."
  [opts k vs {:keys [added replacements] :as pending}]
  (let [sorted    (if (set? vs) (sort-by (rdf/label-sortkey-fn opts) vs) vs)
        editable  (filter #(nil? (lock opts k %)) sorted)
        locked    (remove #(nil? (lock opts k %)) sorted)
        replacing (fn [v] (filter added (get replacements v)))]
    ;; distinct, as a text can be replaced by one that is already a value
    (distinct (concat (mapcat #(or (seq (replacing %)) [%]) editable)
                      (sort-by str added)
                      locked))))

(defn- without-change
  "The pending `changes` without the one that adds or retracts the triple
  `t`."
  [changes t]
  (into []
        (remove #(or (contains? (:add %) t) (contains? (:retract %) t)))
        changes))

(defn- focus-button!
  "Move the focus to the first button of the list item that is where the
  item `li` is now, once the list has been drawn again."
  [li]
  ;; in the frame after the next one, since the list is drawn in the next
  #?(:cljs (when-let [ul (some-> li (.-parentNode))]
             (let [i (.indexOf (js/Array.from (.-children ul)) li)]
               (js/requestAnimationFrame
                 (fn []
                   (js/requestAnimationFrame
                     #(some-> (aget (.-children ul) i)
                              (.querySelector "button")
                              (.focus)))))))))

(rum/defcs edit-value < (rum/local false ::editing?)
  "A value `v` of the property `k` in `opts` that can be edited, framed like
  an input field, with its `status` among the pending changes: :added,
  :retracted, :edited when it replaces the value `original`, or nil.

  Its buttons remove it, or undo the change that removed, added or edited
  it. The pencil turns it into the input of its `value-kind`, and so does a
  click elsewhere in the field. A value entered there replaces it."
  [state {:keys [subject languages comments] :as opts}
   k v status original value-kind]
  (let [editing? (::editing? state)
        t        [subject k v]
        pencil?  (not= status :retracted)
        text     #(if (keyword? %) (rdf/link-label opts %) (str %))
        label    #(str (i18n/da-en languages %1 %2) ": " (text v))
        ;; the focus goes back to the pencil, of the new value when there's one
        close!   (fn []
                   (when @editing?
                     (reset! editing? false)
                     #?(:cljs (focus-button! (rum/dom-node state)))))
        ;; A click anywhere in the field opens its input, as the pencil does,
        ;; unless it is on a link or a button or selects text.
        open!    (fn [e]
                   #?(:cljs (when-not (or @editing?
                                          (.closest (.-target e)
                                                    "a, button, [tabindex]")
                                          (seq (str (js/getSelection))))
                              (reset! editing? true))))]
    [:li (cond-> {:class ["editable"]}
           pencil? (-> (assoc :on-click open!)
                       (update :class conj "clickable"))
           status (update :class conj (name status))
           original (assoc :title (str (i18n/da-en languages "Før" "Before")
                                       ": " (text original)))
           (next (value-origins opts k v)) (assoc :title (:shared comments)))
     (if @editing?
       (edit/field opts value-kind
                   {:value     v
                    :label     (label "Ret" "Edit")
                    :on-cancel close!
                    :on-value  (fn [v']
                                 (close!)
                                 (app/update-changes!
                                   change/replace t [subject k v']))})
       ;; a removed value is a <del>, an added or edited one an <ins>
       [(case status :retracted :del (:added :edited) :ins :span)
        (cond-> (assoc (rdf/value-attrs v) :class ["edit-value__content"])
          (keyword? v) (update :class conj "attr-combo"))
        (rdf/value opts v)])
     (when-not @editing?
       [:span.edit-value__actions
        (when pencil?
          (edit/icon-button "edit" (label "Ret" "Edit")
                            #(reset! editing? true)))
        (case status
          :retracted (edit/icon-button "undo" (label "Fortryd fjernelse af"
                                                     "Undo removal of")
                                       #(app/update-changes! without-change t))
          :added (edit/icon-button "remove" (label "Fjern" "Remove")
                                   #(app/update-changes! without-change t))
          ;; removing an edited value removes the value it replaces
          :edited [:<>
                   (edit/icon-button "undo" (label "Fortryd rettelse af"
                                                   "Undo edit of")
                                     #(app/update-changes! without-change t))
                   (edit/icon-button "remove" (label "Fjern" "Remove")
                                     #(app/update-changes!
                                        (fn [changes]
                                          (-> (without-change changes t)
                                              (change/retract
                                                [subject k original])))))]
          (edit/icon-button "remove" (label "Fjern" "Remove")
                            #(app/update-changes! change/retract t)))])]))

(rum/defcs add-value < (rum/local false ::adding?)
  "An empty field below the values `vs` of the property `k` in `opts`, whose
  button turns it into the input of the `value-kind`. A value entered there
  is added, unless the property has it already."
  [state {:keys [subject languages] :as opts} k vs value-kind]
  (let [adding? (::adding? state)
        label   (i18n/da-en languages "Tilføj en værdi" "Add a value")
        close!  (fn []
                  (when @adding?
                    (reset! adding? false)
                    #?(:cljs (focus-button! (rum/dom-node state)))))]
    [:li.editable.adding
     (if @adding?
       (edit/field opts (cond-> value-kind
                          (:options value-kind) (update :options
                                                        #(remove (set vs) %)))
                   {:like      (first vs)
                    :label     label
                    :on-cancel close!
                    :on-value  (fn [v]
                                 (close!)
                                 (when-not (some #{v} vs)
                                   (app/update-changes!
                                     change/add [subject k v])))})
       ;; the plus says it to the eye, the label to a screen reader
       [:button.edit-value__add {:type     "button"
                                 :title    label
                                 :on-click #(reset! adding? true)}
        [:span.edit-value__icon.edit-value__icon--add {:aria-hidden "true"}]
        [:span.visually-hidden label]])]))

(rum/defc edit-cell
  "A table cell that lists every one of the values `vs` of the property `k`
  in `opts`, for a row in edit mode, with the pending changes made: an
  edited value shows its new text. The values that can be edited are
  followed by a field for adding one. A value that can't be edited has a
  padlock that says why, and it comes last."
  [{:keys [comments] :as opts} k vs]
  (let [{:keys [added retracted replacements] :as pending} (pending opts k)
        original   (into {}
                         (for [[old replacing] replacements
                               v               replacing]
                           [v old]))
        value-kind (edit/value-kind k vs)
        reason     (fn [v] (when-not (added v) (lock opts k v)))
        item-key   (fn [v] (pr-str (if (map? v) (:rdf/about v) v)))
        ordered    (edit-order opts k vs pending)
        editable   (remove reason ordered)]
    [:td
     [:ul.edit-values
      (for [v editable]
        (rum/with-key (edit-value opts k v
                                  (cond
                                    (retracted v) :retracted
                                    (contains? original v) :edited
                                    (added v) :added)
                                  (get original v)
                                  value-kind)
                      (item-key v)))
      (rum/with-key (add-value opts k editable value-kind) "add")
      (for [v (filter reason ordered)]
        [:li (cond-> (assoc (rdf/value-attrs v)
                       :key (item-key v)
                       :class ["locked"])
               (keyword? v) (update :class conj "attr-combo"))
         (padlock (get-in comments [:locked (reason v)]))
         (rdf/value opts v)])]]))

(defn reaches-limit?
  "Do `n` values reach the limit, from which a relation shows the word cloud of
  its top values by default? A truncated relation arrives with exactly that
  many values, so it shows its cloud before the deferred values arrive."
  [n]
  (>= n shared/semantic-relation-limit))

(rum/defc list-cell
  "The values `coll` of the relation in `opts` as a word cloud or a list; a
  long list shows only its first items until expanded."
  [{:keys [display-opt] :as opts} coll]
  (let [display-opt' (or display-opt
                         ;; Display the limited, radial cloud by default for
                         ;; large colls and use tables for everything else.
                         (when (and (display-cloud? opts coll)
                                    (reaches-limit? (count coll)))
                           "cloud"))]
    (case display-opt'
      ;; viz/word-cloud renders an empty <div> that D3 draws into in the
      ;; browser, so the server renders the same <div> and hydration matches.
      "cloud" #?(:cljs (error/try-render
                         (viz/word-cloud coll (assoc opts :cloud-limit shared/semantic-relation-limit)))
                 :clj  [:div])
      "max-cloud" #?(:cljs (error/try-render (viz/word-cloud coll opts))
                     :clj  [:div])
      (rdf/list-items opts coll))))

(defn entailed-relations
  "A collapsed list of the relations `folded-ks` that the relation in `opts`
  entails, e.g. skos:broader for wn:hypernym.

  The backend folds them into the row of the relation to avoid duplication."
  [{:keys [attr-key comments] :as opts} folded-ks]
  ;; Relations whose label collides with the core relation or a sibling show
  ;; their QName instead, e.g. "dc11:date" rather than "date" next to dc:date.
  (let [label-of   (partial rdf/link-label opts)
        core-label (label-of attr-key)
        freqs      (frequencies (map label-of folded-ks))
        ambiguous? (fn [k']
                     (let [label (label-of k')]
                       (or (= label core-label)
                           (> (get freqs label) 1))))]
    [:details.entailed
     [:summary {:title (:entailment comments)}
      "⊑"]
     [:ul.entailed__rels
      (for [k' (sort-by str folded-ks)]
        [:li {:key (str k')}
         (rdf/entity-link k' (cond-> (assoc opts :attr-key k')
                               (ambiguous? k')
                               (assoc-in [:k->label k']
                                         (prefix/kw->qname k'))))])]]))

(defn display-options-select
  "A <select> for how to display the `v-count` values of the relation in `opts`,
  storing the choice in the `display-opts` atom."
  [{:keys [subject attr-key display-opt languages] :as opts}
   v-count display-opts]
  ;; The empty string is the list option; nil would make React warn
  ;; about a null `value` prop on the <select>.
  (let [exceeds? (reaches-limit? v-count)
        value    (or display-opt (when exceeds? "cloud") "")
        change   (fn [e]
                   (swap! display-opts assoc-in [subject attr-key]
                          (.-value (.-target e))))]
    [:select.display-options {:title     (i18n/da-en languages
                                             "Visningsmuligheder"
                                             "Display options")
                              :value     value
                              :on-change change}
     [:option {:value ""}
      (i18n/da-en languages "liste" "list")]
     (let [cloud (i18n/da-en languages "ordsky" "word cloud")]
       (if exceeds?
         [:<>
          [:option {:value "cloud"}
           (str cloud " (top)")]
          [:option {:value "max-cloud"}
           (str cloud " (" v-count ")")]]
         [:option {:value "max-cloud"}
          cloud]))]))

(rum/defc attr-name-extras
  "Extra widgets shown below the relation name in `attr-val-row`: relations
  entailed by the attribute key (folded into its row by the backend) and
  display options for longer lists of synsets."
  [{:keys [attr-key folded] :as opts} v display-opts]
  (let [folded-ks (not-empty (get folded attr-key))
        cloud?    (display-cloud? opts v)
        v-count   (if (coll? v) (count v) 0)]
    (when (or folded-ks cloud?)
      [:div.attr-name__extras
       (when folded-ks
         (entailed-relations opts folded-ks))
       (when cloud?
         (display-options-select opts v-count display-opts))])))

(rum/defc attr-val-row
  "A single row in the attribute-value table. In edit mode, a row with a
  value that can be edited lists all its values, and any other row is faded,
  with a padlock that says why. The values of a nested table have no
  origins, so its rows are as they always are."
  [{:keys [subject comments inherited edit-mode default-origin] :as opts}
   k v display-opts]
  (let [origins       (row-origins opts k v)
        inherited?    (get inherited k)
        inferred?     (contains? origins :inferred)
        supplemented? (contains? origins :supplemented)
        prefix        (if (keyword? k)
                        (some-> (namespace k) symbol)
                        k)
        display-opt   (get-in @display-opts [subject k])
        opts+attr-key (assoc opts
                        :attr-key k
                        :display-opt display-opt)
        vs            (if (shared/multi-valued? v) v [v])
        locks         (when (and edit-mode (some? default-origin))
                        (map #(lock opts k %) vs))
        edit-row?     (some nil? locks)
        locked        (when (and locks (not edit-row?))
                        (->> (distinct locks)
                             (map #(get-in comments [:locked %]))
                             (str/join "; ")))]
    [:tr (cond-> {:key (str k)}
           (keyword? k) (assoc :property (prefix/kw->qname k))
           inferred? (update :class conj "inferred")
           inherited? (update :class conj "inherited")
           supplemented? (update :class conj "supplemented")
           locked (update :class conj "locked"))
     [:td.attr-prefix
      (when inferred?
        [:span.marker {:title (:inference comments)} "∴"])
      (when inherited?
        [:span.marker {:title (:inheritance comments)} "†"])
      (when supplemented?
        [:span.marker {:title (:supplemented comments)} "↪"])
      (rdf/prefix-badge prefix)]
     [:td.attr-name
      (rdf/entity-link k opts+attr-key)
      (when locked
        (padlock locked))
      ;; an edit row lists its values, so it has no display options
      (attr-name-extras opts+attr-key (when-not edit-row? v) display-opts)]
     (cond
       edit-row?
       (edit-cell opts+attr-key k vs)

       (not (shared/multi-valued? v))
       (value-cell opts+attr-key v)

       (= 1 (count v))
       (value-cell opts+attr-key (first v))

       (every? i18n/rdf-string? v)
       [:td (rdf/transform-text opts+attr-key v)]

       :else
       [:td (or (rdf/transform-val-coll v opts+attr-key)
                (list-cell opts+attr-key v))])]))

(defn- fold-member-rows
  "Fold the rdf:_N container membership rows of `subentity` into a single
  :rdfs/member row in place of the first of them, with the members in
  ordinal order."
  [subentity]
  (let [rows     (seq subentity)
        ordinals (filter (comp shared/member-property? key) rows)]
    (if (seq ordinals)
      (let [member-row [:rdfs/member (->> ordinals
                                          (sort-by (comp parse-long #(subs % 1) name key))
                                          (mapv (comp shared/unwrap val)))]
            first-k    (key (first ordinals))]
        (keep (fn [[k _ :as row]]
                (cond
                  (= k first-k) member-row
                  (shared/member-property? k) nil
                  :else row))
              rows))
      subentity)))

(rum/defcs attr-val-table < (rum/local {} ::display-opts)
  "A table which lists attributes and corresponding values of an RDF resource."
  [state {:keys [subject languages] :as opts} subentity]
  (let [display-opts (::display-opts state)
        subentity    (fold-member-rows subentity)]
    ;; Only a table rendered inside a value has :table-component in its opts.
    [:table.attr-val (cond-> {}
                       (:table-component opts)
                       (assoc :class "attr-val--nested")

                       ;; Add RDFa subject when available.
                       (and subject (not (:table-component opts)))
                       (assoc :about (if (keyword? subject)
                                       (prefix/kw->uri subject)
                                       subject)))
     [:colgroup
      [:col {:aria-label (i18n/da-en languages "RDF-præfix" "RDF prefix")}]
      [:col {:aria-label (i18n/da-en languages "Navn (nøgle)" "Name (key)")}]
      [:col {:aria-label (i18n/da-en languages "Værdi" "Value")}]]
     [:tbody
      (for [[k v] subentity]
        (rum/with-key (attr-val-row (assoc opts :table-component attr-val-table)
                                    k v display-opts)
                      k))]]))
