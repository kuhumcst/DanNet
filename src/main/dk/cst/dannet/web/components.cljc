(ns dk.cst.dannet.web.components
  "Shared frontend/backend Rum components."
  (:require [clojure.string :as str]
            [flatland.ordered.map :as fop]
            [rum.core :as rum]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.web.i18n :as i18n]
            [dk.cst.dannet.web.section :as section]
            [ont-app.vocabulary.core :as voc]
            [ont-app.vocabulary.lstr :as lstr]
            [nextjournal.markdown :as md]
            [nextjournal.markdown.transform :as md.transform]
            #?(:cljs [dk.cst.dannet.web.components.visualization :as viz])
            #?(:clj [better-cond.core :refer [cond]])
            #?(:cljs [dk.cst.aria.combobox :as combobox])
            #?(:cljs [reagent.cookies :as cookie])
            #?(:cljs [lambdaisland.uri :as uri])
            #?(:cljs [reitit.frontend.history :as rfh])
            #?(:cljs [reitit.frontend.easy :as rfe]))
  #?(:cljs (:require-macros [better-cond.core :refer [cond]]))
  (:refer-clojure :exclude [cond])
  #?(:clj (:import [clojure.lang Named])))

;; TODO: superfluous DN:A4-ark http://localhost:3456/dannet/data/synset-48300
;; TODO: empty synset http://localhost:3456/dannet/data/synset-47272
;; TODO: equivalent class empty http://localhost:3456/dannet/external/semowl/InformationEntity
;; TODO: empty definition http://0.0.0.0:3456/dannet/data/synset-42955

;; Track hydration state to distinguish between first and subsequent renders.
(defonce ^:dynamic *hydrated* false)

(def word-cloud-limit
  "Arbitrary limit on word cloud size for performance and display reasons."
  150)

(def rdf-resource-re
  #"^<(.+)>$")

(defn break-up-uri
  "Place word break opportunities into a potentially long `uri`."
  [uri]
  (into [:<>] (for [part (re-seq #"[^\./]+|[\./]+" uri)]
                (if (re-matches #"[^\./]+" part)
                  [:<> part [:wbr]]
                  part))))

(rum/defc rdf-uri-hyperlink
  "Display URIs in RDF <resource> style or using a label when available."
  [uri {:keys [languages k->label attr-key] :as opts}]
  (let [labels (get k->label (prefix/uri->rdf-resource uri))
        label  (i18n/select-label languages labels)
        path   (prefix/uri->internal-path uri)]
    (if (and label
             (not (= attr-key :foaf/homepage)))             ; special behaviour
      [:a {:href path} (str label)]
      [:a.rdf-uri {:href path} (break-up-uri uri)])))

(declare prefix-elem)
(declare anchor-elem)

(defn rdf-datatype?
  [x]
  (and (map? x) (:value x) (:uri x)))

(defn transform-val
  "Performs convenient transformations of `v`, optionally informed by `opts`."
  ([v {:keys [attr-key entity] :as opts}]
   (cond
     (rdf-datatype? v)
     (let [{:keys [uri value]} v]
       [:span {:title    uri
               :datatype uri}
        value])

     ;; Transformations of non-strings
     ;; TODO: properly implement date parsing
     (inst? v)
     (let [s (str v)]
       [:time {:date-time s} s])

     ;; Transformations of strings ONLY from here on
     :when-let [s (not-empty (str/trim (str v)))]

     (= attr-key :vann/preferredNamespacePrefix)
     (prefix-elem (symbol s) {:independent-prefix true})

     :let [[rdf-resource uri] (re-find rdf-resource-re s)]
     (re-matches #"\{.+\}" s)
     [:div.set
      [:div.set__left-bracket]
      (into [:div.set__content]
            (interpose
              [:span.subtle " • "]                          ; semicolon->bullet
              (for [label (shared/sense-labels shared/synset-sep s)]
                (if-let [[_ word _ sub mwe] (re-matches shared/sense-label label)]
                  [:<>
                   (if (= word shared/omitted)
                     [:span.subtle word]
                     word)
                   ;; Correct for the rare case of comma an affixed comma.
                   ;; e.g. http://localhost:3456/dannet/data/synset-7290
                   (when sub
                     (if (str/ends-with? sub ",")
                       [:<> [:sub (subs sub 0 (dec (count sub)))] ","]
                       [:sub sub]))
                   mwe]
                  label))))
      [:div.set__right-bracket]]

     rdf-resource
     (rdf-uri-hyperlink uri opts)

     ;; TODO: match is too broad, should be limited somewhat
     (or (get #{:ontolex/sense :ontolex/lexicalizedSense} attr-key)
         (= (:rdf/type entity) #{:ontolex/LexicalSense}))
     (let [[_ word _ sub mwe] (re-matches shared/sense-label s)]
       [:<> word [:sub sub] mwe])

     (re-matches #"https?://[^\s]+" s)
     (break-up-uri s)

     (re-find #"\n" s)
     (into [:<>] (interpose [:br] (str/split s #"\n")))

     :else s))
  ([s]
   (transform-val s nil)))

;; TODO: figure out how to prevent line break for lang tag similar to h1
(rum/defc anchor-elem
  "Entity hyperlink from a `resource` and (optionally) a string label `s`."
  [resource {:keys [languages k->label class] :as opts}]
  (if (keyword? resource)
    (let [labels (get k->label resource)
          label  (i18n/select-label languages labels)
          prefix (symbol (namespace resource))]
      [:a {:href  (prefix/resolve-href resource)
           :title (str prefix ":" (name resource))
           :lang  (i18n/lang label)
           :class (or class (get prefix/prefix->class prefix "unknown"))}
       (or (transform-val label opts)
           (name resource))])
    ;; RDF predicates represented as IRIs Since the namespace is unknown,
    ;; we likely have no label data either and do not bother to fetch it.
    ;; See 'rdf-uri-hyperlink' for how objects are represented!
    (let [local-name (prefix/guess-local-name resource)]
      [:a {:href  (prefix/resource-path resource)
           :title local-name
           :class "unknown"}
       local-name])))

;; See also 'rdf-uri-hyperlink'.
(rum/defc rdf-resource-hyperlink
  "A stylised RDF `resource` hyperlink, stylised according to `opts`."
  [resource {:keys [attr-key k->label] :as opts}]
  (cond
    ;; Label and text colour are modified to fit the inherited relation.
    (= attr-key :dns/inherited)
    (let [inherited       (some->> (get k->label resource) first (prefix/qname->kw))
          inherited-label (get k->label inherited)
          prefix          (when inherited
                            (symbol (namespace inherited)))
          opts'           (-> opts
                              (assoc-in [:k->label resource] inherited-label)
                              (assoc :class (get prefix/prefix->class prefix)))]
      [:div.qname
       (prefix-elem (or prefix (symbol (namespace resource))) opts')
       (anchor-elem resource opts')])

    ;; The generic case just displays the prefix badge + the hyperlink.
    :else
    [:div.qname
     (prefix-elem (symbol (namespace resource)) opts)
     (anchor-elem resource opts)]))

(defn- named?
  [x]
  #?(:cljs (implements? INamed x)
     :clj  (instance? Named x)))

(defn transform-val-coll
  "Performs convenient transformations of `coll` informed by `opts`."
  [coll {:keys [attr-key] :as opts}]
  (cond
    (and (= :dns/ontologicalType attr-key)
         (every? #(and (named? %)
                       (= "dnc" (namespace %))) coll))
    (let [vs     (sort-by name coll)
          fv     (first vs)
          prefix (symbol (namespace fv))]
      (for [v vs]
        [:<> {:key v}
         (when-not (= fv v)
           " + ")
         (prefix-elem prefix opts)
         (anchor-elem v opts)]))))

(defn local-entity-prefix?
  "Is this `prefix` the same as the local entity in `opts`?"
  [prefix {:keys [attr-key entity] :as opts}]
  (or (and (keyword? attr-key)
           (= prefix (-> attr-key namespace symbol)))
      (and (keyword? (:subject (meta entity)))
           (= prefix (-> entity meta :subject namespace symbol)))))

;; TODO: don't hide when `details?` is true?
(defn- hide-prefix?
  "Whether to hide the value column `prefix` according to its context `opts`."
  [prefix {:keys [attr-key details?] :as opts}]
  (or (= :rdf/about attr-key)
      ;; TODO: don't hardcode ontologicalType (get from input config instead)
      (= :dns/ontologicalType attr-key)
      (and (not= :dns/inherited attr-key)                   ; special case
           (local-entity-prefix? prefix opts))))

(rum/defc prefix-elem
  "Visual representation of a `prefix` based on its associated symbol.

  If context `opts` are provided, the `prefix` is assumed to be in the value
  column and will potentially be hidden according to the provided context."
  ([prefix]
   (prefix-elem prefix nil))
  ([prefix {:keys [independent-prefix] :as opts}]
   (cond
     (symbol? prefix)
     [:span.prefix {:title (prefix/prefix->uri prefix)
                    :class [(prefix/prefix->class prefix)
                            (if independent-prefix
                              "independent"
                              (when (hide-prefix? prefix opts)
                                "hidden"))]}
      (str prefix) [:span.prefix__sep ":"]]

     (string? prefix)
     [:span.prefix {:title (prefix/guess-ns prefix)
                    :class "unknown"}
      "???"])))

(defn numbered?
  [x]
  (and (keyword? x)
       (= "rdf" (namespace x))
       (str/starts-with? (name x) "_")))

(declare attr-val-table)

(rum/defc blank-resource
  "Display a blank resource in either a specialised way or as an inline table."
  [{:keys [languages] :as opts} x]
  (cond
    (rdf-datatype? x)
    (transform-val x)

    (= (keys x) [:rdf/value])
    (let [x (i18n/select-str languages (:rdf/value x))]
      (if (coll? x)
        (into [:<>] (for [s x]
                      [:section.text {:lang (i18n/lang s)} (str s)]))
        [:section.text {:lang (i18n/lang x)} (str x)]))

    ;; Special handling of DanNet sentiment data.
    (and (= (keys x) [:marl/hasPolarity :marl/polarityValue])
         (keyword? (first (:marl/hasPolarity x))))
    [:<>
     (rdf-resource-hyperlink (first (:marl/hasPolarity x)) opts)
     " (" (first (:marl/polarityValue x)) ")"]

    (contains? (:rdf/type x) :rdf/Bag)
    (let [ns->resources (-> (->> (dissoc x :rdf/type)
                                 (filter (comp numbered? first))
                                 (mapcat second)
                                 (group-by namespace))
                            (update-vals sort)
                            (update-keys symbol))
          resources     (->> (sort ns->resources)
                             (vals)
                             (apply concat))]
      [:div.set
       (when (and (every? keyword? resources)
                  (apply = (map namespace resources)))
         (let [prefix (symbol (namespace (first resources)))]
           (prefix-elem prefix opts)))
       [:div.set__left-bracket]
       (into [:div.set__content]
             (->> (sort ns->resources)
                  (vals)
                  (apply concat)
                  (map #(anchor-elem % opts))
                  (interpose [:span.subtle " • "])))
       [:div.set__right-bracket]])

    :else
    (attr-val-table opts x)))

(rum/defc val-cell
  "A table cell of an 'attr-val-table' containing a single `v`. The single value
  can either be a literal or an inlined table (i.e. a blank RDF node)."
  [{:keys [languages] :as opts} v]
  (cond
    (keyword? v)
    (if (empty? (name v))
      ;; Handle cases such as :rdfs/ which have been keywordised by Aristotle.
      [:td
       (rdf-uri-hyperlink (-> v namespace symbol prefix/prefix->uri) opts)]
      [:td.attr-combo                                       ; fixes alignment
       (rdf-resource-hyperlink v opts)])

    ;; Using blank resource data included as a metadata map.
    (map? v)
    [:td (blank-resource opts v)]

    ;; Doubly inlined tables are omitted entirely.
    (nil? v)
    (i18n/da-en languages
      [:td.omitted "(detaljer udeladt)"]
      [:td.omitted "(details omitted)"])

    :else
    (let [s (i18n/select-str languages v)]
      [:td {:lang (i18n/lang s)} (transform-val s opts)])))

(rum/defc list-item
  "A list item element of a 'list-cell'."
  [opts item]
  (cond
    (keyword? item)
    [:li (rdf-resource-hyperlink item opts)]

    ;; Currently not including these as they seem to
    ;; be entirely garbage temp data, e.g. check out
    ;; http://0.0.0.0:3456/dannet/2022/external/ontolex/LexicalSense
    (symbol? item)
    (if (not-empty (meta item))
      [:li (blank-resource opts (meta item))]
      [:li.omitted (str item)])

    :else
    [:li {:lang (i18n/lang item)}
     (transform-val item opts)]))

(rum/defc list-cell-coll-items
  [opts coll]
  (let [sort-key (shared/label-sortkey-fn opts)]
    (for [item (sort-by sort-key coll)]
      (rum/with-key (list-item opts item) (sort-key item)))))

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

(defn weight-sort
  "Sort `synsets` by `weights` (synset->weight mapping)."
  [weights synsets]
  (->> (select-keys weights synsets)
       (sort-by second)
       (map first)
       (reverse)))

(defn ol-class
  [amount]
  (cond
    (< amount 100) "two-digits"
    (< amount 1000) "three-digits"
    (< amount 10000) "four-digits"
    (< amount 100000) "five-digits"))

(defn- expandable-coll*
  [{:keys [languages] :as opts} summary-coll rest-coll]
  (let [total (+ (count summary-coll) (count rest-coll))
        c     (ol-class total)]
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
  [{:keys [synset-weights] :as opts} coll]
  ;; Special behaviour for synset/LexicalConcept
  ;; TODO: top 3 synsets by weight are still sorted alphabetically, change?
  (if (get synset-weights (first coll))
    (let [synsets (take 3 (weight-sort synset-weights coll))]
      (expandable-coll* opts synsets (remove (set synsets) coll)))
    (expandable-coll* opts (take 3 coll) (drop 3 coll))))

(def expandable-coll-cutoff
  4)

(rum/defc list-cell-coll
  "A list of ordered content; hidden by default when there are too many items."
  [{:keys [synset-weights display-opt] :as opts} coll]
  (case display-opt
    "cloud" #?(:cljs (viz/word-cloud
                       (assoc opts :cloud-limit word-cloud-limit)
                       (filter synset-weights coll))
               :clj  [:div])
    "max-cloud" #?(:cljs (viz/word-cloud opts (filter synset-weights coll))
                   :clj  [:div])
    (if (<= (count coll) expandable-coll-cutoff)
      [:ol (list-cell-coll-items opts coll)]
      (expandable-coll opts coll))))

(rum/defc list-cell
  "A table cell of an 'attr-val-table' containing multiple values in `coll`."
  [opts coll]
  [:td
   (if-let [transformed-coll (transform-val-coll coll opts)]
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
           (transform-val s* opts)])]]
      [:td {:lang (i18n/lang s) :key coll} (transform-val s opts)])))

;; TODO: maybe just inline these instead?
(defn translate-comments
  [languages]
  {:inference   (i18n/da-en languages
                  "helt eller delvist logisk udledt"
                  "fully or partially logically inferred")
   :inheritance (i18n/da-en languages
                  "helt eller delvist  nedarvet fra hypernym"
                  "fully or partially inherited from hypernym")})

(defn display-cloud?
  [{:keys [synset-weights] :as opts} v]
  (and (coll? v)
       (> (count v) expandable-coll-cutoff)

       ;; TODO: use known synset rels instead...?
       ;; To guard against the possibility of the first synset having no weight.
       ;; This might happen in cases where the cache doesn't match the db 100%.
       (or (get synset-weights (first v))
           (get synset-weights (second v)))))

(rum/defcs attr-val-table < (rum/local {} ::display-opts)
                            "A table which lists attributes and corresponding values of an RDF resource."
  [state {:keys [subject languages inherited inferred comments] :as opts} subentity]
  (let [display-opts (::display-opts state)]
    [:table {:class "attr-val"}
     [:colgroup
      [:col]                                                ; attr prefix
      [:col]                                                ; attr local name
      [:col]]
     [:tbody
      (for [[k v] subentity
            :let [prefix        (if (keyword? k)
                                  (symbol (namespace k))
                                  k)
                  inherited?    (get inherited k)
                  inferred?     (get inferred k)
                  display-opt   (get-in @display-opts [subject k])
                  opts+attr-key (assoc opts
                                  :attr-key k
                                  :display-opt display-opt)]]
        [:tr (cond-> {:key (str k)}
               inferred? (update :class conj "inferred")
               inherited? (update :class conj "inherited"))
         [:td.attr-prefix
          ;; TODO: link to definition below?
          (when inferred?
            [:span.marker {:title (:inference comments)} "∴"])
          (when inherited?
            [:span.marker {:title (:inheritance comments)} "†"])
          (prefix-elem prefix)]
         [:td.attr-name
          (anchor-elem k opts)

          ;; Longer lists of synsets can be displayed as a word cloud.
          (when (display-cloud? opts v)
            (let [value  (or display-opt "")
                  size   (count v)
                  change (fn [e]
                           (swap! display-opts assoc-in [subject k]
                                  (.-value (.-target e))))]
              (i18n/da-en languages
                [:select.display-options {:title     "Visningsmuligheder"
                                          :value     value
                                          :on-change change}
                 [:option {:value ""}
                  "liste"]
                 (if (> size word-cloud-limit)
                   [:<>
                    [:option {:value "cloud"}
                     (str "ordsky (top)")]
                    [:option {:value "max-cloud"}
                     (str "ordsky (" size ")")]]
                   [:option {:value "max-cloud"}
                    "ordsky"])]
                [:select.display-options {:title     "Display options"
                                          :value     value
                                          :on-change change}
                 [:option {:value ""}
                  "list"]
                 (if (> (count v) word-cloud-limit)
                   [:<>
                    [:option {:value "cloud"}
                     (str "word cloud (top)")]
                    [:option {:value "max-cloud"}
                     (str "word cloud (" size ")")]]
                   [:option {:value "max-cloud"}
                    "word cloud"])])))]
         (cond
           (set? v)
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
            (transform-val v opts+attr-key)])])]]))

(defn- ordered-subentity
  "Select a subentity from `entity` based on `ks` (may be a predicate too) and
  order it according to the labels of the preferred languages."
  [opts ks entity]
  (not-empty
    (into (fop/ordered-map)
          (if (coll? ks)
            (->> (for [k ks]
                   (when-let [v (k entity)]
                     [k v]))
                 (remove nil?))
            (sort-by (shared/label-sortkey-fn opts)
                     (filter ks entity))))))

(defn- resolve-names
  [{:keys [subject] :as opts}]
  (when subject
    (if (keyword? subject)
      [(symbol (namespace subject))
       (name subject)
       (voc/uri-for subject)]
      (let [local-name (str/replace subject #"<|>" "")]
        [nil
         local-name
         local-name]))))

(def label-keys
  [:dns/shortLabel
   :rdfs/label
   :dc/title
   :dc11/title
   :foaf/name
   #_:skos/definition                                       ; wn:ili Concepts
   :ontolex/writtenRep])

(def long-label-keys
  [:rdfs/label
   :dns/shortLabel
   :dc/title
   :dc11/title
   :foaf/name
   #_:skos/definition
   :ontolex/writtenRep])

(def short-label-keys
  [:dns/shortLabel
   :rdfs/label
   :dc/title
   :dc11/title
   :foaf/name
   #_:skos/definition
   :ontolex/writtenRep])

(defn entity->label-key
  "Return :rdfs/label or another appropriate key for labeling `entity`."
  ([entity]
   (entity->label-key entity label-keys))
  ([entity ks]
   (loop [[candidate & candidates] ks]
     (if (get entity candidate)
       candidate
       (when candidates
         (recur candidates))))))

(defn entity-label
  [ks entity]
  (get entity (entity->label-key entity ks)))

(defn elem-classes
  [el]
  (set (str/split (.getAttribute el "class") #" ")))

(defn apply-classes
  [el classes]
  (.setAttribute el "class" (str/join " " classes)))

(def radial-tree-selector
  ".radial-tree-nodes [fill],
  .radial-tree-links [stroke],
  .radial-tree-labels [data-theme]")

(defn- get-diagram
  [e]
  (.-previousSibling (.-parentElement (.-parentElement (.-parentElement (.-target e))))))

;; Inspiration for checkboxes: https://www.w3schools.com/howto/tryit.asp?filename=tryhow_css_custom_checkbox
(rum/defcs radial-tree-legend < (rum/local nil ::checked)
  [state {:keys [languages k->label inferred inherited comments] :as opts} subentity]
  [:<>
   [:ul.radial-tree-legend
    (for [k (keys subentity)]
      (when-let [theme (get shared/synset-rel-theme k)]
        (let [label      (i18n/select-label languages (k->label k))
              id         (str k)
              checked    (::checked state)
              inferred?  (get inferred k)
              inherited? (get inherited k)]
          [:li {:key k}
           [:label {:lang (i18n/lang label)} (str label)
            [:input {:type            "checkbox"
                     :default-checked true
                     :on-click        (fn [e]
                                        ;; Set initial state to all checked
                                        (when (nil? @checked)
                                          (reset! checked (set (map shared/synset-rel-theme (keys subentity)))))

                                        ;; Toggle checked/not checked
                                        (if (.-checked (.-target e))
                                          (swap! checked conj theme)
                                          (swap! checked disj theme))

                                        ;; Repaint the diagram based on state
                                        (let [diagram        (get-diagram e)
                                              current-themes @checked]
                                          (doseq [el (.querySelectorAll diagram radial-tree-selector)]
                                            (let [classes (elem-classes el)]
                                              (if (and (not (current-themes (.getAttribute el "stroke")))
                                                       (not (current-themes (.getAttribute el "fill")))
                                                       (not (current-themes (.getAttribute el "data-theme"))))
                                                (when-not (get classes "radial-item__subject")
                                                  (apply-classes el (conj (elem-classes el) "radial-item__de-emphasized")))
                                                (apply-classes el (disj (elem-classes el) "radial-item__de-emphasized")))))))
                     :name            id}]
            [:span {:class "radial-tree-legend__bullet"
                    :style {:background theme}}]
            #_(when inferred?
                [:span.marker {:title (:inference comments)} " ∴"])
            #_(when inherited?
                [:spaln.marker {:title (:inheritance comments)} " †"])]])))]])

(rum/defc entity-page
  [{:keys [href languages comments subject inferred entity k->label details?] :as opts}]
  (let [[prefix local-name rdf-uri] (resolve-names opts)
        ;; Bypass the default use of dns:shortLabel in case the user wants the
        ;; detailed label (usually rsfs:label).
        label-key     (if (and details? (get (:rdf/type entity)
                                             :ontolex/LexicalConcept))
                        :rdfs/label
                        (entity->label-key entity))
        select-label* (partial i18n/select-label languages)
        label         (select-label* (k->label subject))
        label-lang    (i18n/lang label)
        a-titles      [#voc/lstr"Visit this location directly@en"
                       #voc/lstr"Besøg denne lokation direkte@da"]
        inherited     (->> (shared/setify (:dns/inherited entity))
                           (map (comp prefix/qname->kw first k->label))
                           (set))
        uri-only?     (and (not label) (= local-name rdf-uri))]
    [:article
     [:header
      [:h1
       (prefix-elem prefix)
       [:span {:title (if label
                        (prefix/kw->qname label-key)
                        (if uri-only?
                          rdf-uri
                          (str prefix ":" local-name)))
               :key   subject
               :lang  label-lang}
        (if label
          (transform-val label opts)
          (if uri-only?
            [:a.rdf-uri {:href  rdf-uri
                         :title (i18n/select-label languages a-titles)
                         :key   rdf-uri}
             (break-up-uri rdf-uri)]
            local-name))]
       (when label-lang
         [:sup label-lang])]
      (when-not uri-only?
        (if-let [uri-prefix (and prefix (prefix/prefix->uri prefix))]
          [:a.rdf-uri {:href  rdf-uri
                       :title (i18n/select-label languages a-titles)
                       :label (i18n/select-label languages a-titles)}
           [:span.rdf-uri__prefix {:key uri-prefix}
            (break-up-uri uri-prefix)]
           [:span.rdf-uri__name {:key local-name}
            (break-up-uri local-name)]]
          [:a.rdf-uri {:href  rdf-uri
                       :title (i18n/select-label languages a-titles)
                       :key   rdf-uri}
           (break-up-uri rdf-uri)]))]
     (for [[title ks] (section/page-sections entity)]
       (when-let [subentity (-> (ordered-subentity opts ks entity)
                                (not-empty))]
         [:section {:key (or title :no-title)}
          (when title
            [:h2 (str (i18n/select-label languages title))])
          (if (not-empty (select-keys shared/synset-rel-theme (keys subentity)))
            [:<>
             [:p.subheading (i18n/da-en languages
                              "Vis som "
                              "Display as ")
              [:select {:value     (get-in opts [:section title :display :selected])
                        :on-change (fn [e]
                                     (swap! shared/state assoc-in
                                            [:section title :display :selected]
                                            (.-value (.-target e))))}
               (let [m (get-in shared/ui [:section title :display :options])]
                 (->> (sort-by second (if (= "da" (first languages))
                                        (update-vals m first)
                                        (update-vals m second)))
                      (map (fn [[k v]]
                             [:option {:key   k
                                       :value k}
                              v]))))]]
             (case (get-in opts [:section title :display :selected])
               "radial" [:div.radial-tree {:key (str (hash subentity))}
                         #?(:cljs (viz/radial-tree
                                    (assoc opts :label label)
                                    subentity)
                            :clj  [:div.radial-tree-diagram])
                         (radial-tree-legend opts subentity)]
               (attr-val-table (assoc opts :inherited inherited) subentity))]
            (attr-val-table (assoc opts :inherited inherited) subentity))]))
     [:section.notes
      (when (not-empty inferred)
        [:p.note.desktop-only [:strong "∴ "] (:inference comments)])
      (when (not-empty inherited)
        [:p.note.desktop-only [:strong "† "] (:inheritance comments)])
      [:p.note
       [:strong "↓ "]
       (i18n/da-en languages
         "hent data som: "
         "download data as: ")
       ;; TODO: some weird href diff in frontend/backend here
       ;;       http://localhost:3456/dannet/external?subject=%3Chttp%3A%2F%2Fwww.w3.org%2F2000%2F01%2Frdf-schema%23%3E
       [:a {:href     (str href (if (re-find #"\?" href) "&" "?")
                           "format=turtle")
            :type     "text/turtle"
            :title    "Turtle"
            :download true}
        ".ttl"]
       ", "
       [:a {:href     (str href (if (re-find #"\?" href) "&" "?")
                           "format=json")
            :type     "application/ld+json"
            :title    "JSON-LD"
            :download true}
        ".json"]]]]))

(defn- form-elements->query-params
  "Retrieve a map of query parameters from HTML `form-elements`."
  [form-elements]
  (into {} (for [form-element form-elements]
             (when (not-empty (.-name form-element))
               [(.-name form-element) (.-value form-element)]))))

(defn submit-form
  "Submit a form `target` element (optionally with a custom `query-string`)."
  [target & [query-str]]
  #?(:cljs (let [action    (.-action target)
                 query-str (or query-str
                               (-> (.-elements target)
                                   (form-elements->query-params)
                                   (uri/map->query-string)))
                 url       (str action (when query-str
                                         (str "?" query-str)))]
             (js/document.activeElement.blur)
             (shared/navigate-to url))))

;; TODO: handle other methods (only handles GET for now)
(defn on-submit
  "Generic function handling form submit events in Rum components."
  [e]
  #?(:cljs (let [target (.-target e)]
             (.preventDefault e)
             (submit-form target))))

(defn select-text
  "Select text in the target that triggers `e` with a small delay to bypass
  browser's other text selection logic."
  [e]
  #?(:cljs (js/setTimeout #(.select (.-target e)) 100)))

(defn update-search-suggestions
  "An :on-change handler for search suggestions. Each unknown string initiates
  a backend fetch for autocomplete results."
  [e]
  #?(:cljs (let [s                (.-value (.-target e))
                 s'               (shared/search-string s)
                 path             [:search :completion s']
                 autocomplete-url "/dannet/autocomplete"]
             (swap! shared/state assoc-in [:search :s] s')
             (when-not (get-in @shared/state path)
               (.then (shared/api autocomplete-url {:query-params {:s s'}})
                      #(do
                         (shared/clear-fetch autocomplete-url)
                         (when-let [v (not-empty (:autocompletions (:body %)))]
                           (swap! shared/state assoc-in path v))))))))

(defn search-completion-item-id
  [v]
  (str "search-completion-item-" v))

(rum/defc option
  [v on-key-down]
  [:li {:role        "option"
        :tab-index   "-1"
        :on-key-down on-key-down
        :id          (search-completion-item-id v)
        :on-click    (fn [_]
                       #?(:cljs (let [form  (js/document.getElementById "search-form")
                                      input (js/document.getElementById "search-input")]
                                  (set! (.-value input) v)
                                  (submit-form form (str "lemma=" v)))
                          :clj  nil))}
   v])

;; TODO: language localisation
(rum/defc search-form
  [{:keys [lemma search languages] :as opts}]
  (let [{:keys [completion s]} search
        s'                  (shared/search-string s)
        completion-items    (get completion s')
        suggestions?        (boolean (not-empty completion-items))
        submit-label        (i18n/select-label languages
                                               [(lstr/->LangStr "Søg" "da")
                                                (lstr/->LangStr "Search" "en")])
        on-key-down #?(:clj nil :cljs
                       (combobox/keydown-handler
                         #(let [form (js/document.getElementById "search-form")]
                            (submit-form form)
                            (js/document.activeElement.blur))
                         (js/document.getElementById "search-input")
                         (js/document.getElementById "search-completion")
                         {"Escape" (fn [e]
                                     (.preventDefault e)
                                     (js/document.activeElement.blur))}))]
    [:form {:role      "search"
            :id        "search-form"
            :action    prefix/search-path
            :on-submit on-submit
            :method    "get"}
     [:div.search-form__top
      [:input {:role                  "combobox"
               :aria-expanded         suggestions?
               :aria-controls         (str (when suggestions?
                                             "search-completion"))
               :aria-activedescendant (str (when suggestions?
                                             "search-completion-selected"))
               :id                    "search-input"
               :name                  "lemma"
               :title                 (i18n/da-en languages
                                        "Søg efter synsets"
                                        "Search for synsets")
               :placeholder           (i18n/da-en languages
                                        "skriv noget..."
                                        "write something...")
               :on-key-down           on-key-down
               :on-focus              (fn [e] (select-text e))
               :on-click              (fn [e] (.stopPropagation e)) ; don't close overlay
               :on-touch-start        (fn [e] (.focus (.-target e))) ; consistent focus on mobile
               :on-change             update-search-suggestions
               :auto-complete         "off"
               :default-value         (or lemma "")}]
      [:input {:type           "submit"
               :tab-index      "-1"
               :on-click       (fn [e] (.stopPropagation e)) ; don't close overlay)
               :on-touch-start (fn [_] #?(:cljs (submit-form (js/document.getElementById "search-form")))) ; needed on mobile
               :title          (str submit-label)
               :value          (str submit-label)}]]
     [:ul {:role      "listbox"
           :tab-index "-1"
           :id        "search-completion"}
      (when suggestions?
        (for [v completion-items]
          (rum/with-key (option v on-key-down) v)))]]))

(rum/defc search-page
  [{:keys [languages lemma search-results details?] :as opts}]
  [:article.search
   [:header
    [:h1 (str "\"" lemma "\"")]]
   (if (empty? search-results)
     [:p (i18n/da-en languages
           "Ingen resultater kunne findes for dette lemma."
           "No results could be found for this lemma.")]
     (for [[k entity] search-results]
       (let [{:keys [k->label short-label]} (meta entity)
             k->label' (if (and (not details?) short-label)
                         (assoc k->label
                           k short-label)
                         k->label)]
         (rum/with-key (attr-val-table {:languages languages
                                        :k->label  k->label'}
                                       entity)
                       k))))])

(def md->hiccup
  (memoize
    (partial md/->hiccup
             (assoc md.transform/default-hiccup-renderers
               ;; Clerk likes to ignore alt text and produce <figure> tags,
               ;; so we need to intercept the regular image rendering to produce
               ;; accessible images.
               :image (fn [{:as ctx ::keys [parent]}
                           {:as node :keys [attrs content]}]
                        (let [alt (-> (filter (comp #{:text} :type) content)
                                      (first)
                                      (get :text))]
                          [:img (assoc attrs :alt alt)]))))))

(defn _hiccup->title
  "Find the title string located in the first :h1 element in `hiccup`."
  [hiccup]
  (->> (tree-seq vector? rest hiccup)
       (reduce (fn [_ x]
                 (when (= :h1 (first x))
                   (let [node (last x)]
                     (reduced (if (= :img (first node))
                                (:alt (second node))
                                node)))))
               nil)))

(def hiccup->title
  (memoize _hiccup->title))

(rum/defc markdown-page < rum/reactive
  [{:keys [languages content] :as opts}]
  (let [ls     (i18n/select-label languages content)
        lang   (lstr/lang ls)
        md     (str ls)
        hiccup (md->hiccup md)]
    #?(:cljs (when-let [title (hiccup->title hiccup)]
               (set! js/document.title title)))
    [:article.document {:lang lang}
     (md->hiccup md)]))

;; TODO: find better solution? string keys + indirection reduce discoverability
(def pages
  "Mapping from page data metadata :page key to the relevant Rum component."
  {"entity"   entity-page
   "search"   search-page
   "markdown" markdown-page})

(rum/defc page-footer
  [{:keys [languages] :as opts}]
  [:footer
   (i18n/da-en languages
     [:<>
      [:p {:lang "da"}
       [:a {:href  (shared/page-href "privacy")
            :title "Privatlivspolitik"}
        "Privatliv"]
       " · "
       [:a {:href  "https://www.was.digst.dk/wordnet-dk"
            :title "Tilgængelighedserklæring"}
        "Tilgængelighed"]
       " · "
       [:a {:href  (shared/page-href "releases")
            :title "DanNet-versioner"}
        "Versioner"]
       " · "
       [:a {:href  "/dannet/data"
            :title "DanNet-metadata (RDF)"}
        "Metadata"]]
      [:p {:lang "da"}
       "© 2023–2025, "
       [:a {:href "https://cst.ku.dk"}
        "Center for Sprogteknologi"]
       " (" [:abbr {:title "Københavns Universitet"}
             "KU"] ")"
       " & "
       [:a {:href "https://dsl.dk/"}
        "Det Danske Sprog- og Litteraturselskab"]
       "."]]
     [:<>
      [:p {:lang "en"}
       [:a {:href  (shared/page-href "privacy")
            :title "Privacy policy"}
        "Privacy"]
       " · "
       [:a {:href  "https://www.was.digst.dk/wordnet-dk"
            :title "Accessibility statement"}
        "Accessibility"]
       " · "
       [:a {:href  (shared/page-href "releases")
            :title "DanNet releases"}
        "Releases"]
       " · "
       [:a {:href  "/dannet/data"
            :title "DanNet metadata (RDF)"}
        "Metadata"]]
      [:p {:lang "en"}
       "© 2023–2025, "
       [:a {:href "https://cst.ku.dk/english"}
        "Centre for Language Technology"]
       " (" [:abbr {:title "University of Copenhagen"}
             "KU"] ")"
       " & "
       [:a {:lang "da" :href "https://dsl.dk/"}
        "Det Danske Sprog- og Litteraturselskab"]
       "."]])])

(rum/defc language-select < rum/reactive
  [languages]
  [:select.language
   {:title     "Language preference"
    :value     (str (first languages))
    :on-change (fn [e]
                 #?(:cljs (let [v   (-> (.-target e)
                                        (.-value)
                                        (not-empty)
                                        (i18n/lang-prefs))
                                url "/cookies"]
                            (swap! shared/state assoc :languages v)
                            (.then (shared/api url {:method :put
                                                    :body   {:languages v}})
                                   (shared/clear-fetch url)))))}
   [:option {:value ""} "\uD83C\uDDFA\uD83C\uDDF3 Other"]
   [:option {:value "en"} "\uD83C\uDDEC\uD83C\uDDE7 English"]
   [:option {:value "da"} "\uD83C\uDDE9\uD83C\uDDF0 Dansk"]])

(rum/defc loader
  []
  [:div.loader
   [:span.loader__element]
   [:span.loader__element]
   [:span.loader__element]])

(rum/defc help-arrows
  [page {:keys [languages] :as opts}]
  [:section.help-overlay {:aria-hidden true
                          :style       {:opacity (when (not= page "markdown") 0)}}
   [:div.help-overlay__item {:style {:top   6
                                     :color "#df7300"}}
    (i18n/da-en languages
      "start søgning"
      "start search")]
   [:div.help-overlay__item {:style {:bottom 44
                                     :color  "#55f"}}
    (i18n/da-en languages
      "skift sprog"
      "change language")]
   [:div.help-overlay__item {:style {:bottom 6
                                     :color  "#019fa1"}}
    (i18n/da-en languages
      "detaljeniveau"
      "level of detail")]])

(rum/defc page-shell < rum/reactive
  [page {:keys [entity subject languages entities] :as opts}]
  (let [page-component (or (get pages page)
                           (throw (ex-info
                                    (str "No component for page: " page)
                                    opts)))
        state' #?(:clj (assoc @shared/state :languages languages)
                  :cljs (rum/react shared/state))
        languages'     (:languages state')
        comments       (translate-comments languages')
        synset-weights (:synset-weights (meta entity))
        details?       (or (get state' :details?)
                           (get opts :details?))
        entity-label*  (partial entity-label (if details?
                                               long-label-keys
                                               short-label-keys))
        ;; Rejoin entities with subject (split for performance reasons)
        entities'      (assoc entities subject entity)
        ;; Merge frontend state and backend state into a complete product.
        opts'          (assoc (merge opts state')
                         :comments comments
                         :k->label (update-vals entities' entity-label*)
                         :synset-weights synset-weights)
        [prefix _ _] (resolve-names opts')
        prefix'        (or prefix (some-> entity
                                          :vann/preferredNamespacePrefix
                                          symbol))]
    [:<>
     ;; TODO: make horizontal when screen size/aspect ratio is different?
     [:nav {:class ["prefix" (prefix/prefix->class (if (= page "markdown")
                                                     'dn
                                                     prefix'))]}
      (help-arrows page opts')
      (search-form opts')
      [:a.title {:title (i18n/da-en languages
                          "Gå til forsiden"
                          "Go to the front page")
                 :href  (shared/page-href "frontpage")}
       "DanNet"]
      (language-select languages')
      [:button.synset-details {:class    (when details?
                                           "toggled")
                               :title    (if details?
                                           "Show fewer details"
                                           "Show more details")
                               :on-click (fn [e]
                                           (.preventDefault e)
                                           (swap! shared/state update :details? not))}]]
     [:div#content {:class #?(:clj  ""
                              :cljs (if (and *hydrated*
                                             (not-empty (:fetch opts')))
                                      "fetching"
                                      ""))}
      (loader)
      [:main
       (page-component opts')]
      [:hr]
      (page-footer opts')]]))

(comment
  (_hiccup->title (md/->hiccup (slurp "pages/about-da.md")))
  (_hiccup->title nil)
  #_.)