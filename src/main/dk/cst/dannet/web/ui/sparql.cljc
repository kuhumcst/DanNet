(ns dk.cst.dannet.web.ui.sparql
  "Components for a SPARQL query editor and SPARQL query results."
  (:require [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.web.i18n :as i18n]
            [dk.cst.dannet.web.ui.error :as error]
            [dk.cst.dannet.web.ui.form :as form]
            [rum.core :as rum]
            [dk.cst.dannet.web.ui.rdf :as rdf])
  #?(:clj (:import [java.net URLEncoder])))

(def page-sizes
  [10 20 50 100])

(def default-page-size
  10)

(defn- url-encode
  [s]
  #?(:clj  (URLEncoder/encode (str s) "UTF-8")
     :cljs (js/encodeURIComponent (str s))))

(defn- pagination-href
  "Build an href for a SPARQL pagination link."
  [query limit offset inference]
  (cond-> (str prefix/sparql-path
               "?query=" (url-encode query)
               "&limit=" limit
               "&offset=" offset)
    inference (str "&inference=" (url-encode inference))))

(rum/defc editor
  "SPARQL query editor with page size selector."
  [{:keys [languages input limit offset] :as opts}]
  (let [current-limit (or limit default-page-size)]
    [:form.sparql-editor {:action    prefix/sparql-path
                          :on-submit form/on-submit
                          :method    "get"}
     [:div.sparql-editor__input
      [:textarea {:placeholder   (i18n/da-en languages
                                   "skriv noget..."
                                   "write something...")
                  :id            "sparql-textarea"
                  :name          "query"
                  :default-value (:query input)
                  :ref           form/autofocus-ref
                  #_#_:on-focus form/select-text
                  :auto-complete "off"
                  :required      true}]
      [:input {:type      "submit"
               :tab-index "-1"
               :title     (i18n/da-en languages
                            "Send SPARQL-forespørgslen"
                            "Send the SPARQL query")
               :value     (i18n/da-en languages
                            "Eksekvér"
                            "Execute")}]]
     [:input {:type "hidden" :name "offset" :value "0"}]
     [:div.sparql-editor__controls
      [:label.model-select
       (i18n/da-en languages "Kilde " "Source ")
       [:select {:name          "inference"
                 :default-value "auto"}
        [:option {:value "auto"}
         (i18n/da-en languages "Automatisk" "Automatic")]
        [:option {:value "false"}
         (i18n/da-en languages "Rå data" "Raw data")]
        [:option {:value "true"}
         (i18n/da-en languages "Afledt data" "Inferred data")]]]
      [:label.page-size-select
       (i18n/da-en languages "Resultater " "Results ")
       [:select {:name          "limit"
                 :default-value (str current-limit)}
        (for [n page-sizes]
          [:option {:key n :value (str n)} (str n)])]]]]))


(rum/defc result-table
  "Display SPARQL SELECT results as a table with RDF-aware components."
  [{:keys [result-vars limit] :as opts} rows]
  (let [cols         (or result-vars (-> rows first keys))
        display-rows (if limit
                       (take limit rows)
                       rows)]
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
             (if (keyword? v)
               [:td {:key col}
                (rdf/resource-hyperlink v opts)]
               [:td {:key col}
                (rdf/transform-val v opts)])))])]]))

(rum/defc pagination
  "Previous/next page controls for SPARQL results."
  [{:keys [languages input limit offset has-more? sparql-result] :as opts}]
  (let [limit'    (or limit default-page-size)
        offset'   (or offset 0)
        query     (:query input)
        inference (:inference input)
        prev?     (pos? offset')
        next?     has-more?]
    (when (and query (or prev? next?))
      [:nav.sparql-pagination
       (if prev?
         [:a.sparql-pagination__prev
          {:href (pagination-href query limit' (max 0 (- offset' limit')) inference)}
          (i18n/da-en languages "← Forrige" "← Previous")]
         [:span.sparql-pagination__prev.disabled
          (i18n/da-en languages "← Forrige" "← Previous")])
       [:span.sparql-pagination__info
        (let [result-count (if (coll? sparql-result) (count sparql-result) 0)
              shown        (min limit' result-count)]
          (str (inc offset') "–" (+ offset' shown)))]
       (if next?
         [:a.sparql-pagination__next
          {:href (pagination-href query limit' (+ offset' limit') inference)}
          (i18n/da-en languages "Næste →" "Next →")]
         [:span.sparql-pagination__next.disabled
          (i18n/da-en languages "Næste →" "Next →")])])))

(rum/defc output
  [{:keys [languages input sparql-result error inference? lookahead?] :as opts}]
  [:output {:aria-live "polite"}
   (cond
     (seq sparql-result)
     [:<>
      (result-table opts sparql-result)
      (when lookahead?
        (pagination opts))
      [:asides.notes
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
       (when (false? lookahead?)
         [:p.note
          [:strong "⊘ "]
          (i18n/da-en languages
            "paginering deaktiveret (indeholder LIMIT eller OFFSET)"
            "pagination disabled (contains LIMIT or OFFSET)")])]]

     sparql-result
     [:p.note [:strong "∅ "]
      (i18n/da-en languages
        "Ingen resultater."
        "No results.")]

     error
     (let [{:keys [type message details]} error]
       (error/default-fallback (str type ": " message) details)))])
