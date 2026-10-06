(ns dk.cst.dannet.db.stats
  "Statistics comparing the current DanNet release with the legacy DanNet 2.2,
  rendered as the tables of a paper.

  The legacy side is read from the DanNet 2.2 CSV release (the @-separated
  files in bootstrap/dannet/DanNet-2.2_csv), the current side from the graphs
  of a live db map, with the old relation names mapped onto the relations they
  became during the 2023 conversion so the two inventories line up. Run

    (export-stats! @dk.cst.dannet.web.instance/db)

  to write export/stats/<version>/stats.{edn,md,tex}, and

    (export-relation-mapping! @dk.cst.dannet.web.instance/db)

  to write the conversion history of every DanNet 2.2 relation to
  doc/relation-mapping.md."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [dk.cst.dannet.db :as db]
            [dk.cst.dannet.db.query :as q]
            [dk.cst.dannet.db.query.operation :as op]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.release :as release])
  (:import [java.util.zip ZipFile]
           [org.apache.jena.riot Lang RDFDataMgr]
           [org.apache.jena.sparql.graph GraphFactory]))

(def taxonomic-relations
  "The relations placing a synset in a hierarchy; a synset with no other
  relation counts as taxonomic-only."
  #{:wn/hypernym :wn/hyponym
    :wn/instance_hypernym :wn/instance_hyponym
    :dns/orthogonalHypernym :dns/orthogonalHyponym
    :dns/crossPoSHypernym :dns/crossPoSHyponym})

(defn- median
  [xs]
  (let [sorted (vec (sort xs))
        n      (count sorted)]
    (when (pos? n)
      (if (even? n)
        (/ (+ (sorted (dec (quot n 2))) (sorted (quot n 2))) 2.0)
        (double (sorted (quot n 2)))))))

(defn degree-stats
  "Degree statistics of the `synsets` given the [source relation target]
  `edges` between them; a synset's degree counts the edges in either
  direction, i.e. the relations navigable from it once every relation has
  its inverse."
  [synsets edges]
  (let [out     (frequencies (map first edges))
        in      (frequencies (map peek edges))
        non-tax (frequencies (map first (remove (comp taxonomic-relations second)
                                                edges)))
        degrees (map #(+ (get out % 0) (get in % 0)) synsets)
        n       (count synsets)]
    {:synsets        n
     :mean-out       (double (/ (count edges) n))
     :mean-degree    (double (/ (reduce + degrees) n))
     :median-degree  (median degrees)
     :isolated       (count (filter zero? degrees))
     :taxonomic-only (count (filter #(and (pos? (get out % 0))
                                          (zero? (get non-tax % 0)))
                                    synsets))}))

(defn inverse-closure
  "The number of `edges` plus the inverse edges implied by the owl:inverseOf
  pairs `inverses` that are not already asserted."
  [inverses edges]
  (let [asserted (set edges)]
    (+ (count edges)
       (count (for [[s p o] edges
                    :let [q (get inverses p)]
                    :when (and q (not (asserted [o q s])))]
                1)))))

(def legacy-dir
  "The DanNet 2.2 CSV release, the baseline the rebuild is compared with."
  "bootstrap/dannet/DanNet-2.2_csv")

(def relation-history
  "The conversion history of every DanNet 2.2 relation, in table order.

  :key matches legacy-key, :name is the DanNet name in relations.csv, and
  :draft, :initial and :current are the relations it became in the 2021
  draft (GitHub issue #2), the first release (2023-05-11) and the current
  conversion; nil when dropped. :linked lists what the links to Princeton
  WordNet became, and :note explains the changes that are not obvious."
  [{:key       "hyponymOf"
    :name      "has_hyperonym"
    :qualifier "taxonomic"
    :draft     :wn/hypernym
    :initial   :wn/hypernym
    :current   :wn/hypernym
    :note      (str "A GWA hypernym stays within one part of speech. Cross-PoS "
                    "pairs became `dns:crossPoSHypernym` in July 2025 and mostly "
                    "`wn:attribute` in September 2026 ([#146]"
                    "(https://github.com/kuhumcst/DanNet/issues/146), see "
                    "[crosspos/README.md](crosspos/README.md)).")}
   {:key       "hyponymOf/nontaxonomic"
    :name      "has_hyperonym"
    :qualifier "nontaxonomic"
    :draft     [:wn/hypernym :dns/hypernym_ortho]
    :initial   :dns/orthogonalHypernym
    :current   :dns/orthogonalHypernym
    :note      (str "GWA has no relation for orthogonal hyponymy. Since "
                    "September 2021, the link is not also a `wn:hypernym` "
                    "([#7](https://github.com/kuhumcst/DanNet/issues/7)). "
                    "Almost none of these synsets has another hypernym.")}
   {:key     "hypernymOf"
    :name    "has_hyponym"
    :draft   :wn/hyponym
    :initial :wn/hyponym
    :current :wn/hyponym}
   {:key     "instanceOf"
    :name    "is_instance_of"
    :draft   :wn/instance_hypernym
    :initial :wn/instance_hypernym
    :current :wn/instance_hypernym}
   {:key     "meronymOf"
    :name    "has_holonym"
    :draft   :wn/holonym
    :initial :wn/holonym
    :current :wn/holonym}
   {:key     "partMeronymOf"
    :name    "has_holo_part"
    :draft   :wn/holo_part
    :initial :wn/holo_part
    :current :wn/holo_part
    :note    (str "In August 2026, SHACL checks of meronymy found part-whole "
                  "links that were reversed or contradictory.")}
   {:key     "partHolonymOf"
    :name    "has_mero_part"
    :draft   :wn/mero_part
    :initial :wn/mero_part
    :current :wn/mero_part}
   {:key     "memberMeronymOf"
    :name    "has_holo_member"
    :draft   :wn/holo_member
    :initial :wn/holo_member
    :current :wn/holo_member}
   {:key     "memberHolonymOf"
    :name    "has_mero_member"
    :draft   :wn/mero_member
    :initial :wn/mero_member
    :current :wn/mero_member}
   {:key     "madeofMeronymOf"
    :name    "has_holo_madeof"
    :draft   :wn/holo_substance
    :initial :wn/holo_substance
    :current :wn/holo_substance
    :note    "GWA calls the material of a thing its substance."}
   {:key     "madeofHolonymOf"
    :name    "has_mero_madeof"
    :draft   :wn/mero_substance
    :initial :wn/mero_substance
    :current :wn/mero_substance}
   {:key     "locationMeronymOf"
    :name    "has_holo_location"
    :draft   :wn/holo_location
    :initial :wn/holo_location
    :current :wn/holo_location}
   {:key     "locationHolonymOf"
    :name    "has_mero_location"
    :draft   :wn/mero_location
    :initial :wn/mero_location
    :current :wn/mero_location}
   {:key     "roleAgent"
    :name    "role_agent"
    :draft   :wn/agent
    :initial :wn/agent
    :current :wn/agent
    :note    "GWA gives role_agent as the EuroWordNet name of `agent`."}
   {:key     "rolePatient"
    :name    "role_patient"
    :draft   :wn/patient
    :initial :wn/patient
    :current :wn/patient
    :note    "GWA gives role_patient as the EuroWordNet name of `patient`."}
   {:key     "involvedPatient"
    :name    "involved_patient"
    :draft   :wn/involved_patient
    :initial :wn/involved_patient
    :current :wn/involved_patient}
   {:key     "involvedAgent"
    :name    "involved_agent"
    :draft   :wn/co_agent_instrument
    :initial :wn/co_instrument_agent
    :current :wn/co_instrument_agent
    :note    (str "DanNet uses it from an instrument to its user, e.g. "
                  "{violin} to {violinist}, so it is a GWA co-role. The 2021 "
                  "draft had the direction wrong. Reversed in January 2022.")}
   {:key     "involvedInstrument"
    :name    "involved_instrument"
    :draft   :wn/co_instrument_agent
    :initial :wn/co_agent_instrument
    :current :wn/co_agent_instrument
    :note    (str "From a user to its instrument, e.g. {redningsmandskab} to "
                  "{redningsudstyr}. Reversed in January 2022.")}
   {:key     "madeBy"
    :name    "made_by"
    :draft   :wn/result
    :initial :wn/result
    :current :wn/result
    :note    "GWA has no made-by relation. `wn:result` is the nearest one."}
   {:key     "usedFor"
    :name    "used_for"
    :draft   :wn/instrument
    :initial :dns/usedFor
    :current :dns/usedFor
    :note    (str "GWA `instrument` goes from an action to a tool that the "
                  "action needs. DanNet used_for is wider, e.g. {bagage} to "
                  "{rejse}, and goes from a thing to its use. Changed in "
                  "January 2022.")}
   {:key     "usedForObject"
    :name    "used_for_object"
    :draft   :wn/involved_instrument
    :initial :dns/usedForObject
    :current :dns/usedForObject
    :note    "Changed in January 2022, together with used_for."}
   {:key     "concerns"
    :name    "concerns"
    :draft   :wn/also
    :initial :wn/also
    :current :wn/also
    :note    "GWA has no equivalent, and `wn:also` is its loosest relation."}
   {:key     "domain"
    :name    "domain"
    :draft   :wn/has_domain_topic
    :initial :wn/has_domain_topic
    :current :wn/domain_topic
    :note    (str "`wn:has_domain_topic` goes from a domain to its terms. "
                  "Fixed in September 2023. The 2021 PDF draft in "
                  "[#2](https://github.com/kuhumcst/DanNet/issues/2) already "
                  "had `wn:domain_topic`. 119 of the removed rows linked a "
                  "synset to itself.")}
   {:key     "nearSynonymOf"
    :name    "near_synonym"
    :draft   :wn/similar
    :initial :wn/similar
    :current :wn/similar
    :note    "GWA gives near_synonym as the EuroWordNet name of `similar`."}
   {:key     "xposNearSynonymOf"
    :name    "xpos_near_synonym"
    :draft   :wn/similar
    :initial :wn/similar
    :current :wn/similar
    :note    (str "GWA has no cross-PoS synonym relation, so the cross-PoS "
                  "mark is lost. The parts of speech of the two synsets "
                  "still show it.")}
   {:key     "nearAntonymOf"
    :name    "near_antonym"
    :draft   :wn/antonym
    :initial :dns/nearAntonym
    :current :dns/nearAntonym
    :note    (str "GWA `antonym` is strict, and near antonymy is weaker. "
                  "Changed in January 2022.")}
   {:key    "eqSynonymOf"
    :name   "eq_has_synonym"
    :linked [:wn/eq_synonym :wn/ili]
    :note   (str "Links to Princeton WordNet 2.0. Since June 2023, they are "
                 "`wn:eq_synonym` links to OEWN (by sense key) or `wn:ili` "
                 "links (by ENG20 offset), see "
                 "[#3](https://github.com/kuhumcst/DanNet/issues/3).")}
   {:key    "eqHyponymOf"
    :name   "eq_has_hyperonym"
    :linked [:dns/eqHypernym]
    :note   (str "GWA has no cross-lingual hypernym relation. Added in July "
                 "2023. Links to ILI concepts moved to OEWN synsets in August "
                 "2026 ([#205](https://github.com/kuhumcst/DanNet/issues/205)).")}
   {:key    "eqHypernymOf"
    :name   "eq_has_hyponym"
    :linked [:dns/eqHyponym]
    :note   "Added in July 2023, together with eq_has_hyperonym."}
   {:key  "usedForQualifiedBy"
    :name "used_for_qualby"
    :note "Only 4 rows. The 2021 code calls 3 of them broken."}])

(def legacy-relations
  "The relation-history keys and the relations they were converted to; nil
  for the names that were dropped."
  (into {} (map (juxt :key :current)) relation-history))

(def cross-lingual-relations
  "The legacy relation names linking synsets to Princeton WordNet."
  #{"eqSynonymOf" "eqHypernymOf" "eqHyponymOf"})

(def placeholder-gloss
  "The gloss DanNet 2.2 gave synsets without a definition."
  "(ingen definition)")

(defn read-legacy-rows
  "The rows of the @-separated legacy file `f`, each a vector of fields."
  [f]
  (with-open [reader (io/reader f :encoding "ISO-8859-1")]
    (->> (line-seq reader)
         (remove str/blank?)
         (mapv #(str/split % #"@" -1)))))

(defn legacy-key
  "The relation-history key of a legacy relations.csv `row`: its relation
  name, suffixed for the hyponymOf rows flagged nontaxonomic."
  [[_ name _ _ taxonomic]]
  (cond-> name
    (= taxonomic "nontaxonomic") (str "/nontaxonomic")))

(defn legacy-relation
  "The current relation of a legacy relations.csv `row`; nil when dropped."
  [row]
  (get legacy-relations (legacy-key row)))

(defn legacy-edges
  "The internal relations of legacy relations.csv `rows` as [source relation
  target] triples, dropped names and cross-lingual links excluded."
  [rows]
  (for [[source _ _ target :as row] rows
        :let [relation (legacy-relation row)]
        :when relation]
    [source relation target]))

(defn danish-sense?
  "Whether a legacy wordsenses.csv `row` is a Danish sense rather than a
  Princeton WordNet sense key used as a link placeholder."
  [[_ _ synset]]
  (re-matches #"\d+" synset))

(defn legacy-stats
  "The statistics of the legacy CSV release in `dir`, the owl:inverseOf pairs
  `inverses` of the current schemas deciding its inverse closure."
  [dir inverses]
  (let [synsets   (read-legacy-rows (io/file dir "synsets.csv"))
        senses    (filter danish-sense?
                          (read-legacy-rows (io/file dir "wordsenses.csv")))
        relations (read-legacy-rows (io/file dir "relations.csv"))
        edges     (legacy-edges relations)
        defined?  (fn [[_ _ gloss]]
                    (let [gloss (str/trim (or gloss ""))]
                      (not (or (= gloss "")
                               (str/starts-with? gloss placeholder-gloss)))))]
    {:synsets     (count synsets)
     :senses      (count (distinct (map first senses)))
     :words       (count (distinct (map second senses)))
     :definitions (count (filter defined? synsets))
     :relations   (frequencies (map second edges))
     :external    (count (filter (comp cross-lingual-relations second) relations))
     :degrees     (degree-stats (map first synsets) edges)
     :closure     (inverse-closure inverses edges)}))

(defn- synset-filter
  [var]
  (str "FILTER(STRSTARTS(STR(" var "), \"" prefix/dn-uri "synset-\"))"))

(defn- dannet-filter
  [var]
  (str "FILTER(STRSTARTS(STR(" var "), \"" prefix/dn-uri "\"))"))

(defn count-in
  "The number of solutions of the SPARQL `where` clause in graph `g`, or the
  number of distinct bindings of `var` when given."
  ([g where]
   (count-in g where nil))
  ([g where var]
   (let [aggregate (if var (str "COUNT(DISTINCT " var ")") "COUNT(*)")
         query     (op/sparql "SELECT (" aggregate " AS ?n) WHERE { " where " }")]
     (or (some-> (q/run g query) first (get '?n)) 0))))

(defn relation-edges
  "The asserted [source relation target] triples of wn: and dns: relations
  between dn: synsets in the base graph `g`.

  dns:subsumed is not a relation: it links a synset to the id of a removed
  one."
  [g]
  (->> (q/run g (op/sparql "SELECT ?s ?p ?o WHERE { ?s ?p ?o . "
                           (synset-filter "?s") (synset-filter "?o") " }"))
       (keep (fn [{:syms [?s ?p ?o]}]
               (when (and (#{"wn" "dns"} (namespace ?p))
                          (not= :dns/subsumed ?p))
                 [?s ?p ?o])))))

(defn negated-edges
  "The [source relation target] triples that an owl:NegativePropertyAssertion
  denies for dn: synsets in the base graph `g`."
  [g]
  (->> (q/run g (op/sparql "SELECT ?s ?p ?o WHERE { "
                           "?n a owl:NegativePropertyAssertion ; "
                           "owl:sourceIndividual ?s ; owl:assertionProperty ?p ; "
                           "owl:targetIndividual ?o . " (synset-filter "?s") " }"))
       (map (fn [{:syms [?s ?p ?o]}] [?s ?p ?o]))))

(defn similar-breakdown
  "The wn:similar `edges` by how many of their endpoints (0, 1 or 2) are
  synsets of the 2023 adjective supplement, recognisable by their synthesised
  synset-s ids; the supplement is the source of most of these edges."
  [edges]
  (frequencies (for [[s p o] edges
                     :when (= p :wn/similar)]
                 (count (filter #(re-find #"synset-s" (str %)) [s o])))))

(defn inverse-relations
  "The owl:inverseOf pairs declared by the schemas in graph `g`, as a map in
  both directions."
  [g]
  (->> (q/run g (op/sparql "SELECT ?p ?q WHERE { ?p owl:inverseOf ?q }"))
       (mapcat (fn [{:syms [?p ?q]}] [[?p ?q] [?q ?p]]))
       (into {})))

(defn synset-pairs
  "The relations of the [source relation target] `edges`, keyed by their
  [source target] pair."
  [edges]
  (reduce (fn [m [s p o]] (update m [s o] (fnil conj #{}) p)) {} edges))

(defn relation-outcomes
  "What became of the synset relations in the legacy relations.csv `rows`,
  given the current `edges`, their owl:inverseOf pairs `inverses` and the
  set of `negated` edges; the frequency of each outcome per relation-history
  key.

  An outcome is :kept when the converted relation still links the two
  synsets, also as its inverse the other way round, :reversed when it links
  them the other way round, and :negated when the data now denies it.
  Otherwise it is the set of relations that link them instead, or :removed.
  A relation that another legacy row converts to does not count as instead."
  [inverses rows edges negated]
  (let [synset  #(keyword "dn" (str "synset-" %))
        current (synset-pairs edges)
        legacy  (synset-pairs (for [[s p o] (legacy-edges rows)]
                                [(synset s) p (synset o)]))
        instead #(set/difference (get current % #{}) (get legacy %))]
    (->> (for [[s _ _ o :as row] rows
               :let [p (legacy-relation row)]
               :when p
               :let [forward  [(synset s) (synset o)]
                     backward [(synset o) (synset s)]]]
           [(legacy-key row)
            (cond
              (or (contains? (current forward) p)
                  (contains? (current backward) (inverses p))) :kept
              (contains? (current backward) p) :reversed
              (contains? negated [(synset s) p (synset o)]) :negated
              :else (or (not-empty (set/union (instead forward)
                                              (instead backward)))
                        :removed))])
         (reduce (fn [m [k outcome]] (update-in m [k outcome] (fnil inc 0)))
                 {}))))

(defn navigable-relations
  "The synset `relations` and their `inverses` counted in the inference graph
  `g`, i.e. the relations a user can follow on wordnet.dk."
  [g inverses relations]
  (into {}
        (for [p (distinct (concat relations (keep inverses relations)))]
          [p (count-in g (str "?s " (prefix/kw->qname p) " ?o . "
                              (synset-filter "?s") (synset-filter "?o")))])))

(def link-counts
  "The links reaching beyond the synset graph: a label, the SPARQL to count
  and the variable to count distinct values of (nil counts every solution)."
  [["Senses with a DDO source"
    (str "?s a ontolex:LexicalSense ; dns:source ?o . " (dannet-filter "?s")) "?s"]
   ["Words with a DDO source"
    (str "?s ontolex:canonicalForm ?f ; dns:source ?o . " (dannet-filter "?s")) "?s"]
   ["Words linked to a COR word"
    (str "?s ontolex:canonicalForm ?f ; owl:sameAs ?o . " (dannet-filter "?s")) "?s"]
   ["Synsets with an ILI concept"
    (str "?s wn:ili ?o . " (synset-filter "?s")) "?s"]
   ["Distinct ILI concepts linked"
    (str "?s wn:ili ?o . " (synset-filter "?s")) "?o"]
   ["Synsets sharing their ILI concept with another synset"
    (str "?s wn:ili ?o . ?t wn:ili ?o . FILTER(?s != ?t) " (synset-filter "?s")
         (synset-filter "?t")) "?s"]
   ["Synset links to OEWN synsets (eq*)"
    (str "VALUES ?p { wn:eq_synonym dns:eqHypernym dns:eqHyponym dns:eqSimilar } "
         "?s ?p ?o . " (synset-filter "?s")) nil]
   ["Synsets with a supersense"
    (str "?s wn:lexfile ?o . " (synset-filter "?s")) "?s"]
   ["Synsets with an ontological type"
    (str "?s dns:ontologicalType ?o . " (synset-filter "?s")) "?s"]
   ["Synsets with sentiment"
    (str "?s dns:sentiment ?o . " (synset-filter "?s")) "?s"]
   ["Senses with sentiment"
    (str "?s a ontolex:LexicalSense ; dns:sentiment ?o . " (dannet-filter "?s")) "?s"]
   ["Inheritance markings"
    (str "?s dns:inherited ?o . " (synset-filter "?s")) nil]
   ["Senses with an example"
    (str "?s a ontolex:LexicalSense ; lexinfo:senseExample ?o . " (dannet-filter "?s")) "?s"]
   ["COR.SEM senses linked to a synset" "?s dns:linkedSynset ?o" "?s"]
   ["COR.SEM senses with a hypernym anchor" "?s dns:hypernymAnchor ?o" "?s"]
   ["COR.SEM senses matched to a DanNet sense" "?s dns:eqSense ?o" "?s"]
   ["COR.SEM senses with a FrameNet frame" "?s dns:frame ?o" "?s"]])

(defn count-links
  "The link-counts of the base union graph `g` as a vector of [label count]."
  [g]
  (vec (for [[label where var] link-counts]
         [label (count-in g where var)])))

(defn dataset-stats
  "The lime and void statistics every dataset declares about itself in the
  base union graph `g`."
  [g]
  (->> (q/run g (op/sparql
                  "SELECT DISTINCT ?d ?triples ?entries ?concepts ?lexicalizations
                   WHERE {
                     VALUES ?type { dcat:Dataset lime:Lexicon }
                     ?d a ?type .
                     OPTIONAL { ?d void:triples ?triples }
                     OPTIONAL { ?d lime:lexicalEntries ?entries }
                     OPTIONAL { ?d lime:concepts ?concepts }
                     OPTIONAL { ?d lime:lexicalizations ?lexicalizations }
                   }"))
       (map (fn [{:syms [?d ?triples ?entries ?concepts ?lexicalizations]}]
              [(str ?d) ?triples ?entries ?concepts ?lexicalizations]))
       (sort-by first)
       (vec)))

(defn current-stats
  "The statistics of the live db map `dannet`: the asserted dn: graph for the
  counts, the base union graph for the links to other datasets, and the
  inference graph for the navigable relations."
  [{:keys [dataset graph] :as dannet}]
  (let [dn        (db/get-graph dataset prefix/dn-uri)
        base      (.getGraph (.getUnionModel dataset))
        edges     (relation-edges dn)
        synsets   (->> (q/run dn '[:bgp [?s :rdf/type :ontolex/LexicalConcept]])
                       (map '?s))
        relations (frequencies (map second edges))
        inverses  (inverse-relations graph)]
    {:version     release/to
     :synsets     (count synsets)
     :senses      (count-in dn "?s a ontolex:LexicalSense")
     :words       (count-in dn "?w ontolex:canonicalForm ?f")
     :definitions (count-in dn (str "?s skos:definition ?d . " (synset-filter "?s")) "?s")
     :relations   relations
     :external    (count-in dn (str "VALUES ?p { wn:eq_synonym dns:eqHypernym "
                                    "dns:eqHyponym dns:eqSimilar } ?s ?p ?o . "
                                    (synset-filter "?s")))
     :ili         (count-in dn (str "?s wn:ili ?o . " (synset-filter "?s")))
     :degrees     (degree-stats synsets edges)
     :similar     (similar-breakdown edges)
     :closure     (inverse-closure inverses edges)
     :navigable   (navigable-relations graph inverses (keys relations))
     :links       (count-links base)
     :datasets    (dataset-stats base)}))

(def english-dir
  "The local files that the 2023 conversion used to map the DanNet 2.2 links
  to Princeton WordNet onto the OEWN (sense keys) and the ILI (ENG20 offsets)."
  "bootstrap/other/english")

(defn link-edges
  "The asserted [synset relation target] links from dn: synsets to the OEWN
  and the ILI in graph `g`."
  [g]
  (->> (q/run g (op/sparql "SELECT ?s ?p ?o WHERE { VALUES ?p { wn:eq_synonym "
                           "wn:ili dns:eqHypernym dns:eqHyponym dns:eqSimilar } "
                           "?s ?p ?o . " (synset-filter "?s") " }"))
       (map (fn [{:syms [?s ?p ?o]}] [?s ?p ?o]))))

(defn oewn-synsets-by-ili
  "The OEWN synsets of each ILI concept in graph `g`."
  [g]
  (->> (q/run g (op/sparql "SELECT ?e ?i WHERE { ?e wn:ili ?i . "
                           "FILTER(STRSTARTS(STR(?e), \"" prefix/oewn-uri "\")) }"))
       (reduce (fn [m {:syms [?e ?i]}] (update m ?i (fnil conj #{}) ?e)) {})))

(defn english-targets
  "The current targets of the Princeton WordNet IDs in the DanNet 2.2 links,
  from the mapping files in `dir` and the OEWN synsets of each ILI concept
  in `oewn`.

  A sense key maps to its OEWN synset, an ENG20 offset to its ILI concept
  and that concept's OEWN synsets."
  [dir oewn]
  (merge (update-vals (edn/read-string (slurp (io/file dir "senseidx.edn")))
                      hash-set)
         (into {} (for [line (str/split-lines
                               (slurp (io/file dir "ili-map-pwn20.tab")))
                        :let [[ili offset confidence] (str/split line #"\t")]
                        :when (= confidence "1")
                        :let [ili (keyword "ili" ili)]]
                    [(str "ENG20-" offset) (conj (get oewn ili #{}) ili)]))))

(defn legacy-link-outcomes
  "What became of the links to Princeton WordNet in the legacy relations.csv
  `rows`, given the current `targets` of their IDs and the current `links`;
  the frequency of each outcome per relation-history key.

  An outcome is the relation that now links the synset to the same concept.
  When several do, the ones in the :linked of the relation-history entry win,
  then wn:ili. It is :missing when no link to the concept remains, and
  :unmapped when the ID has no current target."
  [targets links rows]
  (let [synset  #(keyword "dn" (str "synset-" %))
        current (synset-pairs links)
        linked  (into {} (map (juxt :key :linked)) relation-history)]
    (->> (for [[s _ _ t :as row] rows
               :let [k     (legacy-key row)
                     found (set (mapcat #(get current [(synset s) %])
                                        (get targets t)))]]
           [k (cond
                (empty? (get targets t)) :unmapped
                (empty? found) :missing
                :else (or (some found (concat (linked k) [:wn/ili]))
                          (first (sort found))))])
         (reduce (fn [m [k outcome]] (update-in m [k outcome] (fnil inc 0)))
                 {}))))

(defn link-origins
  "The current `links` per relation, split into the ones that point to the
  concept of a DanNet 2.2 link from the same synset (:legacy) and the rest
  (:new), given the legacy relations.csv `rows` and their current `targets`."
  [targets links rows]
  (let [synset #(keyword "dn" (str "synset-" %))
        legacy (set (for [[s _ _ t] rows
                          o (get targets t)]
                      [(synset s) o]))]
    (->> links
         (map (fn [[s p o]] [p (if (legacy [s o]) :legacy :new)]))
         (frequencies)
         (reduce (fn [m [[p origin] n]] (assoc-in m [p origin] n)) {}))))

(defn link-stats
  "What became of the DanNet 2.2 links to Princeton WordNet in the legacy CSV
  release in `dir`, and the origin of the current links of the live db map
  `dannet`; see legacy-link-outcomes and link-origins."
  [dir {:keys [dataset] :as dannet}]
  (let [rows    (->> (read-legacy-rows (io/file dir "relations.csv"))
                     (filter (comp cross-lingual-relations second)))
        oewn    (oewn-synsets-by-ili (.getGraph (.getUnionModel dataset)))
        targets (english-targets english-dir oewn)
        links   (link-edges (db/get-graph dataset prefix/dn-uri))]
    {:outcomes (legacy-link-outcomes targets links rows)
     :origins  (link-origins targets links rows)}))

(defn- total
  [relations]
  (reduce + 0 (vals relations)))

(defn- decimals
  "Format `x` with two decimals and a period, whatever the JVM locale."
  [x]
  (String/format java.util.Locale/ROOT "%.2f" (to-array [(double x)])))

(defn- share
  [part whole]
  (String/format java.util.Locale/ROOT "%d (%.1f%%)"
                 (to-array [part (* 100.0 (/ part whole))])))

(defn size-table
  "Size of the two releases side by side."
  [legacy current]
  {:header ["Measure" "DanNet 2.2" (str "DanNet " (:version current))]
   :rows   [["Synsets" (:synsets legacy) (:synsets current)]
            ["Senses" (:senses legacy) (:senses current)]
            ["Words" (:words legacy) (:words current)]
            ["Synsets with a definition" (:definitions legacy) (:definitions current)]
            ["Asserted synset relations" (total (:relations legacy)) (total (:relations current))]
            ["Relation types" (count (:relations legacy)) (count (:relations current))]
            ["Links to English synsets" (:external legacy) (:external current)]
            ["Links to ILI concepts" nil (:ili current)]]})

(defn relation-table
  "Every synset relation with its count in both releases, largest first."
  [legacy current]
  (let [relations (distinct (concat (keys (:relations current))
                                    (keys (:relations legacy))))]
    {:header ["Relation" "DanNet 2.2" (str "DanNet " (:version current))]
     :rows   (->> relations
                  (map (fn [p] [(str (namespace p) ":" (name p))
                                (get-in legacy [:relations p] 0)
                                (get-in current [:relations p] 0)]))
                  (sort-by (fn [[_ old new]] [(- new) (- old)]))
                  (vec))}))

(defn connectivity-table
  "Connectivity of the synset graph in both releases: degrees count asserted
  relations in either direction, the closure adds the inverses the schemas
  imply, and the inference model is what wordnet.dk serves."
  [legacy current]
  (let [old (:degrees legacy)
        new (:degrees current)]
    {:header ["Measure" "DanNet 2.2" (str "DanNet " (:version current))]
     :rows   [["Asserted synset relations" (total (:relations legacy)) (total (:relations current))]
              ["Relations with inverses (closure)" (:closure legacy) (:closure current)]
              ["Relations in the inference model" nil (total (:navigable current))]
              ["Mean outgoing relations per synset" (decimals (:mean-out old))
               (decimals (:mean-out new))]
              ["Mean degree" (decimals (:mean-degree old)) (decimals (:mean-degree new))]
              ["Median degree" (:median-degree old) (:median-degree new)]
              ["Synsets without relations" (:isolated old) (:isolated new)]
              ["Synsets with taxonomic relations only"
               (share (:taxonomic-only old) (:synsets old))
               (share (:taxonomic-only new) (:synsets new))]]}))

(defn similar-table
  "The wn:similar edges of the current release by how many of their endpoints
  belong to the 2023 adjective supplement."
  [current]
  (let [similar (:similar current)]
    {:header ["wn:similar edges" "Count"]
     :rows   [["Between two 2023 adjective synsets" (get similar 2 0)]
              ["One endpoint a 2023 adjective synset" (get similar 1 0)]
              ["Between older synsets" (get similar 0 0)]]}))

(defn link-table
  "The links beyond the synset graph in the current release."
  [current]
  {:header ["Link" "Count"]
   :rows   (:links current)})

(defn dataset-table
  "The self-declared size of every dataset in the store."
  [current]
  {:header ["Dataset" "Triples" "Entries" "Concepts" "Lexicalizations"]
   :rows   (:datasets current)})

(defn legacy-link-table
  "What became of the DanNet 2.2 links to Princeton WordNet, from the `links`
  stats map; see legacy-link-outcomes."
  [links]
  {:header ["DanNet 2.2 link" "Now" "Rows"]
   :rows   (vec (for [{:keys [key name]} relation-history
                      [outcome n] (sort-by (comp - val)
                                           (get-in links [:outcomes key]))]
                  [name
                   (case outcome
                     :missing "no link to the same concept"
                     :unmapped "no current ID"
                     (prefix/kw->qname outcome))
                   n]))})

(defn link-origin-table
  "The current links to the OEWN and the ILI by origin, from the `links` stats
  map: to the concept of a DanNet 2.2 link from the same synset, or new."
  [links]
  {:header ["Link" "Count" "From DanNet 2.2" "New"]
   :rows   (->> (for [[p {:keys [legacy new] :or {legacy 0 new 0}}]
                      (:origins links)]
                  [(prefix/kw->qname p) (+ legacy new) legacy new])
                (sort-by (comp - second))
                (vec))})

(defn thousands
  "Format `n` with thousands separators, whatever the JVM locale."
  [n]
  (String/format java.util.Locale/ROOT "%,d" (to-array [n])))

(defn relations->markdown
  "The `relations` (a keyword, several or nil) as Markdown code spans joined
  by `separator`; nil is dropped."
  [separator relations]
  (if relations
    (->> (if (keyword? relations) [relations] relations)
         (map #(str "`" (prefix/kw->qname %) "`"))
         (str/join separator))
    "dropped"))

(defn- outcome->markdown
  [[outcome n]]
  (str (thousands n) " "
       (case outcome
         :removed "removed"
         :reversed "reversed"
         :negated "negated"
         (str "now " (relations->markdown " + " (sort outcome))))))

(defn mapping-table
  "The conversion history of every DanNet 2.2 relation, from the legacy
  relations.csv `rows` and the `outcomes` of their relations in the data of
  `version`; see relation-history and relation-outcomes."
  [rows outcomes version]
  (let [counts (frequencies (map legacy-key rows))]
    {:header ["DanNet 2.2" "Rows" "Draft (2021)" "First release (2023)"
              (str "Now (" version ")") "Notes"]
     :rows   (vec (for [{:keys [key name qualifier draft initial current
                                linked note]} relation-history
                        :let [changes (->> (dissoc (get outcomes key) :kept)
                                           (sort-by (comp - val)))]]
                    [(cond-> (str "`" name "`")
                       qualifier (str " (" qualifier ")"))
                     (thousands (get counts key 0))
                     (relations->markdown " + " draft)
                     (relations->markdown " + " initial)
                     (if linked
                       (relations->markdown " or " linked)
                       (->> (map outcome->markdown changes)
                            (cons (relations->markdown " + " current))
                            (str/join "; ")))
                     note]))}))

(def nearest-gwa-relations
  "The DanNet 2.2 relation names whose current wn: relation is the nearest GWA
  relation rather than a direct equivalent; see relation-history for why."
  #{"made_by" "concerns" "involved_agent" "involved_instrument"
    "xpos_near_synonym"})

(defn mapping-group
  "The [group subgroup] of a relation-history `entry` in the paper's mapping
  summary, by the namespace it maps to now: :wn or :dns, then :direct,
  :nearest, :dannet or :pwn (links to Princeton WordNet); [:dropped] when
  dropped."
  [{:keys [name current linked]}]
  (let [ns' (some-> (or current (first linked)) namespace keyword)]
    (cond
      (nil? ns') [:dropped]
      linked [ns' :pwn]
      (nearest-gwa-relations name) [:wn :nearest]
      (= :wn ns') [:wn :direct]
      :else [:dns :dannet])))

(defn- tally
  "The number of relation-history `entries`, their :rows and the percentage
  of `total` rows that they make up."
  [entries total]
  (let [rows (reduce + 0 (map :rows entries))]
    {:relations (count entries)
     :rows      rows
     :percent   (/ (Math/round (* 10000.0 (/ rows total))) 100.0)}))

(defn mapping-summary
  "The DanNet 2.2 relations grouped by where they map now, with the number of
  relations and rows in the legacy relations.csv `rows` for each group and
  subgroup; see mapping-group.

  This is the data of the mapping table in the paper. A relation counts once
  per relation-history entry, so has_hyperonym counts in :wn and :dns."
  [rows]
  (let [counts  (frequencies (map legacy-key rows))
        entries (map #(assoc % :rows (get counts (:key %) 0)) relation-history)
        total   (reduce + (map :rows entries))]
    (into {:total {:relations (count entries) :rows total}}
          (for [[group es] (group-by (comp first mapping-group) entries)]
            [group (into (tally es total)
                         (for [[subgroup es'] (group-by (comp second mapping-group) es)
                               :when subgroup]
                           [subgroup (tally es' total)]))]))))

(def conversion-dir
  "The DanNet 2.5.1 CSV export, the input of the 2023 conversion."
  "bootstrap/dannet/DanNet-2.5.1_csv")

(def part-whole-relations
  "The wn: meronymy and holonymy relations."
  #{:wn/meronym :wn/holonym :wn/mero_part :wn/holo_part :wn/mero_member
    :wn/holo_member :wn/mero_substance :wn/holo_substance :wn/mero_location
    :wn/holo_location})

(defn release-graph
  "The dn: graph of the DanNet release `version`, read into memory from the
  dannet.zip asset in its bootstrap directory (see release/version-dir)."
  [version]
  (with-open [zip (ZipFile. (io/file (release/version-dir version) "dannet.zip"))
              in  (.getInputStream zip (.getEntry zip "dannet.ttl"))]
    (doto (GraphFactory/createDefaultGraph)
      (RDFDataMgr/read in Lang/TURTLE))))

(defn compare-releases
  "Call `f` with the dn: graphs of the releases `before` and `after`."
  [before after f]
  (f (release-graph before) (release-graph after)))

(defn legacy-synset-ids
  "The ids of the synsets in the synsets.csv of the legacy CSV release in
  `dir`."
  [dir]
  (set (map first (read-legacy-rows (io/file dir "synsets.csv")))))

(defn- synset-id
  [synset]
  (subs (name synset) (count "synset-")))

(defn by-origin
  "The number of `items`, also split by origin: :legacy when every synset that
  `synsets-of` gives for an item has an id in `legacy-ids`, :new otherwise."
  [legacy-ids synsets-of items]
  (let [legacy? (comp legacy-ids synset-id)
        origins (frequencies (map #(if (every? legacy? (synsets-of %)) :legacy :new)
                                  items))]
    {:count  (count items)
     :legacy (get origins :legacy 0)
     :new    (get origins :new 0)}))

(defn edges
  "The [source target] pairs that the relation `p` links in graph `g`."
  [g p]
  (set (map (juxt '?s '?o) (q/run g [:bgp ['?s p '?o]]))))

(defn typed-synsets
  "The synsets of graph `g`."
  [g]
  (set (map '?s (q/run g '[:bgp [?s :rdf/type :ontolex/LexicalConcept]]))))

(defn removed-triples
  "The triples of the relations `ps` in graph `before` that graph `after` no
  longer has."
  [before after ps]
  (let [triples (fn [g] (set (for [p ps [s o] (edges g p)] [s p o])))]
    (set/difference (triples before) (triples after))))

(defn crosspos-outcomes
  "The dns:crossPoSHypernym pairs of graph `before`, grouped by what links them
  in graph `after`: :attribute (wn:attribute), :kept, :hypernym (the part of
  speech was wrong) or :removed."
  [before after]
  (let [attribute (edges after :wn/attribute)
        kept      (edges after :dns/crossPoSHypernym)
        hypernym  (edges after :wn/hypernym)]
    (group-by #(cond (attribute %) :attribute
                     (kept %) :kept
                     (hypernym %) :hypernym
                     :else :removed)
              (edges before :dns/crossPoSHypernym))))

(defn retagged-verb-phrases
  "The synsets of graph `after` with a word that is a wn:noun in graph
  `before` and a wn:verb in `after`."
  [before after]
  (let [pos        (fn [g] (into {} (map (juxt '?w '?p))
                                 (q/run g '[:bgp [?w :wn/partOfSpeech ?p]])))
        before-pos (pos before)
        after-pos  (pos after)]
    (set (for [w     (keys before-pos)
               :when (and (= :wn/noun (before-pos w)) (= :wn/verb (after-pos w)))
               {:syms [?synset]} (q/run after [:bgp [w :ontolex/sense '?sense]
                                               ['?synset :ontolex/lexicalizedSense '?sense]])]
           ?synset))))

(defn shared-senses
  "The senses that more than one synset lexicalizes in graph `g`, each as a
  [sense synsets] pair."
  [g]
  (->> (q/run g '[:bgp [?synset :ontolex/lexicalizedSense ?sense]])
       (group-by '?sense)
       (keep (fn [[sense rows]]
               (let [synsets (set (map '?synset rows))]
                 (when (< 1 (count synsets))
                   [sense synsets]))))))

(defn legacy-shared-senses
  "The Danish senses that more than one synset shares in the legacy CSV
  release in `dir`, each as a [sense-id synsets] pair."
  [dir]
  (let [synset #(keyword "dn" (str "synset-" %))]
    (->> (read-legacy-rows (io/file dir "wordsenses.csv"))
         (filter danish-sense?)
         (group-by first)
         (keep (fn [[sense rows]]
                 (let [synsets (set (map (comp synset #(nth % 2)) rows))]
                   (when (< 1 (count synsets))
                     [sense synsets])))))))

(defn sense-id
  "The id of the DanNet 2 sense behind the dn: `sense`, without the -i<n>
  suffix that a divided sense has."
  [sense]
  (second (re-matches #"sense-(\d+)(?:-i\d+)?" (name sense))))

(defn divided-sense-ids
  "The ids of the senses that graph `g` divides over more than one synset;
  see sense-id."
  [g]
  (->> (q/run g '[:bgp [?synset :ontolex/lexicalizedSense ?sense]])
       (group-by (comp sense-id '?sense))
       (keep (fn [[id rows]]
               (when (and id (< 1 (count (set (map '?synset rows)))))
                 id)))
       (set)))

(def hypernym-pos-query
  "The [synset hypernym] pairs that dns:HypernymPOSShape in shapes/base.ttl
  warns about: the two are lexicalized by words with different parts of
  speech."
  (op/sparql "SELECT DISTINCT ?s ?o WHERE {
                ?s wn:hypernym ?o .
                FILTER(strstarts(str(?o), str(dn:)))
                ?s ontolex:lexicalizedSense ?sense1 .
                ?word1 ontolex:sense ?sense1 ; wn:partOfSpeech ?pos1 .
                ?o ontolex:lexicalizedSense ?sense2 .
                ?word2 ontolex:sense ?sense2 ; wn:partOfSpeech ?pos2 .
                FILTER(?pos1 != ?pos2)
              }"))

(defn cross-pos-hypernyms
  "The wn:hypernym pairs of graph `g` whose synsets disagree in part of
  speech; see hypernym-pos-query."
  [g]
  (set (map (juxt '?s '?o) (q/run g hypernym-pos-query))))

(def cleanup-history
  "The rows of the paper's data cleaning table: the main corrections to the
  DanNet data since DanNet 2.2, in table order under their :group, if any.

  cleanup-counts computes the count of each :key. :tag and :step name the
  release and the release step that made the correction; see
  git show <tag>:src/main/dk/cst/dannet/db/bootstrap.clj."
  [{:group "Splits and merges" :correction "duplicate synsets merged"
    :key   :duplicates :tag "v2026-08-21" :step "merge-duplicate-synsets!"}
   {:group "Splits and merges" :correction "shared senses split"
    :key   :split-senses :tag "v2026-08-21" :step "split-shared-senses!"}
   {:group      "Cross-PoS hypernyms"
    :correction "now wn:attribute"
    :key        :crosspos/attribute :tag "v2026-09-21" :step "fix-cross-pos-hypernymy!"}
   {:group      "Cross-PoS hypernyms"
    :correction "removed"
    :key        :crosspos/removed :tag "v2026-09-21" :step "fix-cross-pos-hypernymy!"}
   {:group      "Cross-PoS hypernyms"
    :correction "PoS corrected"
    :key        :crosspos/pos-fixed :tag "v2026-09-21" :step "fix-verb-phrase-pos!"}
   {:group      "Cross-PoS hypernyms"
    :correction "kept for review"
    :key        :crosspos/kept}
   {:correction "part-whole errors removed"
    :key        :part-whole :tag "v2026-08-03" :step "fix-meronym-directionality!"}])

(defn cleanup-counts
  "The count of each cleanup-history :key, split by origin; see by-origin.

  A correction is counted by comparing the export of the release before it
  with the one after it (see release-graph), so the releases 2025-07-03 to
  2026-09-21 must be in bootstrap/from/. The duplicates that the 2023
  conversion removed are the DanNet 2.5.1 synsets, untyped in 2026-08-03, that
  a synset of the asserted dn: graph `now` subsumes; the later ones were
  merged in 2026-08-21. Likewise, the senses that the 2023 conversion split
  are those shared in DanNet 2.5.1 that `now` divides over several synsets,
  less those still shared in 2026-08-03 and split in 2026-08-21.

  The cross-PoS hypernyms are those that dns:crossPoSHypernym held as a
  stopgap until 2026-09-21, plus the wn:hypernym pairs that disagree in part
  of speech (see hypernym-pos-query). Those still in either are kept for
  review."
  [now]
  (let [count-by     (partial by-origin (legacy-synset-ids legacy-dir))
        part-whole   (compare-releases "2025-07-03" "2026-08-03"
                                       #(removed-triples %1 %2 part-whole-relations))
        [shared typed-before]
        (compare-releases "2026-08-03" "2026-08-21"
                          (fn [before after]
                            (let [typed (typed-synsets after)]
                              [(group-by (fn [[_ synsets]]
                                           (if (every? typed synsets) :split :merged))
                                         (shared-senses before))
                               (typed-synsets before)])))
        [crosspos verb-phrases]
        (compare-releases "2026-08-21" "2026-09-21"
                          (juxt crosspos-outcomes retagged-verb-phrases))
        conversion   (legacy-synset-ids conversion-dir)
        removed-2023 (->> (q/run now '[:bgp [?s :dns/subsumed ?o]
                                       [?s :rdf/type :ontolex/LexicalConcept]])
                          (map '?o)
                          (filter #(and (conversion (synset-id %))
                                        (not (typed-before %)))))
        divided      (divided-sense-ids now)
        still-shared (set (map (comp sense-id first) (mapcat val shared)))
        split-2023   (filter (fn [[id _]] (and (divided id) (not (still-shared id))))
                             (legacy-shared-senses conversion-dir))]
    {:duplicates         (merge-with + (count-by list removed-2023)
                                     (count-by second (:merged shared)))
     :split-senses       (merge-with + (count-by second split-2023)
                                     (count-by second (:split shared)))
     :crosspos/attribute (count-by (comp list first) (:attribute crosspos))
     :crosspos/removed   (count-by (comp list first) (:removed crosspos))
     :crosspos/pos-fixed (merge-with + (count-by (comp list first) (:hypernym crosspos))
                                     (count-by list verb-phrases))
     :crosspos/kept      (merge-with + (count-by (comp list first) (:kept crosspos))
                                     (count-by (comp list first)
                                               (cross-pos-hypernyms now)))
     :part-whole         (count-by (fn [[s _ o]] [s o]) part-whole)}))

(defn cleanup-summary
  "The cleanup-history rows, each with the counts of its :key in the asserted
  dn: graph `now`; see cleanup-counts."
  [now]
  (let [counts (cleanup-counts now)]
    (vec (for [{:keys [key] :as row} cleanup-history]
           (merge (dissoc row :key) (counts key))))))

(def resource-links
  "The rows of the paper's links table: a resource, the DanNet items that the
  links connect, and either the link-counts :label that counts the links or
  the current-stats :key with the link-origins relations to split them by."
  [{:resource "DDO" :items "senses" :label "Senses with a DDO source"}
   {:resource "COR" :items "words" :label "Words linked to a COR word"}
   {:resource "COR.SEM" :items "COR.SEM senses"
    :label    "COR.SEM senses linked to a synset"}
   {:resource "DDS" :items "senses" :label "Senses with sentiment"}
   {:resource "OEWN" :items "synsets" :key :external
    :origins  [:wn/eq_synonym :dns/eqHypernym :dns/eqHyponym :dns/eqSimilar]}
   {:resource "ILI" :items "synsets" :key :ili :origins [:wn/ili]}
   {:resource "FrameNet" :items "COR.SEM senses"
    :label    "COR.SEM senses with a FrameNet frame"}])

(defn links-summary
  "The links from DanNet to other resources in the `current` and `links`
  stats maps, one map per row of resource-links. The links to OEWN and the
  ILI are also split into those that go back to a DanNet 2.2 link (:legacy)
  and the rest (:new)."
  [current links]
  (let [counts (into {} (:links current))]
    (vec (for [{:keys [resource items label key origins]} resource-links]
           (cond-> {:resource resource
                    :items    items
                    :links    (if label (get counts label) (get current key))}
             origins (merge (apply merge-with + {:legacy 0 :new 0}
                                   (map (:origins links) origins))))))))

(def companion-datasets
  "The companion datasets, each with its graph and the items to count in it:
  a label, the SPARQL to count and the variable to count distinct values of
  (nil counts every solution), as in link-counts."
  [{:dataset "COR" :uri prefix/cor-uri
    :items   [["lexical entries"
               (str "VALUES ?t { ontolex:Word ontolex:MultiwordExpression "
                    "ontolex:Affix } ?s a ?t")
               "?s"]
              ["forms" "?s a ontolex:Form" nil]]}
   {:dataset "COR.SEM" :uri prefix/cor-sem-uri
    :items   [["senses" "?s a ontolex:LexicalSense" nil]]}
   {:dataset "DDS" :uri prefix/dds-uri
    :items   [["sentiment annotations" "?s dns:sentiment ?o" nil]]}
   {:dataset "FrameNet" :uri prefix/framenet-uri
    :items   [["frames" "?s a pmofn:Frame" nil]
              ["frame elements"
               "?s a ?t . FILTER(STRENDS(STR(?t), \"FrameElement\"))" "?s"]]}
   {:dataset "OEWN extension" :uri prefix/oewn-extension-uri
    :items   [["English labels" "?s rdfs:label ?o" nil]]}])

(defn companion-stats
  "The triples and the counted items of each of the companion-datasets in the
  live db map `dannet`."
  [{:keys [dataset]}]
  (vec (for [{:keys [uri items] :as companion} companion-datasets
             :let [g (db/get-graph dataset uri)]]
         {:dataset (:dataset companion)
          :triples (count-in g "?s ?p ?o")
          :items   (vec (for [[label where var] items]
                          [label (count-in g where var)]))})))

(defn new-synset-stats
  "Figures for the synsets of the asserted dn: graph `g` that were not in
  DanNet 2.2, going by the synset `legacy-ids`: how many there are and how
  many of them the 2023 adjective supplement added (synset-s ids), the
  relations that involve them, their mean degree and their links to OEWN
  synsets and ILI concepts."
  [g legacy-ids]
  (let [new?     #(not (legacy-ids (synset-id %)))
        synsets  (filter new? (typed-synsets g))
        edges    (relation-edges g)
        involved (filter (fn [[s _ o]] (or (new? s) (new? o))) edges)
        ili?     (comp #{:wn/ili} second)
        links    (filter (comp new? first) (link-edges g))]
    {:synsets         (count synsets)
     :supplement-2023 (count (filter #(str/starts-with? (name %) "synset-s") synsets))
     :relations       (count involved)
     :similar         (count (filter (comp #{:wn/similar} second) involved))
     :mean-degree     (:mean-degree (degree-stats synsets edges))
     :oewn-links      (count (remove ili? links))
     :ili-links       (count (filter ili? links))}))

(defn tables
  "All tables of the paper, keyed by name, from the `legacy`, `current` and
  `links` stats maps."
  [legacy current links]
  {:size         (size-table legacy current)
   :relations    (relation-table legacy current)
   :connectivity (connectivity-table legacy current)
   :similar      (similar-table current)
   :links        (link-table current)
   :legacy-links (legacy-link-table links)
   :link-origins (link-origin-table links)
   :datasets     (dataset-table current)})

(defn- cell
  [x]
  (if (nil? x) "" (str x)))

(defn ->markdown
  "Render a `table` of :header and :rows as a Markdown table."
  [{:keys [header rows]}]
  (let [row (fn [cells] (str "| " (str/join " | " (map cell cells)) " |"))]
    (str/join "\n" (concat [(row header)
                            (str "|" (str/join "|" (repeat (count header) " --- ")) "|")]
                           (map row rows)))))

(defn- latex-escape
  [s]
  (str/replace s #"[%&_#]" #(str "\\" %)))

(defn ->latex
  "Render a `table` of :header and :rows as a booktabs tabular, the first
  column left-aligned and the rest right-aligned."
  [{:keys [header rows]}]
  (let [row (fn [cells] (str (str/join " & " (map (comp latex-escape cell) cells)) " \\\\"))]
    (str/join "\n" (concat [(str "\\begin{tabular}{l" (apply str (repeat (dec (count header)) "r")) "}")
                            "\\toprule"
                            (row header)
                            "\\midrule"]
                           (map row rows)
                           ["\\bottomrule"
                            "\\end{tabular}"]))))

(defn- render-all
  [heading render tables]
  (str/join "\n\n" (for [[k table] tables]
                     (str heading (name k) "\n" (render table)))))

(defn export-stats!
  "Write the statistics of the live db map `dannet` against the legacy CSV in
  `legacy` to `dir` as stats.edn (every number), stats.md and stats.tex (the
  tables); by default export/stats/<version>/ and the DanNet 2.2 release."
  ([dannet]
   (export-stats! dannet (str "export/stats/" release/to "/") legacy-dir))
  ([dannet dir legacy]
   (println "Computing statistics against" legacy)
   (let [new    (current-stats dannet)
         old    (legacy-stats legacy (inverse-relations (:graph dannet)))
         links  (link-stats legacy dannet)
         tables (tables old new links)
         in-dir (partial str dir)]
     (io/make-parents (in-dir "stats.edn"))
     (spit (in-dir "stats.edn")
           (pr-str {:legacy  old :current new :links links
                    :mapping (mapping-summary
                               (read-legacy-rows (io/file legacy "relations.csv")))
                    :cleanup (cleanup-summary
                               (db/get-graph (:dataset dannet) prefix/dn-uri))
                    :resource-links (links-summary new links)
                    :companions (companion-stats dannet)
                    :new-synsets (new-synset-stats
                                   (db/get-graph (:dataset dannet) prefix/dn-uri)
                                   (legacy-synset-ids legacy))}))
     (spit (in-dir "stats.md") (render-all "## " ->markdown tables))
     (spit (in-dir "stats.tex") (render-all "% " ->latex tables))
     (println "Statistics written to" dir)
     tables)))

(def relation-mapping-intro
  "The Markdown text above the table in doc/relation-mapping.md."
  "# DanNet 2.2 relations in the current DanNet

This file is generated by `dk.cst.dannet.db.stats/export-relation-mapping!`.
Do not edit it by hand.

DanNet 2.2 has 29 relation names. The table shows what each one became:

- **Draft (2021):** the first mapping code, from June 2021
  ([#2](https://github.com/kuhumcst/DanNet/issues/2)).
- **First release (2023):** the conversion for the first release of the new
  DanNet, 2023-05-11.
- **Now:** the current relation. The numbers after it come from the data.
  Each DanNet 2.2 row is compared with the relations that now link the same
  two synsets. A row is counted when its relation was replaced (\"now\"),
  reversed, negated or removed. DanNet 2.2 denied the negated relations
  (`owl:NegativePropertyAssertion`). The 2023 conversion asserted them, and
  the current data denies them again
  ([#216](https://github.com/kuhumcst/DanNet/issues/216)).

The rows column counts the rows in the DanNet 2.2 CSV release. The 2023
conversion read the DanNet 2.5.1 CSV export, which has the same relation
names and almost the same counts. DanNet 2.2 marks each `has_hyperonym` row
as taxonomic or nontaxonomic, so `has_hyperonym` has two rows in the table. The
`eq_*` relations link to Princeton WordNet, so the data check does not
include them.
")

(defn export-relation-mapping!
  "Write the conversion history of the DanNet 2.2 relations, checked against
  the live db map `dannet`, as Markdown to `f`; by default
  doc/relation-mapping.md, with the DanNet 2.2 release in `legacy`."
  ([dannet]
   (export-relation-mapping! dannet "doc/relation-mapping.md" legacy-dir))
  ([{:keys [dataset graph] :as dannet} f legacy]
   (let [rows     (read-legacy-rows (io/file legacy "relations.csv"))
         dn       (db/get-graph dataset prefix/dn-uri)
         outcomes (relation-outcomes (inverse-relations graph) rows
                                     (relation-edges dn)
                                     (set (negated-edges dn)))
         table    (mapping-table rows outcomes release/to)]
     (spit f (str relation-mapping-intro "\n" (->markdown table) "\n"))
     (println "Relation mapping written to" f)
     table)))

(comment
  (legacy-stats legacy-dir (inverse-relations (:graph @dk.cst.dannet.web.instance/db)))
  (current-stats @dk.cst.dannet.web.instance/db)
  (export-stats! @dk.cst.dannet.web.instance/db)
  (export-relation-mapping! @dk.cst.dannet.web.instance/db)

  ;; The data of the mapping table in the paper; needs no database.
  (mapping-summary (read-legacy-rows (io/file legacy-dir "relations.csv")))

  ;; The data of the cleanup and links tables in the paper.
  (cleanup-summary (db/get-graph (:dataset @dk.cst.dannet.web.instance/db)
                                 prefix/dn-uri))
  (let [dannet @dk.cst.dannet.web.instance/db]
    (links-summary (current-stats dannet) (link-stats legacy-dir dannet)))

  ;; The companion datasets and the synsets added after DanNet 2.2.
  (companion-stats @dk.cst.dannet.web.instance/db)
  (new-synset-stats (db/get-graph (:dataset @dk.cst.dannet.web.instance/db)
                                  prefix/dn-uri)
                    (legacy-synset-ids legacy-dir))
  #_.)
