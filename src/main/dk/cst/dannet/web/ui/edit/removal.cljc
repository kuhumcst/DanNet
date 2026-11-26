(ns dk.cst.dannet.web.ui.edit.removal
  "The removal of a resource of the data as a flow: what an editor has to
  know and to choose before it is made, as the steps of a wizard (see
  dk.cst.dannet.web.ui.dialog), and the change that the answers give.

  The server has the removal in parts (see dk.cst.dannet.db.change/removal):
  the resource with what it holds, how many references it has, and the
  orphans, which are the resources that can't stay alone once it is gone,
  each with parts of its own. The answers are a map of the ID of an orphan
  to whether that resource goes too.

  The change names the triples of what goes. The references always go with
  what they point to. They are a rule of the change instead (see
  :unreference in dk.cst.dannet.change), so that they are found when the
  change is made, however many they are."
  (:require [dk.cst.dannet.change :as change]
            [dk.cst.dannet.web.i18n :as i18n]))

(defn answers
  "The answers that the removal with `parts` starts with: every orphan goes
  too, until the editor says otherwise."
  [{:keys [orphans] :as parts}]
  (zipmap (keys orphans) (repeat true)))

(defn- chosen
  "The `parts` that go by the `answers`: the resource, and the orphans that
  are chosen, each as a map of its own parts."
  [{:keys [orphans] :as parts} answers]
  (cons parts (for [[id orphan] (sort-by key orphans)
                    :when       (get answers id)]
                orphan)))

(defn- references
  "How many triples refer to what goes by the `answers`, of the `parts`, as
  a map of a property to a number."
  [parts answers]
  (apply merge-with + (map :references (chosen parts answers))))

(defn removal
  "The change that the `answers` make of the `parts` of a removal. It
  unreferences what it removes."
  [parts answers]
  (let [retract (into #{} (mapcat :entry) (chosen parts answers))]
    {:retract     retract
     :unreference (change/removed {:retract retract})}))

(defn nodes
  "The places of the blank nodes in the `parts` of a removal, also those of
  the parts that aren't chosen."
  [{:keys [orphans] :as parts}]
  (into {} (mapcat :nodes) (cons parts (vals orphans))))

(def large
  "The number of values from which a removal is a large one, which takes a
  while to check."
  500)

;; TODO: a step that moves the references to another resource in place of
;;       removing them, e.g. when two synsets are merged.
(defn steps
  "The steps of the wizard that removes the resource `id` with `parts`, as
  the `answers` so far leave them, for a reader with `languages`.

  A step asks about the orphans when the removal has some. The last step
  sums up what the answers remove, with the references to it, which the
  editor confirms. Each step has its numbers as figures and its details
  folded away, and names a resource by its ID (see
  dk.cst.dannet.web.ui.dialog). The wizard itself says which resource it is
  about."
  [languages id {:keys [orphans] :as parts} answers]
  (let [da-en      #(i18n/da-en languages %1 %2)
        ;; a number with what it counts, in the singular for one
        figure     (fn [n [da das] [en ens]]
                     {:value n
                      :label (if (= 1 n) (da-en da en) (da-en das ens))})
        {:keys [retract] :as change} (removal parts answers)
        removed    (change/removed change)
        referring  (references parts answers)
        pointers   (reduce + (vals referring))
        values     (+ (count retract) pointers)]
    (cond-> []
      (seq orphans)
      (conj {:heading (da-en "Kan ikke stå alene" "Can't stay alone")
             :figures [(figure (count orphans)
                               ["kan ikke stå alene" "kan ikke stå alene"]
                               ["can't stay alone" "can't stay alone"])]
             :text    [(da-en "De valgte fjernes også."
                              "The chosen ones are removed too.")]
             :choices (for [orphan (sort (keys orphans))]
                        {:key orphan :label orphan})})

      true
      (conj {:heading (da-en "Bekræft fjernelsen" "Confirm the removal")
             :figures (cond-> [(figure (count removed)
                                       ["ressource" "ressourcer"]
                                       ["resource" "resources"])]
                        (pos? pointers)
                        (conj (assoc (figure pointers
                                             ["henvisning" "henvisninger"]
                                             ["reference" "references"])
                                :title (da-en
                                         "Henvisninger fra andre ressourcer"
                                         "References from other resources")))

                        true
                        (conj (figure values
                                      ["værdi" "værdier"]
                                      ["value" "values"])))
             :details (cond-> []
                        (next removed)
                        (conj {:summary (da-en "Vis ressourcerne"
                                               "Show the resources")
                               :items   (cons id (sort (disj removed id)))})

                        (seq referring)
                        (conj {:summary (da-en "Vis henvisningerne"
                                               "Show the references")
                               :items   (for [[p n] (sort-by
                                                      (juxt (comp - val) key)
                                                      referring)]
                                          [(str n " ×") p])}))
             :warning (when (<= large values)
                        (da-en
                          (str "Det er en stor fjernelse. Den tager lang "
                               "tid at tjekke.")
                          (str "This is a large removal. It takes a long "
                               "time to check.")))}))))

(defn flow
  "The flow of the wizard that removes the resource `id` with `parts`, for
  a reader with `languages`: its texts, the answers that it starts with and
  its steps (see dk.cst.dannet.web.ui.dialog/wizard)."
  [languages id parts]
  {:title    (i18n/da-en languages "Fjern ressourcen" "Remove the resource")
   :about    id
   :answers  (answers parts)
   :steps-fn #(steps languages id parts %)
   :done     (i18n/da-en languages "Fjern" "Remove")})
