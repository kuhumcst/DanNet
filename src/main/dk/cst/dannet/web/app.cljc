(ns dk.cst.dannet.web.app
  "The client side of the single-page app: the user's session, navigation, and
  the transit-based fetch layer."
  (:require [clojure.string :as str]
            [taoensso.telemere :as t]
            [dk.cst.dannet.shared :as shared]
            #?(:cljs [reitit.frontend.easy :as rfe])
            #?(:cljs [reitit.frontend.history :as rfh])
            #?(:cljs [cognitect.transit :as transit])
            #?(:cljs [flatland.ordered.map :as fop])
            #?(:cljs [lambdaisland.fetch :as fetch])
            #?(:cljs [lambdaisland.uri :as uri])
            #?(:cljs [dk.cst.theophrastus.term :as term])))

(def default-state
  "The user's state before any page is shown. The preferences (languages,
  full-screen, detail level) come from the page: the client takes them from
  the first page, the server from each request."
  {:languages    nil
   :search       {:completion {}
                  :s          ""}
   :full-screen  false
   :detail-level :normal
   :section      {:semantic {:display {:selected     "diagram"
                                       :diagram-mode :radial}}}})

;; The user's current browsing session.
(defonce session
  (atom default-state))

(def diagram-mode-path
  ;; The synset section shows as a table or a diagram (`:display :selected`);
  ;; when it's a diagram, `:diagram-mode` picks which one (:radial, :sunburst,
  ;; :sunburst-orthogonal, or :sunburst-meronym). Co-located under :display so
  ;; the relationship is clear in `session`, reachable via `opts` (the session
  ;; is merged in) and reset to :radial on a full page load.
  [:section :semantic :display :diagram-mode])

(def windows?
  #?(:cljs (and (exists? js/navigator.appVersion)
                (str/includes? js/navigator.appVersion "Windows"))))

(defn normalize-url
  "Normalize a `path` to work in both production and development contexts.

  When accessing using Windows in dev, the OS is assumed to be virtualised and
  localhost:3456 of the macOS host to be available at mac:3456 instead."
  [path]
  (if shared/development?
    (if windows?
      (str "http://mac:3456" path)
      (str "http://localhost:3456" path))
    path))

(defn- scroll-state
  "A history state that records the scroll position of the page content."
  []
  #?(:cljs (when-let [content (js/document.getElementById "content")]
             #js {:scroll (.-scrollTop content)})))

(defn save-scroll!
  "Store the scroll position of the page content in the current history entry,
  so that going back or forward to the entry can restore it."
  []
  #?(:cljs (.replaceState js/window.history (scroll-state) "")))

(defn navigate-to
  "Navigate to internal `url` using reitit.

  With `replace?`, the new page replaces the current one in the history. With
  `keep-scroll?`, it keeps the current scroll position instead of starting at
  the top."
  [url & [{:keys [replace? keep-scroll?]}]]
  #?(:cljs (if (not-empty url)
             ;; Not rfe/push-state, which always sets a nil history state: with
             ;; keep-scroll?, the new entry carries the current scroll position.
             (let [history @rfe/history
                   href    (rfh/-href history url)
                   state   (when keep-scroll? (scroll-state))]
               (save-scroll!)
               (if replace?
                 (.replaceState js/window.history state "" href)
                 (.pushState js/window.history state "" href))
               (rfh/-on-navigate history url))
             (t/log! {:level :warn
                      :data  {:url      url
                              :from     js/window.location.href
                              :referrer js/document.referrer
                              :stack    (.-stack (js/Error.))}}
                     "navigate-to called with empty URL"))))

#?(:cljs
   (do
     (defn- read-lstr
       "The LangString of the Transit `rep` s@lang, split at its last @."
       [rep]
       (let [i (str/last-index-of rep "@")]
         (term/->LangString (subs rep 0 i) (subs rep (inc i)))))

     (def transit-read-handlers
       {"lstr"         read-lstr
        "omap"         #(into (fop/ordered-map) %)
        "rdfdatatype"  identity
        "f"            parse-double                         ; BigDecimal
        "sparqlresult" identity})

     (def transit-reader
       (transit/reader :json {:handlers transit-read-handlers}))

     ;; The in-flight fetches by URL, each with its AbortController. While there
     ;; are any, <html> has a data-fetching attribute, which the CSS shows as a
     ;; loading indicator.
     (defonce fetches
       (atom {}))

     (add-watch fetches ::loading-indicator
                (fn [_ _ _ m]
                  (if (empty? m)
                    (.removeAttribute js/document.documentElement "data-fetching")
                    (.setAttribute js/document.documentElement "data-fetching" ""))))

     (defn clear-current-fetch
       "Clear `url` from the ongoing fetch table (done after fetches complete)."
       [url]
       (swap! fetches dissoc url))

     (defn abort-current-fetch
       "Abort an ongoing fetch for `url`."
       [url]
       (when-let [controller (get @fetches url)]
         (.abort controller)
         (clear-current-fetch url)))

     (defn abort-stale-fetches
       "Abort all in-flight fetches. Called on navigation to prevent stale data
       from previous pages being processed after the user has moved on."
       []
       (doseq [[url controller] @fetches]
         (.abort controller))
       (reset! fetches {}))

     ;; The `:transit` query parameter is there to circumvent the browser
     ;; caching the transit data instead of an HTML page, which can result in a
     ;; weird situation where clicking the back button and then forward
     ;; sometimes results in transit data being displayed rather than an HTML
     ;; page. Responses are cached for a day, so its value is bumped whenever
     ;; the Transit format changes; cached responses in the old format are then
     ;; never used. The app also sends the name of its JS file as :js; when
     ;; either differs from the server's, the server has the app load the page
     ;; again (see resources/outdated-app?).
     (def transit-param
       (let [js js/document.documentElement.dataset.js]
         (cond-> {:transit shared/transit-version}
           js (assoc :js js))))

     ;; Currently lambdaisland/fetch silently loses query strings, so they are
     ;; parsed back out of the URL below to keep them intact.
     (defn fetch!
       "Request the resource at `url` with the lambdaisland.fetch `opts`,
       returning a promise of the response map.

       The promise resolves to nil when a newer fetch of `url` or a navigation
       aborts it. Callers clear the fetch with `clear-current-fetch` once done."
       [url & [{:keys [query-params method] :or {method :get} :as opts}]]

       (abort-current-fetch url)

       (let [string-params (uri/query-string->map (:query (uri/uri url)))
             query-params' (merge string-params query-params transit-param)
             controller    (new js/AbortController)
             signal        (.-signal controller)
             opts*         (merge {:method              method
                                   :transit-json-reader transit-reader
                                   :signal              signal}
                                  (assoc opts
                                    :query-params query-params'))]
         (swap! fetches assoc url controller)
         (-> (fetch/request (normalize-url url) opts*)
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
                #(clear-current-fetch url))
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
