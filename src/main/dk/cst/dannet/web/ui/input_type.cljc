(ns dk.cst.dannet.web.ui.input-type
  "The input types of edit mode.

  An input type is a map that says how a kind of value is entered:

  - :parse-fn, a function of the typed text and the value that the new one
    replaces or resembles, to a value, a promise of one, or nil when the
    text isn't a value;
  - :format-fn, a function of a value to the text that the input shows;
  - :options, the values to choose between, when there are only a few;
  - :search, a search for a value in a dialog.

  A search is a map too:

  - :results-fn, a function of the typed text to results or a promise of
    them, so a search can ask the server or filter what the browser has;
  - :value-fn, a function of a result to its value;
  - :label-fn, a function of a result to the label of its value;
  - :hiccup-fn, a function of a result and the opts to its content;
  - :title and :placeholder, the texts of the dialog as maps of :da and :en.

  The input and the dialog of ui.edit only know these maps, so a new input
  type or a new search needs no change to either."
  (:require [clojure.string :as str]
            [dk.cst.theophrastus.term :as term]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.web.app :as app]
            [dk.cst.dannet.web.i18n :as i18n]))

(def site-root
  "The scheme and domain of the DanNet site, e.g. \"https://wordnet.dk\"."
  (re-find #"^https?://[^/]+" prefix/dannet-root))

(def local-page
  "The address of a page of a local copy of the site, with its path as the
  group."
  #"https?://localhost(?::\d+)?(/.*)")

(def namespaces
  "The prefix and IRI of every namespace that DanNet knows, the longest IRI
  first, so that an IRI is matched to its most specific namespace."
  (->> prefix/schemas
       (keep (fn [[prefix {:keys [uri]}]]
               (when uri
                 [prefix uri])))
       (sort-by (comp count second) >)))

(defn ->resource
  "The resource that the text `s` names, as a keyword, or nil when it names
  none: a prefixed name such as dn:synset-3047, an IRI with or without angle
  brackets, or the address of a page of the site or of a local copy of it."
  [s]
  (let [s   (-> (str/trim s)
                (str/replace #"^<|>$" "")
                (str/replace #"\?.*$" ""))
        iri (if-let [[_ path] (re-matches local-page s)]
              (str site-root path)
              s)
        [prefix uri] (first (filter (fn [[_ uri]]
                                      (and (str/starts-with? iri uri)
                                           (not= iri uri)))
                                    namespaces))]
    (if prefix
      (keyword (str prefix) (subs iri (count uri)))
      (when-let [[_ prefix local-name] (re-matches #"([A-Za-z][\w-]*):(\S+)" s)]
        (when (contains? prefix/schemas (symbol prefix))
          (keyword prefix local-name))))))

(defn remember-label!
  "Keep `label` as the label of the resource `k` in the session, so that a
  page can show a resource that it didn't come with."
  [k label]
  (when label
    (swap! app/session assoc-in [:labels k] label)))

(defn- fetch-data
  "A promise of what the server has under the key `k` of its :data at `url`
  with `query-params`, or of nil when it has none."
  [url k & [query-params]]
  #?(:cljs (.then (app/fetch! url {:query-params query-params})
                  (fn [response]
                    (app/clear-current-fetch url)
                    (when (= 200 (:status response))
                      (get-in response [:body :data k]))))))

(def text
  "A text, which keeps the language of the text that it replaces."
  {:format-fn str
   :parse-fn  (fn [s v]
                (when-let [s (not-empty (str/trim s))]
                  (if-let [lang (i18n/lang v)]
                    (term/->LangString s lang)
                    s)))})

(def resource
  "A resource, typed as its ID. It has to exist, and its page gives the label
  that it is shown with."
  {:format-fn prefix/kw->qname
   :parse-fn  (fn [s _]
                (when-let [k (->resource s)]
                  #?(:clj  k
                     :cljs (.then (fetch-data (prefix/resolve-href k) :entity)
                                  (fn [entity]
                                    (when entity
                                      (remember-label! k (:rdfs/label entity))
                                      k))))))})

(def synset
  "A synset, typed as its ID or found by the start of one of its words,
  which the server looks up."
  (assoc resource
    :search {:title       {:da "Find et synset" :en "Find a synset"}
             :placeholder {:da "skriv et ord..." :en "write a word..."}
             :results-fn  (fn [s]
                            (when-let [lemma (not-empty (str/trim s))]
                              (fetch-data "/dannet/edit/synsets" :synsets
                                          {:lemma lemma})))
             :value-fn    key
             ;; the labels of a result are in the metadata of its entity
             :label-fn    (fn [[k entity]]
                            (get (:k->label (meta entity)) k))
             :hiccup-fn   (fn [[k entity] {:keys [languages] :as opts}]
                            (let [{:keys [k->label]} (meta entity)
                                  label #(str (i18n/select-label languages %))
                                  type  (:dns/ontologicalType entity)]
                              [:<>
                               [:strong (label (get k->label k))]
                               [:span (label (:skos/definition entity))]
                               (when type
                                 [:small (label (get k->label type))])]))}))

;; The types are fetched once and then searched in the browser. A fetch that
;; gives none, e.g. one that a change of page aborts, is made again.
(defonce ^{:doc "Every ontological type with its label, once fetched."}
  ontological-types
  (atom nil))

(defn- matching-types
  "A promise of the ontological types whose label contains the text `s`, in
  any case."
  [s]
  #?(:cljs (let [s      (str/lower-case (str/trim s))
                 match? #(-> (str (:rdfs/label %))
                             (str/lower-case)
                             (str/includes? s))]
             (.then (if-let [types @ontological-types]
                      (js/Promise.resolve types)
                      (.then (fetch-data "/dannet/edit/ontological-types"
                                         :ontological-types)
                             #(reset! ontological-types %)))
                    #(filter match? %)))))

(def ontological-type
  "An ontological type, typed as its ID or found among the existing types,
  which the browser has."
  (assoc resource
    :search {:title       {:da "Find en ontologisk type"
                           :en "Find an ontological type"}
             :placeholder {:da "skriv et begreb..." :en "write a concept..."}
             :results-fn  matching-types
             :value-fn    :rdf/about
             :label-fn    :rdfs/label
             :hiccup-fn   (fn [{:keys [rdfs/label]} _]
                            [:strong (str label)])}))

(def part-of-speech
  "A part of speech, which is one of three."
  {:options [:wn/noun :wn/verb :wn/adjective]})

(def input-types
  "The input type of each property that has one of its own."
  {:wn/partOfSpeech     part-of-speech
   :dns/ontologicalType ontological-type})

(defn- synset?
  "Is `v` the ID of a DanNet synset?"
  [v]
  (and (keyword? v)
       (= "dn" (namespace v))
       (str/starts-with? (name v) "synset-")))

(defn input-type
  "The input type of the property `k`: its own, or else the one that fits
  its values `vs`."
  [k vs]
  (or (get input-types k)
      (cond
        (empty? vs)          text
        (every? synset? vs)  synset
        (every? keyword? vs) resource
        :else                text)))
