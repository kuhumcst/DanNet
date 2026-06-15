(ns dk.cst.dannet.db.query.function
  "Custom ARQ SPARQL functions registered in the global `FunctionRegistry`, so
  any SPARQL query may call them regardless of inference mode.

  Generic plumbing only: `->function` lifts a plain `(ctx & args -> double|nil)`
  fn into a `FunctionFactory`, coercing each raw argument via `coerce` and
  leaving the result unbound (via `ExprEvalException`) when any coercion or the
  fn itself yields nil; `register-functions!` adds a map of such factories under
  a caller-chosen RDF namespace. The concrete functions live with their domain
  (e.g. the taxonomy similarity metrics in `dk.cst.dannet.similarity`, exposed
  under `prefix/dnf-uri`)."
  (:require [dk.cst.dannet.prefix :as prefix]
            [ont-app.vocabulary.core :as voc])
  (:import [org.apache.jena.sparql.engine.binding Binding]
           [org.apache.jena.sparql.expr Expr ExprList NodeValue ExprEvalException]
           [org.apache.jena.sparql.function Function FunctionEnv FunctionFactory
                                            FunctionRegistry]))

(defn node->keyword
  "Keyword for the URI wrapped by `nv`, or nil when it isn't a known URI node."
  [^NodeValue nv]
  (let [node (.asNode nv)]
    (when (.isURI node)
      (try
        (voc/keyword-for (.getURI node))
        (catch Exception _ nil)))))

(defn ->function
  "Lift `f` (ctx & coerced-args -> double|nil) into an ARQ FunctionFactory.
  `ctx-fn` yields a context value at exec time (derefed lazily); each raw
  argument is run through `coerce`, and a nil from any coercion or from `f`
  leaves the result unbound."
  [ctx-fn coerce f]
  (reify FunctionFactory
    (create [_ _uri]
      (reify Function
        (build [_ _uri _args])                              ; no build-time check
        (exec [_ binding args _uri env]
          (let [^ExprList args   args
                ^Binding binding binding
                ^FunctionEnv env env
                vals (map (fn [^Expr e] (coerce (.eval e binding env)))
                          (.getList args))
                r    (when (every? some? vals) (apply f (ctx-fn) vals))]
            (if (nil? r)
              (throw (ExprEvalException. "no result"))
              (NodeValue/makeDouble (double r)))))))))

(defn register-functions!
  "Register `factories` (a map of local-name -> FunctionFactory) in the global
  ARQ FunctionRegistry under the RDF namespace `uri` (also registered under the
  `ns-prefix` prefix), so any SPARQL query may call them by their qualified
  name."
  [ns-prefix uri factories]
  (prefix/register ns-prefix uri)
  (let [registry (FunctionRegistry/get)]
    (doseq [[local factory] factories]
      (.put registry (str uri local) factory))))
