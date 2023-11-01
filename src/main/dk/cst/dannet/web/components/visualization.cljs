(ns dk.cst.dannet.web.components.visualization
  "Visualisation components and associated functions; frontend only!"
  (:require [clojure.math :as math]
            [clojure.string :as str]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.web.i18n :as i18n]
            [rum.core :as rum]
            ["d3" :as d3]
            ["d3-cloud" :as cloud]))

(defn length-penalty
  "Calculate a new size with a penalty based on the size of the `label` and the
  true `size` of the word to make longer words fit the cloud a bit better."
  [label size]
  (/ size (max 1 (math/log10 (count label)))))

(def colours
  (atom (cycle shared/theme)))

(defn next-colour
  []
  (first (swap! colours rest)))

;; TODO: figure out how to include subscript
(defn clean-text
  [s]
  (-> (str s)
      (str/replace #"\{|\}" "")
      #_(str/replace #"_[^; ]+" "")))

(defn remove-parens
  [s]
  (str/replace (str s) #"\{|\}" ""))

(def label-section
  #"_([^; ,]+)")

(defn clean-subscript
  [s]
  (str/replace (str s) label-section " $1"))

(defn remove-subscript
  [s]
  (str/replace (str s) label-section ""))

(def clean-label
  (comp clean-subscript remove-parens))

(def labels-only
  (comp remove-subscript remove-parens))

(defn take-top
  [n weights]
  (->> (sort-by second weights)
       (reverse)
       (take n)
       (into {})))

(defn prepare-synset-cloud
  "Prepare `synsets` for word cloud display using the provided info in `opts`."
  [{:keys [k->label cloud-limit synset-weights] :as opts} synsets]
  (let [max-size  36
        weights   (select-keys synset-weights synsets)
        weights'  (shared/cloud-normalize
                    (if cloud-limit
                      (take-top cloud-limit weights)
                      weights))
        n         (count weights')
        min-size  (min 0.33 (/ 10 n))
        highlight (:highlight (meta weights'))
        k->s      (fn [k]
                    (str (or (get k->label k)
                             (when (keyword? k)
                               (prefix/kw->qname k)))))]
    (->> synsets
         (mapcat (fn [k]
                   (when-let [weight (get weights' k)]
                     (let [s      (remove-parens (k->s k))
                           labels (shared/sense-labels "; " s)
                           n      (count labels)
                           size   (* (max (/ weight (max 1 (/ n 3)))
                                          min-size)
                                     max-size)]
                       (for [label labels]
                         (let [[s word rest-of-s sub mwe]
                               (re-matches shared/sense-label label)]
                           ;; adding spaces to the label seems to improve layout
                           {:text      (str " " word)
                            :title     (str/replace s #"_" " ")
                            :sub       (str sub " ")
                            :highlight (boolean (get highlight k))
                            :href      (prefix/resolve-href k)
                            :size      (length-penalty (str word sub) size)}))))))

         ;; Favour the largest weights in case all words can't fit!
         (sort-by :size)
         (reverse))))

(defn content-width
  "Get the width of a `node`, excluding its padding and border."
  [node]
  (let [style (js/window.getComputedStyle node)]
    (- (.-clientWidth node)
       (js/parseFloat (.-paddingLeft style))
       (js/parseFloat (.-paddingRight style)))))

;; TODO: fix, doesn't seem to add extra width to text when used
(defn- add-sub
  [text]
  (-> text
      (.append "tspan")
      (.attr "class" "sense-paragraph")
      (.attr "dy" "4px")
      (.text (fn [d]
               (.-sub d))))
  text)

(defn build-cloud!
  [state {:keys [cloud-limit] :as opts} synsets node]
  (when (and node (not= @state [cloud-limit synsets]))
    ;; Always start by clearing the old contents.
    (when-let [existing-svg (.-firstChild node)]
      (.remove existing-svg))
    (let [width  (content-width (.-parentElement node))
          words  (prepare-synset-cloud opts synsets)
          height (min
                   (max (* (count words) 6) 128)
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
                       (.attr "class" (fn [d]
                                        (if (.-highlight d)
                                          "word-cloud-item word-cloud-item__top"
                                          "word-cloud-item")))
                       (.attr "text-anchor" "middle")
                       (.attr "transform"
                              (fn [d]
                                (str "translate(" (.-x d) "," (.-y d) ")")))
                       (.text (fn [d] (.-text d)))
                       (.on "click" (fn [_ d]
                                      (shared/navigate-to (.-href d))))

                       ;; Insert subscript paragraph, returning parent text
                       #_(add-sub)

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

(defn by-sense-label
  [{:keys [name] :as m}]
  (for [[s word rest-of-s sub mwe] (->> (shared/sense-labels shared/synset-sep name)
                                        (shared/canonical)
                                        (map #(re-matches shared/sense-label %)))]
    (assoc m
      :name word
      :sub sub)))

(def radial-limit
  48)

;; TODO: use existing theme colours, but vary strokes and final symbols
;; Based on https://observablehq.com/@d3/radial-tree/2
(defn build-radial!
  [state {:keys [label languages k->label synset-weights subject] :as opts} entity node]
  (when node
    ;; Always start by clearing the old contents.
    (when-let [existing-svg (.-firstChild node)]
      (.remove existing-svg))
    (let [width     (content-width (.-parentElement node))
          height    width

          subject   (->> (shared/sense-labels shared/synset-sep label)
                         (shared/canonical)
                         (map remove-subscript)
                         (set)                              ; fixes e.g. http://localhost:3456/dannet/data/synset-2500
                         (sort-by count)
                         (str/join ", "))
          k->label' (comp
                      #_clean-text
                      (partial i18n/select-label languages)
                      k->label)
          entity'   (->> (shared/weight-sort-fn synset-weights)
                         (update-vals (select-keys entity (keys shared/synset-rel-theme)))
                         (shared/top-n-vals radial-limit))

          data      (clj->js
                      {:name     subject
                       :title    subject
                       :subject  true
                       :children (mapcat
                                   (fn [[k synsets]]
                                     (let [theme (get shared/synset-rel-theme k)]
                                       (->> (for [synset synsets]
                                              (let [label (k->label' synset)]
                                                {:name  label
                                                 :theme theme
                                                 :href  (prefix/resolve-href synset)
                                                 :title (labels-only label)
                                                 #_#_:children []}))
                                            (mapcat by-sense-label)
                                            (group-by :theme)
                                            ;; Since we split by labels, a new
                                            ;; map is created to ensure we are
                                            ;; within the limit.
                                            (shared/top-n-vals radial-limit)
                                            (vals)
                                            (apply concat)
                                            (sort-by :name))))

                                   entity')})

          ;; Specify the chart’s dimensions.
          cx        (* 0.5 width)
          cy        (* 0.5 height)
          radius    (/ (min width height) 3.2)              ; overall size

          ; Create a radial tree layout. The layout’s first dimension (x)
          ; is the angle, while the second (y) is the radius.
          tree      (-> (.tree d3)
                        (.size #js [(* 2 js/Math.PI) radius])
                        (.separation (fn [a b]
                                       (/ (if (= (.-parent a) (.-parent b))
                                            1
                                            2)
                                          (.-depth a)))))

          ;; Sort the tree and apply the layout.
          root      (-> (.hierarchy d3 data)
                        #_(.sort (fn [a b]
                                   (.ascending
                                     d3 (.-name (.-data a)) (.-name (.-data b)))))
                        (tree))

          ;; Creates the SVG container.
          svg       (-> d3
                        (.select node)
                        (.append "svg")
                        (.attr "class" "radial-tree-diagram__svg")
                        #_(.create d3 "svg")
                        (.attr "width" width)
                        (.attr "height" height)
                        (.attr "viewBox" #js [(- cx) (- cy) width height]))

          ;; Add mouseover text (in lieu of a title attribute)
          add-title (fn [d3]
                      (-> d3
                          (.append "title")
                          (.text (fn [d] (.-title (.-data d)))))
                      d3)]
      ;; Append links.
      (-> svg
          (.append "g")
          (.attr "class" "radial-tree-links")
          (.attr "fill" "none")
          (.attr "stroke-opacity" "1")
          (.attr "stroke-width" "1")
          (.selectAll)
          (.data (.links root))
          (.join "path")
          (.attr "d" (-> (.linkRadial d3)
                         (.angle (fn [d] (.-x d)))
                         (.radius (fn [d] (.-y d)))))
          (.attr "stroke" (fn [d]
                            (if-let [color ^js/String (.-theme (.-data (.-target d)))]
                              color
                              "#333"))))

      ;; Append nodes.
      (-> svg
          (.append "g")
          (.attr "class" "radial-tree-nodes")
          (.selectAll)
          (.data (.descendants root))
          (.join "circle")
          (.attr "transform" (fn [d]
                               (str "rotate(" (- (/ (* (.-x d) 180) js/Math.PI) 90)
                                    ") translate(" (.-y d) ",0)")))
          (.attr "fill" (fn [d]
                          (if-let [color ^js/String (.-theme (.-data d))]
                            color
                            (if (.-subject (.-data d))
                              "transparent"
                              "#333"))))
          (.attr "r" 5))

      ;; Append labels.
      (-> svg
          (.append "g")
          (.attr "class" "radial-tree-labels")
          (.attr "stroke-linejoin" "round")
          (.attr "stroke-width" "0.1px")                    ;TODO: better way to get min stroke font?
          (.selectAll)
          (.data (.descendants root))
          (.join "text")
          (.attr "class" (fn [d]
                           (if (.-href (.-data d))
                             "radial-item"
                             "radial-item radial-item__subject")))
          (.attr "transform" (fn [d]
                               (if (.-subject (.-data d))
                                 (str "translate(0,-18) ")
                                 (str "rotate(" (- (/ (* (.-x d) 180) js/Math.PI) 90) ")"
                                      "translate(" (.-y d) ",0) "
                                      "rotate(" (if (>= (.-x d) js/Math.PI) 180 0) ")"))))
          (.attr "dy" "0.31em")
          (.attr "x" (fn [d]
                       (if (.-subject (.-data d))
                         0
                         (if (= (< (.-x d) js/Math.PI) (not (.-children d)))
                           12
                           -12))))
          (.attr "text-anchor" (fn [d]
                                 (if (.-subject (.-data d))
                                   "middle"
                                   (if (= (< (.-x d) js/Math.PI) (not (.-children d)))
                                     "start"
                                     "end"))))
          (.attr "paint-order" "stroke")
          #_(.attr "stroke" "#333")
          ;; TODO: colour the labels using the theme colours? or inverted colours?
          (.attr "data-theme" (fn [d]
                                (if-let [theme ^js/String (.-theme (.-data d))]
                                  theme
                                  "#333")))
          (.attr "fill" "#333")
          (.text (fn [d]
                   (let [s (.-name (.-data d))
                         [limit cutoff] (if (.-subject (.-data d))
                                          [30 28]
                                          [18 16])]
                     (if (> (count s) limit)
                       (str (subs s 0 cutoff) shared/omitted)
                       s))))
          (.on "click" (fn [_ d]
                         (when (.-href (.-data d))
                           (reset! shared/post-navigate {:scroll :diagram})
                           (shared/navigate-to (.-href (.-data d))))))

          ;; Adding mouseover text (in lieu of a title attribute)
          (add-title)

          (.append "tspan")
          (.attr "class" "sense-paragraph")
          (.attr "dy" "4px")
          (.text (fn [d]
                   (when (<= (+ (count (.-name (.-data d)))
                                (count (.-sub (.-data d))))
                             16)
                     (.-sub (.-data d))))))

      (.node svg))))


(rum/defcs radial-tree < (rum/local nil ::subentity)
  [state {:keys [languages k->label] :as opts} subentity]
  [:div.radial-tree-diagram
   {:ref (fn [el]
           (let [{:keys [scroll]} @shared/post-navigate]
             (when el
               (build-radial! (::subentity state) opts subentity el)
               (when (= scroll :diagram)
                 (if (.-scrollIntoViewIfNeeded el)
                   (.scrollIntoViewIfNeeded (.-parentElement el))
                   (.scrollIntoView (.-parentElement el)))))))}])
