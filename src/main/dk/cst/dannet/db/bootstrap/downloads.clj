(ns dk.cst.dannet.db.bootstrap.downloads
  "Functions for fetching the bootstrap datasets (DanNet, OEWN, CILI)."
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [dk.cst.dannet.db.query :as q])
  (:import [java.util.zip GZIPInputStream]))

(def bootstrap-files
  "The set of DanNet release zips that constitute a bootstrap. Shared by the
  fetch and the missing-file check so the two can't drift apart."
  #{"dannet.zip"
    "cor.zip"
    "dds.zip"
    "oewn-extension.zip"})

(defn fetch-bootstrap-datasets!
  "Fetch DanNet dataset releases from GitHub and prepare them for bootstrapping.

     :version - Specific release (e.g. \"v2024-08-09\"), defaults to latest
     :files - Set of datasets to fetch, defaults to all (see bootstrap-files)
     :dir - Target directory, defaults to bootstrap/latest"
  [& {:keys [version files dir]
      :or   {files bootstrap-files
             dir   (io/file "bootstrap/latest")}}]
  (let [bootstrap-dir (io/file dir)
        github-api    "https://api.github.com/repos/kuhumcst/DanNet/releases"]

    (when-not (.exists bootstrap-dir)
      (.mkdirs bootstrap-dir))

    (println "Fetching release information from GitHub...")
    (let [releases-url (if version
                         (str github-api "/tags/" version)
                         (str github-api "/latest"))
          response     (slurp releases-url)
          gh-release   (json/read-str response :key-fn keyword)
          assets       (:assets gh-release)
          release-name (or (:tag_name gh-release) (:name gh-release))]
      (println (str "Found release: " release-name))

      (doseq [filename files]
        (when-let [asset (first (filter #(= filename (:name %)) assets))]
          (let [download-url (:browser_download_url asset)
                output-file  (io/file bootstrap-dir filename)]
            (println (str "Downloading " filename "..."))
            (with-open [in  (io/input-stream download-url)
                        out (io/output-stream output-file)]
              (io/copy in out)))))

      (println "Bootstrap datasets ready!")
      release-name)))

(defn missing-bootstrap-files
  "Return the bootstrap-files that are not present as actual files in `dir`."
  [dir]
  (remove #(.exists (io/file dir %)) bootstrap-files))

(defn ensure-bootstrap-datasets!
  "Ensure the bootstrap zips are present in `dir`, downloading any that are
  missing from the `version` release on GitHub."
  [dir version]
  (if-let [missing (seq (missing-bootstrap-files dir))]
    (do
      (println "Missing bootstrap files in" (str dir) "--" (vec missing))
      (fetch-bootstrap-datasets! :version (str "v" version)
                                 :files (set missing)
                                 :dir dir))
    (println "All bootstrap files already present in" (str dir)))
  dir)

(defn ensure-synset-indegrees!
  "Ensure the synset-indegree cache exists at the path query/ reads it from.

  It ships as an asset in each DanNet release because regenerating it from
  scratch is slow, so fetch it from the `version` release if missing rather than
  recomputing. Unlike the dataset zips it must land in db/ (next to the TDB2
  data), not bootstrap/latest/ -- so the destination is the file's own parent
  dir, not the bootstrap dir."
  [version]
  (let [file (io/file q/synset-indegrees-file)]
    (when-not (.exists file)
      (println "Missing" (str file) "-- fetching from release")
      (fetch-bootstrap-datasets! :version (str "v" version)
                                 :files #{(.getName file)}
                                 :dir (.getParentFile file)))))

(def english-dir
  "Directory holding the bootstrap English datasets (OEWN + ILI)."
  "bootstrap/other/english")

(def oewn-version
  "The Open English WordNet edition to bootstrap against."
  "2024")

(def oewn-ttl-path
  (str english-dir "/english-wordnet-" oewn-version ".ttl"))

(def ili-path
  (str english-dir "/ili.ttl"))

(def ili-url
  "Direct download link for the CILI interlingual index (ili.ttl)."
  "https://raw.githubusercontent.com/globalwordnet/cili/master/ili.ttl")

(defn ensure-english-datasets!
  "Download the OEWN and ILI datasets into english-dir if missing."
  []
  (let [dir      (io/file english-dir)
        oewn-ttl (io/file oewn-ttl-path)
        oewn-gz  (io/file (str oewn-ttl-path ".gz"))
        ili      (io/file ili-path)]
    (.mkdirs dir)

    (when-not (.exists oewn-ttl)
      (try
        (with-open [in-gz  (io/input-stream (str "https://en-word.net/static/english-wordnet-" oewn-version ".ttl.gz"))
                    out-gz (io/output-stream oewn-gz)]
          (io/copy in-gz out-gz))
        (with-open [in-ttl  (GZIPInputStream. (io/input-stream oewn-gz))
                    out-ttl (io/output-stream oewn-ttl)]
          (io/copy in-ttl out-ttl))
        (.delete oewn-gz)
        (println "✓ OEWN")
        (catch Exception e (println "⚠ OEWN failed:" (.getMessage e)))))

    (when-not (.exists ili)
      (try
        (with-open [in-ili  (io/input-stream ili-url)
                    out-ili (io/output-stream ili)]
          (io/copy in-ili out-ili))
        (println "✓ ILI")
        (catch Exception e (println "⚠ ILI failed:" (.getMessage e)))))

    {:oewn-exists (.exists oewn-ttl)
     :ili-exists  (.exists ili)}))

(comment
  (ensure-english-datasets!)                                ; ILI and OEWN

  (fetch-bootstrap-datasets!)                               ; latest version
  (fetch-bootstrap-datasets! :version "v2024-08-09")        ; specific version
  (fetch-bootstrap-datasets! :files #{"dannet.zip"}))
