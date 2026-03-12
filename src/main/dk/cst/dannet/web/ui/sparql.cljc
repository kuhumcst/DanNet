(ns dk.cst.dannet.web.ui.sparql
  "Components for a SPARQL query editor and SPARQL query results."
  (:require [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.web.i18n :as i18n]
            [dk.cst.dannet.web.ui.error :as error]
            [dk.cst.dannet.web.ui.form :as form]
            [rum.core :as rum]
            [dk.cst.dannet.web.ui.rdf :as rdf]))

(rum/defc editor
  "SPARQL query editor."
  [{:keys [languages] :as opts}]
  [:form {:id        "sparql-editor"
          :action    prefix/sparql-path
          :on-submit form/on-submit
          :method    "get"}
   [:div.sparql-editor__input
    [:textarea {:placeholder   (i18n/da-en languages
                                 "skriv noget..."
                                 "write something...")
                :id            "sparql-textarea"
                :name          "query"
                :ref           form/autofocus-ref
                :on-focus      form/select-text
                :auto-complete "off"}]
    [:input {:type      "submit"
             :tab-index "-1"
             :title     (i18n/da-en languages
                          "Send SPARQL-forespørgslen"
                          "Send the SPARQL query")
             :value     (i18n/da-en languages
                          "Eksekvér"
                          "Execute")}]]
   [:div.sparql-editor__controls
    [:label.inference-toggle
     [:input {:type  "checkbox"
              :name  "inference"
              :value "true"}]
     (i18n/da-en languages
       " Brug inferens (afledte relationer)"
       " Use inference (derived relations)")]]])

(rum/defc result-table
  "Display SPARQL SELECT results as a table with RDF-aware components."
  [{:keys [result-vars] :as opts} rows]
  (let [cols (or result-vars (-> rows first keys))]
    ;; TODO: change as this is not actually attr-val results
    [:table.attr-val
     [:thead
      [:tr
       (for [col cols]
         [:th {:key col} (name col)])]]
     [:tbody
      (for [[i row] (map-indexed vector rows)]
        [:tr {:key i}
         (for [col cols]
           (let [v (get row col)]
             (if (keyword? v)
               [:td {:key col}
                (rdf/resource-hyperlink v opts)]
               [:td {:key col}
                (rdf/transform-val v opts)])))])]]))

(rum/defc output
  [{:keys [languages sparql-result error] :as opts}]
  [:output {:aria-live "polite"}
   (cond
     (seq sparql-result)
     (result-table opts sparql-result)

     sparql-result
     (i18n/da-en languages
       [:p "Ingen resultater."]
       [:p "No results."])

     error
     (let [{:keys [type message details]} error]
       (error/default-fallback (str type ": " message) details)))])
