(ns dk.cst.dannet.web.ui.visualization
  "The synset diagrams (radial and sunbursts) with their mode bar and legends,
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

(rum/defc sunburst-legend < rum/reactive
  [{:keys [languages sunburst-nav] :as opts}]
  (let [trail #?(:cljs (when sunburst-nav (rum/react sunburst-nav))
                 :clj nil)]
    ;; the same fieldset as the radial legend, so the two columns line up
    [:fieldset.radial-tree-legend
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
                trail))])]))

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
    [:fieldset.radial-tree-legend
     {:aria-label (i18n/da-en languages
                    "Filtrer relationstyper"
                    "Filter relation types")}
     [:ul.radial-tree-legend
      ;; Relations are ordered by the canonical relations/group-order so the
      ;; legend matches the diagram, and the <li> that begins a new group is
      ;; marked so the grouping can be styled in CSS without splitting the list.
      (let [ks (->> (keys subentity)
                    (filter shared/synset-rel-theme)
                    (sort-by #(relations/relation-sort-key % (label-of %))))]
        (for [[prev k] (map vector (cons nil ks) ks)]
          (let [theme        (get shared/synset-rel-theme k)
                label        (label-of k)
                is-selected? (= @selected theme)]
            [:li (cond-> {:key k}
                   (and prev (not= (relations/relation->group prev)
                                   (relations/relation->group k)))
                   (assoc :class "rel-group-begin"))
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
                     :style {:background theme}}]]])))]]))

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
     (if-let [onto-type (:dns/ontologicalType entity)]
       (rdf/value (assoc opts :attr-key :dns/ontologicalType) onto-type)
       "–")]
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
  "The diagram for `subentity` with its mode bar below it, and its legend: the
  radial diagram or a sunburst, by the diagram mode in `opts`."
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
    [:div.synset-diagram
     (when full-screen
       (synset-metadata opts))
     [:div.synset-diagram__main
      ;; The figure keeps the same footprint in every mode, so the mode bar
      ;; right below it stays put when switching diagrams.
      [:div.synset-diagram__figure
       (if sunburst?
         (sunburst-diagram tree caption opts)
         (radial-tree-diagram subentity opts))]
      [:fieldset.viz-mode-toggle
       [:legend.visually-hidden (i18n/da-en languages "Diagram" "Diagram")]
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
                     (i18n/da-en languages "dele" "parts")))]]
     ;; Keep a right-hand column in both modes so the diagram footprint stays
     ;; stable; in sunburst mode it's the zoom history for stepping back up.
     (if sunburst?
       (sunburst-legend opts)
       (radial-tree-legend subentity opts))]))

(defn- on-escape
  "A keydown handler that leaves full-screen mode on Escape."
  [e]
  (when (= "Escape" (.-key e))
    (app/toggle-full-screen!)))

(def full-screen-target
  "The centres of the diagrams that toggle full-screen mode, radial or sunburst."
  ".toggle-full-screen, .hyponym-sunburst__center-target")

(rum/defc full-screen-close
  "The button that leaves full-screen mode, which Escape presses too. It takes
  the focus on entering full screen, so the way out is where the keyboard is,
  and gives it back to the diagram's centre on leaving."
  < {:did-mount    (fn [state]
                     #?(:cljs (do (.focus (rum/dom-node state))
                                  (js/document.addEventListener "keydown" on-escape)))
                     state)
     :will-unmount (fn [state]
                     ;; The page's diagram is only drawn once this unmount is
                     ;; done, and the page itself stays scrolled to the top.
                     #?(:cljs (do (js/document.removeEventListener "keydown" on-escape)
                                  (js/setTimeout
                                    #(some-> (js/document.querySelector full-screen-target)
                                             (.focus #js {:preventScroll true}))
                                    0)))
                     state)}
  [{:keys [languages]}]
  ;; The title is both the tooltip and, with no text in the button, its name.
  [:button.full-screen-close
   {:type     "button"
    :title    (i18n/da-en languages "Luk fuld skærm" "Exit full screen")
    :on-click (fn [_] (app/toggle-full-screen!))}])

(rum/defc expanded-diagram
  "The diagram of the relations in `subentity`; in full-screen mode, with a
  button to leave it and the examples and hypernyms of the synset in `opts`."
  [subentity {:keys [entity ancestry full-screen] :as opts}]
  [:div.synset-diagram-container
   (when full-screen
     (full-screen-close opts))
   ;; A new subentity starts a fresh diagram, e.g. with no legend filter.
   (rum/with-key (synset-diagram subentity opts) (str (hash subentity)))
   (let [{:keys [lexinfo/senseExample]} entity]
     (when (and full-screen (or senseExample ancestry))
       [:footer.synset-diagram__footer
        [:dl
         (when senseExample
           (examples-dt+dd opts))
         (when ancestry
           (ancestry-dt+dd opts))]]))])
