(ns dk.cst.dannet.web.anomaly
  "Errors for user-facing error pages, as Cognitect Anomalies.
  Inspired by this post: https://mbezjak.github.io/posts/exception-translation/

  Classifies errors into anomaly categories using the qualified keywords
  from cognitect.anomalies and provides bilingual (DA/EN) user-friendly
  messages. The `from-error` function maps the errors of the SPARQL
  endpoint, and `translate` any other exception at the boundary, into
  anomaly maps that the UI can render.

  An anomaly map has the shape:
    {::anom/category  ::anom/busy
     ::anom/message   \"The query took too long.\" ; localised
     :message         {:da \"...\" :en \"...\"}    ; bilingual source
     :retry?          true
     :status          503
     :details         \"...\"}                     ; optional, dev only"
  (:require [cognitect.anomalies :as-alias anom]))

(def categories
  {::anom/busy
   {:message {:da "Forespørgslen tog for lang tid. Prøv igen senere eller forenkl din forespørgsel."
              :en "The query took too long. Please try again later or simplify your query."}
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

(def error-categories
  "The anomaly category of each type of error of the SPARQL endpoint that
  isn't ::anom/incorrect, the category of the rest, e.g. :parse-error."
  {:timeout             ::anom/busy
   :form-not-allowed    ::anom/forbidden
   :service-not-allowed ::anom/forbidden
   :java-not-allowed    ::anom/forbidden
   :update-not-allowed  ::anom/forbidden
   :execution-failed    ::anom/fault})

(defn from-error
  "The anomaly map of the `error` of an operation of the SPARQL endpoint,
  with its status, and its message or the :details it keeps from clients as
  the details."
  [{:keys [status type message details]}]
  (-> (make (error-categories type ::anom/incorrect)
            :details (or details message))
      (assoc :status status)))

#?(:clj
   (defn translate
     "Translate the exception `ex` into an anomaly map of ::anom/fault, with
     its class and message as the details."
     [ex]
     (make ::anom/fault
           :details (str (.getSimpleName (class ex)) ": " (ex-message ex)))))
