(ns dk.cst.dannet.web.ui.tabs
  "A frame that shows one of several views of its content, with a tab for each
  view on its top border.

  The tabs follow the ARIA tabs pattern: the arrow keys, Home and End move
  between them, and the selected view is kept in the session at
  app/display-path."
  (:require [rum.core :as rum]
            [dk.cst.dannet.web.app :as app]
            [dk.cst.dannet.web.i18n :as i18n]))

(defn selected-view
  "The view of `tablist` that frame `id` shows in `opts`: the one chosen in the
  session, or else the first."
  [id {:keys [views] :as tablist} opts]
  (let [chosen (get-in opts (app/display-path id))]
    (or (some #(when (= chosen (:id %)) %) views)
        (first views))))

(defn- dom-id
  "The DOM id of a part of frame `id`, named by `parts`, e.g. its panel or the
  tab of a view."
  [id & parts]
  (apply str "view-" (name id) (for [part parts]
                                 (str "-" (name part)))))

(defn- select!
  "Make frame `id` show the view `view-id`."
  [id view-id]
  (swap! app/session assoc-in (app/display-path id) view-id))

(defn- on-tab-key
  "A keydown handler for the tabs of frame `id` that selects and focuses the
  next or previous of `view-ids`, or the first or last, from `selected`."
  [id view-ids selected]
  (fn [e]
    (let [n (count view-ids)
          i (.indexOf view-ids selected)
          j (case (.-key e)
              "ArrowRight" (mod (inc i) n)
              "ArrowLeft" (mod (dec i) n)
              "Home" 0
              "End" (dec n)
              nil)]
      (when j
        (.preventDefault e)
        (let [view-id (nth view-ids j)]
          (select! id view-id)
          #?(:cljs (some-> (js/document.getElementById (dom-id id view-id :tab))
                           (.focus))))))))

(rum/defc switchable
  "A frame showing `subentity` of section `id` in one of the views of
  `tablist`, with a tab for each view on its top border.

  The `tablist` is a map of :views and the :label that names their tabs, a map
  of :da and :en. A view is a map of :id, :icon, :label and :render, a fn of
  `subentity` and `opts` that returns the view's content. It is drawn on a card
  unless its :card? is false, e.g. for a table that has a border of its own."
  [id {:keys [label views] :as tablist} subentity
   {:keys [languages nav-class] :as opts}]
  (let [view     (selected-view id tablist opts)
        selected (:id view)
        view-ids (mapv :id views)
        localize #(i18n/da-en languages (:da %) (:en %))]
    ;; The card goes on the panel, not on the frame, so the frame has the same
    ;; width in every view for the container query that hides the labels.
    [:div.switchable
     ;; The nav's colour class fills the selected tab with the nav's colour.
     [:div.switchable__tabs {:class       nav-class
                             :role        "tablist"
                             :aria-label  (localize label)
                             :on-key-down (on-tab-key id view-ids selected)}
      (for [{view-id :id :keys [icon label]} views
            :let [selected? (= view-id selected)
                  label'    (localize label)]]
        [:button.switchable__tab {:key           view-id
                                  :id            (dom-id id view-id :tab)
                                  :type          "button"
                                  :role          "tab"
                                  :aria-selected (str selected?)
                                  :aria-controls (dom-id id :panel)
                                  :tab-index     (if selected? 0 -1)
                                  :on-click      #(select! id view-id)}
         ;; The label names the tab; the icon's title is only a tooltip, which
         ;; screen readers skip along with the rest of the hidden icon.
         [:span {:class       ["switchable__icon" (str "switchable__icon--" icon)]
                 :title       label'
                 :aria-hidden "true"}]
         [:span.switchable__label label']])]
     ;; The panel is the next stop after the tabs, as a view may start with
     ;; text rather than a link, e.g. the summary.
     [:div.switchable__panel {:id              (dom-id id :panel)
                              :class           (when (:card? view true) "card")
                              :role            "tabpanel"
                              :tab-index       0
                              :aria-labelledby (dom-id id selected :tab)}
      ((:render view) subentity opts)]]))
