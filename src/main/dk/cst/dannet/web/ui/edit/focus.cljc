(ns dk.cst.dannet.web.ui.edit.focus
  "Where the focus goes in edit mode (see ui.edit): to a control that is
  found once the page is drawn again, or to the nearest one when a field
  replaces its controls or goes away."
  (:require [rum.core :as rum]
            [dk.cst.dannet.prefix :as prefix]))

(defn after-redraw!
  "Call `f` once the page is drawn again, i.e. after the frame in which a
  change of state is rendered."
  [f]
  #?(:cljs (js/requestAnimationFrame #(js/requestAnimationFrame f))))

(defn when-drawn!
  "Call `f` with the element that the function `find` returns, once the
  page is drawn with it. A change of state can take several frames to draw,
  so `find` is called in each frame, for about a second."
  [find f]
  #?(:cljs (letfn [(look! [frames]
                     (if-let [element (find)]
                       (f element)
                       (when (pos? frames)
                         (js/requestAnimationFrame #(look! (dec frames))))))]
             (js/requestAnimationFrame #(look! 60)))))

(def row-plus
  "A CSS selector for the button that adds a value to a row, after the
  selector of the row. A row of blank nodes has such buttons in its nested
  tables too."
  "> td > .edit-values > .edit-value > .edit-value__add")

(defn node-selector
  "A CSS selector for the field of the blank node with the `label`."
  [label]
  (str "[data-node=\"" label "\"]"))

(defn in-row
  "The first element that the CSS `selector` matches in the row of the
  property `k` in the table of `subject`, if any. The subject is the
  resource of the page, or the label of a blank node with a nested table."
  [subject k selector]
  #?(:cljs (let [table (if (symbol? subject)
                         (str (node-selector subject) " table")
                         "table.attr-val:not(.attr-val--nested)")]
             (.querySelector js/document
                             (str table " > tbody > tr[property=\""
                                  (prefix/kw->qname k) "\"] " selector)))))

(defn open-new-value!
  "Open the field for a new value in the row of the property `k` in the
  table of `subject` (see in-row), once the page is drawn with it."
  [subject k]
  (when-drawn! #(in-row subject k row-plus) #(.click %)))

(defn focus-row!
  "Move the focus to the first button of a value in the row of the property
  `k` of `subject`, once the page is drawn with it."
  [subject k]
  (when-drawn! #(in-row subject k ".edit-value .edit-value__button")
               #(.focus %)))

(defn focus-beside!
  "Move the focus to the control next to the dialog that the event `e`
  closes: the one that the CSS `selector` matches."
  [e selector]
  (some-> (.. e -target -parentNode) (.querySelector selector) (.focus)))

(defn close-fn
  "A function for the :on-close of a dialog, which sets the flag `showing?`
  of the dialog to false and moves the focus to the control next to the
  dialog that the CSS `selector` matches (see focus-beside!)."
  [showing? selector]
  (fn [e]
    (reset! showing? false)
    (focus-beside! e selector)))

(defn opens-input?
  "Does the click `e` open the input of a closed field? Not on a link or a
  control, or when it selects text."
  [e]
  #?(:clj  false
     :cljs (not (or (.closest (.-target e) "a, button, select, [tabindex]")
                    (seq (str (js/getSelection)))))))

(def refocus
  "A mixin for a field that replaces its controls. When the field has set
  ::refocus to a selector and is drawn again, the control that matches gets
  the focus, unless the focus has gone elsewhere."
  {:init       (fn [state _]
                 (assoc state ::refocus (volatile! nil)))
   :did-update (fn [state]
                 #?(:cljs
                    (when-let [selector @(::refocus state)]
                      (vreset! (::refocus state) nil)
                      (when (= js/document.body js/document.activeElement)
                        (some-> (rum/dom-node state)
                                (.querySelector selector)
                                (.focus)))))
                 state)})

(defn refocus!
  "Have the field with the component `state` move the focus to its control
  that the CSS `selector` matches, once it is drawn again (see refocus)."
  [state selector]
  (vreset! (::refocus state) selector))

(def keep-focus
  "A mixin for a field. When the field goes away while the focus is in it,
  e.g. when an added value is taken away or when its row is full, the focus
  moves to the nearest control that is on the page once it is drawn again:
  a button of the field after it, the add button of its row, a button of
  the last field in its row, or the button that adds a row to its table."
  {:will-unmount
   (fn [state]
     #?(:cljs
        (let [node (rum/dom-node state)]
          (when (.contains node js/document.activeElement)
            (let [after    (.-nextElementSibling node)
                  fields   (.-parentNode node)
                  row      (.closest node "tr")
                  table    (.closest node "table")
                  ;; not a button in the nested table of a blank node
                  own      ":scope > .edit-value__actions button"
                  controls [#(some-> after (.querySelector own))
                            #(some-> row
                                     (.querySelector (str ":scope " row-plus)))
                            #(some-> (.querySelectorAll
                                       fields
                                       ":scope > .edit-value:not(.edit-value--locked)")
                                     (array-seq)
                                     (last)
                                     (.querySelector own))
                            #(some-> (.-nextElementSibling table)
                                     (.querySelector "button"))]]
              (after-redraw!
                ;; unless the focus has gone elsewhere by itself
                #(when (= js/document.body js/document.activeElement)
                   (some-> (first (filter (fn [control]
                                            (some-> control .-isConnected))
                                          (map (fn [f] (f)) controls)))
                           (.focus))))))))
     state)})
