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

(defn log-inc
  "Increment `n` by log(n)."
  [n]
  (+ n (max 1 (math/log n))))

(defn cloud-normalize
  "Normalize a map of `weights` to fit a word cloud. The output is meant to
  display well across a wide range of differently sized word clouds.

  The actual weights are *ONLY* used for sorting! New, artificial weights are
  created by incrementing from 1 using a relative logarithmic increment and then
  fitting these values into the range 0...1.

  Furthermore, an artificial highlight is used for the values which lie above
  a certain threshold. This highlight is applied as bonus constant applied to
  the weights above the threshold. This simulates the effect of outliers."
  [weights]
  (let [artificial-weights (->> (sort-by second weights)
                                (map (fn [n [k _]]
                                       [k n])
                                     (iterate log-inc 1)))
        low                (second (first artificial-weights))
        high               (second (last artificial-weights))
        span               (- high low)

        ;; Highlight threshold & bonus are only used for bigger clouds.
        [threshold bonus] (if (> (count weights) 30)
                            [(- high (math/sqrt span))
                             (/ span 2)]
                            [high 0])
        min-max-normalize' #(min-max-normalize (+ span bonus) low %)]
    (into {} (for [[k v] artificial-weights]
               [k (min-max-normalize' (if (> v threshold)
                                        (+ v bonus)
                                        v))]))))

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
  ;; Testing out relative weights
  (take 10 (map double (iterate log-inc 1)))
  (take 100 (map double (iterate log-inc 1)))
  (take 1000 (map double (iterate log-inc 1)))

  (sort (vals (normalize {:10 0 :8 0 :6 0 :4 0 :2 0 :0 0})))
  #_.)
