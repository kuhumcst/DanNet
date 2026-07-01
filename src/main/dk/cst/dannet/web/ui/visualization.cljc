(ns dk.cst.dannet.web.ui.visualization
  "Visualisation components and associated functions; frontend only!"
  (:require [clojure.string :as str]
            [rum.core :as rum]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.web.i18n :as i18n]
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

(rum/defcs word-cloud < (rum/local nil ::synsets)
  [state synsets {:keys [cloud-limit] :as opts}]
  ;; Key on displayed items only, so deferred data doesn't trigger re-render
  ;; when cloud-limit caps what's shown anyway.
  (let [displayed (if cloud-limit (take cloud-limit synsets) synsets)
        opts'     (viz-opts opts)]
    [:div {:key (hash displayed)
           :ref #?(:clj  nil
                   :cljs #(d3/build-cloud! (::synsets state) opts' synsets %))}]))

(rum/defc radial-tree-diagram
  [subentity {:keys [languages] :as opts}]
  (let [opts' (viz-opts opts)]
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

(rum/defc hyponym-sunburst-diagram
  [tree {:keys [languages sunburst-nav] :as opts}]
  [:figure.hyponym-sunburst-diagram
   {:ref #?(:clj  nil
            :cljs (fn [elem]
                    (when elem
                      (d3/build-sunburst! tree elem sunburst-nav opts))))}
   ;; Names the figure for assistive tech; the SVG itself is built in the ref
   ;; callback and also carries role="img" + a label.
   [:figcaption.visually-hidden
    (i18n/da-en languages
      "Hyponym-soldiagram for det aktuelle synset"
      "Hyponym sunburst for the current synset")]])

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
                 :on-click (fn [_] (shared/toggle-full-screen!))}])

(rum/defc diagram-legend
  "Right-hand column shell shared by the radial and sunburst diagrams: holds the
  mode-specific `content` in a fieldset that lines up across modes. The
  full-screen toggle lives in the shared top bar, not here."
  [attrs content]
  [:fieldset.radial-tree-legend attrs
   content])

(rum/defc hyponym-sunburst-legend < rum/reactive
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

(defn- elem-classes
  [el]
  (if-let [class-attr (.getAttribute el "class")]
    (set (str/split class-attr #" "))
    #{}))

(defn- apply-classes
  [el classes]
  (.setAttribute el "class" (str/join " " classes)))

(def radial-tree-selector
  ".radial-tree-nodes [data-theme],
  .radial-tree-links [data-theme],
  .radial-tree-labels [data-theme]")

(defn- get-diagram
  []
  #?(:cljs (js/document.querySelector ".radial-tree-diagram")))

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
                        :on-click  (fn [_]
                                     (let [new-selection (if is-selected? nil theme)
                                           diagram       (get-diagram)]
                                       (reset! selected new-selection)
                                       (doseq [el (.querySelectorAll diagram radial-tree-selector)]
                                         (let [classes (elem-classes el)
                                               show?   (or (nil? new-selection)
                                                           (= new-selection (.getAttribute el "data-theme"))
                                                           (get classes "radial-item--subject"))]
                                           (if show?
                                             (apply-classes el (disj classes "radial-item--de-emphasized"))
                                             (apply-classes el (conj classes "radial-item--de-emphasized")))))))}]
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
  [{:keys [languages entity detail-level] :as opts}]
  (let [label       (str (:rdfs/label entity))
        short-label (some-> (:dns/shortLabel entity) str)
        subj-label  (if (= detail-level :high) label (or short-label label))]
    [:<>
     [:dt.synset-diagram__footer-item {:id "ancestry"}
      (i18n/da-en languages
        "Overbegreber"
        "Hypernyms")]
     [:dd.synset-diagram__footer-item
      (rdf/hypernym-chain (assoc opts :subject-label subj-label))]]))

(rum/defcs synset-diagram < (rum/local nil ::nav)
  [state subentity {:keys [entity languages full-screen hyponym-tree
                           orthogonal-hyponym-tree] :as opts}]
  (let [hyponym-available? (boolean (:children hyponym-tree))
        ortho-available?   (boolean (:children orthogonal-hyponym-tree))
        ;; Fall back to the radial when the selected sunburst has nothing to show.
        mode               (case (get-in opts shared/diagram-mode-path)
                             :sunburst-orthogonal (if ortho-available?
                                                    :sunburst-orthogonal
                                                    :radial)
                             :sunburst (if hyponym-available? :sunburst :radial)
                             :radial)
        tree               (case mode
                             :sunburst-orthogonal orthogonal-hyponym-tree
                             :sunburst hyponym-tree
                             nil)
        sunburst?          (some? tree)
        ;; Component-local breadcrumb atom: the sunburst builder writes the zoom
        ;; trail to it and the history legend reacts to it, so the two share
        ;; state without a module-global (and a fresh diagram starts clean).
        opts               (assoc opts :sunburst-nav (::nav state))
        set-mode!          (fn [mode]
                             (fn [_] (swap! shared/state assoc-in
                                            shared/diagram-mode-path mode)))]
    [:div.synset-diagram {:key (str (hash subentity))}
     ;; Shared top bar above both columns: mode radios centred, full-screen
     ;; toggle pinned right. Keeping it out of the diagram column means swapping
     ;; diagrams no longer shifts the radios.
     [:div.synset-diagram__toolbar
      [:fieldset.viz-mode-toggle
       [:legend (i18n/da-en languages "Diagram" "Diagram")]
       [:label
        [:input {:type      "radio"
                 :name      "viz-mode"
                 :checked   (= mode :radial)
                 :on-change (set-mode! :radial)}]
        (i18n/da-en languages "relationer" "relations")]
       (when hyponym-available?
         [:label
          [:input {:type      "radio"
                   :name      "viz-mode"
                   :checked   (= mode :sunburst)
                   :on-change (set-mode! :sunburst)}]
          (i18n/da-en languages "underbegreber" "hyponyms")])
       (when ortho-available?
         [:label
          [:input {:type      "radio"
                   :name      "viz-mode"
                   :checked   (= mode :sunburst-orthogonal)
                   :on-change (set-mode! :sunburst-orthogonal)}]
          (i18n/da-en languages "orto-underbegreber" "ortho-hyponyms")])]
      (full-screen-toggle opts)]
     [:div.synset-diagram__body
      (when full-screen
        [:aside.synset-diagram__metadata
         [:dl
          [:dt (i18n/da-en languages
                 "Ontologisk type"
                 "Ontological type")]
          [:dd
           (if-let [onto-types (some-> (:dns/ontologicalType entity)
                                       meta
                                       shared/bag->coll)]
             (rdf/list-items (assoc opts :attr-key :dns/ontologicalType) onto-types)
             "–")]
          [:dt (i18n/da-en languages
                 "Bestanddele"
                 "Constituents")]
          [:dd (rdf/resource
                 (assoc opts :attr-key :ontolex/lexicalizedSense)
                 (:ontolex/lexicalizedSense entity))]
          (examples-dt+dd opts)
          (ancestry-dt+dd opts)]])
      [:div.synset-diagram__main
       (if sunburst?
         (hyponym-sunburst-diagram tree opts)
         (radial-tree-diagram subentity opts))]
      ;; Keep a right-hand column in both modes so the diagram footprint stays
      ;; stable; in sunburst mode it's the zoom history for stepping back up.
      (if sunburst?
        (hyponym-sunburst-legend opts)
        (radial-tree-legend subentity opts))]]))

(defn debounced-rerender-mixin
  "Debounced rerender on resize events to prevent excessive diagram repaints."
  [debounce-millis]
  {:did-mount
   (fn [state]
     #?(:cljs
        (let [comp     (:rum/react-component state)
              rerender (atom nil)
              ;; The observer resets the scheduled rerender on each resize.
              observer (js/ResizeObserver.
                         (fn []
                           (js/clearTimeout @rerender)
                           (reset! rerender
                                   (js/setTimeout
                                     #(rum/request-render comp)
                                     debounce-millis))))]
          (.observe observer (rum/dom-node state))
          (assoc state ::observer observer ::rerender rerender))
        :clj state))

   :will-unmount
   (fn [state]
     #?(:cljs
        (do
          (js/clearTimeout @(::rerender state))
          ;; The observer is explicitly disconnected to prevent memory leaks.
          (when-let [observer (::observer state)]
            (.disconnect observer))
          (dissoc state ::observer ::rerender))
        :clj state))})

;; TODO: display ancestry and examples in full-screen mode
(rum/defc expanded-diagram < (debounced-rerender-mixin 200)
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
