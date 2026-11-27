(ns dk.cst.dannet.web.ui.edit.value
  "The fields of a value in edit mode (see ui.edit): the field of a value in
  a row and the field for a new one, with the content and the notes that
  the other fields and the list of the changes show a value with."
  (:require [clojure.string :as str]
            [rum.core :as rum]
            [dk.cst.dannet.change :as change]
            [dk.cst.dannet.entry :as entry]
            [dk.cst.dannet.web.i18n :as i18n]
            [dk.cst.dannet.web.ui.edit.control :as control]
            [dk.cst.dannet.web.ui.edit.focus :as focus]
            [dk.cst.dannet.web.ui.edit.input :as input]
            [dk.cst.dannet.web.ui.edit.row :as row]
            [dk.cst.dannet.web.ui.edit.session :as session]
            [dk.cst.dannet.web.ui.origin :as origin]
            [dk.cst.dannet.web.ui.rdf :as rdf]))

(defn subject-of
  "The subject of the values in a table with `opts`: the blank node of a
  nested table, or else the resource of the page."
  [{:keys [subject blank-node] :as opts}]
  (if blank-node
    (:rdf/about blank-node)
    subject))

(defn edit!
  "Apply `f` and `args` to the pending changes, for an edit in a table with
  `opts`. An edit in the nested table of a blank node also keeps the place
  of the node, which its changes are located by when they are submitted.

  The labels of the resources in the changes are kept too, since the list of
  the changes shows them on a page of its own (see ui.edit.changes)."
  [{:keys [blank-node subject k->label] :as opts} f & args]
  (when blank-node
    (session/remember-node! (:rdf/about blank-node) (row/node-place opts)))
  (let [{:keys [changes]} (apply session/update-changes! f args)]
    (session/remember-labels!
      (select-keys k->label (conj (change/resources changes)
                                  subject
                                  (:property blank-node))))))

(defn invalid-content
  "The content of a closed field whose entered text `s` isn't a value. The
  `message` says why to a reader who can't see the wavy line under it."
  [s message]
  [:ins.edit-value__content.edit-value__content--invalid
   s [:span.visually-hidden ": " message]])

(defn move-note
  "The note of a value that has moved from one row to another, for a reader
  of a table with `opts`: the property that the `field` (see row/fields)
  has it :moved-from or :moved-to, or nil."
  [{:keys [languages] :as opts} {:keys [moved-from moved-to] :as field}]
  (cond
    moved-from [:small.edit-value__note
                (i18n/da-en languages "Flyttet fra" "Moved from") ": "
                (rdf/resource-name moved-from opts)]
    moved-to [:small.edit-value__note
              (i18n/da-en languages "Flyttet til" "Moved to") ": "
              (rdf/resource-name moved-to opts)]))

(defn removed-note
  "The note of a removed value `v` in `opts` that is an entry which the
  pending changes remove as a whole, or nil. Undoing the removal of such a
  value puts the whole entry back."
  [{:keys [languages changes] :as opts} v]
  (when (entry/removed? changes v)
    [:small.edit-value__note
     (i18n/da-en languages
       "Hele ressourcen fjernes"
       "The whole resource is being removed")]))

(defn value-text
  "The value `v` as the text that names it in a label or a tooltip, in a
  table with `opts`."
  [opts v]
  (if (keyword? v)
    (rdf/link-label opts v)
    (str v)))

(defn status-tag
  "The element that marks a value with the `status` (see row/fields) as a
  change: :del for a removed one and :ins for an added, an edited or a moved
  one. Nil for a value of the data."
  [status]
  (case status
    :retracted :del
    (:added :edited :moved) :ins
    nil))

(defn value-content
  "The content of a field in a table with `opts`: an element with the `tag`
  that holds the value `v`. With `class`, it has that class too."
  [opts tag v & [class]]
  [tag
   (update (rdf/lone-value-attrs v) :class
           #(remove nil? ["edit-value__content" class %]))
   (rdf/value opts v)])

(defn with-note
  "The `content` of a field with the `note` after it, when it has one."
  [content note]
  (if note
    [:span.edit-value__content content note]
    content))

(rum/defcs value-field < (rum/local false ::editing?)
                         (rum/local nil ::draft)
                         focus/refocus
                         focus/keep-focus
  "A field for the value `v` of the property `k` in `opts`, which is an
  rdf:_N property in the row of members.

  The `props` are the field (see row/fields), with the :input-type of its
  input, the :langs of a text and :required, the constraints of a row that
  must keep the value. With :on-remove, the remove button calls that
  function, e.g. to remove the blank node that holds the value. An entered
  text that isn't a value is kept as a draft and shown as an edit."
  [state {:keys [languages comments] :as opts}
   k v {:keys [input-type status original langs required moved-to on-remove]
        :as   props}]
  (let [editing? (::editing? state)
        draft    (::draft state)
        subject  (subject-of opts)
        t        [subject k v]
        ;; a draft is an edit, unless the value that it would replace is new
        tint     (if (and @draft (not= status :added)) :edited status)
        lang     (i18n/lang v)
        langs    (when (i18n/rdf-string? v) langs)
        ;; a value that isn't in the row yet can be taken away again
        required (when-not (#{:added :moved} status) required)
        ;; a pending value isn't in the data yet, so it has no origins
        shared?  (and (not (contains? #{:added :edited :moved} status))
                      (next (origin/value-origins opts k v)))
        text     (or @draft (value-text opts v))
        close!   (fn []
                   (focus/refocus! state "button")
                   (reset! editing? false))
        open!    (fn [e]
                   (when (and (not @editing?) (focus/opens-input? e))
                     (reset! editing? true)))
        ;; a new status replaces the content, and with it the control in focus
        retag!   (fn [lang']
                   (focus/refocus! state (if lang' "select" "button"))
                   (edit! opts change/replace
                          t [subject k (i18n/with-lang v lang')]))
        ;; A pending value is taken away, and what its change removes stays
        ;; removed: the value that it replaces, or the one in its old row.
        remove!  (fn [& _]
                   (reset! draft nil)
                   ;; a removed value keeps its field, with other buttons
                   (focus/refocus! state "button")
                   (cond
                     on-remove (on-remove)
                     status (edit! opts change/without-addition t)
                     :else (edit! opts change/retract t)))
        actions  {:edit   [(control/edit-label languages text)
                           #(reset! editing? true)]
                  :undo   [(control/undo-label languages
                                               (if moved-to :moved tint)
                                               text)
                           (fn [_]
                             (reset! draft nil)
                             (close!)
                             (when status
                               (edit! opts change/without-triple t)))]
                  :remove [(control/remove-label languages text) remove!]}
        note     (or (move-note opts props)
                     (when (= status :retracted)
                       (removed-note opts v)))
        before   (cond
                   (some? original) original
                   @draft v)
        message  (input/invalid-message languages input-type)
        ;; the tooltip names the old language when the language has changed
        titles   [(when @draft
                    message)
                  (when (some? before)
                    (str (i18n/da-en languages "Før" "Before") ": "
                         (value-text opts before)
                         (when (and (not @draft)
                                    (not= lang (i18n/lang before)))
                           (str " (" (control/language-name
                                       languages (i18n/lang before))
                                ")"))))
                  (when shared?
                    (:shared comments))]]
    [:li.edit-value
     (cond-> {:class []}
       (not= tint :retracted) (-> (assoc :on-click open!)
                                  (update :class conj "edit-value--clickable"))
       tint (update :class conj (str "edit-value--" (name tint)))
       (some some? titles) (assoc :title (str/join "; " (remove nil? titles))))
     (cond
       @editing?
       (input/input
         opts input-type
         {:value       v
          :draft       @draft
          :lang        lang
          :langs       langs
          :label       (control/edit-label languages text)
          :tint        tint
          :actions     actions
          :on-cancel   close!
          :on-empty    (when-not required
                         (fn []
                           (close!)
                           (remove!)))
          :keep-reason (some->> required (control/required-message languages))
          :on-invalid  (fn [s leaving?]
                         (reset! draft s)
                         (when leaving?
                           (close!)))
          :on-value    (fn [v']
                         (reset! draft nil)
                         (close!)
                         (when (not= v v')
                           (edit! opts change/replace t [subject k v'])))})

       @draft
       (invalid-content @draft message)

       :else
       (with-note
         (conj (value-content opts (or (status-tag status) :span) v)
               ;; A text without a tag gets its dropdown in the input, and a
               ;; text that can only be in its own language keeps the plain
               ;; tag.
               (when (and langs lang (not= langs [lang])
                          (not= status :retracted))
                 (control/lang-select opts langs lang retag!)))
         note))
     (when-not @editing?
       (control/buttons tint actions
                        (when required
                          (control/required-mark languages required))))]))

(rum/defcs new-value-field < (rum/local false ::adding?)
                             (rum/local nil ::draft)
                             focus/refocus
                             focus/keep-focus
  "The field for a new value of the property `k` in `opts`: a button that
  opens an input. An entered value is added unless it is `taken`.

  The `props` are the :input-type of the input, the :lang of a new text, the
  :langs that it can be in and :gap? when the row has no text in that
  language, which the button then names. With :on-add, an entered value goes
  to that function, e.g. to add it inside a new blank node. An entered text
  that isn't a value is kept as a draft and shown as an addition."
  [state {:keys [languages attr-key] :as opts}
   k taken {:keys [input-type lang langs gap? on-add]}]
  (let [adding? (::adding? state)
        draft   (::draft state)
        subject (subject-of opts)
        tint    (when @draft :added)
        label   (cond-> (control/plus-label opts attr-key)
                  gap? (str " " (i18n/da-en languages "på" "in")
                            " " (control/language-name languages lang)))
        close!  (fn []
                  (focus/refocus! state "button")
                  (reset! adding? false))
        open!   (fn [e]
                  (when (focus/opens-input? e)
                    (reset! adding? true)))
        drop!   (fn [& _]
                  (reset! draft nil)
                  (close!))
        actions {:edit   [(control/edit-label languages @draft)
                          #(reset! adding? true)]
                 :remove [(control/remove-label languages @draft) drop!]}
        message (input/invalid-message languages input-type)]
    [:li.edit-value
     (cond
       @adding? {:class (when tint "edit-value--added")}
       @draft {:class    ["edit-value--added" "edit-value--clickable"]
               :title    message
               :on-click open!}
       :else {:class (cond-> ["edit-value--new"]
                       gap? (conj "edit-value--gap"))})
     (cond
       @adding?
       (input/input
         opts input-type
         {:draft      @draft
          :lang       lang
          :langs      langs
          :label      label
          :taken      taken
          :tint       tint
          :actions    actions
          :on-cancel  close!
          :on-empty   (when @draft drop!)
          :on-invalid (fn [s leaving?]
                        (reset! draft s)
                        (when leaving?
                          (close!)))
          :on-value   (fn [v]
                        (reset! draft nil)
                        (close!)
                        (cond
                          (some #{v} taken) nil
                          on-add (on-add v)
                          :else (edit! opts change/add [subject k v])))})

       @draft
       [:<>
        (invalid-content @draft message)
        (control/buttons tint actions)]

       :else
       (control/add-button label #(reset! adding? true) (when gap? lang)))]))
