(ns dk.cst.dannet.web.service
  "Web service handling entity look-ups and schema downloads."
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [io.pedestal.http :as http]
            [io.pedestal.http.cors :as cors]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.ring-middlewares :as middleware]
            [dk.cst.dannet.web.resources :as res]
            [dk.cst.dannet.web.rate-limit :as rl]
            [dk.cst.dannet.shared :as shared])
  (:import [org.apache.jena.sparql.expr NodeValue])
  (:gen-class))

(defonce server (atom nil))
(defonce conf (atom {}))                                    ;TODO: use?

(defn routes
  []
  (route/expand-routes
    #{;; Frontend redirects
      res/root-route
      res/dannet-route

      ;; See also: middleware/cookies added below as a default interceptor!
      res/cookies-route

      ;; API and other single content-type routes
      res/export-route
      res/schema-download-route
      res/sparql-route
      res/metadata-route

      ;; Non-entity routes which support content-negotiation
      res/markdown-route
      res/search-route
      res/autocomplete-route

      ;; Various entity routes which support content-negotiation
      (res/prefix->entity-route 'dn)
      (res/prefix->entity-route 'dnc)
      (res/prefix->entity-route 'dns)
      res/external-entity-route
      res/unknown-external-entity-route

      ;; These special routes ensure that we also match the individual dataset
      ;; or schema resources; can't use the 'prefix->entity-route' for this.
      (res/prefix->dataset-entity-route 'dn)
      (res/prefix->dataset-entity-route 'dns)
      (res/prefix->dataset-entity-route 'dnc)}))

(defn remove-trailing-slash
  [s]
  (if (and (str/ends-with? s "/") (not= s "/"))
    (subs s 0 (dec (count s)))
    s))

(def trailing-slash
  (io.pedestal.interceptor/interceptor
    {:name  ::trailing-slash
     :enter (fn [ctx]
              (-> ctx
                  (update-in [:request :uri] remove-trailing-slash)
                  (update-in [:request :path-info] remove-trailing-slash)))}))

;; 400 requests/minute based on fingerprinting
(def dannet-rate-limit-ic
  (rl/->rate-limit-ic {:quota     400
                       :window-ms 60000}))

(defn ->service-map
  [conf]
  (let [csp (if shared/development?
              {:default-src "'self' 'unsafe-inline' 'unsafe-eval' localhost:* 0.0.0.0:* ws://localhost:* ws://0.0.0.0:* mac:* ws://mac:*"}
              {:default-src "'none'"
               :script-src  "'self' 'unsafe-inline'"        ; unsafe-eval possibly only needed for dev main.js
               :connect-src "'self'"
               :img-src     "'self'"
               :font-src    "'self'"
               :style-src   "'self' 'unsafe-inline'"
               :base-uri    "'self'"})]
    (-> {::http/routes         #((deref #'routes))
         ::http/type           :jetty
         ::http/host           "0.0.0.0"
         ::http/port           3456
         ::http/resource-path  "/public"
         ::http/secure-headers {:content-security-policy-settings csp}}

        ;; Extending default interceptors here.
        (http/default-interceptors)
        ;; TODO: consider route-differentiated rate limits instead
        (cond->
          (not shared/development?) (update ::http/interceptors #(cons %2 %1) dannet-rate-limit-ic))
        (update ::http/interceptors #(cons %2 %1) trailing-slash)
        (update ::http/interceptors conj middleware/cookies)

        ;; Make sure we can communicate with the Shadow CLJS app during dev.
        ;; The CORS interceptor handles preflight OPTIONS requests and adds CORS
        ;; headers. This is required for shadow-cljs dev server (port 7777) to
        ;; make cross-origin requests to this backend (port 3456).
        (cond->
          shared/development? (update ::http/interceptors 
                                      #(cons (cors/allow-origin {:allowed-origins (constantly true)
                                                                 :creds true}) %))))))

(defn start []
  (let [service-map (->service-map @conf)]
    (http/start (http/create-server service-map))))

(defn start-dev []
  (set! NodeValue/VerboseWarnings false)                    ; annoying warnings
  (async/thread @res/db)                                    ; init database
  (reset! server (http/start (http/create-server (assoc (->service-map @conf)
                                                   ::http/join? false)))))

(defn stop-dev []
  (http/stop @server))

(defn restart []
  (when @server
    (stop-dev))
  (start-dev))

(defn -main
  [& args]
  ;; When uploading a db build from the dev machine the server, we want to skip
  ;; the bootstrap phase entirely.
  (when (not-empty (filter #{"--no-bootstrap"} args))
    (swap! res/dannet-opts dissoc :input-dir))
  (start))

(comment
  @conf
  (restart)
  (stop-dev)

  ;; Rate-limiting
  @rl/registry
  (reset! rl/registry {})
  #_.)
