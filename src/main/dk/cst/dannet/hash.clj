(ns dk.cst.dannet.hash
  "Functions for hashing program data; used to invoke database rebuilds.

  The :hash key of the metadata attached to any hashed def/defn forms can be
  checked at runtime. If this hash differs from the last recorded hash, the form
  must have changed in some way.

  The key functions and data in DanNet have been decorated with hashes.
  These hashes are checked when instantiating an instance of a DanNet database."
  (:require [clojure.walk :as walk])
  (:refer-clojure :exclude [defn]))

;; Via jpmonettas: https://clojurians.slack.com/archives/C03S1KBA2/p1670838328124429
;; Copy-pasted from: https://github.com/jpmonettas/hansel/blob/master/src/hansel/instrument/forms.clj#L829-L865
(clojure.core/defn normalize-gensyms
  "When the reader reads things like #(+ % %) it uses a global id to generate symbols,
  so everytime will read something different, like :
  (fn* [p1__37935#] (+ p1__37935# p1__37935#))
  (fn* [p1__37939#] (+ p1__37939# p1__37939#))
  Normalize symbol can be applied to generate things like :
  (fn* [p__0] (+ p__0 p__0)).
  Useful for generating stable form hashes."
  [form]
  (let [psym->id (atom {})
        gensym?  (fn [x]
                   (and (symbol? x)
                        (re-matches #"^p([\d])__([\d]+)#$" (name x))))
        normal   (fn [psym]
                   (let [ids    @psym->id
                         nsymid (if-let [id (get ids psym)]
                                  id

                                  (if (empty? ids)
                                    0
                                    (inc (apply max (vals ids)))))]

                     (swap! psym->id assoc psym nsymid)

                     (symbol (str "p__" nsymid))))]
    (walk/postwalk
      (fn [x]
        (if (gensym? x)
          (normal x)
          x))
      form)))

(clojure.core/defn hash-form
  "Ensure that the sequential `form` coll hashes the same across restarts."
  [form]
  (hash (mapv str (normalize-gensyms form))))

(defmacro def
  "A regular def macro that hashes its own body and attaches this to :hash."
  [& [name :as args]]
  `(do
     (def ~@args)
     (alter-meta! #'~name assoc :hash (hash-form (quote ~args)))))

(defmacro defn
  "A regular defn macro that hashes its own body and attaches this to :hash."
  [& [name :as args]]
  `(do
     (clojure.core/defn ~@args)
     (alter-meta! #'~name assoc :hash (hash-form (quote ~args)))))
