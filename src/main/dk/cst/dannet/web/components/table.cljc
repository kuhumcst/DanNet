(ns dk.cst.dannet.web.components.table
  (:require [rum.core :as rum]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.web.i18n :as i18n]
            [dk.cst.dannet.web.components.rdf :as rdf]
            #?(:cljs [dk.cst.dannet.web.components.visualization :as viz])))

(def word-cloud-limit
  "Arbitrary limit on word cloud size for performance and display reasons."
  150)

(def expandable-coll-cutoff
  4)

(declare attr-val-table)

(rum/defc val-cell
  "A table cell of an 'attr-val-table' containing a single `v`. The single value
  can either be a literal or an inlined table (i.e. a blank RDF node)."
  [{:keys [languages] :as opts} v]
  (cond
    (keyword? v)
    (if (empty? (name v))
      ;; Handle cases such as :rdfs/ which have been keywordised by Aristotle.
      [:td
       (rdf/rdf-uri-hyperlink (-> v namespace symbol prefix/prefix->uri) opts)]
      [:td.attr-combo                                       ; fixes alignment
       (rdf/rdf-resource-hyperlink v opts)])

    ;; Using blank resource data included as a metadata map.
    (map? v)
    [:td (or (rdf/blank-resource opts v)
             (attr-val-table opts v))]

    ;; Doubly inlined tables are omitted entirely.
    (nil? v)
    (i18n/da-en languages
                [:td.omitted "(detaljer udeladt)"]
                [:td.omitted "(details omitted)"])

    :else
    (let [s (i18n/select-str languages v)]
      [:td {:lang (i18n/lang s)} (rdf/transform-val s opts)])))

(rum/defc list-item
  "A list item element of a 'list-cell'."
  [opts item]
  (cond
    (keyword? item)
    [:li (rdf/rdf-resource-hyperlink item opts)]

    ;; Currently not including these as they seem to
    ;; be entirely garbage temp data, e.g. check out
    ;; http://0.0.0.0:3456/dannet/2022/external/ontolex/LexicalSense
    (symbol? item)
    (if-let [m (not-empty (meta item))]
      [:li (or (rdf/blank-resource opts m)
               (attr-val-table opts m))]
      [:li.omitted (str item)])

    :else
    [:li {:lang (i18n/lang item)}
     (rdf/transform-val item opts)]))

(rum/defc list-cell-coll-items
  [opts coll]
  (for [{:keys [item sort-key]} (shared/sort-by-label-with-keys opts coll)]
    (rum/with-key (list-item opts item) sort-key)))

;; A Rum-controlled version of the <details> element which only renders content
;; if the containing <details> element is open. This circumvents the default
;; behaviour which is to prerender the content in the DOM, but keep it hidden.
;; Some of the more well-connected synsets take AGES to load without this fix!
(rum/defcs react-details < (rum/local false ::open)
  [state summary content]
  (let [open (::open state)]
    [:details {:on-toggle #(swap! open not)
               :open      @open}
     (when summary
       (if (not @open)
         summary
         [:summary ""]))
     (when @open content)]))

(defn- expandable-coll*
  [{:keys [languages] :as opts} summary-coll rest-coll]
  (let [total-amount (+ (count summary-coll)
                        (count rest-coll))
        c            (condp > total-amount
                       100 "two-digits"
                       1000 "three-digits"
                       10000 "four-digits"
                       100000 "five-digits")]
    [:<>
     [:ol {:class c}
      (list-cell-coll-items opts summary-coll)]
     (react-details
       [:summary
        (i18n/da-en languages
                    (str (count rest-coll) " flere")
                    (str (count rest-coll) " more"))]
       [:ol {:class c
             :start 4}
        (list-cell-coll-items opts rest-coll)])]))

(defn expandable-coll
  [opts coll]
  ;; Special behaviour for synset/LexicalConcept
  ;; TODO: top 3 synsets by weight are still sorted alphabetically, change?
  (expandable-coll* opts (take 3 coll) (drop 3 coll)))

(defn display-cloud?
  [{:keys [attr-key] :as opts} v]
  (and (coll? v)
       (> (count v) expandable-coll-cutoff)
       ;; A word cloud is only relevant in cases where the content has been
       ;; presorted by weight, e.g. synset relations currently in use in DanNet.
       (get shared/synset-rel-theme attr-key)))

(rum/defc list-cell-coll
  "A list of ordered content; hidden by default when there are too many items."
  [{:keys [display-opt] :as opts} coll]
  (let [coll-count   (count coll)
        display-opt' (or display-opt
                         ;; Display the limited, radial cloud by default for
                         ;; large colls and use tables for everything else.
                         (when (and (display-cloud? opts coll)
                                    (> coll-count word-cloud-limit))
                           "cloud"))]
    (case display-opt'
      "cloud" #?(:cljs (viz/word-cloud
                         (assoc opts :cloud-limit word-cloud-limit) coll)
                 :clj  [:div])
      "max-cloud" #?(:cljs (viz/word-cloud opts coll)
                     :clj  [:div])
      (if (<= coll-count expandable-coll-cutoff)
        [:ol (list-cell-coll-items opts coll)]
        (expandable-coll opts coll)))))

(rum/defc list-cell
  "A table cell of an 'attr-val-table' containing multiple values in `coll`."
  [opts coll]
  [:td
   (if-let [transformed-coll (rdf/transform-val-coll coll opts)]
     transformed-coll
     (list-cell-coll opts coll))])

(rum/defc str-list-cell
  "A table cell of an 'attr-val-table' containing multiple strings in `coll`."
  [{:keys [languages] :as opts} coll]
  (let [s (i18n/select-str languages coll)]
    (if (coll? s)
      [:td {:key coll}
       [:ol
        (for [s* (sort-by str s)]
          [:li {:key  s*
                :lang (i18n/lang s*)}
           (rdf/transform-val s* opts)])]]
      [:td {:lang (i18n/lang s) :key coll} (rdf/transform-val s opts)])))

(rum/defc attr-val-row < rum/reactive
                         "A single row in the attribute-value table. Only re-renders when its specific display option changes."
  [{:keys [subject languages comments] :as opts} k v display-opts inherited? inferred?]
  (let [prefix        (if (keyword? k)
                        (symbol (namespace k))
                        k)
        display-opt   (get-in @display-opts [subject k])
        opts+attr-key (assoc opts
                        :attr-key k
                        :display-opt display-opt)
        v-count       (if (coll? v) (count v) 0)]
    [:tr (cond-> {:key (str k)}
           inferred? (update :class conj "inferred")
           inherited? (update :class conj "inherited"))
     [:td.attr-prefix
      ;; TODO: link to definition below?
      (when inferred?
        [:span.marker {:title (:inference comments)} "∴"])
      (when inherited?
        [:span.marker {:title (:inheritance comments)} "†"])
      (rdf/prefix-elem prefix)]
     [:td.attr-name
      (rdf/entity-link k opts+attr-key)

      ;; Longer lists of synsets can be displayed as a word cloud.
      (when (display-cloud? opts+attr-key v)
        ;; Default to word clouds for longer collections.
        (let [value  (or display-opt "cloud")
              change (fn [e]
                       (swap! display-opts assoc-in [subject k]
                              (.-value (.-target e))))]
          (i18n/da-en languages
                      [:select.display-options {:title     "Visningsmuligheder"
                                                :value     value
                                                :on-change change}
                       [:option {:value ""}
                        "liste"]
                       (if (> v-count word-cloud-limit)
                         [:<>
                          [:option {:value "cloud"}
                           (str "ordsky (top)")]
                          [:option {:value "max-cloud"}
                           (str "ordsky (" v-count ")")]]
                         [:option {:value "max-cloud"}
                          "ordsky"])]
                      [:select.display-options {:title     "Display options"
                                                :value     value
                                                :on-change change}
                       [:option {:value ""}
                        "list"]
                       (if (> v-count word-cloud-limit)
                         [:<>
                          [:option {:value "cloud"}
                           (str "word cloud (top)")]
                          [:option {:value "max-cloud"}
                           (str "word cloud (" v-count ")")]]
                         [:option {:value "max-cloud"}
                          "word cloud"])])))]
     (cond
       ;; NOTE: this used to only test using `set?`, but as we return both
       ;;       sets and sorted colls now, we need to test this instead.
       (shared/multi-valued? v)
       (cond
         (= 1 (count v))
         (let [v* (first v)]
           (rum/with-key (val-cell opts+attr-key (if (symbol? v*)
                                                   (meta v*)
                                                   v*))
                         v))

         (every? i18n/rdf-string? v)
         (str-list-cell opts+attr-key v)

         ;; TODO: use sublist for identical labels
         :else
         (list-cell opts+attr-key v))

       (keyword? v)
       (rum/with-key (val-cell opts+attr-key v) v)

       (symbol? v)
       (rum/with-key (val-cell opts+attr-key (meta v)) v)

       :else
       [:td {:lang (i18n/lang v) :key v}
        (rdf/transform-val v opts+attr-key)])]))

(rum/defcs attr-val-table < (rum/local {} ::display-opts)
                            "A table which lists attributes and corresponding values of an RDF resource."
  [state {:keys [inherited inferred] :as opts} subentity]
  (let [display-opts (::display-opts state)]
    [:table {:class "attr-val"}
     [:colgroup
      [:col]                                                ; attr prefix
      [:col]                                                ; attr local name
      [:col]]
     [:tbody
      (for [[k v] subentity]
        (rum/with-key (attr-val-row opts k v display-opts
                                    (get inherited k)
                                    (get inferred k))
                      k))]]))
