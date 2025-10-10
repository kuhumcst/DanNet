(ns dk.cst.dannet.web.components.rdf
  (:require [rum.core :as rum]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.web.i18n :as i18n]))


(defn break-up-uri
  "Place word break opportunities into a potentially long `uri`."
  [uri]
  (into [:<>] (for [part (re-seq #"[^\./]+|[\./]+" uri)]
                (if (re-matches #"[^\./]+" part)
                  [:<> part [:wbr]]
                  part))))

(rum/defc rdf-uri-hyperlink
  "Display URIs in RDF <resource> style or using a label when available."
  [uri {:keys [languages k->label attr-key] :as opts}]
  (let [labels (get k->label (prefix/uri->rdf-resource uri))
        label  (i18n/select-label languages labels)
        path   (prefix/uri->internal-path uri)]
    (if (and label
             (not (= attr-key :foaf/homepage)))             ; special behaviour
      [:a {:href path} (str label)]
      [:a.rdf-uri {:href path} (break-up-uri uri)])))

(defn- local-entity-prefix?
  "Is this `prefix` the same as the local entity in `opts`?"
  [prefix {:keys [attr-key entity] :as opts}]
  (or (and (keyword? attr-key)
           (= prefix (-> attr-key namespace symbol)))
      (and (keyword? (:subject (meta entity)))
           (= prefix (-> entity meta :subject namespace symbol)))))


;; TODO: don't hide when `details?` is true?
(defn- hide-prefix?
  "Whether to hide the value column `prefix` according to its context `opts`."
  [prefix {:keys [attr-key details?] :as opts}]
  (or (= :rdf/about attr-key)
      ;; TODO: don't hardcode ontologicalType (get from input config instead)
      (= :dns/ontologicalType attr-key)
      (and (not= :dns/inherited attr-key)                   ; special case
           (local-entity-prefix? prefix opts))))

(rum/defc prefix-elem
  "Visual representation of a `prefix` based on its associated symbol.

  If context `opts` are provided, the `prefix` is assumed to be in the value
  column and will potentially be hidden according to the provided context."
  ([prefix]
   (prefix-elem prefix nil))
  ([prefix {:keys [independent-prefix] :as opts}]
   (cond
     (symbol? prefix)
     [:span.prefix {:title (prefix/prefix->uri prefix)
                    :class [(prefix/prefix->class prefix)
                            (if independent-prefix
                              "independent"
                              (when (hide-prefix? prefix opts)
                                "hidden"))]}
      (str prefix) [:span.prefix__sep ":"]]

     (string? prefix)
     [:span.prefix {:title (prefix/guess-ns prefix)
                    :class "unknown"}
      "???"])))
