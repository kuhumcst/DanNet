(ns dk.cst.dannet.db.bootstrap.downloads
  "Functions for fetching the bootstrap datasets (DanNet, OEWN, CILI, PreMOn).

  Every fetch fails fast and writes atomically, so a network error, a missing
  release asset or a failed decompression throws rather than leaving behind a
  partial file that a later run -- or the import step -- mistakes for finished."
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [taoensso.telemere :as t]
            [dk.cst.dannet.release :as release])
  (:import [java.io File]
           [java.time Instant]
           [java.util.zip GZIPInputStream]
           [java.nio.file CopyOption Files StandardCopyOption]))

(defn- atomically!
  "Build `dest` by calling `f` with a temp sibling and moving it into place.
  Deletes the temp file and rethrows on failure."
  [dest f]
  (let [tmp (io/file (str dest ".part"))]
    (io/make-parents tmp)
    (try
      (f tmp)
      (Files/move (.toPath tmp)
                  (.toPath (io/file dest))
                  (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))
      (catch Throwable e
        (.delete tmp)
        (throw e)))
    (io/file dest)))

(defn download-to-file!
  "Stream `url` to `dest`. `:wrap` wraps the input stream before copying, e.g.
  to gunzip on the fly; `:size` catches a short write here rather than letting
  it surface later as a corrupt zip or a truncated cache."
  [url dest & {:keys [size wrap] :or {wrap identity}}]
  (t/trace! {:id   :dannet.download/file
             :data {:url (str url) :dest (str dest)}}
    (atomically! dest
      (fn [tmp]
        (with-open [in (wrap (io/input-stream url))]
          (io/copy in tmp))
        (when (and size (not= size (.length ^File tmp)))
          (throw (ex-info (str "Incomplete download of " dest)
                          {:url      (str url)
                           :dest     (str dest)
                           :expected size
                           :actual   (.length ^File tmp)})))))))

(def bootstrap-files
  "The set of DanNet release assets that constitute a bootstrap. Shared by the
  fetch and the missing-file check so the two can't drift apart.

  NB: add \"cor-sem.zip\" when bumping release/from past the release that
  debuts the cor-sem: graph; the 2026-08-21 release bootstrapped from now has
  no such asset, so the graph is built from source by add-cor-sem-graph!
  instead."
  #{"dannet.zip"
    "cor.zip"
    "dds.zip"
    "oewn-extension.zip"
    release/indegrees-filename})

(defn fetch-bootstrap-datasets!
  "Fetch DanNet release assets from GitHub and prepare them for bootstrapping.

     :version - the release, e.g. \"2024-08-09\"; defaults to the latest
     :files   - the assets to fetch; defaults to bootstrap-files
     :dir     - the target dir; defaults to the version-dir of the release

  Throws if a requested file has no matching asset in the release."
  [& {:keys [version files dir]
      :or   {files bootstrap-files}}]
  (let [api           "https://api.github.com/repos/kuhumcst/DanNet/releases"
        url           (if version
                        (str api "/tags/v" version)
                        (str api "/latest"))
        gh-release    (json/read-str (slurp url) :key-fn keyword)
        assets        (:assets gh-release)
        release-name  (or (:tag_name gh-release) (:name gh-release))
        ;; Resolved before the target dir is settled, so an unspecified :version
        ;; still lands in a dir named after whichever release was fetched.
        bootstrap-dir (io/file (or dir (release/version-dir release-name)))]
    (.mkdirs bootstrap-dir)
    (t/event! :dannet.download/release-found
              {:level :info
               :data  {:release release-name
                       :files   (vec files)
                       :dir     (str bootstrap-dir)}})
    (doseq [filename files]
      (if-let [asset (first (filter (comp #{filename} :name) assets))]
        (download-to-file! (:browser_download_url asset)
                           (io/file bootstrap-dir filename)
                           :size (:size asset))
        (throw (ex-info (str "No asset named " filename " in release " release-name)
                        {:filename filename
                         :release  release-name
                         :assets   (map :name assets)}))))
    release-name))

(defn missing-bootstrap-files
  "The bootstrap-files not present as actual files in `dir`."
  [dir]
  (remove #(.exists (io/file dir %)) bootstrap-files))

(defn ensure-bootstrap-datasets!
  "Download whichever bootstrap files are missing from `dir` from the `version`
  release on GitHub."
  [dir version]
  (when-let [missing (seq (missing-bootstrap-files dir))]
    (t/log! {:level :info
             :id    :dannet.download/bootstrap-missing
             :data  {:dir (str dir) :missing (vec missing)}}
            "Fetching missing bootstrap files")
    (fetch-bootstrap-datasets! :version version
                               :files (set missing)
                               :dir dir)))

(def english-dir
  "Directory holding the bootstrap English datasets (OEWN + ILI)."
  "bootstrap/other/english")

(def oewn-version
  "The Open English WordNet edition to bootstrap against."
  "2025")

(def oewn-ttl-path
  (str english-dir "/english-wordnet-" oewn-version ".ttl"))

(def oewn-url
  (str "https://en-word.net/static/english-wordnet-" oewn-version ".ttl.gz"))

(def ili-path
  (str english-dir "/ili.ttl"))

(def ili-url
  "Direct download link for the CILI interlingual index (ili.ttl)."
  "https://raw.githubusercontent.com/globalwordnet/cili/master/ili.ttl")

(defn ensure-english-datasets!
  "Download the OEWN and ILI datasets into english-dir if missing. OEWN ships
  gzipped and is decompressed as it streams, so no .gz is staged on disk."
  []
  (when-not (.exists (io/file oewn-ttl-path))
    (download-to-file! oewn-url oewn-ttl-path :wrap #(GZIPInputStream. %)))
  (when-not (.exists (io/file ili-path))
    (download-to-file! ili-url ili-path)))

(def premon-fn17-path
  "The PreMOn FrameNet 1.7 dump; .tql upstream, but the content is N-Quads,
  so the decompressed file is named .nq for Jena to auto-detect the syntax."
  (str "bootstrap/other/framenet/premon-" release/premon-version "-fn17-noinf.nq"))

(def premon-fn17-url
  (str "https://premon.fbk.eu/files/dataset/" release/premon-version
       "/premon-" release/premon-version "-fn17-noinf.tql.gz"))

(defn ensure-framenet-dataset!
  "Download the PreMOn FrameNet 1.7 dump if missing, like the OEWN gunzipped
  as it streams."
  []
  (when-not (.exists (io/file premon-fn17-path))
    (download-to-file! premon-fn17-url premon-fn17-path
                       :wrap #(GZIPInputStream. %))))

(defn assert-input-dir!
  "Fail before any work if `dir` takes part in the version-dir naming scheme but
  isn't named after the `version` being bootstrapped from. A dir outside
  bootstrap-root is a deliberate override and is left alone."
  [dir version]
  ;; The authoritative check runs during import, but only once the full ttl has
  ;; been parsed, so a mismatch otherwise costs minutes and a partial database.
  (let [dir (io/file dir)]
    (when (and (= (io/file release/bootstrap-root) (.getParentFile dir))
               (not= version (.getName dir)))
      (t/log! {:level :error
               :id    :dannet.bootstrap/input-dir-mismatch
               :data  {:dir (str dir) :expected version}}
              "Input dir isn't named after the release being bootstrapped from")
      (throw (ex-info (str "Input dir " dir " isn't named after release " version)
                      {:dir (str dir) :expected version})))))

(defn assert-datasets-present!
  "Verify the datasets required to bootstrap are present in `dir`, erroring
  toward the refetch flag rather than downloading silently or building an
  incomplete database. This is the normal, non-refetch start path.

  No version is checked: the DanNet release is encoded in `dir` and the OEWN
  and PreMOn editions in their filenames, so all are already covered."
  [dir]
  (let [missing (mapv str (concat (map #(io/file dir %) (missing-bootstrap-files dir))
                                  (remove #(.exists ^File %)
                                          [(io/file oewn-ttl-path)
                                           (io/file ili-path)
                                           (io/file premon-fn17-path)])))]
    (when (seq missing)
      (t/log! {:level :error
               :id    :dannet.bootstrap/datasets-incomplete
               :data  {:missing missing}}
              "Bootstrap datasets incomplete; restart with refetch to download")
      (throw (ex-info (str "Bootstrap datasets incomplete -- restart with refetch "
                           "(--refetch, or restart-refetch in the REPL) to "
                           "download the required versions. " (pr-str missing))
                      {:missing missing})))))

(defn refetch-datasets!
  "Wipe the release-bound datasets in `dir` and re-fetch the required `version`.
  Drives the --refetch / restart-refetch path.

  `dir` is named after the release and holds nothing but its inputs, so it can
  be emptied wholesale without touching another release's bootstrap target."
  [dir version]
  ;; The English datasets are left alone: OEWN editions carry their version in
  ;; the filename and so coexist, while ILI is unversioned (CILI master) and has
  ;; no mismatch to act on -- hence the warning rather than a delete.
  (let [files (.listFiles (io/file dir))
        ili   (io/file ili-path)]
    (t/log! {:level :info
             :id    :dannet.refetch/wipe-bootstrap
             :data  {:dir (str dir) :files (mapv #(.getName ^File %) files)}}
            "Wiping bootstrap files for refetch")
    (run! io/delete-file files)
    (when (.exists ili)
      (t/log! {:level :warn
               :id    :dannet.refetch/ili-retained
               :data  {:path     (str ili)
                       :bytes    (.length ili)
                       :modified (str (Instant/ofEpochMilli (.lastModified ili)))
                       :source   ili-url}}
              "ILI is unversioned (CILI master) and kept as-is on refetch")))
  (ensure-bootstrap-datasets! dir version)
  (ensure-english-datasets!)
  (ensure-framenet-dataset!))

(comment
  (ensure-english-datasets!)                                ; ILI and OEWN
  (ensure-framenet-dataset!)                                ; PreMOn FrameNet 1.7

  (fetch-bootstrap-datasets!)                               ; latest version
  (fetch-bootstrap-datasets! :version "2024-08-09")         ; specific version
  (fetch-bootstrap-datasets! :files #{"dannet.zip"})

  ;; Wipe the release-bound datasets, then re-fetch the expected release.
  (refetch-datasets! (release/version-dir release/from) release/from)
  #_.)
