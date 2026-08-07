(ns dk.cst.dannet.web.response
  "Conversion of response data into a content body for each supported type,
  along with the header and request-derived values that accompany it."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [cognitect.transit :as t]
            [com.wsscode.transito :as to]
            [arachne.aristotle.graph :as graph]
            [ont-app.vocabulary.lstr]
            [rum.core :as rum]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.web.i18n :as i18n]
            [dk.cst.dannet.web.ui :as ui]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.db.export.rdf :as export.rdf]
            [dk.cst.dannet.db.export.json-ld :refer [json-ld-ify]]
            [dk.cst.dannet.db.query :as q])
  (:import [java.io ByteArrayOutputStream]
           [java.util Date]
           [ont_app.vocabulary.lstr LangStr]
           [org.apache.jena.datatypes BaseDatatype$TypedValue]
           [org.apache.jena.datatypes.xsd XSDDateTime]
           [org.apache.jena.query ResultSet]
           [org.apache.jena.riot ResultSetMgr]
           [org.apache.jena.riot.resultset ResultSetLang]
           [org.apache.jena.sparql.engine.binding Binding]
           [org.apache.jena.sparql.resultset ResultSetMem]))

(def one-day-cache
  "private, max-age=86400")

;; https://javascript.plainenglish.io/what-is-cache-busting-55366b3ac022
(def version-hash
  "Unique versioning of the frontend app; used for cache busting."
  (abs (hash (Date.))))

(defn html-page
  "A full HTML page ready to be hydrated. Needs a `title`, the user-specific
  `data` (languages and detail level), and the `hiccup` content."
  [title {:keys [languages detail-level] :as data} hiccup]
  (str
    "<!DOCTYPE html>\n"                                     ;; Avoid Quirks Mode
    (rum/render-static-markup
      ;; The :lang attribute declares the negotiated UI language semantically;
      ;; it doubles as the base language for RDFa literals lacking a closer
      ;; @lang and is matched in CSS to hide redundant language superscripts.
      ;; The detail level is mirrored in :data-detail-level since the CSS only
      ;; hides redundant superscripts below the :high detail level.
      [:html {:prefix            prefix/rdfa-prefixes
              :lang              (first languages)
              :data-detail-level (some-> detail-level name)}
       [:head
        [:title title]
        [:meta {:charset "UTF-8"}]
        [:meta {:name    "viewport"
                :content "width=device-width, initial-scale=1.0"}]
        [:link {:rel "stylesheet" :href (str "/css/main.css?hash=" version-hash)}]

        ;; Favicon section
        [:link {:rel "apple-touch-icon" :sizes "180x180" :href "/apple-touch-icon.png"}]
        [:link {:rel "icon" :type "image/png" :sizes "32x32" :href "/favicon-32x32.png"}]
        [:link {:rel "icon" :type "image/png" :sizes "16x16" :href "/favicon-16x16.png"}]
        [:link {:rel "manifest" :href "/site.webmanifest"}]
        [:link {:rel "mask-icon" :href "/safari-pinned-tab.svg" :color "#5bbad5"}]
        [:meta {:name "msapplication-TileColor" :content "#da532c"}]
        [:meta {:name "theme-color" :content "#ffffff"}]]
       [:body
        [:div#app {:dangerouslySetInnerHTML {:__html (rum/render-html hiccup)}}]
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

    ;; Blank nodes from attach-blank-nodes carry their data as metadata.
    (symbol? data)
    (if-let [m (meta data)]
      (->json-safe m)
      (str data))

    (keyword? data) (prefix/kw->qname data)
    (map? data) (into {} (map (fn [[k v]] [(->json-safe k) (->json-safe v)]) data))
    (coll? data) (mapv ->json-safe data)
    :else data))

(defn- typed-value->m
  [o]
  {:value (.-lexicalValue o)
   :uri   (.-datatypeURI o)})

;; TODO: this is a data concern rather than an HTTP one; it may want to follow
;;       find-catalog-resources to the db side.
(defn sparql-result->rows
  "Convert a `sparql-result` to a vector of Clojure maps using Aristotle's
  'graph/data' for node conversion, matching the output format of 'q/run'."
  [^ResultSet sparql-result]
  (when sparql-result
    (mapv (fn [qs]
            (let [^Binding binding (.getBinding qs)]
              (into {}
                    (map (fn [var]
                           [(graph/data var) (graph/data (.get binding var))])
                         (iterator-seq (.vars binding))))))
          (iterator-seq sparql-result))))

(def transit-write-handlers
  {LangStr                 (t/write-handler "lstr" lstr->s)
   BaseDatatype$TypedValue (t/write-handler "rdfdatatype" typed-value->m)
   XSDDateTime             (t/write-handler "datetime" str)
   ResultSetMem            (t/write-handler "array" sparql-result->rows)})

(defn- with-comment
  "Add `s` to the :rdfs/comment of entity `m`, preserving its metadata."
  [m s]
  (with-meta
    (update m :rdfs/comment q/set-merge s)
    (meta m)))

;; TODO: order matters when creating conneg interceptor, should be kvs as
;;       shadow-handler relies on "text/html" being the first key, fix!
(def content-type->body-fn
  {"text/html"
   (fn [data & [{:keys [page title]}]]
     (html-page
       title
       data
       (ui/page-shell page (update data :sparql-result sparql-result->rows))))

   "text/turtle"
   (fn [{:keys [entity href]} & _]
     (when entity
       (export.rdf/ttl-entity entity (str "https://wordnet.dk" href))))

   ;; TODO: should this match the JSON output? Or be used for debugging Transit?
   "application/edn"
   (fn [data & _]
     (pr-str data))

   "application/ld+json"
   (fn [{:keys [entity entities search-results lemma]} & _]
     (let [kv->entity (fn [[subject m]]
                        (assoc m :dc/subject subject))]
       (some-> (cond
                 entity
                 (json-ld-ify
                   (with-comment entity "The @graph contains labels for the properties and values of the core RDF resource defined @id.")
                   (map kv->entity entities))

                 search-results
                 (json-ld-ify
                   {:rdfs/comment (str "The @graph represents an ordered DanNet synset search result for the lemma \"" lemma "\".")}
                   (map kv->entity search-results)))

               (json/write-str {:indent         true
                                :escape-unicode false}))))

   ;; https://www.w3.org/TR/sparql11-results-json/
   "application/sparql-results+json"
   (fn [{:keys [sparql-result]} & _]
     (when sparql-result
       (let [out (ByteArrayOutputStream.)]
         (ResultSetMgr/write out ^ResultSet sparql-result ResultSetLang/RS_JSON)
         (.toString out "UTF-8"))))

   "application/json"
   (fn [data & _]
     (json/write-str (->json-safe data)
                     {:indent         true
                      :escape-unicode false}))

   "application/transit+json"
   (fn [data & _]
     (to/write-str data {:handlers transit-write-handlers}))})

(defn json-body-fn
  "Combined body-fn that prefers specific types of JSON-LD over unspecified JSON
  when they are available."
  [& args]
  (or (apply (content-type->body-fn "application/ld+json") args)
      (apply (content-type->body-fn "application/sparql-results+json") args)
      (apply (content-type->body-fn "application/json") args)))

(defn request->languages
  "Resolve a vector of language preferences from a `request`."
  [request]
  (or (:languages request)
      (i18n/lang-prefs (get-in request [:accept-language :type]))))

(defn with-cookies
  [request data]
  (assoc data
    :full-screen (shared/get-cookie request :full-screen)
    :detail-level (or (shared/get-cookie request :detail-level)
                      :normal)))

;; TODO: eventually support LangStr for titles too
(defn x-headers
  "Encode `page-meta` for a given page as custom HTTP headers.

  See also: dk.cst.dannet.web.router/x-header"
  [page-meta]
  (update-keys page-meta (fn [k] (str "X-" (str/capitalize (name k))))))

(defn with-file-ext
  [title content-type]
  (when-let [extension (get {"application/json"    ".json"
                             "application/ld+json" ".json"
                             "text/turtle"         ".ttl"}
                            content-type)]
    {"Content-Disposition"
     (str "attachment; filename=\""
          (str/replace title #":" "_")
          extension "\"")}))

(defn error-content-type
  "Determine the response content-type for an error response.

  Falls back to sniffing the raw Accept header when content negotiation has not
  run, defaulting to Transit (matching the SPA) for non-HTML clients."
  [request]
  (or (get-in request [:accept :field])
      (let [accept (or (get-in request [:headers "accept"])
                       (get-in request [:headers :accept]))]
        (if (and accept (str/includes? accept "text/html"))
          "text/html"
          "application/transit+json"))))

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
