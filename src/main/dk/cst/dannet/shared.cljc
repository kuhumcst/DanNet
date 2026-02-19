(ns dk.cst.dannet.shared
  "Shared functions for frontend/backend; low-dependency namespace."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.math :as math]
            [reitit.impl :refer [form-decode]]
            [ont-app.vocabulary.core :as voc]
            [taoensso.telemere :as t]
            [dk.cst.dannet.web.section :as section]
            [dk.cst.dannet.web.i18n :as i18n]
            #?(:cljs [reitit.frontend.easy :as rfe])
            #?(:cljs [reitit.frontend.history :as rfh])
            #?(:clj [clojure.core.memoize :as memo])
            #?(:clj [clojure.java.io :as io])
            #?(:cljs [cognitect.transit :as transit])
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

(def default-full-screen
  #?(:clj  false
     :cljs (boolean (get-cookie :full-screen))))

;; Page state used in the single-page app; completely unused server-side.
(defonce state
  (atom {:languages   default-languages
         :search      {:completion {}
                       :s          ""}
         :full-screen default-full-screen
         :section     {section/semantic-title {:display {:selected "radial"}}}
         :details?    nil}))

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

;; https://github.com/pedestal/pedestal/issues/477#issuecomment-256168954
(defn remove-trailing-separator
  "Remove trailing separator (/ or #) from `uri`."
  [uri]
  (let [last-char (last uri)]
    (if (or (= \/ last-char) (= \# last-char))
      (subs uri 0 (dec (count uri)))
      uri)))

#?(:cljs
   (do
     (def transit-read-handlers
       {"lstr"        lstr/read-LangStr
        "rdfdatatype" identity
        "f"           parse-double                          ; BigDecimal
        "datetime"    identity})

     ;; TODO: handle datetime more satisfyingly typewise and in the web UI
     (def reader
       (transit/reader :json {:handlers transit-read-handlers}))

     (defn clear-current-fetch
       "Clear `url` from the ongoing fetch table (done after fetches complete)."
       [url]
       (swap! state update :fetch dissoc url))

     (defn abort-current-fetch
       "Abort an ongoing fetch for `url`."
       [url]
       (when-let [controller (get-in @state [:fetch url])]
         (.abort controller)
         (clear-current-fetch url)))

     (defn abort-stale-fetches
       "Abort all in-flight fetches. Called on navigation to prevent stale data
       from previous pages being processed after the user has moved on."
       []
       (doseq [[url controller] (:fetch @state)]
         (.abort controller))
       (swap! state assoc :fetch {}))

     ;; Currently lambdaisland/fetch silently loses query strings, so the
     ;; `from-query-string` is needed to keep the query string intact.
     ;; The reason that `:transit true` is assoc'd is to circumvent the browser
     ;; caching the transit data instead of an HTML page, which can result in a weird
     ;; situation where clicking the back button and then forward sometimes results
     ;; in transit data being displayed rather than an HTML page.
     (defn api
       "Do a GET request for the resource at `url`, returning the response body."
       [url & [{:keys [query-params method] :or {method :get} :as opts}]]

       ;; Cancel any existing fetches (ignoring nil state, i.e. the first run).
       (when-not (nil? (:fetch @state))
         (abort-current-fetch url))

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
         (-> (fetch/request (normalize-url url) opts*)
             ;; Aborted fetches throw an AbortError. This is expected behaviour
             ;; when looking up search suggestions, so we don't need to litter
             ;; our console with these false positives.
             (.catch #(when-not (= "AbortError" (.-name %))
                        (throw %))))))

     (defn response->url
       [response]
       (-> response meta :lambdaisland.fetch/request (j/get :url)))

     (defn update-cookie!
       "Apply `f` to cookie at key `k`, storing the result in the client state."
       [k f]
       (let [url "/cookies"
             v   (get (swap! state update k f) k)]
         (.then (api url {:method :put
                          :body   {k v}})
                (clear-current-fetch url))
         v))))

(defn setify
  [x]
  (when x
    (if (set? x) x #{x})))

(defn sense-labels*
  "Split a `synset` label into sense labels. Work for both old and new formats."
  [sep label]
  (->> (str/split label sep)
       (into [] (comp
                  (remove empty?)
                  (map str/trim)))))

(def sense-labels
  #?(:clj  (memo/lru sense-labels* :lru/threshold 2000)
     :cljs (memoize sense-labels*)))

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
  (->> (map (comp (partial re-matches sense-label) str) sense-labels)
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

(defn min-max-normalize
  [span low num]
  (/ (- num low) span))

(defn log-inc
  "Increment `n` by log(n)."
  [n]
  (+ n (max 1 (math/log n))))

(defn cloud-normalize
  "Normalize an ordered collection of `synsets` to fit a word cloud.
  
  The synsets should already be sorted by weight (highest first). Creates
  artificial weights by incrementing from 1 using a relative logarithmic
  increment, then normalizes these values into the range 0...1.
  
  For clouds with more than 30 items, applies highlighting to top synsets
  above a threshold. This simulates the effect of outliers by adding a bonus
  constant to weights above the threshold."
  [synsets]
  (let [weights-kvs        (map vector (reverse synsets) (iterate log-inc 1))
        low                (second (first weights-kvs))
        high               (second (last weights-kvs))
        span               (- high low)
        [threshold bonus] (if (> (count synsets) 30)
                            [(- high (math/sqrt span)) (/ span 2)]
                            [high 0])
        min-max-normalize' #(min-max-normalize (+ span bonus) low %)]
    ;; Build both result map and highlight set in single pass using reduce
    (let [[result highlight]
          (reduce (fn [[m hl] [k v]]
                    (if (> v threshold)
                      [(assoc! m k (min-max-normalize' (+ v bonus)))
                       (conj! hl k)]
                      [(assoc! m k (min-max-normalize' v))
                       hl]))
                  [(transient {}) (transient #{})]
                  weights-kvs)]
      (with-meta (persistent! result)
                 {:highlight (persistent! highlight)}))))

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
  #?(:cljs (if (not-empty url)
             (let [history @rfe/history]
               (if replace
                 (.replaceState js/window.history nil "" (rfh/-href history url))
                 (.pushState js/window.history nil "" (rfh/-href history url)))
               (rfh/-on-navigate history url))
             (t/log! {:level :warn
                      :data  {:url      url
                              :from     js/window.location.href
                              :referrer js/document.referrer
                              :stack    (.-stack (js/Error.))}}
                     "navigate-to called with empty URL"))))

(defn label-sortkey-fn*
  "Keyfn for sorting keywords and other content based on a `k->label` mapping.
  Returns vectors so that identical labels are sorted by keywords secondly."
  [{:keys [languages k->label] :as opts}]
  (fn [item]
    (let [k (if (map-entry? item) (first item) item)]
      [(str (i18n/select-label languages (get k->label k)))
       (str item)])))

(def label-sortkey-fn
  #?(:clj  (memo/lru label-sortkey-fn* :lru/threshold 1000)
     :cljs (memoize label-sortkey-fn*)))

(defn sort-by-label-with-keys
  "Sort `coll` by labels and return items with pre-computed sort keys.
  
  Uses the Schwartzian transform pattern to compute each sort key once.
  Returns a sequence of maps with `:item` and `:sort-key` keys."
  [opts coll]
  (let [keyfn (label-sortkey-fn opts)]
    (->> coll
         (map (fn [item] {:item item :sort-key (keyfn item)}))
         (sort-by :sort-key))))

;; NOTE: cannot use fnil as we're limited to assoc! using transients.
(defn vec-conj
  [coll v]
  (if (nil? coll)
    [v]
    (conj coll v)))

(defn top-n-vals
  "Select `n` vals in `m` by picking the first of every val iteratively.
   Round-robins through keys, taking one value from each in turn."
  [n m]
  (let [ks    (vec (keys m))
        total (count ks)]
    (loop [i         0
           remaining n
           source    (transient m)
           result    (transient {})
           exhausted 0]
      (let [k       (nth ks i)
            vs      (get source k)
            v       (if (coll? vs)                          ; one val vs. coll
                      (first vs)
                      vs)
            rest-vs (when (coll? vs)                        ; one val vs. coll
                      (rest vs))]
        (cond
          ;; Exit conditions
          (or (zero? remaining) (= exhausted total))
          (persistent! result)

          ;; Key exhausted -> skip to next
          (not vs)
          (recur (rem (inc i) total) remaining source result exhausted)

          ;; Key has more values -> keep it
          (seq rest-vs)
          (recur (rem (inc i) total)
                 (dec remaining)
                 (assoc! source k rest-vs)
                 (assoc! result k (vec-conj (get result k) v))
                 exhausted)

          ;; Last value for key -> remove key
          :else
          (recur (rem (inc i) total)
                 (dec remaining)
                 (dissoc! source k)
                 (assoc! result k (vec-conj (get result k) v))
                 (inc exhausted)))))))

(defn multi-valued?
  "Return true if `v` is either a (pre-sorted) vector or a set.

  This is used to allow for/signal that collection values can come pre-sorted.
  Ordinarily, multi-valued properties in RDF will always observe set semantics."
  [v]
  (and (coll? v)
       (not (map? v))))

(def narrow-glyphs
  #{\f \i \l \I \j \r \t \1 \. \, \: \; \! \| \' \`})

(def wide-glyphs
  #{\m \w \M \W \æ \Æ \@ \%})

(defn- glyph-width*
  "Estimate the approximate visual width of `s` based on character widths."
  [s]
  (reduce (fn [acc ch]
            (+ acc (cond
                     (narrow-glyphs ch) 0.67
                     (wide-glyphs ch) 1.33
                     :else 1.0)))
          0
          (str s)))

(def glyph-width
  #?(:clj  (memo/lru glyph-width* :lru/threshold 5000)
     :cljs (memoize glyph-width*)))

(defn rdf-datatype?
  "Is `x` an RDF datatype represented as a map?"
  [x]
  (and (map? x) (:value x) (:uri x)))

(defn member-property?
  "Returns true if `x` is an RDF container membership property, e.g. :rdf/_1,
  :rdf/_2 and so on."
  [x]
  (and (keyword? x)
       (= "rdf" (namespace x))
       (str/starts-with? (name x) "_")))

(defn bag->coll
  "Extract member values from an RDF Bag map `m` into a flat sorted collection."
  [{:keys [rdf/type] :as m}]
  (when (or (= :rdf/Bag type)
            (contains? type :rdf/Bag))
    (->> (dissoc m :rdf/type)
         (filter (comp member-property? first))
         (mapcat second)
         sort
         not-empty)))

(defn parse-rdf-term
  "Parses an RDF `term` into [prefix local-name uri] for display/processing."
  [term]
  (when term
    (if (keyword? term)
      [(symbol (namespace term))
       (name term)
       (voc/uri-for term)]
      (let [uri (str/replace term #"<|>" "")]
        [nil uri uri]))))

(def label-keys-full
  "RDF properties checked for labels, preferring full/detailed labels first."
  [:rdfs/label
   :dns/shortLabel
   :dc/title
   :dc11/title
   :foaf/name
   #_:skos/definition
   :ontolex/writtenRep])

(def label-keys-short
  "RDF properties checked for labels, preferring abbreviated labels first."
  [:dns/shortLabel
   :rdfs/label
   :dc/title
   :dc11/title
   :foaf/name
   #_:skos/definition
   :ontolex/writtenRep])

(defn find-label-key
  "Returns the first key from `ks` that exists in `entity`, or nil."
  ([entity]
   (find-label-key entity label-keys-short))
  ([entity ks]
   (loop [[candidate & candidates] ks]
     (if (get entity candidate)
       candidate
       (when candidates
         (recur candidates))))))

(defn get-entity-label
  "Returns the label value from `entity` using the first available property in
  the coll of `ks`."
  [ks entity]
  (when-let [k (find-label-key entity ks)]
    (get entity k)))

(defn ->entity-label-fn
  "Returns a function that extracts labels from entities.
  If `prefer-full?` is true, prefers rdfs:label over dns:shortLabel."
  [prefer-full?]
  (let [label-keys (if prefer-full?
                     label-keys-full
                     label-keys-short)]
    #(get-entity-label label-keys %)))

(def semantic-relation-limit
  "Maximum number of values to display per semantic relation.
  Used for word cloud limits and deferred loading truncation."
  150)

(defn merge-deferred-entity
  "Merge `deferred` entity data into `entity`, concatenating collections.
  Used by the client to combine truncated initial data with deferred remainder."
  [entity deferred]
  (merge-with (fn [old new]
                (if (and (coll? old) (coll? new))
                  (into old new)
                  new))
              entity
              deferred))

(def synset-rel-theme
  "The maximal theme for all in-use synset relations generated via
  `(generate-synset-rels-theme)` in the resources namespace."
  {:dns/crossPoSHypernym   "#e7969c"
   :dns/crossPoSHyponym    "#c49c94"
   :dns/nearAntonym        "#e7ba52"
   :dns/orthogonalHypernym "#387111"
   :dns/orthogonalHyponym  "#666",
   :dns/usedFor            "#fdae6b"
   :dns/usedForObject      "#a55194"
   :wn/agent               "#bd9e39"
   :wn/also                "#9467bd"
   :wn/antonym             "#2ca02c"
   :wn/attribute           "#3182bd"
   :wn/causes              "#ffbb78"
   :wn/co_agent_instrument "#393b79"
   :wn/co_instrument_agent "#dbdb8d"
   :wn/domain_region       "#7b4173"
   :wn/domain_topic        "#019fa1"
   :wn/entails             "#c5b0d5"
   :wn/exemplifies         "#6baed6"
   :wn/has_domain_region   "#ff9896"
   :wn/has_domain_topic    "#df7300"
   :wn/holo_location       "#8ca252"
   :wn/holo_member         "#e7cb94"
   :wn/holo_part           "#d6616b"
   :wn/holo_substance      "#aec7e8"
   :wn/holonym             "#c6dbef"
   :wn/hypernym            "#901a1e"
   :wn/hyponym             "#55f"
   :wn/instance_hypernym   "#c7c7c7"
   :wn/instance_hyponym    "#8c564b"
   :wn/involved_agent      "#b5cf6b"
   :wn/involved_patient    "#de9ed6"
   :wn/involved_result     "#1f77b4"
   :wn/is_caused_by        "#9ecae1"
   :wn/is_entailed_by      "#637939"
   :wn/is_exemplified_by   "#9edae5"
   :wn/mero_location       "#8c6d31"
   :wn/mero_member         "#f7b6d2"
   :wn/mero_part           "#5254a3"
   :wn/mero_substance      "#ce6dbd"
   :wn/meronym             "#9c9ede"
   :wn/other               "#cedb9c"
   :wn/patient             "#e377c2"
   :wn/result              "#98df8a"
   :wn/similar             "#bcbd22"})

(defn lexfile->pos
  [lexfile]
  (cond
    (string? lexfile)
    (when-let [[_ label] (re-matches #"(\w+)\.\w+" lexfile)]
      label)

    ;; We need to account for the odd fact that a few synsets have two lexfiles.
    (coll? lexfile)
    (lexfile->pos (first lexfile))))

(comment
  (lexfile->pos "noun.location")
  (lexfile->pos "adv.all")
  (lexfile->pos ["noun.location" "noun.person"])

  ;; Testing out relative weights
  (take 10 (map double (iterate log-inc 1)))
  (take 100 (map double (iterate log-inc 1)))
  (take 1000 (map double (iterate log-inc 1)))
  #_.)
