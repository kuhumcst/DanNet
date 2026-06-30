(ns dk.cst.dannet.web.ui.relations
  "UI components for displaying the synset relations in use in DanNet."
  (:require [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.web.i18n :as i18n]
            [dk.cst.dannet.web.section :as section]
            [dk.cst.dannet.web.ui.rdf :as rdf]))

(def cross-link-rels
  "The relations used to link to other datasets; sourced from the entity page
  sections to keep the two definitions in sync."
  (set (second section/cross-link-section)))

(def group-order
  "Display order for synset relation groups with titles, descriptions, and the
  member relations listed in display order; most important relations first,
  inverse pairs adjacent. A nil member coll marks the fallback group."
  [[:taxonomic
    {:da "Taksonomi" :en "Taxonomy"}
    {:da "Relationer der forbinder over- og underbegreber, dvs. hypernymi og hyponymi i forskellige afskygninger."
     :en "Relations connecting broader and narrower concepts, i.e. variations of hypernymy and hyponymy."}
    [:wn/hypernym :wn/hyponym
     :wn/instance_hypernym :wn/instance_hyponym
     :dns/orthogonalHypernym :dns/orthogonalHyponym
     :dns/crossPoSHypernym :dns/crossPoSHyponym]]
   [:part-whole
    {:da "Del-helhed" :en "Part–whole"}
    {:da "Relationer mellem dele og helheder, dvs. meronymi og holonymi."
     :en "Relations between parts and wholes, i.e. meronymy and holonymy."}
    [:wn/meronym :wn/holonym
     :wn/mero_part :wn/holo_part
     :wn/mero_member :wn/holo_member
     :wn/mero_substance :wn/holo_substance
     :wn/mero_location :wn/holo_location]]
   [:similarity
    {:da "Lighed og modsætning" :en "Similarity & opposition"}
    {:da "Relationer der udtrykker lighed, modsætning eller attributter."
     :en "Relations expressing similarity, opposition, or attributes."}
    [:wn/similar :wn/antonym :dns/nearAntonym :wn/attribute]]
   [:roles
    {:da "Roller, funktion og kausalitet" :en "Roles, function & causality"}
    {:da "Relationer der beskriver deltagerroller, funktion eller årsagssammenhænge."
     :en "Relations describing participant roles, function, or causal connections."}
    [:wn/agent :wn/involved_agent
     :wn/patient :wn/involved_patient
     :wn/result :wn/involved_result
     :wn/co_agent_instrument :wn/co_instrument_agent
     :dns/usedFor :dns/usedForObject
     :wn/causes :wn/is_caused_by
     :wn/entails :wn/is_entailed_by]]
   [:domain
    {:da "Domæne og eksemplificering" :en "Domain & exemplification"}
    {:da "Relationer der knytter begreber til emneområder, regioner eller eksempler."
     :en "Relations connecting concepts to topics, regions, or examples."}
    [:wn/domain_topic :wn/has_domain_topic
     :wn/domain_region :wn/has_domain_region
     :wn/exemplifies :wn/is_exemplified_by]]
   [:cross-link
    {:da "Eksterne forbindelser" :en "External links"}
    {:da "Relationer der forbinder DanNets begreber med begreber i andre datasæt; disse vises også separat på den enkelte synset-side."
     :en "Relations connecting DanNet concepts to concepts in other datasets; these are also displayed separately on the individual synset page."}
    ;; Local display order; any other cross-link relations are appended.
    (vec (distinct (concat [:wn/ili :dns/linkedConcept
                            :wn/eq_synonym :dns/eqHypernym
                            :dns/eqHyponym :dns/eqSimilar
                            :owl/sameAs]
                           cross-link-rels)))]
   [:other
    {:da "Andet" :en "Other"}
    {:da "Øvrige relationer."
     :en "Other relations."}
    nil]])

(def relation-order
  (into [] (mapcat (fn [[_ _ _ rels]] rels)) group-order))

(def relation->group
  (into {}
        (mapcat (fn [[group-key _ _ rels]]
                  (map (fn [rel] [rel group-key]) rels)))
        group-order))

(def relation->rank
  "Map from a relation to its index in 'relation-order'."
  (zipmap relation-order (range)))

(defn relation-sort-key
  "Ordering key for a relation `k` carrying localised `label`."
  [k label]
  [(get relation->rank k (count relation-order)) (str label)])

(defn relation->class
  "Generic CSS class marking the group of relation `k`."
  [k]
  (str (name (or (relation->group k) :other)) "-rel"))

(defn prepare-groups
  "Transform a map of `relations` based on `opts` into grouped entries.

  Returns a seq of [group-key title description entries] tuples, where entries
  follow the display order defined in `group-order`; relations not explicitly
  listed sort alphabetically at the end of their group. Relations not covered
  by any group end up in the final, fallback group."
  [relations opts]
  (let [rel-group (fn [[rel _]]
                    (or (some (fn [[group-key _ _ rels]]
                                (when (and rels ((set rels) rel))
                                  group-key))
                              group-order)
                        (first (last group-order))))
        grouped   (group-by rel-group relations)]
    (keep (fn [[group-key title desc rels]]
            (when-let [entries (get grouped group-key)]
              (let [rel->idx (zipmap rels (range))]
                [group-key title desc
                 (sort-by (fn [[rel _]]
                            [(get rel->idx rel (count rels)) (name rel)])
                          entries)])))
          group-order)))

(defn k->label
  "Build a keyword->label map from the `relations` entities based on the
  current `detail-level` (no labels are returned for the :basic level)."
  [relations detail-level]
  (update-vals relations (shared/->entity-label-fn detail-level)))

(defn table
  "Render a single relations table for a group of `entries` using `k->label`."
  [{:keys [languages] :as opts} k->label entries]
  [:table.attr-val
   [:colgroup
    [:col {:aria-label (i18n/da-en languages "RDF-præfix" "RDF prefix")}]
    [:col {:aria-label (i18n/da-en languages "Relation" "Relation")}]
    [:col {:aria-label (i18n/da-en languages "Beskrivelse" "Description")}]]
   [:tbody
    (for [[rel {:keys [rdfs/comment]}] entries
          :let [opts' (assoc opts :k->label k->label)]]
      [:tr {:key (str rel)}
       [:td.attr-prefix
        (rdf/prefix-badge (symbol (namespace rel)))]
       [:td.attr-name
        (rdf/entity-link rel opts')]
       [:td (when comment
              (rdf/transform-text opts comment))]])]])
