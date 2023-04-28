(ns dk.cst.aria.combobox
  "Helpers for ARIA-compliant combobox keyboard navigation.

  ARIA reference:
    https://w3c.github.io/aria/#combobox

  The basic design was adapted from my implementation of a roving tabindex:
    https://github.com/kuhumcst/stucco/blob/master/src/dk/cst/stucco/dom/keyboard.cljs

  See also:
    https://javascript.info/bubbling-and-capturing
    https://www.mutuallyhuman.com/blog/keydown-is-the-only-keyboard-event-we-need/"
  (:require [clojure.set :as set]))

(def spacebar
  #{" " "Spacebar"})

(def enter
  #{"Enter"})

(def up
  #{"ArrowUp" "Up"})

(def down
  #{"ArrowDown" "Down"})

(def left
  #{"ArrowLeft" "Left"})

(def right
  #{"ArrowRight" "Right"})

(def prev-item
  up)

(def next-item
  down)

(def click-item
  (set/union spacebar enter))

(def supported-keys
  (set/union prev-item next-item click-item))

(defn keydown-handler
  "Create a framework-independent, ARIA-compliant keydown handler to be used as
  a keydown handler for both the combox and the options in the listbox.

    * submit-fn: function to execute when submitting the search form.
    * combox: HTML element for the combobox.
    * listbox: HTML element for the listbox containing the options.
    * (optional) kmap: a mapping from keyboard keys to associated functions.

  Note that the options should have onclick handlers roughly equivalent to the
  provided 'submit-fn' for this handler to work properly. Additionally, for
  proper ARIA-compliance, the correct ARIA roles and attributes should be set
  on the elements (see: https://w3c.github.io/aria/#combobox)."
  [submit-fn combobox listbox & [kmap]]
  (fn [e]
    (let [elem (.-target e)]
      (if-let [action (get kmap (.-key e))]
        (action e)
        (when (supported-keys (.-key e))
          (.preventDefault e)
          (.stopPropagation e)
          (condp contains? (.-key e)
            click-item (if (= elem combobox)
                         (submit-fn)
                         (.click (.-target e)))
            prev-item (if (= elem combobox)
                        (some-> listbox .-children seq last .focus)
                        (.focus (or (.-previousElementSibling elem) combobox)))
            next-item (if (= elem combobox)
                        (some-> listbox .-children seq first .focus)
                        (.focus (or (.-nextElementSibling elem) combobox)))
            :no-op))))))
