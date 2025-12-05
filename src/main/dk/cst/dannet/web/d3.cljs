(ns dk.cst.dannet.web.d3
  "D3-related functions for visualisation support; frontend only!"
  (:require [clojure.math :as math]
            [clojure.string :as str]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.web.i18n :as i18n]
            [dk.cst.dannet.web.ui.error :as error :include-macros true]
            ["d3" :as d3]
            ["d3-cloud" :as cloud]))

(def colours
  (atom (cycle shared/theme)))

(defn next-colour
  "Uses a stateful atom to cycle through shared/theme colors, ensuring each
  subsequent call returns a different color for visual distinction."
  []
  (first (swap! colours rest)))

(defn remove-parens
  [s]
  (str/replace (str s) #"\{|\}" ""))

(def label-section
  #"_([^; ,]+)")

(defn remove-subscript
  "Remove DDO sense subscripts from label `s`.

  DDO labels include subscripts like _1§1 to identify specific senses.
  This function removes everything from underscore through the subscript
  portion, leaving only the base word form."
  [s]
  (str/replace (str s) label-section ""))

(def labels-only
  "Composed fn that removes both DDO subscripts and synset braces."
  (comp remove-subscript remove-parens))

(defn length-penalty
  "Calculate a new size with a penalty based on the visual width of `label`."
  [label size]
  (/ size (max 1 (math/log10 (shared/glyph-width label)))))

;; NOTE: memoized for performance.
(def reorder-lens-shape
  "Reorder `labels` (sorted by length) into lens shape for circular diagrams.
  Places shortest labels at top and bottom, longest in the middle. This creates
  better visual balance in radial layouts where labels are centered above the
  diagram's center point."
  (memoize
    (fn [labels]
      (let [n (count labels)]
        (loop [remaining labels
               result    (transient (vec (repeat n nil)))
               left      0
               right     (dec n)
               from-left true]
          (if (empty? remaining)
            (persistent! result)
            (if from-left
              (recur (rest remaining)
                     (assoc! result left (first remaining))
                     (inc left)
                     right
                     false)
              (recur (rest remaining)
                     (assoc! result right (first remaining))
                     left
                     (dec right)
                     true))))))))

(defn- displayed-synsets
  "Returns the synsets that will actually be displayed, respecting cloud-limit."
  [{:keys [cloud-limit]} synsets]
  (if cloud-limit (take cloud-limit synsets) synsets))

(defn prepare-synset-cloud
  "Prepare `synsets` for word cloud display using info in `opts`.

  The `synsets` collection should already be sorted by weight (highest first).
  If `:cloud-limit` is specified in `opts`, only the top N synsets are used."
  [{:keys [k->label cloud-limit] :as opts} synsets]
  (let [max-size  36
        synsets'  (if cloud-limit
                    (take cloud-limit synsets)
                    synsets)
        weights   (shared/cloud-normalize synsets')
        n         (count weights)
        min-size  (min 0.33 (/ 10 n))
        highlight (:highlight (meta weights))
        k->s      (fn [k]
                    ;; TODO: remove 'first' hack? relies on monolingual labels
                    (str (or (first (get k->label k))
                             (when (keyword? k)
                               (prefix/kw->qname k)))))]
    (->> synsets'
         (mapcat (fn [k]
                   (when-let [weight (get weights k)]
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
         (sort-by :size >))))

(defn content-width
  "Get the width of a `node`, excluding its padding and border.

  Uses getComputedStyle to account for CSS padding, returning only the
  actual content area width available for rendering visualizations."
  [node]
  (let [style (js/window.getComputedStyle node)]
    (- (.-clientWidth node)
       (js/parseFloat (.-paddingLeft style))
       (js/parseFloat (.-paddingRight style)))))

;; TODO: fix, doesn't seem to add extra width to text when used
(defn- append-subscript-tspan
  "Append a subscript tspan element to D3 `text` selection."
  [text]
  (-> text
      (.append "tspan")
      (.attr "class" "sense-paragraph")
      (.attr "dy" "4px")
      (.text (fn [d]
               (.-sub d))))
  text)

(defn- build-cloud!*
  [state {:keys [displayed] :as opts} synsets node]
  (when (and node (not= @state displayed))
    ;; Clear old contents first to prevent duplicate SVGs accumulating in DOM.
    (when-let [existing-svg (.-firstChild node)]
      (.remove existing-svg))
    (let [width  (content-width (.-parentElement node))
          words  (prepare-synset-cloud opts synsets)

          ;; Height scales with word count (6px per word) within certain bounds:
          ;;   - Minimum 128px ensures that small clouds are readable.
          ;;   - Max prevents excessive vertical scrolling for large clouds.
          height (min
                   (max (* (count words) 6) 128)
                   width)

          ;; Draw callback invoked after layout computation finishes.
          ;; Receives positioned word data with x/y coordinates and sizes.
          draw   (fn [words]
                   (-> d3

                       ;; Adding <svg> and <g> (group) container elements.
                       (.select node)
                       (.append "svg")
                       (.attr "width" width)
                       (.attr "height" height)
                       (.append "g")
                       ;; Center group in SVG: words are positioned relative to (0,0)
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
                       #_(append-subscript-tspan)

                       ;; Adding mouseover text (in lieu of a title attribute)
                       (.append "title")
                       (.text (fn [d] (.-title d)))))

          ;; The d3-cloud layout conf computes word positions to avoid overlaps.
          layout (-> (cloud)
                     (.size #js [width height])
                     (.words (clj->js words))
                     (.padding 8)                           ; prevent visual crowding
                     (.rotate (fn [] 0))                    ; keep labels horizontal for readability
                     (.font "Georgia")                      ; matches CSS styling
                     (.fontSize (fn [d] (.-size d)))
                     ;; Trigger draw when layout computation completes
                     (.on "end" draw))]
      (.start layout))
    (reset! state displayed)))

(defn build-cloud!
  "Build word cloud in `node` from `synsets`, storing render state in `state`.
  
  Computes :displayed for build-cloud!* to use as cache key, ensuring
  deferred synset data doesn't trigger unnecessary re-renders."
  [state {:keys [cloud-limit] :as opts} synsets node]
  ;; Uses try-static-render since this runs in a ref callback, outside React's
  ;; render cycle where try-render and error boundaries can't catch errors.
  (let [displayed (if cloud-limit (take cloud-limit synsets) synsets)]
    (error/try-static-render node
      (build-cloud!* state (assoc opts :displayed displayed) synsets node))))

;; NOTE: memoized for performance.
(def expand-sense-labels
  "Extract individual word labels from synset names in map `m`;
  limits to the top canonical label for each synset to avoid duplicates."
  (memoize
    (fn [{:keys [name] :as m}]
      (for [[s _ _ sub _] (->> (shared/sense-labels shared/synset-sep name)
                               (shared/canonical)
                               ;; limit to top label for each synset
                               (take 1)
                               (map #(re-matches shared/sense-label %)))

            ;; Allow phrases to appear too, not just the core word in the phrase.
            :let [name' (if s
                          (str/replace s (str "_" sub) "")
                          name)]]
        (when-not (= s shared/omitted)
          (assoc m
            :name name'
            :sub sub))))))

(def radial-limit
  56)

(def spacer-node
  {:name "" :theme nil :spacer true :title "" :href nil})

(defn- insert-theme-spacers
  "Group/sort `nodes` by relation using `sort-keyfn`, insert spacers between.
  
  Adds transparent spacer nodes between relation groups for visual separation."
  [sort-keyfn nodes]
  (let [theme-groups  (group-by :relation nodes)
        sorted-themes (sort-by sort-keyfn theme-groups)]
    (->> sorted-themes
         (mapcat (fn [[_theme nodes]]
                   (concat nodes [spacer-node spacer-node])))
         ;; Final spacers removed; balanced spacers handle edges
         (drop-last 2))))

(defn- rotate-for-bottom-placement
  "Rotate `nodes` by a half turn when there are only two nodes."
  [nodes]
  (if (<= (count nodes) 2)
    (let [half (quot (count nodes) 2)]
      (concat (drop half nodes) (take half nodes)))
    nodes))

(defn- insert-balanced-spacers
  "Insert spacers to balance empty space around the diagram for `nodes`.
  
  Distributes spacers equal to radial-limit minus current node count
  across four corner positions. Nodes are arranged in a circle starting
  from top, with spacers inserted at:
  - Top: split between start/end (wrapping around circle) - always minimum 3
  - Right: 1/4 mark - only when excess capacity
  - Bottom: 1/2 mark - always minimum 3
  - Left: 3/4 mark - only when excess capacity"
  [nodes]
  (let [node-count      (count nodes)
        spacers-needed  (max 0 (- radial-limit node-count))
        spacers-per-pos (quot spacers-needed 4)

        ;; Top/bottom always get minimum 3 spacers for visual separation
        ;; Left/right only get spacers when there's excess capacity
        top-spacers     (+ 3 spacers-per-pos)
        side-spacers    spacers-per-pos
        bottom-spacers  (+ 3 spacers-per-pos)
        top-start       (quot top-spacers 2)
        top-end         (- top-spacers top-start)

        ;; Corner position indices
        quarter         (quot node-count 4)
        half            (quot node-count 2)
        three-q         (quot (* 3 node-count) 4)]

    (concat (repeat top-start spacer-node)                  ; top
            (take quarter nodes)                            ; top-right corner
            (repeat side-spacers spacer-node)               ; right
            (take (- half quarter) (drop quarter nodes))    ; bottom-right corner
            (repeat bottom-spacers spacer-node)             ; bottom
            (take (- three-q half) (drop half nodes))       ; bottom-left corner
            (repeat side-spacers spacer-node)               ; left
            (drop three-q nodes)                            ; top-left corner
            (repeat top-end spacer-node))))

(defn- label-space-score
  "Calculate space availability score for labels at `angle` position.

  Lower scores indicate better positions for long labels.

  Formula: (corner-dist² - 0.33 × rotation-factor)
  - Squared corner distance creates steep gradient favoring corners
  - Subtracting rotation factor gives extra advantage to diagonal positions
    where labels are more tilted (need less horizontal space)

  Result ranges approximately:
  - Corners (45°, 135°, 225°, 315°): ~-0.15 (best)
  - Top/Bottom (90°, 270°): ~0.6 (good)
  - Left/Right (0°, 180°): ~0.7 (worst)"
  [angle]
  (let [norm-angle      (mod angle (* 2 js/Math.PI))

        ;; Corner proximity: 0.0 at corners, 1.0 at edges
        eighth          (/ js/Math.PI 4)
        nearest-eighth  (js/Math.round (/ norm-angle eighth))
        dist-to-eighth  (js/Math.abs (- norm-angle (* nearest-eighth eighth)))
        at-corner?      (odd? (mod nearest-eighth 8))
        corner-dist     (if at-corner?
                          (/ dist-to-eighth eighth)
                          (- 1.0 (/ dist-to-eighth eighth)))

        ;; Label rotation factor: measures how much labels are tilted
        ;; More rotation = needs more diagonal/vertical space
        ;; Less rotation (horizontal) = needs more horizontal space
        rotation-rad    (if (< norm-angle js/Math.PI)
                          (* (- (/ js/Math.PI 2) norm-angle) 20)
                          (* (- (* 3 (/ js/Math.PI 2)) norm-angle) 20))
        max-rotation    31.5
        rotation-factor (/ (js/Math.abs rotation-rad) max-rotation)]

    ;; Combined score: squared corner distance with rotation bonus
    ;; Subtracting rotation gives diagonal positions extra advantage
    (- (* corner-dist corner-dist)
       (* 0.8 rotation-factor))))

(defn- optimize-within-group
  "Reorder `nodes` to place longer labels at optimal positions.

  Within each theme group, assigns longer labels to positions with better
  space availability, accounting for both corner proximity and label rotation.
  Uses glyph-aware width for more accurate label size estimation."
  [nodes start-idx total-count]
  (if (< (count nodes) 3)
    nodes
    (let [;; Calculate space score for each position
          positions        (map (fn [offset]
                                  (let [idx   (+ start-idx offset)
                                        angle (* 2 js/Math.PI (/ idx total-count))]
                                    {:offset      offset
                                     :space-score (label-space-score angle)}))
                                (range (count nodes)))

          ;; Best positions first (lowest space score)
          sorted-positions (sort-by :space-score positions)

          ;; Longest labels first (by glyph width, not character count)
          sorted-nodes     (sort-by (comp shared/glyph-width :name) > nodes)

          ;; Pair longest labels with best positions
          offset->node     (zipmap (map :offset sorted-positions) sorted-nodes)]

      ;; Reconstruct in sequential order
      (mapv #(get offset->node %) (range (count nodes))))))

(defn- optimize-radial-structure
  "Optimize label placement within theme groups in the complete structure.

  Walks through `nodes` and reorders each contiguous theme group to place
  longer labels at better angular positions (corners and horizontal areas)."
  [nodes]
  (let [total-count (count nodes)]
    (loop [i      0
           result []]
      (if (>= i total-count)
        result
        (let [node (nth nodes i)]
          (if (or (:spacer node) (nil? (:theme node)))
            ;; Spacer or no theme - just copy it
            (recur (inc i) (conj result node))
            ;; Start of a theme group - find its extent
            (let [theme     (:theme node)
                  group-end (loop [j (inc i)]
                              (if (or (>= j total-count)
                                      (not= theme (:theme (nth nodes j))))
                                j
                                (recur (inc j))))
                  group     (subvec (vec nodes) i group-end)
                  optimized (optimize-within-group group i total-count)]
              (recur group-end (into result optimized)))))))))

(defn- prepare-radial-children
  "Transform entity data into radial tree format with optimized label placement."
  [entity k->label]
  (->> entity
       (into []
             (mapcat (fn [[k synsets]]
                       (let [theme (get shared/synset-rel-theme k)
                             xf    (comp (map (fn [synset]
                                                (let [label (or (k->label synset)
                                                                (prefix/kw->qname synset))]
                                                  {:name     label
                                                   :theme    theme
                                                   :relation k
                                                   :href     (prefix/resolve-href synset)
                                                   :title    (labels-only label)})))
                                         (mapcat expand-sense-labels))]
                         (->> synsets
                              (into [] xf)
                              (group-by :relation)

                              ;; Always select values from every relation type.
                              (shared/top-n-vals radial-limit)
                              (mapcat val))))))

       ;; Sorting into (and of) groups happens here!
       (insert-theme-spacers (comp str k->label first))
       
       ;; Rotate for better placement with few nodes
       (rotate-for-bottom-placement)

       ;; Insert spacers to balance empty space around the diagram
       (insert-balanced-spacers)

       ;; Labels are sorted internally within the groups based on label size.
       ;; The bigger labels are positioned towards the corners of the diagram
       ;; where space is more freely available.
       (optimize-radial-structure)
       (map-indexed (fn [n m] (assoc m :n n)))))

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

(defn- node-fill-color
  "Determine fill color for radial tree node `d`."
  [d]
  (let [data ^js (.-data d)]
    (cond
      (.-spacer data) "transparent"
      (.-theme data) (.-theme data)
      (.-subject data) "transparent"
      :else "#333")))

(defn- prepare-radial-data
  "Transform entity data into radial tree format for visualization."
  [subject entity k->label]
  (let [label    (->> (k->label subject)
                      (shared/sense-labels shared/synset-sep)
                      (shared/canonical)
                      (map remove-subscript)
                      (remove #{shared/omitted})
                      (set)
                      (sort-by shared/glyph-width)
                      (str/join ", "))
        entity'  (->> (keys shared/synset-rel-theme)
                      (select-keys entity)
                      (shared/top-n-vals radial-limit))
        children (prepare-radial-children entity' k->label)
        data     (clj->js
                   {:name     label
                    :title    label
                    :subject  true
                    :children children})]
    data))

(defn- create-radial-svg
  "Create and configure the base SVG container for radial tree.

  Takes `node` (DOM node), `width`, `height`, `cx` (center x), `cy` (center y),
  and `sizing` map with font sizes. Returns the configured SVG selection."
  [node width height cx cy]
  (-> d3
      (.select node)
      (.append "svg")
      (.attr "class" "radial-tree-diagram__svg")
      ;; viewBox: defines coordinate system centered at (0,0)
      ;; [minX minY width height] where (-cx, -cy) shifts origin to center
      ;; This allows positive/negative coords radiating from center
      (.attr "viewBox" #js [(- cx) (- cy) width height])))

(defn- render-radial-links
  "Render connection paths between nodes in the radial tree.

  Takes `svg` (SVG selection) and `root` (D3 hierarchy root). Appends link
  paths with appropriate styling based on node theme."
  [svg root]
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
                            "transparent"
                            (if-let [color ^js/String (.-theme target-data)]
                              color
                              "#333")))))))

(defn balanced-tilt
  "Calculate rotation value for balanced tilt of `d` based on a `tilt-factor`.
  This adjusts the tilt of the vertically pointing nodes to be more horizontal."
  [d tilt-factor]
  (if (< (.-x d) js/Math.PI)
    ;; Top half: rotate proportional to distance from π/2
    (* (- (* js/Math.PI 0.5)
          (.-x d))
       tilt-factor)
    ;; Bottom half: rotate proportional to distance from 3π/2
    (* (- (* js/Math.PI 1.5)
          (.-x d))
       tilt-factor)))

(defn- render-radial-nodes
  "Render node circles in the radial tree.

  Takes `svg` (SVG selection), `root` (D3 hierarchy root), and `radius`
  (diagram radius). Appends circle elements positioned and colored according
  to their role (subject, theme, spacer)."
  [svg root radius]
  (-> svg
      (.append "g")
      (.attr "class" "radial-tree-nodes")
      (.selectAll)
      (.data (.descendants root))
      (.join "path")
      (.attr "transform" (fn [d]
                           (str "rotate(" (- (/ (* (.-x d) 180) js/Math.PI) 90)
                                ") translate(" (.-y d) ",0)"
                                "rotate(" (balanced-tilt d 16) ")")))
      (.attr "fill" node-fill-color)
      ;; Create arrowhead pointing radially outward (right in local coords = outward after transform)
      (.attr "d" (fn [_]
                   (let [size   (/ radius 40)
                         height (* size 2)                  ; Arrow length (pointing outward)
                         base   (* size 1.5)]               ; Arrow base width
                     (str "M 0," (- (/ base 2))             ; Start at bottom of base
                          " L " height ",0"                 ; Draw to point
                          " L 0," (/ base 2)                ; Draw to top of base
                          " Z"))))))

(defn- render-radial-labels
  "Render text labels with rotation and positioning in the radial tree.

  Takes `svg` (SVG selection), `root` (D3 hierarchy root), and `sizing` map
  with font sizes and limits. Handles complex label rotation, multi-line
  subject labels, and subscript rendering."
  [svg root]
  (let [add-title (fn [d3]
                    (-> d3
                        (.append "title")
                        (.text (fn [d] (.-title (.-data d)))))
                    d3)]
    (-> svg
        (.append "g")
        (.attr "class" "radial-tree-labels")
        (.attr "stroke-linejoin" "round")
        (.attr "stroke-width" "0.1px")
        (.selectAll)
        (.data (.descendants root))
        (.join "text")
        (.attr "class" (fn [d]
                         (if (.-href (.-data d))
                           "radial-item"
                           "radial-item radial-item__subject")))

        ;; LABEL ROTATION LOGIC (most complex part of the function):
        ;; For subject (center): simple vertical offset, no rotation
        ;; For other labels: 4-stage transformation.
        (.attr "transform" (fn [d]
                             (if (.-subject (.-data d))
                               ;; Center label: just offset upward slightly
                               (str "translate(0,-4) ")

                               ;; Stage 1: Rotate label to point outward from center
                               ;; angle in radians → degrees, subtract 90° so 0° points up
                               (str "rotate(" (- (/ (* (.-x d) 180) js/Math.PI) 90) ") "

                                    ;; Stage 2: Move label from center to circumference
                                    ;; +2px padding beyond node position for breathing room
                                    "translate(" (+ (.-y d) 2) ",0) "

                                    ;; Stage 3: Flip labels on left half to read left-to-right
                                    ;; x >= π means left half of circle → rotate 180°
                                    "rotate(" (if (>= (.-x d) js/Math.PI) 180 0) ") "

                                    ;; Stage 4: Subtle tilt adjustment for better legibility
                                    ;; - Vertical labels (x near π/2 or 3π/2): rotate more
                                    ;; - Horizontal labels (x near 0 or π): no rotation
                                    ;; - Factor of 20 controls maximum tilt angle (~31.5°)
                                    ;; This makes diagonal labels easier to read by reducing
                                    ;; extreme perpendicular angles
                                    "rotate(" (balanced-tilt d 20) ")"))))
        (.attr "dy" "0.31em")
        (.attr "x" (fn [d]
                     (if (.-subject (.-data d))
                       0
                       ;; Label distance from node: scales with size factor.
                       (let [label-dist 20]
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
        (.attr "data-theme" (fn [d]
                              (if-let [theme ^js/String (.-theme (.-data d))]
                                theme
                                "#333")))
        (.attr "fill" (fn [d]
                        (let [data ^js (.-data d)]
                          (if (.-spacer data)
                            "transparent"
                            "#333"))))
        (.text (fn [d]
                 (let [data ^js (.-data d)]
                   (if (.-spacer data)
                     ""
                     (let [s (.-name data)
                           [limit cutoff] (if (.-subject data)
                                            [20 24]
                                            [20 18])]
                       ;; For subject (center) labels, we'll handle multi-line in tspan
                       ;; For regular labels, display as single line
                       (if (.-subject data)
                         ""
                         (if (and (string? s) (> (count s) limit))
                           (str (subs s 0 cutoff) shared/omitted)
                           s)))))))
        (.on "click" (fn [_ d]
                       (when (.-href (.-data d))
                         (reset! shared/post-navigate {:scroll :diagram})
                         (shared/navigate-to (.-href (.-data d))))))

        ;; Adding mouseover text (in lieu of a title attribute)
        (add-title)

        ;; TODO: also split at appropriate point(s) if the string is too long,
        ;;       e.g. http://localhost:7777/dannet/data/synset-74795
        ;;       (should not include the comma, though, obviously)
        ;; Split center labels into multiple lines at commas
        (.each (fn [d]
                 (this-as this-elem
                   (let [data ^js (.-data d)]
                     (when (.-subject data)
                       (let [label-parts  (->> (str/split (.-name data) #",\s*")
                                               (sort-by count)
                                               (reorder-lens-shape))
                             line-height  56
                             total-lines  (count label-parts)
                             start-offset (- (* (/ (dec total-lines) 2) line-height))]
                         (doseq [[idx part] (map-indexed vector label-parts)]
                           (-> (d3/select this-elem)
                               (.append "tspan")
                               (.attr "x" 0)
                               (.attr "dy" (if (zero? idx)
                                             (str start-offset "px")
                                             (str line-height "px")))
                               (.attr "text-anchor" "middle")
                               (.text (str (str/trim part)
                                           (when (< idx (dec total-lines)) ",")))))))))))

        (.append "tspan")
        (.attr "class" "sense-paragraph")
        (.attr "dy" (str 2 "px"))
        (.attr "dx" (str (/ 10 4) "px"))
        (.text (fn [d]
                 (when (<= (+ (count (str (.-name (.-data d))))
                              (count (.-sub (.-data d))))
                           12)
                   (.-sub (.-data d))))))))

(defn ->localised-labeler
  [{:keys [languages k->label] :as opts}]
  (fn [k]
    (some->> (k->label k)
             (i18n/select-label languages))))

;; TODO: apply response resizing of various elements in CSS, e.g. label sizes
;;       or padding, and possibly radial radius too
;; TODO: use existing theme colours, but vary strokes and final symbols
;; Based on https://observablehq.com/@d3/radial-tree/2
(defn- build-radial!*
  "Build and render a radial tree diagram in `elem` from `entity`.

  Orchestrates the complete radial tree visualization: prepares data,
  creates SVG container, and renders links, nodes, and labels with
  optimized positioning and rotation."
  [entity elem {:keys [subject] :as opts}]
  (when elem
    ;; Clear old contents first to prevent duplicate SVGs accumulating in DOM.
    (when-let [existing-svg (.-firstChild elem)]
      (.remove existing-svg))
    (let [width     800
          height    800

          ;; Prepare hierarchical data structure
          k->label' (->localised-labeler opts)
          data      (prepare-radial-data subject entity k->label')

          ;; Center coordinates: placing tree center at (0.5, 0.5) of viewBox
          ;; allows equal radius in all directions regardless of aspect ratio
          cx        (* 0.5 width)
          cy        (* 0.5 height)

          ;; Utilize diagonal space while keeping labels within viewport bounds.
          radius    (/ (min width height)
                       js/Math.PI)

          ;; Create a radial tree layout. The layout's first dimension (x)
          ;; is the angle, while the second (y) is the radius.
          tree      (-> (.tree d3)
                        (.size #js [(* 2 js/Math.PI) radius])
                        (.separation (fn [a b]
                                       (/ (if (= (.-parent a) (.-parent b))
                                            1
                                            2)
                                          (.-depth a)))))

          ;; Apply the layout.
          root      (-> (.hierarchy d3 data)
                        (tree))

          ;; Create the SVG container
          svg       (create-radial-svg elem width height cx cy)]

      ;; Add gradient definition
      (create-radial-gradient svg)

      ;; Render all visual elements in layering order
      (render-radial-links svg root)

      ;; Add radial gradient background to fade out the line congestion in center
      ;; Positioned after links but before nodes/labels for proper layering
      ;; 75% of diagram radius - nearly to the nodes but not covering labels
      (let [bg-radius (* radius 0.75)]
        (-> svg
            (.append "circle")
            (.attr "class" "subject-background")
            (.attr "cx" 0)
            (.attr "cy" 0)
            (.attr "r" bg-radius)
            (.attr "fill" "url(#subjectBackground)")))

      (render-radial-nodes svg root radius)
      (render-radial-labels svg root)

      (.node svg))))

(defn build-radial!
  [entity elem {:keys [subject] :as opts}]
  ;; Uses try-static-render since this runs in a ref callback, outside React's
  ;; render cycle where try-render and error boundaries can't catch errors.
  (error/try-static-render elem
    (build-radial!* entity elem opts)))
