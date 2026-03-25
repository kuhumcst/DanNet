(ns dk.cst.dannet.web.ui.sparql
  "Components for a SPARQL query editor and SPARQL query results."
  (:require [clojure.string :as str]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.web.i18n :as i18n]
            [dk.cst.dannet.web.ui.error :as error]
            [dk.cst.dannet.web.ui.form :as form]
            [rum.core :as rum]
            [dk.cst.dannet.web.ui.rdf :as rdf]
            [dk.cst.dannet.web.ui.table :as table]
            [reitit.impl :refer [url-encode]]               ; CLJC url-encode
            #?(:cljs [lambdaisland.fetch :as fetch]))
  #?(:clj (:import [java.net URLEncoder])))

(def page-sizes
  [10 20 50 100])

(def default-page-size
  10)

;; TODO: currently differences between frontend/backend
(defn- pagination-href
  "Build href for a SPARQL pagination link."
  [query limit offset inference distinct]
  (cond-> (str prefix/sparql-path
               "?query=" (url-encode query)
               "&limit=" limit
               "&offset=" offset)
    inference (str "&inference=" (url-encode inference))
    (some? distinct) (str "&distinct=" (url-encode distinct))))

(defn- validity-message
  "Build a custom validity message from an `error` map."
  [{:keys [message details]}]
  (cond-> (or message "Invalid query")
    details (str "\n\n" (str/trim details))))

(defn- validate-query!
  "Validate the query in `textarea` via the noop endpoint.

  On error, set custom validity on `textarea`, disable the submit button, and
  report validity. Return a js/Promise resolving to the normalized query on
  success, nil on error."
  [textarea]
  #?(:cljs (when-let [query (not-empty (.-value textarea))]
             (-> (fetch/request (shared/normalize-url prefix/sparql-path)
                                {:query-params        {:query   query
                                                       :noop    true
                                                       :transit true}
                                 :transit-json-reader shared/reader})
                 (.then (fn [{:keys [body]}]
                          (if-let [nq (:normalized-query body)]
                            (do
                              (.setCustomValidity textarea "")
                              (form/set-submit-disabled! textarea false)
                              nq)
                            (do
                              (.setCustomValidity textarea (validity-message (:error body)))
                              (form/set-submit-disabled! textarea true)
                              (.reportValidity textarea)
                              nil))))
                 (.catch (fn [_] nil))))
     :clj  nil))

(defn- normalize-query!
  "Validate and format the current textarea query.

  On success, replace the textarea content with the normalized query.
  On error, mark the textarea as invalid."
  []
  #?(:cljs
     (when-let [textarea (js/document.getElementById
                           "sparql-textarea")]
       (some-> (validate-query! textarea)
               (.then (fn [nq]
                        (when nq
                          (set! (.-value textarea) nq))))))
     :clj nil))

(defn- on-submit
  "Validate the SPARQL query before submitting the `e` form.

  On success, submit the form. On parse error, set custom
  validity and report it to show the browser tooltip."
  [e]
  #?(:cljs
     (let [form     (.-target e)
           textarea (js/document.getElementById
                      "sparql-textarea")]
       (.preventDefault e)
       ;; Client-side pre-validation via the noop endpoint.
       (when (.checkValidity form)
         (some-> (validate-query! textarea)
                 (.then (fn [nq]
                          (when nq
                            (form/submit-form form)))))))
     :clj nil))

(def basic-select-query
  "SELECT  *\nWHERE\n  { ?subject  ?predicate  ?object }")

(rum/defc editor
  "SPARQL query editor with page size selector."
  [{:keys [languages input normalized-query limit] :as opts}]
  (let [current-limit (or limit default-page-size)
        distinct?     (if (:query input)
                        (= (:distinct input) "true")
                        true)]
    [:form.sparql-editor
     {:key       (str (:query input) (:distinct input))
      :action    prefix/sparql-path
      :on-submit on-submit
      :method    "get"}
     [:div.sparql-editor__input
      [:textarea {:placeholder   (i18n/da-en languages
                                   "skriv en forespørgsel..."
                                   "write a query...")
                  :id            "sparql-textarea"
                  :name          "query"
                  :default-value (when-let [s (or normalized-query
                                                  (:query input)
                                                  basic-select-query)]
                                   (str/trim s))
                  :ref           form/autofocus-ref
                  #_#_:on-focus form/select-text
                  :on-input      form/clear-validity!
                  :auto-complete "off"
                  :required      true}]
      [:div.sparql-editor__buttons
       [:input {:type      "submit"
                :tab-index "-1"
                :title     (i18n/da-en languages
                             "Send SPARQL-forespørgslen"
                             "Send the SPARQL query")
                :value     (i18n/da-en languages
                             "Eksekvér"
                             "Execute")}]
       [:button.format-btn
        {:type     "button"
         :title    (i18n/da-en languages
                     "Formatér og validér forespørgslen"
                     "Format and validate the query")
         :on-click normalize-query!}
        (i18n/da-en languages "Formatér" "Format")]]]
     [:input {:type "hidden" :name "offset" :value "0"}]
     ;; TODO: progress meter also displays on normal fetches, e.g. entity, fix!
     #_[:div.sparql-progress]
     [:div.sparql-editor__controls
      [:label.page-size-select
       (i18n/da-en languages "Resultater " "Results ")
       [:select {:name          "limit"
                 :default-value (str current-limit)}
        (for [n page-sizes]
          [:option {:key n :value (str n)} (str n)])]]
      [:label.distinct-select
       (i18n/da-en languages "Fjern dubletter " "No duplicates ")
       [:input {:type            "checkbox"
                :name            "distinct"
                :value           "true"
                :default-checked distinct?}]]
      [:label.model-select
       (i18n/da-en languages "Kilde " "Source ")
       [:select {:name          "inference"
                 :default-value "auto"}
        [:option {:value "auto"}
         (i18n/da-en languages "Automatisk" "Automatic")]
        [:option {:value "false"}
         (i18n/da-en languages "Rå data" "Raw data")]
        [:option {:value "true"}
         (i18n/da-en languages "Afledt data" "Inferred data")]]]]]))


(rum/defc result-table
  "Display SPARQL SELECT results as a table with RDF-aware components."
  [{:keys [result-vars limit blank-nodes] :as opts} rows]
  (let [cols         (or result-vars (-> rows first keys))
        display-rows (if limit
                       (take limit rows)
                       rows)
        opts'        (assoc opts :table-component table/attr-val-table)]
    ;; TODO: change as this is not actually attr-val results
    [:table.attr-val
     [:thead
      [:tr
       (for [col cols]
         [:th {:key col} (name col)])]]
     [:tbody
      (for [[i row] (map-indexed vector display-rows)]
        [:tr {:key i}
         (for [col cols]
           (let [v (get row col)]
             [:td {:key col}
              (if-let [be (and (symbol? v) (get blank-nodes v))]
                (rdf/blank-node opts' be)
                (if (keyword? v)
                  (rdf/resource-hyperlink v opts')
                  (rdf/transform-val v opts')))]))])]]))

(rum/defc pagination
  "Previous/next page controls for SPARQL results."
  [{:keys [languages input limit offset has-more? sparql-result]
    :as   opts}]
  (let [limit'    (or limit default-page-size)
        offset'   (or offset 0)
        query     (:query input)
        inference (:inference input)
        distinct  (:distinct input)
        prev?     (pos? offset')
        next?     has-more?]
    (when (and query (or prev? next?))
      [:nav.sparql-pagination
       (if prev?
         [:a.sparql-pagination__prev
          {:href (pagination-href
                   query limit'
                   (max 0 (- offset' limit'))
                   inference distinct)}
          (i18n/da-en languages
            "← Forrige" "← Previous")]
         [:span.sparql-pagination__prev.disabled
          (i18n/da-en languages
            "← Forrige" "← Previous")])
       [:span.sparql-pagination__info
        (let [result-count (if (coll? sparql-result)
                             (count sparql-result)
                             0)
              shown        (min limit' result-count)]
          (str (inc offset') "–" (+ offset' shown)))]
       (if next?
         [:a.sparql-pagination__next
          {:href (pagination-href
                   query limit'
                   (+ offset' limit')
                   inference distinct)}
          (i18n/da-en languages
            "Næste →" "Next →")]
         [:span.sparql-pagination__next.disabled
          (i18n/da-en languages
            "Næste →" "Next →")])])))

(rum/defc output
  [{:keys [languages input sparql-result error inference?
           cached? distinct? lookahead? limit offset]
    :as   opts}]
  [:output {:aria-live "polite"}
   (cond
     (seq sparql-result)
     [:<>
      (result-table opts sparql-result)
      (when lookahead?
        (pagination opts))
      [:aside.notes
       (when inference?
         (let [forced? (= (:inference input) "true")]
           [:p.note
            [:strong "∴ "]
            (if forced?
              (i18n/da-en languages
                "kan indeholde logisk afledt data"
                "may contain logically inferred data")
              (i18n/da-en languages
                "indeholder logisk afledt data"
                "contains logically inferred data"))]))
       (when cached?
         [:p.note
          [:strong "⧖ "]
          (i18n/da-en languages
            "resultatet blev hentet fra cachen"
            "result was served from cache")])
       (when (false? lookahead?)
         [:p.note
          [:strong "⊘ "]
          (i18n/da-en languages
            "paginering deaktiveret (indeholder LIMIT eller OFFSET)"
            "pagination disabled (contains LIMIT or OFFSET)")])
       (when-let [query (:query input)]
         (let [href (cond-> (str prefix/sparql-path
                                 "?query=" (url-encode query)
                                 "&format=json")
                      limit (str "&limit=" limit)
                      offset (str "&offset=" offset)
                      (:inference input) (str "&inference="
                                              (url-encode (:inference input)))
                      (:distinct input) (str "&distinct="
                                             (url-encode (:distinct input))))]
           [:p.note
            [:strong "↓ "]
            (i18n/da-en languages
              "hent data som: "
              "download data as: ")
            [:a {:href     href
                 :type     "application/json"
                 :title    "JSON"
                 :download true}
             "JSON"]]))]]

     sparql-result
     [:p.note [:strong "∅ "]
      (i18n/da-en languages
        "Ingen resultater."
        "No results.")]

     error
     (let [{:keys [type message details]} error]
       (error/default-fallback
         (str type ": " message) details)))])
