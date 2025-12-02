(ns dk.cst.dannet.web.ui.error
  "Error boundary mixin and macros for catching render errors.
  
  Provides two mechanisms for error handling:
    - React error boundaries (CLJS) via 'error-boundary-mixin'
    - try/catch wrappers (CLJ/CLJS) via 'try-render' and 'try-render-with'
    - Imperative DOM error handling (CLJS only) via 'try-static-render'
  
  All caught errors are logged via Telemere."
  (:require [rum.core :as rum]
            [taoensso.telemere :as t]
            #?(:clj [clojure.stacktrace])))

(def error-boundary-mixin
  "Rum mixin that catches render errors in child components.

  Stores the caught error in component state under ::error key.
  Logs error via Telemere. Only active on CLJS (no-op on CLJ)."
  #?(:cljs {:did-catch (fn [state error info]
                         (t/log! {:level :error
                                  :error error
                                  :data  {:info info}}
                                 "React render error")
                         (assoc state ::error error))}
     :clj  {}))

(defn default-fallback
  "Default error fallback: expandable details with message and stack trace."
  [e]
  [:details.render-error
   [:summary "⚠️ " (ex-message e)]
   [:pre #?(:cljs (.-stack e)
            :clj  (with-out-str
                    (clojure.stacktrace/print-cause-trace e)))]])

(rum/defcs error-boundary < error-boundary-mixin
  "Wrap `child` in a React error boundary, showing `fallback` on error.

  Displays a default expandable details element if no fallback provided.
  Prefer using 'try-render' macro instead. Only active on CLJS."
  [state child fallback]
  (if-let [error (::error state)]
    (or fallback (default-fallback error))
    child))

;; TODO: find a way to preserve backend errors in the frontend after SSR,
;;       e.g. log to console before React hydration replaces the content.
(defn fallback-content
  "Generate fallback hiccup for `e` using `fallback` or a default.
  
  Logs the error via Telemere. Default fallback is an expandable details
  element with error message and stack trace."
  [e fallback]
  (t/log! {:level :error
           :error e}
          "Render error caught")
  (or fallback (default-fallback e)))

(defn- emit-try-catch
  "Emit try/catch for `body` with `error-sym` and `catch-body`.
  
  When `cljs?` is true, catches :default; otherwise catches Exception."
  [cljs? body error-sym catch-body]
  (if cljs?
    `(try ~body (catch :default ~error-sym ~catch-body))
    `(try ~body (catch Exception ~error-sym ~catch-body))))

(defmacro try-render
  "Wrap `child` in try/catch, showing `fallback` on error.

  Wraps in 'error-boundary' for consistent SSR/hydration and to catch
  React rendering errors on CLJS.
  
  Examples:
    (try-render (component opts))
    (try-render (component opts) [:span \"error\"])"
  [child & [fallback]]
  (let [cljs? (:ns &env)]
    (emit-try-catch
      cljs?
      `(error-boundary ~child ~fallback)
      'e#
      `(fallback-content ~'e# ~fallback))))

(defmacro try-render-with
  "Wrap `child` in try/catch with `bindings` for error context.
  
  First symbol in `bindings` is bound to the exception. Remaining symbols
  are logged as context data. Wraps in 'error-boundary' for consistent
  SSR/hydration.
  
  Example:
    (try-render-with [e v opts]
      (transform-val* v opts)
      [:span.render-error {:title (ex-message e)} (str v)])"
  [bindings child fallback]
  (let [cljs?       (:ns &env)
        [error-sym & context-syms] bindings
        context-map (zipmap (map keyword context-syms)
                            context-syms)]
    (emit-try-catch
      cljs?
      `(error-boundary ~child nil)
      error-sym
      `(do
         (t/log! {:level :error
                  :error ~error-sym
                  :data  ~context-map}
                 "Render error caught")
         ~fallback))))

(defmacro try-static-render
  "Wrap `body` in try/catch, rendering fallback into `elem` on error.
  
  This is used for imperative DOM code (e.g. ref callbacks) that runs *outside*
  React's normal render cycle. It uses 'rum/render-static-markup' to render
  the fallback hiccup directly into the element if needed.
  
  Examples:
    (try-static-render node (build-viz! node))
    (try-static-render app (rum/mount component app) fallback-page)"
  [elem & body]
  (let [cljs?    (:ns &env)
        fallback (when (> (count body) 1) (last body))
        body     (if fallback (butlast body) body)]
    ;; CLJS: wrap in try/catch. CLJ: no-op, just execute body.
    (if cljs?
      `(try
         ~@body
         (catch :default e#
           (set! (.-innerHTML ~elem)
                 (rum/render-static-markup (fallback-content e# ~fallback)))))
      `(do ~@body))))
