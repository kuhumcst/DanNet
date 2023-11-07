(ns dk.cst.dannet.shared
  "Shared functions for frontend/backend; low-dependency namespace."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.math :as math]
            [dk.cst.dannet.web.section :as section]
            [reitit.impl :refer [form-decode]]
            [dk.cst.dannet.web.i18n :as i18n]
            #?(:cljs [reitit.frontend.easy :as rfe])
            #?(:cljs [reitit.frontend.history :as rfh])
            #?(:clj [clojure.core.memoize :as memo])
            #?(:clj [clojure.java.io :as io])
            #?(:cljs [clojure.string :as str])
            #?(:cljs [cognitect.transit :as t])
            #?(:cljs [reagent.cookies :as cookie])
            #?(:cljs [lambdaisland.fetch :as fetch])
            #?(:cljs [lambdaisland.uri :as uri])
            #?(:cljs [ont-app.vocabulary.lstr :as lstr])
            #?(:cljs [applied-science.js-interop :as j])))

#?(:clj
   (def main-js
     "When making a release, the filename will be appended with a hash;
     that is not the case when running the regular shadow-cljs watch process.

     Relies on the :module-hash-names being set to true in shadow-cljs.edn."
     (if-let [url (io/resource "public/js/compiled/manifest.edn")]
       (-> url slurp edn/read-string first :output-name)
       "main.js")))

(def development?
  "Source of truth for whether this is a development build or not. "
  #?(:clj  (= main-js "main.js")
     :cljs (when (exists? js/inDevelopmentEnvironment)
             js/inDevelopmentEnvironment)))

(def theme
  ["#901a1e"
   "#55f"
   "#019fa1"
   "#df7300"
   "#387111"
   "#666"])

(defn page-href
  [s]
  (str "/dannet/page/" s))

;; NOTE: cookies should be set using the /cookies endpoint! This is the only way
;; to get long-term cookie storage in e.g. Safari using JavaScript.
(defn get-cookie
  "Cross-compatible way to get cookie `k` (from the `request` on backend)."
  #?(:clj
     ([request k]
      (try
        (some-> request
                :cookies
                (get (name k))
                :value
                (edn/read-string))
        (catch Exception e nil)))
     :cljs
     ([k]
      ;; Reitit properly decodes the form values from Ring Cookie
      ;; (the native JS functions leave a few undesired chars around).
      (some-> (cookie/get-raw k)
              (form-decode)
              (edn/read-string)))))

(def default-languages
  #?(:clj  nil
     :cljs (or
             (get-cookie :languages)
             (if (exists? js/negotiatedLanguages)
               (edn/read-string js/negotiatedLanguages)
               ["en" nil "da"]))))

(def ui
  "UI descriptions which shouldn't be configurable by the client."
  {:section {section/semantic-title
             {:display {:options {"table"  ["tabel"
                                            "table"]
                                  "radial" ["diagram"
                                            "diagram"]}}}}})

;; Page state used in the single-page app; completely unused server-side.
(defonce state
  (atom {:languages default-languages
         :search    {:completion {}
                     :s          ""}
         :section   {section/semantic-title {:display {:selected "table"}}}
         :details?  nil}))

;; Temporary store for special behaviour after navigating to a new page.
(defonce post-navigate
  (atom nil))

(def windows?
  #?(:cljs (and (exists? js/navigator.appVersion)
                (str/includes? js/navigator.appVersion "Windows"))))

(defn normalize-url
  "Normalize a `path` to work in both production and development contexts.

  When accessing using Windows in dev, the OS is assumed to be virtualised and
  localhost:3456 of the macOS host to be available at mac:3456 instead."
  [path]
  (if development?
    (if windows?
      (str "http://mac:3456" path)
      (str "http://localhost:3456" path))
    path))

(defn search-string
  "Normalize search string `s`."
  [s]
  (some-> s str str/trim str/lower-case))

#?(:cljs
   (do
     (def transit-read-handlers
       {"lstr"        lstr/read-LangStr
        "rdfdatatype" identity
        "f"           parse-double                          ; BigDecimal
        "datetime"    identity})

     ;; TODO: handle datetime more satisfyingly typewise and in the web UI
     (def reader
       (t/reader :json {:handlers transit-read-handlers}))

     (defn clear-fetch
       "Clear a `url` from the ongoing fetch table (done after fetches)."
       [url]
       (swap! state update :fetch dissoc url))

     (defn abort-fetch
       "Abort an ongoing fetch for `url`."
       [url]
       (when-let [controller (get-in @state [:fetch url])]
         (.abort controller)
         (clear-fetch url)))

     ;; Currently lambdaisland/fetch silently loses query strings, so the
     ;; `from-query-string` is needed to keep the query string intact.
     ;; The reason that `:transit true` is assoc'd is to circumvent the browser
     ;; caching the transit data instead of an HTML page, which can result in a weird
     ;; situation where clicking the back button and then forward sometimes results
     ;; in transit data being displayed rather than an HTML page.
     (defn api
       "Do a GET request for the resource at `url`, returning the response body."
       [url & [{:keys [query-params method] :or {method :get} :as opts}]]
       (abort-fetch url)                                    ; cancel existing
       (let [string-params (uri/query-string->map (:query (uri/uri url)))
             query-params' (assoc (merge string-params query-params)
                             :transit true)
             controller    (new js/AbortController)
             signal        (.-signal controller)
             opts*         (merge {:method              method
                                   :transit-json-reader reader
                                   :signal              signal}
                                  (assoc opts
                                    :query-params query-params'))]
         (swap! state assoc-in [:fetch url] controller)
         (fetch/request (normalize-url url) opts*)))

     (defn response->url
       [response]
       (-> response meta :lambdaisland.fetch/request (j/get :url)))))

(defn setify
  [x]
  (when x
    (if (set? x) x #{x})))

(defn sense-labels
  "Split a `synset` label into sense labels. Work for both old and new formats."
  [sep label]
  (->> (str/split label sep)
       (into [] (comp
                  (remove empty?)
                  (map str/trim)))))

(def sense-label
  "On matches returns the vector: [s word rest-of-s sub mwe]."
  #"([^_<>]+)(_((?:§|\d|\()[^_ ]+)( .+)?)?")

(def synset-sep
  #"\{|;|\}")

(def omitted
  "…")

(defn- entry-sort
  "Divide `sense-labels` into partitions of [s sub] according to DSL entry IDs."
  [sense-labels]
  (->> (map (partial re-matches sense-label) sense-labels)
       (map (fn [[s _ _ sub _]]
              (cond
                (nil? sub)                                  ; uncertain = keep
                [s "1§1"]

                (and sub (str/starts-with? sub "§"))        ; normalisation
                [s (str "0" sub)]

                :else
                [s sub])))
       (sort-by second)
       (partition-by second)))

(defn canonical
  "Return only canonical `sense-labels` using the DSL entry IDs as a heuristic.

  The sense of canonical being applied here is: 'reduced to the simplest and
  most significant form possible without loss of generality'.

  Input labels are sorted into partitions with the top partition returned.
  In cases where adding the second partition puts the total count at n<=3,
  this second partition is also included."
  [sense-labels]
  (if (= 1 (count sense-labels))
    sense-labels
    (let [[first-partition second-partition :as parts] (entry-sort sense-labels)
          n (+ (count first-partition) (count second-partition))]
      (mapv first (if (<= n 3)
                    (concat first-partition second-partition)
                    first-partition)))))

;; Memoization unbounded in CLJS since core.memoize is CLJ-only!
#?(:clj  (alter-var-root #'canonical #(memo/lu % :lu/threshold 1000))
   :cljs (def canonical (memoize canonical)))

(defn with-omitted
  [sense-labels canonical-labels]
  (if (= (count sense-labels)
         (count canonical-labels))
    sense-labels
    (concat canonical-labels [omitted])))

(defn freq-limit
  "Limit to top `n` of `strs` by frequency according to `freqs` and also
  sort alphabetically in cases where two frequencies are identical."
  [n freqs strs]
  (take n (reverse (sort-by (juxt freqs str) strs))))

(defn top-n-senses
  "Return the top `n` of `sense-labels` based on `sense-label->freq` mapping."
  [n sense-label->freq sense-labels]
  (if (> (count sense-labels) n)
    (freq-limit n sense-label->freq sense-labels)
    sense-labels))

(defn abridged-labels
  "Get the frequent, canonical `sense-labels` based on `sense-label->freq`."
  [sense-label->freq sense-labels]
  (->> (canonical sense-labels)
       (top-n-senses 2 sense-label->freq)
       (with-omitted sense-labels)))

(defn min-max-normalize
  [span low num]
  (/ (- num low) span))

(defn log-inc
  "Increment `n` by log(n)."
  [n]
  (+ n (max 1 (math/log n))))

(defn cloud-normalize
  "Normalize a map of `weights` to fit a word cloud. The output is meant to
  display well across a wide range of differently sized word clouds.

  The actual weights are *ONLY* used for sorting! New, artificial weights are
  created by incrementing from 1 using a relative logarithmic increment and then
  fitting these values into the range 0...1.

  Furthermore, an artificial highlight is used for the values which lie above
  a certain threshold. This highlight is applied as bonus constant applied to
  the weights above the threshold. This simulates the effect of outliers."
  [weights]
  ;; Note that in this implementation, the incrementing sizes are randomly
  ;; assigned to synsets of the same weight. I did experiment with grouping
  ;; by weight first, assigning the same size to synsets of the same weight
  ;; before incrementing the size, but this creates much worse clouds,
  ;; despite being closer to the source data. ~sg
  (let [artificial-weights (->> (sort-by second weights)
                                (map (fn [n [k _]]
                                       [k n])
                                     (iterate log-inc 1)))
        low                (second (first artificial-weights))
        high               (second (last artificial-weights))
        span               (- high low)

        ;; Highlight threshold & bonus are only used for bigger clouds.
        [threshold bonus] (if (> (count weights) 30)
                            [(- high (math/sqrt span))
                             (/ span 2)]
                            [high 0])
        min-max-normalize' #(min-max-normalize (+ span bonus) low %)
        highlight          (atom #{})]
    (with-meta
      (into {} (for [[k v] artificial-weights]
                 [k (min-max-normalize' (if (> v threshold)
                                          (do
                                            (swap! highlight conj k)
                                            (+ v bonus))
                                          v))]))
      {:highlight @highlight})))

(defn x-header
  "Get the custom `header` in the HTTP `headers`.

  See also: dk.cst.dannet.web.resources/x-headers"
  [headers header]
  ;; Interestingly (hahaha) fetch seems to lower-case all keys in the headers.
  (get headers (str "x-" (str/lower-case (name header)))))

(defn navigate-to
  "Navigate to internal `url` using reitit.

  Optionally, specify whether to `replace` the state in history."
  [url & [replace]]
  #?(:cljs (let [history @rfe/history]
             (if replace
               (.replaceState js/window.history nil "" (rfh/-href history url))
               (.pushState js/window.history nil "" (rfh/-href history url)))
             (rfh/-on-navigate history url))))

(defn label-sortkey-fn
  "Keyfn for sorting keywords and other content based on a `k->label` mapping.
  Returns vectors so that identical labels are sorted by keywords secondly."
  [{:keys [languages k->label] :as opts}]
  (fn [item]
    (let [k (if (map-entry? item) (first item) item)]
      [(str (i18n/select-label languages (get k->label k)))
       (str item)])))

(defn weight-sort-fn
  [weights]
  (fn [x]
    (sort-by #(get weights % 0) > (setify x))))

(defn vec-conj
  [coll v]
  (if (nil? coll)
    [v]
    (conj coll v)))

(defn top-n-vals
  "Select `n` vals in `m` by picking the first of every vals iteratively."
  [n m]
  (let [ks (keys m)]
    ;; The m should already be sorted by key alphabetically at this point.
    ;; TODO: don't use cycle, a bit inefficient since we check many empty rels
    (loop [[rel & rels] (cycle ks)
           i       n
           entity' m
           ret     {}]
      ;; We want to make sure to quit when we run out of data.
      (if (or (zero? i) (empty? entity'))
        ret
        (if-let [synset (first (get entity' rel))]
          (recur rels (dec i)
                 (update entity' rel rest)
                 (update ret rel vec-conj synset))
          (recur rels i
                 (dissoc entity' rel)
                 ret))))))

(def synset-rel-theme
  "The maximal theme for all in-use synset relations generated via
  `(generate-synset-rels-theme)` in the resources namespace."
  {:wn/similar             "#bcbd22",
   :dns/usedForObject      "#a55194",
   :wn/is_caused_by        "#c6dbef",
   :dns/eqSimilar          "#9ecae1",
   :wn/involved_agent      "#cedb9c",
   :wn/eq_synonym          "#7b4173",
   :wn/co_instrument_agent "#9edae5",
   :wn/co_agent_instrument "#5254a3",
   :dns/eqHyponym          "#dbdb8d",
   :wn/agent               "#bd9e39",
   :dns/nearAntonym        "#e7ba52",
   :wn/involved_patient    "#de9ed6",
   :wn/involved_result     "#1f77b4",
   :wn/has_domain_topic    "#df7300",
   :dns/orthogonalHypernym "#387111",
   :wn/hypernym            "#901a1e",
   :wn/attribute           "#3182bd",
   :wn/holo_member         "#e7cb94",
   :dns/usedFor            "#fdd0a2",
   :wn/domain_region       "#e7969c",
   :wn/mero_part           "#9c9ede",
   :wn/instance_hypernym   "#c7c7c7",
   :wn/mero_substance      "#ce6dbd",
   :wn/holonym             "#fdae6b",
   :wn/holo_substance      "#aec7e8",
   :wn/is_exemplified_by   "#393b79",
   :wn/domain_topic        "#019fa1",
   :wn/patient             "#e377c2",
   :wn/instance_hyponym    "#c49c94",
   :wn/hyponym             "#55f",
   :dns/orthogonalHyponym  "#666",
   :wn/causes              "#ffbb78",
   :wn/meronym             "#637939",
   :dns/eqHypernym         "#8c564b",
   :wn/is_entailed_by      "#8ca252",
   :wn/mero_location       "#8c6d31",
   :wn/also                "#9467bd",
   :wn/antonym             "#2ca02c",
   :wn/result              "#98df8a",
   :wn/entails             "#c5b0d5",
   :wn/exemplifies         "#6baed6",
   :wn/has_domain_region   "#ff9896",
   :wn/holo_part           "#d6616b",
   :wn/holo_location       "#b5cf6b",
   :wn/mero_member         "#f7b6d2"})

(defn synset-uri
  [id]
  (keyword "dn" (str "synset-" id)))

(defn word-uri
  [id]
  (keyword "dn" (str "word-" id)))

(defn sense-uri
  [id]
  (keyword "dn" (str "sense-" id)))

;; I am not allowed to start the identifier/local name with a dot in QNames.
;; Similarly, I cannot begin the name part of a keyword with a number.
;; For this reason, I need to include the "COR." part in the local name too.
;; NOTE: QName an be validated using http://sparql.org/query-validator.html
(defn cor-uri
  [& parts]
  (keyword "cor" (str "COR." (str/join "." (remove nil? parts)))))

(comment
  ;; Testing out relative weights
  (take 10 (map double (iterate log-inc 1)))
  (take 100 (map double (iterate log-inc 1)))
  (take 1000 (map double (iterate log-inc 1)))

  (sort (vals (normalize {:10 0 :8 0 :6 0 :4 0 :2 0 :0 0})))

  (canonical ["legemsdel_§1" "kropsdel"])                   ; identical
  (canonical ["flab_§1" "flab_§1a" "gab_2§1" "gab_2§1a"
              "kværn_§3" "mule_1§1a" "mund_§1"])            ; ["flab_§1" "mund_§1"]
  #_.)
