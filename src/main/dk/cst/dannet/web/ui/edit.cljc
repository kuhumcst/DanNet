(ns dk.cst.dannet.web.ui.edit
  "The input of a value in edit mode, which adapts to the kind of value.

  A value kind is a map that says how a kind of value is entered:

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

  The field and the dialog only know these maps, so a new kind of value or
  of search needs no change to either."
  (:require [clojure.string :as str]
            [rum.core :as rum]
            [dk.cst.theophrastus.term :as term]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.web.app :as app]
            [dk.cst.dannet.web.i18n :as i18n]
            [dk.cst.dannet.web.ui.rdf :as rdf]))

(def site-root
  "The scheme and domain of the DanNet site, e.g. \"https://wordnet.dk\"."
  (re-find #"^https?://[^/]+" prefix/dannet-root))

(defn ->resource
  "The resource that the text `s` names, as a keyword, or nil when it names
  none: a prefixed name such as dn:synset-3047, an IRI with or without angle
  brackets, or the address of a page of the site or of a local copy of it."
  [s]
  (let [s   (-> (str/trim s)
                (str/replace #"^<|>$" "")
                (str/replace #"\?.*$" ""))
        iri (if-let [[_ path] (re-matches #"https?://localhost(?::\d+)?(/.*)" s)]
              (str site-root path)
              s)
        [prefix uri] (->> prefix/schemas
                          (keep (fn [[prefix {:keys [uri]}]]
                                  (when (and uri
                                             (str/starts-with? iri uri)
                                             (not= iri uri))
                                    [prefix uri])))
                          (sort-by (comp count second) >)
                          (first))]
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

(defn- matches?
  "Does the label of the `result` contain the text `s`, in any case?"
  [s {:keys [rdfs/label] :as result}]
  (str/includes? (str/lower-case (str label)) (str/lower-case (str/trim s))))

(def synset
  "A synset, typed as its ID or found by one of its words, which the server
  looks up."
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
                                  select (partial i18n/select-label languages)
                                  type   (:dns/ontologicalType entity)]
                              [:<>
                               [:strong (str (select (get k->label k)))]
                               [:span (str (select (:skos/definition entity)))]
                               (when type
                                 [:small (str (select (get k->label type)))])]))}))

;; The types are fetched once and then searched in the browser.
(defonce ^{:doc "A promise of every ontological type with its label."}
  ontological-types
  (delay (fetch-data "/dannet/edit/ontological-types" :ontological-types)))

(def ontological-type
  "An ontological type, typed as its ID or found among the existing types,
  which the browser has."
  (assoc resource
    :search {:title       {:da "Find en ontologisk type"
                           :en "Find an ontological type"}
             :placeholder {:da "skriv et begreb..." :en "write a concept..."}
             :results-fn  (fn [s]
                            (.then @ontological-types
                                   (fn [types]
                                     (filter (partial matches? s) types))))
             :value-fn    :rdf/about
             :label-fn    :rdfs/label
             :hiccup-fn   (fn [{:keys [rdfs/label]} _]
                            [:strong (str label)])}))

(def part-of-speech
  "A part of speech, which is one of three."
  {:options [:wn/noun :wn/verb :wn/adjective]})

(def value-kinds
  "The value kind of each property that has one of its own."
  {:wn/partOfSpeech     part-of-speech
   :dns/ontologicalType ontological-type})

(defn- synset?
  "Is `v` the ID of a DanNet synset?"
  [v]
  (and (keyword? v)
       (= "dn" (namespace v))
       (str/starts-with? (name v) "synset-")))

(defn value-kind
  "The value kind of the property `k`: its own, or else the kind that its
  values `vs` are of."
  [k vs]
  (or (get value-kinds k)
      (cond
        (empty? vs)          text
        (every? synset? vs)  synset
        (every? keyword? vs) resource
        :else                text)))

(defn icon-button
  "A button with `icon` in an editable value, named by `label`."
  [icon label on-click]
  [:button.edit-value__button {:type     "button"
                               :title    label
                               :on-click on-click}
   [:span {:class       ["edit-value__icon" (str "edit-value__icon--" icon)]
           :aria-hidden "true"}]
   [:span.visually-hidden label]])

(def ^:private show-modal
  "A mixin that opens a <dialog> as a modal one when it is drawn, which
  keeps the focus inside it and closes it on Escape. The focus starts in
  its input."
  {:did-mount (fn [state]
                #?(:cljs (let [dialog (rum/dom-node state)]
                           (.showModal dialog)
                           (some-> (.querySelector dialog "input") (.focus))))
                state)})

(rum/defcs search-dialog < show-modal
                           (rum/local nil ::results)
                           (rum/local 0 ::searches)
  "A dialog that finds a value with the `search` and calls `on-value` with
  the one that is chosen, or `on-close` when it is closed without one."
  [state {:keys [languages] :as opts}
   {:keys [results-fn value-fn label-fn hiccup-fn title placeholder] :as search}
   {:keys [on-value on-close]}]
  (let [results  (::results state)
        searches (::searches state)
        localize #(i18n/da-en languages (:da %) (:en %))
        ;; Only the results of the latest search are shown, and a search
        ;; waits until the typing pauses.
        search!  (fn [s]
                   #?(:cljs
                      (let [n (swap! searches inc)]
                        (js/setTimeout
                          (fn []
                            (when (= n @searches)
                              (.then (js/Promise.resolve (results-fn s))
                                     #(when (= n @searches)
                                        (reset! results (vec %))))))
                          200))))]
    [:dialog.search-dialog
     {:aria-label (localize title)
      :on-close   on-close
      ;; a click on the backdrop is a click on the dialog itself
      :on-click   (fn [e]
                    (when (= (.-target e) (.-currentTarget e))
                      (.close (.-currentTarget e))))}
     [:header
      [:h2 (localize title)]
      (icon-button "remove" (i18n/da-en languages "Luk" "Close")
                   (fn [e] (.close (.closest (.-target e) "dialog"))))]
     [:input {:type          "search"
              :auto-complete "off"
              :aria-label    (localize title)
              :placeholder   (localize placeholder)
              :on-change     #(search! (.. % -target -value))}]
     [:output {:aria-live "polite"}
      (cond
        (nil? @results)
        nil

        (empty? @results)
        [:p (i18n/da-en languages "Ingen resultater" "No results")]

        :else
        [:ul.search-dialog__results
         (for [result @results
               :let [v (value-fn result)]]
           [:li {:key (pr-str v)}
            [:button {:type     "button"
                      :on-click (fn [_]
                                  (when label-fn
                                    (remember-label! v (label-fn result)))
                                  (on-value v))}
             (hiccup-fn result opts)]])])]]))

(rum/defcs field < (rum/local nil ::error)
                   (rum/local false ::searching?)
  "The input of a value of the `value-kind`: a dropdown of its options, or
  else a text input, with a button for its search when it has one.

  The input starts out with the `value`, when there is one. It calls
  `on-value` with a new value and `on-cancel` when there is none: Enter or
  leaving the input enters the text, and Escape leaves the value as it was.
  A text that isn't a value stays in the input, which is then invalid and
  has a wavy line under its text. The `like` value is one that a new value
  should resemble, e.g. in its language."
  [state {:keys [languages] :as opts}
   {:keys [parse-fn format-fn options search] :as value-kind}
   {:keys [value like label on-value on-cancel]}]
  (let [error      (::error state)
        searching? (::searching? state)
        old-text   (if (some? value) (format-fn value) "")
        ;; Leaving the input for its own search button isn't leaving it.
        inside?    (fn [e]
                     (some-> (.-relatedTarget e) (.closest "li")
                             (= (.closest (.-target e) "li"))))
        invalid    (i18n/da-en languages
                     "Ikke en gyldig værdi" "Not a valid value")
        ;; A text that isn't a value makes the input invalid, whether it is
        ;; entered or the input is left. The browser only reports it when it
        ;; is entered, since a report takes the focus back.
        enter!     (fn [e leave?]
                     (let [input (.-target e)
                           s     (str/trim (.-value input))]
                       (if (or (empty? s) (= s old-text))
                         (on-cancel)
                         #?(:cljs
                            (.then (js/Promise.resolve
                                     (parse-fn s (if (some? value) value like)))
                                   (fn [v]
                                     (if (some? v)
                                       (on-value v)
                                       (do
                                         (reset! error s)
                                         (.setCustomValidity input invalid)
                                         (when-not leave?
                                           (.reportValidity input))))))))))]
    (if options
      [:select.edit-value__input
       {:aria-label    label
        :auto-focus    true
        :default-value (str (some-> value prefix/kw->qname))
        :on-blur       (fn [_] (on-cancel))
        :on-key-down   (fn [e]
                         (when (= "Escape" (.-key e))
                           (on-cancel)))
        :on-change     (fn [e]
                         (let [qname (.. e -target -value)]
                           (if (= qname (some-> value prefix/kw->qname))
                             (on-cancel)
                             (on-value (prefix/qname->kw qname)))))}
       (when (nil? value)
         [:option {:value ""} "…"])
       (for [option options]
         [:option {:key   (str option)
                   :value (prefix/kw->qname option)}
          (rdf/link-label opts option)])]
      [:<>
       [:textarea.edit-value__input
        {:default-value old-text
         :lang          (i18n/lang (if (some? value) value like))
         :aria-label    label
         :aria-invalid  (when @error "true")
         :title         (when @error invalid)
         :auto-focus    true
         :rows          1
         :spell-check   (when search "false")
         ;; the caret starts at the end of the text
         :on-focus      (fn [e]
                          (let [n (count (.. e -target -value))]
                            (.setSelectionRange (.-target e) n n)))
         :on-change     (fn [e]
                          (reset! error nil)
                          (.setCustomValidity (.-target e) ""))
         :on-blur       (fn [e]
                          (when-not (or (inside? e) @searching?)
                            (enter! e true)))
         :on-key-down   (fn [e]
                          (case (.-key e)
                            "Enter" (do (.preventDefault e) (enter! e false))
                            "Escape" (on-cancel)
                            nil))}]
       (when search
         [:span.edit-value__actions
          (icon-button "search" (i18n/da-en languages "Søg" "Search")
                       #(reset! searching? true))])
       (when @searching?
         (search-dialog opts search
                        {:on-value on-value
                         :on-close #(reset! searching? false)}))])))
