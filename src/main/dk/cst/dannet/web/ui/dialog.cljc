(ns dk.cst.dannet.web.ui.dialog
  "Modal dialogs: a dialog with a title, and a wizard that leads through the
  steps of a choice that has to be confirmed.

  A step of a wizard is data, a map of what the step says and asks:

  - :heading, what the step is about, which names it for a screen reader;
  - :figures, the numbers that sum the step up, each a map of the :value,
    of the short :label that says what it counts, and of a :title that
    says it in full when the pointer is on the figure;
  - :text, its paragraphs;
  - :details, what the summary leaves out, as lists that are folded away
    until they are asked for: each a map of its :summary and its :items;
  - :choices, its checkboxes, each a map of the :key of its answer and its
    :label;
  - :warning, what to think twice about.

  A text is in the language of the reader already. A paragraph, an item or
  a label can also be a resource, as a keyword, which the wizard draws with
  the function that its flow has for it. An item can be a pair of a short
  lead, e.g. a number, and what the lead is about. The figures are large
  and in the middle of the step, side by side. The items and the choices
  are rows of two columns below them, so that they line up: a lead or a
  checkbox, and then the text or the resource. The answers are a map of a
  key to true or false, which a wizard starts with and gives back."
  (:require [rum.core :as rum]
            [dk.cst.dannet.web.i18n :as i18n]))

(def show-modal
  "A mixin that opens a <dialog> as a modal when it is drawn. The focus
  goes to the element that is marked for it with data-focus. Whether the
  mouse was last pressed outside of the dialog is kept as
  ::pressed-outside?."
  {:will-mount (fn [state]
                 (assoc state ::pressed-outside? (volatile! false)))
   :did-mount (fn [state]
                #?(:cljs (let [dialog (rum/dom-node state)]
                           (.showModal dialog)
                           (some-> (.querySelector dialog "[data-focus]")
                                   (.focus))))
                state)})

(defn- close!
  "Close the dialog that the event `e` comes from."
  [e]
  #?(:cljs (.close (.closest (.-target e) "dialog"))))

(defn- outside?
  "Is the place of the mouse event `e` outside of the dialog that it is on?
  A click on the backdrop is a click on the dialog itself, and so is one on
  its padding, so the place decides."
  [e]
  #?(:cljs
     (let [dialog (.-currentTarget e)
           box    (.getBoundingClientRect dialog)]
       (and (= dialog (.-target e))
            (not (and (<= (.-left box) (.-clientX e) (.-right box))
                      (<= (.-top box) (.-clientY e) (.-bottom box))))))))

(rum/defcs dialog < show-modal
  "A modal dialog named by `title`, for a reader with `languages`: a header
  with the title and a button that closes the dialog, above the `content`.
  It calls `on-close` when it closes, also by Escape or by a click outside
  of it.

  The `class` says which kind of dialog it is, and a `heading` is shown in
  place of the title. The focus starts on the element of the content that
  has data-focus."
  [state languages {:keys [title heading class on-close]} content]
  (let [close            (i18n/da-en languages "Luk" "Close")
        pressed-outside? (::pressed-outside? state)]
    [:dialog.dialog
     {:class         class
      :aria-label    title
      :on-close      on-close
      ;; a press in the dialog that ends outside of it, e.g. one that
      ;; selects a text, doesn't close the dialog
      :on-mouse-down #(vreset! pressed-outside? (outside? %))
      :on-click      #(when (and @pressed-outside? (outside? %))
                        (.close (.-currentTarget %)))}
     [:header
      [:h2 (or heading title)]
      [:button.edit-value__button {:type     "button"
                                   :title    close
                                   :on-click close!}
       [:span.edit-value__icon.edit-value__icon--remove {:aria-hidden "true"}]
       [:span.visually-hidden close]]]
     content]))

(def focus-step
  "A mixin for the wizard. When the step changes, the focus moves to the
  new step, so that a screen reader reads it from its start. The time when
  the step was drawn is kept as ::changed."
  {:will-mount (fn [state]
                 (assoc state
                   ::drawn (volatile! 0)
                   ::changed (volatile! #?(:clj 0 :cljs (js/Date.now)))))
   :did-update (fn [state]
                 #?(:cljs
                    (let [step @(::step state)]
                      (when (not= step @(::drawn state))
                        (vreset! (::drawn state) step)
                        (vreset! (::changed state) (js/Date.now))
                        (some-> (rum/dom-node state)
                                (.querySelector "[data-focus]")
                                (.focus)))))
                 state)})

(defn- figures-list
  "The `figures` of a step as a list of names and values: each number with
  what it counts. A figure with a title has the title as a tooltip, and a
  screen reader gets the title in place of the shorter label."
  [figures]
  [:dl.wizard__figures
   (for [[n {:keys [value label title]}] (map-indexed vector figures)]
     [:div {:key n :title title}
      [:dt (if title
             [:<>
              [:span {:aria-hidden "true"} label]
              [:span.visually-hidden title]]
             label)]
      [:dd value]])])

(rum/defcs wizard < (rum/local 0 ::step)
                    (rum/local nil ::answers)
                    focus-step
  "A dialog that leads through the steps of the `flow`, for a reader with
  `languages`. It calls `on-done` with the answers when the last step is
  confirmed, and `on-close` when the dialog closes without that.

  The flow is a map of:

  - :title, the title of the dialog;
  - :about, what it is about, shown below the title at every step;
  - :answers, the answers that it starts with;
  - :steps-fn, a function of the answers so far that returns the steps
    (see the namespace);
  - :resource-fn, a function that draws a resource in a text;
  - :done, the text of the button that confirms.

  The focus starts on the first step, never on that button."
  [state languages
   {:keys [title about answers steps-fn resource-fn done] :as flow}
   {:keys [on-done on-close]}]
  (let [step    (::step state)
        current (or @(::answers state) answers)
        steps   (vec (steps-fn current))
        i       (min @step (dec (count steps)))
        draw    #(if (keyword? %) ((or resource-fn str) %) %)
        {:keys [heading figures text details choices warning]} (get steps i)]
    (dialog
      languages
      {:title    title
       :class    "wizard"
       :on-close on-close
       ;; how far the wizard is stands beside its title
       :heading  [:<>
                  title
                  ;; a space, so that the two aren't read out as one word
                  " "
                  (when (next steps)
                    [:span#wizard-progress.wizard__progress
                     (i18n/da-en languages
                       (str "Trin " (inc i) " af " (count steps))
                       (str "Step " (inc i) " of " (count steps)))])]}
      [:<>
       (when about
         [:p.wizard__about (draw about)])
       ;; The step takes the focus, and then says its heading and how far it
       ;; is. It can be reached by Tab too, since a long step scrolls. It is
       ;; keyed by its number, so that a new step starts with its details
       ;; folded.
       [:section.wizard__step
        {:key              i
         :tab-index        0
         :data-focus       true
         :aria-labelledby  "wizard-step"
         :aria-describedby (when (next steps) "wizard-progress")}
        ;; the figures say the same to the eye, so the heading isn't shown
        [:h3#wizard-step.visually-hidden heading]
        (when (seq figures)
          (figures-list figures))
        (for [[n paragraph] (map-indexed vector text)]
          [:p {:key n} (draw paragraph)])
        (when (seq choices)
          [:fieldset {:aria-labelledby "wizard-step"}
           [:ul.wizard__rows
            (for [{:keys [key label]} choices]
              [:li {:key (pr-str key)}
               [:label
                [:input {:type      "checkbox"
                         :checked   (boolean (get current key))
                         :on-change #(reset! (::answers state)
                                             (assoc current
                                               key (.. % -target -checked)))}]
                [:span (draw label)]]])]])
        (when warning
          [:p#wizard-warning.wizard__warning warning])
        (when (seq details)
          [:div.wizard__folds
           (for [[n {:keys [summary items]}] (map-indexed vector details)]
             [:details.wizard__details {:key n}
              [:summary summary]
              [:ul.wizard__rows
               (for [[n item] (map-indexed vector items)
                     :let [[lead about] (if (vector? item) item [nil item])]]
                 [:li {:key n}
                  [:span.wizard__lead lead]
                  [:span (draw about)]])]])])]
       [:footer
        [:button {:type "button" :on-click close!}
         (i18n/da-en languages "Annullér" "Cancel")]
        (when (pos? i)
          [:button {:type "button" :on-click #(reset! step (dec i))}
           (i18n/da-en languages "Tilbage" "Back")])
        (if (= i (dec (count steps)))
          ;; The button can be where the one for the next step was, or the
          ;; one that opened the wizard, so it leaves out the second click of
          ;; a double click on that.
          [:button.wizard__done
           {:type             "button"
            :key              "done"
            :aria-describedby (when warning "wizard-warning")
            :on-click         #?(:clj  nil
                                 :cljs #(when (< 500 (- (js/Date.now)
                                                        @(::changed state)))
                                          (on-done current)))}
           done]
          [:button {:type "button" :key "next" :on-click #(reset! step (inc i))}
           (i18n/da-en languages "Næste" "Next")])]])))
