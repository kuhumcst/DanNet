(ns dk.cst.dannet.web.components
  "Shared frontend/backend Hiccup components."
  (:require [clojure.string :as str]
            [flatland.ordered.map :as fop]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.web.i18n :as i18n])
  (:import [ont_app.vocabulary.lstr LangStr]))

(defn- <>
  "Inline `coll` using a fragment element.

  This is supported by *both* reagent and lambdaisland/hiccup and makes up for
  the fact that lambdaisland/hiccup *doesn't* support inlining seqs."
  [coll]
  (into [:<>] coll))

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
(def sections
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

(defn anchor-elem
  "Entity hyperlink from a `resource` and (optionally) a string label `s`."
  ([resource s]
   (if (keyword? resource)
     [:a {:href  (prefix/resolve-href resource)
          :title (name resource)
          :lang  (i18n/lang s)
          :class (prefix->css-class (symbol (namespace resource)))}
      (or s (name resource))]
     (let [qname      (subs resource 1 (dec (count resource)))
           local-name (guess-local-name qname)]
       [:span.unknown {:title local-name}
        local-name])))
  ([resource] (anchor-elem resource nil)))

(defn prefix-elem
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
    s))

(declare html-table)

(defn html-table-cell
  [{:keys [languages k->label v]}]
  (cond
    (keyword? v)
    (if (empty? (name v))
      (let [prefix (symbol (namespace v))]
        (or [:td (prefix/prefix->uri prefix)]
            [:td [:span.prefix {:class (prefix->css-class prefix)} prefix]]))
      [:td
       (prefix-elem (symbol (namespace v)))
       (anchor-elem v (i18n/select-label languages (get k->label v)))])

    ;; Display blank resources as inlined tables.
    (map? v)
    [:td [html-table {:languages languages
                      :entity    v}]]

    ;; Doubly inlined tables are omitted entirely.
    (nil? v)
    [:td.omitted {:lang "en"} "(details omitted)"]

    :else
    (let [s (i18n/select-str languages v)]
      [:td {:lang (i18n/lang s)} (str-transformation s)])))

(defn sort-keyfn
  "Keyfn for sorting keywords and other content based on a `k->label` mapping.
  Returns vectors so that identical labels are sorted by keywords secondly."
  [languages k->label]
  (fn [item]
    (if (keyword? item)
      [(str (i18n/select-label languages (get k->label item))) item]
      [(str item) nil])))

(defn list-item
  [{:keys [languages k->label item]}]
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
    nil #_[:li [html-table {:languages languages
                            :entity    (meta item)}]]

    :else
    [:li {:lang (i18n/lang item)}
     (str-transformation item)]))

(defn list-cell
  [{:keys [languages k->label coll]}]
  (let [lis (for [item (sort-by (sort-keyfn languages k->label) coll)]
              [list-item {:languages languages
                          :k->label  k->label
                          :item      item}])]
    [:td
     (let [amount (count lis)]
       (cond
         (<= amount 5)
         [:ol [<> lis]]

         (< amount 100)
         [:details [:summary ""]
          [:ol [<> lis]]]

         (< amount 1000)
         [:details [:summary ""]
          [:ol.three-digits [<> lis]]]

         (< amount 10000)
         [:details [:summary ""]
          [:ol.four-digits [<> lis]]]

         :else
         [:details [:summary ""]
          [:ol.five-digits [<> lis]]]))]))

(defn html-table
  [{:keys [languages entity k->label]}]
  [:table
   [:colgroup
    [:col]
    [:col]
    [:col]]
   [:tbody
    [<> (for [[k v] entity
              :let [prefix (if (keyword? k)
                             (symbol (namespace k))
                             k)]]
          [:tr
           [:td.prefix (prefix-elem prefix)]
           [:td (anchor-elem k (i18n/select-label languages (get k->label k)))]
           (cond
             (set? v)
             (cond
               (= 1 (count v))
               (let [v* (first v)]
                 [html-table-cell {:languages languages
                                   :k->label  k->label
                                   :v         (if (symbol? v*)
                                                (meta v*)
                                                v*)}])

               (or (instance? LangStr (first v))
                   (string? (first v)))
               (let [s (i18n/select-str languages v)]
                 (if (coll? s)
                   [:td
                    [:ol
                     (for [s* (sort-by str s)]
                       [:li {:lang (i18n/lang s*)} (str-transformation s*)])]]
                   [:td {:lang (i18n/lang s)} (str-transformation s)]))

               ;; TODO: use sublist for identical labels
               :else
               [list-cell {:languages languages
                           :k->label  k->label
                           :coll      v}])

             (keyword? v)
             [html-table-cell {:languages languages
                               :k->label  k->label
                               :v         v}]

             (symbol? v)
             [html-table-cell {:languages languages
                               :k->label  k->label
                               :v         (meta v)}]

             :else
             [:td {:lang (i18n/lang v)} (str-transformation v)])])]]])

(defn page-shell
  "The outer shell of an HTML page; needs a `title` and a `content` element."
  [title content]
  [:html
   [:head
    [:title title]
    [:meta {:charset "UTF-8"}]
    [:meta {:name    "viewport"
            :content "width=device-width, initial-scale=1.0"}]
    [:link {:rel "stylesheet" :href "/css/main.css"}]]
   [:body
    content
    [:footer {:lang "da"}
     [:p
      "© 2022 " [:a {:href "https://cst.ku.dk/english/"}
                 "Centre for Language Technology"]
      ", " [:abbr {:title "University of Copenhagen"}
            "KU"] "."]
     [:p "The source code for DanNet is available at our "
      [:a {:href "https://github.com/kuhumcst/DanNet"}
       "Github repository"] "."]]]])

(defn entity-page
  "A view of the entity map of a specific RDF resource."
  [{:keys [languages entity]}]
  (let [subject     (-> entity meta :subject)
        k->label    (-> entity meta :k->label)
        prefix      (symbol (namespace subject))
        ks-defs     (map second sections)
        in-ks?      (fn [[k v]]
                      (get (set (apply concat (filter coll? ks-defs)))
                           k))
        in-section? (apply some-fn in-ks? (filter fn? ks-defs))
        other       ["Other attributes" (complement in-section?)]]
    [page-shell (prefix/kw->qname subject)
     [:article
      [:header [:h1
                (prefix-elem prefix)
                (let [label (i18n/select-label languages (:rdfs/label entity))]
                  [:span {:title (name subject)
                          :lang  (i18n/lang label)}
                   (or label (name subject))])]
       (when-let [uri (prefix/prefix->uri prefix)]
         [:p uri [:em (name subject)]])]
      [<> (for [[title ks] (conj sections other)]
            (when-let [m (not-empty
                           (into (fop/ordered-map)
                                 (if (coll? ks)
                                   (->> (for [k ks]
                                          (when-let [v (k entity)]
                                            [k v]))
                                        (remove nil?))
                                   (sort-by (sort-keyfn languages k->label)
                                            (filter ks entity)))))]
              (if title
                [:<>
                 [:h2 title]
                 [html-table {:languages languages
                              :entity    m
                              :k->label  k->label}]]
                [html-table {:languages languages
                             :entity    m
                             :k->label  k->label}])))]]]))

(defn search-page
  "Search results for a given lemma."
  [{:keys [lemma search-path languages results]}]
  [page-shell (str "Search: " lemma)
   [:section.search
    [:form {:action search-path
            :method "get"}
     [:input {:type  "text"
              :name  "lemma"
              :value lemma}]
     [:input {:type  "submit"
              :value "Search"}]]
    (if (empty? results)
      [:article
       [:p "No results."]]
      [:article
       [<> (for [[kw result] results]
             (let [{:keys [k->label]} (meta result)]
               [html-table {:languages languages
                            :entity    result
                            :k->label  k->label}]))]])]])
