(ns dk.cst.dannet.web.components
  "Shared frontend/backend Hiccup components."
  (:require [clojure.string :as str]
            [ont-app.vocabulary.lstr :as lstr]
            [flatland.ordered.map :as fop]
            [dk.cst.dannet.prefix :as prefix])
  (:import [ont_app.vocabulary.lstr LangStr]))

(defn invert-map
  [m]
  (into {} (for [[group prefixes] m
                 prefix prefixes]
             [prefix group])))

(def prefix-groups
  (invert-map
    {"dannet"  #{'dn 'dnc 'dns}
     "w3c"     #{'rdf 'rdfs 'owl}
     "ontolex" #{'ontolex 'skos 'lexinfo}
     "wordnet" #{'wn}}))

(defn with-prefix
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

(defn uri->path
  "Remove every part of the `uri` aside from the path."
  [uri]
  (second (str/split uri #"http://[^/]+")))

(defn resolve-href
  "Given a namespaced `kw`, resolve the href for the resource."
  [kw]
  (let [prefix (symbol (namespace kw))]
    (if (get #{'dn 'dnc 'dns} prefix)
      (str (-> prefix prefix/schemas :uri uri->path) (name kw))
      (-> (str prefix/dannet-root "external/" (namespace kw) "/" (name kw))
          (uri->path)))))

(defn lang
  "Return the language abbreviation of `s` if available  or nil if not."
  [s]
  (when (instance? LangStr s)
    (lstr/lang s)))

(defn select-label
  "Select a single label from set of labels `x` based on preferred `languages`.
  If `x` is a not a set, e.g. a string, it is just returned as-is."
  [languages x]
  (if (set? x)
    (let [lang->s (into {} (map (juxt lang identity) x))]
      (or (loop [[head & tail] languages]
            (when head
              (if-let [ret (lang->s head)]
                ret
                (recur tail))))
          (lang->s nil)))
    x))

(defn select-str
  "Select strings in a set of strings `x` based on preferred `languages`.
  If `x` is a not a set, e.g. a string, it is just returned as-is.

  This function differs from 'select-label' by allowing for multiple strings
  to be returned instead of just one."
  [languages x]
  (if (set? x)
    (let [lang->strs (group-by lang x)
          ret        (loop [[head & tail] languages]
                       (when head
                         (or (get lang->strs head)
                             (recur tail))))
          strs       (or ret (get lang->strs nil))]
      (if (= 1 (count strs))
        (first strs)
        strs))
    x))

;; TODO: use e.g. core.memoize rather than naïve memoisation
(def select-label* (memoize select-label))
(def select-str* (memoize select-str))

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
     [:a {:href  (resolve-href resource)
          :title (name resource)
          :lang  (lang s)
          :class (get prefix-groups (symbol (namespace resource)))}
      (or s (name resource))]
     (let [qname      (subs resource 1 (dec (count resource)))
           local-name (guess-local-name qname)]
       [:span.unknown {:title local-name}
        local-name])))
  ([kw] (anchor-elem kw nil)))

(defn prefix-elem
  "Visual representation of a `prefix` based on its associated symbol."
  [prefix]
  (if (symbol? prefix)
    [:span.prefix {:title (:uri (get prefix/schemas prefix))
                   :class (get prefix-groups prefix)}
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
      [:span
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
  [languages k->label v]
  (cond
    (keyword? v)
    (if (empty? (name v))
      (let [prefix (symbol (namespace v))]
        (or [:td (:uri (get prefix/schemas prefix))]
            [:td [:span.prefix {:class (get prefix-groups prefix)} prefix]]))
      [:td
       (prefix-elem (symbol (namespace v)))
       (anchor-elem v (select-label* languages (get k->label v)))])

    ;; Display blank resources as inlined tables.
    (map? v)
    [:td [html-table languages v nil nil]]

    ;; Doubly inlined tables are omitted entirely.
    (nil? v)
    [:td.omitted {:lang "en"} "(details omitted)"]

    :else
    (let [s (select-str* languages v)]
      [:td {:lang (lang s)} (str-transformation s)])))

(defn sort-keyfn
  "Keyfn for sorting keywords and other content based on a `k->label` mapping.
  Returns vectors so that identical labels are sorted by keywords secondly."
  [languages k->label]
  (fn [item]
    (if (keyword? item)
      [(str (select-label* languages (get k->label item))) item]
      [(str item) nil])))

(defn html-table
  [languages entity subject k->label]
  [:table
   [:colgroup
    [:col]
    [:col]
    [:col]]
   (into [:tbody]
         (for [[k v] entity
               :let [prefix (if (keyword? k)
                              (symbol (namespace k))
                              k)
                     k-str  (select-label* languages (get k->label k))]]
           [:tr
            [:td.prefix (prefix-elem prefix)]
            [:td (anchor-elem k k-str)]
            (cond
              (set? v)
              (cond
                (= 1 (count v))
                (let [v* (first v)]
                  (if (symbol? v*)
                    [html-table-cell languages k->label (meta v*)]
                    [html-table-cell languages k->label v*]))

                (or (instance? LangStr (first v))
                    (string? (first v)))
                (let [s (select-str* languages v)]
                  (if (coll? s)
                    [:td
                     [:ol
                      (for [s* (sort-by str s)]
                        [:li {:lang (lang s*)} (str-transformation s*)])]]
                    [:td {:lang (lang s)} (str-transformation s)]))

                ;; TODO: use sublist for identical labels
                :else
                (let [lis (for [item (sort-by (sort-keyfn languages k->label) v)]
                            (cond
                              (keyword? item)
                              (let [prefix (symbol (namespace item))
                                    label  (select-label* languages (get k->label item))]
                                [:li
                                 (prefix-elem prefix)
                                 (anchor-elem item label)])

                              ;; TODO: handle blank resources better?
                              ;; Currently not including these as they seem to
                              ;; be entirely garbage temp data, e.g. check out
                              ;; http://0.0.0.0:8080/dannet/2022/external/ontolex/LexicalSense
                              (symbol? item)
                              nil #_[:li [html-table languages (meta item) nil nil]]

                              :else
                              [:li {:lang (lang item)}
                               (str-transformation item)]))]
                  [:td
                   (let [amount (count lis)]
                     (cond
                       (<= amount 5)
                       (into [:ol] lis)

                       (< amount 100)
                       [:details [:summary ""]
                        (into [:ol] lis)]

                       (< amount 1000)
                       [:details [:summary ""]
                        (into [:ol.three-digits] lis)]

                       (< amount 10000)
                       [:details [:summary ""]
                        (into [:ol.four-digits] lis)]

                       :else
                       [:details [:summary ""]
                        (into [:ol.five-digits] lis)]))]))

              (keyword? v)
              [html-table-cell languages k->label v]

              (symbol? v)
              [html-table-cell languages k->label (meta v)]

              :else
              [:td {:lang (lang v)} (str-transformation v)])]))])

(defn shell
  [title body]
  [:html
   [:head
    [:title title]
    [:meta {:charset "UTF-8"}]
    [:meta {:name    "viewport"
            :content "width=device-width, initial-scale=1.0"}]
    [:link {:rel "stylesheet" :href "/css/main.css"}]]
   [:body
    body
    [:footer {:lang "da"}
     [:p
      "© 2022 " [:a {:href "https://cst.ku.dk/english/"}
                 "Centre for Language Technology"]
      ", " [:abbr {:title "University of Copenhagen"}
            "KU"] "."]
     [:p "The source code for DanNet is available at our "
      [:a {:href "https://github.com/kuhumcst/DanNet"}
       "Github repository"] "."]]]])

(defn entity-tables
  [subject languages other entity k->label]
  (into [:<>]
        (for [[title ks] (conj sections other)]
          (let [m (into (fop/ordered-map)
                        (if (coll? ks)
                          (->> (for [k ks]
                                 (when-let [v (k entity)]
                                   [k v]))
                               (remove nil?))
                          (sort-by (sort-keyfn languages k->label)
                                   (filter ks entity))))]
            (when (not-empty m)
              (if title
                [:div
                 [:h2 title]
                 [html-table languages m subject k->label]]
                [html-table languages m subject k->label]))))))

(defn entity-page
  [languages entity]
  (let [subject     (-> entity meta :subject)
        k->label    (-> entity meta :k->label)
        prefix      (symbol (namespace subject))
        uri         (:uri (get prefix/schemas (symbol prefix)))
        ks-defs     (map second sections)
        in-ks?      (fn [[k v]]
                      (get (set (apply concat (filter coll? ks-defs)))
                           k))
        in-section? (apply some-fn in-ks? (filter fn? ks-defs))
        other       ["Other attributes" (complement in-section?)]]
    [shell (prefix/kw->qname subject)
     [:article
      [:header [:h1
                (prefix-elem prefix)
                (let [label (select-label* languages (:rdfs/label entity))]
                  [:span {:title (name subject)
                          :lang  (lang label)}
                   (or label (name subject))])]
       (when uri
         [:p uri [:em (name subject)]])]
      [entity-tables subject languages other entity k->label]]]))

(defn search-page
  [lemma search-path tables]
  [shell (str "Search: " lemma)
   [:section.search
    [:form {:action search-path
            :method "get"}
     [:input {:type  "text"
              :name  "lemma"
              :value lemma}]
     [:input {:type  "submit"
              :value "Search"}]]
    (if (empty? tables)
      [:article
       [:p "No results."]]
      (into [:article] tables))]])
