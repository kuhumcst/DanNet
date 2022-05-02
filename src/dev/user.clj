(ns user)

(defn shadow-handler
  "Handler used by shadow-cljs to orient itself on page load.
  Note that the backend web service must be running on http://0.0.0.0:3456!"
  [{:keys [uri query-string] :as request}]
  {:status  200
   :headers {"Content-Type" "text/html"}
   :body    (slurp (str "http://localhost:3456" uri
                        (when query-string
                          (str "?" query-string))))})
