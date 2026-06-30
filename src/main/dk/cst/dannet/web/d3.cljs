(ns dk.cst.dannet.web.d3
  "D3-related functions for visualisation support; frontend only!"
  (:require [clojure.math :as math]
            [clojure.string :as str]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.web.i18n :as i18n]
            [dk.cst.dannet.web.ui.error :as error :include-macros true]
            [dk.cst.dannet.web.ui.relations :as relations]
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

       ;; Grouping (and group ordering) happens here: groups follow the
       ;; canonical relations/group-order so related relations sit together
       ;; and always appear in roughly the same place.
       (insert-theme-spacers (fn [[k _]]
                               (relations/relation-sort-key k (k->label k))))

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
        (.attr "stop-color" "white") (.attr "stop-opacity" "0.6"))
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
  "Transform `subject` entity data into radial tree format for visualization."
  [subject subentity full-entity k->label languages]
  (let [label      (->> (k->label subject)
                        (shared/sense-labels shared/synset-sep)
                        (shared/canonical)
                        (map remove-subscript)
                        (remove #{shared/omitted})
                        (set)
                        (sort-by shared/glyph-width)
                        (str/join ", "))
        pos        (some-> full-entity :wn/lexfile shared/lexfile->pos)
        pos-label  (when pos
                     (i18n/da-en languages
                       (shared/pos-abbr-da pos)
                       (shared/pos-abbr-en pos)))
        definition (some->> (:skos/definition full-entity)
                            (i18n/select-label languages)
                            str)
        entity'    (->> (keys shared/synset-rel-theme)
                        (select-keys subentity)
                        (shared/top-n-vals radial-limit))
        children   (prepare-radial-children entity' k->label)]
    (clj->js {:name       label
              :title      label
              :pos        pos-label
              :definition definition
              :subject    true
              :children   children})))

(defn- create-radial-svg
  "Create and configure the base SVG container for radial tree.

  Takes `node` (DOM node), `width`, `height`, `cx` (center x), `cy` (center y),
  and `aria-label` (accessible name). Exposes the SVG to assistive tech as a
  single labelled image (`role=img` + `aria-label` plus a <title>), since the
  tree geometry isn't meaningfully navigable by a screen reader — the table view
  is the accessible path through the same relations. Returns the SVG selection."
  [node width height cx cy aria-label]
  (let [svg (-> d3
                (.select node)
                (.append "svg")
                (.attr "class" "radial-tree-diagram__svg")
                (.attr "role" "img")
                (.attr "aria-label" aria-label)
                ;; viewBox: defines coordinate system centered at (0,0)
                ;; [minX minY width height] where (-cx, -cy) shifts origin to center
                ;; This allows positive/negative coords radiating from center
                (.attr "viewBox" #js [(- cx) (- cy) width height]))]
    (-> svg (.append "title") (.text aria-label))
    svg))

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
                              "#333")))))
      (.attr "data-theme" (fn [d]
                            (let [target-data ^js (.-data (.-target d))]
                              (when-let [color ^js/String (.-theme target-data)]
                                color))))))

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
      (.attr "data-theme" (fn [d]
                            (when-let [color ^js/String (.-theme (.-data d))]
                              color)))
      ;; Create arrowhead pointing radially outward (right in local coords = outward after transform)
      (.attr "d" (fn [_]
                   (let [size   (/ radius 40)
                         height (* size 2)                  ; Arrow length (pointing outward)
                         base   (* size 1.5)]               ; Arrow base width
                     (str "M 0," (- (/ base 2))             ; Start at bottom of base
                          " L " height ",0"                 ; Draw to point
                          " L 0," (/ base 2)                ; Draw to top of base
                          " Z"))))))

(defn- wrap-at-bullets
  "Wrap a bullet-separated string into lines that fit within max-width.
  
  Uses glyph-width estimation to determine line breaks. If an individual
  segment exceeds max-width, it will be placed on its own line (no truncation).
  Returns a vector of line strings."
  [s max-width font-scale]
  (let [segments (str/split s #"\s*•\s*")
        sep      " • "]
    (reduce
      (fn [lines segment]
        (let [current   (peek lines)
              candidate (if (str/blank? current)
                          segment
                          (str current sep segment))
              width     (* (shared/glyph-width candidate) font-scale)]
          (if (or (str/blank? current)
                  (<= width max-width))
            (conj (pop lines) candidate)
            (conj lines segment))))
      [""]
      segments)))

(defn- render-subject-labels-horizontal
  "Render subject labels in upper half, definition in lower half.
  
  Labels are distributed in an arc following circular bounds, with longest
  labels at the bottom (widest part) and shortest at top (narrowest part)."
  [this-elem data]
  (let [definition    (.-definition data)
        pos-label     (.-pos data)
        label-parts   (->> (str/split (.-name data) #",\s*")
                           (remove str/blank?))

        ;; Circle geometry: viewBox is 800x800, radius ≈ 300
        ;; Background gradient has radius 0.75 * main radius
        bg-radius     225
        font-size     18
        line-height   48

        ;; Sort by width, then distribute longest-first from bottom up
        segments      (->> (str/split (str/join " • " label-parts) #"\s*•\s*")
                           (sort-by shared/glyph-width))

        ;; Reduce font size when many long labels exceed threshold
        total-width   (reduce + (map shared/glyph-width segments))
        small-font?   (> total-width 60)

        ;; Calculate max width for a line at given y-position using circle equation
        width-at-y    (fn [y]
                        (let [y-abs     (js/Math.abs y)
                              r-squared (* bg-radius bg-radius)
                              y-squared (* y-abs y-abs)]
                          (if (>= y-abs bg-radius)
                            0
                            (* 2 (js/Math.sqrt (- r-squared y-squared))))))

        bottom-y      -5
        max-lines     5

        ;; Distribute segments across lines respecting circular bounds
        wrapped-lines (loop [remaining (reverse segments)
                             lines     []
                             line-idx  0]
                        (if (or (empty? remaining) (>= line-idx max-lines))
                          lines
                          (let [y-pos     (- bottom-y (* line-idx line-height))
                                max-width (* (width-at-y y-pos) 0.85)
                                current   (atom "")
                                used      (atom 0)]
                            (doseq [seg remaining]
                              (let [candidate (if (str/blank? @current)
                                                seg
                                                (str @current " • " seg))
                                    width     (* (shared/glyph-width candidate) font-size)]
                                (when (<= width max-width)
                                  (reset! current candidate)
                                  (swap! used inc))))
                            (if (zero? @used)
                              (recur (rest remaining)
                                     (conj lines (first remaining))
                                     (inc line-idx))
                              (recur (drop @used remaining)
                                     (conj lines @current)
                                     (inc line-idx))))))

        n-lines       (count wrapped-lines)

        ;; Definition positioning and wrapping
        def-height    24
        def-start-y   40
        max-def-chars 35
        def-lines     (when definition
                        (reduce
                          (fn [lines word]
                            (let [current (peek lines)]
                              (if (> (+ (count current) 1 (count word)) max-def-chars)
                                (conj lines word)
                                (conj (pop lines) (str current " " word)))))
                          [(first (str/split definition #"\s+"))]
                          (rest (str/split definition #"\s+"))))]

    ;; Render label lines from bottom up
    (doseq [[idx line] (map-indexed vector wrapped-lines)]
      (when-not (str/blank? line)
        (let [parts      (str/split line #"\s*•\s*")
              line-tspan (-> (d3/select this-elem)
                             (.append "tspan")
                             (.attr "x" 0)
                             (.attr "dy" (str (if (zero? idx)
                                                bottom-y
                                                (- line-height)) "px"))
                             (.attr "text-anchor" "middle")
                             (.attr "class" (when small-font? "subject-label-small")))]
          (doseq [[part-idx part] (map-indexed vector parts)]
            (when-not (zero? part-idx)
              (-> line-tspan
                  (.append "tspan")
                  (.attr "class" "subject-label-separator")
                  (.text " • ")))
            (-> line-tspan
                (.append "tspan")
                (.text part))))))

    ;; Render definition lines in lower half
    (when def-lines
      (doseq [[idx line] (map-indexed vector def-lines)]
        (let [first-line? (zero? idx)
              ;; Calculate relative offset from last label to definition position
              ;; dy = target_pos - current_pos = def-start-y - (bottom-y - (n-1) * line-height)
              dy          (if first-line?
                            (+ def-start-y (- bottom-y) (* (dec n-lines) line-height))
                            def-height)
              container   (-> (d3/select this-elem)
                              (.append "tspan")
                              (.attr "x" 0)
                              (.attr "dy" (str dy "px"))
                              (.attr "text-anchor" "middle")
                              (.attr "font-size" "0.45em"))]
          (when (and pos-label first-line?)
            (-> container
                (.append "tspan")
                (.attr "class" "definition-pos svg-text-outlined")
                (.attr "font-variant" "small-caps")
                (.text (str pos-label " | "))))
          (-> container
              (.append "tspan")
              (.attr "class" "definition-text svg-text-outlined")
              (.text line)))))))

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
                           "radial-item radial-item--subject svg-text-outlined")))

        ;; LABEL ROTATION LOGIC (most complex part of the function):
        ;; For subject (center): simple vertical offset, no rotation
        ;; For other labels: 4-stage transformation.
        (.attr "transform" (fn [d]
                             (if (.-subject (.-data d))
                               ;; Center label: just offset upward slightly
                               "translate(0,-4) "

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
                              (or (.-theme ^js (.-data d)) "#333")))
        (.attr "fill" (fn [d]
                        (if (.-spacer ^js (.-data d)) "transparent" "#333")))
        (.text (fn [d]
                 (let [data ^js (.-data d)]
                   (when-not (or (.-spacer data) (.-subject data))
                     (let [s (.-name data)]
                       (if (and (string? s) (> (count s) 20))
                         (str (subs s 0 18) shared/omitted)
                         s))))))
        (.on "click" (fn [_ d]
                       (when-let [href (.-href (.-data d))]
                         (reset! shared/post-navigate {:scroll :diagram})
                         (shared/navigate-to href))))
        (add-title)

        ;; Render subject labels with horizontal layout
        (.each (fn [d]
                 (this-as this-elem
                   (let [data ^js (.-data d)]
                     (when (.-subject data)
                       (render-subject-labels-horizontal this-elem data))))))

        (.append "tspan")
        (.attr "class" "sense-paragraph")
        (.attr "dy" "2px")
        (.attr "dx" "2.5px")
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
  [subentity elem {:keys [subject languages entity] :as opts}]
  (when elem
    ;; Clear old contents first to prevent duplicate SVGs accumulating in DOM.
    (when-let [existing-svg (.-firstChild elem)]
      (.remove existing-svg))
    (let [width     800
          height    800

          ;; Prepare hierarchical data structure
          k->label' (->localised-labeler opts)
          data      (prepare-radial-data subject subentity entity k->label' languages)

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

          ;; Create the SVG container, named for assistive tech after the subject.
          svg       (create-radial-svg
                      elem width height cx cy
                      (i18n/da-en languages
                        (str "Relationsdiagram for " (.-name data))
                        (str "Relations diagram for " (.-name data))))]

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

      ;; Transparent clickable circle for toggling full-screen mode.
      ;; Positioned last so it's on top, extending to where the nodes are.
      (let [languages (:languages opts)]
        (-> svg
            (.append "circle")
            (.attr "class" "toggle-full-screen")
            (.attr "cx" 0)
            (.attr "cy" 0)
            (.attr "r" radius)
            (.attr "fill" "transparent")
            (.style "cursor" (if (:full-screen opts) "zoom-out" "zoom-in"))
            (.on "click" (fn [_] (shared/toggle-full-screen!)))
            (.append "title")
            (.text (if (:full-screen opts)
                     (i18n/da-en languages "Minimér" "Minimize")
                     (i18n/da-en languages "Maksimér" "Maximize")))))

      (.node svg))))

(defn build-radial!
  [entity elem {:keys [subject] :as opts}]
  ;; Uses try-static-render since this runs in a ref callback, outside React's
  ;; render cycle where try-render and error boundaries can't catch errors.
  (error/try-static-render elem
    (build-radial!* entity elem opts)))

(def sunburst-palette
  "Per-branch hues, reusing the shared diagram theme colours. Lightness is
  normalised per arc in `build-sunburst!*`, so only the hue/saturation matter."
  (vec shared/theme))

(defn- redistribute-x!
  "Soften partition slice sizes in place: redistribute each node's children
  across the parent's angular span proportional to (value^power) instead of
  value, so a single huge branch doesn't squeeze its siblings into slivers.
  power<1 compresses the differences (0.5 = square root).

  NOTE: mutates the D3 layout nodes in place (`set!` on x0/x1) and walks the
  child arrays with `aget`/`alength` — the most imperative spot in the sunburst.
  If anything looks off, re-check against a synset with heavy multiple
  inheritance: such synsets are tree-ified into repeated nodes (the same id
  under several parents), and each repeat is laid out independently here."
  [node power]
  (when-let [kids (.-children node)]
    (let [weights (mapv (fn [c] (math/pow (.-value c) power)) kids)
          total   (reduce + weights)
          span    (- (.-x1 node) (.-x0 node))]
      (loop [x (.-x0 node)
             i 0]
        (when (< i (alength kids))
          (let [c (aget kids i)
                w (* span (/ (nth weights i) total))]
            (set! (.-x0 c) x)
            (set! (.-x1 c) (+ x w))
            (redistribute-x! c power)
            (recur (+ x w) (inc i))))))))

(defn- render-centre-labels!
  "Render sense `parts` stacked one per line and vertically centred in the
  `text-sel` <text> element, with a faded `.subject-label-separator` dot trailing
  every line but the last — the radial's grey separator, adapted to the narrow
  centre where senses can't sit inline. When `link?`, a matching grey ↪ is
  prepended to the first line, marking the centre as a navigable reference."
  [text-sel parts link?]
  (-> text-sel (.selectAll "tspan") (.remove))
  (let [n      (count parts)
        line-h 1.15]
    (doseq [[i part] (map-indexed vector parts)]
      (let [row (-> text-sel
                    (.append "tspan")
                    (.attr "x" 0)
                    ;; First line lifts the block up so it stays centred.
                    (.attr "dy" (if (zero? i)
                                  (str (- 0.32 (* (/ (dec n) 2.0) line-h)) "em")
                                  (str line-h "em"))))]
        (when (and link? (zero? i))
          (-> row
              (.append "tspan")
              (.attr "class" "subject-label-separator")
              (.text "↪ ")))
        (-> row (.append "tspan") (.text part))
        (when (< i (dec n))
          (-> row
              (.append "tspan")
              (.attr "class" "subject-label-separator")
              (.text " •")))))))

(def sunburst-width
  "Intrinsic SVG width of the sunburst; the viewBox scales it to its container."
  800)

(def sunburst-radius
  "Width of one ring band, and radius of the centre hit-circle."
  (* sunburst-width 0.15))

(def sunburst-label-px
  "Font size of the arc labels; used to measure whether a word fits its ring."
  12)

(def sunburst-centre-px
  "Base centre font size (mirrors --font-size-l); shrunk per-label to fit."
  24)

(defn- arc-visible?
  "Whether arc `d` falls within the three rings drawn around the focus."
  [d]
  (and (<= (.-y1 d) 3) (>= (.-y0 d) 1) (> (.-x1 d) (.-x0 d))))

(defn- label-visible?
  "Whether arc `d` is visible and wide enough to carry a readable label."
  [d]
  (and (<= (.-y1 d) 3) (>= (.-y0 d) 1)
       (> (* (- (.-y1 d) (.-y0 d))
             (- (.-x1 d) (.-x0 d))) 0.045)))

(def sunburst-arc
  "Arc generator mapping partition coords (x0/x1 angles, y0/y1 rings) to a path."
  (-> (.arc d3)
      (.startAngle (fn [d] (.-x0 d)))
      (.endAngle (fn [d] (.-x1 d)))
      (.padAngle (fn [d] (min (/ (- (.-x1 d) (.-x0 d)) 2) 0.004)))
      (.padRadius (* sunburst-radius 1.5))
      (.innerRadius (fn [d] (* (.-y0 d) sunburst-radius)))
      (.outerRadius (fn [d] (max (* (.-y0 d) sunburst-radius)
                                 (- (* (.-y1 d) sunburst-radius) 1))))))

(defn- label-fit
  "Arc `d`'s label, kept whole where it fits the ring band, else truncated with
  an ellipsis. The 0.97 factor lets labels run close to the ring edge before
  cutting, leaving only a minimal margin."
  [d]
  (let [s    (str (.. d -data -name))
        maxw (* 0.97 sunburst-radius)
        w    (* (shared/glyph-width s) sunburst-label-px)]
    (if (<= w maxw)
      s
      (let [k (max 1 (int (* (count s) (/ maxw w))))]
        (str (subs s 0 k) shared/omitted)))))

(defn- label-transform
  "SVG transform placing arc `d`'s label along its ring, flipped upright on the
  bottom half."
  [d]
  (let [x (* (/ (+ (.-x0 d) (.-x1 d)) 2) (/ 180 math/PI))
        y (* (/ (+ (.-y0 d) (.-y1 d)) 2) sunburst-radius)]
    (str "rotate(" (- x 90) ") translate(" y ",0) "
         "rotate(" (if (< x 180) 0 180) ")")))

(defn- sunburst-colour
  "Fill for arc `d`: its top-level branch keeps a shared-theme hue at full
  strength, while deeper rings lighten a little so the hierarchy stays readable."
  [d]
  (let [top  (loop [n d]
               (if (> (.-depth n) 1) (recur (.-parent n)) n))
        idx  (mod (.indexOf (.-children (.-parent top)) top)
                  (count sunburst-palette))
        base (.hsl d3 (nth sunburst-palette idx))]
    (set! (.-l base) (min 0.8 (+ (.-l base)
                                 (* (dec (.-depth d)) 0.12))))
    (.formatHex base)))

(defn- reduced-motion?
  "Whether the user has asked for reduced motion. The sunburst zoom is a pure
  D3 transition (JS-driven), so CSS `prefers-reduced-motion` can't reach it — we
  read the media query here and collapse the durations instead."
  []
  (boolean (some-> js/window
                   (.matchMedia "(prefers-reduced-motion: reduce)")
                   (.-matches))))

(defn- arc-tooltip
  "Slash-joined ancestry path of arc `d`, for its <title> hover tooltip."
  [d]
  (->> (.ancestors d)
       (map (fn [n] (.. n -data -name)))
       (reverse)
       (str/join " / ")))

(defn- create-sunburst-svg
  "Append the base sunburst <svg> to `elem`: viewBox-centred on the origin and
  exposed to assistive tech as a single labelled image (`role=img` + `aria-label`
  plus a <title>), since the arc geometry itself isn't meaningfully navigable by
  a screen reader — the table view and zoom-history breadcrumb are the
  accessible paths through the same data."
  [elem aria-label]
  (let [svg (-> (.select d3 elem)
                (.append "svg")
                (.attr "class" "hyponym-sunburst-diagram__svg")
                (.attr "role" "img")
                (.attr "aria-label" aria-label)
                (.attr "viewBox" #js [(/ (- sunburst-width) 2)
                                      (/ (- sunburst-width) 2)
                                      sunburst-width sunburst-width]))]
    (-> svg (.append "title") (.text aria-label))
    svg))

(defn- append-paper-pattern!
  "Define the tiled paper-texture pattern used by the slice overlay (560x420 =
  the PNG's native size, so its grain matches the rest of the page)."
  [svg]
  (-> svg
      (.append "defs")
      (.append "pattern")
      (.attr "id" "sunburst-paper")
      (.attr "patternUnits" "userSpaceOnUse")
      (.attr "width" 560)
      (.attr "height" 420)
      (.append "image")
      (.attr "href" "/images/exclusive-paper.png")
      (.attr "width" 560)
      (.attr "height" 420)))

(defn- render-sunburst-arcs
  "Append the coloured arc layer for `root`'s descendants and return the path
  selection. Each arc carries a slash-joined ancestry <title> for hover."
  [svg root]
  (let [path (-> svg
                 (.append "g")
                 (.selectAll "path")
                 (.data (.slice (.descendants root) 1))
                 (.join "path")
                 (.attr "fill" sunburst-colour)
                 (.attr "fill-opacity" (fn [d] (if (arc-visible? (.-current d)) 1 0)))
                 (.attr "pointer-events" (fn [d] (if (arc-visible? (.-current d)) "auto" "none")))
                 (.attr "d" (fn [d] (sunburst-arc (.-current d)))))]
    (-> path (.append "title") (.text arc-tooltip))
    path))

(defn- render-sunburst-texture
  "Append the paper-texture overlay layer, bound to the same nodes as the arc
  layer so their shared `current` coords keep both aligned through zooms. Purely
  decorative, so it's hidden from assistive tech and ignores pointer events."
  [svg root]
  (-> svg
      (.append "g")
      (.attr "class" "hyponym-sunburst__texture")
      (.attr "aria-hidden" "true")
      (.attr "pointer-events" "none")
      (.selectAll "path")
      (.data (.slice (.descendants root) 1))
      (.join "path")
      (.attr "fill" "url(#sunburst-paper)")
      (.attr "fill-opacity" (fn [d] (if (arc-visible? (.-current d)) 1 0)))
      (.attr "d" (fn [d] (sunburst-arc (.-current d))))))

(defn- render-sunburst-labels
  "Append the arc-label layer for `root`'s descendants and return the selection."
  [svg root]
  (-> svg
      (.append "g")
      (.attr "pointer-events" "none")
      (.attr "text-anchor" "middle")
      (.selectAll "text")
      (.data (.slice (.descendants root) 1))
      (.join "text")
      (.attr "class" "hyponym-sunburst__label")
      (.attr "fill" "#fff")
      (.attr "dy" "0.32em")
      (.attr "fill-opacity" (fn [d] (if (label-visible? (.-current d)) 1 0)))
      (.attr "transform" (fn [d] (label-transform (.-current d))))
      (.text (fn [d] (label-fit d)))))

(defn- build-sunburst!*
  "Build and render a zoomable hyponym sunburst in `elem` from prepared `tree`
  data ({:name :href :children}, labels already localised).

  Arc area is proportional to a softened leaf count; click any wedge (or focus
  it and press Enter/Space) to focus it (zoom). The centre acts like the radial's
  centre when it is the current synset (root) — toggling full-screen — and
  navigates to the synset otherwise. The `nav` atom is reset to the root → focus
  breadcrumb on every zoom, so the legend column can step back up."
  [tree elem nav {:keys [entity languages full-screen]}]
  (when (and elem tree)
    (when-let [existing (.-firstChild elem)]
      (.remove existing))
    (let [tau           (* 2 math/PI)
          data          (clj->js tree)
          ;; Centre shows the synset's curated short label(s) (dns:shortLabel):
          ;; the listed senses minus the "…" marker, joined by the middle dot.
          subject-parts (->> (i18n/select-label languages (:dns/shortLabel entity))
                             (shared/sense-labels shared/synset-sep)
                             (map remove-subscript)
                             (remove #{shared/omitted})
                             (distinct)
                             (vec))
          aria-label    (i18n/da-en languages
                          (str "Hyponym-soldiagram for " (str/join " · " subject-parts))
                          (str "Hyponym sunburst for " (str/join " · " subject-parts)))
          hierarchy     (-> (.hierarchy d3 data)
                            (.sum (fn [d] (if (.-children d) 0 1)))
                            (.sort (fn [a b] (- (.-value b) (.-value a)))))
          height        (.-height hierarchy)
          root          ((-> (.partition d3)
                             (.size #js [tau (inc height)]))
                         hierarchy)
          ;; Soften slice sizes so a dominant branch leaves room for siblings.
          _             (redistribute-x! root 0.5)
          _             (.each root (fn [d] (set! (.-current d) d)))
          ;; Reduced motion → instant transitions (the zoom is JS-driven, so CSS
          ;; prefers-reduced-motion can't reach it).
          zoom-ms       (if (reduced-motion?) 0 600)
          fade-ms       (if (reduced-motion?) 0 300)
          visible-now?  (fn [el] (> (js/parseFloat (.getAttribute el "fill-opacity")) 0))
          centre-cursor (fn [root?] (if root?
                                      (if full-screen "zoom-out" "zoom-in")
                                      "pointer"))
          centre-title  (fn [root?] (if root?
                                      (if full-screen
                                        (i18n/da-en languages "Minimér" "Minimize")
                                        (i18n/da-en languages "Maksimér" "Maximize"))
                                      (i18n/da-en languages
                                        "Gå til dette synset"
                                        "Go to this synset")))
          svg           (create-sunburst-svg elem aria-label)
          _             (append-paper-pattern! svg)
          focus         (atom root)
          centre-label  (-> svg
                            (.append "text")
                            (.attr "class" "hyponym-sunburst__centre")
                            (.attr "fill" "#333")
                            (.attr "text-anchor" "middle"))
          path          (render-sunburst-arcs svg root)
          texture-path  (render-sunburst-texture svg root)
          label         (render-sunburst-labels svg root)
          ;; Shown (faded in) only while the focus has no hyponyms to drill into.
          empty-ring    (-> svg
                            (.append "path")
                            (.attr "class" "hyponym-sunburst__empty-ring")
                            (.attr "fill" "#e6e6e6")
                            (.attr "pointer-events" "none")
                            (.attr "d" (sunburst-arc #js {:x0 0 :x1 tau :y0 1 :y1 2}))
                            (.attr "fill-opacity" (if (.-children root) 0 1)))
          ;; Transparent hit-circle over the centre, on top of everything.
          centre-target (-> svg
                            (.append "circle")
                            (.attr "class" "hyponym-sunburst__center-target")
                            (.attr "r" sunburst-radius)
                            (.attr "fill" "transparent")
                            (.attr "pointer-events" "all")
                            (.style "cursor" (centre-cursor true)))]
      (-> empty-ring
          (.append "title")
          (.text (i18n/da-en languages "Ingen hyponymer" "No hyponyms")))
      (letfn [(navigate-centre! []
                ;; Root centre toggles full-screen; a zoomed-in centre navigates
                ;; to that synset's own page.
                (if (identical? @focus root)
                  (shared/toggle-full-screen!)
                  (when-let [href (.. @focus -data -href)]
                    ;; Reset to the radial so navigating reads as a page change,
                    ;; not a silent re-root.
                    (swap! shared/state assoc-in shared/diagram-mode-path :radial)
                    (reset! shared/post-navigate {:scroll :diagram})
                    (shared/navigate-to href))))
              (render-centre! [p]
                ;; Root → its short-label senses stacked with a faded dot between
                ;; them; deeper → the focused node's own name, prefixed with the
                ;; grey link arrow (the centre navigates there).
                (let [parts  (if (and (identical? p root) (seq subject-parts))
                               subject-parts
                               [(str (.. p -data -name))])
                      link?  (not (identical? p root))
                      budget (* 2 sunburst-radius 0.9)
                      ;; Size to the widest single sense so each stacked line
                      ;; stays legible rather than shrinking to fit them inline.
                      maxw   (apply max 1 (map shared/glyph-width parts))
                      fit    (max 16 (min sunburst-centre-px (math/floor (/ budget maxw))))]
                  (.style centre-label "font-size" (str fit "px"))
                  (render-centre-labels! centre-label parts link?)))
              (update-nav! [p]
                ;; Publish root → focus breadcrumb for the legend column.
                (reset! nav
                        (vec (map-indexed
                               (fn [_ n]
                                 {:name     (.. n -data -name)
                                  :last?    (identical? n p)
                                  :on-click (fn [_] (zoom-to n))})
                               (reverse (.ancestors p))))))
              (zoom-to [p]
                (reset! focus p)
                (render-centre! p)
                (.attr centre-label "text-decoration" "none")
                (let [root? (identical? p root)]
                  (.style centre-target "cursor" (centre-cursor root?))
                  (-> centre-target (.select "title") (.text (centre-title root?))))
                (-> empty-ring (.transition) (.duration fade-ms)
                    (.attr "fill-opacity" (if (.-children p) 0 1)))
                (update-nav! p)
                (.each root
                       (fn [d]
                         (set! (.-target d)
                               #js {:x0 (* (max 0 (min 1 (/ (- (.-x0 d) (.-x0 p))
                                                            (- (.-x1 p) (.-x0 p))))) tau)
                                    :x1 (* (max 0 (min 1 (/ (- (.-x1 d) (.-x0 p))
                                                            (- (.-x1 p) (.-x0 p))))) tau)
                                    :y0 (max 0 (- (.-y0 d) (.-depth p)))
                                    :y1 (max 0 (- (.-y1 d) (.-depth p)))})))
                (let [t (-> svg (.transition) (.duration zoom-ms))]
                  (-> path
                      (.transition t)
                      (.tween "data" (fn [d]
                                       (let [i (.interpolate d3 (.-current d) (.-target d))]
                                         (fn [x] (set! (.-current d) (i x))))))
                      (.filter (fn [d] (this-as this (or (visible-now? this)
                                                         (arc-visible? (.-target d))))))
                      (.attr "fill-opacity" (fn [d] (if (arc-visible? (.-target d)) 1 0)))
                      (.attr "pointer-events" (fn [d] (if (arc-visible? (.-target d)) "auto" "none")))
                      ;; Keep tab order in step with visibility: only on-screen arcs are focusable.
                      (.attr "tabindex" (fn [d] (if (arc-visible? (.-target d)) 0 nil)))
                      (.attrTween "d" (fn [d] (fn [] (sunburst-arc (.-current d))))))
                  (-> texture-path
                      (.filter (fn [d] (this-as this (or (visible-now? this)
                                                         (arc-visible? (.-target d))))))
                      (.transition t)
                      (.attr "fill-opacity" (fn [d] (if (arc-visible? (.-target d)) 1 0)))
                      (.attrTween "d" (fn [d] (fn [] (sunburst-arc (.-current d))))))
                  (-> label
                      (.filter (fn [d] (this-as this (or (visible-now? this)
                                                         (label-visible? (.-target d))))))
                      (.transition t)
                      (.attr "fill-opacity" (fn [d] (if (label-visible? (.-target d)) 1 0)))
                      (.attrTween "transform" (fn [d] (fn [] (label-transform (.-current d))))))))]
        ;; Centre interactions: click/Enter/Space act; hover/focus underline the
        ;; label when it would navigate (i.e. when zoomed in).
        (-> centre-target
            (.append "title")
            (.text (centre-title true)))
        (let [show-link! (fn [_] (when-not (identical? @focus root)
                                   (.attr centre-label "text-decoration" "underline")))
              hide-link! (fn [_] (.attr centre-label "text-decoration" "none"))]
          (-> centre-target
              (.attr "tabindex" 0)
              (.attr "role" "button")
              (.attr "aria-label" (centre-title true))
              (.on "click" (fn [_] (navigate-centre!)))
              (.on "keydown" (fn [e] (when (#{"Enter" " "} (.-key e))
                                       (.preventDefault e)
                                       (navigate-centre!))))
              (.on "mouseenter" show-link!)
              (.on "mouseleave" hide-link!)
              (.on "focus" show-link!)
              (.on "blur" hide-link!)))
        (render-centre! root)
        (update-nav! root)
        ;; Any wedge — leaf or not — just focuses (zooms to) itself; only the
        ;; centre navigates, so behaviour is consistent at the leaves. Arcs are
        ;; focusable so the zoom is reachable by keyboard as well as pointer.
        (-> path
            (.style "cursor" "pointer")
            (.attr "tabindex" (fn [d] (if (arc-visible? (.-current d)) 0 nil)))
            (.attr "role" "button")
            (.attr "aria-label" (fn [d] (.. d -data -name)))
            (.on "click" (fn [_ d] (zoom-to d)))
            (.on "keydown" (fn [e d] (when (#{"Enter" " "} (.-key e))
                                       (.preventDefault e)
                                       (zoom-to d))))))
      (.node svg))))

(defn build-sunburst!
  "Render the hyponym sunburst for `tree` into the ref'd `elem`, publishing its
  zoom breadcrumb to the `nav` atom (owned by the parent component, so the legend
  column can react to it).

  Idempotent across resizes: the SVG is viewBox-scaled, so resizing needs no
  rebuild — and rebuilding would discard the user's zoom. A render-key stashed on
  `elem` means we only (re)build when the data (`tree`) or `:full-screen` changes;
  resizes just let CSS scale the existing SVG. (Like build-radial!, this runs in
  a ref callback, so it uses try-static-render.)"
  [tree ^js elem nav {:keys [full-screen] :as opts}]
  (when elem
    (let [render-key (str (hash tree) "|" (boolean full-screen))]
      (when (not= render-key (.-dnSunburstKey elem))
        (set! (.-dnSunburstKey elem) render-key)
        (error/try-static-render elem
          (build-sunburst!* tree elem nav opts))))))
