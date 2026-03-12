(ns dk.cst.dannet.web.ui.form
  (:require [dk.cst.dannet.shared :as shared]
            #?(:clj  [dk.cst.dannet.web.ui.error :as error]
               :cljs [dk.cst.dannet.web.ui.error :as error :include-macros true])
            #?(:cljs [lambdaisland.uri :as uri])
            #?(:cljs [dk.cst.dannet.web.ui.search.aria :as aria])))

(defn- form-elements->query-params
  "Retrieve a map of query parameters from HTML `form-elements`, mimicking how
  the browser would pick these up.

  Unchecked checkboxes are excluded to match standard HTML form submission
  behaviour, where only checked checkboxes contribute their value."
  [form-elements]
  (into {} (for [form-element form-elements
                 :when (not-empty (.-name form-element))
                 :when (or (not= (.-type form-element) "checkbox")
                           (.-checked form-element))]
             [(.-name form-element) (.-value form-element)])))

(defn submit-form
  "Submit a form `target` element (optionally with a custom `query-string`)."
  [target & [query-str]]
  #?(:cljs (let [action    (.-action target)
                 query-str (or query-str
                               (-> (.-elements target)
                                   (form-elements->query-params)
                                   (uri/map->query-string)))
                 url       (str action (when query-str
                                         (str "?" query-str)))]
             (js/document.activeElement.blur)
             (shared/navigate-to url))))

;; TODO: handle other methods (only handles GET for now)
(defn on-submit
  "Generic function handling form submit events in Rum components."
  [e]
  #?(:cljs (let [target (.-target e)]
             (.preventDefault e)
             (submit-form target))))

(defn autofocus-ref
  [node]
  (when node (.focus node)))

(defn select-text
  "Select text in the target that triggers `e` with a small delay to bypass
  browser's other text selection logic."
  [e]
  #?(:cljs (js/setTimeout #(.select (.-target e)) 100)))
