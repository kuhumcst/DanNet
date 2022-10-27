(ns dk.cst.dannet.shared
  "Shared functions for frontend/backend; low-dependency namespace."
  (:require #?(:clj [clojure.java.io :as io])
            #?(:clj [clojure.edn :as edn])
            #?(:cljs [cognitect.transit :as t])
            #?(:cljs [lambdaisland.fetch :as fetch])
            #?(:cljs [lambdaisland.uri :as uri])
            #?(:cljs [ont-app.vocabulary.lstr :as lstr])
            #?(:cljs [applied-science.js-interop :as j])))

;; Page state used in the single-page app; completely unused server-side.
(defonce state
  (atom {:languages nil
         :search    {:completion {}
                     :s          ""}
         :details?  nil}))

#?(:clj
   (def main-js
     "When making a release, the filename will be appended with a hash;
     that is not the case when running the regular shadow-cljs watch process.

     Relies on the :module-hash-names being set to true in shadow-cljs.edn."
     (if-let [url (io/resource "public/js/compiled/manifest.edn")]
       (-> url slurp edn/read-string first :output-name)
       "main.js")))

(def development?
  "Source of truth for whether this is a development build or not. "
  #?(:clj  (= main-js "main.js")
     :cljs (when (exists? js/inDevelopmentEnvironment)
             js/inDevelopmentEnvironment)))

(defn normalize-url
  [path]
  (if development?
    (str "http://localhost:3456" path)
    path))

#?(:cljs
   (do
     ;; TODO: handle datetime more satisfyingly typewise and in the web UI
     (def reader
       (t/reader :json {:handlers {"lstr"     lstr/read-LangStr
                                   "datetime" identity}}))

     (defn clear-fetch
       "Clear a `url` from the ongoing fetch table (done after fetches)."
       [url]
       (swap! state update :fetch dissoc url))

     (defn abort-fetch
       "Abort an ongoing fetch for `url`."
       [url]
       (when-let [controller (get-in @state [:fetch url])]
         (.abort controller)
         (clear-fetch url)))

     ;; Currently lambdaisland/fetch silently loses query strings, so the
     ;; `from-query-string` is needed to keep the query string intact.
     ;; The reason that `:transit true` is assoc'd is to circumvent the browser
     ;; caching the transit data instead of an HTML page, which can result in a weird
     ;; situation where clicking the back button and then forward sometimes results
     ;; in transit data being displayed rather than an HTML page.
     (defn fetch
       "Do a GET request for the resource at `url`, returning the response body."
       [url & [{:keys [query-params] :as opts}]]
       (abort-fetch url)                                    ; cancel existing
       (let [string-params (uri/query-string->map (:query (uri/uri url)))
             query-params' (assoc (merge string-params query-params)
                             :transit true)
             controller    (new js/AbortController)
             signal        (.-signal controller)
             opts*         (merge {:transit-json-reader reader
                                   :signal              signal}
                                  (assoc opts
                                    :query-params query-params'))]
         (swap! state assoc-in [:fetch url] controller)
         (fetch/get (normalize-url url) opts*)))

     (defn response->url
       [response]
       (-> response meta :lambdaisland.fetch/request (j/get :url)))))

(defn setify
  [x]
  (when x
    (if (set? x) x #{x})))
