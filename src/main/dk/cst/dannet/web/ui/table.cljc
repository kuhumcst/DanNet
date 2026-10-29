(ns dk.cst.dannet.web.ui.table
  "Table rendering functions for RDF data."
  (:require [rum.core :as rum]
            [dk.cst.dannet.shared :as shared]
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
  "A table cell of an 'attr-val-table' containing a single `v`. The single value
  can either be a literal or an inlined table (i.e. a blank RDF node)."
  [{:keys [languages] :as opts} v]
  (cond
    (keyword? v)
    (if (empty? (name v))
      ;; A namespace IRI arrives as a keyword with no name, e.g. :rdfs/.
      [:td
       (rdf/rdf-uri-hyperlink (-> v namespace symbol prefix/prefix->uri) opts)]
      [:td.attr-combo                                       ; fixes alignment
       (rdf/resource-hyperlink v opts)])

    ;; A blank node as a map of its properties (or a typed literal).
    (map? v)
    [:td (rdf/blank-node opts v)]

    ;; Doubly inlined tables, i.e. blank nodes inside blank nodes, arrive as
    ;; symbols and are omitted entirely.
    (symbol? v)
    (i18n/da-en languages
      [:td.omitted "(detaljer udeladt)"]
      [:td.omitted "(details omitted)"])

    :else
    (let [s (i18n/select-str languages v)]
      [:td {:lang (i18n/lang s)} (rdf/transform-val s opts)])))

(defn display-cloud?
  "Can the values `v` of the predicate in `opts` be shown as a word cloud?"
  [{:keys [attr-key] :as opts} v]
  (and (coll? v)
       (> (count v) cloud-cutoff)
       ;; A word cloud is only relevant in cases where the content has been
       ;; presorted by weight, e.g. synset relations currently in use in DanNet.
       (shared/weight-sorted-rel? attr-key)))

(rum/defc list-cell
  "A list of ordered content; hidden by default when there are too many items."
  [{:keys [display-opt] :as opts} coll]
  (let [coll-count   (count coll)
        display-opt' (or display-opt
                         ;; Display the limited, radial cloud by default for
                         ;; large colls and use tables for everything else.
                         (when (and (display-cloud? opts coll)
                                    (>= coll-count shared/semantic-relation-limit))
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

(rum/defc multi-value-cell
  "A table cell of an 'attr-val-table' containing multiple values in `coll`."
  [opts coll]
  (if-let [transformed-coll (rdf/transform-val-coll coll opts)]
    transformed-coll
    (list-cell opts coll)))

(rum/defc string-list-cell
  "A table cell of an 'attr-val-table' containing multiple strings in `coll`."
  [opts coll]
  [:td {:key coll}
   (rdf/transform-text opts coll)])

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
  ;; Default to word cloud only for collections exceeding the limit.
  ;; The empty string is the list option; nil would make React warn
  ;; about a null `value` prop on the <select>.
  (let [exceeds-limit? (> v-count shared/semantic-relation-limit)
        value          (or display-opt (when exceeds-limit? "cloud") "")
        change         (fn [e]
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
       (if exceeds-limit?
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
  "A single row in the attribute-value table."
  [{:keys [subject comments] :as opts} k v display-opts inherited? inferred? supplemented?]
  (let [prefix        (if (keyword? k)
                        (some-> (namespace k) symbol)
                        k)
        display-opt   (get-in @display-opts [subject k])
        opts+attr-key (assoc opts
                        :attr-key k
                        :display-opt display-opt)]
    [:tr (cond-> {:key (str k)}
           (keyword? k) (assoc :property (prefix/kw->qname k))
           inferred? (update :class conj "inferred")
           inherited? (update :class conj "inherited")
           supplemented? (update :class conj "supplemented"))
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
      (attr-name-extras opts+attr-key v display-opts)]
     (cond
       ;; NOTE: this used to only test using `set?`, but as we return both
       ;;       sets and sorted colls now, we need to test this instead.
       (shared/multi-valued? v)
       (cond
         (= 1 (count v))
         (rum/with-key (value-cell opts+attr-key (first v)) v)

         (every? i18n/rdf-string? v)
         (string-list-cell opts+attr-key v)

         :else
         [:td (multi-value-cell opts+attr-key v)])

       (or (keyword? v) (symbol? v) (rdf/blank-node? v))
       (rum/with-key (value-cell opts+attr-key v) v)

       :else
       [:td {:lang (i18n/lang v) :key v}
        (rdf/transform-val v opts+attr-key)])]))

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
  [state {:keys [subject languages inherited inferred supplemented] :as opts} subentity]
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
                                    k v display-opts
                                    (get inherited k)
                                    (get inferred k)
                                    (get supplemented k))
                      k))]]))
