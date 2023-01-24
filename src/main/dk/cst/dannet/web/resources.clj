(ns dk.cst.dannet.web.resources
  "Pedestal interceptors for entity look-ups and schema downloads."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.pprint :refer [print-table]]
            [cognitect.transit :as t]
            [com.wsscode.transito :as to]
            [io.pedestal.http.route :refer [decode-query-part]]
            [io.pedestal.http.content-negotiation :as conneg]
            [ring.util.response :as ring]
            [ont-app.vocabulary.lstr]
            [rum.core :as rum]
            [com.owoga.trie :as trie]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.web.i18n :as i18n]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.db :as db]
            [dk.cst.dannet.query :as q]
            [dk.cst.dannet.bootstrap :as bootstrap]
            [dk.cst.dannet.web.components :as com]
            [dk.cst.dannet.query.operation :as op])
  (:import [ont_app.vocabulary.lstr LangStr]
           [org.apache.jena.datatypes BaseDatatype$TypedValue]
           [org.apache.jena.datatypes.xsd XSDDateTime]))

;; TODO: "download as" on entity page + don't use expanded entity for non-HTML
;; TODO: weird label edge cases:
;;       http://localhost:3456/dannet/data/synset-74520
;;       http://localhost:3456/dannet/data/synset-57570

(defonce db
  (delay
    (db/->dannet
      :db-type :tdb2
      :db-path "db/tdb2"
      :bootstrap-imports bootstrap/imports
      :schema-uris db/schema-uris)))

(def one-month-cache
  "private, max-age=2592000")

(def one-day-cache
  "private, max-age=86400")

(defn prefix->download-route
  "Create a table-style Pedestal route to serve the schema file for `prefix`. "
  [prefix]
  (let [{:keys [uri alt]} (get prefix/schemas prefix)
        path       (prefix/uri->path (prefix/download-uri uri))
        filename   (last (str/split alt #"/"))
        disp       (str "attachment; filename=\"" filename "\"")
        handler    (fn [request]
                     (-> (ring/file-response (.getPath (io/resource alt)))
                         (assoc-in [:headers "Content-Type"] "text/turtle")
                         (assoc-in [:headers "Cache-Control"] one-month-cache)
                         (assoc-in [:headers "Content-Disposition"] disp)))
        route-name (keyword (str *ns*) (str prefix "-schema"))]
    [path :get handler :route-name route-name]))

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

(defn html-page
  "A full HTML page ready to be hydrated. Needs a `title` and `content`."
  [title content]
  (rum/render-static-markup
    [:html
     [:head
      [:title title]
      [:meta {:charset "UTF-8"}]
      [:meta {:name    "viewport"
              :content "width=device-width, initial-scale=1.0"}]
      [:link {:rel "stylesheet" :href "/css/main.css"}]
      ;; TODO: make this much more clean
      ;; Disable animation when JS is unavailable, otherwise much too frequent!
      [:noscript [:style {:type "text/css"} "body, body *, header h1 span, header p, header p em { animation: none;transition: background 0; }"]]]
     [:body
      [:div#app {:dangerouslySetInnerHTML {:__html (rum/render-html content)}}]
      [:script (str "var inDevelopmentEnvironment = " shared/development? ";")]
      [:script {:src (str "/js/compiled/" shared/main-js)}]]]))

(defn- lstr->s
  [lstr]
  (str (.s lstr) "@" (.lang lstr)))

(defn- TypedValue->m
  [o]
  {:value (.-lexicalValue o)
   :uri   (.-datatypeURI o)})

(def transit-write-handlers
  {LangStr                 (t/write-handler "lstr" lstr->s)
   BaseDatatype$TypedValue (t/write-handler "rdfdatatype" TypedValue->m)
   XSDDateTime             (t/write-handler "datetime" str)})

;; TODO: order matters when creating conneg interceptor, should be kvs
(def content-type->body-fn
  {"text/plain"
   ;; TODO: make generic
   (fn [& {:keys [data]}]
     (ascii-table data))

   "application/edn"
   (fn [& {:keys [data]}]
     (pr-str data))

   "application/transit+json"
   (fn [& {:keys [data page title]}]
     (to/write-str (vary-meta data assoc :page page :title title)
                   {:handlers transit-write-handlers}))

   "text/html"
   (fn [& {:keys [data page title] :as opts}]
     (html-page
       title
       (com/page-shell page data)))})

(def use-lang?
  #{"application/transit+json" "text/html"})

(defn ->entity-ic
  "Create an interceptor to return DanNet resources, optionally specifying a
  predetermined `prefix` to use for graph look-ups; otherwise locates the prefix
  within the path-params."
  [& {:keys [prefix subject] :as static-params}]
  {:name  ::entity
   :leave (fn [{:keys [request] :as ctx}]
            (let [content-type (get-in request [:accept :field] "text/plain")
                  lang         (get-in request [:accept-language :field])
                  {:keys [prefix subject]} (merge (:path-params request)
                                                  (:query-params request)
                                                  static-params)
                  ;; TODO: why is decoding necessary?
                  ;; You would think that the path-params-decoder handled this.
                  subject*     (cond->> (decode-query-part subject)
                                 prefix (keyword (name prefix)))
                  entity       (if (use-lang? content-type)
                                 (q/expanded-entity (:graph @db) subject*)
                                 (q/entity (:graph @db) subject*))
                  languages    (i18n/lang-prefs lang)
                  data         {:languages languages
                                :k->label  (-> entity meta :k->label)
                                :inferred  (-> entity meta :inferred)
                                :subject   subject*
                                :entity    entity}
                  qname        (if (keyword? subject*)
                                 (prefix/kw->qname subject*)
                                 subject*)]
              (-> ctx
                  (update :response assoc
                          :status (if entity 200 404)
                          :body ((content-type->body-fn content-type)
                                 :data data
                                 :title qname
                                 :page :entity))
                  (update-in [:response :headers] assoc
                             "Content-Type" content-type
                             ;; TODO: use cache in production
                             #_#_"Cache-Control" one-day-cache))))})

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
              (if-let [search-results (db/look-up (:graph @db) lemma)]
                (let [data {:languages      languages
                            :lemma          lemma
                            :search-results search-results}]
                  (-> ctx
                      (update :response assoc
                              :status 200
                              :body ((content-type->body-fn content-type)
                                     :data data
                                     :title (str "Search: " lemma)
                                     :page :search))
                      (update-in [:response :headers] assoc
                                 "Content-Type" content-type
                                 ;; TODO: use cache in production
                                 #_#_"Cache-Control" one-day-cache)))
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
  (->language-negotiation-ic i18n/supported-languages))

(def content-negotiation-ic
  (conneg/negotiate-content (keys content-type->body-fn)))

(defn prefix->entity-route
  "Internal entity look-up route for a specific `prefix`. Looks up the prefix in
  a map of URIs and creates a local, relative path based on this URI."
  [prefix]
  [(str (-> prefix prefix/schemas :uri prefix/uri->path) ":subject")
   :get [content-negotiation-ic
         language-negotiation-ic
         (->entity-ic :prefix prefix)]
   :route-name (keyword (str *ns*) (str prefix "-entity"))])

(def external-entity-route
  "Look-up route for external resources. Doesn't conform to the actual URIs."
  [(str prefix/external-path "/:prefix/:subject")
   :get [content-negotiation-ic
         language-negotiation-ic
         (->entity-ic)]
   :route-name ::external-entity])

(def unknown-external-entity-route
  [prefix/external-path
   :get [content-negotiation-ic
         language-negotiation-ic
         (->entity-ic)]
   :route-name ::unknown-external-entity])

(defn prefix->dataset-entity-route
  [prefix]
  (let [uri (-> prefix prefix/prefix->uri prefix/remove-trailing-slash)]
    [(prefix/uri->path uri)
     :get [content-negotiation-ic
           language-negotiation-ic
           (->entity-ic :subject (prefix/uri->rdf-resource uri))]
     :route-name (keyword (str *ns*) (str prefix "-dataset-entity"))]))

(def search-route
  [prefix/search-path
   :get [content-negotiation-ic
         language-negotiation-ic
         search-ic]
   :route-name ::search])

(def dannet-metadata-redirect
  [(fn [_] {:status  301
            :headers {"Location" (-> (prefix/prefix->uri 'dn)
                                     (prefix/remove-trailing-slash)
                                     (prefix/uri->path))}})])

(def root-route
  ["/" :get dannet-metadata-redirect :route-name ::root])

(def dannet-route
  ["/dannet" :get dannet-metadata-redirect :route-name ::dannet])

(def autocomplete-path
  (str (prefix/uri->path prefix/dannet-root) "autocomplete"))

;; TODO: ... include COR writtenRep too? Other labels?
;; TODO: should be transformed into a tightly packed tried (currently loose)
(defonce search-trie
  (delay
    (let [g     (db/get-graph (:dataset @db) prefix/dn-uri)
          words (q/run g '[?writtenRep] op/written-representations)]
      (println "Building trie for search autocompletion...")
      (let [trie (apply trie/make-trie (map str (mapcat concat words words)))]
        (println "Search trie finished!")
        trie))))

(defn autocomplete*
  "Return autocompletions for `s` found in the graph."
  [s]
  (->> (trie/lookup @search-trie s)
       (remove (comp nil? second))                          ; remove partial
       (map second)                                         ; grab full words
       (sort)))

(def autocomplete (memoize autocomplete*))

(def autocomplete-ic
  {:name  ::autocomplete
   :leave (fn [{:keys [request] :as ctx}]
            (let [;; TODO: why is decoding necessary?
                  ;; You would think that the path-params-decoder handled this.
                  s (get-in request [:query-params :s])]
              (when-let [s' (and s (decode-query-part s))]
                (if (> (count s') 2)
                  (-> ctx
                      (update :response assoc
                              :status 200
                              :body (to/write-str (autocomplete s')))
                      (update-in [:response :headers] assoc
                                 "Content-Type" "application/transit+json"
                                 "Cache-Control" one-day-cache))
                  (update ctx :response assoc
                          :status 204
                          :headers {})))))})

(def autocomplete-route
  [autocomplete-path
   :get [autocomplete-ic]
   :route-name ::autocomplete])

(comment
  (q/expanded-entity (:graph @db) :dn/form-11029540-land)
  (q/expanded-entity (:graph @db) :dn/synset-4849)
  (q/entity-triples (:graph @db) :dn/synset-4849)

  ;; 51 cases of true duplicates
  (count (db/find-duplicates (:graph @db)))

  ;; Dealing with senses appearing in multiple synsets.
  (db/discrete-sense-triples (db/find-intersections (:graph @db)))
  (db/intersecting-sense-triples (db/find-intersections (:graph @db)))

    ;; TODO: systematic polysemy
  (-> (->> (q/run (:graph @db) op/synset-intersection)
           (group-by (fn [{:syms [?ontotype ?otherOntotype]}]
                       (into #{} [?ontotype ?otherOntotype])))))

  ;; Other examples: "brun kartoffel", "åbne vejen for", "snakkes ved"
  (q/run (:graph @db) [:bgp
                       ['?word :ontolex/canonicalForm '?form]
                       ['?form :ontolex/writtenRep "fandens karl"]])

  ;; Return all DanNet words that have identical PoS and writtenRep (issue #35)
  (->> (q/run (:graph @db) op/word-clones)
       (filter (fn [{:syms [?w1 ?w2]}]
                 (and (= "dn" (namespace ?w1))
                      (= "dn" (namespace ?w2)))))
       (group-by (juxt '?writtenRep '?pos))
       (count))

  ;; Find unlabeled senses (count: 0)
  (count (q/run (:graph @db) op/unlabeled-senses))

  ;; Testing autocompletion
  (autocomplete* "sar")
  (autocomplete* "spo")
  (autocomplete* "tran")

  ;; Look up synsets based on the lemma "have"
  (db/look-up (:graph @db) "have")
  (db/label-lookup (:graph @db))
  #_.)
