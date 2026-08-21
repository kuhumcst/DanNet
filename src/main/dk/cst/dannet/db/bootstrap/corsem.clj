(ns dk.cst.dannet.db.bootstrap.corsem
  "Convert the COR.SEM source file into the triples of the cor-sem: graph.

  COR.SEM is DSL's manually curated sense inventory for the COR word registry:
  one row per sense, identified by a COR.SEM ID sharing the URI scheme of the
  other COR resources (GitHub issue #207). Columns carrying semantic payload
  map onto existing properties where one fits (ontolex, skos, marl, dc) and
  onto the dns:frame/dns:polysemyPattern/dns:corOntologicalType additions
  where none did; the purely COR/DDO-internal bookkeeping columns are skipped.

  The frames are materialized as resources in the frame: namespace, typed as
  lexical concepts per the PreMOn rendition of FrameNet, with owl:sameAs links
  to PreMOn for the frames known to it (FrameNet 1.7) and rdfs:seeAlso links
  to the frame reports of the live Berkeley database, which also holds the
  handful of frames added after 1.7.

  The link columns targeting dn: synsets (DanNet-link, overbegreb-DanNet) and
  cor: words (COR-basis-id, COR.EXT-id) are converted for every row; pruning
  the links whose targets do not resolve requires the graphs and so happens in
  bootstrap/add-cor-sem-graph!."
  (:require [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [dk.cst.dannet.db.bootstrap.cor :as cor]
            [dk.cst.dannet.db.bootstrap.metadata :as md]
            [dk.cst.dannet.hash :as h]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.release :as release]))

(def premon-fn17-frames
  "The FrameNet 1.7 frames rendered by PreMOn, in its lowercased spelling;
  fetched once from https://premon.fbk.eu/sparql. COR.SEM frames outside this
  set were added to the live Berkeley database after the 1.7 release."
  (-> (io/resource "etc/premon-fn17-frames.txt")
      (slurp)
      (str/split-lines)
      (set)))

(defn split-field
  "The individual values of a possibly multi-value field `s`; nil when empty.

  The documented separator is | but ; also occurs, both in the fields where
  the spec sanctions it (overbegreb-tekst) and in a handful of DanNet-link
  values, there followed by a space -- hence the trimming."
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
  "Convert a FrameNet frame `name` to the triples of its frame: resource."
  [name]
  (let [frame (frame-uri name)]
    (cond-> #{[frame :rdf/type :ontolex/LexicalConcept]
              [frame :rdfs/label (md/en name)]
              [frame :rdfs/seeAlso
               (prefix/uri->rdf-resource
                 (str "https://framenet.icsi.berkeley.edu/fnReports/data/frame/"
                      name ".xml"))]}
      (premon-fn17-frames (str/lower-case name))
      (conj [frame :owl/sameAs
             (prefix/uri->rdf-resource
               (str "http://premon.fbk.eu/resource/fn17-"
                    (str/lower-case name)))]))))

(def restriction-comments
  "The COR.SEM restriktion values mapped to the rdfs:comment stating the DDO
  usage restriction; the source only flags the kind of restriction, so the
  details require a DDO look-up."
  {"frekvens"  "i DDO markeret som sjælden eller gammeldags"
   "sprogbrug" "i DDO markeret med en sprogbrugsrestriktion, fx uformel eller nedsættende"})

(def centrality-comments
  "The COR.SEM centralitet codes mapped to the rdfs:comments stating where the
  sense counts as central; 0 (central in neither) is left unmarked."
  (let [dannet "centralt begreb i DanNet, koblet til Princeton WordNets Core WordNet"
        ddb    "nøgleord i Den Danske Begrebsordbog"]
    {"1" [ddb]
     "2" [dannet]
     "3" [ddb dannet]}))

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
  docstring. The skipped columns are kept in the argument vector -- prefixed
  with _ -- so the full source format remains documented here."
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

        ;; Word links, matching how COR words already link DanNet senses.
        (into (for [cor-id (concat (split-field cor-ids) (split-field ext-id))]
                [(keyword "cor" cor-id) :ontolex/sense sense]))

        ;; The synsets consisting of this sense: the spec's own reading of
        ;; DanNet-link, i.e. precisely ontolex:isLexicalizedSenseOf.
        (into (for [synset-id (split-field links)]
                [sense :ontolex/isLexicalizedSenseOf (synset-uri synset-id)]))

        ;; Curated hypernym anchors; a cross-scheme hierarchical mapping.
        (into (for [synset-id (split-field hypernym-links)]
                [sense :skos/broadMatch (synset-uri synset-id)]))

        (into (for [ontotype (split-field ontotypes)]
                [sense :dns/corOntologicalType ontotype]))

        ;; DDO topic domains, matching the dc:subject usage in the dn: graph.
        (into (for [topic (split-field topics)]
                [sense :dc/subject (md/da topic)]))

        (into (for [pattern (split-field polysemy)]
                [sense :dns/polysemyPattern pattern]))

        (into (for [frame (split-field frames)]
                [sense :dns/frame (frame-uri frame)]))

        (into (when-not (str/blank? sentiment)
                (sentiment-triples sense sentiment)))

        (into (for [restriction (split-field restrictions)]
                [sense :rdfs/comment (md/da (restriction-comments restriction))]))

        (into (for [comment (centrality-comments centrality)]
                [sense :rdfs/comment (md/da comment)])))))

(defn source-triples
  "Every triple of the cor-sem: graph, read from the source file: the sense
  triples of all rows plus the resources of the frames they reference."
  []
  (with-open [reader (io/reader (io/file cor/source-dir
                                         (str "cor.sem." release/cor-sem-version ".tsv"))
                                :encoding "UTF-8")]
    (let [rows   (rest (csv/read-csv reader :separator \tab))
          frames (into #{} (mapcat #(split-field (nth % 18))) rows)]
      (-> (into #{} (mapcat ->corsem-triples) rows)
          (into (mapcat ->frame-triples) frames)))))

(comment
  ;; A fully featured row: two hypernym anchors, frame, sentiment, domain.
  (->corsem-triples
    ["COR.SEM.82257.01" "COR.82257" "" "" "11023722" "køreskole" "sb." "fk."
     "1" "" "skole" "synset-46618" "billist|bilkørsel" "" "Institution"
     "trafik" "" "synset-19375" "Education_teaching" "-2" "sprogbrug" "3"
     "manuel" "1" "0"])

  (count (source-triples))
  (count premon-fn17-frames)
  #_.)
