(ns dk.cst.dannet.db.bootstrap.corsem
  "Convert the COR.SEM source file into the triples of the cor-sem: graph.

  COR.SEM is DSL's manually curated sense inventory for the COR word registry:
  one row per sense, identified by a COR.SEM ID sharing the URI scheme of the
  other COR resources (GitHub issue #207). Columns carrying semantic payload
  map onto existing properties where one fits (ontolex, skos, marl, dc) and
  onto the dns:frame/dns:polysemyPattern/dns:simpleOntologicalType additions
  where none did; the purely COR/DDO-internal bookkeeping columns are skipped.

  The frames referenced by dns:frame live in their own dataset, the framenet
  graph built by the premon ns; only the handful of frames added to the live
  Berkeley database after FrameNet 1.7 (and hence outside that graph) get a
  minimal frame: resource here.

  The link columns targeting dn: synsets (DanNet-link, overbegreb-DanNet) and
  cor: words (COR-basis-id, COR.EXT-id) are converted for every row; pruning
  the links whose targets do not resolve and deriving the sense-level SKOS
  matches both require the graphs and so happen in
  bootstrap/add-cor-sem-graph!."
  (:require [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [dk.cst.dannet.db.bootstrap.cor :as cor]
            [dk.cst.dannet.db.bootstrap.metadata :as md]
            [dk.cst.dannet.db.bootstrap.premon :as premon]
            [dk.cst.dannet.hash :as h]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.release :as release]))

(defn split-field
  "The individual values of a possibly multi-value field `s`; nil when empty.

  The documented separator is | but ; also occurs, both in the fields where
  the spec sanctions it (overbegreb-tekst) and in a handful of DanNet-link
  values, there followed by a space; hence the trimming."
  [s]
  (when-not (str/blank? s)
    (->> (str/split s #"[|;]")
         (map str/trim)
         (remove str/blank?))))

(defn synset-uri
  "The dn: resource for `synset-id`, e.g. \"synset-46618\"."
  [synset-id]
  (keyword "dn" synset-id))

(defn frame-uri
  "The frame: resource for the FrameNet frame `name`."
  [name]
  (keyword "frame" name))

(h/defn ->frame-triples
  "Convert a FrameNet frame `name` to the triples of its frame: resource;
  nil for the frames already described by the framenet graph.

  Frames added to the live Berkeley database after FrameNet 1.7 fall outside
  the PreMOn-derived inventory and so get a minimal resource here, carrying
  only the (unreliable) rdfs:seeAlso link to their frame report."
  [name]
  (when-not (premon/fn17-frame? name)
    (let [frame (frame-uri name)]
      #{[frame :rdf/type :ontolex/LexicalConcept]
        [frame :rdfs/label (md/en name)]
        [frame :rdfs/seeAlso
         (prefix/uri->rdf-resource
           (str "https://framenet.icsi.berkeley.edu/fnReports/data/frame/"
                name ".xml"))]})))

(h/defn ->ontotype
  "The dnt: resource for the ontological type comprising the concept `atoms`,
  paired with its triples: a dns:OntologicalType, i.e. an rdf:Bag of one or
  more dnc: concepts, named after its sorted atoms and thereby shareable.

  The IRI separates atoms with a dash, since a + would collide with the
  +-as-space rule wherever the IRI passes through query-string decoding. The
  label keeps the conventional + of the EuroWordNet composite types, spaced
  for readability."
  [atoms]
  (let [atoms'   (vec (sort atoms))
        names    (map name atoms')
        ontotype (keyword "dnt" (str/join "-" names))]
    [ontotype
     ;; rdf:Bag is not asserted: it follows from the schema's subclass axiom,
     ;; like the other dns: class hierarchies on their instances.
     (into #{[ontotype :rdf/type :dns/OntologicalType]
             [ontotype :rdfs/label (md/en (str/join " + " names))]}
           ;; Container membership properties start at rdf:_1 by convention.
           (map-indexed (fn [i atom]
                          [ontotype (keyword "rdf" (str "_" (inc i))) atom]))
           atoms')]))

(h/defn ontotype-atoms
  "The dnc: atom concepts of the simplified ontological type `s`, e.g.
  \"Human+Object\". The two order-entity atoms map onto their EuroWordNet
  spellings; every other atom shares its name with a dnc: concept."
  [s]
  (->> (str/split s #"\+")
       (mapv #(case %
                "1stOrderEntity" :dnc/FirstOrderEntity
                "2ndOrderEntity" :dnc/SecondOrderEntity
                (keyword "dnc" %)))))

;; TODO: find out what the "sprogbrug" restriction actually pertains to (which
;;       register, e.g. uformel/nedsættende) so the note can name the register
;;       like the DDO-derived notes do; the DDO entry or the eqSense-linked
;;       dn: sense's own usage note may hold the answer.
(def polysemy-pattern-info
  "The COR.SEM systematisk-polysemi patterns mapped to their two readings (a
  dnc: concept where one plainly matches the reading, otherwise the reading's
  own name) and, for the patterns appearing in the published typology, the
  group in its partition (see dns:patternGroup); the patterns without a group
  were added to the inventory after publication.

  :derived marks the (1-based) readings whose concept corresponds by
  distributional evidence rather than by name: the concept dominates and
  discriminates the ontological types of the pattern's senses in COR.SEM 1.0.
  ->polysemy-pattern states this in a comment on the pattern resource."
  {"ANIMAL / FOOD"                      {:group 1 :readings [:dnc/Animal :dnc/Comestible]}
   "ANIMAL BODY PART / FOOD"            {:group 1 :readings ["ANIMAL BODY PART" :dnc/Comestible]}
   "PLANT / FOOD"                       {:group 1 :readings [:dnc/Plant :dnc/Comestible]}
   "PLANT / MATERIAL"                   {:group 1 :readings [:dnc/Plant :dnc/Substance]
                                         :derived #{2}}
   "ARTIFACT / MATERIAL"                {:group 1 :readings [:dnc/Artifact :dnc/Substance]
                                         :derived #{2}}
   "BODY PART / PART OF GARMENT"        {:group 1 :readings [:dnc/BodyPart "PART OF GARMENT"]}
   "SHOP / PERSON"                      {:group 1 :readings ["SHOP" :dnc/Human]}
   "PROCESS / RESULT (CONCRETE)"        {:group 2 :readings [:dnc/Act "RESULT (CONCRETE)"]
                                         :derived #{1}}
   "ARTIFACT / ACTIVITY"                {:group 2 :readings [:dnc/Artifact :dnc/Act]
                                         :derived #{2}}
   "ARTIFACT / PROPERTY (COLOUR)"       {:group 2 :readings [:dnc/Artifact "PROPERTY (COLOUR)"]}
   "ACT / EVENT"                        {:group 2 :readings [:dnc/Act :dnc/Event]}
   "ARTIFACT / CONTENT"                 {:group 3 :readings [:dnc/Artifact "CONTENT"]}
   "ARTIFACT / INSTITUTION"             {:group 3 :readings [:dnc/Artifact :dnc/Institution]}
   "ARTIFACT / FORM"                    {:group 3 :readings [:dnc/Artifact :dnc/Form]}
   "OBJECT / SYMBOL"                    {:group 3 :readings [:dnc/Object "SYMBOL"]}
   "COUNTABLE / UNCOUNTABLE"            {:group 3 :readings ["COUNTABLE" "UNCOUNTABLE"]}
   "CONTAINER / CONTENTS"               {:group 3 :readings [:dnc/Container "CONTENTS"]}
   "LOCATION / INSTITUTION"             {:group 3 :readings [:dnc/Place :dnc/Institution]}
   "PROCESS / RESULT (ABSTRACT)"        {:group 4 :readings [:dnc/Act "RESULT (ABSTRACT)"]
                                         :derived #{1}}
   "ACT / THOUGHT"                      {:group 4 :readings [:dnc/Act "THOUGHT"]}
   "ACTIVITY / INSTITUTION"             {:group 4 :readings [:dnc/Act :dnc/Institution]
                                         :derived #{1}}
   "ACT / COMMUNICATE"                  {:group 4 :readings [:dnc/Act :dnc/Communication]}
   "EVENT / POINT IN TIME"              {:group 4 :readings [:dnc/Event :dnc/Time]}
   "ACT / SOUND"                        {:group 4 :readings [:dnc/Act "SOUND"]}
   "DANCE STYLE / MUSIC STYLE"          {:group 5 :readings ["DANCE STYLE" "MUSIC STYLE"]}
   "AREA OF KNOWLEDGE / SCHOOL SUBJECT" {:group 5 :readings ["AREA OF KNOWLEDGE" "SCHOOL SUBJECT"]}
   "PROPERTY (PERSON) / PROPERTY (ACT)" {:readings ["PROPERTY (PERSON)" "PROPERTY (ACT)"]}
   "ACTIVITY / DOMAIN"                  {:readings [:dnc/Act :dnc/Domain]
                                         :derived #{1}}
   "ACT / PARTICULAR ACT"               {:readings [:dnc/Act "PARTICULAR ACT"]}
   "ARTIFACT / OPENING"                 {:readings [:dnc/Artifact "OPENING"]}})

(defn- pattern-part
  "The IRI part for one reading of a pattern name `s`, e.g.
  \"RESULT (ABSTRACT)\" -> \"ResultAbstract\"."
  [s]
  (->> (re-seq #"[A-Za-z]+" s)
       (map str/capitalize)
       (str/join)))

(h/defn ->polysemy-pattern
  "The dnp: resource for the systematic polysemy pattern named `s`, paired
  with its triples: a dns:PolysemyPattern, i.e. an rdf:Seq of the pattern's
  two readings in order, drawn from polysemy-pattern-info. The IRI joins the
  halves of the name with a dash, like the dnt: ontological types."
  [s]
  (let [halves (mapv str/trim (str/split s #" / "))
        {:keys [group readings derived]
         :or   {readings halves}} (polysemy-pattern-info s)
        pattern  (keyword "dnp" (str/join "-" (map pattern-part halves)))
        ->member (fn [r] (if (keyword? r) r (md/en r)))]
    [pattern
     (cond-> #{[pattern :rdf/type :dns/PolysemyPattern]
               [pattern :rdfs/label (md/en s)]
               [pattern :rdf/_1 (->member (first readings))]
               [pattern :rdf/_2 (->member (second readings))]}
       group (conj [pattern :dns/patternGroup group])
       derived
       (into (mapcat (fn [i]
                       (let [h (nth halves (dec i))
                             c (prefix/kw->qname (nth readings (dec i)))]
                         [[pattern :rdfs/comment
                           (md/en "The " h " reading is linked to " c
                                  " on distributional evidence rather than by"
                                  " a shared name: the concept dominates and"
                                  " discriminates the ontological types of the"
                                  " pattern's senses in COR.SEM 1.0.")]
                          [pattern :rdfs/comment
                           (md/da h "-læsningen er koblet til " c
                                  " på baggrund af distributionel evidens og"
                                  " ikke ud fra et fælles navn: konceptet"
                                  " dominerer og adskiller de ontologiske typer"
                                  " for mønstrets betydninger i COR.SEM 1.0.")]])))
             derived))]))

(def restriction-notes
  "The COR.SEM restriktion values mapped to the lexinfo:usageNote stating the
  DDO usage restriction, in the abbreviated style of the DDO-derived notes on
  dn: senses. The source only flags the kind of restriction: \"frekvens\"
  cannot distinguish rare from archaic (hence \"sj. el. gl.\") and
  \"sprogbrug\" does not say which register, so the details require a DDO
  look-up."
  {"frekvens"  "sj. el. gl."
   "sprogbrug" "sprogbrug"})

(defn- sentiment-triples
  "The marl Opinion triples for the polarity `value` of `sense`; the blank
  node is named after the sense to stay unique within the graph."
  [sense value]
  (let [opinion (symbol (str "_opinion-" (str/replace (name sense) "." "-")))
        n       (parse-long value)]
    #{[sense :dns/sentiment opinion]
      [opinion :marl/hasPolarity (if (neg? n) :marl/Negative :marl/Positive)]
      [opinion :marl/polarityValue n]}))

(h/defn ->corsem-triples
  "Convert a `row` of the COR.SEM file to triples.

  Every link is emitted whether or not its target resolves; see the ns
  docstring. The frame, ontological type and polysemy pattern resources a row
  references are emitted along with it; rows share these, so building the
  graph set deduplicates them. The skipped columns are kept in the argument
  vector (prefixed with _) so the full source format remains documented
  here."
  [[id cor-ids ext-id _ddo-diff entry-id lemma _pos _gender _nr _pos-shift
    _hypernym-text hypernym-links _related _synonyms ontotypes topics
    polysemy links frames sentiment restrictions centrality _curated
    _ddo-senses _ddo-idioms :as row]]
  (let [[_ _ lemma-id sense-nr] (re-matches cor/cor-id id)
        sense   (cor/cor-uri "SEM" lemma-id sense-nr)
        ;; The queries of the DDO source URLs in the dn: graph are IRIs that
        ;; keep the Danish letters raw, escaping only spaces and commas.
        ddo-url (str "https://gammel.ordnet.dk/ddo/ordbog?entry_id=" entry-id
                     "&query=" (str/replace lemma #"[ ,]" {" " "%20" "," "%2C"}))]
    ;; The label follows the dn: sense convention of lemma + subscripted
    ;; ordinal; the two-digit ordinal is COR.SEM's own sense number, taken
    ;; verbatim from the ID, and so deliberately not DDO's §-numbering,
    ;; which COR.SEM renumbers (and sometimes merges).
    (-> #{[sense :rdf/type :ontolex/LexicalSense]
          [sense :rdfs/label (md/da lemma "_" sense-nr)]
          [sense :dns/source (prefix/uri->rdf-resource ddo-url)]}

        ;; Word links; plain ontolex:sense, as COR.SEM is COR's own sense
        ;; inventory (the links into DanNet's are dns:linkedSense).
        (into (for [cor-id (concat (split-field cor-ids) (split-field ext-id))]
                [(keyword "cor" cor-id) :ontolex/sense sense]))

        ;; The synsets consisting of this sense: the spec's own reading of
        ;; DanNet-link, asserted as the dns:linkedSynset subproperty of
        ;; ontolex:isLexicalizedSenseOf to keep the two inventories apart.
        (into (for [synset-id (split-field links)]
                [sense :dns/linkedSynset (synset-uri synset-id)]))

        ;; Curated hypernym anchors; a cross-scheme hierarchical mapping.
        (into (for [synset-id (split-field hypernym-links)]
                [sense :dns/hypernymAnchor (synset-uri synset-id)]))

        (into (mapcat (fn [ontotype]
                        (let [[kw triples] (->ontotype (ontotype-atoms ontotype))]
                          (conj triples [sense :dns/simpleOntologicalType kw]))))
              (split-field ontotypes))

        ;; DDO topic domains, matching the dc:subject usage in the dn: graph.
        (into (for [topic (split-field topics)]
                [sense :dc/subject (md/da topic)]))

        (into (mapcat (fn [pattern]
                        (let [[kw triples] (->polysemy-pattern pattern)]
                          (conj triples [sense :dns/polysemyPattern kw]))))
              (split-field polysemy))

        (into (mapcat (fn [frame]
                        (cons [sense :dns/frame (frame-uri frame)]
                              (->frame-triples frame))))
              (split-field frames))

        (into (when-not (str/blank? sentiment)
                (sentiment-triples sense sentiment)))

        (into (for [restriction (split-field restrictions)]
                [sense :lexinfo/usageNote (md/da (restriction-notes restriction))]))

        ;; 0 (central in neither DanNet nor DDB) is left unmarked.
        (into (when-let [n (parse-long centrality)]
                (when (pos? n)
                  [[sense :dns/centrality n]]))))))

(defn source-triples
  "Every triple of the cor-sem: graph, read from the source file: the sense
  triples of all rows plus the resources of the frames, ontological types
  and polysemy patterns they reference."
  []
  (with-open [reader (io/reader (io/file cor/source-dir
                                         (str "cor.sem." release/cor-sem-version ".tsv"))
                                :encoding "UTF-8")]
    (into #{}
          (mapcat ->corsem-triples)
          (rest (csv/read-csv reader :separator \tab)))))

(comment
  ;; A fully featured row: two hypernym anchors, frame, sentiment, domain.
  (->corsem-triples
    ["COR.SEM.71368.01" "COR.71368" "" "" "11029335" "køreskole" "sb." "fk."
     "1" "" "skole" "synset-46618" "billist|bilkørsel" "" "Institution"
     "trafik" "" "synset-19375" "Education_teaching" "-2" "sprogbrug" "3"
     "manuel" "1" "0"])

  (count (source-triples))
  #_.)
