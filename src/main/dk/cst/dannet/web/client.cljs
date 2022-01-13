(ns dk.cst.dannet.web.client
  "The central namespace of the frontend app."
  (:require [rum.core :as rum]
            [reitit.frontend :as rf]
            [reitit.frontend.easy :as rfe :refer [href]]
            [lambdaisland.fetch :as fetch]
            [lambdaisland.uri :as uri]
            [cognitect.transit :as t]
            [kitchen-async.promise :as p]
            [dk.cst.dannet.web.components :as com]
            [ont-app.vocabulary.lstr :as lstr]))

(defonce development?
  (when (exists? js/inDevelopmentEnvironment)
    js/inDevelopmentEnvironment))

(defonce location
  (atom {}))

(def app
  (js/document.getElementById "app"))

(defn normalize-url
  [url]
  (if development?
    (str "http://localhost:8080" url)
    url))

(def reader
  (t/reader :json {:handlers {"lstr" lstr/read-LangStr}}))

(defn fetch
  "Do a GET request for the resource at `url`, returning the response body.
  Bad response codes result in a dialog asking the user to refresh the page.

  Usually, bad responses (e.g. 403) are caused by frontend-server mismatch
  which can be resolved by loading the latest version of the frontend app."
  [url & [opts]]
  ;; Currently lambdaisland/fetch silently loses query strings, so this line is
  ;; needed to keep the query string intact.
  (let [query-params (uri/query-string->map (:query (uri/uri url)))
        default-opts {:transit-json-reader reader
                      :query-params        query-params}]
    (p/let [{:keys [status body]} (fetch/get (normalize-url url)
                                             (merge default-opts opts))]
      body)))

(def routes
  [["{*path}" :delegate]])

(defn set-up-navigation!
  []
  (rfe/start!
    (rf/router routes)
    (fn [{:keys [path query-params] :as m}]
      (p/then (fetch path {:query-params query-params})
              #(let [page-component (com/data->page %)]
                 (reset! location {:path path
                                   :data %})
                 (rum/mount (page-component %) app)
                 ;; TODO: remember scroll position
                 (js/window.scrollTo #js {:top 0 :behavior "smooth"}))))
    {:use-fragment false}))

(defn ^:dev/after-load render
  []
  (let [data (:data @location)
        page (com/data->page data)]
    (set-up-navigation!)                                    ; keep up-to-date
    (rum/mount (page data) app)))

(defn init!
  "The entry point of the frontend app."
  []
  (let [entry-url (str js/window.location.pathname js/window.location.search)]
    (p/then (fetch entry-url)
            #(let [page (com/data->page %)]
               (rum/hydrate (page %) app)
               (set-up-navigation!)))))
