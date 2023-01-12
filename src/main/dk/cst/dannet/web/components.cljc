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
            #?(:clj [better-cond.core :refer [cond]])
            #?(:clj [clojure.core.memoize :as memo])
            #?(:cljs [lambdaisland.uri :as uri])
            #?(:cljs [reitit.frontend.history :as rfh])
            #?(:cljs [reitit.frontend.easy :as rfe]))
  #?(:cljs (:require-macros [better-cond.core :refer [cond]]))
  (:refer-clojure :exclude [cond])
  #?(:clj (:import [clojure.lang Named])))

;; TODO: superfluous DN:A4-ark http://localhost:3456/dannet/data/synset-48300
;; TODO: empty synset? http://localhost:3456/dannet/data/synset-3290
;; TODO: owl:	versionInfo	[TaggedValue: f, 1.1] http://localhost:3456/dannet/external?subject=%3Chttp://www.w3.org/ns/lemon/ontolex%3E
;; TODO: lots of unknown TaggedValues http://localhost:3456/dannet/external?subject=%3Chttp%3A%2F%2Fwww.ontologydesignpatterns.org%2Fcp%2Fowl%2Fsemiotics.owl%3E
;; TODO: empty synset http://localhost:3456/dannet/data/synset-47272
;; TODO: equivalent class empty http://localhost:3456/dannet/external/semowl/InformationEntity
;; TODO: empty definition http://0.0.0.0:3456/dannet/data/synset-42955

(def sense-label
  "On matches returns the vector: [s word rest-of-s sub mwe]."
  #"([^_<>]+)(_((?:§|\d)[^_ ]+)( .+)?)?")

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

(rum/defc rdf-uri-hyperlink
  [uri]
  [:a.rdf-uri {:href (if (or (str/starts-with? uri prefix/dannet-root)
                             (str/starts-with? uri prefix/download-root))
                       (prefix/uri->path uri)
                       (prefix/resource-path (prefix/uri->rdf-resource uri)))}
   (break-up-uri uri)])

(defn- float-str
  "Coerces `x` into a floating point number value string."
  [x]
  (let [s (str (cond
                 (double? x) x
                 (number? x) (double x)
                 (string? x) #?(:clj  (parse-double x)
                                :cljs (js/parseFloat x))))]
    (if (str/ends-with? s ".0")
      (subs s 0 (- (count s) 2))
      s)))

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

(defn transform-val
  "Performs convenient transformations of `v`, optionally informed by `opts`."
  ([v {:keys [attr-key languages k->label entity details?] :as opts}]
   (cond
     ;; Transformations of non-strings
     ;; TODO: properly implement date parsing
     (inst? v)
     (let [s (str v)]
       [:time {:date-time s} s])

     ;; Transformations of strings ONLY from here on
     :when-let [s (not-empty (str/trim (str v)))]

     (= attr-key :dns/inherited)
     (let [[_ prefix-str local-name] (re-matches prefix/qname-re s)
           resource  (keyword prefix-str local-name)
           labels    (get k->label resource)
           label     (i18n/select-label languages labels)
           css-class (prefix/prefix->class (symbol prefix-str))]
       [:span.emblem {:class css-class}
        (prefix-elem (symbol prefix-str))
        (str label)])

     (= attr-key :vann/preferredNamespacePrefix)
     (prefix-elem (symbol s))

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

     :let [[_ word _ sub mwe] (re-matches sense-label s)]

     word
     [:<> word [:sub sub] mwe]

     rdf-resource
     (rdf-uri-hyperlink uri)

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
  [resource {:keys [languages k->label] :as opts}]
  (if (keyword? resource)
    (let [labels (get k->label resource)
          label  (i18n/select-label languages labels)]
      [:a {:href  (prefix/resolve-href resource)
           :title (name resource)
           :lang  (i18n/lang label)
           :class (or (prefix/prefix->class (symbol (namespace resource))) "")}
       (or (transform-val label opts)
           (name resource))])
    (let [local-name (prefix/guess-local-name resource)]
      [:a {:href  (prefix/resource-path resource)
           :title local-name
           :class "unknown"}
       local-name])))

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

(defn- hide-prefix?
  "Whether to hide the value column `prefix` according to its context `opts`."
  [prefix {:keys [attr-key entity] :as opts}]
  (or (= :rdf/value attr-key)
      ;; TODO: don't hardcode ontologicalType (get from input config instead)
      (= :dns/ontologicalType attr-key)
      (and (symbol? prefix)
           (or (and (keyword? attr-key)
                    (= prefix (-> attr-key namespace symbol)))
               (and (keyword? (:subject (meta entity)))
                    (= prefix (-> entity meta :subject namespace symbol)))))))

(rum/defc prefix-elem
  "Visual representation of a `prefix` based on its associated symbol.

  If context `opts` are provided, the `prefix` is assumed to be in the value
  column and will potentially be hidden according to the provided context."
  ([prefix]
   (cond
     (symbol? prefix)
     [:span.prefix {:title (prefix/prefix->uri prefix)
                    :class (prefix/prefix->class prefix)}
      (str prefix) [:span.prefix__sep ":"]]

     (string? prefix)
     [:span.prefix {:title (prefix/guess-ns prefix)
                    :class "unknown"}
      "???"]))
  ([prefix opts]
   (if (hide-prefix? prefix opts)
     [:span.hidden (prefix-elem prefix)]
     (prefix-elem prefix))))

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
       (prefix-elem (symbol (namespace v)) opts)
       (anchor-elem v opts)])

    ;; Display blank resources as inlined tables.
    (map? v)
    [:td (if (= v (select-keys v [:rdf/value v]))
           (let [x (i18n/select-str languages (:rdf/value v))]
             (if (coll? x)
               (into [:<>] (for [s x]
                             [:section.text {:lang (i18n/lang s)} (str s)]))
               [:section.text {:lang (i18n/lang x)} (str x)]))
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
    (let [prefix (symbol (namespace item))]
      [:li
       (prefix-elem prefix opts)
       (anchor-elem item opts)])

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

(rum/defc no-entity-data
  [languages rdf-uri]
  ;; TODO: should be more intelligent than a hardcoded value
  (if (= languages ["da" "en"])
    [:section.text
     [:p {:lang "da"}
      "Der er desværre intet data som beskriver denne "
      [:abbr {:title "Resource Description Framework"}
       "RDF"]
      "-ressource i DanNet."]
     [:p {:lang "da"}
      "Kunne du i stedet for tænke dig at besøge webstedet "
      [:a {:href rdf-uri} (break-up-uri rdf-uri)]
      " i din browser?"]]
    [:section.text
     [:p {:lang "en"}
      "There is unfortunately no data describing this "
      [:abbr {:title "Resource Description Framework"}
       "RDF"]
      " resource in DanNet."]
     [:p {:lang "en"}
      "Would you instead like to visit the website "
      [:a {:href rdf-uri} (break-up-uri rdf-uri)]
      " in your browser?"]]))

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
            [:div.rdf-uri {:key rdf-uri} (break-up-uri rdf-uri)]
            local-name))]
       (when label-lang
         [:sup label-lang])]
      (when-not uri-only?
        (if-let [uri-prefix (and prefix (prefix/prefix->uri prefix))]
          [:div.rdf-uri
           [:span.rdf-uri__prefix {:key uri-prefix}
            (break-up-uri uri-prefix)]
           [:span.rdf-uri__name {:key local-name}
            (break-up-uri local-name)]]
          [:div.rdf-uri {:key rdf-uri} (break-up-uri rdf-uri)]))]
     (if (empty? entity)
       (no-entity-data languages rdf-uri)
       (for [[title ks] (section/page-sections entity)]
         (when-let [subentity (-> (ordered-subentity opts ks entity)
                                  (dissoc label-key)
                                  (not-empty))]
           [:section {:key (or title :no-title)}
            (when title [:h2 (str (i18n/select-label languages title))])
            (attr-val-table (assoc opts :inherited inherited) subentity)])))
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

;; TODO: handle other methods (only handles GET for now)
(defn on-submit
  "Generic function handling form submit events in Rum components."
  [e]
  #?(:cljs (let [action    (.. e -target -action)
                 query-str (-> (.. e -target -elements)
                               (form-elements->query-params)
                               (uri/map->query-string))
                 url       (str action (when query-str
                                         (str "?" query-str)))]
             (.preventDefault e)
             (js/document.activeElement.blur)
             (navigate-to url))))

(defn select-text
  "Select text in the target that triggers `e` with a small delay to bypass
  browser's other text selection logic."
  [e]
  #?(:cljs (js/setTimeout #(.select (.-target e)) 50)))

;; TODO: abort any on-going fetches as a first step
;;       https://developer.mozilla.org/en-US/docs/Web/API/AbortController
(defn search-completion
  "An :on-change handler for search autocompletion."
  [e]
  #?(:clj  nil
     :cljs (let [s                (.-value (.-target e))
                 path             [:search :completion s]
                 autocomplete-url "/dannet/autocomplete"]
             (when-not (get-in @shared/state path)
               (.then (shared/fetch autocomplete-url {:query-params {:s s}})
                      #(do
                         (shared/clear-fetch autocomplete-url)
                         (when-let [v (not-empty (:body %))]
                           (swap! shared/state assoc-in path v)
                           (swap! shared/state assoc-in [:search :s] s))))))))

(rum/defc option
  [v]
  [:option {:value v}])

;; TODO: language localisation
(rum/defc search-form
  [{:keys [lemma search] :as opts}]
  [:form {:role      "search"
          :action    prefix/search-path
          :on-submit on-submit
          :method    "get"}
   [:input {:type          "search"
            :list          "completion"
            :name          "lemma"
            :title         "Search for synsets"
            :placeholder   "search term"
            :on-focus      select-text
            :on-change     search-completion
            :auto-complete "off"
            :default-value (or lemma "")}]
   (let [{:keys [completion s]} search]
     [:datalist {:id "completion"}
      (for [v (get completion s)]
        (rum/with-key (option v) v))])])

(rum/defc search-page
  [{:keys [languages lemma search-results] :as opts}]
  [:article.search
   [:header
    [:h1 (str "\"" lemma "\"")]]
   (if (empty? search-results)
     [:p "No search-results."]
     (for [[k entity] search-results]
       (let [{:keys [k->label]} (meta entity)]
         (rum/with-key (attr-val-table {:languages languages
                                        :k->label  k->label}
                                       entity)
                       k))))])

(def pages
  "Mapping from page data metadata :page key to the relevant Rum component."
  {:entity entity-page
   :search search-page})

(def data->page
  "Get the page referenced in the page data's metadata."
  (comp :page meta))

;; TODO: eventually support LangStr for titles too
(def data->title
  (comp :title meta))

(rum/defc page-footer
  [{:keys [languages] :as opts}]
  [:footer
   (i18n/da-en languages
     [:p {:lang "da"}
      "© 2022 " [:a {:href "https://cst.ku.dk"}
                 "Center for Sprogteknologi"]
      ", " [:abbr {:title "Københavns Universitet"}
            "KU"] "."]
     [:p {:lang "en"}
      "© 2022 " [:a {:href "https://cst.ku.dk/english"}
                 "Centre for Language Technology"]
      ", " [:abbr {:title "University of Copenhagen"}
            "KU"] "."])])

;; TODO: store in cookie?
(rum/defc language-select < rum/reactive
  [server-languages]
  (let [default (first (or (:languages (rum/react shared/state))
                           server-languages))]
    [:select.language {:title         "Language preference"
                       :default-value default
                       :on-change     (fn [e]
                                        (let [v (.-value (.-target e))]
                                          (swap! shared/state assoc :languages
                                                 (i18n/lang-prefs v))))}
     (when (not (#{"en" "da"} default))
       [:option {:value default} (str default " (browser default)")])
     [:option {:value "en"} "\uD83C\uDDEC\uD83C\uDDE7 English"]
     [:option {:value "da"} "\uD83C\uDDE9\uD83C\uDDF0 Dansk"]]))

(rum/defc page-shell < rum/reactive
  [page {:keys [languages entity] :as opts}]
  #?(:cljs (when-not (:languages @shared/state)
             (swap! shared/state assoc :languages languages)))
  (let [page-component (get pages page)
        state' #?(:clj {} :cljs (rum/react shared/state))
        comments       {:comments (translate-comments languages)}
        opts'          (merge opts state' comments)
        [prefix _ _] (resolve-names opts')
        prefix'        (or prefix (some-> entity
                                          :vann/preferredNamespacePrefix
                                          symbol))
        details?       (:details? opts')]
    [:<>
     ;; TODO: make horizontal when screen size/aspect ratio is different?
     [:nav {:class ["prefix" (prefix/prefix->class prefix')]}
      (search-form opts')
      [:a.title {:title "Frontpage"
                 :href  "/"}
       "DanNet"]
      (language-select languages)
      [:button.synset-details {:class    (when details?
                                           "toggled")
                               :title    (if details?
                                           "Show fewer details"
                                           "Show more details")
                               :on-click (fn [e]
                                           (.preventDefault e)
                                           (swap! shared/state update :details? not))}]
      [:a.github {:title "The source code for DanNet is available on Github"
                  :href  "https://github.com/kuhumcst/DanNet"}]]
     [:div#content
      [:main
       (page-component opts')]
      [:hr]
      (page-footer opts')]]))

(comment
  (canonical ["legemsdel_§1" "kropsdel"])                   ; identical
  (canonical ["flab_§1" "flab_§1a" "gab_2§1" "gab_2§1a"
              "kværn_§3" "mule_1§1a" "mund_§1"])            ; ["flab_§1" "mund_§1"]
  #_.)