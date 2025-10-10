(ns dk.cst.dannet.web.components.rdf
  (:require [clojure.string :as str]
            [rum.core :as rum]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.web.i18n :as i18n]
            #?(:clj [better-cond.core :refer [cond]]))
  #?(:cljs (:require-macros [better-cond.core :refer [cond]]))
  (:refer-clojure :exclude [cond]))


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

(def rdf-resource-re
  #"^<(.+)>$")

(defn transform-val
  "Performs convenient transformations of `v`, optionally informed by `opts`."
  ([v {:keys [attr-key entity] :as opts}]
   (cond
     (shared/rdf-datatype? v)
     (let [{:keys [uri value]} v]
       [:span {:title    uri
               :datatype uri}
        value])

     ;; Transformations of non-strings
     ;; TODO: properly implement date parsing
     (inst? v)
     (let [s (str v)]
       [:time {:date-time s} s])

     ;; Transformations of strings ONLY from here on
     :when-let [s (not-empty (str/trim (str v)))]

     (= attr-key :vann/preferredNamespacePrefix)
     (prefix-elem (symbol s) {:independent-prefix true})

     :let [[rdf-resource uri] (re-find rdf-resource-re s)]
     (re-matches #"\{.+\}" s)
     [:div.set
      [:div.set__left-bracket]
      (into [:div.set__content]
            (interpose
              [:span.subtle " • "]                          ; semicolon->bullet
              (for [label (shared/sense-labels shared/synset-sep s)]
                (if-let [[_ word _ sub mwe] (re-matches shared/sense-label label)]
                  [:<>
                   (if (= word shared/omitted)
                     [:span.subtle word]
                     word)
                   ;; Correct for the rare case of comma an affixed comma.
                   ;; e.g. http://localhost:3456/dannet/data/synset-7290
                   (when sub
                     (if (str/ends-with? sub ",")
                       [:<> [:sub (subs sub 0 (dec (count sub)))] ","]
                       [:sub sub]))
                   mwe]
                  label))))
      [:div.set__right-bracket]]

     rdf-resource
     (rdf-uri-hyperlink uri opts)

     ;; TODO: match is too broad, should be limited somewhat
     (or (get #{:ontolex/sense :ontolex/lexicalizedSense} attr-key)
         (= (:rdf/type entity) :ontolex/LexicalSense))
     (let [[_ word _ sub mwe] (re-matches shared/sense-label s)]
       [:<> word [:sub sub] mwe])

     (re-matches #"https?://[^\s]+" s)
     (break-up-uri s)

     (re-find #"\n" s)
     (into [:<>] (interpose [:br] (str/split s #"\n")))

     :else s))
  ([s]
   (transform-val s nil)))

;; TODO: figure out how to prevent line break for lang tag similar to h1
(rum/defc entity-link
  "Entity hyperlink from a `resource` and (optionally) a string label `s`."
  [resource {:keys [languages k->label class] :as opts}]
  (if (keyword? resource)
    (let [labels (get k->label resource)
          label  (i18n/select-label languages labels)
          prefix (symbol (namespace resource))]
      [:a {:href  (prefix/resolve-href resource)
           :title (str prefix ":" (name resource))
           :lang  (i18n/lang label)
           :class (or class (get prefix/prefix->class prefix "unknown"))}
       (or (transform-val label opts)
           (name resource))])
    ;; RDF predicates represented as IRIs Since the namespace is unknown,
    ;; we likely have no label data either and do not bother to fetch it.
    ;; See 'rdf-uri-hyperlink' for how objects are represented!
    (let [local-name (prefix/guess-local-name resource)]
      [:a {:href  (prefix/resource-path resource)
           :title local-name
           :class "unknown"}
       local-name])))

;; See also 'rdf-uri-hyperlink'.
(rum/defc rdf-resource-hyperlink
  "A stylised RDF `resource` hyperlink, stylised according to `opts`."
  [resource {:keys [attr-key k->label] :as opts}]
  (cond
    ;; Label and text colour are modified to fit the inherited relation.
    (= attr-key :dns/inherited)
    (let [inherited       (some->> (get k->label resource) first (prefix/qname->kw))
          inherited-label (get k->label inherited)
          prefix          (when inherited
                            (symbol (namespace inherited)))
          opts'           (-> opts
                              (assoc-in [:k->label resource] inherited-label)
                              (assoc :class (get prefix/prefix->class prefix)))]
      [:div.qname
       (prefix-elem (or prefix (symbol (namespace resource))) opts')
       (entity-link resource opts')])

    ;; The generic case just displays the prefix badge + the hyperlink.
    :else
    [:div.qname
     (prefix-elem (symbol (namespace resource)) opts)
     (entity-link resource opts)]))

(defn transform-val-coll
  "Performs convenient transformations of `coll` informed by `opts`."
  [coll {:keys [attr-key] :as opts}]
  (cond
    (and (= :dns/ontologicalType attr-key)
         (every? #(and (qualified-ident? %)
                       (= "dnc" (namespace %))) coll))
    (let [vs     (sort-by name coll)
          fv     (first vs)
          prefix (symbol (namespace fv))]
      (for [v vs]
        [:<> {:key v}
         (when-not (= fv v)
           " + ")
         (prefix-elem prefix opts)
         (entity-link v opts)]))))

(rum/defc blank-resource
  "Display a blank resource in either a specialised way or as an inline table."
  [{:keys [languages] :as opts} x]
  (cond
    (shared/rdf-datatype? x)
    (transform-val x)

    (= (keys x) [:rdf/value])
    (let [x (i18n/select-str languages (:rdf/value x))]
      (if (coll? x)
        (into [:<>] (for [s x]
                      [:section.text {:lang (i18n/lang s)} (str s)]))
        [:section.text {:lang (i18n/lang x)} (str x)]))

    ;; Special handling of DanNet sentiment data.
    (and (= (keys x) [:marl/hasPolarity :marl/polarityValue])
         (keyword? (first (:marl/hasPolarity x))))
    [:<>
     (rdf-resource-hyperlink (first (:marl/hasPolarity x)) opts)
     " (" (first (:marl/polarityValue x)) ")"]

    (contains? (:rdf/type x) :rdf/Bag)
    (let [ns->resources (-> (->> (dissoc x :rdf/type)
                                 (filter (comp shared/member-property? first))
                                 (mapcat second)
                                 (group-by namespace))
                            (update-vals sort)
                            (update-keys symbol))
          resources     (->> (sort ns->resources)
                             (vals)
                             (apply concat))]
      [:div.set
       (when (and (every? keyword? resources)
                  (apply = (map namespace resources)))
         (let [prefix (symbol (namespace (first resources)))]
           (prefix-elem prefix opts)))
       [:div.set__left-bracket]
       (into [:div.set__content]
             (->> (sort ns->resources)
                  (vals)
                  (apply concat)
                  (map #(entity-link % opts))
                  (interpose [:span.subtle " • "])))
       [:div.set__right-bracket]])))
