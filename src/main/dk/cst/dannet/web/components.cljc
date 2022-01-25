(ns dk.cst.dannet.web.components
  "Shared frontend/backend Rum components."
  (:require [clojure.string :as str]
            [flatland.ordered.map :as fop]
            [rum.core :as rum]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.web.i18n :as i18n]
            #?(:cljs [lambdaisland.uri :as uri])
            #?(:cljs [reitit.frontend.history :as rfh])
            #?(:cljs [reitit.frontend.easy :as rfe])
            #?(:cljs [ont-app.vocabulary.lstr :refer [LangStr]]))
  #?(:clj (:import [ont_app.vocabulary.lstr LangStr])))

(defn invert-map
  [m]
  (into {} (for [[group prefixes] m
                 prefix prefixes]
             [prefix group])))

(def prefix->css-class
  (invert-map
    {"dannet"  #{'dn 'dnc 'dns}
     "w3c"     #{'rdf 'rdfs 'owl}
     "ontolex" #{'ontolex 'skos 'lexinfo}
     "wordnet" #{'wn}}))

(defn with-prefix
  "Return predicate accepting keywords with `prefix`, optionally `except` set."
  [prefix & {:keys [except]}]
  (fn [[k v]]
    (when (keyword? k)
      (and (not (except k))
           (= (namespace k) (name prefix))))))

;; TODO: use sets of langStrings for titles
(def defined-sections
  [[nil [:rdf/type
         :rdfs/label
         :rdfs/comment]]
   ["Lexical information" [:ontolex/writtenRep
                           :skos/definition
                           :lexinfo/partOfSpeech
                           :ontolex/canonicalForm
                           :ontolex/evokes
                           :ontolex/isEvokedBy
                           :ontolex/sense
                           :ontolex/isSenseOf
                           :ontolex/lexicalizedSense
                           :ontolex/isLexicalizedSenseOf
                           :lexinfo/senseExample]]
   ["WordNet relations" (some-fn (with-prefix 'wn :except #{:wn/partOfSpeech})
                                 (comp #{:dns/orthogonalHyponym
                                         :dns/orthogonalHypernym} first))]])

(def sections
  (let [ks-defs     (map second defined-sections)
        in-ks?      (fn [[k v]]
                      (get (set (apply concat (filter coll? ks-defs)))
                           k))
        in-section? (apply some-fn in-ks? (filter fn? ks-defs))]
    (conj defined-sections ["Other attributes" (complement in-section?)])))

(defn- partition-str
  "Partition a string `s` by the character `ch`.

  Works similarly to str/split, except the splits are also kept as parts."
  [ch s]
  (map (partial apply str) (partition-by (partial = ch) s)))

(defn- guess-parts
  [qname]
  (cond
    (re-find #"#" qname)
    (partition-str \# qname)

    (re-find #"/" qname)
    (partition-str \/ qname)))

(defn guess-local-name
  "Given a `qname` with an unknown namespace, attempt to guess the local name."
  [qname]
  (last (guess-parts qname)))

(defn guess-namespace
  "Given a `qname` with an unknown namespace, attempt to guess the namespace."
  [qname]
  (str/join (butlast (guess-parts qname))))

(rum/defc anchor-elem
  "Entity hyperlink from a `resource` and (optionally) a string label `s`."
  ([resource s]
   (if (keyword? resource)
     [:a {:href  (prefix/resolve-href resource)
          :title (name resource)
          :lang  (i18n/lang s)
          :class (prefix->css-class (symbol (namespace resource)))}
      (str (or s (name resource)))]
     (let [qname      (subs resource 1 (dec (count resource)))
           local-name (guess-local-name qname)]
       [:span.unknown {:title local-name}
        local-name])))
  ([resource] (anchor-elem resource nil)))

(rum/defc prefix-elem
  "Visual representation of a `prefix` based on its associated symbol."
  [prefix]
  (if (symbol? prefix)
    [:span.prefix {:title (prefix/prefix->uri prefix)
                   :class (prefix->css-class prefix)}
     (str prefix ":")]
    [:span.prefix {:title (guess-namespace (subs prefix 1 (dec (count prefix))))
                   :class "unknown"}
     "???:"]))

(def inheritance-pattern
  #"^The (.+) relation was inherited from dn:(synset-\d+) (\{.+\}).$")

;; For instance, synset-2128 {ambulance} has 6 inherited relations.
(defn str-transformation
  "Performs basic transformations of `s`; just synset hyperlinks for now."
  [s]
  (if-let [[_ qname synset-id label] (re-find inheritance-pattern (str s))]
    (let [[prefix rel] (str/split qname #":")]
      [:<>
       "The "
       (prefix-elem (symbol prefix))
       (anchor-elem (keyword prefix rel) rel)
       " relation was inherited from "
       (prefix-elem 'dn)
       (anchor-elem (keyword "dn" synset-id) label)
       "."])
    (str s)))

(declare attr-val-table)

(rum/defc val-cell
  "A table cell of an 'attr-val-table' which contains a single `v`. The single
  value can either be a literal or an inlined table (i.e. a blank RDF node)."
  [{:keys [languages k->label] :as opts} v]
  (cond
    (keyword? v)
    [:td
     (prefix-elem (symbol (namespace v)))
     (anchor-elem v (i18n/select-label languages (get k->label v)))]

    ;; Display blank resources as inlined tables.
    (map? v)
    [:td (attr-val-table opts v)]

    ;; Doubly inlined tables are omitted entirely.
    (nil? v)
    [:td.omitted {:lang "en"} "(details omitted)"]

    :else
    (let [s (i18n/select-str languages v)]
      [:td {:lang (i18n/lang s)} (str-transformation s)])))

(defn sort-keyfn
  "Keyfn for sorting keywords and other content based on a `k->label` mapping.
  Returns vectors so that identical labels are sorted by keywords secondly."
  [{:keys [languages k->label] :as opts}]
  (fn [item]
    (let [k (if (map-entry? item) (first item) item)]
      [(str (i18n/select-label languages (get k->label k))) item])))

(rum/defc val-list-item
  "A list item element of a 'val-list-cell'."
  [{:keys [languages k->label] :as opts} item]
  (cond
    (keyword? item)
    (let [prefix (symbol (namespace item))
          label  (i18n/select-label languages (get k->label item))]
      [:li
       (prefix-elem prefix)
       (anchor-elem item label)])

    ;; TODO: handle blank resources better?
    ;; Currently not including these as they seem to
    ;; be entirely garbage temp data, e.g. check out
    ;; http://0.0.0.0:8080/dannet/2022/external/ontolex/LexicalSense
    (symbol? item)
    nil #_[:li (attr-val-table opts (meta item))]

    :else
    [:li {:lang (i18n/lang item)}
     (str-transformation item)]))

(rum/defc val-list-cell
  "A table cell of an 'attr-val-table' that contains multiple values in `coll`."
  [{:keys [languages k->label] :as opts} coll]
  (let [amount     (count coll)
        list-items (for [item (sort-by (sort-keyfn opts) coll)]
                     (val-list-item opts item))]
    [:td
     (cond
       (<= amount 5)
       [:ol list-items]

       (< amount 100)
       [:details [:summary ""]
        [:ol list-items]]

       (< amount 1000)
       [:details [:summary ""]
        [:ol.three-digits list-items]]

       (< amount 10000)
       [:details [:summary ""]
        [:ol.four-digits list-items]]

       :else
       [:details [:summary ""]
        [:ol.five-digits list-items]])]))

(rum/defc attr-val-table
  "A table which lists attributes and corresponding values of an RDF resource."
  [{:keys [languages k->label] :as opts} subentity]
  [:table {:class "attr-val"}
   [:colgroup
    [:col]                                                  ; attr prefix
    [:col]                                                  ; attr local name
    [:col]]
   [:tbody
    (for [[k v] subentity
          :let [prefix (if (keyword? k)
                         (symbol (namespace k))
                         k)]]
      [:tr {:key k}
       [:td.attr-prefix (prefix-elem prefix)]
       [:td.attr-name (->> (get k->label k)
                           (i18n/select-label languages)
                           (anchor-elem k))]
       (cond
         (set? v)
         (cond
           (= 1 (count v))
           (let [v* (first v)]
             (rum/with-key (val-cell opts (if (symbol? v*)
                                            (meta v*)
                                            v*))
                           v))

           (every? i18n/rdf-string? v)
           (let [s (i18n/select-str languages v)]
             (if (coll? s)
               [:td {:key v}
                [:ol
                 (for [s* (sort-by str s)]
                   [:li {:key  s*
                         :lang (i18n/lang s*)}
                    (str-transformation s*)])]]
               [:td {:lang (i18n/lang s) :key v} (str-transformation s)]))

           ;; TODO: use sublist for identical labels
           :else
           (val-list-cell opts v))

         (keyword? v)
         (rum/with-key (val-cell opts v) v)

         (symbol? v)
         (rum/with-key (val-cell opts (meta v)) v)

         :else
         [:td {:lang (i18n/lang v) :key v} (str-transformation v)])])]])

(defn- ordered-subentity
  "Select a subentity from `entity` based on `ks` (may be a predicate too) and
  order it according to the labels of the preferred languages."
  [{:keys [languages k->label] :as opts} ks entity]
  (not-empty
    (into (fop/ordered-map)
          (if (coll? ks)
            (->> (for [k ks]
                   (when-let [v (k entity)]
                     [k v]))
                 (remove nil?))
            (sort-by (sort-keyfn opts)
                     (filter ks entity))))))

(rum/defc entity-page
  [{:keys [languages k->label subject entity] :as opts}]
  (let [local-name (name subject)
        prefix     (symbol (namespace subject))
        label      (i18n/select-label languages (:rdfs/label entity))]
    [:article
     [:header
      [:h1
       (rum/with-key (prefix-elem prefix) prefix)
       [:span {:title local-name
               :key   subject
               :lang  (i18n/lang label)}
        (str (or label local-name))]]
      (when-let [uri (prefix/prefix->uri prefix)]
        [:p {:key uri} uri [:em {:key local-name} local-name]])]
     (for [[title ks] sections]
       (when-let [subentity (ordered-subentity opts ks entity)]
         (if title
           [:<> {:key (str ks)}
            [:h2 title]
            (attr-val-table opts subentity)]
           (rum/with-key (attr-val-table opts subentity) (str ks)))))]))

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
             (navigate-to url))))

(rum/defc search-page
  [{:keys [languages lemma search-path search-results] :as opts}]
  [:section.search
   [:form {:action    search-path
           :on-submit on-submit
           :method    "get"}
    [:input {:type          "text"
             :name          "lemma"
             :default-value lemma}]
    [:input {:type  "submit"
             :value "Search"}]]
   (if (empty? search-results)
     [:article
      [:p "No search-results."]]
     [:article
      (for [[k entity] search-results]
        (let [{:keys [k->label]} (meta entity)]
          (rum/with-key (attr-val-table {:languages languages
                                         :k->label  k->label}
                                        entity)
                        k)))])])

(def pages
  "Mapping from page data metadata :page key to the relevant Rum component."
  {:entity entity-page
   :search search-page})

(def data->page
  "Get the page referenced in the page data's metadata."
  (comp pages :page meta))
