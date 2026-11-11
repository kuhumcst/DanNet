(ns dk.cst.dannet.web.ui.table
  "Table rendering functions for RDF data."
  (:require [clojure.string :as str]
            [rum.core :as rum]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.web.i18n :as i18n]
            #?(:clj  [dk.cst.dannet.web.ui.error :as error]
               :cljs [dk.cst.dannet.web.ui.error :as error :include-macros true])
            [dk.cst.dannet.web.ui.edit :as edit]
            [dk.cst.dannet.web.ui.lock :as lock]
            [dk.cst.dannet.web.ui.rdf :as rdf]
            [dk.cst.dannet.web.ui.visualization :as viz]))

(def cloud-cutoff
  "Relations with more values than this can be shown as a word cloud."
  4)

(rum/defc value-cell
  "A table cell of an 'attr-val-table' containing the single value `v`, e.g. a
  literal or an inlined table (i.e. a blank RDF node)."
  [opts v]
  [:td (rdf/lone-value-attrs v)
   (rdf/value opts v)])

(defn display-cloud?
  "Can the values `v` of the predicate in `opts` be shown as a word cloud?"
  [{:keys [attr-key] :as opts} v]
  (and (coll? v)
       (> (count v) cloud-cutoff)
       ;; A word cloud is only relevant in cases where the content has been
       ;; presorted by weight, e.g. synset relations currently in use in DanNet.
       (shared/weight-sorted-rel? attr-key)))

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
  value that can be edited lists all its values in fields (see ui.edit), and
  any other row is faded, with a padlock that says why, unless the whole
  page is locked. No row is a word cloud then. The values of a nested table have no origins, so its rows have
  neither fields nor padlocks."
  [{:keys [subject comments inherited edit-mode default-origin] :as opts}
   k v display-opts]
  (let [origins       (lock/row-origins opts k v)
        inherited?    (get inherited k)
        inferred?     (contains? origins :inferred)
        supplemented? (contains? origins :supplemented)
        prefix        (if (keyword? k)
                        (some-> (namespace k) symbol)
                        k)
        ;; edit mode lists the values of every row, so it has no word clouds;
        ;; the empty string is the list option
        display-opt   (if edit-mode
                        ""
                        (get-in @display-opts [subject k]))
        opts+attr-key (assoc opts
                        :attr-key k
                        :display-opt display-opt)
        vs            (if (shared/multi-valued? v) v [v])
        locks         (when (and edit-mode (some? default-origin))
                        (map #(lock/lock opts k %) vs))
        edit-row?     (some nil? locks)
        locked?       (and locks (not edit-row?))
        ;; the padlock of the page says that its resource is locked (see
        ;; ui.entity/entity-header), so a row only says what else locks it
        why           (when locked?
                        (->> (distinct locks)
                             (remove #{:resource})
                             (map #(get-in comments [:locked %]))
                             (str/join "; ")
                             (not-empty)))]
    [:tr (cond-> {:key (str k)}
           (keyword? k) (assoc :property (prefix/kw->qname k))
           inferred? (update :class conj "inferred")
           inherited? (update :class conj "inherited")
           supplemented? (update :class conj "supplemented")
           locked? (update :class conj "locked"))
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
      (when why
        (edit/padlock why))
      ;; edit mode has no word clouds, so no display options either
      (attr-name-extras opts+attr-key (when-not edit-mode v) display-opts)]
     (cond
       edit-row?
       (edit/cell opts+attr-key k vs)

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
  row of members (see shared/member-row) in place of the first of them, with
  the members in ordinal order."
  [subentity]
  (let [rows     (seq subentity)
        ordinals (filter (comp shared/member-property? key) rows)]
    (if (seq ordinals)
      (let [members (->> ordinals
                         (sort-by (comp shared/member-ordinal key))
                         (mapv (comp shared/unwrap val)))
            first-k (key (first ordinals))]
        (keep (fn [[k _ :as row]]
                (cond
                  (= k first-k) [shared/member-row members]
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
