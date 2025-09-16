(ns dk.cst.dannet.web.resources
  "Pedestal interceptors for entity look-ups and schema downloads."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint print-table]]
            [clojure.data.json :as json]
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
            [thi.ng.color.core :as col]
            [thi.ng.color.presets.categories :as cat]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.web.i18n :as i18n]
            [dk.cst.dannet.web.components :as com]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.db :as db]
            [dk.cst.dannet.db.bootstrap :as bootstrap]
            [dk.cst.dannet.db.export.rdf :as export.rdf]
            [dk.cst.dannet.db.export.json-ld :refer [json-ld-ify]]
            [dk.cst.dannet.db.search :as search]
            [dk.cst.dannet.query :as q]
            [dk.cst.dannet.query.operation :as op])
  (:import [java.io File]
           [ont_app.vocabulary.lstr LangStr]
           [org.apache.jena.datatypes BaseDatatype$TypedValue]
           [org.apache.jena.datatypes.xsd XSDDateTime]))

;; TODO: "download as" on entity page + don't use expanded entity for non-HTML
;; TODO: weird label edge cases:
;;       http://localhost:3456/dannet/data/synset-74520

(def schema-uris
  "URIs where relevant schemas can be fetched."
  (->> (for [{:keys [alt uri export]} (vals prefix/schemas)]
         (when-not export
           (if alt
             (cond
               (= alt :no-schema)
               nil

               (or (str/starts-with? alt "http://")
                   (str/starts-with? alt "https://"))
               alt

               :else
               (io/resource alt))
             uri)))
       (filter some?)))

(def dannet-opts
  (atom {:db-type     :tdb2
         :db-path     "db/tdb2"
         :input-dir   (io/file "bootstrap/latest")
         :schema-uris schema-uris}))

(defonce db
  (delay
    (println "DanNet opts:")
    (pprint @dannet-opts)
    (time (bootstrap/->dannet @dannet-opts))))

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
        [:link {:rel "stylesheet" :href "/css/main.css?v=6"}]

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

(defn- ->json-safe
  "Convert RDF data to JSON-compatible format."
  [data]
  (cond
    (instance? LangStr data)
    {:value (.s data) :lang (.lang data)}

    (instance? BaseDatatype$TypedValue data)
    {:value (.getLexicalValue data) :datatype (str (.getDatatypeURI data))}

    (instance? XSDDateTime data)
    {:value (str data) :datatype "xsd:dateTime"}

    ;; Handle symbols with metadata (blank entities from attach-blank-entities)
    (symbol? data)
    (if-let [resolved-data (meta data)]
      ;; If the symbol has metadata, use the resolved data
      (->json-safe resolved-data)
      ;; Otherwise, convert the symbol to string
      (str data))

    (keyword? data) (prefix/kw->qname data)
    (map? data) (into {} (map (fn [[k v]] [(->json-safe k) (->json-safe v)]) data))
    (coll? data) (mapv ->json-safe data)
    :else data))

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

   "text/turtle"
   (fn [{:keys [entity href]} & _]
     (when entity
       (export.rdf/ttl-entity entity (str "https://wordnet.dk" href))))

   ;; TODO: should this match the JSON output? Or be used for debugging Transit?
   "application/edn"
   (fn [data & _]
     (pr-str data))

   ;; The JSON format is used as an API and doesn't need the same information
   ;; that is passed to the web app through Transit.
   "application/json"
   (fn [{:keys [entity entities
                search-results lemma]
         :as   data} & _]
     (let [kv->entity (fn [[subject entity]]
                        (assoc entity :dc/subject subject))]
       (json/write-str (cond
                         entity
                         (json-ld-ify
                           (merge-with
                             q/set-merge entity
                             {:rdfs/comment "The @graph contains labels for the properties and values of the core RDF resource defined @id."})
                           (map kv->entity entities))

                         search-results
                         (json-ld-ify
                           {:rdfs/comment (str "The @graph represents an ordered DanNet synset search result for the lemma \"" lemma "\".")}
                           (map kv->entity search-results))

                         :else
                         (->json-safe data)))))

   "application/transit+json"
   (fn [data & _]
     (to/write-str data {:handlers transit-write-handlers}))})

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

(defn redirect-location
  "Redirect to `x`.
  If the provided arg isn't an RDF resource, it is assumed to be a plain URI."
  [x]
  (cond
    (keyword? x)
    (prefix/uri->dannet-path (prefix/kw->uri x))

    (prefix/rdf-resource? x)
    (prefix/resource-path x)

    :else                                                   ; plain URI
    x))

;; NOTE: redirect doesn't work on shadow-cljs port, but works fine otherwise!
(defn redirect
  "Get a redirect response that works for both HTTP redirects and for the API
  based on some `x` to redirect to and the requested `content-type`.

  Unfortunately, the JS fetch API does not allow for intercepting 30x redirects
  manually, so a somewhat hacky solution is required to make it work. By setting
  a custom header and adding some redirect logic on the client-side, the client
  knows when to redirect from an API call.

  NOTE: An optional `replace-state` arg may be provided to tell the client SPA
  to replace the state in history rather than adding a new entry. This is needed
  when automatically redirecting to an alt entity, since the back button will
  otherwise break for the user."
  [x content-type & [replace-state {:keys [format lang] :as query-params}]]
  (let [location     (redirect-location x)
        ;; TODO: HACK - preserve explicit lang/format in redirects (for API)
        api-location (if (or lang format)
                       (cond
                         (and lang format)
                         (str location "?lang=" lang "&format=" format)

                         lang
                         (str location "?lang=" lang)

                         format
                         (str location "?format=" format))
                       location)]
    (case content-type
      "text/html"
      {:status  303
       :headers {"Location" location}}

      ;; Custom header hack for SPA client-side redirect handling
      ;; Ideally, this would be 204 and no body, but Fetch has issues with that,
      ;; e.g. https://github.com/lambdaisland/fetch/issues/24
      "application/transit+json"
      {:status  200
       :headers (x-headers {:redirect location
                            :replace  (if replace-state "T" "F")})
       :body    "{}"}

      ;; Simple redirect for JSON - let HTTP client follow the redirect
      "application/json"
      {:status  303
       :headers {"Location" api-location}}

      ;; else
      nil)))

(defn remove-internal-params
  [query-string]
  (when query-string
    (str/replace query-string #"&?(transit=true|format=turtle)" "")))

(defn json-ld-upgrade
  "Change JSON `content-type` to JSON-LD (used by the core RDF endpoints)."
  [content-type]
  (if (= "application/json" content-type)
    "application/ld+json"
    content-type))

(defn ->entity-ic
  "Create an interceptor to return DanNet resources, optionally specifying a
  predetermined `prefix` to use for graph look-ups; otherwise locates the prefix
  within the path-params."
  [& {:keys [prefix subject] :as static-params}]
  {:name  ::entity
   :leave (fn [{:keys [request] :as ctx}]
            (let [{:keys [prefix
                          subject]} (merge (:path-params request)
                                           (:query-params request)
                                           static-params)
                  content-type (or (get-in request [:accept :field])
                                   "application/json")
                  ;; TODO: why is decoding necessary?
                  ;; You would think that the path-params-decoder handled this.
                  g            (:graph @db)
                  subject*     (cond->> (decode-query-part subject)
                                 prefix (keyword (name prefix)))
                  ;; TODO: just always expand?
                  entity       (if (get #{"application/transit+json"
                                          "text/html"

                                          ;; MCP server needs it too
                                          "application/json"}
                                        content-type)
                                 (q/expanded-entity g subject*)
                                 (q/entity g subject*))
                  languages    (request->languages request)
                  qs           (remove-internal-params (:query-string request))
                  data         {:languages languages
                                :href      (str (:uri request)
                                                (when (not-empty qs)
                                                  (str "?" qs)))
                                :entities  (dissoc (-> entity meta :entities)
                                                   subject*)
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
                              (cond
                                (and alt (not-empty (q/entity g alt)))
                                (redirect alt content-type :replace)

                                (keyword? subject*)
                                (redirect (prefix/kw->uri subject*) content-type)

                                (string? subject*)
                                (redirect (prefix/rdf-resource->uri subject*) content-type)))))
                  (update-in [:response :headers] merge
                             (assoc (x-headers page-meta)
                               "Content-Type" (json-ld-upgrade content-type))
                             (when (= content-type "text/turtle")
                               (let [filename (str/replace qname #":" "_")
                                     cd       (str "attachment; filename=\"" filename ".ttl\"")]
                                 {"Content-Disposition" cd}))
                             ;; TODO: use cache in production
                             #_#_"Cache-Control" one-day-cache))))})

(defn look-up*
  [g lemma]
  (or (not-empty (search/look-up g lemma))
      ;; TODO: attempt to ignore case entirely...?
      ;; Also check for a lower-case version
      (when (and (first lemma)
                 (Character/isUpperCase ^Character (first lemma)))
        (not-empty (search/look-up g (str/lower-case lemma))))))

(def search-ic
  "Presents search results as synsets matching a given lemma.

  In cases where one-and-only-one search result is returned, the interceptor
  automatically redirects to that specific synset, skipping the list.

  When provided with a QName or RDF resource URI in place of a lemma, the
  relevant redirect is performed instead."
  {:name  ::search
   :leave (fn [{:keys [request] :as ctx}]
            (let [content-type (get-in request [:accept :field] "application/json")
                  query-params (:query-params request)
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
              (cond
                (prefix/rdf-resource? lemma)
                (assoc ctx :response (redirect lemma content-type nil query-params))

                (and (string? lemma) (re-find #"^https?://" lemma))
                (assoc ctx :response (redirect (prefix/uri->rdf-resource lemma) content-type nil query-params))

                (prefix/qname? lemma)
                (assoc ctx :response (redirect (prefix/qname->kw lemma) content-type nil query-params))

                :else
                (let [results (look-up* (:graph @db) lemma)]
                  (if (= (count results) 1)
                    (assoc ctx
                      :response (redirect (ffirst results) content-type :replace query-params))
                    (-> ctx
                        (update :response assoc
                                :status 200
                                :body (body {:languages      languages
                                             :lemma          lemma
                                             :search-results results}
                                            page-meta))
                        (update-in [:response :headers] merge
                                   (assoc (x-headers page-meta)
                                     "Content-Type" (json-ld-upgrade content-type)
                                     ;; TODO: use cache in production
                                     #_#_"Cache-Control" one-day-cache))))))))})

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

(def explicit-params-ic
  "Interceptor that completely supersedes content and language negotiation
  when explicit query parameters are provided.
  
  Supports:
  - ?format= query parameter for content types (json, edn, transit, turtle, html, plain)
  - ?lang= query parameter for languages (da, en, danish, english)
  
  Layman's terms are mapped to proper values, while standard MIME types and
  language codes pass through as-is. This provides both user-friendly shortcuts
  and precise control for API clients.
  
  Must be placed AFTER content-negotiation-ic and language-negotiation-ic
  in the interceptor chain to completely override their results."
  {:name  ::explicit-params
   :enter (fn [{:keys [request] :as ctx}]
            (let [{:keys [format lang]} (:query-params request)
                  ;; TODO: why is decoding necessary?
                  ;; You would think that the query-params-decoder handled this.
                  format*         (when format (decode-query-part format))
                  lang*           (when lang (decode-query-part lang))

                  ;; Map layman's terms to proper MIME types
                  format->mime    {"json"    "application/json"
                                   "edn"     "application/edn"
                                   "transit" "application/transit+json"
                                   "turtle"  "text/turtle"
                                   "ttl"     "text/turtle"  ; common alias
                                   "html"    "text/html"
                                   "plain"   "text/plain"}
                  content-type    (or (format->mime format*) format*)

                  ;; Map layman's terms to language vectors
                  lang->languages {"danish" ["da"], "english" ["en"]}
                  languages       (or (lang->languages lang*) (when lang* [lang*]))]

              (cond-> ctx
                content-type
                (assoc-in [:request :accept :field] content-type)

                languages
                (assoc-in [:request :languages] languages))))})

(defn prefix->entity-route
  "Internal entity look-up route for a specific `prefix`. Looks up the prefix in
  a map of URIs and creates a local, relative path based on this URI."
  [prefix]
  [(str (-> prefix prefix/schemas :uri prefix/uri->path) ":subject")
   :get [content-negotiation-ic
         language-negotiation-ic
         explicit-params-ic
         (->entity-ic :prefix prefix)]
   :route-name (keyword (str *ns*) (str prefix "-entity"))])

(def external-entity-route
  "Look-up route for external resources. Doesn't conform to the actual URIs."
  [(str prefix/external-path "/:prefix/:subject")
   :get [content-negotiation-ic
         language-negotiation-ic
         explicit-params-ic
         (->entity-ic)]
   :route-name ::external-entity])

(def unknown-external-entity-route
  [prefix/external-path
   :get [content-negotiation-ic
         language-negotiation-ic
         explicit-params-ic
         (->entity-ic)]
   :route-name ::unknown-external-entity])

(defn prefix->dataset-entity-route
  [prefix]
  (let [uri (-> prefix prefix/prefix->uri prefix/remove-trailing-slash)]
    [(prefix/uri->path uri)
     :get [content-negotiation-ic
           language-negotiation-ic
           explicit-params-ic
           (->entity-ic :subject (prefix/uri->rdf-resource uri))]
     :route-name (keyword (str *ns*) (str prefix "-dataset-entity"))]))

(def search-route
  [prefix/search-path
   :get [content-negotiation-ic
         language-negotiation-ic
         explicit-params-ic
         search-ic]
   :route-name ::search])

(defn frontpage-redirect
  [_]
  {:status  301
   :headers {"Location" (shared/page-href "frontpage")}})

(def root-route
  ["/" :get [frontpage-redirect] :route-name ::root])

(def dannet-route
  ["/dannet" :get [frontpage-redirect] :route-name ::dannet])

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
                  content-type (get-in request [:accept :field] "application/json")
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
         explicit-params-ic
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
            (let [content-type (get-in request [:accept :field] "application/json")
                  body         (content-type->body-fn content-type)
                  ;; TODO: why is decoding necessary?
                  ;; You would think that the path-params-decoder handled this.
                  s            (get-in request [:query-params :s])]
              (when-let [s' (shared/search-string s)]
                (if (> (count s') 2)
                  (-> ctx
                      (update :response assoc
                              :status 200
                              :body (body {:autocompletions (autocomplete s')}))
                      (update-in [:response :headers] assoc
                                 "Content-Type" content-type
                                 "Cache-Control" one-day-cache))
                  (update ctx :response assoc
                          :status 204
                          :headers {})))))})

(def autocomplete-route
  [autocomplete-path
   :get [content-negotiation-ic
         explicit-params-ic
         autocomplete-ic]
   :route-name ::autocomplete])

(def not-in-theme
  "Predicate for filtering colours with a certain HSV distance from theme."
  (let [dist-check (fn [theme-color]
                     (fn [other-color]
                       (> (col/dist-hsv theme-color other-color) 0.33)))]
    (apply every-pred (map (comp dist-check col/css) shared/theme))))

(defn generate-synset-rels-theme
  "Generate list of in-use synset relation types and map it to unique colours."
  []
  (let [fixed-rels  [:wn/hypernym
                     :wn/hyponym
                     :wn/domain_topic
                     :wn/has_domain_topic
                     :dns/orthogonalHypernym
                     :dns/orthogonalHyponym]
        fixed-theme (zipmap fixed-rels (map col/css shared/theme))
        colors      (->> (concat cat/cat20 cat/cat20b cat/cat20c)
                         (map col/int24)
                         (filter not-in-theme)
                         (map col/as-css))
        rels        (->> (q/run (:graph @db) op/synset-relation-types)
                         (map '?rel)
                         (remove (set fixed-rels)))
        num-colours (count colors)
        num-rels    (count rels)]
    (when (> (count rels) (count colors))
      (throw (ex-info (str "Not enough colours available: only "
                           num-colours " colors for " num-rels " rels")
                      {:colors colors
                       :rels   rels})))
    (update-vals (merge fixed-theme (zipmap rels colors)) deref)))

(comment
  ;; Generate the theme used for e.g. radial diagrams
  (generate-synset-rels-theme)

  (meta (q/expanded-entity (:graph @db) bootstrap/<dn>))
  (meta (q/expanded-entity (:graph @db) :ontolex/isEvokedBy))
  (q/entity (:graph @db) :dn/synset-78300)
  (let [subject :dn/synset-78300
        entity  (q/entity (:graph @db) subject)]
    (export.rdf/ttl-entity entity))

  (q/entity (:graph @db) :dn/synset-46015)

  ;; Test for existence of duplicate ontotypes
  (->> (q/run (:graph @db) '[:bgp
                             [?s1 :dns/ontologicalType ?o1]
                             [?s1 :dns/ontologicalType ?o2]])
       (filter (fn [{:syms [?o1 ?o2]}] (not= ?o1 ?o2))))

  ;; 51 cases of true duplicates
  (count (db/find-duplicates (:graph @db)))

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

  ;; Store the synset indegrees (the file is used during bootstrap)
  (q/save-synset-indegrees! (:graph @db))

  ;; Find unlabeled senses (count: 0)
  (count (q/run (:graph @db) op/unlabeled-senses))

  ;; Testing autocompletion
  (autocomplete* "sar")
  (autocomplete* "spo")
  (autocomplete* "tran")
  #_.)
