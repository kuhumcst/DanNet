(ns dk.cst.dannet.web.snapshot
  "Snapshots of the pages served by the dev service, for checking UI changes.

  Each path in `paths` is fetched as HTML and as Transit, in Danish and
  English, and written to a directory. Running `diff -r` on two snapshot
  directories shows what a change did to the output."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [cognitect.transit :as t]
            [dk.cst.dannet.web.sparql :as sparql])
  (:import [com.cognitect.transit DefaultReadHandler]
           [java.net HttpURLConnection URL URLEncoder]))

(defn- sparql-path
  [query]
  (str "/dannet/sparql?query=" (URLEncoder/encode ^String query "UTF-8")))

(def paths
  "Pages covering the page types and the special cases of the UI."
  ["/dannet/page/frontpage"
   "/dannet/page/sparql"
   "/dannet/page/nonexistent"
   "/dannet/data/synset-52"
   "/dannet/data/synset-52?deferred=true"
   "/dannet/data/synset-8369"
   "/dannet/data/synset-8369?deferred=true"
   "/dannet/data/synset-60920"
   "/dannet/data/sense-21062734"
   "/dannet/data/word-11024957"
   "/dannet/data/synset-999999999"
   "/dannet/external/en/oewn-07644479-n"
   "/dannet/external/cor/COR.EXT.137785"
   "/framenet/Having_or_lacking_access"
   "/framenet/Intentionally_affect@Time"
   "/framenet/Time_semType"
   "/dannet/types/Animal-Group-Object"
   "/dannet/patterns/Activity-Domain"
   "/dannet/external/ontolex/LexicalConcept"
   "/dannet/external/wn/hypernym"
   "/dannet/metadata"
   "/dannet/relations"
   "/dannet/search?lemma=hund"
   "/dannet/search?lemma=bank"
   "/dannet/search?lemma=dn:synset-52"
   "/dannet/sparql"
   (sparql-path "SELECT ?p ?o WHERE { dn:synset-52 ?p ?o }")
   (sparql-path "SELECT ?p ?o WHERE { dn:synset-52 ?p ?o")])

(def media-types
  {"html"    "text/html"
   "transit" "application/transit+json"})

(defn- with-lang
  [path lang]
  (str path (if (str/includes? path "?") "&" "?") "lang=" lang))

(defn fetch
  "Fetch `path` from the dev service as `media-type` in `lang`.

  Redirects are not followed, so that they show up in the snapshot."
  [path media-type lang]
  (let [url  (URL. (str "http://localhost:3456" (with-lang path lang)))
        conn (doto ^HttpURLConnection (.openConnection url)
               (.setInstanceFollowRedirects false)
               (.setRequestProperty "Accept" media-type))
        code (.getResponseCode conn)]
    {:status  code
     :headers (->> (.getHeaderFields conn)
                   (keep (fn [[k vs]]
                           (when (and k (or (str/starts-with? k "X-")
                                            (#{"Content-Type" "Location"} k)))
                             [k (str/join ", " vs)])))
                   (into (sorted-map)))
     :body    (some-> (if (>= code 400)
                        (.getErrorStream conn)
                        (.getInputStream conn))
                      (slurp))}))

(defn- sorted
  "Sort the maps and sets in `x` so that printing it is stable."
  [x]
  (let [by-str #(compare (str %1) (str %2))]
    (walk/postwalk
      (fn [x]
        (cond
          (map? x) (into (sorted-map-by by-str) x)
          (set? x) (into (sorted-set-by by-str) x)
          :else x))
      x)))

(defn transit->edn
  "Read the Transit string `s`, keeping tagged values as plain data."
  [s]
  (with-open [in (io/input-stream (.getBytes ^String s "UTF-8"))]
    (t/read (t/reader in :json
                      {:default-handler (reify DefaultReadHandler
                                          (fromRep [_ tag rep]
                                            {:tag tag :rep rep}))
                       :handlers        {"lstr"      (t/read-handler
                                                       #(str "#lstr " %))
                                         "with-meta" (t/read-handler
                                                       (fn [[x m]]
                                                         {:with-meta x
                                                          :meta      m}))}}))))

(defn- format-body
  [media-type body]
  (cond
    (str/blank? body)
    ""

    (= media-type "text/html")
    (-> body
        (str/replace #"\?hash=-?\d+" "?hash=")
        (str/replace "><" ">\n<"))

    :else
    (with-out-str (pprint/pprint (sorted (transit->edn body))))))

(defn- file-name
  [path ext lang]
  (str (str/replace (subs path 1) #"[^A-Za-z0-9_.@-]+" "_") "." lang "." ext))

(defn snapshot!
  "Write a snapshot of every path in `paths` to the directory `dir`.

  The SPARQL cache is emptied first, since its note on cached results would
  otherwise differ between runs."
  [dir]
  (sparql/reset-cache!)
  (doseq [path     paths
          [ext mt] media-types
          lang     ["da" "en"]
          :let [{:keys [status headers body]} (fetch path mt lang)
                f (io/file dir (file-name path ext lang))]]
    (io/make-parents f)
    (spit f (str "status: " status "\n"
                 (str/join (for [[k v] headers] (str k ": " v "\n")))
                 "\n"
                 (format-body mt body))))
  dir)

(comment
  (snapshot! "/tmp/ui-snapshots/before")
  (:headers (fetch "/dannet/data/synset-52" "application/transit+json" "en"))
  #_.)
