(ns dk.cst.dannet.web.resources
  "Pedestal interceptors for entity look-ups and schema downloads."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint print-table]]
            [io.pedestal.http.route :refer [decode-query-part]]
            [io.pedestal.http.content-negotiation :as conneg]
            [ring.util.response :as ring]
            [com.wsscode.transito :as transito]
            [lambdaisland.hiccup :as hiccup]
            [flatland.ordered.map :as fop]
            [com.owoga.trie :as trie]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.db :as db]
            [dk.cst.dannet.query :as q]
            [dk.cst.dannet.query.operation :as op]
            [dk.cst.dannet.bootstrap :as bootstrap]
            [dk.cst.dannet.web.components :as com]))

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
     (hiccup/render
       [com/entity-page languages entity]))})

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
                           [(com/html-table languages result nil k->label)]))
                       results)]
    (hiccup/render
      [com/search-page lemma search-path tables])))

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