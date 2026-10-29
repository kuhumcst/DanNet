(ns dk.cst.dannet.db
  "General database access functions.

  Refer to 'dk.cst.dannet.db.bootstrap' for how to bootstrap an instance."
  (:require [taoensso.telemere :as t]
            [dk.cst.theophrastus :as theo])
  (:import [java.io File]))

(defn ->schema-model
  "Create a Model containing the schemas found at the given `uris`."
  [uris]
  (:model (theo/read! (theo/db {:transactional? false}) uris)))

(defn safe-add!
  "Add `data` to `db`, logging a warning instead of throwing on failure."
  [db data]
  (try
    (theo/transact! db data)
    (catch Exception e
      (t/log! {:level :warn
               :id    :dannet.db/safe-add-failed
               :error e
               :data  {:data-sample (subs (str data) 0 (min 240 (count (str data))))}}
              "Failed to add data; continuing"))))

(defn import-files
  "Import `files` into the named `graph` of the `db` in a safe way, optionally
  applying a `changefn` to the temporary db of the files before import."
  ([db graph files]
   (import-files db graph files nil))
  ([db graph files changefn]
   (t/log! {:level :info
            :id    :dannet.db/import-files
            :data  {:graph graph
                    :files (mapv #(if (instance? File %) (.getName ^File %) %) files)}}
           "Importing files")
   (let [temp (theo/db {:prefixes (:prefixes db) :transactional? false})]
     (t/log! {:level :debug :id :dannet.db/temp-graph-create} "Creating temporary in-memory graph")
     (theo/read! temp files)

     (when changefn
       (t/log! {:level :debug :id :dannet.db/temp-graph-changes} "Applying changes to temporary graph")
       (changefn temp))

     (t/log! {:level :debug
              :id    :dannet.db/temp-graph-persist
              :data  {:graph graph}}
             "Persisting temporary graph")
     (theo/transact! (theo/named-graph db graph) (theo/jena-graph temp)))))

(defn update-triples!
  "Update triples in the named `graph` of `db` by mapping `f` to `query`
  result maps producing new triples. The `query` may also be a [query bindings]
  pair, with bindings as in theophrastus/q.

  Optionally, supply one or more `triples-to-remove` which may either be generic
  triple patterns -OR- if only a one `removal` is supplied it may serve as an fn
  applied to the result maps (like `f`) producing removal triples instead."
  [db graph query f & [removal :as triples-to-remove]]
  (let [g                  (theo/named-graph db graph)
        ms                 (set (apply theo/q g (if (vector? query) query [query])))
        triples-to-add     (remove nil? (map f ms))
        triples-to-remove' (if (fn? removal)
                             (remove nil? (map removal ms))
                             triples-to-remove)]
    (when (not (empty? triples-to-remove'))
      (t/log! {:level :debug
               :id    :dannet.db/remove-triples
               :data  {:count   (count triples-to-remove')
                       :example (first triples-to-remove')}}
              "Removing triples")
      (theo/retract! g triples-to-remove'))
    (t/log! {:level :debug
             :id    :dannet.db/add-triples
             :data  {:added   (count triples-to-add)
                     :results (count ms)
                     :query   query}}
            "Adding updated triples")
    (safe-add! g triples-to-add)))
