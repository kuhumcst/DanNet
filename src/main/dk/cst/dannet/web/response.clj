(ns dk.cst.dannet.web.response
  "Conversion of response data into a content body for each supported type,
  along with the header and request-derived values that accompany it."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [cognitect.transit :as t]
            [dk.cst.theophrastus :as theo]
            [dk.cst.theophrastus.sparql :as theo.sparql]
            [dk.cst.theophrastus.term :as term]
            [rum.core :as rum]
            [dk.simongray.conneg :as conneg]
            [dk.simongray.ratelimit :as ratelimit]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.web.i18n :as i18n]
            [dk.cst.dannet.web.sparql :as sparql]
            [dk.cst.dannet.web.ui :as ui]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.db.export.rdf :as export.rdf]
            [dk.cst.dannet.db.export.json-ld :refer [json-ld-ify]]
            [dk.cst.dannet.db.query :as q])
  (:import [java.io ByteArrayOutputStream]
           [java.util Date]
           [dk.cst.theophrastus.term LangString Literal]
           [flatland.ordered.map OrderedMap]
           [org.apache.jena.query ResultSet]
           [org.apache.jena.rdf.model Model]
           [org.apache.jena.sparql.core ResultBinding]
           [org.apache.jena.sparql.resultset ResultSetMem]))

(def one-day-cache
  "private, max-age=86400")

;; https://javascript.plainenglish.io/what-is-cache-busting-55366b3ac022
(def version-hash
  "Unique versioning of the frontend app; used for cache busting."
  (abs (hash (Date.))))

(declare ->transit)

(defn- script-safe
  "Escape the Transit string `s` for a <script> element, which would end at
  any </script> inside it."
  [s]
  (str/replace s "<" "\\u003c"))

(defn html-page
  "A full HTML page for the `page` value, ready to be hydrated.

  The page value is embedded as Transit, so the client hydrates from the same
  data that the server rendered."
  [{:keys [title prefs] :as page}]
  (let [{:keys [languages detail-level]} prefs]
    (str
      "<!DOCTYPE html>\n"                                   ;; Avoid Quirks Mode
      (rum/render-static-markup
        ;; The :lang attribute declares the negotiated UI language semantically;
        ;; it doubles as the base language for RDFa literals lacking a closer
        ;; @lang and is matched in CSS to hide redundant language superscripts.
        ;; The detail level is mirrored in :data-detail-level since the CSS only
        ;; hides redundant superscripts below the :high detail level.
        ;; The frontend reads the development flag and the name of its own JS
        ;; file from data attributes.
        [:html {:prefix            prefix/rdfa-prefixes
                :lang              (i18n/da-en languages "da" "en")
                :data-detail-level (some-> detail-level name)
                :data-development  (str shared/development?)
                :data-js           shared/main-js}
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
          [:div#app {:dangerouslySetInnerHTML
                     {:__html (rum/render-html (ui/page-shell page))}}]
          [:script {:id                      "page-data"
                    :type                    "application/transit+json"
                    :dangerouslySetInnerHTML {:__html (script-safe (->transit page))}}]
          [:script {:src (str "/js/compiled/" shared/main-js)}]]]))))

(defn- ->json-safe
  "Convert RDF data to JSON-compatible format."
  [data]
  (cond
    (instance? LangString data)
    {:value (str data) :lang (term/lang data)}

    (instance? Literal data)
    {:value    (str data)
     :datatype (prefix/rdf-resource->uri (term/datatype data))}

    (symbol? data) (str data)
    (keyword? data) (or (prefix/kw->qname data) (name data))
    ;; the :rdf/about of a blank node is left out
    (map? data) (into {}
                      (map (fn [[k v]] [(->json-safe k) (->json-safe v)]))
                      (dissoc data :rdf/about))
    (coll? data) (mapv ->json-safe data)
    :else data))

;; TODO: this is a data concern rather than an HTTP one; it may want to follow
;;       catalog/find-resources to the db side.
(defn sparql-result->rows
  "Convert a `sparql-result` to a vector of Clojure maps, as theo/q returns
  them. The Model of a CONSTRUCT or DESCRIBE query has a row per triple."
  [sparql-result]
  (cond
    (instance? ResultSet sparql-result)
    (mapv #(theo.sparql/row (.getBinding ^ResultBinding %) prefix/prefixes)
          (iterator-seq sparql-result))

    ;; A copy, since a db of the Model itself would replace the prefixes of
    ;; the cached result.
    (instance? Model sparql-result)
    (let [db (theo/db {:prefixes prefix/prefixes :transactional? false})]
      (theo/transact! db (.getGraph ^Model sparql-result))
      (mapv #(zipmap sparql/graph-result-vars %)
            (theo/triples db '[_ _ _])))))

;; The RDF terms have the tags and values of their reader tags, e.g.
;; #rdf/langstring ["hund" "da"], so the browser reads them as the same types.
;; ClojureScript reads a plain map of more than 8 entries as a hash map, which
;; loses the order of e.g. the search results, so an ordered map gets a tag.
(def transit-write-handlers
  {LangString   (t/write-handler "rdf/langstring" (juxt str term/lang))
   Literal      (t/write-handler "rdf/literal" (juxt str term/datatype))
   OrderedMap   (t/write-handler "omap" vec)
   ResultSetMem (t/write-handler "array" sparql-result->rows)
   Model        (t/write-handler "array" sparql-result->rows)})

(def transit-default-handler
  "Write a value of a type with no Transit handler as its printed form."
  (t/write-handler (constantly "unknown") pr-str))

(defn ->transit
  "Write `x` as a Transit JSON string."
  [x]
  (let [out (ByteArrayOutputStream.)]
    (t/write (t/writer out :json {:handlers        transit-write-handlers
                                  :default-handler transit-default-handler
                                  :transform       t/write-meta})
             x)
    (.toString out "UTF-8")))

(defn- with-comment
  "Add `s` to the :rdfs/comment of entity `m`, preserving its metadata."
  [m s]
  (with-meta
    (update m :rdfs/comment q/set-merge s)
    (meta m)))

(def content-type->body-fn
  "Body fns by media type, called with the content (plus the cookie prefs, see
  `with-cookies`) and the page value of a response. HTML and Transit render
  the page; the others render the content."
  {"text/html"
   (fn [_ page]
     ;; The page is both rendered and embedded, so the result is read once.
     (let [result (get-in page [:data :sparql-result])]
       (html-page (cond-> page
                    (or (instance? ResultSet result) (instance? Model result))
                    (update-in [:data :sparql-result] sparql-result->rows)))))

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
     (some-> (cond
               entity
               (json-ld-ify
                 (with-comment entity "The @graph contains labels for the properties and values of the core RDF resource defined @id.")
                 (for [[subject m] entities]
                   (assoc m :rdf/about subject)))

               search-results
               (json-ld-ify
                 {:rdfs/comment (str "The @graph represents an ordered DanNet synset search result for the lemma \"" lemma "\".")}
                 ;; search/look-up keeps the synset under dc:subject too
                 (for [[subject m] search-results]
                   (assoc (dissoc m :dc/subject) :rdf/about subject))))

             (json/write-str {:indent         true
                              :escape-unicode false})))

   "application/json"
   (fn [data & _]
     (json/write-str (->json-safe data)
                     {:indent         true
                      :escape-unicode false}))

   "application/transit+json"
   (fn [_ page]
     (->transit page))})

(defn json-body-fn
  "Combined body-fn that prefers specific types of JSON-LD over unspecified JSON
  when they are available."
  [& args]
  (or (apply (content-type->body-fn "application/ld+json") args)
      (apply (content-type->body-fn "application/json") args)))

;; TODO: select-label and select-str match tags exactly, so only the top
;;       language gets its shorter forms here. Matching in those functions,
;;       e.g. with langtag-clj's lookup, would cover every preference and add
;;       less to the frontend bundle than conneg-clj would.
(defn request->languages
  "Resolve a vector of language preferences from a `request`.

  It starts with the negotiated language when the client's preference
  matches da or en, and otherwise with the client's own top language and
  its shorter forms, e.g. de-de and de for de-DE, so that labels in that
  language come first."
  [request]
  (let [{:keys [language preferences]} (::conneg/decision request)
        preference (:language preferences)
        top        (->> (conneg/language-ranges preference)
                        (filter (comp pos? :weight))
                        (map :range)
                        (remove #{"*"})
                        (first))]
    (if (or (nil? top) (conneg/language preference i18n/supported-languages))
      (i18n/lang-prefs language)
      (into [] (distinct) (concat (->> (str/split top #"-")
                                       (reductions #(str %1 "-" %2))
                                       (reverse))
                                  (i18n/lang-prefs top))))))

(defn with-cookies
  [request data]
  (assoc data
    :full-screen (shared/get-cookie request :full-screen)
    :detail-level (or (shared/get-cookie request :detail-level)
                      :normal)))

;; TODO: eventually support LangString for titles too
(defn ->page
  "The page value for `content` and its `page-meta`, which the UI renders.

  A page also carries the user's preferences from the `request`. A response
  that isn't a page, e.g. autocompletions, has only :data."
  [request content page-meta]
  (cond-> (assoc page-meta :data content)
    (:page page-meta) (assoc :prefs (-> (with-cookies request {})
                                        (assoc :languages (:languages content))))))

(defn attachment-header
  "The Content-Disposition header that saves a `content-type` body as a file
  named after `title`; nil unless the type is JSON, JSON-LD or Turtle."
  [title content-type]
  (when-let [extension (get {"application/json"    ".json"
                             "application/ld+json" ".json"
                             "text/turtle"         ".ttl"}
                            content-type)]
    {"Content-Disposition"
     (str "attachment; filename=\""
          (str/replace title #":" "_")
          extension "\"")}))

(defn client-address
  "The address of the client of the `request`: the last X-Forwarded-For
  address, i.e. the one appended by our own reverse proxy, or else the remote
  address."
  [request]
  (ratelimit/client-address request {:proxies 1}))

(defn error-content-type
  "Determine the response content-type for an error response.

  Falls back to Transit (matching the SPA) or HTML by the Accept header when
  content negotiation has not run, with Transit for clients that accept
  neither."
  [request]
  (or (get-in request [::conneg/decision :media-type])
      (conneg/media-type (or (get-in request [:headers "accept"])
                             (get-in request [:headers :accept]))
                         ["application/transit+json" "text/html"])
      "application/transit+json"))

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
