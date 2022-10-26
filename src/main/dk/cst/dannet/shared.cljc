(ns dk.cst.dannet.shared
  "Shared functions for frontend/backend; low-dependency namespace."
  (:require #?(:clj [clojure.java.io :as io])
            #?(:clj [clojure.edn :as edn])
            #?(:cljs [cognitect.transit :as t])
            #?(:cljs [kitchen-async.promise :as p])
            #?(:cljs [lambdaisland.fetch :as fetch])
            #?(:cljs [lambdaisland.uri :as uri])
            #?(:cljs [ont-app.vocabulary.lstr :as lstr])
            #?(:cljs [applied-science.js-interop :as j])))

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

     ;; Currently lambdaisland/fetch silently loses query strings, so the
     ;; `from-query-string` is needed to keep the query string intact.
     ;; The reason that `:transit true` is assoc'd is to circumvent the browser
     ;; caching the transit data instead of an HTML page, which can result in a weird
     ;; situation where clicking the back button and then forward sometimes results
     ;; in transit data being displayed rather than an HTML page.
     (defn fetch
       "Do a GET request for the resource at `url`, returning the response body."
       [url & [{:keys [query-params] :as opts}]]
       (let [from-query-string (uri/query-string->map (:query (uri/uri url)))
             query-params'     (assoc (merge from-query-string query-params)
                                 :transit true)
             opts*             (merge {:transit-json-reader reader}
                                      (assoc opts :query-params query-params'))]
         (p/let [response (fetch/get (normalize-url url) opts*)]
                response)))

     (defn response->url
       [response]
       (-> response meta ::lambdaisland.fetch/request (j/get :url)))))

(defn setify
  [x]
  (when x
    (if (set? x) x #{x})))
