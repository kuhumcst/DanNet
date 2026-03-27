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
            #?(:cljs [dk.cst.dannet.web.ui.codemirror :as cm])
            #?(:cljs [lambdaisland.fetch :as fetch]))
  #?(:clj (:import [java.net URLEncoder])))

(def page-sizes
  [10 20 50 100])

(def default-page-size
  10)

#?(:cljs (defonce ^:private *editor-view (atom nil)))

;; TODO: currently differences between frontend/backend
(defn- pagination-href
  "Build href for a SPARQL pagination link."
  [query limit offset inference distinct labels]
  (cond-> (str prefix/sparql-path
               "?query=" (url-encode query)
               "&limit=" limit
               "&offset=" offset)
    inference (str "&inference=" (url-encode inference))
    (some? distinct) (str "&distinct=" (url-encode distinct))
    (some? labels) (str "&labels=" (url-encode labels))))

(defn- validity-message
  "Build a custom validity message from an `error` map."
  [{:keys [message details]}]
  (cond-> (or message "Invalid query")
    details (str "\n\n" (str/trim details))))

(defn- set-submit-disabled!
  "Toggle the submit button `disabled` state inside `form`."
  [form disabled?]
  #?(:cljs
     (when-let [btn (.querySelector form "input[type=submit]")]
       (set! (.-disabled btn) disabled?))))

(defn- validate-query!
  "Validate the current query via the noop endpoint.

  Read the query from the CM6 editor, send it to the server for parsing.
  On success, clear any error state and return a js/Promise resolving to
  the normalized query string. On error, show the error and return nil."
  [form]
  #?(:cljs
     (when-let [view @*editor-view]
       (when-let [query (not-empty (cm/get-doc view))]
         (-> (fetch/request (shared/normalize-url prefix/sparql-path)
                            {:query-params        {:query   query
                                                   :noop    true
                                                   :transit true}
                             :transit-json-reader shared/reader})
             (.then (fn [{:keys [body]}]
                      (if-let [nq (:normalized-query body)]
                        (do
                          (cm/clear-editor-error! view)
                          (set-submit-disabled! form false)
                          nq)
                        (do
                          (cm/show-editor-error!
                            view (validity-message (:error body)))
                          (set-submit-disabled! form true)
                          nil))))
             (.catch (fn [_] nil)))))))

(defn- normalize-query!
  "Validate and format the current query.

  On success, replace the editor content with the normalized query.
  On error, mark the editor as invalid."
  []
  #?(:cljs
     (when-let [form (js/document.querySelector "form.sparql-editor")]
       (some-> (validate-query! form)
               (.then (fn [nq]
                        (when (and nq @*editor-view)
                          (cm/set-doc! @*editor-view (str/trim nq))
                          (cm/fold-all! @*editor-view))))))))

(defn- on-submit
  "Validate the SPARQL query before submitting `e` form.

  On success, sync the query to the hidden input and submit.
  On parse error, mark the editor invalid."
  [e]
  #?(:cljs
     (let [form (.-target e)]
       (.preventDefault e)
       (when (.checkValidity form)
         (some-> (validate-query! form)
                 (.then (fn [nq]
                          (when nq
                            ;; Write the normalized query to the hidden input
                            ;; so that form/submit-form picks it up correctly.
                            (when-let [el (.getElementById
                                            js/document
                                            "sparql-query-hidden")]
                              (set! (.-value el) nq))
                            (form/submit-form form)))))))))

(def basic-select-query
  "SELECT  *\nWHERE\n  { ?subject  ?predicate  ?object }")

(defn- init-editor!
  "Mount a CM6 editor inside the component's .cm-wrapper div."
  [state]
  #?(:cljs
     (let [dom-node (rum/dom-node state)
           cm-div   (.querySelector dom-node ".cm-wrapper")
           hidden   (.querySelector dom-node "#sparql-query-hidden")
           view     (cm/create-editor! cm-div (.-value hidden)
                                       (fn [doc]
                                         ;; Keep the hidden input in sync so the form always
                                         ;; has the current query for submission.
                                         (set! (.-value hidden) doc)
                                         (when-let [v @*editor-view]
                                           (cm/clear-editor-error! v)
                                           (set-submit-disabled!
                                             (.closest (.-dom v) "form") false))))]
       (reset! *editor-view view)
       (cm/focus! view)
       (assoc state ::view view))
     :clj state))

(defn- teardown-editor!
  "Destroy the CM6 editor and clear the shared atom."
  [state]
  #?(:cljs
     (do
       (when-let [view (::view state)]
         (cm/destroy-editor! view)
         (reset! *editor-view nil))
       (dissoc state ::view))
     :clj state))

(def ^:private codemirror-mixin
  ;; Note: only :did-mount/:will-unmount — no :did-update. The form must NOT
  ;; have a React :key, since a key change would destroy the CM6 DOM without
  ;; triggering a Rum remount (Rum lifecycle is tied to the component, not
  ;; its inner elements). The editor content is the source of truth and
  ;; persists across re-renders.
  {:did-mount    init-editor!
   :will-unmount teardown-editor!})

(rum/defcs editor < codemirror-mixin
  "SPARQL query editor with page size selector."
  [state {:keys [languages input normalized-query limit] :as opts}]
  (let [current-limit (or limit default-page-size)
        distinct?     (if (:query input)
                        (= (:distinct input) "true")
                        true)
        labels?       (if (:query input)
                        (= (:labels input) "true")
                        true)
        query-value   (when-let [s (or normalized-query
                                       (:query input)
                                       basic-select-query)]
                        (str/trim s))]
    [:form.sparql-editor
     {:action    prefix/sparql-path
      :on-submit on-submit
      :method    "get"}
     [:div.sparql-editor__input
      ;; The .cm-wrapper div is populated by codemirror-mixin on :did-mount.
      ;; The hidden input carries the query value for form submission; the CM6
      ;; update listener keeps it in sync.
      [:div.cm-wrapper]
      [:input {:type  "hidden"
               :name  "query"
               :id    "sparql-query-hidden"
               :value (or query-value "")}]
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
       {:title (i18n/da-en languages
                 "Antal resultater pr. side"
                 "Number of results per page")}
       (i18n/da-en languages "Resultater " "Results ")
       [:select {:name          "limit"
                 :default-value (str current-limit)}
        (for [n page-sizes]
          [:option {:key n :value (str n)} (str n)])]]
      [:label.model-select
       {:title (i18n/da-en languages
                 "Vælg om forespørgslen køres mod rå eller logisk afledt data"
                 "Choose whether to query raw or logically inferred data")}
       (i18n/da-en languages "Kilde " "Source ")
       [:select {:name          "inference"
                 :default-value "auto"}
        [:option {:value "auto"}
         "Auto"]
        [:option {:value "false"}
         (i18n/da-en languages "Rå" "Raw")]
        [:option {:value "true"}
         (i18n/da-en languages "Afledt" "Inferred")]]]
      [:label.distinct-select
       {:title (i18n/da-en languages
                 "Fjern duplikerede rækker fra resultatet"
                 "Remove duplicate rows from the result")}
       (i18n/da-en languages "Fjern dubletter " "No duplicates ")
       [:input {:type            "checkbox"
                :name            "distinct"
                :value           "true"
                :default-checked distinct?}]]
      [:label.labels-select
       {:title (i18n/da-en languages
                 "Tilføj menneskelæsbare etiketter til ressourcer i resultatet"
                 "Add human-readable labels to resources in the result")}
       (i18n/da-en languages "Beriget " "Enriched ")
       [:input {:type            "checkbox"
                :name            "labels"
                :value           "true"
                :default-checked labels?}]]]]))


(rum/defc result-table
  "Display SPARQL SELECT results as a table with RDF-aware components."
  [{:keys [result-vars limit blank-nodes] :as opts} rows]
  (let [cols         (or result-vars (-> rows first keys))
        display-rows (if limit
                       (take limit rows)
                       rows)
        opts'        (assoc opts :table-component table/attr-val-table)]
    [:table.sparql-results
     [:thead
      [:tr
       [:th.sparql-results__count "#"]
       (for [col cols]
         [:th {:key col} (name col)])]]
     [:tbody
      (for [[i row] (map-indexed vector display-rows)]
        [:tr {:key i}
         [:td.sparql-results__count (inc i)]
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
        labels    (:labels input)
        prev?     (pos? offset')
        next?     has-more?]
    (when (and query (or prev? next?))
      [:nav.sparql-pagination
       (if prev?
         [:a.sparql-pagination__prev
          {:href (pagination-href
                   query limit'
                   (max 0 (- offset' limit'))
                   inference distinct labels)}
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
                   inference distinct labels)}
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
                                             (url-encode (:distinct input)))
                      (:labels input) (str "&labels="
                                           (url-encode (:labels input))))]
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
