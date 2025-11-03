(ns dk.cst.dannet.web.ui.visualization
  "Visualisation components and associated functions; frontend only!"
  (:require [clojure.string :as str]
            [rum.core :as rum]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.web.i18n :as i18n]
            #?(:cljs [dk.cst.dannet.web.d3 :as d3])))

(rum/defcs word-cloud < (rum/local nil ::synsets)
  [state synsets {:keys [cloud-limit] :as opts}]
  [:div {:key (str (hash synsets) "-" cloud-limit)
         :ref #?(:clj  nil
                 :cljs #(d3/build-cloud! (::synsets state) opts synsets %))}])

(rum/defc radial-tree-diagram
  [subentity opts]
  [:div.radial-tree-diagram
   {:ref #?(:clj  nil
            :cljs (fn [elem]
                    (let [{:keys [scroll]} @shared/post-navigate]
                      (when elem
                        (d3/build-radial! subentity elem opts)
                        (when (= scroll :diagram)
                          (if (.-scrollIntoViewIfNeeded elem)
                            (.scrollIntoViewIfNeeded (.-parentElement elem))
                            (.scrollIntoView (.-parentElement elem))))))))}])

(defn- elem-classes
  [el]
  (set (str/split (.getAttribute el "class") #" ")))

(defn- apply-classes
  [el classes]
  (.setAttribute el "class" (str/join " " classes)))

(def radial-tree-selector
  ".radial-tree-nodes [fill],
  .radial-tree-links [stroke],
  .radial-tree-labels [data-theme]")

;; TODO: make this less hacky
(defn- get-diagram
  [e]
  (.-previousSibling (.-parentElement (.-parentElement (.-parentElement (.-target e))))))

;; Inspiration for checkboxes: https://www.w3schools.com/howto/tryit.asp?filename=tryhow_css_custom_checkbox
(rum/defcs radial-tree-legend < (rum/local nil ::selected)
  [state subentity {:keys [languages k->label] :as opts}]
  (let [selected (::selected state)]
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
                      :on-click  (fn [e]
                                   (let [new-selection (if is-selected? nil theme)
                                         diagram       (get-diagram e)]
                                     (reset! selected new-selection)
                                     (doseq [el (.querySelectorAll diagram radial-tree-selector)]
                                       (let [classes (elem-classes el)
                                             show?   (or (nil? new-selection)
                                                         (= new-selection (.getAttribute el "stroke"))
                                                         (= new-selection (.getAttribute el "fill"))
                                                         (= new-selection (.getAttribute el "data-theme"))
                                                         (get classes "radial-item__subject"))]
                                         (if show?
                                           (apply-classes el (disj classes "radial-item__de-emphasized"))
                                           (apply-classes el (conj classes "radial-item__de-emphasized")))))))}]
             [:span {:class "radial-tree-legend__bullet"
                     :style {:background theme}}]]])))]))

(rum/defc radial-tree
  [subentity opts]
  [:div.radial-tree {:key (str (hash subentity))}
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

(rum/defc expanded-radial < (debounced-rerender-mixin 200)
  [subentity {:keys [languages entity] :as opts}]
  (let [toggle (fn [_]
                 #?(:cljs (swap! shared/state update :full-screen? not)))
        {:keys [full-screen?]} @shared/state]
    [:div.synset-radial-container {:key (str (hash subentity))}
     ;; TODO: consider whether to only show the header in full-screen
     [:div.synset-radial__header
      [:strong.pos-label (pos-label opts)]
      [:span.synset-radial__definition
       (str (i18n/select-label languages (:skos/definition entity)))]
      [:button.icon {:class    (if full-screen?
                                 "minimize"
                                 "maximize")
                     :on-click toggle}]]
     (radial-tree subentity opts)
     ;; TODO: add content to footer
     [:div.synset-radial__footer]]))
