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
                    ;; TODO: remove 'first' hack? relies on monolingual labels
                    (str (or (first (get k->label k))
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
                         (when-not (= label shared/omitted)
                           (let [[s word rest-of-s sub mwe]
                                 (re-matches shared/sense-label label)]
                             ;; adding spaces to the label seems to improve layout
                             {:text      (str " " word)
                              :title     (str/replace s #"_" " ")
                              :sub       (str sub " ")
                              :highlight (boolean (get highlight k))
                              :href      (prefix/resolve-href k)
                              :size      (length-penalty (str word sub) size)})))))))
         (remove nil?)

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
    (when-not (= s shared/omitted)
      (assoc m
        :name word
        :sub sub))))



(def radial-limit
  48)

(def spacer-node
  {:name "" :theme nil :spacer true :title "" :href nil})

(defn- add-theme-spacers
  "Insert transparent spacer nodes between theme groups for visual separation."
  [themed-children]
  (let [theme-groups  (group-by :theme themed-children)
        sorted-themes (sort-by first theme-groups)]
    (->> sorted-themes
         (mapcat (fn [[_theme nodes]]
                   (concat nodes [spacer-node])))

         ;; The final one isn't need as we'll add some with add-middle-spacers.
         (butlast))))

(def big-spacer
  [spacer-node spacer-node spacer-node spacer-node])

(defn- add-middle-spacers
  [nodes]
  (let [mid-point (quot (count nodes) 2)
        [right left] (split-at mid-point nodes)]
    (concat big-spacer
            right
            big-spacer big-spacer
            left
            big-spacer)))

(defn- calculate-dynamic-sizing
  "Calculate dynamic font sizes and text limits for `node-count`, `width`, and `radius`.
  
  Returns map with :size-factor, :font-size, :subject-font-size, :tspan-font-size,
  :subject-limits [limit cutoff], and :regular-limits [limit cutoff]."
  [node-count width radius]
  (let [;; Node density: fewer nodes allow bigger text
        density-factor (max 0.7 (min 1.3 (/ 20 (max node-count 8))))
        ;; Screen space: bigger screens allow proportionally bigger text
        space-factor   (max 0.8 (min 1.5 (/ width 600)))
        ;; Radius scaling: maintain proportion with diagram size
        radius-factor  (max 0.9 (min 1.1 (/ radius 120)))
        ;; Combined scaling with base adjustment
        size-factor    (* density-factor space-factor radius-factor 1.05)
        font-size      (* 14 size-factor)]
    {:size-factor       size-factor
     :font-size         font-size
     :subject-font-size (* font-size 2.2)
     :tspan-font-size   (* font-size 0.75)
     :subject-limits    [(int (* 30 size-factor)) (int (* 28 size-factor))]
     :regular-limits    [(int (* 18 size-factor)) (int (* 16 size-factor))]}))

(defn- create-radial-gradient
  "Add radial gradient definition for subject label background."
  [svg]
  (let [defs     (-> svg (.append "defs"))
        gradient (-> defs
                     (.append "radialGradient")
                     (.attr "id" "subjectBackground")
                     (.attr "cx" "50%")
                     (.attr "cy" "50%")
                     (.attr "r" "50%"))]
    (-> gradient (.append "stop") (.attr "offset" "0%")
        (.attr "stop-color" "white") (.attr "stop-opacity" "0.9"))
    (-> gradient (.append "stop") (.attr "offset" "70%")
        (.attr "stop-color" "white") (.attr "stop-opacity" "0.4"))
    (-> gradient (.append "stop") (.attr "offset" "100%")
        (.attr "stop-color" "white") (.attr "stop-opacity" "0"))))

(defn- render-node-fill [d]
  "Determine fill color for nodes, handling spacers and themes."
  (let [data ^js (.-data d)]
    (cond
      (.-spacer data) "transparent"
      (.-theme data) (.-theme data)
      (.-subject data) "transparent"
      :else "#333")))

(defn- render-text-content [d subject-limits regular-limits]
  "Generate text content for labels, handling spacers and truncation."
  (let [data ^js (.-data d)]
    (if (.-spacer data)
      ""
      (let [s (.-name data)
            [limit cutoff] (if (.-subject data)
                             subject-limits
                             regular-limits)]
        (if (> (count s) limit)
          (str (subs s 0 cutoff) shared/omitted)
          s)))))

;; TODO: use existing theme colours, but vary strokes and final symbols
;; Based on https://observablehq.com/@d3/radial-tree/2
(defn build-radial!
  [state {:keys [label languages k->label synset-weights subject] :as opts} entity node]
  (when node
    ;; Always start by clearing the old contents.
    (when-let [existing-svg (.-firstChild node)]
      (.remove existing-svg))
    (let [width           (content-width (.-parentElement node))
          height          width

          subject         (->> (shared/sense-labels shared/synset-sep label)
                               (shared/canonical)
                               (map remove-subscript)
                               (remove #{shared/omitted})
                               (set)                        ; fixes e.g. http://localhost:3456/dannet/data/synset-2500
                               (sort-by count)
                               (str/join ", "))
          ;; TODO: need to use prefix:identifier when label is unavailable, e.g. ILI synsets
          k->label'       (comp
                            #_clean-text
                            (partial i18n/select-label languages)
                            k->label)
          entity'         (->> (shared/weight-sort-fn synset-weights)
                               (update-vals (select-keys entity (keys shared/synset-rel-theme)))
                               (shared/top-n-vals radial-limit))

          ;; Transform entity data into radial tree format with category spacers
          children-with-spacers
                          (->> entity'
                               (mapcat (fn [[k synsets]]
                                         (let [theme (get shared/synset-rel-theme k)]
                                           (->> synsets
                                                (map (fn [synset]
                                                       (let [label (k->label' synset)]
                                                         {:name  label :theme theme
                                                          :href  (prefix/resolve-href synset)
                                                          :title (labels-only label)})))
                                                (mapcat by-sense-label)
                                                (group-by :theme)
                                                (shared/top-n-vals radial-limit)
                                                vals (apply concat) (sort-by :name)
                                                (map-indexed (fn [n m] (assoc m :n n)))))))
                               (add-theme-spacers)
                               (add-middle-spacers))

          data            (clj->js
                            {:name     subject
                             :title    subject
                             :subject  true
                             :children children-with-spacers})

          ;; Specify the chart's dimensions.
          cx              (* 0.5 width)
          cy              (* 0.5 height)
          ;; More aggressive padding - use more of the available space
          diagram-padding (max 16 (min 28 (* width 0.04)))  ; 4% of width, clamped between 16-28px
          radius          (- (/ (min width height)
                                js/Math.PI)
                             diagram-padding)               ; overall size

          ; Create a radial tree layout. The layout's first dimension (x)
          ; is the angle, while the second (y) is the radius.
          tree            (-> (.tree d3)
                              (.size #js [(* 2 js/Math.PI) radius])
                              (.separation (fn [a b]
                                             (/ (if (= (.-parent a) (.-parent b))
                                                  1
                                                  2)
                                                (.-depth a)))))

          ;; Sort the tree and apply the layout.
          root            (-> (.hierarchy d3 data)
                              #_(.sort (fn [a b]
                                         (.ascending
                                           d3 (.-name (.-data a)) (.-name (.-data b)))))
                              (tree))

          ;; Calculate dynamic sizing factors for fonts and text limits
          node-count      (.-length (.descendants root))
          sizing          (calculate-dynamic-sizing node-count width radius)
          {:keys [size-factor font-size subject-font-size tspan-font-size
                  subject-limits regular-limits]} sizing

          ;; Creates the SVG container.
          svg             (-> d3
                              (.select node)
                              (.append "svg")
                              (.attr "class" "radial-tree-diagram__svg")
                              (.attr "width" width)
                              (.attr "height" height)
                              (.attr "viewBox" #js [(- cx) (- cy) width height])
                              (.style "--radial-font-size" (str font-size "px"))
                              (.style "--radial-subject-font-size" (str subject-font-size "px"))
                              (.style "--radial-tspan-font-size" (str tspan-font-size "px")))

          _               (create-radial-gradient svg)

          ;; Add mouseover text (in lieu of a title attribute)
          add-title       (fn [d3]
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
                            (let [target-data ^js (.-data (.-target d))]
                              (if (.-spacer target-data)
                                "transparent"               ; Hide spacer links
                                (if-let [color ^js/String (.-theme target-data)]
                                  color
                                  "#333"))))))

      ;; Add radial gradient background to fade out the line congestion in center
      ;; Positioned after links but before nodes/labels for proper layering
      (let [bg-radius (* radius 0.75)]                      ; 75% of diagram radius - nearly to the nodes but not covering labels
        (-> svg
            (.append "circle")
            (.attr "class" "subject-background")
            (.attr "cx" 0)                                  ; Center of diagram where lines congregate
            (.attr "cy" 0)                                  ; Center of diagram where lines congregate
            (.attr "r" bg-radius)
            (.attr "fill" "url(#subjectBackground)")))

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
          (.attr "fill" render-node-fill)                   ; Default color
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
                                 (str "translate(0,-4) ")


                                 ;; Rotate the labels to stand perpendicular to centre.
                                 (str "rotate(" (- (/ (* (.-x d) 180) js/Math.PI) 90) ") "

                                      ;; Move labels from the centre to the circumference.
                                      "translate(" (.-y d) ",0) "

                                      ;; Flip labels on the left, so that they go on the outside of the circle
                                      "rotate(" (if (>= (.-x d) js/Math.PI) 180 0) ") "

                                      ;; Rotate the labels ever so slightly to make them more legible.
                                      ;; The most vertical ones are rotated the most.
                                      "rotate(" (if (< (.-x d) js/Math.PI)
                                                  (* (- (* js/Math.PI 0.5)
                                                        (.-x d))
                                                     20)
                                                  (* (- (* js/Math.PI 1.5)
                                                        (.-x d))
                                                     20))
                                      ")"))))
          (.attr "dy" "0.31em")
          (.attr "x" (fn [d]
                       (if (.-subject (.-data d))
                         0
                         ;; The distance between - optimized for available space
                         (let [base-dist  12                ; Reduced from 16 to save space
                               label-dist (* base-dist size-factor)]
                           (if (= (< (.-x d) js/Math.PI) (not (.-children d)))
                             label-dist
                             (- label-dist))))))
          (.attr "text-anchor" (fn [d]
                                 (if (.-subject (.-data d))
                                   "middle"
                                   (if (= (< (.-x d) js/Math.PI) (not (.-children d)))
                                     "start"
                                     "end"))))
          (.attr "paint-order" "stroke")
          (.attr "font-size" (fn [d]
                               ;; Use larger font for subject (center) label
                               (if (.-subject (.-data d))
                                 (str subject-font-size "px")
                                 (str font-size "px"))))
          #_(.attr "stroke" "#333")
          ;; TODO: colour the labels using the theme colours? or inverted colours?
          (.attr "data-theme" (fn [d]
                                (if-let [theme ^js/String (.-theme (.-data d))]
                                  theme
                                  "#333")))
          (.attr "fill" (fn [d]
                          (let [data ^js (.-data d)]
                            (if (.-spacer data)
                              "transparent"                 ; Hide spacer labels
                              "#333"))))
          (.text (fn [d]
                   (let [data ^js (.-data d)]
                     (if (.-spacer data)
                       ""                                   ; Empty text for spacers
                       (let [s (.-name data)
                             ;; Use dynamic limits based on node count
                             [limit cutoff] (if (.-subject data)
                                              subject-limits
                                              regular-limits)]
                         (if (> (count s) limit)
                           (str (subs s 0 cutoff) shared/omitted)
                           s))))))
          (.on "click" (fn [_ d]
                         (when (.-href (.-data d))
                           (reset! shared/post-navigate {:scroll :diagram})
                           (shared/navigate-to (.-href (.-data d))))))

          ;; Adding mouseover text (in lieu of a title attribute)
          (add-title)

          (.append "tspan")
          (.attr "class" "sense-paragraph")
          (.attr "dy" "6px")
          (.attr "dx" "4px")
          (.attr "font-size" (str tspan-font-size "px"))    ; Dynamic tspan font size
          (.text (fn [d]
                   (when (<= (+ (count (.-name (.-data d)))
                                (count (.-sub (.-data d))))
                             (* 12 size-factor))            ; Updated to match new base-dist
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
