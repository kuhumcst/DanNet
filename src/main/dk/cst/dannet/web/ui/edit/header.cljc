(ns dk.cst.dannet.web.ui.edit.header
  "What edit mode adds to the header of a page: the switch that turns it on
  and off, the link to the pending changes, the padlock of a resource that
  can't be edited, and the buttons that act on a whole entry."
  (:require [rum.core :as rum]
            [dk.cst.dannet.entry :as entry]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.web.app :as app]
            [dk.cst.dannet.web.i18n :as i18n]
            [dk.cst.dannet.web.ui.edit.control :as control]
            [dk.cst.dannet.web.ui.edit.entry :as entry-field]
            [dk.cst.dannet.web.ui.edit.lock :as lock]
            [dk.cst.dannet.web.ui.edit.session :as session]))

(rum/defc edit-mode-switch
  "A button that turns edit mode on and off, drawn as an icon. It is pressed
  while edit mode is on in `opts`."
  [{:keys [languages edit-mode nav-class] :as opts}]
  (let [label (i18n/da-en languages "Redigér" "Edit")]
    [:button.edit-mode-switch {:type         "button"
                               :class        nav-class
                               :title        label
                               :aria-pressed (str (boolean edit-mode))
                               :on-click     #(session/toggle-edit-mode!)}
     [:span.edit-mode-switch__icon {:aria-hidden "true"}]
     ;; The label names the button; its title is only a tooltip.
     [:span.visually-hidden label]]))

(rum/defc pending-changes-link
  "The number of the pending changes in `opts`: a status that is read out
  when the number changes, since an edit says nothing else, and a link to
  the list of the changes, drawn as the number, while something is pending."
  [{:keys [languages changes nav-class] :as opts}]
  (let [n     (count changes)
        label (i18n/da-en languages
                (str n (if (= n 1) " ændring" " ændringer"))
                (str n (if (= n 1) " change" " changes")))]
    [:<>
     [:span.visually-hidden {:role "status"} label]
     (when (seq changes)
       [:a.pending-changes {:href  prefix/changes-path
                            :class nav-class
                            :title label}
        [:span {:aria-hidden "true"} n]
        [:span.visually-hidden label]])]))

(defn entry-controls
  "The controls of the page header that act on the whole entry in `opts`,
  in edit mode. No entry of the data has them when the resource is `locked?`.

  A new entry has the button that takes it away, which leaves its page for
  the list of the pending changes. An entry of the data that a template
  describes has the button that removes it as a whole. An entry that is
  :removed? says so, with the button that puts it back."
  [{:keys [subject languages entity edit-mode draft? removed? edit-rules]
    :as   opts}
   locked?]
  [:<>
   (when (and edit-mode draft?)
     (entry-field/discard-entry-button
       opts subject
       (fn [_]
         (entry-field/discard-entry! opts subject)
         (app/navigate-to prefix/changes-path {:replace? true}))))
   (when (and edit-mode
              (not (or draft? removed? locked?))
              (entry/described? (:entries edit-rules) entity))
     (entry-field/remove-entry-button opts subject))
   (when removed?
     [:<>
      [:p.entry-removed
       (i18n/da-en languages
         "Denne ressource fjernes."
         "This resource is being removed.")]
      (entry-field/restore-entry-button
        opts subject
        (fn [e]
          ;; the button goes away, so a link of the header gets the focus
          #?(:cljs (some-> (.closest (.-currentTarget e) "header")
                           (.querySelector "a")
                           (.focus)))
          (entry-field/restore-entry! subject)))])])

(defn locked?
  "Is the resource of the page in `opts` one that edit mode can't edit?"
  [{:keys [edit-mode] :as opts}]
  (and edit-mode (not (lock/editable-resource? opts))))

(defn padlock
  "The padlock of a page whose resource can't be edited, which says so to a
  reader of `opts`."
  [{:keys [comments] :as opts}]
  (control/padlock (get-in comments [:locked :resource])))

(defn controls
  "What edit mode adds below the header of the page in `opts`: the controls
  of its entry, which a `locked?` resource of the data lacks (see
  entry-controls), and the link to the pending changes and the switch."
  [opts locked?]
  [:<>
   (entry-controls opts locked?)
   ;; the editor is a preview in development only
   (when shared/development?
     [:<>
      (pending-changes-link opts)
      (edit-mode-switch opts)])])
