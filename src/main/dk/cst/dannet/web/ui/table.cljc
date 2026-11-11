(ns dk.cst.dannet.web.ui.table
  "Table rendering functions for RDF data."
  (:require [clojure.string :as str]
            [rum.core :as rum]
            [dk.cst.theophrastus.origin :as origin]
            [dk.cst.theophrastus.term :as term]
            [dk.cst.dannet.change :as change]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.web.app :as app]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.web.i18n :as i18n]
            #?(:clj  [dk.cst.dannet.web.ui.error :as error]
               :cljs [dk.cst.dannet.web.ui.error :as error :include-macros true])
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

(defn lock
  "Why the value `v` of the property `k` in `opts` can't be edited, as a key
  of the :locked comments, or nil when it can be: the value is :inferred or
  :supplemented, or it is :elsewhere, in a graph that changes don't go to."
  [opts k v]
  (when-not (editable? opts k v)
    (let [origins (value-origins opts k v)]
      (cond
        (contains? origins :inferred)     :inferred
        (contains? origins :supplemented) :supplemented
        :else                             :elsewhere))))

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

(defn- value-button
  "A button with `icon` in an editable value, named by `label`."
  [icon label on-click]
  [:button.edit-value__button {:type     "button"
                               :title    label
                               :on-click on-click}
   [:span {:class       ["edit-value__icon" (str "edit-value__icon--" icon)]
           :aria-hidden "true"}]
   [:span.visually-hidden label]])

(defn- focus-button!
  "Move the focus to the button named `title` in the list around `node`, once
  the list has been drawn again."
  [node title]
  ;; in the frame after the next one, since the list is drawn in the next
  #?(:cljs (when-let [ul (.closest node "ul")]
             (js/requestAnimationFrame
               (fn []
                 (js/requestAnimationFrame
                   #(some-> (->> (array-seq (.querySelectorAll ul "button"))
                                 (filter (fn [b] (= title (.-title b))))
                                 (first))
                            (.focus))))))))

(rum/defcs edit-value < (rum/local false ::editing?)
  "A value `v` of the property `k` in `opts` that can be edited, framed like
  an input field, with its `status` among the pending changes: :added,
  :retracted, :edited when it replaces the value `original`, or nil.

  Its buttons remove it, or undo the change that removed, added or edited
  it. The pencil of a text turns it into an input: Enter or leaving the
  input replaces the text, and Escape leaves it as it was. A blank node and
  a folded member have no buttons yet."
  [state {:keys [subject languages comments] :as opts} k v status original]
  (let [editing? (::editing? state)
        t        [subject k v]
        text?    (or (string? v) (some? (i18n/lang v)))
        label    #(str (i18n/da-en languages %1 %2) ": "
                       (if (keyword? v) (rdf/link-label opts v) (str v)))
        edit     #(str (i18n/da-en languages "Ret" "Edit") ": " %)
        ;; the focus goes back to the pencil, of the new text when there's one
        replace! (fn [e]
                   (when @editing?
                     (reset! editing? false)
                     (let [s        (str/trim (.. e -target -value))
                           changed? (not (or (empty? s) (= s (str v))))]
                       (focus-button! (.-target e) (edit (if changed? s v)))
                       (when changed?
                         (app/update-changes!
                           change/replace t
                           [subject k (if-let [lang (i18n/lang v)]
                                        (term/->LangString s lang)
                                        s)])))))]
    [:li (cond-> {:class ["editable"]}
           status (update :class conj (name status))
           original (assoc :title (str (i18n/da-en languages "Før" "Before")
                                       ": " original))
           (next (value-origins opts k v)) (assoc :title (:shared comments)))
     (if @editing?
       [:textarea.edit-value__input
        {:default-value (str v)
         :lang          (i18n/lang v)
         :aria-label    (edit v)
         :auto-focus    true
         :rows          1
         ;; the caret starts at the end of the text
         :on-focus      (fn [e]
                          (let [n (count (.. e -target -value))]
                            (.setSelectionRange (.-target e) n n)))
         :on-blur       replace!
         :on-key-down   (fn [e]
                          (case (.-key e)
                            "Enter" (do (.preventDefault e) (replace! e))
                            "Escape" (do (reset! editing? false)
                                         (focus-button! (.-target e) (edit v)))
                            nil))}]
       ;; a removed value is a <del>, an added or edited one an <ins>
       [(case status :retracted :del (:added :edited) :ins :span)
        (cond-> (assoc (rdf/value-attrs v) :class ["edit-value__content"])
          (keyword? v) (update :class conj "attr-combo"))
        (rdf/value opts v)])
     (when-not (or @editing? (map? v) (= k :rdfs/member))
       [:span.edit-value__actions
        (when (and text? (not= status :retracted))
          (value-button "edit" (edit v) #(reset! editing? true)))
        (case status
          :retracted (value-button "undo" (label "Fortryd fjernelse af"
                                                 "Undo removal of")
                                   #(app/update-changes! without-change t))
          :added (value-button "remove" (label "Fjern" "Remove")
                               #(app/update-changes! without-change t))
          ;; removing an edited value removes the value it replaces
          :edited [:<>
                   (value-button "undo" (label "Fortryd rettelse af"
                                               "Undo edit of")
                                 #(app/update-changes! without-change t))
                   (value-button "remove" (label "Fjern" "Remove")
                                 #(app/update-changes!
                                    (fn [changes]
                                      (-> (without-change changes t)
                                          (change/retract
                                            [subject k original])))))]
          (value-button "remove" (label "Fjern" "Remove")
                        #(app/update-changes! change/retract t)))])]))

(rum/defc edit-cell
  "A table cell that lists every one of the values `vs` of the property `k`
  in `opts`, for a row in edit mode, with the pending changes made: an
  edited value shows its new text. A value that can't be edited has a
  padlock that says why, and it comes after the ones that can."
  [{:keys [comments] :as opts} k vs]
  (let [{:keys [added retracted replacements] :as pending} (pending opts k)
        original (into {}
                       (for [[old replacing] replacements
                             v               replacing]
                         [v old]))]
    [:td
     [:ul.edit-values
      (for [v    (edit-order opts k vs pending)
            :let [reason (when-not (added v) (lock opts k v))
                  key    (pr-str (if (map? v) (:rdf/about v) v))]]
        (if reason
          [:li (cond-> (assoc (rdf/value-attrs v) :key key :class ["locked"])
                 (keyword? v) (update :class conj "attr-combo"))
           (padlock (get-in comments [:locked reason]))
           (rdf/value opts v)]
          (rum/with-key (edit-value opts k v
                                    (cond
                                      (retracted v) :retracted
                                      (contains? original v) :edited
                                      (added v) :added)
                                    (get original v))
                        key)))]]))

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
