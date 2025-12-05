(ns dk.cst.dannet.web.ui.visualization
  "Visualisation components and associated functions; frontend only!"
  (:require [clojure.string :as str]
            [rum.core :as rum]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.web.i18n :as i18n]
            [dk.cst.dannet.web.ui.rdf :as rdf]
            #?(:cljs [dk.cst.dannet.web.d3 :as d3])))

(rum/defcs word-cloud < (rum/local nil ::synsets)
  [state synsets {:keys [cloud-limit] :as opts}]
  ;; Key on displayed items only, so deferred data doesn't trigger re-render
  ;; when cloud-limit caps what's shown anyway.
  (let [displayed (if cloud-limit (take cloud-limit synsets) synsets)]
    [:div {:key (hash displayed)
           :ref #?(:clj  nil
                   :cljs #(d3/build-cloud! (::synsets state) opts synsets %))}]))

(rum/defc radial-tree-diagram
  [subentity opts]
  [:div.radial-tree-diagram
   {:ref #?(:clj  nil
            :cljs (fn [elem]
                    (when elem
                      (d3/build-radial! subentity elem opts))))}])

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
  [state subentity {:keys [languages k->label] :as opts}]
  (let [selected (::selected state)]
    [:div.radial-tree-legend-container
     [:ul.radial-tree-legend
      (for [k (keys subentity)]
        (when-let [theme (get shared/synset-rel-theme k)]
          (let [label        (i18n/select-label languages (k->label k))
                is-selected? (= @selected theme)]
            [:li {:key k}
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
                                                          (get classes "radial-item__subject"))]
                                          (if show?
                                            (apply-classes el (disj classes "radial-item__de-emphasized"))
                                            (apply-classes el (conj classes "radial-item__de-emphasized")))))))}]
              [:span {:class "radial-tree-legend__bullet"
                      :style {:background theme}}]]])))]]))

;; TODO: use a heuristic for high-lighting the relevant word
(rum/defc examples-dt+dd
  [{:keys [entity languages] :as opts}]
  (when-let [v (:lexinfo/senseExample entity)]
    [:<>
     [:dt.synset-radial__footer-item
      (i18n/da-en languages
        "Eksempler"
        "Examples")]
     [:dd.synset-radial__footer-item
      (rdf/resource (assoc opts :attr-key :lexinfo/senseExample) v)]]))

(rum/defc ancestry-dt+dd
  [{:keys [languages entity details?] :as opts}]
  (let [label       (str (:rdfs/label entity))
        short-label (some-> (:dns/shortLabel entity) str)
        subj-label  (if details? label (or short-label label))]
    [:<>
     [:dt.synset-radial__footer-item
      (i18n/da-en languages
        "Overbegreber"
        "Hypernyms")]
     [:dd.synset-radial__footer-item
      (rdf/hypernym-chain (assoc opts :subject-label subj-label))]]))

(rum/defc radial-tree
  [subentity {:keys [entity languages full-screen] :as opts}]
  [:div.radial-tree {:key (str (hash subentity))}
   (when full-screen
     [:<>
      [:div.synset-radial__metadata
       [:dl
        [:dt (i18n/da-en languages
               "Ontologisk type"
               "Ontological type")]
        [:dd
         (if-let [v (meta (:dns/ontologicalType entity))]
           (rdf/resource (assoc opts :attr-key :dns/ontologicalType) v)
           "–")]
        [:dt (i18n/da-en languages
               "Tilknyttede ord"
               "Associated words")]
        [:dd (rdf/resource
               (assoc opts :attr-key :ontolex/lexicalizedSense)
               (:ontolex/lexicalizedSense entity))]
        (examples-dt+dd opts)
        (ancestry-dt+dd opts)]]])
   (radial-tree-diagram subentity opts)
   (radial-tree-legend subentity opts)])

(rum/defc pos-label
  [{:keys [languages entity] :as opts}]
  (let [lexfile (:wn/lexfile entity)
        pos     (shared/lexfile->pos lexfile)]
    [:strong {:title lexfile}
     (i18n/da-en languages
       (get {"noun" "substantiv"
             "adj"  "adjektiv"
             "adv"  "adverbium"
             "verb" "verbum"} pos)
       (get {"noun" "noun"
             "adj"  "adjective"
             "adv"  "adverb"
             "verb" "verb"} pos))]))

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
(rum/defc expanded-radial < (debounced-rerender-mixin 200)
  [subentity {:keys [languages entity full-screen ancestry] :as opts}]
  (let [toggle (fn [_]
                 #?(:cljs (do
                            (shared/update-cookie! :full-screen not)
                            ;; Scrolling to the top simulates a page change.
                            (some-> (js/document.getElementById "content")
                                    (.scroll #js {:top 0})))))]
    [:div.synset-radial-container {:key (str (hash subentity))}
     ;; TODO: consider whether to only show the header in full-screen
     [:div.synset-radial__header
      [:span.synset-radial__definition
       [:strong.pos-label (pos-label opts)]
       (str (i18n/select-label languages (:skos/definition entity)))]
      [:button.icon {:class    (if full-screen
                                 "minimize"
                                 "maximize")
                     :on-click toggle}]]
     (radial-tree subentity opts)
     (let [{:keys [lexinfo/senseExample]} entity]
       (when (or senseExample ancestry)
         [:div.synset-radial__footer
          [:dl
           (when senseExample
             (examples-dt+dd opts))
           (when ancestry
             (ancestry-dt+dd opts))]]))]))
