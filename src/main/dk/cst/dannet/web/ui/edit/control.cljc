(ns dk.cst.dannet.web.ui.edit.control
  "The small parts that the fields of edit mode share (see ui.edit): the
  icons and the buttons, the dropdown of the languages, the mark of a value
  that its row must keep, and the names of the buttons."
  (:require [dk.cst.dannet.web.i18n :as i18n]
            [dk.cst.dannet.web.ui.rdf :as rdf]))

(defn icon
  "The icon with the `icon-name`, e.g. \"add\", which a screen reader
  skips."
  [icon-name]
  [:span {:class       ["edit-value__icon" (str "edit-value__icon--" icon-name)]
          :aria-hidden "true"}])

(defn icon-button
  "A button with the `icon-name` that calls `on-click`. The `label` is its
  name and its tooltip."
  [icon-name label on-click]
  [:button.edit-value__button {:type     "button"
                               :key      icon-name
                               :title    label
                               :on-click on-click}
   (icon icon-name)
   [:span.visually-hidden label]])

(defn plus-label
  "The name of the add button in the row of the property `k` in `opts`. The
  button looks the same in every row, so its name says which row."
  [{:keys [languages] :as opts} k]
  (str (i18n/da-en languages "Tilføj en værdi til" "Add a value to")
       " \"" (rdf/link-label opts k) "\""))

(defn add-button
  "The plus button of a row, which calls `on-click`. The `label` is its name
  and its tooltip (see plus-label). With `gap`, the button also shows that
  text, e.g. a language that the row lacks."
  [label on-click & [gap]]
  [:button.edit-value__add {:type     "button"
                            :title    label
                            :on-click on-click}
   (icon "add")
   (when gap
     [:span.edit-value__gap {:aria-hidden "true"} gap])
   [:span.visually-hidden label]])

(defn padlock
  "A padlock icon that says `why` a row or a value can't be edited."
  [why]
  [:span.padlock {:role "img" :aria-label why :title why}])

(defn locked-value
  "The list item of the value `v` that can't be edited, in a table with
  `opts`: the value after a padlock that says `why` it is locked (see
  ui.edit.lock/lock). The item has the `react-key`."
  [{:keys [comments] :as opts} v why react-key]
  [:li.edit-value.edit-value--locked
   (assoc (rdf/lone-value-attrs v) :key react-key)
   (padlock (get-in comments [:locked why]))
   (rdf/value opts v)])

(def language-names
  "The Danish and English names of the languages of a text, where nil is
  none."
  {"da" ["dansk" "Danish"]
   "en" ["engelsk" "English"]
   nil  ["intet sprog" "no language"]})

(defn language-name
  "The Danish or English name of the language `lang`, by the reader's
  `languages`. A language without a name keeps its tag."
  [languages lang]
  (if-let [[da en] (get language-names lang)]
    (i18n/da-en languages da en)
    lang))

(defn lang-select
  "A dropdown of the languages `langs` in a table with `opts`, which shows
  `lang` as the tag of a text, or that a language can be added. It calls
  `on-lang` with a chosen language, and `attrs` are added to its attributes."
  [{:keys [languages] :as opts} langs lang on-lang & [attrs]]
  (let [label (str (i18n/da-en languages "Sprog" "Language") ": "
                   (language-name languages lang))]
    [:select.edit-value__lang
     (merge {:aria-label label
             :title      label
             :value      (str lang)
             :on-change  (fn [e]
                           (on-lang (not-empty (.. e -target -value))))}
            attrs)
     ;; the language of the text is shown even when it isn't a choice
     (for [lang' (cond-> (vec langs)
                   (not-any? #(= lang %) langs) (conj lang))]
       (cond
         (some? lang')
         [:option {:key lang' :value lang'} lang']

         ;; choosing no language removes the tag of a text
         (some? lang)
         [:option {:key "" :value ""} (language-name languages nil)]

         ;; a text without a tag shows this, which the menu leaves out
         :else
         [:option {:key "" :value "" :hidden true}
          (i18n/da-en languages "tilføj sprog" "add language")]))]))

(defn required-message
  "The reason why the one value that a row `must` have can't be removed, for
  a reader with `languages`: the row takes exactly one value, or at least
  one."
  [languages {:keys [min max] :as must}]
  (if (= min max)
    (i18n/da-en languages
      "Rækken skal have præcis én værdi, så den kan rettes, men ikke fjernes"
      "The row needs exactly one value, so it can be edited but not removed")
    (i18n/da-en languages
      "Rækken skal have mindst én værdi, så den sidste kan ikke fjernes"
      "The row needs at least one value, so the last one can't be removed")))

(defn required-mark
  "The mark of the one value that a row `must` have: a 1 in a ring, with the
  reason why it can't be removed as its tooltip (see required-message), for
  a reader with `languages`."
  [languages must]
  (let [reason (required-message languages must)]
    [:span.edit-value__required {:key        "required"
                                 :role       "img"
                                 :aria-label reason
                                 :title      reason}
     ;; a drawing, since the digit of a font isn't in the middle of its box
     [:span.edit-value__icon.edit-value__icon--one]]))

(defn field-buttons
  "The buttons of a field in their order, by whether it is `open?` as an
  input and by its `tint`: nil, :edited, :moved, :added or :retracted.

  The tint is the :status of the value (see ui.edit.row/fields). A field
  with a draft, a typed text that isn't a value yet, is :edited, or :added
  when its value is new.

  - :edit opens the input: on a closed field, unless its value is removed.
  - :undo puts the value of the data back: on an edited, a moved or a
    removed value.
  - :remove takes the value away: on an added value, and on a closed field
    with no tint, an edited or a moved one."
  [open? tint]
  (cond-> []
    (and (not open?) (not= tint :retracted)) (conj :edit)
    (contains? #{:edited :moved :retracted} tint) (conj :undo)
    (or (= tint :added)
        (and (not open?) (contains? #{nil :edited :moved} tint)))
    (conj :remove)))

(defn action-buttons
  "The buttons of a field with the `tint`, by whether it is `open?` as an
  input (see field-buttons), from `actions`, a map of a button to its label
  and function. A button that isn't among them is left out.

  With `required`, the mark of a value that its row must keep, the mark is
  there in place of the button that removes the value."
  [open? tint actions & [required]]
  (for [button (field-buttons open? tint)
        :let   [[label f :as action] (get actions button)
                mark (when (= button :remove) required)]
        :when  (or action mark)]
    (or mark (icon-button (name button) label f))))

(defn buttons
  "The buttons of a closed field with the `tint`, from `actions` and with
  the mark `required` (see action-buttons)."
  [tint actions & [required]]
  [:span.edit-value__actions (action-buttons false tint actions required)])

(defn undo-label
  "The name of the button that undoes a change with the `tint` to the value
  that `text` names, for a reader with `languages`."
  [languages tint text]
  (str (case tint
         :retracted (i18n/da-en languages
                      "Fortryd fjernelse af" "Undo removal of")
         :moved (i18n/da-en languages
                  "Fortryd flytning af" "Undo move of")
         (i18n/da-en languages
           "Fortryd rettelse af" "Undo edit of"))
       ": " text))

(defn edit-label
  "The name of the button that opens the input of the value that `text`
  names, for a reader with `languages`."
  [languages text]
  (str (i18n/da-en languages "Ret" "Edit") ": " text))

(defn remove-label
  "The name of the button that takes away the value that `text` names, for
  a reader with `languages`."
  [languages text]
  (str (i18n/da-en languages "Fjern" "Remove") ": " text))
