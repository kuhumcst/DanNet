(ns dk.cst.dannet.web.ui.edit.removal
  "The removal of a resource of the data as a flow: what an editor has to
  know and to choose before it is made, as the steps of a wizard (see
  dk.cst.dannet.web.ui.dialog), and the change that the answers give.

  The server has the removal in parts (see
  dk.cst.dannet.db.change/removal-parts). The answers are a map of the ID of
  an orphan, a resource that can't stay alone once the other is gone, to
  whether that resource goes too.

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

(defn chosen
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
  (let [retract (into #{} (mapcat :triples) (chosen parts answers))]
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

(defn figure
  "The number `n` with what it counts, for a reader with `languages`: the
  singular or the plural of the Danish pair `da` or the English pair `en`."
  [languages n da en]
  (let [form (if (= 1 n) first second)]
    {:value n
     :label (i18n/da-en languages (form da) (form en))}))

(defn- orphans-step
  "The step that asks which of the `orphans` go too, for a reader with
  `languages`."
  [languages orphans]
  (let [da-en #(i18n/da-en languages %1 %2)]
    {:heading (da-en "Kan ikke stå alene" "Can't stay alone")
     :figures [(figure languages (count orphans)
                       ["kan ikke stå alene" "kan ikke stå alene"]
                       ["can't stay alone" "can't stay alone"])]
     :text    [(da-en "De valgte fjernes også."
                      "The chosen ones are removed too.")]
     :choices (for [orphan (sort (keys orphans))]
                {:key orphan :label orphan})}))

(defn- confirm-step
  "The step that sums up the `change` that removes the resource `id`, for a
  reader with `languages`. The `counts` are how many references to what it
  removes go too, as a map of a property to a number."
  [languages id {:keys [retract unreference] :as change} counts]
  (let [da-en      #(i18n/da-en languages %1 %2)
        references (reduce + (vals counts))
        values     (+ (count retract) references)]
    {:heading (da-en "Bekræft fjernelsen" "Confirm the removal")
     :figures (cond-> [(figure languages (count unreference)
                               ["ressource" "ressourcer"]
                               ["resource" "resources"])]
                (pos? references)
                (conj (assoc (figure languages references
                                     ["henvisning" "henvisninger"]
                                     ["reference" "references"])
                        :title (da-en "Henvisninger fra andre ressourcer"
                                      "References from other resources")))

                true
                (conj (figure languages values
                              ["værdi" "værdier"]
                              ["value" "values"])))
     :details (cond-> []
                (next unreference)
                (conj {:summary (da-en "Vis ressourcerne" "Show the resources")
                       :items   (cons id (sort (disj unreference id)))})

                (seq counts)
                (conj {:summary (da-en "Vis henvisningerne"
                                       "Show the references")
                       :items   (for [[p n] (sort-by (juxt (comp - val) key)
                                                     counts)]
                                  [(str n " ×") p])}))
     :warning (when (<= large values)
                (da-en
                  "Det er en stor fjernelse. Den tager lang tid at tjekke."
                  "This is a large removal. It takes a long time to check."))}))

(defn steps
  "The steps of the wizard that removes the resource `id` with `parts`, as
  the `answers` so far leave them, for a reader with `languages`.

  A step asks about the orphans when the removal has some, and the last
  step sums up what the answers remove, which the editor confirms."
  [languages id {:keys [orphans] :as parts} answers]
  (cond-> []
    (seq orphans) (conj (orphans-step languages orphans))
    true          (conj (confirm-step languages id
                                      (removal parts answers)
                                      (references parts answers)))))

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
