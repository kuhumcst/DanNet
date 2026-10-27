(ns dk.cst.dannet.web.ui.model
  "The opts that a page renders from: the page data merged with the user's
  state, plus the values derived from them.

  The same function runs on the server and in the browser, so the client
  renders what the server rendered. Beyond the page data, components read
  these keys:

    :languages     the preferred languages, in order
    :detail-level  how much labels are enriched: :basic, :normal or :high
    :full-screen   whether a synset diagram fills the page
    :search        the search input and its completions (in the browser)
    :section       the display mode of each entity page section
    :k->label      a label for each resource on the page
    :synset?       whether the page is about a synset
    :inherited     the relations that the entity inherits from its hypernym
    :comments      the notes that explain the markers in the tables

  Components add a few keys of their own on the way down, e.g. :attr-key, the
  predicate whose values are being rendered."
  (:require [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.web.i18n :as i18n]))

(defn- comments
  "The notes that explain the markers in the tables, in `languages`."
  [languages]
  {:inference    (i18n/da-en languages
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

  Inheritance is marked by anonymous resources (#182) carrying their entity
  data as metadata."
  [entity]
  (->> (shared/setify (:dns/inherited entity))
       (keep #(some-> (meta %)
                      (get :dns/inheritedRelation)
                      (shared/unwrap)))
       (set)))

(defn render-opts
  "The opts that the components of a page render from: the page `data` merged
  with the user's `state`, plus the values derived from them."
  [{:keys [entity subject entities] :as data} {:keys [languages] :as state}]
  (let [detail-level (or (:detail-level state) :normal)
        label-of     (shared/->entity-label-fn detail-level)]
    (cond-> (assoc (merge data state)
              :detail-level detail-level
              :synset? (some shared/semantic-rels? entity)
              :comments (comments languages)
              ;; Entity pages label the resources of their expanded entity;
              ;; other pages (e.g. SPARQL) may supply their own k->label.
              :k->label (if entities
                          (update-vals (assoc entities subject entity) label-of)
                          (:k->label data)))
      entity (assoc :inherited (inherited entity)))))

(comment
  (render-opts {:subject  :dn/synset-1
                :entity   {:rdfs/label "{x}"}
                :entities {}}
               {:languages ["da" "en" nil] :detail-level :normal})
  #_.)
