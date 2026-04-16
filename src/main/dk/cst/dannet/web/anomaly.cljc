(ns dk.cst.dannet.web.anomaly
  "Exception translation for user-facing error pages using Cognitect Anomalies.
  Inspired by this post: https://mbezjak.github.io/posts/exception-translation/

  Classifies exceptions into anomaly categories using the qualified keywords
  from cognitect.anomalies and provides bilingual (DA/EN) user-friendly
  messages. The `translate` function maps Java exceptions at the boundary
  into anomaly maps that the UI can render.

  An anomaly map has the shape:
    {::anom/category  ::anom/busy
     ::anom/message   \"The query took too long.\" ; localised
     :message         {:da \"...\" :en \"...\"}    ; bilingual source
     :retry?          true
     :status          504
     :details         \"...\"}                     ; optional, dev only"
  (:require [cognitect.anomalies :as-alias anom])
  #?(:clj (:import [clojure.lang ExceptionInfo]
                   [java.util.concurrent TimeoutException]
                   [org.apache.jena.query QueryCancelledException])))

(def categories
  {::anom/busy
   {:message {:da "Forespørgslen tog for lang tid. Prøv igen senere eller forenkl din forespørgsel."
              :en "The query took too long. Please try again later or simplify your query."}
    :retry?  true
    :status  504}

   ::anom/unavailable
   {:message {:da "Tjenesten er midlertidigt utilgængelig. Prøv igen senere."
              :en "The service is temporarily unavailable. Please try again later."}
    :retry?  true
    :status  503}

   ::anom/incorrect
   {:message {:da "Forespørgslen er ugyldig."
              :en "The query is invalid."}
    :retry?  false
    :status  400}

   ::anom/forbidden
   {:message {:da "Denne type forespørgsel er ikke tilladt."
              :en "This type of query is not allowed."}
    :retry?  false
    :status  403}

   ::anom/not-found
   {:message {:da "Den ønskede ressource blev ikke fundet."
              :en "The requested resource was not found."}
    :retry?  false
    :status  404}

   ;; TODO: maybe use ::anom/busy instead?
   ;; Not in cognitect.anomalies, but useful for rate limiting.
   ::too-many-requests
   {:message {:da "For mange forespørgsler. Vent venligst et øjeblik og prøv igen."
              :en "Too many requests. Please wait a moment and try again."}
    :retry?  true
    :status  429}

   ::anom/fault
   {:message {:da "Der opstod en uventet fejl."
              :en "An unexpected error occurred."}
    :retry?  false
    :status  500}})

(defn make
  "Create an anomaly map for `category`, optionally overriding the default
  message or adding technical details.

  Options:
    :message  - override the default bilingual message {:da \"...\" :en \"...\"}
    :details  - technical details string (shown in dev mode only)"
  [category & {:keys [message details]}]
  (let [defaults (get categories category (get categories ::anom/fault))]
    (cond-> (assoc defaults ::anom/category category)
      message (assoc :message message)
      details (assoc :details details))))

(defn localize
  "Select the user-facing message string from `anomaly` map, given `languages`.
  Also assocs the result as ::anom/message for convenience."
  [anomaly languages]
  (let [lang (if (= "da" (first languages)) :da :en)]
    (assoc anomaly ::anom/message (get-in anomaly [:message lang]))))

;; -- Exception translation (CLJ only) ----------------------------------------

#?(:clj
   (do
     (def ^:private validation-type->category
       "Maps SPARQL validation ex-data :type to anomaly category."
       {:parse-error        ::anom/incorrect
        :query-too-long     ::anom/incorrect
        :unsafe-query-type  ::anom/forbidden
        :update-not-allowed ::anom/forbidden})

     (defn translate
       "Translate an exception into an anomaly map.

       Handles known exception types with specific categories and messages;
       falls back to ::anom/fault for unknown exceptions. Always returns
       an anomaly."
       [ex]
       (cond
         ;; ExceptionInfo with :type in ex-data (SPARQL validation errors)
         (and (instance? ExceptionInfo ex)
              (contains? (ex-data ex) :type))
         (let [{:keys [type cause max actual]} (ex-data ex)
               category (get validation-type->category type ::anom/fault)
               details  (cond-> (or cause (ex-message ex))
                          max (str " (max: " max ", actual: " actual ")"))]
           (make category :details details))

         ;; Query timeout (Jena cancellation or hard timeout)
         (or (instance? QueryCancelledException ex)
             (instance? TimeoutException ex))
         (make ::anom/busy)

         ;; Generic fallback
         :else
         (make ::anom/fault
               :details (str (.getSimpleName (class ex))
                             ": " (ex-message ex)))))))
