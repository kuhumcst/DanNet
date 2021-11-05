(ns dk.wordnet.web.resources
  "Pedestal interceptors for entity look-ups and schema downloads."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint print-table]]
            [io.pedestal.http.route :refer [decode-query-part]]
            [io.pedestal.http.content-negotiation :refer [negotiate-content]]
            [ring.util.response :as ring]
            [com.wsscode.transito :as transito]
            [hiccup.core :as hiccup]
            [hiccup.util :refer [escape-html]]
            [ont-app.vocabulary.lstr :as lstr]
            [flatland.ordered.map :as fop]
            [dk.wordnet.prefix :as prefix]
            [dk.wordnet.db :as db]
            [dk.wordnet.query :as q]
            [dk.wordnet.bootstrap :as bootstrap])
  (:import [ont_app.vocabulary.lstr LangStr]))

;; TODO: add language tag as superscript using attr and CSS
;; TODO: "download as" on entity page + don't use expanded entity for non-HTML
;; TODO: special content functions, e.g. for "Some relations inherited from..."

(defonce db
  (delay
    (db/->dannet
      :imports bootstrap/imports
      :schema-uris db/schema-uris)))

(def one-month-cache
  "private, max-age=2592000")

(def one-day-cache
  "private, max-age=86400")

(def lang-prefs
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
                           :ontolex/usage]]
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

;; TODO: what about mixed sets of LangStr/regular strings?
(defn select-label
  "Select a single label from set of labels `x` based on preferred `languages`.
  If `x` is a not a set, e.g. a string, it is just returned as-is."
  [languages x]
  (if (and (set? x)
           (every? #(instance? LangStr %) x))
    (let [lang->s (into {} (map (juxt lstr/lang str) x))]
      (loop [[head & tail] languages]
        (when head
          (if-let [ret (lang->s head)]
            ret
            (recur tail)))))
    x))

(defn select-str
  "Select strings in a set of strings `x` based on preferred `languages`.
  If `x` is a not a set, e.g. a string, it is just returned as-is.

  This function differs from 'select-label' by allowing for multiple strings
  to be returned instead of just one."
  [languages x]
  (if (and (set? x)
           (every? #(instance? LangStr %) x))
    (let [m (group-by lstr/lang x)]
      (loop [[head & tail] languages]
        (when head
          (if-let [ret (get m head)]
            (if (= 1 (count ret))
              (first ret)
              ret)
            (recur tail)))))
    x))

;; TODO: use e.g. core.memoize rather than naïve memoisation
(def select-label* (memoize (partial select-label lang-prefs)))
(def select-str* (memoize (partial select-str lang-prefs)))

(defn anchor-elem
  "Entity hyperlink from an entity `kw` and (optionally) a string label `s`."
  ([kw s]
   [:a {:href  (resolve-href kw)
        :title (name kw)
        :class (str (if s "string" "keyword")
                    (str " " (get prefix-groups (symbol (namespace kw)))))}
    (or s (name kw))])
  ([kw] (anchor-elem kw nil)))

(defn prefix-elem
  "Visual representation of a `prefix` based on its associated symbol."
  [prefix]
  [:span.prefix {:title (:uri (get prefix/schemas prefix))
                 :class (get prefix-groups prefix)}
   (str prefix ":")])

;; TODO: do something about omitted content
;; e.g. "<http://www.w3.org/2003/06/sw-vocab-status/ns#term_status>"
(defn filter-entity
  "Remove <...> RDF resource keys from `entity` since they mess up sorting."
  [entity]
  (into {} (filter (comp keyword? first) entity)))

(declare html-table)

(defn html-table-cell
  [k->label v]
  (cond
    (keyword? v)
    (if (empty? (name v))
      (let [prefix (symbol (namespace v))]
        (or [:td (:uri (get prefix/schemas prefix))]
            [:td [:span.prefix {:class (get prefix-groups prefix)} prefix]]))
      [:td
       (prefix-elem (symbol (namespace v)))
       (anchor-elem v (select-label* (get k->label v)))])

    ;; Display blank resources as inlined tables
    (symbol? v)
    (let [{:keys [s p]} (meta v)]
      [:td.string (html-table (q/blank-entity (:graph @db) s p) nil nil)])

    :else
    [:td.string (escape-html (select-str* v))]))

(defn sort-keyfn
  "Keyfn for sorting keywords and other content based on a `k->label` mapping."
  [k->label]
  (fn [item]
    (if (keyword? item)
      (str (select-label* (get k->label item)))
      (str item))))

(defn html-table
  [entity subject k->label & [ks]]
  [:table
   [:colgroup
    [:col]
    [:col]
    [:col]]
   (into [:tbody]
         (for [[k v] entity
               :let [prefix (symbol (namespace k))
                     k-str  (select-label* (get k->label k))]]
           [:tr
            [:td.prefix (prefix-elem prefix)]
            [:td (anchor-elem k k-str)]
            (cond
              (set? v)
              (cond
                (= 1 (count v))
                (let [v* (first v)]
                  (if (symbol? v*)
                    (html-table-cell k->label (with-meta v* {:s subject
                                                             :p k}))
                    (html-table-cell k->label v*)))

                (instance? LangStr (first v))
                [:td.string (let [s (select-str* v)]
                              (if (coll? s)
                                [:ul
                                 (for [object (sort-by str s)]
                                   [:li.string (escape-html object)])]
                                s))]

                :else
                (let [lis (for [item (sort-by (sort-keyfn k->label) v)]
                            (cond
                              (keyword? item)
                              (let [prefix (symbol (namespace item))
                                    label  (select-label* (get k->label item))]
                                [:li
                                 (prefix-elem prefix)
                                 (anchor-elem item label)])

                              (symbol? item)
                              nil

                              :else
                              [:li.string (escape-html item)]))]
                  [:td
                   (if (> (count lis) 5)
                     [:details [:summary ""] (into [:ul.keyword] lis)]
                     (into (if (keyword? (first v))
                             [:ul.keyword]
                             [:ul.string])
                           lis))]))

              (keyword? v)
              (html-table-cell k->label v)

              (symbol? v)
              (html-table-cell k->label (with-meta v {:s subject
                                                      :p k}))

              :else
              [:td.string (escape-html v)])]))])

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
   (fn [entity]
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
                         (let [m (if (coll? ks)
                                   (into (fop/ordered-map)
                                         (->> (for [k ks]
                                                (when-let [v (k entity*)]
                                                  [k v]))
                                              (remove nil?)))
                                   (into {} (sort (filter ks entity*))))]
                           (when (not-empty m)
                             (if title
                               [:div
                                [:h2 title]
                                (html-table m subject k->label)]
                               (html-table m subject k->label)))))]
       (hiccup/html
         [:html
          [:head
           [:title (prefix/kw->qname subject)]
           [:meta {:charset "UTF-8"}]
           [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
           [:link {:rel "stylesheet" :href "/css/main.css"}]]
          [:body
           (into [:article
                  [:header [:h1
                            (prefix-elem prefix)
                            [:span {:title (name subject)}
                             (if-let [label (:rdfs/label entity*)]
                               (select-label* label)
                               (name subject))]]
                   (when uri
                     [:p uri [:em (name subject)]])]]
                 (conj (vec tables)
                       [:footer
                        [:p {:lang "da"}
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
                  entity->body (content-type->body-fn content-type)
                  prefix*      (or (get-in request [:path-params :prefix])
                                   prefix)
                  ;; TODO: why is decoding necessary?
                  ;; You would think that the path-params-decoder handled this.
                  subject      (-> request
                                   (get-in [:path-params :subject])
                                   (decode-query-part)
                                   (->> (keyword (name prefix*))))]
              (if-let [entity (q/expanded-entity (:graph @db) subject)]
                (-> ctx
                    (update :response assoc
                            :status 200
                            :body (entity->body entity))
                    (update-in [:response :headers] assoc
                               "Content-Type" content-type
                               "Cache-Control" one-day-cache))
                (update ctx :response assoc
                        :status 404
                        :headers {}))))})

(def content-negotiation-ic
  (negotiate-content (keys content-type->body-fn)))

(defn prefix->entity-route
  "Internal entity look-up route for a specific `prefix`. Looks up the prefix in
  a map of URIs and creates a local, relative path based on this URI."
  [prefix]
  [(str (-> prefix prefix/schemas :uri uri->path) ":subject")
   :get [content-negotiation-ic
         (->entity-ic prefix)]
   :route-name (keyword (str *ns*) (str prefix "-entity"))])

(def external-entity-route
  "Look-up route for external resources. Doesn't conform to the actual URIs."
  [(str (uri->path prefix/dannet-root) "external/:prefix/:subject")
   :get [(negotiate-content (keys content-type->body-fn))
         (->entity-ic)]
   :route-name ::external-entity])

(comment
  (q/expanded-entity (:graph @db) :dn/form-11029540-land)
  (q/expanded-entity (:graph @db) :dn/synset-4849)

  ;; Other examples: "brun kartoffel", "åbne vejen for", "snakkes ved"
  (q/run (:graph @db) [:bgp
                       ['?word :ontolex/canonicalForm '?form]
                       ['?form :ontolex/writtenRep "fandens karl"]])
  #_.)