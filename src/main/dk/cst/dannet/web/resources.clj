(ns dk.cst.dannet.web.resources
  "Pedestal interceptors for entity look-ups and schema downloads."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint print-table]]
            [cognitect.transit :as t]
            [com.wsscode.transito :as to]
            [io.pedestal.http.body-params :refer [body-params]]
            [io.pedestal.http.route :refer [decode-query-part]]
            [io.pedestal.http.content-negotiation :as conneg]
            [ont-app.vocabulary.lstr :as lstr]
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
  (:import [java.io File]
           [ont_app.vocabulary.lstr LangStr]
           [org.apache.jena.datatypes BaseDatatype$TypedValue]
           [org.apache.jena.datatypes.xsd XSDDateTime]))

;; TODO: "download as" on entity page + don't use expanded entity for non-HTML
;; TODO: weird label edge cases:
;;       http://localhost:3456/dannet/data/synset-74520

(def dannet-opts
  (atom {:db-type           :tdb2
         :db-path           "db/tdb2"
         :bootstrap-imports bootstrap/imports
         :schema-uris       db/schema-uris}))

(defonce db
  (delay
    (println "DanNet opts:")
    (pprint @dannet-opts)
    (db/->dannet @dannet-opts)))

(def one-day-cache
  "private, max-age=86400")

(def schema-download-route
  (let [handler (fn [{:keys [path-params] :as request}]
                  (let [{:keys [prefix]} path-params
                        path     (prefix/prefix->schema-path (symbol prefix))
                        filename (last (str/split path #"/"))
                        cd       (str "attachment; filename=\"" filename "\"")]
                    (-> (ring/resource-response path)
                        (assoc-in [:headers "Cache-Control"] one-day-cache)
                        (assoc-in [:headers "Content-Disposition"] cd))))]
    ["/schema/:prefix" :get handler :route-name ::schema-download]))

(def export-route
  (let [handler (fn [{:keys [path-params query-params] :as request}]
                  (let [{:keys [prefix type variant]} (merge path-params
                                                             query-params)
                        file (prefix/export-file type (symbol prefix) variant)
                        root (str "export/" type "/")
                        cd   (str "attachment; filename=\"" file "\"")]
                    (-> (ring/file-response file {:root root})
                        (assoc-in [:headers "Content-Type"] "text/turtle")
                        (assoc-in [:headers "Cache-Control"] one-day-cache)
                        (assoc-in [:headers "Content-Disposition"] cd))))]
    ["/export/:type/:prefix" :get handler :route-name ::export]))

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
  [title languages content]
  (str
    "<!DOCTYPE html>\n"                                     ;; Avoid Quirks Mode
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
        [:noscript [:style {:type "text/css"} "body, body *, header h1 span, header p, header p em { animation: none;transition: background 0; }"]]

        ;; Favicon section
        [:link {:rel "apple-touch-icon" :sizes "180x180" :href "/apple-touch-icon.png"}]
        [:link {:rel "icon" :type "image/png" :sizes "32x32" :href "/favicon-32x32.png"}]
        [:link {:rel "icon" :type "image/png" :sizes "16x16" :href "/favicon-16x16.png"}]
        [:link {:rel "manifest" :href "/site.webmanifest"}]
        [:link {:rel "mask-icon" :href "/safari-pinned-tab.svg" :color "#5bbad5"}]
        [:meta {:name "msapplication-TileColor" :content "#da532c"}]
        [:meta {:name "theme-color" :content "#ffffff"}]]
       [:body
        [:div#app {:dangerouslySetInnerHTML {:__html (rum/render-html content)}}]
        [:script
         {:dangerouslySetInnerHTML
          {:__html (str
                     "var inDevelopmentEnvironment = " shared/development? ";"
                     "var negotiatedLanguages = '" (pr-str languages) "';")}}]
        [:script {:src (str "/js/compiled/" shared/main-js)}]]])))

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

;; TODO: order matters when creating conneg interceptor, should be kvs as
;;       shadow-handler relies on "text/html" being the first key, fix!
(def content-type->body-fn
  {"text/html"
   (fn [{:keys [languages] :as data} &
        [{:keys [page title] :as opts}]]
     (html-page
       title
       languages
       (com/page-shell page data)))

   "text/plain"
   ;; TODO: make generic
   (fn [data & _]
     (ascii-table data))

   "application/edn"
   (fn [data & _]
     (pr-str data))

   "application/transit+json"
   (fn [data & _]
     (to/write-str data {:handlers transit-write-handlers}))})

(def use-lang?
  #{"application/transit+json" "text/html"})

(defn- alt-resource
  "Return an alternate resource qname for the given `qname`; useful for e.g.
  resolving <https://example.com/ns#> as <https://example.com/ns>."
  [qname]
  (let [uri (prefix/rdf-resource->uri qname)]
    (when (or (str/ends-with? uri "#")
              (str/ends-with? uri "/"))
      (as-> (dec (count uri)) $
            (subs uri 0 $)
            (str "<" $ ">")))))

;; TODO: eventually support LangStr for titles too
(defn x-headers
  "Encode `page-meta` for a given page as custom HTTP headers.

  See also: dk.cst.dannet.web.components/x-header"
  [page-meta]
  (update-keys page-meta (fn [k] (str "X-" (str/capitalize (name k))))))

(defn request->languages
  "Resolve a vector of language preferences from a `request`."
  [request]
  (or (:languages request)
      (i18n/lang-prefs (get-in request [:accept-language :type]))))

(defn ->entity-ic
  "Create an interceptor to return DanNet resources, optionally specifying a
  predetermined `prefix` to use for graph look-ups; otherwise locates the prefix
  within the path-params."
  [& {:keys [prefix subject] :as static-params}]
  {:name  ::entity
   :leave (fn [{:keys [request] :as ctx}]
            (let [content-type (get-in request [:accept :field] "text/plain")
                  {:keys [prefix subject]} (merge (:path-params request)
                                                  (:query-params request)
                                                  static-params)
                  ;; TODO: why is decoding necessary?
                  ;; You would think that the path-params-decoder handled this.
                  g            (:graph @db)
                  subject*     (cond->> (decode-query-part subject)
                                 prefix (keyword (name prefix)))
                  entity       (if (use-lang? content-type)
                                 (q/expanded-entity g subject*)
                                 (q/entity g subject*))
                  languages    (request->languages request)
                  data         {:languages languages
                                :k->label  (-> entity meta :k->label)
                                :inferred  (-> entity meta :inferred)
                                :subject   subject*
                                :entity    entity}
                  qname        (if (keyword? subject*)
                                 (prefix/kw->qname subject*)
                                 subject*)
                  body         (content-type->body-fn content-type)
                  page-meta    {:title qname
                                :page  "entity"}]
              (-> ctx
                  (update :response merge
                          (if (not-empty entity)
                            {:status 200
                             :body   (body data page-meta)}
                            (let [alt (alt-resource qname)]
                              (if (and alt (not-empty (q/entity g alt)))
                                {:status  301
                                 :headers {"Location" (prefix/resource-path alt)}}
                                {:status 404
                                 :body   (body (dissoc data :entity) page-meta)}))))
                  (update-in [:response :headers] merge
                             (assoc (x-headers page-meta)
                               "Content-Type" content-type)
                             ;; TODO: use cache in production
                             #_#_"Cache-Control" one-day-cache))))})

(def entity-redirect-path
  (str (-> 'dn prefix/schemas :uri prefix/uri->path)))

(def search-ic
  "Presents search results as synsets mathcing a given lemma.

  In cases where one-and-only-one search result is returned, the interceptor
  automatically redirects to that specific synset, skipping the list."
  {:name  ::search
   :leave (fn [{:keys [request] :as ctx}]
            (let [content-type (get-in request [:accept :field] "text/plain")
                  languages    (request->languages request)
                  ;; TODO: why is decoding necessary?
                  ;; You would think that the path-params-decoder handled this.
                  lemma        (-> request
                                   (get-in [:query-params :lemma])
                                   (decode-query-part))
                  body         (content-type->body-fn content-type)
                  page-meta    {:title (i18n/da-en languages
                                         (str "Søg: " lemma)
                                         (str "Search: " lemma))
                                :page  "search"}]
              (let [search-results (db/look-up (:graph @db) lemma)]
                (if (= (count search-results) 1)
                  (-> ctx
                      (update :response assoc
                              :status 301)
                      (update-in [:response :headers] assoc
                                 "Location" (str entity-redirect-path
                                                 (name (ffirst search-results)))))
                  (-> ctx
                      (update :response assoc
                              :status 200
                              :body (body {:languages      languages
                                           :lemma          lemma
                                           :search-results search-results}
                                          page-meta))
                      (update-in [:response :headers] merge
                                 (assoc (x-headers page-meta)
                                   "Content-Type" content-type
                                   ;; TODO: use cache in production
                                   #_#_"Cache-Control" one-day-cache)))))))})

(defn ->language-negotiation-ic
  "Make a language negotiation interceptor from a coll of `supported-languages`.

  The interceptor reuses Pedestal's content-negotiation logic, but unlike the
  included content negotiation interceptor this one does not create a 406
  response if no match is found.

  Furthermore, the client can specify preferred languages explicitly through the
  :languages cookie; this will override any language negotiation."
  [supported-languages]
  (let [match-fn   (conneg/best-match-fn supported-languages)
        lang-paths [[:request :headers "accept-language"]
                    [:request :headers :accept-language]]]
    {:name  ::negotiate-language
     :enter (fn [{:keys [request] :as ctx}]

              ;; Explicitly set languages based on cookies.
              ;; This part is required to avoid an instant language shift when
              ;; first loading the page.
              (if-let [languages (shared/get-cookie request :languages)]
                (let [language (first languages)]
                  (-> ctx
                      (assoc-in [:request :accept-language] {:field language
                                                             :type  language})
                      (assoc-in [:request :languages] languages)))

                ;; Implicitly set languages based on language negotiation.
                (if-let [accept-param (loop [[path & paths] lang-paths]
                                        (if-let [param (get-in ctx path)]
                                          param
                                          (when (not-empty paths)
                                            (recur paths))))]
                  (if-let [language (->> (conneg/parse-accept-* accept-param)
                                         (conneg/best-match match-fn))]
                    (assoc-in ctx [:request :accept-language] language)
                    ctx)
                  ctx)))}))

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

(defn about-redirect
  [_]
  {:status  301
   :headers {"Location" (shared/page-href "about")}})

(def root-route
  ["/" :get [about-redirect] :route-name ::root])

(def dannet-route
  ["/dannet" :get [about-redirect] :route-name ::dannet])

(defn page-langstrings
  "Return Markdown pages as a set of LangStrings for the `document`."
  [document]
  (let [md-pattern' (re-pattern (str document "-(.+)\\.md"))
        xf          (comp
                      (map (fn [f]
                             (some->> (.getName ^File f)
                                      (re-matches md-pattern')
                                      (second)
                                      (lstr/->LangStr (slurp f)))))
                      (remove nil?))]
    (into #{} xf (file-seq (io/file "pages/")))))

(def markdown-ic
  "Returns a generic, localised markdown page for the given given page."
  {:name  ::markdown
   :leave (fn [{:keys [request] :as ctx}]
            (let [document     (-> request :path-params :document)
                  content-type (get-in request [:accept :field] "text/plain")
                  languages    (request->languages request)
                  body         (content-type->body-fn content-type)
                  page-meta    {:page "markdown"}
                  data         {:languages languages
                                :content   (page-langstrings document)}]
              ;; TODO: implement generic 404 page
              (when (not-empty (:content data))
                (-> ctx
                    (update :response assoc
                            :status 200
                            :body (body data page-meta))
                    (update-in [:response :headers] merge
                               (assoc (x-headers page-meta)
                                 "Content-Type" content-type
                                 ;; TODO: use cache in production
                                 #_#_"Cache-Control" one-day-cache))))))})

(def markdown-route
  [prefix/markdown-path
   :get [content-negotiation-ic
         language-negotiation-ic
         markdown-ic]
   :route-name ::markdown])

(def cookie-opts
  {:max-age (* 60 60 12 365)                                ; one year
   :path    "/"
   :domain  (if shared/development?
              false
              "wordnet.dk")})

(def cookies-route
  ["/cookies"
   :put [(body-params)
         (fn [{:keys [transit-params] :as request}]
           ;; The ring cookie interceptor takes care of actual cookie storage.
           {:status  204
            :cookies (update-vals transit-params (fn [v]
                                                   (assoc cookie-opts
                                                     :value (str v))))})]
   :route-name ::cookies])

(def autocomplete-path
  (str (prefix/uri->path prefix/dannet-root) "autocomplete"))

;; TODO: ... include COR writtenRep too? Other labels?
;; TODO: should be transformed into a tightly packed tried (currently loose)
(defonce search-trie
  (delay
    (let [g      (db/get-graph (:dataset @db) prefix/dn-uri)
          words  (q/run g '[?writtenRep] op/written-representations)
          lwords (map (partial map shared/search-string) words)]
      (println "Building trie for search autocompletion...")
      (let [trie (apply trie/make-trie (map str (mapcat concat lwords words)))]
        (println "Search trie finished!")
        trie))))

(defn autocomplete*
  "Return autocompletions for `s` found in the graph."
  [s]
  (->> (trie/lookup @search-trie s)
       (remove (comp nil? second))                          ; remove partial
       (map second)                                         ; grab full words
       (sort)))

;; TODO: use core.memoize, limit memoization to some fixed N invocations
(def autocomplete (memoize autocomplete*))

(def autocomplete-ic
  {:name  ::autocomplete
   :leave (fn [{:keys [request] :as ctx}]
            (let [;; TODO: why is decoding necessary?
                  ;; You would think that the path-params-decoder handled this.
                  s (get-in request [:query-params :s])]
              (when-let [s' (shared/search-string s)]
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
  (q/entity (:graph @db) :dn/synset-78300)
  (q/entity (:graph @db) :dn/synset-46015)
  (q/entity-triples (:graph @db) :dn/synset-4849)

  ;; Test for existence of duplicate ontotypes
  (->> (q/run (:graph @db) '[:bgp
                             [?s1 :dns/ontologicalType ?o1]
                             [?s1 :dns/ontologicalType ?o2]])
       (filter (fn [{:syms [?o1 ?o2]}] (not= ?o1 ?o2))))

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
