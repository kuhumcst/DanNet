(ns dk.cst.dannet.web.ui.catalog
  "UI components for displaying catalog resources, i.e. schemas and datasets."
  (:require [clojure.string :as str]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.web.i18n :as i18n]
            [dk.cst.dannet.web.ui.rdf :as rdf]))

(def group-order
  "Display order for catalog prefix groups with titles and descriptions."
  [["dannet" "DanNet"
    {:da "Skemaer og datasæt specifikt for DanNet."
     :en "Schemas and datasets specifically for DanNet."}]
   ["wordnet" "WordNet"
    {:da "Skemaer relateret til Global WordNet-standarden."
     :en "Schemas related to the Global WordNet standard."}]
   ["ontolex" {:da "Lingvistiske skemaer" :en "Linguistic schemas"}
    {:da "Skemaer fra OntoLex-Lemon-familien til leksikografiske data."
     :en "Schemas from the OntoLex-Lemon family for lexicographic data."}]
   ["w3c" "W3C"
    {:da "Standardskemaer fra World Wide Web Consortium."
     :en "Standard schemas from the World Wide Web Consortium."}]
   ["meta" "Metadata"
    {:da "Skemaer vedrørende metadata og licenser."
     :en "Schemas for metadata and licensing."}]
   [nil {:da "Andet" :en "Other"}
    {:da "Andre skemaer og datasæt."
     :en "Other schemas and datasets."}]])

(defn prepare-groups
  "Transform raw `catalog` data based on `opts` into grouped, sorted entries.
  
  Returns a seq of [group-key title description entries] tuples, where entries
  are sorted alphabetically by label within each group."
  [catalog {:keys [languages] :as opts}]
  (let [sort-key    (fn [[_ {:keys [label]}]]
                      (str/lower-case (str (i18n/select-label languages label))))
        with-labels (filter (comp :label second) catalog)
        grouped     (group-by (fn [[_ {:keys [prefix]}]]
                                (when prefix
                                  (prefix/prefix->class (symbol prefix))))
                              with-labels)]
    (keep (fn [[group-key title desc]]
            (when-let [entries (get grouped group-key)]
              [group-key title desc (sort-by sort-key entries)]))
          group-order)))

(defn k->label
  "Build a keyword->label map from `catalog` entries that have prefixes."
  [catalog]
  (into {}
        (keep (fn [[_ {:keys [label prefix]}]]
                (when prefix
                  [(keyword (str prefix) "") (or label (str prefix))])))
        catalog))

(defn table
  "Render a single catalog table for a group of `entries` using `k->label`."
  [{:keys [languages] :as opts} k->label entries]
  [:table.attr-val
   [:colgroup
    [:col]
    [:col]
    [:col]]
   [:tbody
    (for [[rdf-resource {:keys [description prefix]}] entries
          :let [uri   (prefix/rdf-resource->uri rdf-resource)
                path  (prefix/uri->internal-path uri)
                opts' (assoc opts :k->label k->label :href path)
                kw    (when prefix (keyword (str prefix) ""))]]
      [:tr {:key rdf-resource}
       [:td.attr-prefix
        (when prefix
          (rdf/prefix-badge (symbol prefix)))]
       [:td.attr-name
        (if kw
          (rdf/entity-link kw opts')
          (rdf/rdf-uri-hyperlink uri opts'))]
       [:td (when description
              (rdf/transform-text opts description))]])]])
