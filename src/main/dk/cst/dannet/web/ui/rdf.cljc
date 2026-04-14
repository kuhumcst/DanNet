(ns dk.cst.dannet.web.ui.rdf
  (:require [clojure.string :as str]
            [rum.core :as rum]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.web.i18n :as i18n]
            #?(:clj  [dk.cst.dannet.web.ui.error :as error]
               :cljs [dk.cst.dannet.web.ui.error :as error :include-macros true])
            #?(:clj [better-cond.core :refer [cond]]))
  #?(:clj (:import [org.apache.jena.datatypes BaseDatatype$TypedValue]))
  #?(:cljs (:require-macros [better-cond.core :refer [cond]]))
  (:refer-clojure :exclude [cond]))

(def expandable-list-cutoff
  8)

(def uri-pattern
  #"://|[^\./\?&=#%]+|%[0-9A-Fa-f]{2}|[\./\?&=#]")

(defn break-up-uri
  "Place word break opportunities into a potentially long `uri`.

  Tokenizes the URI into path separators, query string delimiters, %-encoded
  sequences, and plain text by inserting <wbr> before key delimiters: / ? & = #.
  The :// scheme separator is kept atomic to avoid awkward mid-scheme breaks."
  [uri]
  (into [:<>] (for [part (re-seq uri-pattern uri)]
                (case part
                  ("/" "?" "&" "=" "#") [:<> [:wbr] part]
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

(defn- local-entity-prefix?
  "Is this `prefix` the same as the local entity in `opts`?"
  [prefix {:keys [attr-key entity subject] :as opts}]
  (let [subj (or subject (:subject (meta entity)))]
    (or (and (keyword? attr-key)
             (= prefix (-> attr-key namespace symbol)))
        (and (keyword? subj)
             (= prefix (-> subj namespace symbol))))))


;; TODO: don't hide when detail-level is :high?
(defn- hide-prefix?
  "Whether to hide the value column `prefix` according to its context `opts`."
  [prefix {:keys [attr-key] :as opts}]
  (or (= :rdf/about attr-key)
      ;; TODO: don't hardcode ontologicalType (get from input config instead)
      (= :dns/ontologicalType attr-key)
      (and (not= :dns/inherited attr-key)                   ; special case
           (local-entity-prefix? prefix opts))))

(rum/defc prefix-badge
  "Visual representation of a `prefix` based on its associated symbol.

  If context `opts` are provided, the `prefix` is assumed to be in the value
  column and will potentially be hidden according to the provided context."
  ([prefix]
   (prefix-badge prefix nil))
  ([prefix {:keys [independent-prefix] :as opts}]
   (cond
     (symbol? prefix)
     (let [prefix-str (str prefix)
           long?      (> (count prefix-str) 4)
           classes    (cond-> []
                        (prefix/prefix->class prefix) (conj (prefix/prefix->class prefix))
                        long? (conj "truncatable")
                        independent-prefix (conj "independent")
                        (and (not independent-prefix)
                             (hide-prefix? prefix opts)) (conj "hidden"))]
       [:span.prefix (cond-> {:title (prefix/prefix->uri prefix)}
                       (seq classes) (assoc :class classes)
                       long? (assoc :tab-index 0))
        prefix-str [:span.prefix__sep ":"]])

     (string? prefix)
     [:span.prefix {:title (prefix/guess-ns prefix)
                    :class "unknown"}
      "???"])))

(def rdf-resource-re
  #"^<(.+)>$")

(defn- transform-rdf-datatype
  "Render an RDF datatype value as a span with title/datatype attributes."
  [{:keys [uri value]}]
  [:span {:title    uri
          :datatype uri}
   value])

(defn- transform-val*
  "Implementation of transform-val without error handling."
  ([v {:keys [attr-key entity] :as opts}]
   (cond
     ;; RDF typed literals: TypedValue on backend, {:uri :value} map on frontend.
     #?@(:clj
         [(instance? BaseDatatype$TypedValue v)
          (transform-rdf-datatype
            {:uri   (.-datatypeURI ^BaseDatatype$TypedValue v)
             :value (.-lexicalValue ^BaseDatatype$TypedValue v)})]
         :cljs
         [(shared/rdf-datatype? v)
          (transform-rdf-datatype v)])

     ;; Transformations of non-strings
     ;; TODO: properly implement date parsing
     (inst? v)
     (let [s (str v)]
       [:time {:date-time s} s])

     ;; Transformations of strings ONLY from here on
     :when-let [s (not-empty (str/trim (str v)))]

     (= attr-key :vann/preferredNamespacePrefix)
     (prefix-badge (symbol s) {:independent-prefix true})

     :let [[rdf-resource uri] (re-find rdf-resource-re s)]
     (re-matches #"\{.+\}" s)
     [:span.set
      [:span.set__left-bracket]
      (into [:span.set__content]
            (interpose
              [:span.subtle " • "]                          ; semicolon->bullet
              (for [label (shared/sense-labels shared/synset-sep s)]
                (if-let [[_ word _ sub mwe] (re-matches shared/sense-label label)]
                  [:<>
                   [:span.set__word
                    (if (= word shared/omitted)
                      [:span.subtle word]
                      word)
                    ;; Correct for the rare case of an affixed comma.
                    ;; e.g. http://localhost:3456/dannet/data/synset-7290
                    (when sub
                      (if (str/ends-with? sub ",")
                        [:<> [:sub (subs sub 0 (dec (count sub)))] ","]
                        [:sub sub]))]
                   mwe]
                  label))))
      [:span.set__right-bracket]]

     rdf-resource
     (rdf-uri-hyperlink uri opts)

     (or (get #{:ontolex/sense :ontolex/lexicalizedSense} attr-key)
         (= (:rdf/type entity) :ontolex/LexicalSense))
     (let [[_ word _ sub mwe] (re-matches shared/sense-label s)]
       [:<> word [:sub sub] mwe])

     (re-matches #"https?://[^\s]+" s)
     (break-up-uri s)

     (re-find #"\n" s)
     (into [:<>] (interpose [:br] (str/split s #"\n")))

     ;; Context-free sense label rendering for e.g. SPARQL result tables where
     ;; no attr-key or entity is available.
     ;; TODO: could be tightened by threading the resource keyword through and
     ;;       checking for :dn/sense-* instead of relying on string matching alone.
     :let [[_ word _ sub mwe] (when-not attr-key (re-matches shared/sense-label s))]
     sub [:<> word [:sub sub] mwe]

     :else s))
  ([s]
   (transform-val* s nil)))

(defn transform-val
  "Performs convenient transformations of `v`, optionally informed by `opts`.
  
  Returns nil for nil/empty input to allow fallback via 'or'."
  ([v opts]
   (when (some? v)
     (error/try-render-with [e v opts]
       (transform-val* v opts)
       [:span.render-error {:title (ex-message e)} (str v)])))
  ([v]
   (transform-val v nil)))

;; TODO: figure out how to prevent line break for lang tag similar to h1
(rum/defc entity-link
  "Entity hyperlink from a `resource` and (optionally) a string label `s`."
  [resource {:keys [languages k->label class link-href attr-key] :as opts}]
  (if (keyword? resource)
    (let [labels (get k->label resource)
          label  (i18n/select-label languages labels)
          prefix (symbol (namespace resource))
          ;; When rendering the attribute key itself (not a value), remove
          ;; :attr-key from opts to prevent transform-val from applying
          ;; attribute-specific transformations to the label.
          opts'  (if (= resource attr-key)
                   (dissoc opts :attr-key)
                   opts)]
      [:a (cond-> {:href  (or link-href (prefix/resolve-href resource))
                   :title (str prefix ":" (name resource))
                   :lang  (i18n/lang label)
                   :class (or class (get prefix/prefix->class prefix "unknown"))}
            ;; Add RDFa :resource when rendering a value to complete the triple
            ;; begun by the parent [:tr {:property ...}].
            (not= resource attr-key) (assoc :resource (prefix/kw->uri resource)))
       (or (transform-val label opts')
           (name resource))])
    ;; RDF predicates represented as IRIs Since the namespace is unknown,
    ;; we likely have no label data either and do not bother to fetch it.
    ;; See 'rdf-uri-hyperlink' for how objects are represented!
    (let [local-name (prefix/guess-local-name resource)]
      [:a {:href  (or link-href (prefix/resource-path resource))
           :title local-name
           :class "unknown"}
       local-name])))

;; See also 'rdf-uri-hyperlink'.
(rum/defc resource-hyperlink
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
      [:span.qname
       (prefix-badge (or prefix (symbol (namespace resource))) opts')
       (entity-link resource opts')])

    ;; The generic case just displays the prefix badge + the hyperlink.
    :else
    [:span.qname
     (prefix-badge (symbol (namespace resource)) opts)
     (entity-link resource opts)]))

(defn transform-val-coll
  "Performs convenient transformations of `coll` informed by `opts`."
  [coll {:keys [attr-key] :as opts}]
  (cond
    (and (= :dns/ontologicalType attr-key)
         (every? #(and (qualified-ident? %)
                       (= "dnc" (namespace %))) coll))
    (let [vs     (sort-by name coll)
          fv     (first vs)
          prefix (symbol (namespace fv))]
      (for [v vs]
        [:<> {:key v}
         (when-not (= fv v)
           " + ")
         (prefix-badge prefix opts)
         (entity-link v opts)]))))

(defn transform-text
  "Transform language-tagged text `x` with language selection and proper markup.
  Single values render with :lang attribute, multiple values as a :ul list."
  [{:keys [languages] :as opts} x]
  (let [selected (i18n/select-str languages x)]
    (if (coll? selected)
      [:ul
       (for [s selected]
         [:li {:key (str s) :lang (i18n/lang s)}
          (transform-val s opts)])]
      [:span {:lang (i18n/lang selected)}
       (transform-val selected opts)])))

(defn- blank-node*
  [languages attr-key v & vs]
  (let [base-title (i18n/da-en languages "Blank knude" "Blank node")
        title      (if attr-key
                     (str base-title " (" (prefix/kw->qname attr-key) ")")
                     base-title)]
    (into [:div.blank-node
           [:span.marker {:title title} "⦿"]
           v]
          vs)))

(defn blank-node
  "Display blank node based on map `m` in a specialised way based on `opts`."
  [{:keys [table-component languages attr-key] :as opts} m]
  (let [container (partial blank-node* languages attr-key)]
    (cond
      (shared/rdf-datatype? m)
      (container (transform-val m))

      (= (keys m) [:rdf/value])
      (container (transform-text opts (:rdf/value m)))

      ;; Special handling of DanNet sentiment data.
      :let [polarity (some-> (get m :marl/hasPolarity) shared/unwrap)]
      (keyword? polarity)
      (container
        (resource-hyperlink polarity opts)
        (when-let [value (some-> (get m :marl/polarityValue) shared/unwrap)]
          (str " (" value ")")))

      :let [resources (shared/bag->coll m)]
      resources
      (container [:span.set
                  (when (and (every? keyword? resources)
                             (apply = (map namespace resources)))
                    (let [prefix (symbol (namespace (first resources)))]
                      (prefix-badge prefix opts)))
                  [:span.set__left-bracket]
                  (into [:span.set__content]
                        (->> resources
                             (map #(entity-link % opts))
                             (interpose [:span.subtle " • "])))
                  [:span.set__right-bracket]])

      ;; An optional fallback table component with the same function signature.
      ;; It's passed via dependency injection to avoid cyclic ns dependencies.
      ;; NOTE: we don't mark tables as it is clear that they are blank nodes.
      table-component
      (table-component opts m))))

(rum/defc list-item
  "A list item element of a 'list-cell'."
  [opts item]
  (cond
    (keyword? item)
    [:li (resource-hyperlink item opts)]

    (symbol? item)
    (if-let [m (not-empty (meta item))]
      [:li (blank-node opts m)]
      [:li.omitted (str item)])

    :else
    [:li {:lang (i18n/lang item)}
     (transform-val item opts)]))

(rum/defc render-list-items
  [opts coll]
  (for [{:keys [item sort-key]} (shared/sort-by-label-with-keys opts coll)]
    (rum/with-key (error/try-render (list-item opts item)) sort-key)))

;; A Rum-controlled version of the <details> element which only renders content
;; if the containing <details> element is open. This circumvents the default
;; behaviour which is to prerender the content in the DOM, but keep it hidden.
;; Some of the more well-connected synsets take AGES to load without this fix!
(rum/defcs reactive-details < (rum/local false ::open)
  [state summary content]
  (let [open (::open state)]
    [:details {:on-toggle #(swap! open not)
               :open      @open}
     (when summary
       (if (not @open)
         summary
         [:summary ""]))
     (when @open content)]))

(defn- expandable-list*
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
      (render-list-items opts summary-coll)]
     (reactive-details
       [:summary
        (i18n/da-en languages
          (str (count rest-coll) " flere")
          (str (count rest-coll) " more"))]
       [:ol {:class c
             :start 4}
        (render-list-items opts rest-coll)])]))

(defn expandable-list
  [opts coll]
  (expandable-list* opts (take 3 coll) (drop 3 coll)))

(rum/defc list-items
  [opts coll]
  (if (<= (count coll) expandable-list-cutoff)
    [:ol (render-list-items opts coll)]
    (expandable-list opts coll)))

(defn hypernym-chain
  "Render nested `ancestry` in `opts` as arrow-separated hyperlinks.

  Handles multiple hypernyms per synset, creating nested sublists. Respects
  `:detail-level` in `opts` to select full/short labels. When `:subject-label`
  is provided, prepends the subject as first item (not a hyperlink)."
  ([{:keys [ancestry subject-label] :as opts}]
   (if subject-label
     [:ul.hypernym-chain.hypernym-chain--root
      [:li.subject
       (transform-val subject-label opts)
       (hypernym-chain ancestry opts)]]
     (hypernym-chain ancestry opts)))
  ([ancestry {:keys [detail-level] :as opts}]
   (when (seq ancestry)
     (into [:ul.hypernym-chain]
           (for [{:keys [wn/hypernym rdfs/label dns/shortLabel ancestors]} ancestry
                 :let [label (if (= detail-level :high)
                               label
                               (or shortLabel label))]]
             [:li
              (entity-link hypernym (assoc opts :k->label {hypernym label}))
              (hypernym-chain ancestors opts)])))))

(rum/defc resource
  [opts x]
  (cond
    (map? x)
    (blank-node opts x)

    (coll? x)
    (list-items opts x)

    (some? x)
    (resource-hyperlink x opts)))
