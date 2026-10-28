(ns dk.cst.dannet.web.ui.visualization
  "The synset diagrams (radial and sunbursts) with their toolbar and legends,
  and the word clouds. The server renders their containers; web.d3 draws into
  them from ref callbacks in the browser."
  (:require [rum.core :as rum]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.web.i18n :as i18n]
            [dk.cst.dannet.web.app :as app]
            [dk.cst.dannet.web.ui.rdf :as rdf]
            [dk.cst.dannet.web.ui.relations :as relations]
            #?(:cljs [dk.cst.dannet.web.d3 :as d3])))

(defn- viz-opts
  "Returns opts with k->label guaranteed to have real labels for visualisations,
  regardless of the user's detail-level setting."
  [{:keys [detail-level entities entity subject] :as opts}]
  (if (and (= detail-level :basic) entities)
    (let [entities' (assoc entities subject entity)]
      (assoc opts :k->label
                  (update-vals entities' (shared/->entity-label-fn :normal))))
    opts))

(defn- with-actions
  "Add the callbacks for clicks in a diagram to `opts`: `on-navigate` gets the
  href of a clicked synset, and the subject toggles full-screen mode. The D3
  code calls these rather than the app."
  [opts on-navigate]
  (assoc opts
    :on-navigate on-navigate
    :on-toggle-full-screen app/toggle-full-screen!))

(rum/defc word-cloud
  "A word cloud of `synsets`, sorted by weight with the highest first, using
  the labels and :cloud-limit in `opts`."
  [synsets opts]
  (let [opts' (with-actions (viz-opts opts) app/navigate-to)]
    [:div {:ref #?(:clj  nil
                   :cljs #(d3/build-cloud! opts' synsets %))}]))

(rum/defc radial-tree-diagram
  [subentity {:keys [languages] :as opts}]
  ;; Scrolling away from a diagram after a click in it is disorienting.
  (let [opts' (with-actions (viz-opts opts)
                            #(app/navigate-to % {:keep-scroll? true}))]
    [:figure.radial-tree-diagram
     {:ref #?(:clj  nil
              :cljs (fn [elem]
                      (when elem
                        (d3/build-radial! subentity elem opts'))))}
     ;; Names the figure for assistive tech; the SVG itself is built in the ref
     ;; callback and also carries role="img" + a label.
     [:figcaption.visually-hidden
      (i18n/da-en languages
        "Relationsdiagram for det aktuelle synset"
        "Relations diagram for the current synset")]]))

(rum/defc sunburst-diagram
  [tree caption {:keys [sunburst-nav] :as opts}]
  [:figure.hyponym-sunburst-diagram
   {:ref #?(:clj  nil
            :cljs (fn [elem]
                    (when elem
                      (d3/build-sunburst!
                        tree elem sunburst-nav
                        (with-actions
                          opts
                          (fn [href]
                            ;; Reset to the radial so navigating reads as a
                            ;; page change, not a silent re-root.
                            (swap! app/session assoc-in app/diagram-mode-path :radial)
                            (app/navigate-to href {:keep-scroll? true})))))))}
   ;; Names the figure for assistive tech; the SVG itself is built in the ref
   ;; callback and also carries role="img" + a label.
   [:figcaption.visually-hidden caption]])

(rum/defc full-screen-toggle
  "The maximize/minimize button shared by the radial legend and the sunburst
  history column, so it stays in place across diagram modes."
  [{:keys [full-screen languages]}]
  [:button.icon {:class    (if full-screen
                             "minimize"
                             "maximize")
                 :title    (if full-screen
                             (i18n/da-en languages "Minimér" "Minimize")
                             (i18n/da-en languages "Maksimér" "Maximize"))
                 :on-click (fn [_] (app/toggle-full-screen!))}])

(rum/defc diagram-legend
  "Right-hand column shell shared by the radial and sunburst diagrams: holds the
  mode-specific `content` in a fieldset that lines up across modes. The
  full-screen toggle lives in the shared top bar, not here."
  [attrs content]
  [:fieldset.radial-tree-legend attrs
   content])

(rum/defc sunburst-legend < rum/reactive
  [{:keys [languages sunburst-nav] :as opts}]
  (let [trail #?(:cljs (when sunburst-nav (rum/react sunburst-nav))
                 :clj nil)]
    (diagram-legend
      {:aria-label (i18n/da-en languages "Zoomhistorik" "Zoom history")}
      (when (seq trail)
        ;; The breadcrumb is navigation through the zoom history, so it reads as
        ;; a <nav> landmark rather than a bare list.
        [:nav.hyponym-sunburst-history-nav
         {:aria-label (i18n/da-en languages "Zoomhistorik" "Zoom history")}
         (into [:ol.hyponym-sunburst-history]
               (map-indexed
                 (fn [i {:keys [name last? on-click]}]
                   [:li {:key   i
                         :class (when last? "current")}
                    (if last?
                      [:span {:aria-current "true"} name]
                      [:button {:type "button" :on-click on-click} name])])
                 trail))]))))

(def radial-tree-selector
  ".radial-tree-nodes [data-theme],
  .radial-tree-links [data-theme],
  .radial-tree-labels [data-theme]")

(defn filter-radial-diagram!
  "De-emphasize the items of the radial `diagram` outside relation `theme`; a
  nil `theme` shows all."
  [diagram theme]
  (doseq [el (.querySelectorAll diagram radial-tree-selector)]
    (let [show? (or (nil? theme)
                    (= theme (.getAttribute el "data-theme"))
                    (.contains (.-classList el) "radial-item--subject"))]
      (.toggle (.-classList el) "radial-item--de-emphasized" (not show?)))))

;; Inspiration for checkboxes: https://www.w3schools.com/howto/tryit.asp?filename=tryhow_css_custom_checkbox
(rum/defcs radial-tree-legend < (rum/local nil ::selected)
  [state subentity {:keys [languages k->label detail-level]}]
  (let [selected (::selected state)
        label-of (fn [k]
                   (if (= detail-level :basic)
                     (prefix/kw->qname k)
                     (i18n/select-label languages (k->label k))))]
    (diagram-legend
      {:aria-label (i18n/da-en languages
                     "Filtrer relationstyper"
                     "Filter relation types")}
      [:ul.radial-tree-legend
       ;; Relations are ordered by the canonical relations/group-order so the
       ;; legend matches the diagram, and each <li> is tagged with its group
       ;; class so the grouping can be styled in CSS without splitting the list.
       (for [k (sort-by #(relations/relation-sort-key % (label-of %))
                        (keys subentity))]
         (when-let [theme (get shared/synset-rel-theme k)]
           (let [label        (label-of k)
                 is-selected? (= @selected theme)]
             [:li {:key   k
                   :class (relations/relation->class k)}
              [:label {:lang (i18n/lang label)} (str label)
               [:input {:type      "radio"
                        :name      "radial-tree-filter"
                        :value     theme
                        :checked   is-selected?
                        :read-only true
                        :on-click  (fn [e]
                                     (let [new-selection (if is-selected? nil theme)
                                           diagram       (-> (.-target e)
                                                             (.closest ".synset-diagram")
                                                             (.querySelector ".radial-tree-diagram"))]
                                       (reset! selected new-selection)
                                       (filter-radial-diagram! diagram new-selection)))}]
               [:span {:class "radial-tree-legend__bullet"
                       :style {:background theme}}]]])))])))

;; TODO: use a heuristic for high-lighting the relevant word
(rum/defc examples-dt+dd
  [{:keys [entity languages] :as opts}]
  (when-let [v (:lexinfo/senseExample entity)]
    [:<>
     [:dt.synset-diagram__footer-item {:id "examples"}
      (i18n/da-en languages
        "Eksempler"
        "Examples")]
     [:dd.synset-diagram__footer-item
      (rdf/resource (assoc opts :attr-key :lexinfo/senseExample) v)]]))

(rum/defc ancestry-dt+dd
  [{:keys [languages entity detail-level ancestry] :as opts}]
  (let [label       (str (:rdfs/label entity))
        short-label (some-> (:dns/shortLabel entity) str)
        subj-label  (if (= detail-level :high) label (or short-label label))]
    [:<>
     [:dt.synset-diagram__footer-item {:id "ancestry"}
      (i18n/da-en languages
        "Overbegreber"
        "Hypernyms")]
     [:dd.synset-diagram__footer-item
      (rdf/hypernym-chain subj-label ancestry opts)]]))

(rum/defc synset-metadata
  "The ontological type, constituents, examples and hypernyms of the synset in
  `opts`, shown beside the diagram in full-screen mode."
  [{:keys [entity languages] :as opts}]
  [:aside.synset-diagram__metadata
   [:dl
    [:dt (i18n/da-en languages
           "Ontologisk type"
           "Ontological type")]
    [:dd
     (let [onto-type (:dns/ontologicalType entity)
           onto-coll (some-> onto-type meta rdf/bag->coll)]
       (cond
         (keyword? onto-type)
         (rdf/resource-hyperlink onto-type
                                 (assoc opts :attr-key :dns/ontologicalType))

         onto-coll
         (rdf/list-items (assoc opts :attr-key :dns/ontologicalType)
                         onto-coll)

         :else "–"))]
    [:dt (i18n/da-en languages
           "Bestanddele"
           "Constituents")]
    [:dd (rdf/resource
           (assoc opts :attr-key :ontolex/lexicalizedSense)
           (:ontolex/lexicalizedSense entity))]
    (examples-dt+dd opts)
    (ancestry-dt+dd opts)]])

(defn diagram-mode
  "The diagram mode selected in `opts`, or :radial when its tree in `trees`
  has no children."
  [trees opts]
  (let [mode (get-in opts app/diagram-mode-path)]
    (if (:children (get trees mode))
      mode
      :radial)))

(rum/defc mode-radio
  "The radio button labelled `label` that switches the synset diagram to
  `mode`, checked when `mode` is the `current` one."
  [mode current label]
  [:label
   [:input {:type      "radio"
            :name      "viz-mode"
            :checked   (= mode current)
            :on-change (fn [_] (swap! app/session assoc-in
                                      app/diagram-mode-path mode))}]
   label])

(rum/defcs synset-diagram
  "The toolbar, diagram and legend for `subentity`: the radial diagram or a
  sunburst, by the diagram mode in `opts`."
  < (rum/local nil ::nav)
  [state subentity {:keys [languages full-screen hyponym-tree
                           orthogonal-hyponym-tree meronym-tree] :as opts}]
  (let [trees     {:sunburst            hyponym-tree
                   :sunburst-orthogonal orthogonal-hyponym-tree
                   :sunburst-meronym    meronym-tree}
        mode      (diagram-mode trees opts)
        tree      (get trees mode)
        sunburst? (some? tree)
        ;; Component-local breadcrumb atom: the sunburst builder writes the zoom
        ;; trail to it and the history legend reacts to it, so the two share
        ;; state without a module-global (and a fresh diagram starts clean).
        opts      (assoc opts :sunburst-nav (::nav state))
        caption   (if (= mode :sunburst-meronym)
                    (i18n/da-en languages
                      "Meronym-soldiagram for det aktuelle synset"
                      "Meronym sunburst for the current synset")
                    (i18n/da-en languages
                      "Hyponym-soldiagram for det aktuelle synset"
                      "Hyponym sunburst for the current synset"))]
    [:div.synset-diagram {:key (str (hash subentity))}
     ;; Shared top bar above both columns: mode radios centred, full-screen
     ;; toggle pinned right. Keeping it out of the diagram column means swapping
     ;; diagrams no longer shifts the radios.
     [:div.synset-diagram__toolbar
      [:fieldset.viz-mode-toggle
       [:legend (i18n/da-en languages "Diagram" "Diagram")]
       (mode-radio :radial mode
                   (i18n/da-en languages "relationer" "relations"))
       (when (:children hyponym-tree)
         (mode-radio :sunburst mode
                     (i18n/da-en languages "underbegreber" "hyponyms")))
       (when (:children orthogonal-hyponym-tree)
         (mode-radio :sunburst-orthogonal mode
                     (i18n/da-en languages
                       "orto-underbegreber" "ortho-hyponyms")))
       (when (:children meronym-tree)
         (mode-radio :sunburst-meronym mode
                     (i18n/da-en languages "dele" "parts")))]
      (full-screen-toggle opts)]
     [:div.synset-diagram__body
      (when full-screen
        (synset-metadata opts))
      [:div.synset-diagram__main
       (if sunburst?
         (sunburst-diagram tree caption opts)
         (radial-tree-diagram subentity opts))]
      ;; Keep a right-hand column in both modes so the diagram footprint stays
      ;; stable; in sunburst mode it's the zoom history for stepping back up.
      (if sunburst?
        (sunburst-legend opts)
        (radial-tree-legend subentity opts))]]))

(rum/defc expanded-diagram
  "The diagram of the relations in `subentity`, with the examples and
  hypernyms of the synset in `opts` below it."
  [subentity {:keys [entity ancestry] :as opts}]
  [:div.synset-diagram-container {:key (str (hash subentity))}
   (synset-diagram subentity opts)
   (let [{:keys [lexinfo/senseExample]} entity]
     (when (or senseExample ancestry)
       [:footer.synset-diagram__footer
        [:dl
         (when senseExample
           (examples-dt+dd opts))
         (when ancestry
           (ancestry-dt+dd opts))]]))])
