(ns dk.cst.dannet.web.rate-limit
  "Simple rate limiting solution relying on composite key generation."
  (:require [clojure.string :as str]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.interceptor.chain :as chain]
            [taoensso.telemere :as t])
  (:import [java.time Instant]))

;; Registry of composite keys to expiration and hit count.
(defonce registry
  (atom {}))

(defn now-ms
  "Get current time in milliseconds."
  []
  (.toEpochMilli (Instant/now)))

(defn get-hit-count!
  "Get current hit count for `key`, cleaning expired entries too."
  [key]
  (let [{:keys [hits expiry-ms]
         :or   {hits 0}} (get @registry key)]
    (if (and expiry-ms (< expiry-ms (now-ms)))
      (do (swap! registry dissoc key) 0)
      hits)))

(defn inc-hit-count!
  "Increment hit count for `key` by `ttl-ms` (milliseconds)."
  [key ttl-ms]
  (let [expiry-ms (+ (now-ms) ttl-ms)]
    (swap! registry update key
           (fn [{:keys [hits]
                 :or   {hits 0}}]
             {:hits      (inc hits)
              :expiry-ms expiry-ms}))))

(defn get-expiry
  "Get expiry time in milliseconds for `key`."
  [key]
  (when-let [expiry-ms (get-in @registry [key :expiry-ms])]
    (Instant/ofEpochMilli expiry-ms)))

;; TODO: use data structure instead of string?
(defn req->composite-key
  "Generate composite key from `req` using IP, User-Agent, and Accept-Language."
  [req]
  (str (:remote-addr req) "-"
       (get-in req [:headers "user-agent"]) "-"
       (get-in req [:headers "accept-language"])))

(defn rate-limit-response
  "Get 429 response based on `req`, current `quota` and `retry-after-seconds`."
  [quota retry-after-seconds req]
  (let [accept (get-in req [:headers "accept"] "")]
    (if (str/includes? accept "text/html")
      ;; HTML response for browsers
      {:status  429
       :headers {"Content-Type" "text/html; charset=utf-8"
                 "Retry-After"  (str retry-after-seconds)}
       :body    (str "<html><body>"
                     "<h1>Rate Limit Exceeded</h1>"
                     "<p>You have exceeded the limit of " quota " requests per minute.</p>"
                     "<p>Please try again later.</p>"
                     "</body></html>")}
      ;; JSON response for API clients
      {:status  429
       :headers {"Content-Type" "application/json"
                 "Retry-After"  (str retry-after-seconds)}
       :body    (str "{\"error\": \"Too Many Requests\", \"quota\": " quota "}")})))

(defn ->rate-limit-ic
  "Create rate limiting interceptor with simple `config` map.
  
  The config map should contain the following keys:
    :quota     - requests allowed per window.
    :window-ms - window duration in milliseconds.
    :req->key  - function to generate key from request."
  [{:keys [quota window-ms req->key]
    :or   {req->key req->composite-key}}]
  (interceptor/interceptor
    {:name ::rate-limit
     :enter
     (fn [ctx]
       (let [req           (:request ctx)
             key           (req->key req)
             current-count (get-hit-count! key)]

         (if (>= current-count quota)
           ;; Rate limit exceeded
           (let [retry-after-inst    (or (get-expiry key)
                                         (Instant/ofEpochMilli (+ (now-ms) window-ms)))
                 retry-after-seconds (int (/ (.toEpochMilli retry-after-inst) 1000))
                 response            (rate-limit-response quota retry-after-seconds req)]
             (t/log! {:level :warn
                      :data  {:key         key
                              :hits        current-count
                              :quota       quota
                              :remote-addr (:remote-addr req)}}
                     "Rate limit exceeded")
             (chain/terminate (assoc ctx :response response)))

           ;; Allow request and increment counter
           (do
             (inc-hit-count! key window-ms)
             (when (= 0 (mod (inc current-count) 10))
               (t/log! {:level :debug
                        :data  {:key   key
                                :hits  (inc current-count)
                                :quota quota}}
                       "Rate limit status"))
             ctx))))}))
