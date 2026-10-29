(ns dk.cst.dannet.web.resources
  "Pedestal interceptors (-ic) and their associated routes.

  Most routes negotiate the media type and language, then a handler
  interceptor puts :content and :page-meta, or a :redirect or :replace
  location, on the ctx. redirect-ic and response-body-ic turn these into the
  response; a ctx left without them becomes a 404."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [dk.cst.dannet.web.sparql :as sparql]
            [dk.simongray.conneg :as conneg]
            [dk.simongray.conneg.pedestal :as conneg.pedestal]
            [io.pedestal.http.body-params :refer [body-params]]
            [io.pedestal.http.route :refer [decode-query-part]]
            [io.pedestal.interceptor :as interceptor]
            [ring.util.response :as ring]
            [taoensso.telemere :as tel]
            [dk.cst.theophrastus :as theo]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.web.i18n :as i18n]
            [dk.cst.dannet.web.anomaly :as anomaly]
            [dk.cst.dannet.web.response :as resp]
            [dk.cst.dannet.web.ui.markdown :as mdc]
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

(def negotiation
  "The media types and languages that DanNet offers.

  The ?format= and ?lang= query parameters override the Accept fields, and
  so does the :languages cookie for Accept-Language. They take the layman's
  terms below as well as MIME types and language tags."
  {;; HTML first, so a client with no preference gets HTML
   :media-type {:offers  ["text/html"
                          "text/turtle"
                          "application/edn"
                          "application/ld+json"
                          "application/sparql-results+json"
                          "application/json"
                          "application/transit+json"]
                :query   "format"
                :aliases {"json"    "application/json"
                          "json-ld" "application/ld+json"
                          "edn"     "application/edn"
                          "transit" "application/transit+json"
                          "turtle"  "text/turtle"
                          "ttl"     "text/turtle"           ; common alias
                          "html"    "text/html"}}

   ;; English first, so a client with no preference gets English
   :language   {:offers  ["en" "da"]
                :default "en"
                :query   "lang"
                :aliases {"danish"  "da"
                          "english" "en"}
                :cookie  {:name "languages" :read edn/read-string}}

   ;; pages read other cookies too, and the dev-only CORS headers read Origin
   :vary       (cond-> ["Cookie"] shared/development? (conj "Origin"))})

(def negotiation-ic
  (conneg.pedestal/negotiate negotiation))

(def media-type-negotiation-ic
  "Negotiation for routes whose content doesn't depend on the language."
  (conneg.pedestal/negotiate (select-keys negotiation [:media-type :vary])))

;; NOTE: redirect doesn't work on shadow-cljs port, but works fine otherwise!
(def redirect-ic
  "Turn the :redirect or :replace location in the ctx into a response: a 303
  for HTML and JSON, and Transit data for the SPA, whose fetch can't intercept
  a 30x.

  :replace swaps the current history entry instead of adding one, e.g. for an
  alt entity, so the back button still works. Only :lang and :format of the
  :redirect-params carry over, into the JSON location."
  {:name  ::redirect
   :enter (fn [{:keys [redirect redirect-params replace request] :as ctx}]
            (let [{:keys [lang format]} redirect-params
                  content-type (or (get-in request [::conneg/decision :media-type])
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

                              ;; The SPA can't intercept a 30x from fetch, so it
                              ;; gets the redirect as data.
                              "application/transit+json"
                              {:status  200
                               :headers {"Content-Type" content-type}
                               :body    (resp/->transit {:redirect location
                                                         :replace? (boolean replace)})}

                              ;; Simple redirect for JSON - let HTTP client follow the redirect
                              "application/json"
                              {:status  303
                               :headers {"Location" api-location}}

                              ;; Turtle, JSON-LD and EDN clients follow a plain
                              ;; redirect too.
                              {:status  303
                               :headers {"Location" location}}))
                ctx)))})

(def response-body-ic
  "Generate a response containing the content body (if available)."
  {:name  ::response-body
   :leave (fn [{:keys [request content page-meta] :as ctx}]
            (let [content-type (or (get-in request [::conneg/decision :media-type])
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
                            (and (empty? content) (empty? page-meta))
                            {:status  404
                             :headers {}}

                            :else
                            {:status (or (:response-status ctx) 200)
                             :body   (body-fn (resp/with-cookies request content)
                                              (resp/->page request content page-meta))}))
                  (update-in [:response :headers] merge
                             (-> {"Content-Type"  content-type
                                  "Cache-Control" resp/one-day-cache}
                                 (merge (resp/attachment-header title content-type)))))))})

(def error-ic
  "Error-handling interceptor in front of the router (see web.service).

  Translates any unhandled exception into an anomaly via 'anomaly/translate'
  and renders it through the same 'resp/content-type->body-fn' machinery used
  for normal responses. Logs the original exception via Telemere at :error
  level.

  Catches errors from any interceptor enqueued after it, including the router
  and all per-route interceptors. Since content negotiation is a per-route
  interceptor, it may not have run before the error was thrown; in that case
  the content type comes from the Accept header alone."
  (interceptor/interceptor
    {:name  ::error
     :error (fn [ctx ex]
              ;; Pedestal wraps what an interceptor throws in an ex-info,
              ;; keeping the original under :exception.
              (let [cause        (or (some-> ex ex-data :exception) ex)
                    request      (:request ctx)
                    languages    (resp/request->languages request)
                    anomaly      (-> (anomaly/translate cause)
                                     (anomaly/localize languages))
                    content-type (resp/error-content-type request)
                    body-fn      (resp/content-type->body-fn content-type)
                    title        (i18n/da-en languages "Fejl" "Error")
                    content      {:languages languages
                                  :anomaly   anomaly}
                    page-meta    {:title title :page :error}
                    ;; Pedestal skips the :leave that adds Vary after an
                    ;; exception. Without a decision, Accept sets the type.
                    vary         (conneg/vary-header
                                   (get-in request [::conneg/decision :vary]
                                           (cons "Accept" (:vary negotiation))))]
                (tel/log! {:level :error
                           :error cause
                           :data  {:uri    (:uri request)
                                   :method (:request-method request)}}
                          "Unhandled exception")
                (assoc ctx
                  :response {:status  (:status anomaly)
                             :headers (cond-> {"Content-Type" content-type}
                                        vary (assoc "Vary" vary))
                             :body    (body-fn (resp/with-cookies request content)
                                               (resp/->page request content
                                                            page-meta))})))}))

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
    (str/replace query-string #"&?(transit=[^&]*|format=turtle)" "")))

(def linked-media-types
  "The media types of an entity that get a URL in its Link header and
  Content-Location: HTML and the formats of the download links."
  #{"text/html" "text/turtle" "application/ld+json"})

(def language-media-types
  "The media types whose content depends on the language."
  #{"text/html" "application/transit+json" "application/edn"})

(defn entity-href
  "The URL of the entity `request` in the `offers`, or nil unless the media
  type is one of linked-media-types.

  The URL leaves out the internal query parameters, and the language when
  the media type doesn't depend on it."
  [request {:keys [media-type] :as offers}]
  (let [media-type' (or media-type (get-in request [::conneg/decision :media-type]))]
    (when (linked-media-types media-type')
      (some->> (cond-> offers
                 (not (language-media-types media-type')) (dissoc :language))
               (not-empty)
               (conneg/query-href negotiation
                                  (update request :query-string remove-internal-params))))))

(def entity-negotiation-ic
  "Negotiation for entities, with a Link to each other representation in
  linked-media-types and the Content-Location of the chosen one."
  (conneg.pedestal/negotiate (assoc negotiation :href entity-href)
                             {:link? true :content-location? true}))

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

(defn params->subject
  "The subject that the request `params` ask for: a keyword or an RDF
  resource, or nil when the prefix of the subject is unknown."
  [{:keys [prefix subject]}]
  ;; TODO: why is decoding necessary?
  ;; You would think that the path-params-decoder handled this.
  ;; NB: the path-params-decoder has already percent-decoded the subject, so
  ;; literal + (e.g. dnc:Human+Object) must be kept out of reach of
  ;; decode-query-part's +-as-space rule.
  (let [s (some-> subject
                  (str/replace "+" "%2B")
                  (decode-query-part))]
    (cond
      ;; A keyword with an unknown prefix has no IRI to look up.
      prefix
      (when (get prefix/prefixes (keyword (name prefix)))
        (keyword (name prefix) s))

      ;; A generic consumer such as the DMLex browser sends a bare URI, not a
      ;; bracketed RDF resource.
      (and s (re-find #"^https?://" s))
      (prefix/uri->rdf-resource s)

      :else s)))

(defn- displayed-entity
  "Prepare the `raw-entity` of `subject` for display in the browser.

  Superproperty rows entailed by other rows are folded into them, and large
  semantic relations are truncated. Returns {:entity :folded :has-deferred},
  where :entity is the overflow of the truncated relations when `deferred?`
  and there is any."
  [subject raw-entity deferred?]
  (let [k->supers @instance/superproperty-closure
        {:keys [entity folded]}
        (-> (ent/prune-entailed-superproperties k->supers raw-entity)
            (update :entity #(ent/prune-self-references subject %))
            (update :entity #(ent/prune-embedded-entities k->supers %)))
        {:keys [truncated deferred has-deferred]}
        (ent/truncate-semantic-relations entity)]
    {:entity       (if (and deferred? (not-empty deferred))
                     deferred
                     truncated)
     :folded       folded
     :has-deferred has-deferred}))

(defn- sunburst-trees
  "The subtrees of the synset `subject` shown as sunburst diagrams: hyponyms,
  orthogonal hyponyms and meronyms, keyed as in the page data."
  [g languages subject]
  (let [orthogonal-only (:orthogonal-only @instance/hyponym-graph)
        ->tree          (fn [graph root-filter]
                          (hyponymy/hyponym-tree g graph languages
                                                 root-filter subject))]
    (into {} (filter val)
          {:hyponym-tree            (->tree @instance/hyponym-graph
                                            (complement orthogonal-only))
           :orthogonal-hyponym-tree (->tree @instance/hyponym-graph
                                            orthogonal-only)
           :meronym-tree            (->tree @instance/meronym-graph any?)})))

(defn- entity-content
  "The page data for `subject` in the `request`, made from its `raw-entity`
  and the part of it that is `shown` (see displayed-entity)."
  [request subject raw-entity {:keys [entity folded] :as shown}
   {:keys [truncate? deferred?]}]
  (let [languages (resp/request->languages request)
        qs        (some-> (:query-string request) remove-internal-params)
        synset?   (and truncate?
                       (not deferred?)
                       (shared/synset? subject raw-entity))]
    (-> (meta raw-entity)
        (update :entities dissoc subject)
        (assoc :languages languages
               :href (str (:uri request) (when (not-empty qs) (str "?" qs)))
               :subject subject
               :entity entity)
        (cond->
          ;; Display-only payload trimming: the deferred response carries the
          ;; overflow labels, and the UI checks :inferred keys, never values.
          truncate?
          (update :entities select-keys
                  (cond-> (ent/displayed-resources entity)
                    (not deferred?)
                    (into (mapcat #(cons (key %) (val %)) folded))))

          (and truncate? (not deferred?))
          (update :inferred (comp set keys))

          deferred?
          (dissoc :inferred :supplemented :ancestry)

          (and (not deferred?) (not-empty folded))
          (assoc :folded folded)

          synset?
          (merge (sunburst-trees (:inference @instance/db) languages subject))))))

(defn- missing-entity
  "Send `ctx` on to where the missing `subject` (shown as `qname`) can be
  found instead, or leave it as it is, which becomes a 404."
  [ctx g subject qname]
  (let [alt      (alt-resource qname)
        uri      (cond
                   (keyword? subject) (prefix/kw->uri subject)
                   (string? subject) (prefix/rdf-resource->uri subject))
        subsumer (ffirst (theo/triples g ['_ :dns/subsumed subject]))]
    (cond
      (and alt (not-empty (q/entity g alt)))
      (assoc ctx :replace alt)

      ;; The id of a merged or removed duplicate leads to the resource that
      ;; subsumed it.
      subsumer
      (assoc ctx :redirect subsumer)

      ;; A DanNet IRI resolves to this page, so a redirect to it would loop;
      ;; the empty context becomes a 404 instead.
      (and uri (prefix/uri->dannet-path uri))
      ctx

      :else
      (assoc ctx :redirect uri))))

(defn ->entity-ic
  "Create an interceptor to return DanNet resources, optionally specifying a
  predetermined `prefix` or a fixed `subject` to use for graph look-ups;
  otherwise locates the prefix within the path-params.

  When the content-type supports truncation (HTML, Transit) and the entity has
  large semantic relations, values are truncated to `semantic-relation-limit`.
  The remaining data can be fetched by adding `?deferred=true` to the request."
  [& {:keys [prefix subject] :as static-params}]
  {:name  ::entity
   :enter (fn [{:keys [request] :as ctx}]
            (let [params       (merge (:path-params request)
                                      (:query-params request)
                                      static-params)
                  subject      (params->subject params)
                  qname        (if (keyword? subject)
                                 (prefix/kw->qname subject)
                                 subject)
                  content-type (or (get-in request [::conneg/decision :media-type])
                                   "application/json")
                  g            (:inference @instance/db)
                  raw-entity   (when subject
                                 (if (expand-content-types content-type)
                                   (q/expanded-entity g subject)
                                   (q/entity g subject)))
                  truncate?    (truncate-content-types content-type)
                  deferred?    (boolean (and truncate? (:deferred params)))
                  shown        (if (and truncate? (not-empty raw-entity))
                                 (displayed-entity subject raw-entity deferred?)
                                 {:entity raw-entity})]
              (cond
                ;; An unknown prefix or a missing ?subject leaves nothing to
                ;; look up; the empty context becomes a 404.
                (nil? subject)
                ctx

                (not-empty (:entity shown))
                (assoc ctx
                  :content (entity-content request subject raw-entity shown
                                           {:truncate? truncate?
                                            :deferred? deferred?})
                  :page-meta (cond-> {:title qname
                                      :page  :entity}
                               (and (:has-deferred shown) (not deferred?))
                               (assoc :deferred? true)))

                :else
                (missing-entity ctx g subject qname))))})

(defn prefix->entity-route
  "Internal entity look-up route for a specific `prefix`. Looks up the prefix in
  a map of URIs and creates a local, relative path based on this URI."
  [prefix]
  [(str (-> prefix prefix/schemas :uri prefix/uri->path) ":subject")
   :get [entity-negotiation-ic
         (->entity-ic :prefix prefix)
         redirect-ic
         response-body-ic]
   :route-name (keyword (str *ns*) (str prefix "-entity"))])

(def external-entity-route
  "Look-up route for external resources. Doesn't conform to the actual URIs."
  [(str prefix/external-path "/:prefix/:subject")
   :get [entity-negotiation-ic
         (->entity-ic)
         redirect-ic
         response-body-ic]
   :route-name ::external-entity])

(def unknown-external-entity-route
  "Look-up route for the resource in ?subject=, as made by prefix/resource-path
  for IRIs with no known prefix."
  [prefix/external-path
   :get [entity-negotiation-ic
         (->entity-ic)
         redirect-ic
         response-body-ic]
   :route-name ::unknown-external-entity])

(defn prefix->dataset-entity-route
  "Route for the resource of `prefix` itself, e.g. the dataset
  <https://wordnet.dk/dannet/data>, whose path has no :subject."
  [prefix]
  (let [uri (-> prefix prefix/prefix->uri prefix/remove-trailing-separator)]
    [(prefix/uri->path uri)
     :get [entity-negotiation-ic
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
                (let [results (look-up-any-case (:inference @instance/db) lemma)]
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
                                  :page  :search}))))))})

(def search-route
  [prefix/search-path
   :get [negotiation-ic
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
              ;; a blank string would match every entry in the search index
              (when-let [s' (not-empty (shared/search-string s))]
                (assoc ctx
                  :content {:autocompletions (instance/autocomplete s')}))))})

(def autocomplete-route
  [autocomplete-path
   :get [media-type-negotiation-ic
         autocomplete-ic
         response-body-ic]
   :route-name ::autocomplete])

(defn sparql-page-meta
  "The page-meta of a SPARQL page titled `da` or `en`, as `languages` prefer."
  [languages da en]
  {:title (i18n/da-en languages da en)
   :page  :sparql})

(defn- sparql-query-opts
  "The :sparql map of the parsed `query-obj` of `query-str`, with the
  execution options read from the `query-params`."
  [query-str query-obj {:keys [timeout limit offset
                               distinct inference lookahead noop enrichment]}]
  (let [timeout'   (if (not-empty timeout)
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
    {:input       {:query      query-str
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

(defn- sparql-validation-error
  "Put the error page for the invalid `query-str` on the `ctx`, with the
  status and message of the validation exception `e`."
  [{:keys [request] :as ctx} query-str e]
  (let [anomaly   (anomaly/translate e)
        languages (resp/request->languages request)]
    (assoc ctx
      :sparql {:input {:query query-str}}
      :response-status (:status anomaly)
      :content {:languages languages
                :input     {:query query-str}
                :anomaly   anomaly}
      :page-meta (sparql-page-meta languages
                                   "SPARQL-valideringsfejl"
                                   "SPARQL validation error"))))

(def sparql-validation-ic
  "Validate and parse SPARQL query parameters, placing results in a single
  `:sparql` map on the ctx. On validation failure, short-circuits by setting
  `:content`/`:page-meta`/`:response-status` directly so that response-body-ic
  handles the error response normally.

  Three code paths reach sparql-execution-ic downstream:
    1. Valid query  -> :sparql has :query-obj, execution proceeds.
    2. Invalid query -> :content/:page-meta already set (400), execution skips.
    3. No query     -> the ctx has no :sparql, editor page is rendered."
  {:name  ::sparql-validation
   :enter (fn [{:keys [request] :as ctx}]
            (let [{:keys [query-params body]} request
                  query-str (or (:query query-params)
                                (when (string? body) body))]
              (if query-str
                (try
                  (let [query-obj (sparql/validate query-str)]
                    (assoc ctx :sparql (sparql-query-opts query-str query-obj
                                                          query-params)))
                  (catch ExceptionInfo e
                    (sparql-validation-error ctx query-str e)))
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
        g    (:inference @instance/db)
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
                      :page-meta (sparql-page-meta languages
                                                   "SPARQL-fejl" "SPARQL error"))
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
                        :page-meta (sparql-page-meta languages
                                                     "SPARQL-result"
                                                     "SPARQL result")))))

                ;; No query -> render the SPARQL editor page.
                :else
                (assoc ctx
                  :content {:input     input
                            :languages languages}
                  :page-meta (sparql-page-meta languages
                                               "SPARQL-editor" "SPARQL editor")))))})

;; TODO: should have a differentiated rate limit (more limited)
(def sparql-route
  [prefix/sparql-path
   :any [negotiation-ic
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
                          :catalog   (catalog/find-resources (:inference @instance/db))}
                :page-meta {:title (i18n/da-en languages "Metadata" "Metadata")
                            :page  :metadata})))})

(def metadata-route
  [prefix/metadata-path
   :get [negotiation-ic
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
                            :page  :relations})))})

(def relations-route
  [prefix/relations-path
   :get [negotiation-ic
         relations-ic
         response-body-ic]
   :route-name ::relations])

(defn page-hiccup
  "Return the Markdown pages for the `document` as Hiccup, keyed by language."
  [document]
  (let [md-pattern' (re-pattern (str document "-(.+)\\.md"))
        xf          (comp
                      (map (fn [f]
                             (when-let [lang (some->> (.getName ^File f)
                                                      (re-matches md-pattern')
                                                      (second))]
                               [lang (mdc/md->hiccup (slurp f))])))
                      (remove nil?))]
    (into {} xf (file-seq (io/file "pages/")))))

;; TODO: an unknown document, e.g. /dannet/page/nonexistent, gives an empty
;;       200 response, since the interceptor returns nil in place of the ctx.
;;       Returning the ctx unchanged would give a 404 instead.
(def markdown-ic
  "Returns a generic, localised markdown page for the given given page."
  {:name  ::markdown
   :enter (fn [{:keys [request] :as ctx}]
            (let [document  (-> request :path-params :document)
                  languages (resp/request->languages request)
                  md-pages  (page-hiccup document)]
              (when (not-empty md-pages)
                (assoc ctx
                  :content {:languages languages
                            :content   md-pages}
                  :page-meta {:title (->> (filter md-pages languages)
                                          (first)
                                          (get md-pages)
                                          (mdc/hiccup->title))
                              :page  :markdown}))))})

(def markdown-route
  [prefix/markdown-path
   :get [negotiation-ic
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

;; TODO: clean up this comment block, e.g. move some lines to other namespaces
(comment
  ;; thi.ng/color is not a dependency; add-lib loads it into the running REPL.
  (require 'clojure.repl.deps)
  (clojure.repl.deps/add-lib 'thi.ng/color {:mvn/version "1.5.1"})
  (require '[thi.ng.color.core :as col]
           '[thi.ng.color.presets.categories :as cat])

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
          other-rels  [:wn/ili
                       :dns/iliOf                           ; inverse of wn:ili
                       :wn/eq_synonym
                       :dns/eqHyponym
                       :dns/eqHypernym
                       :dns/eqSimilar]
          fixed-theme (zipmap fixed-rels (map col/css shared/theme))
          colors      (->> (concat cat/cat20 cat/cat20b cat/cat20c)
                           (map col/int24)
                           (filter not-in-theme)
                           (map col/as-css))
          rels        (->> (theo/q (:inference @instance/db) op/synset-relation-types)
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

  ;; Generate the theme used for e.g. radial diagrams
  (generate-synset-rels-theme)

  (meta (q/expanded-entity (:inference @instance/db) :ontolex/isEvokedBy))
  (q/entity (:inference @instance/db) :dn/synset-78300)
  (require '[dk.cst.dannet.db.export.rdf :as export.rdf])
  (let [subject :dn/synset-78300
        entity  (q/expanded-entity (:inference @instance/db) subject)]
    (export.rdf/ttl-entity entity))

  (q/entity (:inference @instance/db) :dn/synset-46015)

  ;; Test for existence of duplicate ontotypes
  (->> (theo/q (:inference @instance/db) "SELECT ?s1 ?o1 ?o2
                                          WHERE {
                                            ?s1 dns:ontologicalType ?o1 , ?o2 .
                                          }")
       (filter (fn [{:syms [?o1 ?o2]}] (not= ?o1 ?o2))))

  ;; TODO: systematic polysemy
  (-> (->> (theo/q (:inference @instance/db) op/synset-intersection)
           (group-by (fn [{:syms [?ontotype ?otherOntotype]}]
                       (into #{} [?ontotype ?otherOntotype])))))

  ;; Other examples: "brun kartoffel", "åbne vejen for", "snakkes ved"
  (theo/q (:inference @instance/db) "SELECT ?word ?form
                                     WHERE {
                                       ?word ontolex:canonicalForm ?form .
                                       ?form ontolex:writtenRep \"fandens karl\"@da .
                                     }")

  ;; Return all DanNet words that have identical PoS and writtenRep (issue #35)
  (->> (theo/q (:inference @instance/db) op/word-clones)
       (filter (fn [{:syms [?w1 ?w2]}]
                 (and (= "dn" (namespace ?w1))
                      (= "dn" (namespace ?w2)))))
       (group-by (juxt '?writtenRep '?pos))
       (count))

  ;; Store the synset indegrees (the file is used during bootstrap).
  ;; The default lands among the export artifacts, ready to attach to the release.
  (q/save-synset-indegrees! (:inference @instance/db))

  ;; Or write it straight to a location that gets read: the legacy db/ override
  ;; (which takes precedence), or the version dir of the release being produced,
  ;; where the next cycle will look for it once release/from is bumped.
  (require '[dk.cst.dannet.release :as release])
  (q/save-synset-indegrees! (:inference @instance/db) (first q/indegrees-files))
  (q/save-synset-indegrees! (:inference @instance/db) (q/indegrees-file release/to))

  ;; Find unlabeled senses (count: 0)
  (count (theo/q (:inference @instance/db) op/unlabeled-senses))

  #_.)
