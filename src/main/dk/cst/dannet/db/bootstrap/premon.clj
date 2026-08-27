(ns dk.cst.dannet.db.bootstrap.premon
  "Convert the PreMOn rendition of FrameNet 1.7 into the triples of the
  framenet graph: the complete inventory of 1,221 frames, their 11,428 frame
  elements and the semantic frame-to-frame relations.

  The source is the official premon-2018a-fn17-noinf dump (CC BY-SA 4.0),
  fetched by downloads/ensure-framenet-dataset!. Resources are renamed from
  PreMOn's lowercased IRIs into the frame: namespace using the Berkeley
  spellings carried by their rdfs:label: frame:Abandonment, and
  frame:Abandonment@Agent for frame elements, reusing PreMOn's own @
  separator. Each keeps an owl:sameAs link back to its PreMOn original.
  The COR.SEM frame names match the Berkeley spellings exactly, so the
  dns:frame links emitted by the corsem ns resolve against this graph.

  Reused vocabulary: pmo:semRole links a frame to its frame elements, the
  four pmofn: status classes type them, and the seven semantic subproperties
  of pmofn:frameRelation relate frames, mirrored between the frames' roles
  by their FER counterparts and the excludes/requires constraints. Core sets
  (pmofn:feCoreSet blank nodes with pmo:item members) group the core roles
  that fill the same slot, and pmofn:semType links frames and roles to the
  109 semantic types, named with PreMOn's _semType suffix since 14 share
  their name with a frame. FrameNet's editorial relations (reFrameMapping,
  seeAlso, metaphor) and the English lexical units carried by the dump are
  deliberately left out."
  (:require [clojure.string :as str]
            [dk.cst.dannet.db.bootstrap.downloads :as downloads]
            [dk.cst.dannet.db.bootstrap.metadata :as md]
            [dk.cst.dannet.hash :as h]
            [dk.cst.dannet.prefix :as prefix])
  (:import [org.apache.jena.rdf.model Statement]
           [org.apache.jena.riot RDFDataMgr]))

(def fn17-resource
  "The named graph of the PreMOn dump holding the FrameNet 1.7 data."
  "http://premon.fbk.eu/resource/fn17")

(def fe-status
  "The pmofn: frame element status classes by full IRI; the -noinf dump
  asserts exactly one of these per frame element (pmofn:FrameElement itself
  is left to inference)."
  (into {} (map (juxt prefix/kw->uri identity))
        [:pmofn/CoreFrameElement
         :pmofn/PeripheralFrameElement
         :pmofn/ExtraThematicFrameElement
         :pmofn/CoreUnexpressedFrameElement]))

(def frame-relations
  "The seven semantic frame-to-frame relations by full IRI."
  (into {} (map (juxt prefix/kw->uri identity))
        [:pmofn/inheritsFrom
         :pmofn/uses
         :pmofn/subframeOf
         :pmofn/perspectiveOn
         :pmofn/precedes
         :pmofn/isCausativeOf
         :pmofn/isInchoativeOf]))

(def fe-relations
  "The frame element counterparts of frame-relations (which role maps to
  which along each frame-to-frame relation) plus the intra-frame
  excludes/requires constraints between roles; by full IRI."
  (into {} (map (juxt prefix/kw->uri identity))
        [:pmofn/inheritsFromFER
         :pmofn/usesFER
         :pmofn/subframeOfFER
         :pmofn/perspectiveOnFER
         :pmofn/precedesFER
         :pmofn/isCausativeOfFER
         :pmofn/isInchoativeOfFER
         :pmofn/excludesFrameElement
         :pmofn/requiresFrameElement]))

(h/defn premon-index
  "Parse the PreMOn FrameNet dump into maps keyed by PreMOn IRI: the sets
  :frames, :semtypes, :relations, :fe-relations, :core-sets, :semtype-links
  and :subtypes, the maps :fe->status and :items, and the string-valued
  lookup maps :label, :definition and :abbreviation."
  []
  (let [model     (-> (RDFDataMgr/loadDataset downloads/premon-fn17-path)
                      (.getNamedModel fn17-resource))
        rdf-type  (prefix/kw->uri :rdf/type)
        label     (prefix/kw->uri :rdfs/label)
        defn'     (prefix/kw->uri :skos/definition)
        abbrev    (prefix/kw->uri :pmo/abbreviation)
        item      (prefix/kw->uri :pmo/item)
        core-set  (prefix/kw->uri :pmofn/feCoreSet)
        sem-type  (prefix/kw->uri :pmofn/semType)
        subtype   (prefix/kw->uri :pmofn/subTypeOf)
        sem-class (prefix/kw->uri :pmofn/Frame)
        st-class  (prefix/kw->uri :pmofn/SemType)]
    (reduce (fn [acc ^Statement stmt]
              (let [s (.getSubject stmt)
                    p (.getURI (.getPredicate stmt))
                    o (.getObject stmt)]
                (if-not (.isURIResource s)
                  acc
                  (let [s'  (.getURI s)
                        o'  (when (.isURIResource o)
                              (.getURI (.asResource o)))
                        str' #(.getString (.asLiteral o))]
                    (cond
                      (= p rdf-type)
                      (cond
                        (= o' sem-class)  (update acc :frames conj s')
                        (= o' st-class)   (update acc :semtypes conj s')
                        (fe-status o')    (assoc-in acc [:fe->status s']
                                                    (fe-status o'))
                        :else acc)

                      (= p label)  (assoc-in acc [:label s'] (str'))
                      (= p defn')  (assoc-in acc [:definition s'] (str'))
                      (= p abbrev) (assoc-in acc [:abbreviation s'] (str'))

                      ;; pmo:item also links e.g. annotation sets, so the
                      ;; core set members are filtered out at emission.
                      (= p item)     (update-in acc [:items s'] (fnil conj #{}) o')
                      (= p core-set) (update acc :core-sets conj [s' o'])
                      (= p sem-type) (update acc :semtype-links conj [s' o'])
                      (= p subtype)  (update acc :subtypes conj [s' o'])

                      (frame-relations p)
                      (update acc :relations conj [s' (frame-relations p) o'])

                      (fe-relations p)
                      (update acc :fe-relations conj [s' (fe-relations p) o'])

                      :else acc)))))
            {:frames #{} :semtypes #{} :fe->status {}
             :relations #{} :fe-relations #{}
             :core-sets #{} :items {} :semtype-links #{} :subtypes #{}
             :label {} :definition {} :abbreviation {}}
            (iterator-seq (.listStatements model)))))

(def index
  "Delayed parse of the PreMOn dump, shared by the triple emission and the
  fn17-frame? lookups from the corsem ns."
  (delay (premon-index)))

(def fn17-frame-names
  "The Berkeley spellings of the frames in the PreMOn-derived inventory."
  (delay (let [{:keys [frames label]} @index]
           (into #{} (map label) frames))))

(defn fn17-frame?
  "Return true if the FrameNet frame `name` (Berkeley spelling) is part of
  the PreMOn-derived frame inventory, i.e. existed in FrameNet 1.7."
  [name]
  (contains? @fn17-frame-names name))

(h/defn source-triples
  "Every triple of the framenet graph, read from the PreMOn dump."
  []
  (let [{:keys [frames semtypes fe->status relations fe-relations
                core-sets items semtype-links subtypes
                label definition abbreviation]} @index
        frame-kw (comp (partial keyword "frame") label)
        fe-kw    (fn [iri]
                   (let [f-iri (subs iri 0 (str/index-of iri "@"))]
                     (keyword "frame" (str (label f-iri) "@" (label iri)))))
        ;; The 14 semType labels shared with a frame make the disambiguating
        ;; PreMOn suffix part of the IRI; the label stays the bare name.
        st-kw    (fn [iri] (keyword "frame" (str (label iri) "_semType")))
        ;; semType subjects outside the materialized frames and frame
        ;; elements are the English lexical units, which are left out.
        node-kw  (fn [iri]
                   (cond
                     (fe->status iri) (fe-kw iri)
                     (frames iri)     (frame-kw iri)))
        ;; Blank nodes: the sets are display plumbing for grouping a frame's
        ;; core roles, not resources anyone should link or dereference.
        set-node (fn [iri]
                   (symbol (str "_" (str/replace (label (subs iri 0 (str/index-of iri "_coreSet")))
                                                 #"[^A-Za-z0-9]" "-")
                                (subs iri (str/index-of iri "_coreSet")))))]
    (-> #{}
        (into (mapcat (fn [iri]
                        (let [f    (frame-kw iri)
                              name (label iri)]
                          (cond-> #{[f :rdf/type :ontolex/LexicalConcept]
                                    [f :rdf/type :pmofn/Frame]
                                    [f :rdfs/label (md/en name)]
                                    [f :rdfs/seeAlso
                                     (prefix/uri->rdf-resource
                                       (str "https://framenet.icsi.berkeley.edu/fnReports/data/frame/"
                                            name ".xml"))]
                                    [f :owl/sameAs (prefix/uri->rdf-resource iri)]}
                            (definition iri)
                            (conj [f :skos/definition (md/en (definition iri))])))))
              frames)
        (into (mapcat (fn [[iri status]]
                        (let [f  (frame-kw (subs iri 0 (str/index-of iri "@")))
                              fe (fe-kw iri)]
                          (cond-> #{[f :pmo/semRole fe]
                                    [fe :rdf/type status]
                                    [fe :rdfs/label (md/en (label iri))]
                                    [fe :owl/sameAs (prefix/uri->rdf-resource iri)]}
                            (definition iri)
                            (conj [fe :skos/definition (md/en (definition iri))])

                            (abbreviation iri)
                            (conj [fe :pmo/abbreviation (abbreviation iri)])))))
              fe->status)
        (into (mapcat (fn [iri]
                        (cond-> #{[(st-kw iri) :rdf/type :pmofn/SemType]
                                  [(st-kw iri) :rdfs/label (md/en (label iri))]
                                  [(st-kw iri) :owl/sameAs (prefix/uri->rdf-resource iri)]}
                          (definition iri)
                          (conj [(st-kw iri) :skos/definition
                                 (md/en (definition iri))]))))
              semtypes)
        (into (map (fn [[s p o]]
                     [(frame-kw s) p (frame-kw o)]))
              relations)
        (into (map (fn [[s p o]]
                     [(fe-kw s) p (fe-kw o)]))
              fe-relations)
        (into (mapcat (fn [[f-iri set-iri]]
                        (let [bn (set-node set-iri)]
                          (into #{[(frame-kw f-iri) :pmofn/feCoreSet bn]
                                  [bn :rdf/type :pmofn/FECoreSet]}
                                (map (fn [fe] [bn :pmo/item (fe-kw fe)]))
                                (items set-iri)))))
              core-sets)
        (into (keep (fn [[s st]]
                      (when-let [subject (node-kw s)]
                        [subject :pmofn/semType (st-kw st)])))
              semtype-links)
        (into (map (fn [[a b]]
                     [(st-kw a) :pmofn/subTypeOf (st-kw b)]))
              subtypes))))

(comment
  (count (:frames @index))                                  ; 1221
  (count (:fe->status @index))                              ; 11428
  (count (:relations @index))                               ; 1763
  (count (:fe-relations @index))                            ; 11027
  (count (:core-sets @index))                               ; 381
  (count (:semtypes @index))                                ; 109

  (count (source-triples))
  ;; The Abandonment frame and one of its frame elements.
  (filter (comp #{:frame/Abandonment} first) (source-triples))
  (filter (comp #{(keyword "frame" "Abandonment@Agent")} first) (source-triples))
  ;; The two core sets of the Statement frame.
  (filter (comp #{:frame/Statement} first) (source-triples))
  #_.)
