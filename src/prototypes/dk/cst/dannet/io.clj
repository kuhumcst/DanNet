(ns dk.cst.dannet.io
  (:require [clojure.string :as str])
  (:import [java.io File]))

(defn source-folder
  "Load a `folder` as a source map of file extensions to file paths."
  [^File folder & [path-fn]]
  (let [filenames (.list folder)
        filepaths (map (or path-fn (partial str (.getAbsolutePath folder) "/"))
                       filenames)
        extension (comp second #(str/split % #"\."))]
    (group-by extension filepaths)))
