(ns dk.wordnet.web.service
  "Web service handling entity look-ups and schema downloads."
  (:require [io.pedestal.http :as http]
            [dk.wordnet.web.resources :as res]
            [io.pedestal.http.route :as route]))

(defonce server (atom nil))
(defonce conf (atom {}))                                    ;TODO: use?

(defn routes
  []
  (route/expand-routes
    #{res/external-entity-route

      (res/prefix->entity-route 'dn)
      (res/prefix->entity-route 'dnc)
      (res/prefix->entity-route 'dns)

      (res/prefix->schema-route 'dnc)
      (res/prefix->schema-route 'dns)}))

(defn ->service-map
  [conf]
  (let [csp {:default-src "'none'"
             :script-src  "'self'"
             :connect-src "'self'"
             :img-src     "'self'"
             :font-src    "'self'"
             :style-src   "'self'"
             :base-uri    "'self'"}]
    (cond-> {::http/routes         #((deref #'routes))
             ::http/type           :jetty
             ::http/host           "0.0.0.0"
             ::http/port           8080
             ::http/resource-path  "/public"
             ::http/secure-headers {:content-security-policy-settings csp}})))

(defn start []
  (let [service-map (->service-map @conf)]
    (http/start (http/create-server service-map))))

(defn start-dev []
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
  (restart))
