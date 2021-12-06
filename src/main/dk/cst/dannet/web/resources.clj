(ns dk.cst.dannet.web.resources
  "Pedestal interceptors for entity look-ups and schema downloads."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint print-table]]
            [io.pedestal.http.route :refer [decode-query-part]]
            [io.pedestal.http.content-negotiation :as conneg]
            [ring.util.response :as ring]
            [com.wsscode.transito :as transito]
            [hiccup.core :as hiccup]
            [hiccup.util :as hutil]
            [ont-app.vocabulary.lstr :as lstr]
            [flatland.ordered.map :as fop]
            [com.owoga.trie :as trie]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.db :as db]
            [dk.cst.dannet.query :as q]
            [dk.cst.dannet.query.operation :as op]
            [dk.cst.dannet.bootstrap :as bootstrap])
  (:import [ont_app.vocabulary.lstr LangStr]))

;; TODO: support "systematic polysemy" for  ontological type, linking to blank resources instead
;; TODO: should :wn/instrument be :dns/usedFor instead? Bolette objects to instrument
;; TODO: co-agent instrument confusion http://0.0.0.0:8080/dannet/2022/instances/synset-4249
;; TODO: add missing labels, e.g. http://0.0.0.0:8080/dannet/2022/instances/synset-49069
;; TODO: "download as" on entity page + don't use expanded entity for non-HTML

(defonce db
  (future
    (db/->dannet
      :imports bootstrap/imports
      :schema-uris db/schema-uris)))

;; TODO: should be transformed into a tightly packed tried (currently loose)
(defonce search-trie
  (future
    (let [words (q/run (:graph @db) '[?writtenRep] op/written-representations)]
      (apply trie/make-trie (mapcat concat words words)))))

(def one-month-cache
  "private, max-age=2592000")

(def one-day-cache
  "private, max-age=86400")

(def supported-languages
  ["da" "en"])

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

;; https://github.com/pedestal/pedestal/issues/477#issuecomment-256168954
(defn remove-trailing-slash
  [uri]
  (if (= \/ (last uri))
    (subs uri 0 (dec (count uri)))
    uri))

(defn uri->path
  "Remove every part of the `uri` aside from the path."
  [uri]
  (second (str/split uri #"http://[^/]+")))

(defn prefix->schema-route
  "Create a table-style Pedestal route to serve the schema file for `prefix`. "
  [prefix]
  (let [{:keys [uri alt]} (get prefix/schemas prefix)
        path       (uri->path uri)
        filename   (last (str/split alt #"/"))
        disp       (str "attachment; filename=\"" filename "\"")
        handler    (fn [request]
                     (-> (ring/file-response (.getPath (io/resource alt)))
                         (assoc-in [:headers "Content-Type"] "text/turtle")
                         (assoc-in [:headers "Cache-Control"] one-month-cache)
                         (assoc-in [:headers "Content-Disposition"] disp)))
        route-name (keyword (str *ns*) (str prefix "-schema"))]
    [(remove-trailing-slash path) :get handler :route-name route-name]))

;; TODO: needs some work
(defn ascii-table
  [entity]
  (with-out-str
    (print-table
      (for [[k v] (sort-by first entity)]
        {:predicate (prefix/kw->qname k)
         :object    (if (set? v)
                      (str "{ " (first (sort v)) " , ... }")
                      v)}))))

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

(defn anchor-elem
  "Entity hyperlink from an entity `kw` and (optionally) a string label `s`."
  ([kw s]
   [:a {:href  (resolve-href kw)
        :title (name kw)
        :lang  (lang s)
        :class (get prefix-groups (symbol (namespace kw)))}
    (or s (name kw))])
  ([kw] (anchor-elem kw nil)))

(defn prefix-elem
  "Visual representation of a `prefix` based on its associated symbol."
  [prefix]
  [:span.prefix {:title (:uri (get prefix/schemas prefix))
                 :class (get prefix-groups prefix)}
   (str prefix ":")])

(def inheritance-pattern
  #"dn:(synset-\d+) (\{.+\}).$")

(defn str-transformation
  "Performs basic transformations of `s`; just synset hyperlinks for now."
  [s]
  (if-let [[_ synset-id label] (re-find inheritance-pattern (str s))]
    [:span (hutil/escape-html (str/replace s inheritance-pattern ""))
     (prefix-elem 'dn)
     (anchor-elem (keyword "dn" synset-id) label)
     "."]
    (hutil/escape-html s)))

;; TODO: do something about omitted content
;; e.g. "<http://www.w3.org/2003/06/sw-vocab-status/ns#term_status>"
(defn filter-entity
  "Remove <...> RDF resource keys from `entity` since they mess up sorting."
  [entity]
  (into {} (filter (comp keyword? first) entity)))

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

    ;; Display blank resources as inlined tables
    ;; Note that doubly inlined tables are omitted entirely.
    (symbol? v)
    (let [{:keys [s p]} (meta v)]
      (if-let [entity (q/blank-entity (:graph @db) s p)]
        [:td (html-table languages entity nil nil)]
        [:td.omitted {:lang "en"} "(details omitted)"]))

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
               :let [prefix (symbol (namespace k))
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
                    (html-table-cell languages k->label (with-meta v* {:s subject
                                                                       :p k}))
                    (html-table-cell languages k->label v*)))

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

                              (symbol? item)
                              nil

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
              (html-table-cell languages k->label v)

              (symbol? v)
              (html-table-cell languages k->label (with-meta v {:s subject
                                                                :p k}))

              :else
              [:td {:lang (lang v)} (str-transformation v)])]))])

(def content-type->body-fn
  {"application/edn"
   (fn [entity]
     (pr-str entity))

   "application/transit+json"
   (fn [entity]
     (transito/write-str entity))

   "text/plain"
   (fn [entity]
     (ascii-table entity))

   "text/html"
   (fn [entity & [languages]]
     (let [subject     (-> entity meta :subject)
           k->label    (-> entity meta :k->label)
           entity*     (filter-entity entity)
           prefix      (symbol (namespace subject))
           uri         (:uri (get prefix/schemas (symbol prefix)))
           ks-defs     (map second sections)
           in-ks?      (fn [[k v]]
                         (get (set (apply concat (filter coll? ks-defs)))
                              k))
           in-section? (apply some-fn in-ks? (filter fn? ks-defs))
           other       ["Other attributes" (complement in-section?)]
           tables      (for [[title ks] (conj sections other)]
                         (let [m (into (fop/ordered-map)
                                       (if (coll? ks)
                                         (->> (for [k ks]
                                                (when-let [v (k entity*)]
                                                  [k v]))
                                              (remove nil?))
                                         (sort-by (sort-keyfn languages k->label)
                                                  (filter ks entity*))))]
                           (when (not-empty m)
                             (if title
                               [:div
                                [:h2 title]
                                (html-table languages m subject k->label)]
                               (html-table languages m subject k->label)))))]
       (hiccup/html
         [:html
          [:head
           [:title (prefix/kw->qname subject)]
           [:meta {:charset "UTF-8"}]
           [:meta {:name    "viewport"
                   :content "width=device-width, initial-scale=1.0"}]
           [:link {:rel "stylesheet" :href "/css/main.css"}]]
          [:body
           (into [:article
                  [:header [:h1
                            (prefix-elem prefix)
                            (let [label (select-label* languages (:rdfs/label entity*))]
                              [:span {:title (name subject)
                                      :lang  (lang label)}
                               (or label (name subject))])]
                   (when uri
                     [:p uri [:em (name subject)]])]]
                 (conj (vec tables)
                       [:footer {:lang "da"}
                        [:p
                         "© 2022 " [:a {:href "https://cst.ku.dk/english/"}
                                    "Centre for Language Technology"]
                         ", " [:abbr {:title "University of Copenhagen"}
                               "KU"] "."]
                        [:p "The source code for DanNet is available at our "
                         [:a {:href "https://github.com/kuhumcst/DanNet"}
                          "Github repository"] "."]]))]])))})

(defn ->entity-ic
  "Create an interceptor to return DanNet resources, optionally specifying a
  predetermined `prefix` to use for graph look-ups; otherwise locates the prefix
  within the path-params."
  [& [prefix]]
  {:name  ::entity
   :leave (fn [{:keys [request] :as ctx}]
            (let [content-type (get-in request [:accept :field] "text/plain")
                  lang         (get-in request [:accept-language :field])
                  entity->body (content-type->body-fn content-type)
                  prefix*      (or (get-in request [:path-params :prefix])
                                   prefix)
                  ;; TODO: why is decoding necessary?
                  ;; You would think that the path-params-decoder handled this.
                  subject      (-> request
                                   (get-in [:path-params :subject])
                                   (decode-query-part)
                                   (->> (keyword (name prefix*))))
                  entity       (if (= content-type "text/html")
                                 (q/expanded-entity (:graph @db) subject)
                                 (q/entity (:graph @db) subject))]
              (if entity
                (-> ctx
                    (update :response assoc
                            :status 200
                            :body (if (= content-type "text/html")
                                    (if lang
                                      (entity->body entity [lang "en"])
                                      (entity->body entity ["en"]))
                                    (entity->body entity)))
                    (update-in [:response :headers] assoc
                               "Content-Type" content-type
                               ;; TODO: use cache in production
                               #_#_"Cache-Control" one-day-cache))
                (update ctx :response assoc
                        :status 404
                        :headers {}))))})

(def search-path
  (str (uri->path prefix/dannet-root) "search"))

(def autocomplete-path
  (str (uri->path prefix/dannet-root) "autocomplete"))

(defn autocomplete
  "Return autocompletions for `s` found in the graph."
  [s]
  (->> (trie/lookup @search-trie s)
       (remove (comp nil? second))                          ; remove partial
       (map second)                                         ; grab full words
       (sort)))

(def autocomplete* (memoize autocomplete))

(defn html-search-result
  [languages lemma results]
  (let [tables (mapcat (fn [[kw result]]
                         (let [{:keys [k->label]} (meta result)]
                           [(html-table languages result nil k->label)]))
                       results)]
    (hiccup/html
      [:html
       [:head
        [:title "Search: " lemma]
        [:meta {:charset "UTF-8"}]
        [:meta {:name    "viewport"
                :content "width=device-width, initial-scale=1.0"}]
        [:link {:rel "stylesheet" :href "/css/main.css"}]]
       [:body.search
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
          (into [:article] tables))]])))

(def search-ic
  {:name  ::search
   :leave (fn [{:keys [request] :as ctx}]
            (let [content-type (get-in request [:accept :field] "text/plain")
                  lang         (get-in request [:accept-language :field])
                  languages    (if lang [lang "en"] ["en"])
                  ;; TODO: why is decoding necessary?
                  ;; You would think that the path-params-decoder handled this.
                  lemma        (-> request
                                   (get-in [:query-params :lemma])
                                   (decode-query-part))]
              (if-let [results (db/look-up (:graph @db) lemma)]
                (-> ctx
                    (update :response assoc
                            :status 200
                            :body (html-search-result languages lemma results))
                    (update-in [:response :headers] assoc
                               "Content-Type" content-type
                               ;; TODO: use cache in production
                               #_#_"Cache-Control" one-day-cache))
                (update ctx :response assoc
                        :status 404
                        :headers {}))))})

(def autocomplete-ic
  {:name  ::autocomplete
   :leave (fn [{:keys [request] :as ctx}]
            (let [;; TODO: why is decoding necessary?
                  ;; You would think that the path-params-decoder handled this.
                  s (-> request
                        (get-in [:query-params :s])
                        (decode-query-part))]
              (if (> (count s) 2)
                (-> ctx
                    (update :response assoc
                            :status 200
                            :body (str/join "\n" (autocomplete* s)))
                    (update-in [:response :headers] assoc
                               "Content-Type" "text/plain"
                               "Cache-Control" one-day-cache))
                (update ctx :response assoc
                        :status 404
                        :headers {}))))})

(defn ->language-negotiation-ic
  "Make a language negotiation interceptor from a coll of `supported-languages`.

  The interceptor reuses Pedestal's content-negotiation logic, but unlike the
  included content negotiation interceptor this one does not create a 406
  response if no match is found."
  [supported-languages]
  (let [match-fn   (conneg/best-match-fn supported-languages)
        lang-paths [[:request :headers "accept-language"]
                    [:request :headers :accept-language]]]
    {:name  ::negotiate-language
     :enter (fn [ctx]
              (if-let [accept-param (loop [[path & paths] lang-paths]
                                      (if-let [param (get-in ctx path)]
                                        param
                                        (when (not-empty paths)
                                          (recur paths))))]
                (if-let [language (->> (conneg/parse-accept-* accept-param)
                                       (conneg/best-match match-fn))]
                  (assoc-in ctx [:request :accept-language] language)
                  ctx)
                ctx))}))

(def language-negotiation-ic
  (->language-negotiation-ic supported-languages))

(def content-negotiation-ic
  (conneg/negotiate-content (keys content-type->body-fn)))

(defn prefix->entity-route
  "Internal entity look-up route for a specific `prefix`. Looks up the prefix in
  a map of URIs and creates a local, relative path based on this URI."
  [prefix]
  [(str (-> prefix prefix/schemas :uri uri->path) ":subject")
   :get [content-negotiation-ic
         language-negotiation-ic
         (->entity-ic prefix)]
   :route-name (keyword (str *ns*) (str prefix "-entity"))])

(def external-entity-route
  "Look-up route for external resources. Doesn't conform to the actual URIs."
  [(str (uri->path prefix/dannet-root) "external/:prefix/:subject")
   :get [content-negotiation-ic
         language-negotiation-ic
         (->entity-ic)]
   :route-name ::external-entity])

(def search-route
  [search-path
   :get [content-negotiation-ic
         language-negotiation-ic
         search-ic]
   :route-name ::search])

(def autocomplete-route
  [autocomplete-path
   :get [autocomplete-ic]
   :route-name ::autocomplete])

(comment
  (q/expanded-entity (:graph @db) :dn/form-11029540-land)
  (q/expanded-entity (:graph @db) :dn/synset-4849)

  ;; Other examples: "brun kartoffel", "åbne vejen for", "snakkes ved"
  (q/run (:graph @db) [:bgp
                       ['?word :ontolex/canonicalForm '?form]
                       ['?form :ontolex/writtenRep "fandens karl"]])

  ;; Testing autocompletion
  (autocomplete "sar")
  (autocomplete "spo")
  (autocomplete "tran")

  ;; Look up synsets based on the lemma "have"
  (db/look-up (:graph @db) "have")
  (db/label-lookup (:graph @db))
  #_.)