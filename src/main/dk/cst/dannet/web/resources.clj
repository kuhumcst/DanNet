(ns dk.cst.dannet.web.resources
  "Pedestal interceptors and their associated routes."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [dk.cst.dannet.web.sparql :as sparql]
            [io.pedestal.http.body-params :refer [body-params]]
            [io.pedestal.http.route :refer [decode-query-part]]
            [io.pedestal.http.content-negotiation :as conneg]
            [io.pedestal.interceptor :as interceptor]
            [ont-app.vocabulary.lstr :as lstr]
            [ring.util.response :as ring]
            [thi.ng.color.core :as col]
            [thi.ng.color.presets.categories :as cat]
            [taoensso.telemere :as tel]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.web.i18n :as i18n]
            [dk.cst.dannet.web.anomaly :as anomaly]
            [dk.cst.dannet.web.response :as resp]
            [dk.cst.dannet.web.instance :as instance]
            [dk.cst.dannet.entity :as ent]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.db.search :as search]
            [dk.cst.dannet.db.catalog :as catalog]
            [dk.cst.dannet.db.query :as q]
            [dk.cst.dannet.db.query.operation :as op]
            [dk.cst.dannet.web.hyponymy :as hyponymy])
  (:import [clojure.lang ExceptionInfo]
           [java.io File]
           [org.apache.jena.sparql.resultset ResultSetMem]))

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
  (conneg/negotiate-content (keys resp/content-type->body-fn)))

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
                  format'         (when format (decode-query-part format))
                  lang'           (when lang (decode-query-part lang))

                  ;; Map layman's terms to proper MIME types
                  format->mime    {"json"    "application/json"
                                   "json-ld" "application/ld+json"
                                   "edn"     "application/edn"
                                   "transit" "application/transit+json"
                                   "turtle"  "text/turtle"
                                   "ttl"     "text/turtle"  ; common alias
                                   "html"    "text/html"}
                  content-type    (get format->mime format' format')
                  lang->languages {"danish"  ["da"]
                                   "english" ["en"]}
                  languages       (or (lang->languages lang')
                                      (when lang' [lang']))]

              (cond-> ctx
                content-type
                (assoc-in [:request :accept :field] content-type)

                languages
                (assoc-in [:request :languages] languages))))})

;; NOTE: redirect doesn't work on shadow-cljs port, but works fine otherwise!
(def redirect-ic
  "Get a redirect response that works for both HTTP redirects and for the API
  based on keys set in the the context:

    :redirect        - (symbolic) location to redirect to
    :redirect-params - query-params to used when redirecting
    :replace         - (symbolic) location which replaces the current state


  NOTE: The alternative `replace` location arg may be provided to tell the
        client to replace the state in history rather than adding a new entry.
        This is needed when automatically redirecting to an alt entity,
        as the back button will otherwise break for the user.

  ----

  Unfortunately, the JS fetch API does not allow for intercepting 30x redirects
  manually, so a somewhat hacky solution is required to make it work. By setting
  a custom header and adding some redirect logic on the client-side, the client
  knows when to redirect from an API call."
  {:name  ::redirect
   :enter (fn [{:keys [redirect redirect-params replace request] :as ctx}]
            (let [{:keys [lang format]} redirect-params
                  content-type (or (get-in request [:accept :field])
                                   "application/json")
                  location     (resp/redirect-location (or redirect replace))
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
              (if location
                (assoc ctx
                  :response (case content-type
                              "text/html"
                              {:status  303
                               :headers {"Location" location}}

                              ;; Custom header hack for SPA client-side redirect handling
                              ;; Ideally, this would be 204 and no body, but Fetch has issues with that,
                              ;; e.g. https://github.com/lambdaisland/fetch/issues/24
                              "application/transit+json"
                              {:status  200
                               :headers (resp/x-headers {:redirect location
                                                         :replace  (if replace "T" "F")})
                               :body    "{}"}

                              ;; Simple redirect for JSON - let HTTP client follow the redirect
                              "application/json"
                              {:status  303
                               :headers {"Location" api-location}}

                              ;; else
                              nil))
                ctx)))})

(def response-body-ic
  "Generate a response containing the content body (if available)."
  {:name  ::response-body
   :leave (fn [{:keys [request content page-meta] :as ctx}]
            (let [content-type (or (get-in request [:accept :field])
                                   "application/json")
                  ;; Prefer using the JSON-LD body if available whenever the
                  ;; content-type is regular JSON too. In this case the response
                  ;; content-type doesn't get changed to JSON-LD, though.
                  body-fn      (if (= content-type "application/json")
                                 resp/json-body-fn
                                 (resp/content-type->body-fn content-type))
                  title        (get page-meta :title "DanNet")]
              (-> ctx
                  (update :response merge
                          (cond
                            (false? content)
                            {:status  204
                             :headers {}}

                            (and (empty? content) (empty? page-meta))
                            {:status  404
                             :headers {}}

                            :else
                            {:status (or (:response-status ctx) 200)
                             :body   (body-fn (resp/with-cookies request content) page-meta)}))
                  (update-in [:response :headers] merge
                             (-> (assoc (resp/x-headers page-meta)
                                   "Content-Type" content-type
                                   "Cache-Control" resp/one-day-cache)

                                 ;; Add filename extensions when needed.
                                 (merge (resp/with-file-ext title content-type)))))))})

(def error-ic
  "Outermost error-handling interceptor.

  Translates any unhandled exception into an anomaly via 'anomaly/translate'
  and renders it through the same 'resp/content-type->body-fn' machinery used
  for normal responses. Logs the original exception via Telemere at :error
  level.

  Catches errors from any interceptor enqueued after it, including the router
  and all per-route interceptors. Since content negotiation is a per-route
  interceptor, it may not have run before the error was thrown; in that case
  we sniff the Accept header directly."
  (interceptor/interceptor
    {:name  ::error
     :error (fn [ctx ex]
              (let [cause        (or (some-> ex ex-data :exception) ex)
                    request      (:request ctx)
                    anomaly      (-> (anomaly/translate cause)
                                     (anomaly/localize (resp/request->languages request)))
                    content-type (resp/error-content-type request)
                    body-fn      (resp/content-type->body-fn content-type)
                    title        (i18n/da-en (resp/request->languages request)
                                   "Fejl" "Error")
                    content      {:languages (resp/request->languages request)
                                  :anomaly   anomaly}
                    page-meta    {:title title :page "error"}]
                (tel/log! {:level :error
                           :error cause
                           :data  {:uri    (:uri request)
                                   :method (:request-method request)}}
                          "Unhandled exception")
                (assoc ctx
                  :response {:status  (:status anomaly)
                             :headers (merge (resp/x-headers page-meta)
                                             {"Content-Type" content-type})
                             :body    (body-fn (resp/with-cookies request content)
                                               page-meta)})))}))

(defn frontpage-redirect
  [_]
  {:status  301
   :headers {"Location" (shared/page-href "frontpage")}})

(def root-route
  ["/" :get [frontpage-redirect] :route-name ::root])

(def dannet-route
  ["/dannet" :get [frontpage-redirect] :route-name ::dannet])

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

(defn remove-internal-params
  [query-string]
  (when query-string
    (str/replace query-string #"&?(transit=true|format=turtle)" "")))

(def expand-content-types
  "Content types that receive expanded entity data with relation labels."
  #{"application/transit+json"
    "text/html"
    "text/turtle"
    "application/ld+json"
    "application/json"})

(def truncate-content-types
  "Content types that support deferred loading of large semantic relations.
  Limited to browser-based content types where the client can fetch the rest."
  #{"application/transit+json"
    "text/html"})

(defn ->entity-ic
  "Create an interceptor to return DanNet resources, optionally specifying a
  predetermined `prefix` to use for graph look-ups; otherwise locates the prefix
  within the path-params.

  When the content-type supports truncation (HTML, Transit) and the entity has
  large semantic relations, values are truncated to `semantic-relation-limit`.
  The remaining data can be fetched by adding `?deferred=true` to the request."
  [& {:keys [prefix subject] :as static-params}]
  {:name  ::entity
   :enter (fn [{:keys [request] :as ctx}]
            (let [{:keys [prefix
                          subject
                          deferred]} (merge (:path-params request)
                                            (:query-params request)
                                            static-params)
                  content-type       (or (get-in request [:accept :field])
                                         "application/json")
                  g                  (:graph @instance/db)
                  ;; TODO: why is decoding necessary?
                  ;; You would think that the path-params-decoder handled this.
                  subject*           (cond->> (decode-query-part subject)
                                       prefix (keyword (name prefix)))
                  languages          (resp/request->languages request)
                  qs                 (some-> (:query-string request)
                                             remove-internal-params
                                             ;; Canonicalize slash encoding: reitit's
                                             ;; url-encode emits %2F while the client
                                             ;; leaves slashes bare. The difference
                                             ;; otherwise causes a hydration mismatch.
                                             (str/replace "%2F" "/"))
                  qname              (if (keyword? subject*)
                                       (prefix/kw->qname subject*)
                                       subject*)
                  expand?            (expand-content-types content-type)
                  raw-entity         (if expand?
                                       (q/expanded-entity g subject*)
                                       (q/entity g subject*))
                  ;; Apply truncation only for content types that support deferred loading
                  truncate?          (truncate-content-types content-type)
                  deferred?          (and truncate? deferred)
                  ;; De-duplicate entailed superproperty rows (display only).
                  k->supers          @instance/superproperty-closure
                  {pruned-entity :entity
                   folded        :folded}
                  (if (and truncate? (not-empty raw-entity))
                    (-> (ent/prune-entailed-superproperties k->supers raw-entity)
                        (update :entity #(ent/prune-embedded-entities
                                           k->supers %)))
                    {:entity raw-entity :folded nil})
                  {:keys           [truncated has-deferred]
                   deferred-entity :deferred}
                  (if (and truncate? (not-empty pruned-entity))
                    (ent/truncate-semantic-relations pruned-entity)
                    {:truncated pruned-entity :deferred {} :has-deferred false})
                  ;; Return truncated on initial request, deferred portion on deferred request.
                  ;; If there's nothing deferred (entity was small, or was re-fetched from
                  ;; scratch), return the full entity to avoid an empty response.
                  entity             (if deferred?
                                       (if (not-empty deferred-entity)
                                         deferred-entity
                                         truncated)
                                       truncated)
                  ;; Two independent hyponym sunburst trees (real vs
                  ;; orthogonal-only), only for synsets on the initial
                  ;; (non-deferred) browser request.
                  synset?            (and truncate?
                                          (not deferred?)
                                          (shared/synset? subject* raw-entity))
                  orthogonal-only    (:orthogonal-only @instance/hyponym-graph)
                  ->tree             (fn [root-filter]
                                       (when synset?
                                         (hyponymy/hyponym-tree
                                           g @instance/hyponym-graph languages
                                           root-filter subject*)))
                  hyponym            (->tree (complement orthogonal-only))
                  orthogonal-hyponym (->tree orthogonal-only)
                  ;; Whole->part sunburst tree over the meronym graph, reusing
                  ;; the hyponym subtree machinery unchanged.
                  meronym            (when synset?
                                       (hyponymy/hyponym-tree
                                         g @instance/meronym-graph languages
                                         any? subject*))]
              (if (not-empty entity)
                (assoc ctx
                  :content (-> (meta raw-entity)
                               (update :entities dissoc subject*)
                               (assoc :languages languages
                                      :href (str (:uri request)
                                                 (when (not-empty qs)
                                                   (str "?" qs)))
                                      :subject subject*
                                      :entity entity)
                               (cond->
                                 (not-empty folded) (assoc :folded folded)
                                 (some? hyponym) (assoc :hyponym-tree hyponym)
                                 (some? orthogonal-hyponym)
                                 (assoc :orthogonal-hyponym-tree orthogonal-hyponym)
                                 (some? meronym) (assoc :meronym-tree meronym)))
                  :page-meta (cond-> {:title qname
                                      :page  "entity"}
                               (and has-deferred (not deferred?))
                               (assoc :has-deferred "true")))
                (let [alt (alt-resource qname)]
                  (cond
                    (and alt (not-empty (q/entity g alt)))
                    (assoc ctx :replace alt)

                    (keyword? subject*)
                    (assoc ctx :redirect (prefix/kw->uri subject*))

                    (string? subject*)
                    (assoc ctx :redirect (prefix/rdf-resource->uri subject*)))))))})

(defn prefix->entity-route
  "Internal entity look-up route for a specific `prefix`. Looks up the prefix in
  a map of URIs and creates a local, relative path based on this URI."
  [prefix]
  [(str (-> prefix prefix/schemas :uri prefix/uri->path) ":subject")
   :get [content-negotiation-ic
         language-negotiation-ic
         explicit-params-ic
         (->entity-ic :prefix prefix)
         redirect-ic
         response-body-ic]
   :route-name (keyword (str *ns*) (str prefix "-entity"))])

(def external-entity-route
  "Look-up route for external resources. Doesn't conform to the actual URIs."
  [(str prefix/external-path "/:prefix/:subject")
   :get [content-negotiation-ic
         language-negotiation-ic
         explicit-params-ic
         (->entity-ic)
         redirect-ic
         response-body-ic]
   :route-name ::external-entity])

(def unknown-external-entity-route
  [prefix/external-path
   :get [content-negotiation-ic
         language-negotiation-ic
         explicit-params-ic
         (->entity-ic)
         redirect-ic
         response-body-ic]
   :route-name ::unknown-external-entity])

(defn prefix->dataset-entity-route
  [prefix]
  (let [uri (-> prefix prefix/prefix->uri shared/remove-trailing-separator)]
    [(prefix/uri->path uri)
     :get [content-negotiation-ic
           language-negotiation-ic
           explicit-params-ic
           (->entity-ic :subject (prefix/uri->rdf-resource uri))
           redirect-ic
           response-body-ic]
     :route-name (keyword (str *ns*) (str prefix "-dataset-entity"))]))

(defn look-up-any-case
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
   :enter (fn [{:keys [request] :as ctx}]
            (let [query-params (:query-params request)
                  languages    (resp/request->languages request)
                  ;; TODO: why is decoding necessary?
                  ;; You would think that the path-params-decoder handled this.
                  lemma        (-> request
                                   (get-in [:query-params :lemma])
                                   (decode-query-part))]
              (cond
                (prefix/rdf-resource? lemma)
                (assoc ctx
                  :redirect lemma
                  :redirect-params query-params)

                (and (string? lemma) (re-find #"^https?://" lemma))
                (assoc ctx
                  :redirect (prefix/uri->rdf-resource lemma)
                  :redirect-params query-params)

                (prefix/qname? lemma)
                (assoc ctx
                  :redirect (prefix/qname->kw lemma)
                  :redirect-params query-params)

                :else
                (let [results (look-up-any-case (:graph @instance/db) lemma)]
                  (if (= (count results) 1)
                    (assoc ctx
                      :replace (ffirst results)
                      :redirect-params query-params)
                    (assoc ctx
                      :content {:languages      languages
                                :lemma          lemma
                                :search-results results}
                      :page-meta {:title (i18n/da-en languages
                                           (str "Søgning: " lemma)
                                           (str "Search: " lemma))
                                  :page  "search"}))))))})

(def search-route
  [prefix/search-path
   :get [content-negotiation-ic
         language-negotiation-ic
         explicit-params-ic
         search-ic
         redirect-ic
         response-body-ic]
   :route-name ::search])

(def autocomplete-path
  (str (prefix/uri->path prefix/dannet-root) "autocomplete"))

(def autocomplete-ic
  {:name  ::autocomplete
   :enter (fn [ctx]
            (let [s (get-in ctx [:request :query-params :s])]
              (when-let [s' (shared/search-string s)]
                (when (> (count s') 2)
                  (assoc ctx
                    :content {:autocompletions (instance/autocomplete s')})))))})

(def autocomplete-route
  [autocomplete-path
   :get [content-negotiation-ic
         explicit-params-ic
         autocomplete-ic
         response-body-ic]
   :route-name ::autocomplete])

(def sparql-validation-ic
  "Validate and parse SPARQL query parameters, placing results in a single
  `:sparql` map on the ctx. On validation failure, short-circuits by setting
  `:content`/`:page-meta`/`:response-status` directly so that response-body-ic
  handles the error response normally.

  Three code paths reach sparql-execution-ic downstream:
    1. Valid query  -> :sparql has :query-obj, execution proceeds.
    2. Invalid query -> :content/:page-meta already set (400), execution skips.
    3. No query     -> :sparql has :input nil, editor page is rendered."
  {:name  ::sparql-validation
   :enter (fn [{:keys [request] :as ctx}]
            (let [{:keys [query timeout limit offset
                          distinct inference lookahead noop enrichment]} (:query-params request)
                  query-str (or query
                                (when (string? (:body request))
                                  (:body request)))]
              (if query-str
                (try
                  (let [query-obj  (sparql/validate query-str)
                        timeout'   (if (not-empty timeout)
                                     (let [ms (* (Long/parseLong timeout) 1000)]
                                       (if shared/development?
                                         ms
                                         (min ms sparql/max-timeout)))
                                     sparql/max-timeout)
                        limit'     (min (or (sparql/user-limit query-obj)
                                            (when limit (Long/parseLong limit))
                                            sparql/max-results-limit)
                                        sparql/max-results-limit)
                        offset'    (if offset
                                     (Long/parseLong offset)
                                     0)
                        distinct?  (= distinct "true")
                        ;; Lookahead (N+1) is on by default for pagination.
                        ;; Disable when the query contains LIMIT/OFFSET or
                        ;; when explicitly opted out via lookahead=false.
                        user-pag?  (sparql/has-user-pagination? query-obj)
                        lookahead? (and (not= lookahead "false")
                                        (not user-pag?))
                        ;; Three-way: nil (auto), true (force), false (base only)
                        inference? (case inference
                                     "true" true
                                     "false" false
                                     nil)]
                    (assoc ctx
                      :sparql {:input       {:query      query-str
                                             :inference  inference
                                             :distinct   distinct
                                             :enrichment enrichment}
                               :query-obj   query-obj
                               :noop?       (some? noop)
                               :timeout     timeout'
                               :limit       limit'
                               :offset      offset'
                               :distinct?   distinct?
                               :lookahead?  lookahead?
                               :inference?  inference?
                               :enrichment? (= enrichment "true")}))
                  (catch ExceptionInfo e
                    (let [anomaly   (anomaly/translate e)
                          languages (resp/request->languages request)
                          title     (i18n/da-en languages
                                      "SPARQL-valideringsfejl"
                                      "SPARQL validation error")]
                      (assoc ctx
                        :sparql {:input {:query query-str}}
                        :response-status (:status anomaly)
                        :content {:languages languages
                                  :input     {:query query-str}
                                  :anomaly   anomaly}
                        :page-meta {:title title
                                    :page  "sparql"}))))
                ctx)))})

(def max-label-resources
  "Maximum number of unique resources to fetch labels for in SPARQL results.
  Prevents excessive label queries for very large result sets."
  500)

(defn- collect-keywords
  "Collect all unique keywords from SPARQL SELECT result `rows`."
  [rows]
  (into #{} (comp (mapcat vals) (filter keyword?)) rows))

(defn- enrich-select-result
  "Enrich a SELECT result with blank node data and optionally resource labels.
  Converts the ResultSetMem to rows, resets it for downstream consumers,
  then attaches :blank-nodes and (when `enrichment?`) :k->label to `content`."
  [content ^ResultSetMem sparql-result enrichment?]
  (let [rows (resp/sparql-result->rows sparql-result)
        g    (:graph @instance/db)
        kws  (collect-keywords rows)]
    (.reset sparql-result)
    (cond-> (assoc content :blank-nodes (q/collect-blank-nodes g rows))
      (and enrichment? (seq kws) (<= (count kws) max-label-resources))
      (assoc :k->label (let [entity-label* (shared/->entity-label-fn :normal)]
                         (update-vals (q/resource-labels g kws)
                                      entity-label*))))))

(def sparql-execution-ic
  "Execute the validated SPARQL query, or render the editor page if no query.

  Delegates to sparql/execute-cached which handles caching, request coalescing,
  model selection (base vs inference), and the N+1 pagination lookahead.

  For SELECT results, collects blank node entity data so that the UI can render
  blank nodes inline, and fetches labels for all keyword resources in the result
  so that the UI can display human-readable labels (works for both SSR and SPA)."
  {:name  ::sparql-execution
   :enter (fn [{:keys [sparql request] :as ctx}]
            (let [{:keys [input query-obj noop? limit offset lookahead? enrichment?]} sparql
                  languages (resp/request->languages request)]
              (cond
                ;; Validation error -> :content already set, pass through.
                (:content ctx)
                ctx

                ;; Noop -> return normalized query without executing.
                (and query-obj noop?)
                (assoc ctx :content {:normalized-query (str query-obj)})

                ;; Valid query -> execute (with caching) and return results.
                query-obj
                (let [result (sparql/execute-cached @instance/db sparql)]
                  (if-let [anomaly (:anomaly result)]
                    (assoc ctx
                      :response-status (:status anomaly)
                      :content {:input     input
                                :languages languages
                                :anomaly   anomaly}
                      :page-meta {:title (i18n/da-en languages
                                           "SPARQL-fejl" "SPARQL error")
                                  :page  "sparql"})
                    (let [{:keys [sparql-result sparql-type]} result
                          content (cond-> (assoc result
                                            :input input
                                            :languages languages
                                            :limit limit
                                            :offset offset
                                            :lookahead? lookahead?)
                                    (= sparql-type :select)
                                    (enrich-select-result sparql-result enrichment?))]
                      (assoc ctx
                        :content content
                        :page-meta {:title (i18n/da-en languages
                                             "SPARQL-result" "SPARQL result")
                                    :page  "sparql"}))))

                ;; No query -> render the SPARQL editor page.
                :else
                (assoc ctx
                  :content {:input     input
                            :languages languages}
                  :page-meta {:title (i18n/da-en languages
                                       "SPARQL-editor" "SPARQL editor")
                              :page  "sparql"}))))})

;; TODO: should have a differentiated rate limit (more limited)
(def sparql-route
  [prefix/sparql-path
   :any [content-negotiation-ic
         language-negotiation-ic
         explicit-params-ic
         sparql-validation-ic
         sparql-execution-ic
         response-body-ic]
   :route-name ::sparql])

(def metadata-ic
  {:name  ::metadata
   :enter (fn [{:keys [request] :as ctx}]
            (let [languages (resp/request->languages request)]
              (assoc ctx
                :content {:languages languages
                          :catalog   (catalog/find-resources (:graph @instance/db))}
                :page-meta {:title (i18n/da-en languages "Metadata" "Metadata")
                            :page  "metadata"})))})

(def metadata-route
  [prefix/metadata-path
   :get [content-negotiation-ic
         language-negotiation-ic
         explicit-params-ic
         metadata-ic
         response-body-ic]
   :route-name ::metadata])

(def relations-ic
  {:name  ::relations
   :enter (fn [{:keys [request] :as ctx}]
            (let [languages (resp/request->languages request)]
              (assoc ctx
                :content {:languages languages
                          :relations @instance/synset-rels}
                :page-meta {:title (i18n/da-en languages
                                     "Synset-relationer" "Synset relations")
                            :page  "relations"})))})

(def relations-route
  [prefix/relations-path
   :get [content-negotiation-ic
         language-negotiation-ic
         explicit-params-ic
         relations-ic
         response-body-ic]
   :route-name ::relations])

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
   :enter (fn [{:keys [request] :as ctx}]
            (let [document  (-> request :path-params :document)
                  languages (resp/request->languages request)
                  md-pages  (page-langstrings document)]
              (when (not-empty md-pages)
                (assoc ctx
                  :content {:languages languages
                            :content   md-pages}
                  :page-meta {:page "markdown"}))))})

(def markdown-route
  [prefix/markdown-path
   :get [content-negotiation-ic
         language-negotiation-ic
         explicit-params-ic
         markdown-ic
         response-body-ic]
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

(def schema-download-route
  (let [handler (fn [{:keys [path-params] :as request}]
                  (let [{:keys [prefix]} path-params
                        path     (prefix/prefix->schema-path (symbol prefix))
                        filename (last (str/split path #"/"))
                        cd       (str "attachment; filename=\"" filename "\"")]
                    (-> (ring/resource-response path)
                        (assoc-in [:headers "Cache-Control"] resp/one-day-cache)
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
                        (assoc-in [:headers "Cache-Control"] resp/one-day-cache)
                        (assoc-in [:headers "Content-Disposition"] cd))))]
    ["/export/:type/:prefix" :get handler :route-name ::export]))

;; TODO: move elsewhere, it's not the right ns for this
(def not-in-theme
  "Predicate for filtering colours with a certain HSV distance from theme."
  (let [dist-check (fn [theme-color]
                     (fn [other-color]
                       (> (col/dist-hsv theme-color other-color) 0.33)))]
    (apply every-pred (map (comp dist-check col/css) shared/theme))))

;; TODO: move elsewhere, it's not the right ns for this
(defn generate-synset-rels-theme
  "Generate list of in-use synset relation types and map it to unique colours."
  []
  (let [fixed-rels  [:wn/hypernym
                     :wn/hyponym
                     :wn/domain_topic
                     :wn/has_domain_topic
                     :dns/orthogonalHypernym
                     :dns/orthogonalHyponym]
        other-rels  [:wn/ili
                     :dns/linkedConcept                     ; inverse of wn:ili
                     :wn/eq_synonym
                     :dns/eqHyponym
                     :dns/eqHypernym
                     :dns/eqSimilar]
        fixed-theme (zipmap fixed-rels (map col/css shared/theme))
        colors      (->> (concat cat/cat20 cat/cat20b cat/cat20c)
                         (map col/int24)
                         (filter not-in-theme)
                         (map col/as-css))
        rels        (->> (q/run (:graph @instance/db) op/synset-relation-types)
                         (map '?rel)
                         (remove (set fixed-rels))
                         (remove (set other-rels)))
        num-colours (count colors)
        num-rels    (count rels)]
    (when (> (count rels) (count colors))
      (throw (ex-info (str "Not enough colours available: only "
                           num-colours " colors for " num-rels " rels")
                      {:colors colors
                       :rels   rels})))
    (into (sorted-map)
          (update-vals (merge fixed-theme (zipmap rels colors)) deref))))

;; TODO: clean up this comment block, e.g. move some lines to other namespaces
(comment
  ;; Generate the theme used for e.g. radial diagrams
  (generate-synset-rels-theme)

  (meta (q/expanded-entity (:graph @instance/db) :ontolex/isEvokedBy))
  (q/entity (:graph @instance/db) :dn/synset-78300)
  (require '[dk.cst.dannet.db.export.rdf :as export.rdf])
  (let [subject :dn/synset-78300
        entity  (q/expanded-entity (:graph @instance/db) subject)]
    (export.rdf/ttl-entity entity))

  (q/entity (:graph @instance/db) :dn/synset-46015)

  ;; Test for existence of duplicate ontotypes
  (->> (q/run (:graph @instance/db) '[:bgp
                                      [?s1 :dns/ontologicalType ?o1]
                                      [?s1 :dns/ontologicalType ?o2]])
       (filter (fn [{:syms [?o1 ?o2]}] (not= ?o1 ?o2))))

  ;; TODO: systematic polysemy
  (-> (->> (q/run (:graph @instance/db) op/synset-intersection)
           (group-by (fn [{:syms [?ontotype ?otherOntotype]}]
                       (into #{} [?ontotype ?otherOntotype])))))

  ;; Other examples: "brun kartoffel", "åbne vejen for", "snakkes ved"
  (q/run (:graph @instance/db) [:bgp
                                ['?word :ontolex/canonicalForm '?form]
                                ['?form :ontolex/writtenRep "fandens karl"]])

  ;; Return all DanNet words that have identical PoS and writtenRep (issue #35)
  (->> (q/run (:graph @instance/db) op/word-clones)
       (filter (fn [{:syms [?w1 ?w2]}]
                 (and (= "dn" (namespace ?w1))
                      (= "dn" (namespace ?w2)))))
       (group-by (juxt '?writtenRep '?pos))
       (count))

  ;; Store the synset indegrees (the file is used during bootstrap).
  ;; The default lands among the export artifacts, ready to attach to the release.
  (q/save-synset-indegrees! (:graph @instance/db))

  ;; Or write it straight to a location that gets read: the legacy db/ override
  ;; (which takes precedence), or the version dir of the release being produced,
  ;; where the next cycle will look for it once release/from is bumped.
  (require '[dk.cst.dannet.release :as release])
  (q/save-synset-indegrees! (:graph @instance/db) (first q/indegrees-files))
  (q/save-synset-indegrees! (:graph @instance/db) (q/indegrees-file release/to))

  ;; Find unlabeled senses (count: 0)
  (count (q/run (:graph @instance/db) op/unlabeled-senses))

  #_.)
