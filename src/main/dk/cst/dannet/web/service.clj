(ns dk.cst.dannet.web.service
  "Web service handling entity look-ups and schema downloads."
  (:require [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [dk.cst.dannet.web.resources :as res]
            [dk.cst.dannet.shared :as shared])
  (:import [org.apache.jena.sparql.expr NodeValue])
  (:gen-class))

(defonce server (atom nil))
(defonce conf (atom {}))                                    ;TODO: use?

(defn routes
  []
  (route/expand-routes
    #{res/root-route
      res/dannet-route
      res/search-route
      res/autocomplete-route
      res/external-entity-route
      res/unknown-external-entity-route

      (res/prefix->dataset-entity-route 'dn)
      (res/prefix->dataset-entity-route 'dns)
      (res/prefix->dataset-entity-route 'dnc)

      (res/prefix->download-route 'tr)
      (res/prefix->download-route 'dnc)
      (res/prefix->download-route 'dns)

      (res/prefix->entity-route 'dn)
      (res/prefix->entity-route 'dnc)
      (res/prefix->entity-route 'dns)}))

(defn ->service-map
  [conf]
  (let [csp (if shared/development?
              {:default-src "'self' 'unsafe-inline' 'unsafe-eval' localhost:* 0.0.0.0:* ws://localhost:* ws://0.0.0.0:*"}
              {:default-src "'none'"
               :script-src  "'self' 'unsafe-inline'"        ; unsafe-eval possibly only needed for dev main.js
               :connect-src "'self'"
               :img-src     "'self'"
               :font-src    "'self'"
               :style-src   "'self' 'unsafe-inline'"
               :base-uri    "'self'"})]
    (cond-> {::http/routes         #((deref #'routes))
             ::http/type           :jetty
             ::http/host           "0.0.0.0"
             ::http/port           3456
             ::http/resource-path  "/public"
             ::http/secure-headers {:content-security-policy-settings csp}}

      ;; Make sure we can communicate with the Shadow CLJS app during dev.
      shared/development? (assoc ::http/allowed-origins (constantly true)))))

(defn start []
  (let [service-map (->service-map @conf)]
    (http/start (http/create-server service-map))))

(defn start-dev []
  (set! NodeValue/VerboseWarnings false)                    ; annoying warnings
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
  (start))

(comment
  @conf
  (restart)
  #_.)
