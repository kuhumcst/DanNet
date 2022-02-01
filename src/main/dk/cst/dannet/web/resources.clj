(ns dk.cst.dannet.web.resources
  "Pedestal interceptors for entity look-ups and schema downloads."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.pprint :refer [pprint print-table]]
            [cognitect.transit :as t]
            [com.wsscode.transito :as to]
            [io.pedestal.http.route :refer [decode-query-part]]
            [io.pedestal.http.content-negotiation :as conneg]
            [ring.util.response :as ring]
            [ont-app.vocabulary.lstr]
            [rum.core :as rum]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.db :as db]
            [dk.cst.dannet.query :as q]
            [dk.cst.dannet.bootstrap :as bootstrap]
            [dk.cst.dannet.web.components :as com])
  (:import [ont_app.vocabulary.lstr LangStr]
           [org.apache.jena.datatypes.xsd XSDDateTime]))

;; TODO: support "systematic polysemy" for  ontological type, linking to blank resources instead
;; TODO: should :wn/instrument be :dns/usedFor instead? Bolette objects to instrument
;; TODO: co-agent instrument confusion http://0.0.0.0:8080/dannet/2022/instances/synset-4249
;; TODO: involved instrument confusion http://0.0.0.0:8080/dannet/2022/instances/synset-65998
;; TODO: add missing labels, e.g. http://0.0.0.0:8080/dannet/2022/instances/synset-49069
;; TODO: "download as" on entity page + don't use expanded entity for non-HTML

(defonce db
  (future
    (db/->dannet
      :imports bootstrap/imports
      :schema-uris db/schema-uris)))

(def main-js
  "When making a release, the filename will be appended with a hash;
  that is not the case when running the regular shadow-cljs watch process.

  Relies on the :module-hash-names being set to true in shadow-cljs.edn."
  (if-let [url (io/resource "public/js/compiled/manifest.edn")]
    (-> url slurp edn/read-string first :output-name)
    "main.js"))

(def development?
  "Source of truth for whether this is a development build or not. "
  (= main-js "main.js"))

(def one-month-cache
  "private, max-age=2592000")

(def one-day-cache
  "private, max-age=86400")

(def supported-languages
  ["da" "en"])

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
    [(prefix/remove-trailing-slash path) :get handler :route-name route-name]))

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
      [:footer {:lang "en"}
       [:hr]
       [:p
        "© 2022 " [:a {:href "https://cst.ku.dk/english/"}
                   "Centre for Language Technology"]
        ", " [:abbr {:title "University of Copenhagen"}
              "KU"] "."]
       [:p "The source code for DanNet is available at our "
        [:a {:href "https://github.component/kuhumcst/DanNet"}
         "Github repository"] "."]]
      [:script (str "var inDevelopmentEnvironment = " development? ";")]
      [:script {:src (str "/js/compiled/" main-js)}]]]))

(defn- lstr->s
  [lstr]
  (str (.s lstr) "@" (.lang lstr)))

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
                   {:handlers {LangStr     (t/write-handler "lstr" lstr->s)
                               XSDDateTime (t/write-handler "datetime" str)}}))

   "text/html"
   (fn [& {:keys [data page title]}]
     (html-page
       title
       ((get com/pages page) data)))})

(def use-lang?
  #{"application/transit+json" "text/html"})

(defn ->entity-ic
  "Create an interceptor to return DanNet resources, optionally specifying a
  predetermined `prefix` to use for graph look-ups; otherwise locates the prefix
  within the path-params."
  [& {:keys [prefix subject]}]
  {:name  ::entity
   :leave (fn [{:keys [request] :as ctx}]
            (let [content-type (get-in request [:accept :field] "text/plain")
                  lang         (get-in request [:accept-language :field])
                  prefix*      (or (get-in request [:path-params :prefix])
                                   prefix)
                  ;; TODO: why is decoding necessary?
                  ;; You would think that the path-params-decoder handled this.
                  subject      (or subject
                                   (-> request
                                       (get-in [:path-params :subject])
                                       (decode-query-part)
                                       (->> (keyword (name prefix*)))))
                  entity       (if (use-lang? content-type)
                                 (q/expanded-entity (:graph @db) subject)
                                 (q/entity (:graph @db) subject))
                  languages    (if lang [lang "en"] ["en"])
                  data         {:languages languages
                                :k->label  (-> entity meta :k->label)
                                :subject   (-> entity meta :subject)
                                :entity    entity}]
              (if entity
                (-> ctx
                    (update :response assoc
                            :status 200
                            :body ((content-type->body-fn content-type)
                                   :data data
                                   :title (:subject data)
                                   :page :entity))
                    (update-in [:response :headers] assoc
                               "Content-Type" content-type
                               ;; TODO: use cache in production
                               #_#_"Cache-Control" one-day-cache))
                (update ctx :response assoc
                        :status 404
                        :headers {}))))})

(def search-path
  (str (uri->path prefix/dannet-root) "search"))

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
                            :search-path    search-path
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
         (->entity-ic :prefix prefix)]
   :route-name (keyword (str *ns*) (str prefix "-entity"))])

(def external-entity-route
  "Look-up route for external resources. Doesn't conform to the actual URIs."
  [(str (uri->path prefix/dannet-root) "external/:prefix/:subject")
   :get [content-negotiation-ic
         language-negotiation-ic
         (->entity-ic)]
   :route-name ::external-entity])

(defn prefix->dataset-entity-route
  [prefix]
  (let [uri (-> prefix prefix/prefix->uri prefix/remove-trailing-slash)]
    [(prefix/uri->path uri)
     :get [content-negotiation-ic
           language-negotiation-ic
           (->entity-ic :subject (prefix/rdf-resource uri))]
     :route-name (keyword (str *ns*) (str prefix "-dataset-entity"))]))

(def search-route
  [search-path
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

#_(def autocomplete-path
    (str (uri->path prefix/dannet-root) "autocomplete"))

;; TODO: should be transformed into a tightly packed tried (currently loose)
#_(defonce search-trie
    (future
      (let [words (q/run (:graph @db) '[?writtenRep] op/written-representations)]
        (apply trie/make-trie (mapcat concat words words)))))

#_(defn autocomplete
    "Return autocompletions for `s` found in the graph."
    [s]
    (->> (trie/lookup @search-trie s)
         (remove (comp nil? second))                        ; remove partial
         (map second)                                       ; grab full words
         (sort)))

#_(def autocomplete* (memoize autocomplete))

#_(def autocomplete-ic
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

#_(def autocomplete-route
    [autocomplete-path
     :get [autocomplete-ic]
     :route-name ::autocomplete])

(defn shadow-handler
  "Handler used by shadow-cljs to orient itself on page load.
  Note that the backend web service must be running on http://0.0.0.0:8080!"
  [{:keys [uri query-string] :as request}]
  {:status  200
   :headers {"Content-Type" "text/html"}
   :body    (slurp (str "http://localhost:8080" uri
                        (when query-string
                          (str "?" query-string))))})

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
