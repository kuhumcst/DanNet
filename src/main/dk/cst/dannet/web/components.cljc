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
            #?(:clj [better-cond.core :refer [cond]])
            #?(:clj [clojure.core.memoize :as memo])
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

(def sense-label
  "On matches returns the vector: [s word rest-of-s sub mwe]."
  #"([^_<>]+)(_((?:§|\d|\()[^_ ]+)( .+)?)?")

(def synset-sep
  #"\{|;|\}")

(def omitted
  "…")

(defn- sort-by-entry
  "Divide `sense-labels` into partitions of [s sub] according to DSL entry IDs."
  [sense-labels]
  (->> (map (partial re-matches sense-label) sense-labels)
       (map (fn [[s _ _ sub _]]
              (cond
                (nil? sub)                                  ; uncertain = keep
                [s "0§1"]

                (and sub (str/starts-with? sub "§"))        ; normalisation
                [s (str "0" sub)]

                :else
                [s sub])))
       (sort-by second)
       (partition-by second)))

(defn canonical
  "Return only canonical `sense-labels` using the DSL entry IDs as a heuristic.

  Input labels are sorted into partitions with the top partition returned.
  In cases where only a single label would be returned, the second-highest
  partition is concatenated, provided it contains at most 2 additional labels."
  [sense-labels]
  (if (= 1 (count sense-labels))
    sense-labels
    (let [[first-partition second-partition] (sort-by-entry sense-labels)]
      (mapv first (if (and (= (count first-partition) 1)
                           (<= (count second-partition) 2))
                    (concat first-partition second-partition)
                    first-partition)))))

;; Memoization unbounded in CLJS since core.memoize is CLJ-only!
#?(:clj  (alter-var-root #'canonical #(memo/lu % :lu/threshold 1000))
   :cljs (def only-canonical (memoize only-canonical)))

(defn sense-labels
  "Split a `synset` label into sense labels. Work for both old and new formats."
  [sep label]
  (->> (str/split label sep)
       (into [] (comp
                  (remove empty?)
                  (map str/trim)))))

(def rdf-resource-re
  #"^<(.+)>$")

(defn break-up-uri
  "Place word break opportunities into a potentially long `uri`."
  [uri]
  (into [:<>] (for [part (re-seq #"[^\./]+|[\./]+" uri)]
                (if (re-matches #"[^\./]+" part)
                  [:<> part [:wbr]]
                  part))))

(defn- internal-path
  [uri]
  (when (or (str/starts-with? uri prefix/dannet-root)
            (str/starts-with? uri prefix/schema-root)
            (str/starts-with? uri prefix/export-root))
    (prefix/uri->path uri)))

(defn capture-uri
  "Ensure that an internal `uri` will point to a local path, while external URIs
  are resolved as look-ups of external RDF resources."
  [uri]
  (or (internal-path uri)
      (prefix/resource-path (prefix/uri->rdf-resource uri))))

(rum/defc rdf-uri-hyperlink
  [uri]
  [:a.rdf-uri {:href (capture-uri uri)}
   (break-up-uri uri)])

(rum/defc external-hyperlink
  [uri]
  [:a {:href (or (internal-path uri)
                 uri)}
   (break-up-uri uri)])

(defn- choose-sense-labels
  "Choose which sense labels to show from a `synset-label` based on `opts`."
  [synset-label {:keys [details?] :as opts}]
  (if details?
    (sense-labels synset-sep synset-label)
    (let [sense-labels     (sense-labels synset-sep synset-label)
          canonical-labels (canonical sense-labels)]
      (if (= (count sense-labels)
             (count canonical-labels))
        sense-labels
        (conj canonical-labels omitted)))))

(declare prefix-elem)
(declare anchor-elem)

(defn rdf-datatype?
  [x]
  (and (map? x) (:value x) (:uri x)))

(defn transform-val
  "Performs convenient transformations of `v`, optionally informed by `opts`."
  ([v {:keys [attr-key languages k->label entity details?] :as opts}]
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
              (for [label (choose-sense-labels s opts)]
                (if-let [[_ word _ sub mwe] (re-matches sense-label label)]
                  [:<>
                   (if (= word omitted)
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
     (rdf-uri-hyperlink uri)

     ;; TODO: match is too broad, should be limited somewhat
     (or (get #{:ontolex/sense :ontolex/lexicalizedSense} attr-key)
         (= (:rdf/type entity) #{:ontolex/LexicalSense}))
     (let [[_ word _ sub mwe] (re-matches sense-label s)]
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
    (let [inherited       (some->> (get k->label resource) (prefix/qname->kw))
          inherited-label (get k->label inherited)
          prefix          (when inherited
                            (symbol (namespace inherited)))
          opts'           (-> opts
                              (assoc-in [:k->label resource] inherited-label)
                              (assoc :class (get prefix/prefix->class prefix)))]
      [:<>
       (prefix-elem (or prefix (symbol (namespace resource))) opts')
       (anchor-elem resource opts')])

    ;; The generic case just displays the prefix badge + the hyperlink.
    :else
    [:<>
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
  (or (= :rdf/value attr-key)
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

(rum/defc val-cell
  "A table cell of an 'attr-val-table' containing a single `v`. The single value
  can either be a literal or an inlined table (i.e. a blank RDF node)."
  [{:keys [languages] :as opts} v]
  (cond
    (keyword? v)
    (if (empty? (name v))
      ;; Handle cases such as :rdfs/ which have been keywordised by Aristotle.
      [:td
       (rdf-uri-hyperlink (-> v namespace symbol prefix/prefix->uri))]
      [:td.attr-combo                                       ; fixes alignment
       (rdf-resource-hyperlink v opts)])

    ;; Display blank resources as inlined tables.
    (map? v)
    [:td
     (cond
       (rdf-datatype? v)
       (transform-val v)

       (= v (select-keys v [:rdf/value v]))
       (let [x (i18n/select-str languages (:rdf/value v))]
         (if (coll? x)
           (into [:<>] (for [s x]
                         [:section.text {:lang (i18n/lang s)} (str s)]))
           [:section.text {:lang (i18n/lang x)} (str x)]))

       (contains? (:rdf/type v) :rdf/Bag)
       (let [ns->resources (-> (->> (dissoc v :rdf/type)
                                    (filter (comp numbered? first))
                                    (mapcat second)
                                    (group-by namespace))
                               (update-vals sort)
                               (update-keys symbol))
             resources     (->> (sort ns->resources)
                                (vals)
                                (apply concat))]
         ;; TODO: hover effect like synsets?
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
       (attr-val-table opts v))]

    ;; Doubly inlined tables are omitted entirely.
    (nil? v)
    [:td.omitted {:lang "en"} "(details omitted)"]

    :else
    (let [s (i18n/select-str languages v)]
      [:td {:lang (i18n/lang s)} (transform-val s opts)])))

(defn sort-keyfn
  "Keyfn for sorting keywords and other content based on a `k->label` mapping.
  Returns vectors so that identical labels are sorted by keywords secondly."
  [{:keys [languages k->label] :as opts}]
  (fn [item]
    (let [k (if (map-entry? item) (first item) item)]
      [(str (i18n/select-label languages (get k->label k)))
       (str item)])))

(rum/defc list-item
  "A list item element of a 'list-cell'."
  [opts item]
  (cond
    (keyword? item)
    [:li (rdf-resource-hyperlink item opts)]

    ;; TODO: handle blank resources better?
    ;; Currently not including these as they seem to
    ;; be entirely garbage temp data, e.g. check out
    ;; http://0.0.0.0:3456/dannet/2022/external/ontolex/LexicalSense
    (symbol? item)
    nil #_[:li (attr-val-table opts (meta item))]

    :else
    [:li {:lang (i18n/lang item)}
     (transform-val item opts)]))

(rum/defc list-cell-coll-items
  [opts coll]
  (let [sort-key (sort-keyfn opts)]
    (for [item (sort-by sort-key coll)]
      (rum/with-key (list-item opts item) (sort-key item)))))

;; A Rum-controlled version of the <details> element which only renders content
;; if the containing <details> element is open. This circumvents the default
;; behaviour which is to pre´´render the content in the DOM, but keep it hidden.
;; Some of the more well-connected synsets take AGES to load without this fix!
(rum/defcs react-details < (rum/local false ::open)
  [state summary content]
  (let [open (::open state)]
    [:details {:on-toggle #(swap! open not)
               :open      @open}
     (when summary summary)
     (when @open content)]))

(rum/defc list-cell-coll
  "A list of ordered content; hidden by default when there are too many items."
  [opts coll]
  (let [amount     (count coll)
        list-items (list-cell-coll-items opts coll)]
    (cond
      (<= amount 5)
      [:ol list-items]

      (< amount 100)
      (react-details [:summary ""] [:ol list-items])

      (< amount 1000)
      (react-details [:summary ""] [:ol.three-digits list-items])

      (< amount 10000)
      (react-details [:summary ""] [:ol.four-digits list-items])

      :else
      (react-details [:summary ""] [:ol.five-digits list-items]))))

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

(defn translate-comments
  [languages]
  {:inference   (i18n/da-en languages
                  "helt eller delvist logisk udledt"
                  "fully or partially logically inferred")
   :inheritance (i18n/da-en languages
                  "helt eller delvist  nedarvet fra hypernym"
                  "fully or partially inherited from hypernym")})

(rum/defc attr-val-table
  "A table which lists attributes and corresponding values of an RDF resource."
  [{:keys [inherited inferred comments] :as opts} subentity]
  [:table {:class "attr-val"}
   [:colgroup
    [:col]                                                  ; attr prefix
    [:col]                                                  ; attr local name
    [:col]]
   [:tbody
    (for [[k v] subentity
          :let [prefix        (if (keyword? k)
                                (symbol (namespace k))
                                k)
                inherited?    (get inherited k)
                inferred?     (get inferred k)
                opts+attr-key (assoc opts :attr-key k)]]
      [:tr {:key   k
            :class [(when inferred? "inferred")
                    (when inherited? "inherited")]}
       [:td.attr-prefix
        ;; TODO: link to definition below?
        (when inferred?
          [:span.marker {:title (:inference comments)} "∴"])
        (when inherited?
          [:span.marker {:title (:inheritance comments)} "†"])
        (prefix-elem prefix)]
       [:td.attr-name (anchor-elem k opts)]
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
          (transform-val v opts+attr-key)])])]])

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
            (sort-by (sort-keyfn opts)
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
  [:rdfs/label
   :dc/title
   :dc11/title
   :foaf/name])

(defn entity->label-key
  "Return :rdfs/label or another appropriate key for labeling `entity`."
  [entity]
  (loop [[candidate & candidates] label-keys]
    (if (get entity candidate)
      candidate
      (when candidates
        (recur candidates)))))

(rum/defc entity-page
  [{:keys [languages comments subject inferred entity k->label] :as opts}]
  (let [[prefix local-name rdf-uri] (resolve-names opts)
        label-key  (entity->label-key entity)
        label      (i18n/select-label languages (get entity label-key))
        label-lang (i18n/lang label)
        a-titles   [#voc/lstr"Visit this location directly@en"
                    #voc/lstr"Besøg denne lokation direkte@da"]
        inherited  (->> (shared/setify (:dns/inherited entity))
                        (map (comp prefix/qname->kw k->label))
                        (set))
        uri-only?  (and (not label) (= local-name rdf-uri))]
    [:article
     [:header
      [:h1
       (prefix-elem prefix)
       [:span {:title (if label
                        (prefix/kw->qname label-key)
                        subject)
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
                                (dissoc label-key)
                                (not-empty))]
         [:section {:key (or title :no-title)}
          (when title [:h2 (str (i18n/select-label languages title))])
          (attr-val-table (assoc opts :inherited inherited) subentity)]))
     (when (not-empty inferred)
       [:p.note [:strong "∴ "] (:inference comments)])
     (when (not-empty inherited)
       [:p.note [:strong "† "] (:inheritance comments)])]))

(defn- form-elements->query-params
  "Retrieve a map of query parameters from HTML `form-elements`."
  [form-elements]
  (into {} (for [form-element form-elements]
             (when (not-empty (.-name form-element))
               [(.-name form-element) (.-value form-element)]))))

(defn- navigate-to
  "Navigate to internal `url` using reitit."
  [url]
  #?(:cljs (let [history @rfe/history]
             (.pushState js/window.history nil "" (rfh/-href history url))
             (rfh/-on-navigate history url))))

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
             (navigate-to url))))

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
                         (when-let [v (not-empty (:body %))]
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
               :placeholder           "lemma"
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
  [{:keys [languages lemma search-results] :as opts}]
  [:article.search
   [:header
    [:h1 (str "\"" lemma "\"")]]
   (if (empty? search-results)
     [:p (i18n/da-en languages
           "Ingen resultater kunne findes for dette lemma."
           "No results could be found for this lemma.")]
     (for [[k entity] search-results]
       (let [{:keys [k->label]} (meta entity)]
         (rum/with-key (attr-val-table {:languages languages
                                        :k->label  k->label}
                                       entity)
                       k))))])

(def md->hiccup
  (memoize md/->hiccup))

(defn _hiccup->title
  "Find the title string located in the first :h1 element in `hiccup`."
  [hiccup]
  (->> (tree-seq vector? rest hiccup)
       (reduce (fn [_ x]
                 (when (= :h1 (first x))
                   (reduced (last x))))
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

(defn x-header
  "Get the custom `header` in the HTTP `headers`.

  See also: dk.cst.dannet.web.resources/x-headers"
  [headers header]
  ;; Interestingly (hahaha) fetch seems to lower-case all keys in the headers.
  (get headers (str "x-" (str/lower-case (name header)))))

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
       "© 2023, "
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
       "© 2023, "
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
                 #?(:cljs (let [v (-> (.-target e)
                                      (.-value)
                                      (not-empty)
                                      (i18n/lang-prefs))]
                            (shared/api "/cookies" {:method :put
                                                    :body   {:languages v}})
                            (swap! shared/state assoc :languages v))))}
   [:option {:value ""} "\uD83C\uDDFA\uD83C\uDDF3 Other"]
   [:option {:value "en"} "\uD83C\uDDEC\uD83C\uDDE7 English"]
   [:option {:value "da"} "\uD83C\uDDE9\uD83C\uDDF0 Dansk"]])

(rum/defc page-shell < rum/reactive
  [page {:keys [entity languages] :as opts}]
  (let [page-component (get pages page)
        state' #?(:clj {:languages languages}
                  :cljs (rum/react shared/state))
        languages'     (:languages state')
        comments       {:comments (translate-comments languages')}
        opts'          (merge opts state' comments)
        [prefix _ _] (resolve-names opts')
        prefix'        (or prefix (some-> entity
                                          :vann/preferredNamespacePrefix
                                          symbol))
        details?       (:details? opts')]
    [:<>
     ;; TODO: make horizontal when screen size/aspect ratio is different?
     [:nav {:class ["prefix" (prefix/prefix->class (if (= page "markdown")
                                                     'dn
                                                     prefix'))]}
      (search-form opts')
      [:a.title {:title "Frontpage"
                 :href  (shared/page-href "about")}
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
     [:div#content
      [:main
       (page-component opts')]
      [:hr]
      (page-footer opts')]]))

(comment
  (_hiccup->title (md/->hiccup (slurp "pages/about-da.md")))
  (_hiccup->title nil)

  (canonical ["legemsdel_§1" "kropsdel"])                   ; identical
  (canonical ["flab_§1" "flab_§1a" "gab_2§1" "gab_2§1a"
              "kværn_§3" "mule_1§1a" "mund_§1"])            ; ["flab_§1" "mund_§1"]
  #_.)