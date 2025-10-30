(ns user
  (:import [java.net HttpURLConnection URL]))

(defn shadow-handler
  "Handler used by shadow-cljs to orient itself on page load.
  Note that the backend web service must be running on http://0.0.0.0:3456!"
  [{:keys [uri query-string headers] :as request}]
  (let [{:strs [accept-language cookie]} headers
        url  (URL. (str "http://localhost:3456" uri
                        (when query-string (str "?" query-string))))
        ;; Forward Accept-Language & Cookie headers to ensure server-side
        ;; language negotiation matches client-side hydration. Without this,
        ;; the server may render in a different way than the client expects,
        ;; causing React hydration mismatches.
        conn (doto ^HttpURLConnection (.openConnection url)
               (.setRequestMethod "GET")
               (.setRequestProperty "Accept-Language" accept-language)
               (.setRequestProperty "Cookie" cookie))]
    {:status  200
     :headers {"Content-Type" "text/html"}
     ;; NOTE: this used to be just (slurp url), but it had to be changed to
     ;; forward some necessary headers.
     :body    (slurp (.getInputStream conn))}))
