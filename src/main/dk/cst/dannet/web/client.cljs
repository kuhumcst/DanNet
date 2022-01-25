(ns dk.cst.dannet.web.client
  "The central namespace of the frontend app."
  (:require [rum.core :as rum]
            [reitit.frontend :as rf]
            [reitit.frontend.easy :as rfe :refer [href]]
            [reitit.frontend.history :as rfh]
            [lambdaisland.fetch :as fetch]
            [applied-science.js-interop :as j]
            [lambdaisland.uri :as uri]
            [cognitect.transit :as t]
            [kitchen-async.promise :as p]
            [dk.cst.dannet.web.components :as com]
            [ont-app.vocabulary.lstr :as lstr])
  (:import [goog Uri]))

(defonce development?
  (when (exists? js/inDevelopmentEnvironment)
    js/inDevelopmentEnvironment))

(defonce location
  (atom {}))

(defonce visited-urls
  (atom #{}))

(def app
  (js/document.getElementById "app"))

(defn normalize-url
  [path]
  (if development?
    (str "http://localhost:8080" path)
    path))

(def reader
  (t/reader :json {:handlers {"lstr" lstr/read-LangStr}}))

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
        all-query-params  (assoc (merge from-query-string query-params)
                            :transit true)
        opts*             (merge {:transit-json-reader reader}
                                 (assoc opts :query-params all-query-params))]
    (p/let [response (fetch/get (normalize-url url) opts*)]
      response)))

(def routes
  [["{*path}" :delegate]])

(defn- response->url
  [response]
  (-> response meta ::lambdaisland.fetch/request (j/get :url)))

(defn- update-scroll-state!
  "Scroll to the top of the page if `url` has not been visited before.

  This fakes classic page load behaviour for new pages. Hard refreshes are
  ignored letting the browser stay in place using the cached scroll state.
  Clicking a hyperlink will *always* scroll to the top, however, since they are
  intercepted by the custom 'ignore-anchor-click?' function defined below."
  [url]
  (let [already-visited @visited-urls
        refresh-page?   (-> already-visited meta :refresh-page?)]
    (if refresh-page?                                       ; hyperlink clicks
      (js/window.scrollTo #js {:top 0})
      (when (not (already-visited url))
        (when (not-empty already-visited)                   ; hard refreshes
          (js/window.scrollTo #js {:top 0}))
        (swap! visited-urls conj url)))
    (swap! visited-urls vary-meta dissoc :refresh-page?)))

(defn- ignore-anchor-click?
  "Adds a side-effect to any intercepted anchor clicks in reitit making sure
  that the scroll state always resets when intentionally clicking a link.

  Works in conjunction with 'update-scroll-state!' defined above."
  [router e el uri]
  (when (rfh/ignore-anchor-click? router e el uri)
    (swap! visited-urls vary-meta assoc :refresh-page? true)
    true))

(defn on-navigate
  [{:keys [path query-params] :as m}]
  (p/then (fetch path {:query-params query-params})
          #(let [data           (:body %)
                 page-component (com/data->page data)
                 page-title     (com/data->title data)]
             (set! js/document.title page-title)
             (reset! location {:path path
                               :data data})
             (update-scroll-state! (response->url %))
             (rum/mount (page-component data) app))))

(defn set-up-navigation!
  []
  (rfe/start! (rf/router routes)
              on-navigate
              {:use-fragment         false
               :ignore-anchor-click? ignore-anchor-click?}))

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
            #(let [data (:body %)
                   page (com/data->page data)]
               (rum/hydrate (page data) app)
               (set-up-navigation!)))))
