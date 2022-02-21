(ns dk.cst.dannet.web.components
  "Shared frontend/backend Rum components."
  (:require [clojure.string :as str]
            [flatland.ordered.map :as fop]
            [rum.core :as rum]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.web.i18n :as i18n]
            [ont-app.vocabulary.lstr :refer [->LangStr #?(:cljs LangStr)]]
            #?(:clj [better-cond.core :refer [cond]])
            #?(:cljs [lambdaisland.uri :as uri])
            #?(:cljs [reitit.frontend.history :as rfh])
            #?(:cljs [reitit.frontend.easy :as rfe]))
  #?(:cljs (:require-macros [better-cond.core :refer [cond]]))
  (:refer-clojure :exclude [cond])
  #?(:clj (:import [ont_app.vocabulary.lstr LangStr])))

;; TODO: why error? http://localhost:8080/dannet/external?subject=%3Chttp://www.w3.org/ns/lemon/ontolex%3E
;;       Because of https://github.com/ont-app/vocabulary/pull/14
;;       (remove PR has been merged, deps.edn updated)
;; TODO: lots of unknown TaggedValues, possible related to error above http://localhost:8080/dannet/external?subject=%3Chttp%3A%2F%2Fwww.ontologydesignpatterns.org%2Fcp%2Fowl%2Fsemiotics.owl%3E
;; TODO: empty synset http://localhost:8080/dannet/data/synset-47272
;; TODO: equivalent class empty http://localhost:8080/dannet/external/semowl/InformationEntity
;; TODO: empty definition http://0.0.0.0:8080/dannet/data/synset-42955

;; No-op in CLJ
(defonce state (atom {}))

(defn invert-map
  [m]
  (into {} (for [[group prefixes] m
                 prefix prefixes]
             [prefix group])))

(def prefix->css-class
  (invert-map
    {"dannet"  #{'dn 'dnc 'dns}
     "w3c"     #{'rdf 'rdfs 'owl 'skos 'dcat}
     "meta"    #{'dct 'vann}
     "ontolex" #{'ontolex 'lexinfo 'marl}
     "wordnet" #{'wn}}))

(defn with-prefix
  "Return predicate accepting keywords with `prefix`, optionally `except` set."
  [prefix & {:keys [except]}]
  (fn [[k v]]
    (when (keyword? k)
      (and (not (except k))
           (= (namespace k) (name prefix))))))

(def defined-sections
  [[nil [:rdf/type
         :skos/definition
         :rdfs/comment
         :lexinfo/partOfSpeech
         :lexinfo/senseExample
         :dns/ontologicalType
         :dct/title
         :dct/description
         :dct/rights
         :dcat/downloadURL]]
   [#{(->LangStr "Lexical information" "en")
      (->LangStr "Leksikalsk information" "da")}
    [:ontolex/writtenRep
     :ontolex/canonicalForm
     :ontolex/evokes
     :ontolex/isEvokedBy
     :ontolex/sense
     :ontolex/isSenseOf
     :ontolex/lexicalizedSense
     :ontolex/isLexicalizedSenseOf]]
   [#{(->LangStr "WordNet relations" "en")
      (->LangStr "WordNet-relationer" "da")}
    (some-fn (with-prefix 'wn :except #{:wn/partOfSpeech})
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
    (conj defined-sections [#{(->LangStr "Other attributes" "en")
                              (->LangStr "Andre egenskaber" "da")}
                            (complement in-section?)])))

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

(def sense-label
  #"([^_]+)_((?:§|\d)[^_ ]+)( .+)?")

(def synset-sep
  #"\{|,|\}")

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
  [:a.rdf-uri {:href (if (str/starts-with? uri prefix/dannet-root)
                       (prefix/uri->path uri)
                       (prefix/resource-path (prefix/uri->rdf-resource uri)))}
   (break-up-uri uri)])

(defn str-transformation
  "Performs convenient transformations of `s`."
  [s]
  (when-let [s (not-empty (str s))]
    (cond
      :let [[rdf-resource uri] (re-find rdf-resource-re s)]
      (re-matches #"\{.+\}" s)
      [:div.set
       [:div.set__left-bracket]
       (into [:div.set__content]
             (interpose
               [:span.subtle " • "]                         ; comma -> bullet
               (for [label (sense-labels synset-sep s)]
                 (if-let [[_ word sub mwe] (re-matches sense-label label)]
                   [:<> word [:sub sub] mwe]
                   label))))
       [:div.set__right-bracket]]

      :let [[_ word sub mwe] (re-matches sense-label s)]

      word
      [:<> word [:sub sub] mwe]

      rdf-resource
      (rdf-uri-hyperlink uri)

      (re-matches #"https?://[^\s]+" s)
      (break-up-uri s)

      :else s)))

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
           :class (prefix->css-class (symbol (namespace resource)))}
       (or (str-transformation label)
           (name resource))])
    (let [qname      (subs resource 1 (dec (count resource)))
          local-name (guess-local-name qname)]
      [:a {:href  (prefix/resource-path resource)
           :title local-name
           :class "unknown"}
       local-name])))

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
    [:td (if (= v (select-keys v [:rdf/value v]))
           [:section.text {:lang (i18n/lang v)}
            (str-transformation (i18n/select-str languages (:rdf/value v)))]
           (attr-val-table opts v))]

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
  [{:keys [inherited languages] :as opts} subentity]
  [:table {:class "attr-val"}
   [:colgroup
    [:col]                                                  ; attr prefix
    [:col]                                                  ; attr local name
    [:col]]
   [:tbody
    (for [[k v] subentity
          :let [prefix     (if (keyword? k)
                             (symbol (namespace k))
                             k)
                inherited? (get inherited k)]]
      [:tr {:key   k
            :class (when inherited? "inherited")}
       [:td.attr-prefix
        ;; TODO: link to definition below?
        (when inherited?
          [:span.marker {:title (i18n/da-en languages
                                  "Nedarvet egenskab"
                                  "Inherited attribute")}
           "†"])
        (prefix-elem prefix)]
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
  [{:keys [languages subject entity k->label] :as opts}]
  (let [[prefix local-name rdf-uri] (resolve-names opts)
        label      (i18n/select-label languages (entity->label entity))
        label-lang (i18n/lang label)
        inherited  (->> (:dns/inherited entity)
                        (map (comp prefix/qname->kw k->label))
                        (set))
        uri-only?  (= local-name rdf-uri)]
    [:article
     [:header
      [:h1
       (prefix-elem prefix :no-local-name? (empty? local-name))
       [:span {:title (or local-name subject)
               :key   subject
               :lang  label-lang}
        (if label
          (str-transformation label)
          (if uri-only?
            [:div.rdf-uri {:key rdf-uri} (break-up-uri rdf-uri)]
            local-name))]
       (when label-lang
         [:sup label-lang])]
      (when-not uri-only?
        (if rdf-uri
          [:div.rdf-uri {:key rdf-uri} (break-up-uri rdf-uri)]
          (when-let [uri-prefix (prefix/prefix->uri prefix)]
            [:div.rdf-uri
             [:span.rdf-uri__prefix {:key uri-prefix}
              (break-up-uri uri-prefix)]
             [:span.rdf-uri__name {:key local-name}
              (break-up-uri local-name)]])))]
     (if (empty? entity)
       (no-entity-data languages rdf-uri)
       (for [[title ks] sections]
         (when-let [subentity (-> (ordered-subentity opts ks entity)
                                  (dissoc :rdfs/label)
                                  (not-empty))]
           [:section {:key (or title :no-title)}
            (when title [:h2 (str (i18n/select-label languages title))])
            (attr-val-table (assoc opts :inherited inherited) subentity)])))
     (when (not-empty inherited)
       [:p.note
        [:strong "†"]
        (i18n/da-en languages
          ": egenskab helt eller delvist nedarvet fra hypernym."
          ": attribute fully or partially inherited from hypernym.")])]))

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

(rum/defc page-footer
  [{:keys [languages] :as data}]
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
  (let [default (first (or (:languages (rum/react state))
                           server-languages))]
    [:select.language {:title         "Language preference"
                       :default-value default
                       :on-change     (fn [e]
                                        (let [v (.-value (.-target e))]
                                          (swap! state assoc :languages
                                                 (i18n/lang-prefs v))))}
     (when (not (#{"en" "da"} default))
       [:option {:value default} (str default " (browser default)")])
     [:option {:value "en"} "\uD83C\uDDEC\uD83C\uDDE7 English"]
     [:option {:value "da"} "\uD83C\uDDE9\uD83C\uDDF0 Dansk"]]))

(rum/defc page-shell < rum/reactive
  [page {:keys [languages] :as data}]
  #?(:cljs (when-not (:languages @state)
             (swap! state assoc :languages languages)))
  (let [page-component (get pages page)
        data* #?(:clj  {} :cljs (rum/react state))
        data           (merge data data*)
        [prefix local-name rdf-uri] (if (:subject data)
                                      (resolve-names data)
                                      [nil nil nil])]
    [:<>
     ;; TODO: make horizontal when screen size/aspect ratio is different?
     [:nav {:class ["prefix" (prefix->css-class prefix)]}
      (search-form data)
      [:a.title {:title "Frontpage"
                 :href  "/"}
       "DanNet"]
      (language-select languages)
      [:a.github {:title "The source code for DanNet is available on Github"
                  :href  "https://github.com/kuhumcst/DanNet"}]]
     [:div#content
      [:main
       (page-component data)]
      [:hr]
      (page-footer data)]]))
