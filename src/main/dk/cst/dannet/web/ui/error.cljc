(ns dk.cst.dannet.web.ui.error
  "Error boundary mixin and macro for catching render errors."
  (:require [rum.core :as rum]
            #?(:clj [clojure.stacktrace])))

(def error-boundary-mixin
  "Mixin that catches render errors in child components.
  Stores the error in component state under ::error."
  #?(:cljs {:did-catch (fn [state error info]
                         (js/console.error "Render error:" error)
                         (assoc state ::error error))}
     :clj  {}))

(rum/defcs error-boundary < error-boundary-mixin
  "Component wrapper for React error boundaries (CLJS only).
  Use `with-fallback` macro instead of calling this directly."
  [state child fallback]
  (if-let [error (::error state)]
    (or fallback
        [:details.render-error
         [:summary "⚠️ " (ex-message error)]
         [:pre #?(:cljs (.-stack error)
                  :clj  nil)]])
    child))

;; TODO: find a way to preserve backend errors in the frontend after SSR,
;;       e.g. log to console before React hydration replaces the content.
(defn fallback-content
  "Generate fallback hiccup for a caught error `e`."
  [e fallback]
  (or fallback
      [:details.render-error
       [:summary "⚠️ " (ex-message e)]
       [:pre #?(:cljs (.-stack e)
                :clj  (with-out-str
                        (clojure.stacktrace/print-cause-trace e)))]]))

(defmacro with-fallback
  "Wrap `child` to catch render errors and display `fallback` instead.
  On CLJ: uses try/catch. On CLJS: uses React error boundary only."
  [child & [fallback]]
  (if (:ns &env)
    ;; CLJS: error boundary only (most errors happen during React rendering)
    `(error-boundary ~child ~fallback)
    ;; CLJ: try/catch
    `(try
       ~child
       (catch Exception e#
         (fallback-content e# ~fallback)))))
