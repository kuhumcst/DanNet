(ns dk.cst.dannet.web.rate-limit
  "Simple rate limiting solution relying on composite key generation."
  (:require [clojure.string :as str]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.interceptor.chain :as chain]
            [clojure.tools.logging :as log])
  (:import [java.time Instant]))

(defonce ^:private storage
  (atom {}))

(defn now-millis
  "Current time in milliseconds since epoch."
  []
  (.toEpochMilli (Instant/now)))

(defn get-count
  "Get current count for key, cleaning expired entries."
  [key]
  (let [now   (now-millis)
        entry (get @storage key)]
    (if (and entry (< (:expiry entry) now))
      (do (swap! storage dissoc key) 0)
      (:count entry 0))))

(defn increment-count!
  "Increment count for key with TTL in milliseconds."
  [key ttl-ms]
  (let [now    (now-millis)
        expiry (+ now ttl-ms)]
    (swap! storage update key
           (fn [old]
             {:count  (inc (:count old 0))
              :expiry expiry}))
    (:count (get @storage key))))

(defn get-expiry
  "Get expiry time for key."
  [key]
  (when-let [expiry (get-in @storage [key :expiry])]
    (Instant/ofEpochMilli expiry)))

;; Good for handling shared IP scenarios (coffee shops, classrooms, offices).
(defn ip-user-agent-lang-key
  "Generate composite key from IP, User-Agent, and Accept-Language."
  [req]
  (str (:remote-addr req) "-"
       (get-in req [:headers "user-agent"]) "-"
       (get-in req [:headers "accept-language"])))

(defn rate-limit-response
  "Create 429 response based on request Accept header."
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
  "Create rate limiting interceptor with simple configuration map.
  
  Config map should contain:
  - :quota - requests allowed per window  
  - :window-ms - window duration in milliseconds
  - :key-fn - function to generate key from request (defaults to ip-user-agent-lang-key)"
  [config]
  (let [{:keys [quota window-ms key-fn]
         :or   {key-fn ip-user-agent-lang-key}} config]
    (interceptor/interceptor
      {:name ::rate-limit
       :enter
       (fn [context]
         (let [req           (:request context)
               key           (key-fn req)
               current-count (get-count key)]

           (if (>= current-count quota)
             ;; Rate limit exceeded
             (let [retry-after-inst    (or (get-expiry key)
                                           (Instant/ofEpochMilli (+ (now-millis) window-ms)))
                   retry-after-seconds (int (/ (.toEpochMilli retry-after-inst) 1000))
                   response            (rate-limit-response quota retry-after-seconds req)]
               (log/warn "Rate limit exceeded"
                         {:key         key
                          :count       current-count
                          :quota       quota
                          :remote-addr (:remote-addr req)})
               (chain/terminate (assoc context :response response)))

             ;; Allow request and increment counter
             (do
               (increment-count! key window-ms)
               (when (= 0 (mod (inc current-count) 10))
                 (log/debug "Rate limit status" {:key key :count (inc current-count) :quota quota}))
               context))))})))

(defn reset-storage!
  "Clear all rate limiting state."
  []
  (reset! storage {}))

(defn get-storage-state
  "Get current storage state for debugging."
  []
  @storage)
