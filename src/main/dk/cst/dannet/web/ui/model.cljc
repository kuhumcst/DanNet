(ns dk.cst.dannet.web.ui.model
  "The opts that a page renders from: the page data merged with the user's
  state, plus the values derived from them.

  The same function runs on the server and in the browser, so the client
  renders what the server rendered. Beyond the page data, components read
  these keys:

    :languages     the preferred languages, in order
    :detail-level  how much labels are enriched: :basic, :normal or :high
    :full-screen   whether a synset diagram fills the page
    :edit-mode     whether the tables show what can be edited
    :edit-rules    which resources and properties edit mode has fields for
    :changes       the pending changes made in edit mode (in the browser)
    :nodes         the places of the blank nodes that those changes are to
    :labels        the labels of the resources that those changes add
    :search        the search input and its completions (in the browser)
    :section       the display mode of each entity page section
    :k->label      a label for each resource on the page
    :synset?       whether the entity has semantic relations, as synsets do
    :inherited     the relations that the entity inherits from its hypernym
    :comments      the notes that explain the markers in the tables

  Components add a few keys of their own on the way down, e.g. :attr-key, the
  predicate whose values are being rendered, and :blank-node, the place of a
  nested table in the entity: its node and the property that holds it."
  (:require [dk.cst.dannet.editable :as editable]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.web.i18n :as i18n]))

(defn- comments
  "The notes that explain the markers in the tables, in `languages`, and
  in edit mode why a value is :locked or what removing a :shared one does."
  [languages]
  {:locked       {:inferred     (i18n/da-en languages
                                  "Udledt af andre data"
                                  "Inferred from other data")
                  :elsewhere    (i18n/da-en languages
                                  "Fra et andet datasæt"
                                  "From another dataset")
                  :supplemented (i18n/da-en languages
                                  "Hentet fra relaterede opslag"
                                  "Taken from related entries")
                  :derived      (i18n/da-en languages
                                  "Dannes automatisk ved udgivelsen"
                                  "Made automatically at release")
                  :resource     (i18n/da-en languages
                                  "Kan ikke rettes på denne side"
                                  "Can't be edited on this page")
                  :yet          (i18n/da-en languages
                                  "Kan ikke rettes endnu"
                                  "Can't be edited yet")}
   :shared       (i18n/da-en languages
                   (str "Findes også i et andet datasæt, så værdien bliver "
                        "på siden, hvis den fjernes")
                   (str "Also in another dataset, so the value stays on the "
                        "page if it is removed"))
   :inference    (i18n/da-en languages
                   "indeholder logisk afledt data"
                   "contains logically inferred data")
   :inheritance  (i18n/da-en languages
                   "helt eller delvist nedarvet fra hypernym"
                   "fully or partially inherited from hypernym")
   :supplemented (i18n/da-en languages
                   "suppleret fra andre ressourcer"
                   "supplemented from other resources")
   :entailment   (i18n/da-en languages
                   "se andre relationer afledt af denne"
                   "view other relations entailed by this one")})

(defn inherited
  "The relations that `entity` inherits from its hypernym.

  Inheritance is marked by anonymous resources (#182), i.e. blank nodes."
  [entity]
  (->> (shared/setify (:dns/inherited entity))
       (keep (comp shared/unwrap :dns/inheritedRelation))
       (set)))

(defn render-opts
  "The opts that the components of a page render from: the page `data` merged
  with the user's `state`, plus the values derived from them."
  [{:keys [entity subject entities] :as data} {:keys [languages] :as state}]
  (let [detail-level (or (:detail-level state) :normal)
        label-of     (shared/->entity-label-fn detail-level)]
    (cond-> (assoc (merge data state)
              :detail-level detail-level
              ;; edit mode shows the tables, so no diagram fills the page
              :full-screen (and (:full-screen state) (not (:edit-mode state)))
              :synset? (some shared/semantic-rels? entity)
              :comments (comments languages)
              :edit-rules editable/rules
              ;; Entity pages label the resources of their expanded entity;
              ;; other pages (e.g. SPARQL) may supply their own k->label. The
              ;; :labels of the state are of resources added in edit mode.
              :k->label (if entities
                          (merge (:labels state)
                                 (update-vals (assoc entities subject entity)
                                              label-of))
                          (:k->label data)))
      entity (assoc :inherited (inherited entity)))))

(comment
  (render-opts {:subject  :dn/synset-1
                :entity   {:rdfs/label "{x}"}
                :entities {}}
               {:languages ["da" "en" nil] :detail-level :normal})
  #_.)
