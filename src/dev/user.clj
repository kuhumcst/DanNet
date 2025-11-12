(ns user
  (:import [java.net HttpURLConnection URL]))

;; TROUBLESHOOTING REACT HYDRATION ERRORS:
;; If you experience hydration mismatches (e.g. server renders in English
;; client in Danish), then it's likely due to stale cookies on localhost:3456.
;; The language widget sets cookies on localhost:7777 (shadow-cljs), but old
;; cookies may exist on localhost:3456 (backend). These are separate cookies!
;; 
;; To fix: clear cookies for localhost:3456 in your browser's developer tools.
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
               (.setRequestProperty "Accept-Language" (or accept-language ""))
               (.setRequestProperty "Cookie" (or cookie "")))]
    {:status  200
     :headers {"Content-Type" "text/html"}
     ;; NOTE: this used to be just (slurp url), but it had to be changed to
     ;; forward some necessary headers.
     :body    (slurp (.getInputStream conn))}))
