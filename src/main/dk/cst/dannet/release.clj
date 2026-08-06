(ns dk.cst.dannet.release
  "The DanNet release versions and the on-disk bootstrap layout keyed on them.

  Kept free of DanNet dependencies since it is required from both ends of the
  namespace graph: db.query and db.bootstrap.* consume `from`, while db.export.*
  and the dataset metadata consume `to`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def from
  "The previous formal release the database is bootstrapped from. The files in
  its version-dir must match it precisely, and it decides which release
  downloads/fetch-bootstrap-datasets! pulls from GitHub."
  "2026-08-03")

(def to
  "The version being produced. Defaults to `from`, i.e. during development we
  reproduce the release we bootstrap from; set to a new version only at the
  moment a release is cut, which is also what enables make-release-changes!."
  from)

(def bootstrap-root
  "Parent of the version-named bootstrap directories."
  "bootstrap/from")

(def indegrees-filename
  "Filename of the synset-indegree cache, which ships as a release asset and so
  appears both among the bootstrap input files and among the export artifacts."
  "synset-indegree.edn")

(defn version-dir
  "The bootstrap directory holding the input files for release `v`, given either
  as a bare version or as a GitHub tag.

  Naming the directory after the release is also what keeps the databases
  distinct: ->dannet hashes the input File paths, so the version in the path is
  what makes db-name version-specific."
  [v]
  (io/file bootstrap-root (cond-> v (str/starts-with? v "v") (subs 1))))
