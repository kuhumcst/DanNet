(ns dk.cst.dannet.web.app
  "The client side of the single-page app: the user's session, the cookie-backed
  defaults feeding it, navigation, and the transit-based fetch layer."
  (:require [clojure.edn :as edn]
            [taoensso.telemere :as t]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.web.section :as section]
            #?(:cljs [reitit.frontend.easy :as rfe])
            #?(:cljs [reitit.frontend.history :as rfh])
            #?(:cljs [cognitect.transit :as transit])
            #?(:cljs [lambdaisland.fetch :as fetch])
            #?(:cljs [lambdaisland.uri :as uri])
            #?(:cljs [ont-app.vocabulary.lstr :as lstr])))

(def default-languages
  #?(:clj  nil
     :cljs (or
             (shared/get-cookie :languages)
             (if (exists? js/negotiatedLanguages)
               (edn/read-string js/negotiatedLanguages)
               ["en" nil "da"]))))

(def default-full-screen
  #?(:clj  false
     :cljs (boolean (shared/get-cookie :full-screen))))

(def default-detail-level
  #?(:clj  :normal
     :cljs (or (shared/get-cookie :detail-level)
               :normal)))

;; The user's current browsing session; also read server-side to seed hydration.
(defonce session
  (atom {:languages    default-languages
         :search       {:completion {}
                        :s          ""}
         :full-screen  default-full-screen
         :detail-level default-detail-level
         :section      {section/semantic-title
                        {:display {:selected     "diagram"
                                   :diagram-mode :radial}}}}))

;; Temporary store for special behaviour after navigating to a new page.
(defonce post-navigate
  (atom nil))

(def diagram-mode-path
  ;; The synset section shows as a table or a diagram (`:display :selected`);
  ;; when it's a diagram, `:diagram-mode` picks which one (:radial, :sunburst,
  ;; :sunburst-orthogonal, or :sunburst-meronym). Co-located under :display so
  ;; the relationship is clear in `session`, reachable via `opts` (the session
  ;; is merged in) and reset to :radial on a full page load.
  [:section section/semantic-title :display :diagram-mode])

(defn navigate-to
  "Navigate to internal `url` using reitit.

  Optionally, specify whether to `replace` the state in history."
  [url & [replace]]
  #?(:cljs (if (not-empty url)
             (let [history @rfe/history]
               (if replace
                 (.replaceState js/window.history nil "" (rfh/-href history url))
                 (.pushState js/window.history nil "" (rfh/-href history url)))
               (rfh/-on-navigate history url))
             (t/log! {:level :warn
                      :data  {:url      url
                              :from     js/window.location.href
                              :referrer js/document.referrer
                              :stack    (.-stack (js/Error.))}}
                     "navigate-to called with empty URL"))))

#?(:cljs
   (do
     (def transit-read-handlers
       {"lstr"         lstr/read-LangStr
        "rdfdatatype"  identity
        "f"            parse-double                         ; BigDecimal
        "sparqlresult" identity
        "datetime"     identity})

     ;; TODO: handle datetime more satisfyingly typewise and in the web UI
     (def transit-reader
       (transit/reader :json {:handlers transit-read-handlers}))

     (defn clear-current-fetch
       "Clear `url` from the ongoing fetch table (done after fetches complete)."
       [url]
       (swap! session update :fetch dissoc url))

     (defn abort-current-fetch
       "Abort an ongoing fetch for `url`."
       [url]
       (when-let [controller (get-in @session [:fetch url])]
         (.abort controller)
         (clear-current-fetch url)))

     (defn abort-stale-fetches
       "Abort all in-flight fetches. Called on navigation to prevent stale data
       from previous pages being processed after the user has moved on."
       []
       (doseq [[url controller] (:fetch @session)]
         (.abort controller))
       (swap! session assoc :fetch {}))

     ;; Currently lambdaisland/fetch silently loses query strings, so they are
     ;; parsed back out of the URL below to keep them intact.
     ;; The reason that `:transit true` is assoc'd is to circumvent the browser
     ;; caching the transit data instead of an HTML page, which can result in a weird
     ;; situation where clicking the back button and then forward sometimes results
     ;; in transit data being displayed rather than an HTML page.
     (defn fetch!
       "Request the resource at `url`, returning the response body."
       [url & [{:keys [query-params method] :or {method :get} :as opts}]]

       ;; Cancel any existing fetches (ignoring a nil session, i.e. the first run).
       (when-not (nil? (:fetch @session))
         (abort-current-fetch url))

       (let [string-params (uri/query-string->map (:query (uri/uri url)))
             query-params' (assoc (merge string-params query-params)
                             :transit true)
             controller    (new js/AbortController)
             signal        (.-signal controller)
             opts*         (merge {:method              method
                                   :transit-json-reader transit-reader
                                   :signal              signal}
                                  (assoc opts
                                    :query-params query-params'))]
         (swap! session assoc-in [:fetch url] controller)
         (-> (fetch/request (shared/normalize-url url) opts*)
             ;; Aborted fetches throw an AbortError. This is expected behaviour
             ;; when looking up search suggestions, so we don't need to litter
             ;; our console with these false positives.
             (.catch #(when-not (= "AbortError" (.-name %))
                        (throw %))))))

     (defn update-cookie!
       "Apply `f` to cookie at key `k`, storing the result in the session."
       [k f]
       (let [url "/cookies"
             v   (get (swap! session update k f) k)]
         (.then (fetch! url {:method :put
                             :body   {k v}})
                (clear-current-fetch url))
         v))))

(defn toggle-full-screen!
  "Toggle the `:full-screen` cookie and scroll the content pane to the top.

  Shared by both diagram modes' full-screen controls; scrolling to the top
  makes entering/leaving full-screen read like a page change."
  []
  #?(:cljs (do
             (update-cookie! :full-screen not)
             (some-> (js/document.getElementById "content")
                     (.scroll #js {:top 0})))))
