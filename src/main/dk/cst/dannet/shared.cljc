(ns dk.cst.dannet.shared
  "Shared functions for frontend/backend; low-dependency namespace."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.math :as math]
            [reitit.impl :refer [form-decode]]
            #?(:cljs [reitit.frontend.easy :as rfe])
            #?(:cljs [reitit.frontend.history :as rfh])
            #?(:clj [clojure.java.io :as io])
            #?(:cljs [clojure.string :as str])
            #?(:cljs [cognitect.transit :as t])
            #?(:cljs [reagent.cookies :as cookie])
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

(defn page-href
  [s]
  (str "/dannet/page/" s))

;; NOTE: cookies should be set using the /cookies endpoint! This is the only way
;; to get long-term cookie storage in e.g. Safari using JavaScript.
(defn get-cookie
  "Cross-compatible way to get cookie `k` (from the `request` on backend)."
  #?(:clj
     ([request k]
      (try
        (some-> request
                :cookies
                (get (name k))
                :value
                (edn/read-string))
        (catch Exception e nil)))
     :cljs
     ([k]
      ;; Reitit properly decodes the form values from Ring Cookie
      ;; (the native JS functions leave a few undesired chars around).
      (some-> (cookie/get-raw k)
              (form-decode)
              (edn/read-string)))))

(def default-languages
  #?(:clj  nil
     :cljs (or
             (get-cookie :languages)
             (if (exists? js/negotiatedLanguages)
               (edn/read-string js/negotiatedLanguages)
               ["en" nil "da"]))))

;; Page state used in the single-page app; completely unused server-side.
(defonce state
  (atom {:languages default-languages
         :search    {:completion {}
                     :s          ""}
         :details?  nil}))

(def windows?
  #?(:cljs (and (exists? js/navigator.appVersion)
                (str/includes? js/navigator.appVersion "Windows"))))

(defn normalize-url
  "Normalize a `path` to work in both production and development contexts.

  When accessing using Windows in dev, the OS is assumed to be virtualised and
  localhost:3456 of the macOS host to be available at mac:3456 instead."
  [path]
  (if development?
    (if windows?
      (str "http://mac:3456" path)
      (str "http://localhost:3456" path))
    path))

(defn search-string
  "Normalize search string `s`."
  [s]
  (some-> s str str/trim str/lower-case))

#?(:cljs
   (do
     (def transit-read-handlers
       {"lstr"        lstr/read-LangStr
        "rdfdatatype" identity
        "f"           parse-double                          ; BigDecimal
        "datetime"    identity})

     ;; TODO: handle datetime more satisfyingly typewise and in the web UI
     (def reader
       (t/reader :json {:handlers transit-read-handlers}))

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
     (defn api
       "Do a GET request for the resource at `url`, returning the response body."
       [url & [{:keys [query-params method] :or {method :get} :as opts}]]
       (abort-fetch url)                                    ; cancel existing
       (let [string-params (uri/query-string->map (:query (uri/uri url)))
             query-params' (assoc (merge string-params query-params)
                             :transit true)
             controller    (new js/AbortController)
             signal        (.-signal controller)
             opts*         (merge {:method              method
                                   :transit-json-reader reader
                                   :signal              signal}
                                  (assoc opts
                                    :query-params query-params'))]
         (swap! state assoc-in [:fetch url] controller)
         (fetch/request (normalize-url url) opts*)))

     (defn response->url
       [response]
       (-> response meta :lambdaisland.fetch/request (j/get :url)))))

(defn setify
  [x]
  (when x
    (if (set? x) x #{x})))

(defn sense-labels
  "Split a `synset` label into sense labels. Work for both old and new formats."
  [sep label]
  (->> (str/split label sep)
       (into [] (comp
                  (remove empty?)
                  (map str/trim)))))

(def sense-label
  "On matches returns the vector: [s word rest-of-s sub mwe]."
  #"([^_<>]+)(_((?:§|\d|\()[^_ ]+)( .+)?)?")

(def synset-sep
  #"\{|;|\}")

(defn min-max-normalize
  [span low num]
  (/ (- num low) span))

(defn mean
  [nums]
  (let [sum   (apply + nums)
        count (count nums)]
    (if (pos? count)
      (/ sum count)
      0)))

(defn std-deviation
  [nums]
  (let [avg     (mean nums)
        squares (for [num nums]
                  (let [num-avg (- num avg)]
                    (* num-avg num-avg)))
        total   (count nums)]
    (math/sqrt (/ (apply + squares)
                  (- total 1)))))

;; TL;DR combining z-score and min-max is pointless:
;; https://stats.stackexchange.com/questions/318170/min-max-scaling-on-z-score-standardized-data
(defn z-score
  [avg std-dev num]
  (/ (- num avg) std-dev))

(defn normalize
  "Normalize a map of keys to `weights`. The effect of outliers is reduced using
  the logarithm and the new weights are made to fit the range 0...1."
  [weights]
  (let [no-infinite        #(if (infinite? %) 0 %)
        weights'           (update-vals weights (comp no-infinite math/log))
        adjusted-vals      (sort (vals weights'))
        low                (first adjusted-vals)
        high               (last adjusted-vals)
        span               (- high low)
        min-max-normalize' (partial min-max-normalize span low)]
    (update-vals weights' min-max-normalize')))

(defn x-header
  "Get the custom `header` in the HTTP `headers`.

  See also: dk.cst.dannet.web.resources/x-headers"
  [headers header]
  ;; Interestingly (hahaha) fetch seems to lower-case all keys in the headers.
  (get headers (str "x-" (str/lower-case (name header)))))

(defn navigate-to
  "Navigate to internal `url` using reitit."
  [url]
  #?(:cljs (let [history @rfe/history]
             (.pushState js/window.history nil "" (rfh/-href history url))
             (rfh/-on-navigate history url))))

(comment
  (sort (vals (normalize {:10 10 :8 8 :6 6 :4 4 :2 2 :0 0})))
  #_.)
