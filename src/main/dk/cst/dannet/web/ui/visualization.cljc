(ns dk.cst.dannet.web.ui.visualization
  "Visualisation components and associated functions; frontend only!"
  (:require [clojure.string :as str]
            [rum.core :as rum]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.web.i18n :as i18n]
            #?(:cljs [dk.cst.dannet.web.d3 :as d3])))

(rum/defcs word-cloud < (rum/local nil ::synsets)
  [state {:keys [cloud-limit] :as opts} synsets]
  [:div {:key (str (hash synsets) "-" cloud-limit)
         :ref #?(:clj  nil
                 :cljs #(d3/build-cloud! (::synsets state) opts synsets %))}])

(rum/defcs radial-tree < (rum/local nil ::subentity)
  [state {:keys [languages k->label] :as opts} subentity]
  [:div.radial-tree-diagram
   {:ref #?(:clj  nil
            :cljs (fn [el]
                    (let [{:keys [scroll]} @shared/post-navigate]
                      (when el
                        (d3/build-radial! (::subentity state) opts subentity el)
                        (when (= scroll :diagram)
                          (if (.-scrollIntoViewIfNeeded el)
                            (.scrollIntoViewIfNeeded (.-parentElement el))
                            (.scrollIntoView (.-parentElement el))))))))}])


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

(defn- get-diagram
  [e]
  (.-previousSibling (.-parentElement (.-parentElement (.-parentElement (.-target e))))))

;; Inspiration for checkboxes: https://www.w3schools.com/howto/tryit.asp?filename=tryhow_css_custom_checkbox
(rum/defcs radial-tree-legend < (rum/local nil ::selected)
  [state {:keys [languages k->label] :as opts} subentity]
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
