(ns dk.cst.dannet.web.ui.input-type
  "The input types of edit mode.

  An input type is a map that says how a kind of value is entered:

  - :parse-fn, a function of the typed text and the language of its field,
    to a value, a promise of one, or nil when the text isn't a value;
  - :format-fn, a function of a value to the text that the input shows;
  - :options, the values to choose between, when there are only a few;
  - :lang?, true when a value is a text, which can be in a language;
  - :description, what a value has to be, as a text in each language;
  - :search, a search for a value in a dialog.

  A search is a map too:

  - :results-fn, a function of the typed text to results or a promise of
    them, so a search can ask the server or filter what the browser has;
  - :value-fn, a function of a result to its value;
  - :label-fn, a function of a result to the label of its value;
  - :hiccup-fn, a function of a result and the opts to its content;
  - :title and :placeholder, the texts of the dialog as maps of :da and :en;
  - :heading, the title as content, when it holds more than text;
  - :list-all?, true when the dialog lists every result before a text is
    typed;
  - :refuse-fn, a function of a result to the reason why the result
    can't be chosen, or nil when it can be;
  - :one-line?, true when a result is a name and a short note, which are
    shown on one line;
  - :note, what choosing a result does besides, as a map of :da and :en.

  The input and the dialog of ui.edit.input only know these maps, so a new input
  type or a new search needs no change to either. The input types of DanNet's
  own classes are in dk.cst.dannet.editable.input-types."
  (:require [clojure.string :as str]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.web.app :as app]
            [dk.cst.dannet.web.i18n :as i18n]))

(defn fetch-data
  "A promise of what the server has under the key `k` of its :data at `url`
  with `query-params`, or of nil when it has none."
  [url k & [query-params]]
  #?(:cljs (.then (app/fetch! url {:query-params query-params})
                  (fn [response]
                    (app/clear-current-fetch url)
                    (when (= 200 (:status response))
                      (get-in response [:body :data k]))))))

(def text
  "A text, in the language of its field when the field has one."
  {:lang?     true
   :format-fn str
   :parse-fn  (fn [s lang]
                (some-> (not-empty (str/trim s))
                        (i18n/with-lang lang)))})

(defn matching
  "A text that the `pattern` of a shape matches, with the `description` of
  such a text in each language.

  The pattern can match anywhere in the text unless it says where, as in
  SHACL."
  [pattern description]
  (assoc text
    :description description
    :parse-fn    (fn [s lang]
                   (when (re-find (re-pattern pattern) (str/trim s))
                     ((:parse-fn text) s lang)))))

(def resource
  "A resource, typed as its ID. It has to exist, and its page gives the label
  that it is shown with."
  {:format-fn prefix/kw->qname
   :parse-fn  (fn [s _]
                (when-let [k (prefix/->resource s)]
                  #?(:clj  k
                     :cljs (.then (fetch-data (prefix/resolve-href k) :entity)
                                  (fn [entity]
                                    (when entity
                                      (when-let [label (:rdfs/label entity)]
                                        (app/remember-labels! {k label}))
                                      k))))))})

(defn fitting
  "The resource `input-type` for the resources whose IRI the `pattern` of a
  shape matches, with the `description` of such a resource in each language.

  A search finds resources of DanNet, so the input type has none when the
  pattern leaves those out, as it does for an English synset."
  [{:keys [parse-fn] :as input-type} pattern description]
  (let [re    (re-pattern pattern)
        fits? #(some? (re-find re (prefix/kw->uri %)))]
    (cond-> (assoc input-type
              :description description
              :parse-fn (fn [s lang]
                          (when (some-> (prefix/->resource s) fits?)
                            (parse-fn s lang))))
      (not (re-find re prefix/dn-uri)) (dissoc :search))))

(defn input-type
  "The input type of a property with the values `vs` and the constraints
  that it `must` meet, as the shapes state them, when `input-types` is a map
  of a class to the input type of its values (see dk.cst.dannet.editable).

  It is the values to choose :in, a resource of its :class or one that is an
  IRI, a value that matches its :pattern, or else the one that fits its
  values."
  [input-types vs {:keys [in class iri? pattern description] :as must}]
  (let [resource' (or (get input-types class)
                      (when (or class iri?) resource))]
    (cond
      in                      {:options in}
      (and resource' pattern) (fitting resource' pattern description)
      resource'               resource'
      pattern                 (matching pattern description)
      (empty? vs)             text
      (every? keyword? vs)    resource
      :else                   text)))
