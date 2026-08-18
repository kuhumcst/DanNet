(ns dk.cst.dannet.shared
  "Shared functions for frontend/backend; low-dependency namespace."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.math :as math]
            [reitit.impl :refer [form-decode]]
            [ont-app.vocabulary.core :as voc]
            [dk.cst.dannet.web.i18n :as i18n]
            #?(:clj [clojure.core.memoize :as memo])
            #?(:clj [clojure.java.io :as io])
            #?(:cljs [reagent.cookies :as cookie])))

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

(defn setify
  [x]
  (when x
    (if (set? x) x #{x})))

(defn re-quote
  "Escape regex special characters in `s` (a CLJC-safe Pattern/quote)."
  [s]
  (str/replace s #"[.*+?^${}()|\[\]\\]" "\\\\$0"))

(defn ci-pattern
  "Compile pattern string `s` into a case-insensitive, unicode-aware regex."
  [s]
  #?(:clj  (re-pattern (str "(?iu)" s))
     :cljs (js/RegExp. s "iu")))

(defn match-bounds
  "Return [start end] index pairs for every match of `re` in string `s`.
  This is the only part of regex splitting requiring platform-specific code."
  [re s]
  #?(:clj  (let [m (re-matcher re s)]
             (loop [bounds []]
               (if (.find m)
                 (recur (conj bounds [(.start m) (.end m)]))
                 bounds)))
     :cljs (let [flags (cond-> (.-flags re)
                         (not (.-global re)) (str "g"))
                 re'   (js/RegExp. (.-source re) flags)]
             (loop [bounds []]
               (if-let [m (.exec re' s)]
                 (recur (conj bounds [(.-index m)
                                      (+ (.-index m) (count (aget m 0)))]))
                 bounds)))))

(defn split-matches
  "Split `s` into segments of [:text s]/[:match s] according to regex `re`.
  Unlike str/split, the matched segments are retained in the output."
  [re s]
  (let [indices (concat [0] (apply concat (match-bounds re s)) [(count s)])
        spans   (partition 2 1 indices)
        kinds   (cycle [:text :match])]
    (->> (map (fn [kind [start end]]
                [kind (subs s start end)])
              kinds
              spans)
         (filterv (comp seq second)))))

(defn lemma-pattern
  "Regex matching any of the `lemmas` as (the start of) a full word, e.g. the
  lemma 'hund' also matches inflected forms such as 'hunden' or 'hundes'."
  [lemmas]
  (let [alternation (->> (sort-by count > lemmas)           ; longest match wins
                         (map re-quote)
                         (str/join "|"))]
    (ci-pattern (str "(?<!\\p{L})(?:" alternation ")\\p{L}*"))))

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

(defn synset?
  "Return true if `subject` is a synset `entity` in DanNet, OEWN, etc."
  [subject entity]
  (and (keyword? subject)
       (map? entity)
       (= :ontolex/LexicalConcept (:rdf/type entity))))

(defn dn-synset?
  "Return true if `subject` is a DanNet synset specifically."
  [subject entity]
  (and (synset? subject entity)
       (= "dn" (namespace subject))))

(defn word?
  "Return true if `subject` is a word `entity`, incl. multiword expressions."
  [subject entity]
  (and (keyword? subject)
       (map? entity)
       (boolean (some #{:ontolex/Word :ontolex/MultiwordExpression}
                      (setify (:rdf/type entity))))))

(defn dn-word?
  "Return true if `subject` is a DanNet word specifically."
  [subject entity]
  (and (word? subject entity)
       (= "dn" (namespace subject))))

(defn with-prefix
  "Return predicate accepting keywords with `prefix` (`except` set of keywords)."
  [prefix & {:keys [except]}]
  (fn [[k v]]
    (when (keyword? k)
      (and (not (except k))
           (= (namespace k) (name prefix))))))

(def semantic-rels?
  (some-fn (with-prefix 'wn :except #{:wn/partOfSpeech
                                      :wn/definition
                                      :wn/ili
                                      :wn/eq_synonym
                                      :wn/lexfile
                                      :wn/example})
           (comp #{:dns/usedFor
                   :dns/usedForObject
                   :dns/nearAntonym
                   :dns/crossPoSHyponym
                   :dns/crossPoSHypernym
                   :dns/orthogonalHyponym
                   :dns/orthogonalHypernym} first)))

(def omitted
  "…")

(defn entry-sort-key
  "Sort key for a sense label `s` based on its DSL entry ID.

  Lower sense numbers rank first (parsed numerically, so §2 < §12). The first
  subentry outranks the bare sense number (§1a > §1), which outranks the later
  subentries (§1b, §1c, …). The homograph number breaks any remaining ties
  (1, then no homograph, then 2, 3, …). So e.g. 1§1 > §1 > 2§1 and §1a > §1.

  Labels with no parsable entry ID (e.g. proper nouns) sort last."
  [s]
  (let [[_ _ _ sub] (re-matches sense-label (str s))
        [_ homograph sense suffix] (some->> sub (re-matches #"(\d+)?§(\d+)(.*)"))
        h (some-> homograph parse-long)
        n (some-> sense parse-long)]
    (if n
      [n
       (case suffix
         "a" "0"                                            ; first subentry
         ""  "1"                                            ; bare sense number
         (str "2" suffix))                                  ; b, c, … + uncertain
       (cond
         (= h 1)  0                                         ; primary homograph
         (nil? h) 1                                         ; no homograph number
         :else    h)]                                       ; secondary (2, 3, …)
      [##Inf "" 0])))

(defn canonical*
  "Implementation for `canonical`. Use that function instead."
  ([sense-labels]
   (canonical* nil sense-labels))
  ([tiebreak sense-labels]
   (let [keyfn (if tiebreak (juxt entry-sort-key tiebreak) entry-sort-key)
         word  (fn [s] (or (second (re-matches sense-label (str s)))
                           (str s)))]
     (->> (remove #{omitted} sense-labels)
          (sort-by keyfn)
          (reduce (fn [[seen out :as acc] s]
                    (let [w (word s)]
                      (if (contains? seen w)
                        acc
                        [(conj seen w) (conj out s)])))
                  [#{} []])
          (second)
          (into [] (take 2))))))

;; Memoization unbounded in CLJS since core.memoize is CLJ-only!
(def canonical
  "Return the top (at most 2) canonical `sense-labels`, at most one per word,
  using the DSL entry IDs as a heuristic. Optionally takes a `tiebreak` keyfn
  as its first argument.

  The sense of canonical being applied here is: 'reduced to the simplest and
  most significant form possible without loss of generality'. The maximum of 2
  keeps labels from taking over the UI.

  A `tiebreak` keyfn refines the order among labels with equal entry-ID keys;
  without one, ties keep their input order (sort-by is stable), so pre-ranked
  labels such as stored dns:shortLabel values pass through in their stored
  order.

  The \"…\" truncation marker is ignored, as it is never a sense label."
  #?(:clj  (memo/lu canonical* :lu/threshold 1000)
     :cljs (memoize canonical*)))

(defn polysemy-tiebreak
  "A `canonical` tiebreak keyfn from `polysemy`, a map of sense label to the
  sense count of its word.

  A commoner word -- more senses -- ranks first; the label itself breaks the
  remaining ties."
  [polysemy]
  (fn [s] [(- (get polysemy (str s) 0)) (str s)]))

(defn short-label
  "Return an abridged version of a synset `label` keeping only the canonical
  sense labels followed by the \"…\" marker, or nil when nothing is omitted.

  An optional `tiebreak` keyfn is passed on to `canonical`."
  ([label]
   (short-label nil label))
  ([tiebreak label]
   (let [labels (sense-labels synset-sep (str label))
         kept   (canonical tiebreak labels)]
     (when (< (count kept) (count labels))
       (str "{" (str/join "; " kept) "; " omitted "}")))))

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

(defn label-sortkey-fn*
  "Keyfn for sorting keywords and other content based on a `k->label` mapping.
  Returns vectors so that identical labels are sorted by keywords secondly."
  [{:keys [languages k->label] :as opts}]
  (fn [item]
    (let [k (if (map-entry? item) (first item) item)]
      [(str (i18n/select-label languages (get k->label k)))
       (str k)])))

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

(defn unwrap
  "Unwrap a single-element coll, returning the `v` as-is if not a collection."
  [v]
  (cond-> v (coll? v) first))

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
         (mapcat (comp setify second))
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
  "Return a function that extracts labels from entities based on `detail-level`.

    :basic  - returns nil (no label enrichment)
    :normal - prefers dns:shortLabel over rdfs:label
    :high   - prefers rdfs:label over dns:shortLabel"
  [detail-level]
  (case detail-level
    :basic (constantly nil)
    ;; NOTE: dns:shortLabel values are also inferred as rdfs:label values
    ;; (via skos:altLabel), so the short values must be excluded from the
    ;; label set to actually surface the full/detailed label.
    :high (fn [entity]
            (let [label (get-entity-label label-keys-full entity)
                  short (setify (:dns/shortLabel entity))]
              (if-let [full (and (seq short)
                                 (not-empty
                                   (into #{} (remove short) (setify label))))]
                (if (= 1 (count full))
                  (first full)
                  full)
                label)))
    (let [label-keys label-keys-short]
      #(get-entity-label label-keys %))))

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

(def pos-abbr-da
  {"noun" "sb." "adj" "adj." "adv" "adv." "verb" "vb."})

(def pos-abbr-en
  {"noun" "n." "adj" "adj." "adv" "adv." "verb" "v."})

(defn rdf=
  "An equals which also supports RDF open-ended semantics, i.e. `v` can  also be
  a set or a vector containing `x`."
  [v x]
  (cond
    (set? v) (get v x)
    (coll? v) (get (set v) x)
    :else (= v x)))

(defn text->slug
  "Create a URL-safe slug from `text`, keeping only English alphanumerics
  separated by single dashes. Danish characters are transliterated first.

  NOTE: technically, HTML5 allows for most Unicode characters. However, the
        subsequent URL-encoding effort really isn't worth it."
  [text]
  (when (string? text)
    (-> text
        (str/trim)
        (str/lower-case)
        (str/replace "æ" "ae")
        (str/replace "ø" "oe")
        (str/replace "å" "aa")
        (str/replace #"[^a-z0-9]+" "-")
        (str/replace #"^-|-$" ""))))

;; See also: https://mathiasbynens.be/notes/html5-id-class
(defn lstr-slug
  "Turn a coll of `lstrs` into a slug that is HTML5 id compatible."
  [lstrs]
  (some-> (i18n/select-label [nil "en" "da"] lstrs)
          (str)
          (str/lower-case)
          (str/replace #"\s+" "-")))

(comment
  (lexfile->pos "noun.location")
  (lexfile->pos "adv.all")
  (lexfile->pos ["noun.location" "noun.person"])

  ;; Testing out relative weights
  (take 10 (map double (iterate log-inc 1)))
  (take 100 (map double (iterate log-inc 1)))
  (take 1000 (map double (iterate log-inc 1)))
  #_.)
