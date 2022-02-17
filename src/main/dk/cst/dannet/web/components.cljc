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
     "meta"    #{'dct 'vann 'dcat}
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
         :rdfs/comment
         :dct/title
         :dct/description
         :dct/rights
         :dcat/downloadURL]]
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
                                 (comp #{:dns/usedFor
                                         :dns/usedForObject
                                         :dns/nearAntonym
                                         :dns/orthogonalHyponym
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
  ([resource {:keys [languages k->label] :as opts}]
   (if (keyword? resource)
     (let [labels (get k->label resource)
           label  (i18n/select-label languages labels)]
       [:a {:href  (prefix/resolve-href resource)
            :title (name resource)
            :lang  (i18n/lang label)
            :class (prefix->css-class (symbol (namespace resource)))}
        (str (or label (name resource)))])
     (let [qname      (subs resource 1 (dec (count resource)))
           local-name (guess-local-name qname)]
       [:span.unknown {:title local-name}
        local-name])))
  ([resource] (anchor-elem resource nil)))

(rum/defc prefix-elem
  "Visual representation of a `prefix` based on its associated symbol."
  [prefix & {:keys [no-local-name?]}]
  (cond
    (symbol? prefix)
    [:span.prefix {:title (prefix/prefix->uri prefix)
                   :class (prefix->css-class prefix)}
     (if no-local-name?
       (str prefix)
       (str prefix ":"))]

    (string? prefix)
    [:span.prefix {:title (guess-namespace (subs prefix 1 (dec (count prefix))))
                   :class "unknown"}
     "???:"]))

(def rdf-resource-re
  #"^<(.+)>$")

(defn rdf-resource-path
  [rdf-resource]
  (str prefix/external-path "?subject=" rdf-resource))

(defn break-up-uri
  "Place word break opportunities into a potentially long `uri`."
  [uri]
  (into [:<>] (for [part (re-seq #"[^\./]+|[\./]+" uri)]
                (if (re-matches #"[^\./]+" part)
                  [:<> part [:wbr]]
                  part))))

(rum/defc rdf-uri-hyperlink
  [uri]
  [:a.rdf-uri {:href (if (str/starts-with? uri prefix/dannet-root)
                       (prefix/uri->path uri)
                       (rdf-resource-path (prefix/uri->rdf-resource uri)))}
   (break-up-uri uri)])

;; For instance, synset-2128 {ambulance} has 6 inherited relations.
(defn str-transformation
  "Performs convenient transformations of `s`."
  [s]
  (let [s (str s)
        [rdf-resource uri] (re-find rdf-resource-re s)]
    (cond
      rdf-resource
      (rdf-uri-hyperlink uri)

      :else s)))

(declare attr-val-table)

(rum/defc val-cell
  "A table cell of an 'attr-val-table' containing a single `v`. The single value
  can either be a literal or an inlined table (i.e. a blank RDF node)."
  [{:keys [languages k->label] :as opts} v]
  (cond
    (keyword? v)
    (if (empty? (name v))
      ;; Handle cases such as :rdfs/ which have been keywordised by Aristotle.
      [:td
       (rdf-uri-hyperlink (-> v namespace symbol prefix/prefix->uri))]
      [:td
       (prefix-elem (symbol (namespace v)))
       (anchor-elem v opts)])

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

(rum/defc list-item
  "A list item element of a 'list-cell'."
  [{:keys [languages k->label] :as opts} item]
  (cond
    (keyword? item)
    (let [prefix (symbol (namespace item))]
      [:li
       (prefix-elem prefix)
       (anchor-elem item opts)])

    ;; TODO: handle blank resources better?
    ;; Currently not including these as they seem to
    ;; be entirely garbage temp data, e.g. check out
    ;; http://0.0.0.0:8080/dannet/2022/external/ontolex/LexicalSense
    (symbol? item)
    nil #_[:li (attr-val-table opts (meta item))]

    :else
    [:li {:lang (i18n/lang item)}
     (str-transformation item)]))

(rum/defc list-cell
  "A table cell of an 'attr-val-table' containing multiple values in `coll`."
  [opts coll]
  (let [amount     (count coll)
        list-items (for [item (sort-by (sort-keyfn opts) coll)]
                     (list-item opts item))]
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
           (str-transformation s*)])]]
      [:td {:lang (i18n/lang s) :key coll} (str-transformation s)])))

(rum/defc attr-val-table
  "A table which lists attributes and corresponding values of an RDF resource."
  [opts subentity]
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
       [:td.attr-name (anchor-elem k opts)]
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
           (str-list-cell opts v)

           ;; TODO: use sublist for identical labels
           :else
           (list-cell opts v))

         (keyword? v)
         (rum/with-key (val-cell opts v) v)

         (symbol? v)
         (rum/with-key (val-cell opts (meta v)) v)

         :else
         [:td {:lang (i18n/lang v) :key v} (str-transformation v)])])]])

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
  [{:keys [subject entity] :as opts}]
  (cond
    (keyword? subject)
    [(symbol (namespace subject))
     (name subject)
     nil]

    (:vann/preferredNamespacePrefix entity)
    [(symbol (:vann/preferredNamespacePrefix entity))
     (:dct/title entity)
     (str/replace subject #"<|>" "")]

    :else
    (let [local-name (str/replace subject #"<|>" "")]
      [nil
       local-name
       local-name])))

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

(defn entity->label
  "Return the :rdfs/label or another appropriate label value for `entity`."
  [{:keys [rdfs/label]
    :as   entity}]
  label)

(rum/defc entity-page
  [{:keys [languages subject entity] :as opts}]
  (let [[prefix local-name rdf-uri] (resolve-names opts)
        label (i18n/select-label languages (entity->label entity))]
    [:article
     [:header
      [:h1
       (prefix-elem prefix :no-local-name? (empty? local-name))
       [:span {:title (or local-name subject)
               :key   subject
               :lang  (i18n/lang label)}
        (if label
          (str label)
          (if (= local-name rdf-uri)
            (break-up-uri rdf-uri)
            local-name))]]
      (if rdf-uri
        [:div.rdf-uri {:key rdf-uri} (break-up-uri rdf-uri)]
        (when-let [uri-prefix (prefix/prefix->uri prefix)]
          [:div.rdf-uri
           [:span.rdf-uri__prefix {:key uri-prefix} (break-up-uri uri-prefix)]
           [:span.rdf-uri__name {:key local-name} (break-up-uri local-name)]]))]
     (if (empty? entity)
       (no-entity-data languages rdf-uri)
       (for [[title ks] sections]
         (when-let [subentity (ordered-subentity opts ks entity)]
           [:section {:key (or title :no-title)}
            (when title [:h2 title])
            (attr-val-table opts subentity)])))]))

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

;; TODO: language localisation
(rum/defc search-form
  [{:keys [lemma] :as opts}]
  [:form {:role      "search"
          :action    prefix/search-path
          :on-submit on-submit
          :method    "get"}
   [:input {:type          "search"
            :name          "lemma"
            :title         "Search for synsets"
            :placeholder   "search term"
            :on-focus      select-text
            :auto-complete "off"
            :default-value (or lemma "")}]])

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

;; TODO: language localisation
(rum/defc page-footer
  [{}]
  [:footer {:lang "en"}
   [:p
    "© 2022 " [:a {:href "https://cst.ku.dk/english/"}
               "Centre for Language Technology"]
    ", " [:abbr {:title "University of Copenhagen"}
          "KU"] "."]])

(rum/defc page-shell
  [page data]
  (let [page-component (get pages page)
        [prefix local-name rdf-uri] (if (:subject data)
                                      (resolve-names data)
                                      [nil nil nil])]
    [:<>
     [:nav {:class ["prefix" (prefix->css-class prefix)]}
      (search-form data)
      [:a.github {:title "The source code for DanNet is available on Github"
                  :href  "https://github.com/kuhumcst/DanNet"}]]
     [:div#content
      [:main
       (page-component data)]
      [:hr]
      (page-footer {})]]))
