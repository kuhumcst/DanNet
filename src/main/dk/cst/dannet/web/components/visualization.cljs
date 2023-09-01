(ns dk.cst.dannet.web.components.visualization
  "Visualisation components and associated functions; frontend only!"
  (:require [clojure.math :as math]
            [clojure.string :as str]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.shared :as shared]
            [rum.core :as rum]
            ["d3" :as d3]
            ["d3-cloud" :as cloud]))

(defn length-penalty
  [label size]
  (/ size (max 1 (math/log10 (count label)))))

(def colours
  (atom
    (cycle
      ["#901a1e"
       "#55f"
       "#019fa1"
       "#df7300"
       "#387111"
       #_"#333"])))

(defn next-colour
  []
  (first (swap! colours rest)))

(defn prepare-synset-cloud
  "Prepare `synsets` for display in a word cloud using the provided weights in
  `opts` as well the current `width` of the containing element."
  [{:keys [k->label cloud-limit synset-weights] :as opts} synsets]
  (let [max-size 36
        weights  (select-keys synset-weights synsets)
        n        (count weights)
        min-size (min 0.15 (/ 10 n))
        weights' (if (<= n 10)
                   (update-vals weights (constantly (/ 1 (math/cbrt n))))
                   (shared/normalize (if cloud-limit
                                       (into {} (->> (sort-by second weights)
                                                     (reverse)
                                                     (take cloud-limit)))
                                       weights)))
        text     (fn [k]
                   (or (get k->label k)
                       (when (keyword? k)
                         (prefix/kw->qname k))))]
    (->> synsets
         (mapcat (fn [k]
                   (when-let [weight (get weights' k)]
                     (let [t          (str (text k))
                           labels     (shared/sense-labels "; " (when t
                                                                  (-> t
                                                                      (str/replace #"\{|\}" "")
                                                                      (str/replace #"_[^; ]+" ""))))

                           n          (count labels)
                           ;; To facilitate some heavy-weight synsets having many
                           ;; labels, we need to diminish the final size by a
                           ;; certain factor. Kinda winging this diminisher value,
                           ;; but it is extremely critical!
                           diminisher (if (> n 1)
                                        (math/cbrt n)
                                        1)
                           size       (-> (+ weight min-size)
                                          ;; The `max-size` and the `diminisher` balance each other out. The goal is being
                                          ;; able to fit as much information into the allotted space.
                                          (/ diminisher)
                                          (* max-size))]
                       (for [label labels]
                         {:text  (str " " label " ")        ; adding this margin seems to improve layout
                          :title label
                          :href  (prefix/resolve-href k)
                          :size  (length-penalty label size)})))))

         ;; Favour the largest weights in case all words can't fit!
         (sort-by :size)
         (reverse))))

(defn build-cloud!
  [state {:keys [cloud-limit] :as opts} synsets node]
  (when (and node (not= @state [cloud-limit synsets]))
    ;; Always start by clearing the old contents.
    (when-let [existing-svg (.-firstChild node)]
      (.remove existing-svg))
    (let [width  (.-offsetWidth (.-parentElement node))
          words  (prepare-synset-cloud opts synsets)
          ;; TODO: need a better heuristic for height
          height (min
                   (max (* (count words) 4) 128)
                   width)
          draw   (fn [words]
                   (-> d3

                       ;; Adding <svg> and <g> (group) container elements.
                       (.select node)
                       (.append "svg")
                       (.attr "width" width)
                       (.attr "height" height)
                       (.append "g")
                       (.attr "transform" (str "translate(" (/ width 2)
                                               "," (/ height 2) ")"))

                       ;; Styling/adding interactivity to all <text> elements.
                       (.selectAll "text")
                       (.data words)
                       (.join "text")
                       (.style "font-size" (fn [d] (str (.-size d) "px")))
                       (.style "font-family" "Georgia")
                       (.style "text-shadow" "1px 0px rgba(255,255,255,0.8)")
                       (.style "fill" next-colour)
                       (.attr "class" "word-cloud-item")
                       (.attr "text-anchor" "middle")
                       (.attr "transform"
                              (fn [d]
                                (str "translate(" (.-x d) "," (.-y d) ")"
                                     "rotate(0)")))
                       (.text (fn [d] (.-text d)))
                       (.on "click" (fn [_ d]
                                      (shared/navigate-to (.-href d))))

                       ;; Adding mouseover text (in lieu of a title attribute)
                       (.append "title")
                       (.text (fn [d] (.-title d)))))

          ;; The cloud layout itself is created using d3-cloud.
          layout (-> (cloud)
                     (.size #js [width height])
                     (.words (clj->js words))
                     (.padding 8)
                     (.rotate (fn [] 0))
                     (.font "Georgia")
                     (.fontSize (fn [d] (.-size d)))
                     (.on "end" draw))]
      (.start layout))
    (reset! state [cloud-limit synsets])))

(rum/defcs word-cloud < (rum/local nil ::synsets)
  [state {:keys [cloud-limit] :as opts} synsets]
  [:div {:key (str (hash synsets) "-" cloud-limit)
         :ref #(build-cloud! (::synsets state) opts synsets %)}])
