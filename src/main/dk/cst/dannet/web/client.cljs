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

(defonce visited
  (atom {:back    '()
         :forward '()}))

(def app
  (js/document.getElementById "app"))

(defn normalize-url
  [path]
  (if development?
    (str "http://localhost:8080" path)
    path))

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

;; TODO: also do this for back/forward button
(defn- update-scroll-opts
  [history opts]
  (conj
    (rest history)
    [(ffirst history) opts]))

(defn- update-scroll-state!
  "Scroll to the top of the page if `url` is not from back/forward button."
  [url]
  (let [{:keys [back forward] :as urls} @visited
        [back-url back-opts] (first (rest back))
        [forward-url forward-opts] (first forward)
        anchor-click?   (-> urls meta :anchor-click?)
        back-button?    (= url back-url)
        forward-button? (= url forward-url)
        content-element (js/document.getElementById "content")]
    (swap! visited vary-meta dissoc :anchor-click?)
    (if (or anchor-click? (not (or back-button? forward-button?)))
      (let [opts (clj->js {:top (.-scrollTop content-element)})]
        (.scroll content-element #js {:top 0})
        (swap! visited assoc
               :back (if (empty? back)
                       (list [url #js {:top 0}])
                       (conj (update-scroll-opts back opts)
                             [url #js {:top 0}]))
               :forward '()))
      (cond
        back-button?
        (do
          (.scroll content-element back-opts)
          (swap! visited assoc
                 :back (rest back)
                 :forward (cons (first back) forward)))

        forward-button?
        (do
          (.scroll content-element forward-opts)
          (swap! visited assoc
                 :back (conj back (first forward))
                 :forward (rest forward)))))))

(defn- ignore-anchor-click?
  "Adds a side-effect to any intercepted anchor clicks in reitit making sure
  that the scroll state always resets when intentionally clicking a link.

  Works in conjunction with 'update-scroll-state!' defined above."
  [router e el uri]
  (when (rfh/ignore-anchor-click? router e el uri)
    (swap! visited vary-meta assoc :anchor-click? true)
    true))

(defn on-navigate
  [{:keys [path query-params] :as m}]
  (p/then (fetch path {:query-params query-params})
          #(let [data           (:body %)
                 page-component (com/page-shell (com/data->page data) data)
                 page-title     (com/data->title data)]
             (set! js/document.title page-title)
             (reset! location {:path path
                               :data data})
             (update-scroll-state! (response->url %))
             (rum/mount page-component app))))

(defn set-up-navigation!
  []
  (rfe/start! (rf/router routes)
              on-navigate
              {:use-fragment         false
               :ignore-anchor-click? ignore-anchor-click?}))

(defn ^:dev/after-load render
  []
  (let [data           (:data @location)
        page-component (com/page-shell (com/data->page data) data)]
    (set-up-navigation!)                                    ; keep up-to-date
    (rum/mount page-component app)))

(defn init!
  "The entry point of the frontend app."
  []
  (let [entry-url (str js/window.location.pathname js/window.location.search)]
    (p/then (fetch entry-url)
            #(let [data           (:body %)
                   page-component (com/page-shell (com/data->page data) data)]
               (rum/hydrate page-component app)
               (set-up-navigation!)))))