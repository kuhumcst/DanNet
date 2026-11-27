(ns dk.cst.dannet.web.ui.edit.entry
  "The fields of an entry in edit mode (see ui.edit): the field of a new
  entry, of an entry of the data and for making an entry, with the buttons
  and the wizard that take a whole entry away or put it back.

  The entries as data are in dk.cst.dannet.entry."
  (:require [clojure.string :as str]
            [rum.core :as rum]
            [dk.cst.dannet.change :as change]
            [dk.cst.dannet.entry :as entry]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.web.app :as app]
            [dk.cst.dannet.web.i18n :as i18n]
            [dk.cst.dannet.web.ui.dialog :as dialog]
            [dk.cst.dannet.web.ui.edit.control :as control]
            [dk.cst.dannet.web.ui.edit.focus :as focus]
            [dk.cst.dannet.web.ui.edit.input :as input]
            [dk.cst.dannet.web.ui.edit.removal :as removal]
            [dk.cst.dannet.web.ui.edit.value :as value]
            [dk.cst.dannet.web.ui.input-type :as input-type]
            [dk.cst.dannet.web.ui.rdf :as rdf]))

(rum/defcs entry-field < (rum/local false ::editing?)
                         focus/refocus
                         focus/keep-focus
  "A field for the new entry with the temporary ID `id` in a row of a table
  with `opts`: the label that the pending changes give the entry, as a link
  to the page of the entry. The label can be edited here too. The remove
  button takes the entry away, with what can't stay without it (see
  entry/without).

  The field also names the other entries that hold the entry, e.g. the
  synset of a new sense in the row of a word, which tells two senses with
  the same label apart."
  [state {:keys [languages changes draft? subject edit-rules] :as opts} id]
  (let [editing? (::editing? state)
        without  #(entry/without (:entries edit-rules) % id)
        label    (get (entry/labels changes) id)
        others   (map #(rdf/link-label opts %)
                      (entry/held-by (:entries edit-rules) changes id
                                     (value/subject-of opts)))
        text     (cond-> (if label (str label) (prefix/kw->qname id))
                   (seq others) (str " (" (str/join ", " others) ")"))
        close!   (fn []
                   (focus/refocus! state "button")
                   (reset! editing? false))
        remove!  (fn [& _]
                   (value/edit! opts without)
                   ;; the page of a new entry that went with it is empty
                   (when (and draft?
                              (nil? (entry/entity (without changes) subject)))
                     (app/navigate-to prefix/changes-path {:replace? true})))
        actions  {:edit   [(control/edit-label languages text)
                           #(reset! editing? true)]
                  :remove [(control/remove-label languages text) remove!]}]
    [:li.edit-value.edit-value--added
     (cond-> {:data-entry (prefix/kw->qname id)}
       (not @editing?) (assoc :class "edit-value--clickable"
                              :on-click (fn [e]
                                          (when (focus/opens-input? e)
                                            (reset! editing? true)))))
     (if @editing?
       (input/text-input
         opts input-type/text
         {:value       label
          :lang        (i18n/lang label)
          :label       (control/edit-label languages text)
          :tint        :added
          :actions     actions
          :keep-reason (i18n/da-en languages
                         "En ny ressource skal have en etiket"
                         "A new resource needs a label")
          :on-cancel   close!
          :on-value    (fn [label']
                         (close!)
                         (if label
                           (value/edit! opts change/replace
                                        [id :rdfs/label label]
                                        [id :rdfs/label label'])
                           (value/edit! opts change/add
                                        [id :rdfs/label label'])))})
       [:<>
        ;; as any other resource in the row, so with a link to its page
        (value/with-note [:ins.edit-value__content (rdf/value opts id)]
                         (when (seq others)
                           [:small.edit-value__note (str/join ", " others)]))
        (control/buttons :added actions)])]))

(defn discard-entry!
  "Take the new entry with the `id` out of the pending changes of a page
  with `opts`, with what can't stay without it (see entry/without)."
  [{:keys [edit-rules] :as opts} id]
  (app/update-changes! #(entry/without (:entries edit-rules) % id)))

(defn entry-button
  "A button with the `text` that acts on the whole entry with the `id`, on a
  page with `opts`. Its name also says which entry."
  [opts id text on-click]
  [:button.entry-button {:type       "button"
                         :aria-label (str text ": " (rdf/link-label opts id))
                         :on-click   on-click}
   text])

(defn discard-entry-button
  "The button that takes the new entry with the `id` away as a whole, on a
  page with `opts`. Its `on-click` does that (see discard-entry!), and what
  the page needs besides, e.g. to move the focus."
  [{:keys [languages] :as opts} id on-click]
  (entry-button opts id
                (i18n/da-en languages
                  "Fjern den nye ressource"
                  "Remove the new resource")
                on-click))

(defn restore-entry!
  "Put the removed entry `id` of the data back: drop the pending change that
  removes it as a whole (see entry/without-removal)."
  [id]
  (app/update-changes! entry/without-removal id))

(defn restore-entry-button
  "The button that puts the removed entry with the `id` back, on a page
  with `opts`. Its `on-click` does that (see restore-entry!), and what the
  page needs besides, e.g. to move the focus."
  [{:keys [languages] :as opts} id on-click]
  (entry-button opts id
                (i18n/da-en languages
                  "Fortryd fjernelsen af ressourcen"
                  "Undo the removal of the resource")
                on-click))

(defn fetch-removal
  "A promise of what the server has for removing the entry `id` of the data
  (see db.change/removal-parts), or of nil when the data has no such entry
  or the request fails."
  [id]
  #?(:cljs
     ;; each entry has a URL of its own, so one request doesn't end another
     (let [url (str prefix/removal-path "?id="
                    (js/encodeURIComponent (prefix/kw->qname id)))]
       (-> (app/fetch! url)
           (.then (fn [response]
                    (app/clear-current-fetch url)
                    (let [parts (get-in response [:body :data])]
                      (when (and (= 200 (:status response))
                                 (seq (:triples parts)))
                        parts))))
           (.catch (fn [_]
                     (app/clear-current-fetch url)
                     nil))))))

(defn entity-labels
  "The label of each resource in `entities`, a map of a resource to its
  entity as the server sends it, as a page with `opts` shows them."
  [{:keys [detail-level] :as opts} entities]
  (update-vals entities (shared/->entity-label-fn detail-level)))

(defn remove-entry!
  "Make the removal of an entry of the data a pending change, on a page
  with `opts`: the one that the `answers` of the editor make of the `parts`
  that the server has for it (see ui.edit.removal and entry/with-removal). The
  places of its blank nodes and the labels of its resources are kept, which
  the list of the changes needs."
  [{:keys [edit-rules] :as opts} parts answers]
  (doseq [[label place] (removal/nodes parts)]
    (app/remember-node! label place))
  (app/remember-labels! (entity-labels opts (:entities parts)))
  (value/edit! opts #(entry/with-removal (:entries edit-rules) %
                                         (removal/removal parts answers))))

(def fetch-parts
  "A mixin that asks the server for the parts of a removal when its wizard
  is drawn, and keeps them as ::parts. The wizard closes when the data has
  no such entry."
  {:did-mount (fn [state]
                #?(:cljs
                   (let [[_ id {:keys [on-close]}] (:rum/args state)]
                     (.then (fetch-removal id)
                            #(if %
                               (reset! (::parts state) %)
                               (on-close)))))
                state)})

(rum/defcs removal-wizard < (rum/local nil ::parts)
                            fetch-parts
  "The wizard that removes the entry `id` of the data, on a page with
  `opts`. It is drawn once the server has given the parts of the removal.
  Then it says what goes with the entry and asks about the rest (see
  ui.edit.removal).

  It calls `on-done` right before the removal is made, e.g. to say where
  the focus goes, and `on-close` when it closes without one."
  [state {:keys [languages] :as opts} id {:keys [on-done on-close]}]
  (when-let [parts @(::parts state)]
    (let [labelled (update opts :k->label
                           #(merge (entity-labels opts (:entities parts)) %))]
      (dialog/wizard
        languages
        (assoc (removal/flow languages id parts)
          ;; a link would leave the page, and the wizard with it
          :resource-fn #(rdf/resource-name % labelled))
        {:on-done  (fn [answers]
                     (on-done)
                     (remove-entry! opts parts answers))
         :on-close on-close}))))

(rum/defcs remove-entry-button < (rum/local nil ::opener)
  "The button that removes the entry `id` of the data as a whole, on its own
  page with `opts`. It opens the wizard of the removal, which has to be
  confirmed (see removal-wizard).

  The button that puts the entry back then takes its place and the focus."
  [state {:keys [languages] :as opts} id]
  (let [opener (::opener state)]
    [:<>
     (entry-button
       opts id
       (i18n/da-en languages "Fjern ressourcen" "Remove the resource")
       #(reset! opener (.-currentTarget %)))
     (when-let [button @opener]
       (removal-wizard
         opts id
         {:on-done  (fn []
                      (reset! opener nil)
                      #?(:cljs
                         (let [header (.closest button "header")]
                           ;; the header still has this button at first
                           (focus/when-drawn!
                             #(let [other (.querySelector
                                            header ".entry-button")]
                                (when (not= other button)
                                  other))
                             #(.focus %)))))
          :on-close #(reset! opener nil)}))]))

(rum/defcs data-entry-field < (rum/local false ::removing?)
                              focus/refocus
                              focus/keep-focus
  "A field for the entry `v` of the data in the row of the property `k` in
  `opts`, when the row takes new entries, e.g. a sense of a synset. The
  `props` are the field (see ui.edit.row/fields), with :required, the
  constraints of a row that must keep the value.

  The remove button removes the whole entry, since an entry without the
  link would be broken. It opens the wizard of the removal (see
  removal-wizard). A removed entry says so, and its button puts it back."
  [state {:keys [languages] :as opts} k v {:keys [status required]}]
  (let [removing? (::removing? state)
        removed?  (= status :retracted)
        text      (value/value-text opts v)
        link      [(value/subject-of opts) k v]
        content   (value/value-content opts
                                       (or (value/status-tag status) :span)
                                       v)]
    [:li.edit-value (when removed? {:class "edit-value--retracted"})
     (if removed?
       [:span.edit-value__content content (value/removed-note opts v)]
       content)
     (control/buttons
       status
       {:undo   [(control/undo-label languages :retracted text)
                 (fn [_]
                   (focus/refocus! state "button")
                   (value/edit! opts change/without-triple link))]
        :remove [(str (i18n/da-en languages
                        "Fjern hele ressourcen"
                        "Remove the whole resource")
                      ": " text)
                 #(reset! removing? true)]}
       (when required
         (control/required-mark languages required)))
     (when @removing?
       (removal-wizard opts v
                       {:on-done  (fn []
                                    (reset! removing? false)
                                    (focus/refocus! state "button"))
                        :on-close #(reset! removing? false)}))]))

(defn with-pending
  "The `search` for an entry with the new entries in `pending` among its
  results, before the ones that it finds itself: those whose label has the
  typed text in it, and all of them before a text is typed.

  A pending result is a map of the :pending ID of a new entry, its :label
  and its :kind, which is what such an entry is called in each language.
  The result says it after its label."
  [{:keys [results-fn value-fn label-fn hiccup-fn] :as search} pending]
  (let [matching (fn [s]
                   (let [s (str/lower-case (str/trim s))]
                     (filter #(str/includes? (str/lower-case (str (:label %)))
                                             s)
                             pending)))]
    (if (empty? pending)
      search
      (assoc search
        :list-all?  true
        ;; the search itself asks the server, which only a browser does
        :results-fn (fn [s]
                      #?(:clj  (matching s)
                         :cljs (.then (js/Promise.resolve (results-fn s))
                                      #(concat (matching s) %))))
        :value-fn   #(or (:pending %) (value-fn %))
        :label-fn   #(when (and label-fn (not (:pending %)))
                       (label-fn %))
        :hiccup-fn  (fn [{:keys [label kind] :as result}
                         {:keys [languages] :as opts}]
                      (if (:pending result)
                        [:<>
                         [:strong (str label)]
                         [:span (i18n/localize languages kind)]]
                        (hiccup-fn result opts)))))))

(rum/defcs new-entry-field < (rum/local false ::choosing?)
  "The field for a new entry in the row of the property `k` in `opts`: a
  button that opens a dialog, which asks for the gap of the template of
  `row-entry` that the subject of the row doesn't fill (see entry/for-row).
  The answer fills the template.

  The dialog says what the template adds besides, lists the new entries of
  the classes of the gap, and refuses an answer that already shares an
  entry of the template with the subject. It offers to make the answer
  when the typed text fits the template of one of the classes, and a made
  answer is shown on its own page."
  [state {:keys [languages changes entity inverses edit-rules nodes] :as opts}
   k {:keys [template given] :as row-entry}]
  (let [choosing? (::choosing? state)
        s         (value/subject-of opts)
        entries   (:entries edit-rules)
        [gap classes] (first (entry/asked template [given]))
        labels    (entry/labels changes (:derived-labels edit-rules))
        pending   (sort-by :pending
                           (for [class classes
                                 id    (entry/of-class changes class)]
                             {:pending id
                              :label   (get labels id)
                              :kind    (:name (get entries class))}))
        search    (with-pending (:search (some input-type/class-input-types
                                               classes))
                                pending)
        value-fn  (:value-fn search)
        taken     (when (:once template)
                    (into (entry/known template inverses entity given gap)
                          (entry/linked template changes given s gap)))
        reason    (get-in template [:once :reason])
        focus!    (fn [change]
                    #?(:cljs
                       (when-let [id (first (change/temporary-ids [change]))]
                         (focus/when-drawn!
                           #(.querySelector
                              js/document
                              (str "[data-entry=\"" (prefix/kw->qname id)
                                   "\"] button"))
                           #(.focus %)))))
        add!      (fn [result]
                    (let [found  (or (some->> (:pending result)
                                              (entry/entity changes))
                                     result)
                          change (entry/fill-from template changes
                                                  {given s
                                                   gap   (value-fn result)}
                                                  {given entity
                                                   gap   found})]
                      (value/edit! opts conj change)
                      (focus! change)))
        ;; a new answer is made from the template that the typed text fits
        make      #(entry/new-entry-of entries classes changes %
                                       (change/new-labels nodes))
        make!     (fn [text]
                    (let [{:keys [id] :as made} (make text)
                          change (entry/fill-around template changes gap made
                                                    {given s}
                                                    {given entity})]
                      ;; a new blank node has its place in the session
                      (doseq [[label place] (change/new-places change)]
                        (app/remember-node! label place))
                      (value/edit! opts conj change)
                      (app/navigate-to (prefix/resolve-href id))))]
    [:li.edit-value.edit-value--new
     (control/add-button (control/plus-label opts k) #(reset! choosing? true))
     (when @choosing?
       (input/search-dialog
         opts
         (cond-> (assoc search :note (:note template))
           taken (assoc :refuse-fn
                        (fn [result]
                          (when (contains? taken (value-fn result))
                            (i18n/localize languages reason)))))
         {:on-result (fn [result]
                       (reset! choosing? false)
                       (add! result))
          :new-fn    (when (some entries classes)
                       make)
          :on-new    (fn [text]
                       (reset! choosing? false)
                       (make! text))
          :on-close  (focus/close-fn choosing? "button")}))]))
