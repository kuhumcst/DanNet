(ns dk.cst.dannet.db.bootstrap.downloads
  "Functions for fetching the bootstrap datasets (DanNet, OEWN, CILI).

  Every fetch fails fast: a network error, a missing release asset, or a failed
  decompression throws rather than leaving the bootstrap half-populated. Files
  are also written atomically (download to a temp sibling, then move into
  place), so a crashed/partial download never leaves a file that a later run --
  or the import step -- would mistake for complete."
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [taoensso.telemere :as t]
            [dk.cst.dannet.db.query :as q])
  (:import [java.util.zip GZIPInputStream]
           [java.nio.file CopyOption Files StandardCopyOption]))

(defn- move-into-place!
  "Atomically move `tmp` onto `dest` (same filesystem), replacing any existing
  file. Pairs with the temp-file writes below so `dest` only ever appears once
  it is complete."
  [tmp dest]
  (Files/move (.toPath (io/file tmp))
              (.toPath (io/file dest))
              (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING])))

(defn download-to-file!
  "Stream `url` to `dest`, writing to a temp sibling first and moving it into
  place only on success. Deletes the temp file and rethrows on any failure."
  [url dest]
  (let [tmp (io/file (str dest ".part"))]
    (io/make-parents tmp)
    (t/trace! {:id   :dannet.download/file
               :data {:url (str url) :dest (str dest)}}
      (try
        (with-open [in  (io/input-stream url)
                    out (io/output-stream tmp)]
          (io/copy in out))
        (move-into-place! tmp dest)
        (catch Throwable e
          (.delete tmp)
          (throw e))))
    (io/file dest)))

(defn gunzip-to-file!
  "Decompress gzip `src` to `dest`, writing to a temp sibling first and moving it
  into place only on success. Deletes the temp file and rethrows on any failure."
  [src dest]
  (let [tmp (io/file (str dest ".part"))]
    (io/make-parents tmp)
    (t/trace! {:id   :dannet.download/gunzip
               :data {:src (str src) :dest (str dest)}}
      (try
        (with-open [in  (GZIPInputStream. (io/input-stream src))
                    out (io/output-stream tmp)]
          (io/copy in out))
        (move-into-place! tmp dest)
        (catch Throwable e
          (.delete tmp)
          (throw e))))
    (io/file dest)))

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
     :dir - Target directory, defaults to bootstrap/latest

  Throws if a requested file has no matching asset in the release, so a partial
  or stale release can't quietly leave the bootstrap incomplete."
  [& {:keys [version files dir]
      :or   {files bootstrap-files
             dir   (io/file "bootstrap/latest")}}]
  (let [bootstrap-dir (io/file dir)
        github-api    "https://api.github.com/repos/kuhumcst/DanNet/releases"]

    (when-not (.exists bootstrap-dir)
      (.mkdirs bootstrap-dir))

    (let [releases-url (if version
                         (str github-api "/tags/" version)
                         (str github-api "/latest"))
          response     (slurp releases-url)
          gh-release   (json/read-str response :key-fn keyword)
          assets       (:assets gh-release)
          release-name (or (:tag_name gh-release) (:name gh-release))]
      (t/event! :dannet.download/release-found
                {:level :info
                 :data  {:release release-name
                         :files   (vec files)
                         :dir     (str bootstrap-dir)}})

      (doseq [filename files]
        (if-let [asset (first (filter #(= filename (:name %)) assets))]
          (download-to-file! (:browser_download_url asset)
                             (io/file bootstrap-dir filename))
          (throw (ex-info (str "No asset named " filename " in release " release-name)
                          {:filename filename
                           :release  release-name
                           :assets   (map :name assets)}))))

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
      (t/log! {:level :info
               :id    :dannet.download/bootstrap-missing
               :data  {:dir (str dir) :missing (vec missing)}}
              "Fetching missing bootstrap files")
      (fetch-bootstrap-datasets! :version (str "v" version)
                                 :files (set missing)
                                 :dir dir))
    (t/log! {:level :debug
             :id    :dannet.download/bootstrap-present
             :data  {:dir (str dir)}}
            "All bootstrap files already present"))
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
      (t/log! {:level :info
               :id    :dannet.download/synset-indegrees
               :data  {:file (str file) :version version}}
              "Fetching synset-indegree cache from release")
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
  "Download the OEWN and ILI datasets into english-dir if missing.

  Fails fast: any download or decompression error propagates so the overall
  bootstrap stops rather than continuing toward an import of a missing file."
  []
  (let [oewn-ttl (io/file oewn-ttl-path)
        ili      (io/file ili-path)]
    (when-not (.exists oewn-ttl)
      (let [oewn-gz (io/file (str oewn-ttl-path ".gz"))]
        (download-to-file! (str "https://en-word.net/static/english-wordnet-" oewn-version ".ttl.gz")
                           oewn-gz)
        (gunzip-to-file! oewn-gz oewn-ttl)
        (.delete oewn-gz)
        (t/event! :dannet.download/oewn
                  {:level :info :data {:version oewn-version :path (str oewn-ttl)}})))

    (when-not (.exists ili)
      (download-to-file! ili-url ili)
      (t/event! :dannet.download/ili
                {:level :info :data {:path (str ili)}}))))

(defn oewn-ttl-files
  "List the english-wordnet-*.ttl files in english-dir, regardless of version."
  []
  (let [dir (io/file english-dir)]
    (when (.isDirectory dir)
      (filter #(re-matches #"english-wordnet-.*\.ttl" (.getName %))
              (.listFiles dir)))))

(defn stale-oewn-files
  "List OEWN ttls on disk whose version differs from the required `oewn-version`
  -- i.e. any english-wordnet-*.ttl that isn't the file we bootstrap from now."
  []
  (let [wanted (.getName (io/file oewn-ttl-path))]
    (remove #(= wanted (.getName %)) (oewn-ttl-files))))

(defn assert-datasets-present!
  "Verify the datasets required to bootstrap are present in `dir`, erroring
  toward the refetch flag rather than downloading silently or building an
  incomplete database (the normal, non-refetch start path).

  The DanNet release *version* isn't checked here -- the zip filename doesn't
  encode it, so that is asserted during import. OEWN is version-checked, since
  its version lives in the filename."
  [dir]
  (let [missing-zips (missing-bootstrap-files dir)
        stale-oewn   (stale-oewn-files)
        oewn-ttl     (io/file oewn-ttl-path)
        ili          (io/file ili-path)
        indegrees    (io/file q/synset-indegrees-file)
        problems     (cond-> {}
                       (seq missing-zips)
                       (assoc :missing-bootstrap-zips (vec missing-zips))
                       (not (.exists oewn-ttl))
                       (assoc :missing-oewn (str oewn-ttl))
                       (seq stale-oewn)
                       (assoc :stale-oewn (mapv str stale-oewn))
                       (not (.exists ili))
                       (assoc :missing-ili (str ili))
                       (not (.exists indegrees))
                       (assoc :missing-synset-indegrees (str indegrees)))]
    (when (seq problems)
      (t/log! {:level :error
               :id    :dannet.bootstrap/datasets-incomplete
               :data  problems}
              "Bootstrap datasets incomplete; restart with refetch to download")
      (throw (ex-info (str "Bootstrap datasets incomplete -- restart with refetch "
                           "(--refetch, or restart-refetch in the REPL) to "
                           "download the required versions. " (pr-str problems))
                      {:problems problems})))))

(defn wipe-for-refetch!
  "Delete the version-bound bootstrap datasets in `dir` so the ensure-* steps
  re-download the required versions. Removes:

   - all DanNet release zips and extracted .ttl leftovers in `dir` (the zip
     filename doesn't encode a version, so we can't be selective -- wipe all)
   - the synset-indegree cache (ships per release)
   - stale OEWN ttls (a correct one is kept, so we don't re-pull ~46 MB)

  ILI is unversioned (pulled from CILI master), so there's no mismatch to act
  on: it's kept as-is, with a warning carrying the on-disk file's details so it
  can be refreshed manually if needed."
  [dir]
  (doseq [f     (.listFiles (io/file dir))
          :when (re-find #"\.(zip|ttl)$" (.getName f))]
    (t/log! {:level :info
             :id    :dannet.refetch/wipe-bootstrap
             :data  {:file (str f)}}
            "Wiping bootstrap file for refetch")
    (.delete f))
  (let [indegrees (io/file q/synset-indegrees-file)]
    (when (.exists indegrees)
      (t/log! {:level :info
               :id    :dannet.refetch/wipe-synset-indegrees
               :data  {:file (str indegrees)}}
              "Wiping synset-indegree cache for refetch")
      (.delete indegrees)))
  (doseq [f (stale-oewn-files)]
    (t/log! {:level :info
             :id    :dannet.refetch/wipe-oewn
             :data  {:file (str f) :required oewn-version}}
            "Wiping stale OEWN file for refetch")
    (.delete f))
  (let [ili (io/file ili-path)]
    (when (.exists ili)
      (t/log! {:level :warn
               :id    :dannet.refetch/ili-retained
               :data  {:path     (str ili)
                       :bytes    (.length ili)
                       :modified (str (java.time.Instant/ofEpochMilli (.lastModified ili)))
                       :source   ili-url}}
              "ILI is unversioned (CILI master) and kept as-is on refetch"))))

(defn refetch-datasets!
  "Wipe the stale/version-bound datasets in `dir` and re-fetch the required
  `version`. Drives the --refetch / restart-refetch path."
  [dir version]
  (wipe-for-refetch! dir)
  (ensure-bootstrap-datasets! dir version)
  (ensure-synset-indegrees! version)
  (ensure-english-datasets!))

(comment
  (ensure-english-datasets!)                                ; ILI and OEWN

  (fetch-bootstrap-datasets!)                               ; latest version
  (fetch-bootstrap-datasets! :version "v2024-08-09")        ; specific version
  (fetch-bootstrap-datasets! :files #{"dannet.zip"})

  ;; Wipe the stale/version-bound datasets, then re-fetch the expected release.
  (refetch-datasets! (io/file "bootstrap/latest") "2025-07-03")
  #_.)
