(ns dk.cst.dannet.web.diagram
  "The data side of the D3 diagrams in web.d3: word cloud sizes and weights,
  estimated label widths, and the order and spacing of the relations around
  the radial diagram. These are pure functions, so they also run in the JVM
  REPL; web.d3 does the drawing."
  (:require [clojure.math :as math]
            [clojure.string :as str]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.web.i18n :as i18n]
            [dk.cst.dannet.web.ui.relations :as relations]
            #?(:clj [clojure.core.memoize :as memo])))

(defn min-max-normalize
  [span low num]
  (/ (- num low) span))

(defn log-inc
  "Increment `n` by log(n)."
  [n]
  (+ n (max 1 (math/log n))))

(defn cloud-normalize
  "Normalize an ordered collection of `synsets` to fit a word cloud.
  
  The synsets should already be sorted by weight (highest first). Creates
  artificial weights by incrementing from 1 using a relative logarithmic
  increment, then normalizes these values into the range 0...1.
  
  For clouds with more than 30 items, applies highlighting to top synsets
  above a threshold. This simulates the effect of outliers by adding a bonus
  constant to weights above the threshold."
  [synsets]
  (let [weights-kvs        (map vector (reverse synsets) (iterate log-inc 1))
        low                (second (first weights-kvs))
        high               (second (last weights-kvs))
        span               (- high low)
        [threshold bonus] (if (> (count synsets) 30)
                            [(- high (math/sqrt span)) (/ span 2)]
                            [high 0])
        min-max-normalize' #(min-max-normalize (+ span bonus) low %)]
    ;; Build both result map and highlight set in single pass using reduce
    (let [[result highlight]
          (reduce (fn [[m hl] [k v]]
                    (if (> v threshold)
                      [(assoc! m k (min-max-normalize' (+ v bonus)))
                       (conj! hl k)]
                      [(assoc! m k (min-max-normalize' v))
                       hl]))
                  [(transient {}) (transient #{})]
                  weights-kvs)]
      (with-meta (persistent! result)
                 {:highlight (persistent! highlight)}))))

;; NOTE: cannot use fnil as we're limited to assoc! using transients.
(defn vec-conj
  [coll v]
  (if (nil? coll)
    [v]
    (conj coll v)))

(defn top-n-vals
  "Select `n` vals in `m` by picking the first of every val iteratively.
   Round-robins through keys, taking one value from each in turn."
  [n m]
  (let [ks    (vec (keys m))
        total (count ks)]
    (loop [i         0
           remaining n
           source    (transient m)
           result    (transient {})
           exhausted 0]
      (let [k       (nth ks i)
            vs      (get source k)
            v       (if (coll? vs)                          ; one val vs. coll
                      (first vs)
                      vs)
            rest-vs (when (coll? vs)                        ; one val vs. coll
                      (rest vs))]
        (cond
          ;; Exit conditions
          (or (zero? remaining) (= exhausted total))
          (persistent! result)

          ;; Key exhausted -> skip to next
          (not vs)
          (recur (rem (inc i) total) remaining source result exhausted)

          ;; Key has more values -> keep it
          (seq rest-vs)
          (recur (rem (inc i) total)
                 (dec remaining)
                 (assoc! source k rest-vs)
                 (assoc! result k (vec-conj (get result k) v))
                 exhausted)

          ;; Last value for key -> remove key
          :else
          (recur (rem (inc i) total)
                 (dec remaining)
                 (dissoc! source k)
                 (assoc! result k (vec-conj (get result k) v))
                 (inc exhausted)))))))

(def narrow-glyphs
  #{\f \i \l \I \j \r \t \1 \. \, \: \; \! \| \' \`})

(def wide-glyphs
  #{\m \w \M \W \æ \Æ \@ \%})

(defn- glyph-width*
  "Estimate the approximate visual width of `s` based on character widths."
  [s]
  (reduce (fn [acc ch]
            (+ acc (cond
                     (narrow-glyphs ch) 0.67
                     (wide-glyphs ch) 1.33
                     :else 1.0)))
          0
          (str s)))

(def glyph-width
  #?(:clj  (memo/lru glyph-width* :lru/threshold 5000)
     :cljs (memoize glyph-width*)))

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
  (/ size (max 1 (math/log10 (glyph-width label)))))

(defn displayed-synsets
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
        weights   (cloud-normalize synsets')
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
                           labels (shared/sense-labels #"; " s)
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
        (assoc m
          :name name'
          :sub sub)))))

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
  (let [norm-angle      (mod angle (* 2 math/PI))

        ;; Corner proximity: 0.0 at corners, 1.0 at edges
        eighth          (/ math/PI 4)
        nearest-eighth  (math/round (/ norm-angle eighth))
        dist-to-eighth  (abs (- norm-angle (* nearest-eighth eighth)))
        at-corner?      (odd? (mod nearest-eighth 8))
        corner-dist     (if at-corner?
                          (/ dist-to-eighth eighth)
                          (- 1.0 (/ dist-to-eighth eighth)))

        ;; Label rotation factor: measures how much labels are tilted
        ;; More rotation = needs more diagonal/vertical space
        ;; Less rotation (horizontal) = needs more horizontal space
        rotation-rad    (if (< norm-angle math/PI)
                          (* (- (/ math/PI 2) norm-angle) 20)
                          (* (- (* 3 (/ math/PI 2)) norm-angle) 20))
        max-rotation    31.5
        rotation-factor (/ (abs rotation-rad) max-rotation)]

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
                                        angle (* 2 math/PI (/ idx total-count))]
                                    {:offset      offset
                                     :space-score (label-space-score angle)}))
                                (range (count nodes)))

          ;; Best positions first (lowest space score)
          sorted-positions (sort-by :space-score positions)

          ;; Longest labels first (by glyph width, not character count)
          sorted-nodes     (sort-by (comp glyph-width :name) > nodes)

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
                              (top-n-vals radial-limit)
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

(defn radial-tree
  "The radial diagram of the `subject` and its relations in `subentity`, as a
  tree for d3.hierarchy, labelled with `k->label` in `languages`."
  [subject subentity full-entity k->label languages]
  (let [label      (->> (k->label subject)
                        (shared/sense-labels shared/synset-sep)
                        (shared/canonical)
                        (map remove-subscript)
                        (set)
                        (sort-by glyph-width)
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
                        (top-n-vals radial-limit))
        children   (prepare-radial-children entity' k->label)]
    {:name       label
     :title      label
     :pos        pos-label
     :definition definition
     :subject    true
     :children   children}))
