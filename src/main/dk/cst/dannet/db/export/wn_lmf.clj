(ns dk.cst.dannet.db.export.wn-lmf
  "WordNet LMF export functionality. This format is limited to what the GWA
  decides should be in a WordNet, so it doesn't contain all of DanNet.

  See also: the `wn_lmf_query.py` Python example code which parses and queries
  the resulting XML file."
  (:require [clojure.data.xml :as xml]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [dk.cst.dannet.db :as db]
            [dk.cst.dannet.db.bootstrap :as bootstrap]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.query :as q]
            [dk.cst.dannet.query.operation :as op]
            [dk.cst.dannet.shared :as shared])
  (:import [java.util.zip GZIPOutputStream]))

(def supported-wn-relations
  "Supported relations (from https://globalwordnet.github.io/schemas/)."
  [:wn/hypernym
   :wn/hyponym
   :wn/instance_hyponym
   :wn/instance_hypernym
   :wn/mero_member
   :wn/holo_member
   :wn/mero_part
   :wn/holo_part
   :wn/mero_substance
   :wn/holo_substance
   :wn/entails
   :wn/causes
   :wn/similar
   :wn/also
   :wn/attribute
   :wn/domain_topic
   :wn/has_domain_topic
   :wn/domain_region
   :wn/has_domain_region
   :wn/exemplifies
   :wn/is_exemplified_by
   :wn/agent
   :wn/antonym
   :wn/be_in_state
   :wn/classified_by
   :wn/classifies
   :wn/co_agent_instrument
   :wn/co_agent_patient
   :wn/co_agent_result
   :wn/co_instrument_agent
   :wn/co_instrument_patient
   :wn/co_instrument_result
   :wn/co_patient_agent
   :wn/co_patient_instrument
   :wn/co_result_agent
   :wn/co_result_instrument
   :wn/co_role
   :wn/direction
   :wn/eq_synonym
   :wn/holo_location
   :wn/holo_portion
   :wn/holonym
   :wn/in_manner
   :wn/instrument
   :wn/involved_agent
   :wn/involved_direction
   :wn/involved_instrument
   :wn/involved_location
   :wn/involved_patient
   :wn/involved_result
   :wn/involved_source_direction
   :wn/involved_target_direction
   :wn/involved
   :wn/is_caused_by
   :wn/is_entailed_by
   :wn/is_subevent_of
   :wn/location
   :wn/manner_of
   :wn/mero_location
   :wn/mero_portion
   :wn/meronym
   :wn/other
   :wn/patient
   :wn/restricted_by
   :wn/restricts
   :wn/result
   :wn/role
   :wn/source_direction
   :wn/state_of
   :wn/subevent
   :wn/target_direction])

;; See #146 - caught by WN-LMF validation
;; W203: redundant lexical entry with the same lemma and synset
(def excluded-synsets
  #{:dn/synset-14978
    :dn/synset-14909
    :dn/synset-24050
    :dn/synset-17868
    :dn/synset-15034
    :dn/synset-14930
    :dn/synset-14937
    :dn/synset-14933
    :dn/synset-67904
    :dn/synset-15022
    :dn/synset-57516
    :dn/synset-14877
    :dn/synset-14936
    :dn/synset-12476
    :dn/synset-26586
    :dn/synset-14895
    :dn/synset-15021
    :dn/synset-14890
    :dn/synset-63551
    :dn/synset-14927
    :dn/synset-48300
    :dn/synset-14945
    :dn/synset-14674
    :dn/synset-14919
    :dn/synset-9652
    :dn/synset-34562
    :dn/synset-37443})

;; #146 - some 500 cases left out, should really be fixed manually (eventually)
(def excluded-hypernymy
  (delay
    (let [ms (q/run (db/get-graph (:dataset @dk.cst.dannet.web.resources/db)
                                  prefix/dn-uri)
                    op/cross-pos-hypernymy)]
      (->> ms
           (map (fn [{:syms [?synset ?hypernym]}]
                  {?synset (shared/setify ?hypernym)}))
           (apply merge-with set/union)))))

(defn map-invert-multi
  "Like 'map-invert', but supports flipping maps where the value is a set."
  [m]
  (reduce (fn [m' [k vs]]
            (->> (for [v vs]
                   [v #{k}])
                 (into {})
                 (merge-with set/union m')))
          {}
          m))

(def excluded-hyponymy
  (delay
    (map-invert-multi @excluded-hypernymy)))

(defn ->wn-relations-query
  [rel]
  (op/sparql
    "SELECT ?subject ?object
     WHERE {
       FILTER(STRSTARTS(str(?subject), str(dn:))) .
       ?subject " (prefix/kw->qname rel) " ?object .
     }"))

(def lexical-entry-query
  (op/sparql
    "SELECT ?lexicalEntry ?synset ?sense ?pos ?writtenRep
     WHERE {
       ?lexicalEntry ontolex:evokes ?synset ;
                     ontolex:sense ?sense ;
                     wn:partOfSpeech ?pos ;
                     ontolex:canonicalForm ?form .

       # Only keep results where parts are interrelated (sense, entry, synset)
       ?synset ontolex:lexicalizedSense ?sense .

       ?form ontolex:writtenRep ?writtenRep .
     }"))

(def sense-example-query
  (op/sparql
    "SELECT ?synset ?example
     WHERE {
       ?sense lexinfo:senseExample ?example .
       ?synset ontolex:lexicalizedSense ?sense .
     }"))

(def ili-query
  (op/sparql
    "SELECT ?synset ?ili
     WHERE {
       ?synset wn:ili ?ili .
     }"))

(def definition-query
  (op/sparql
    "SELECT ?synset ?definition
     WHERE {
       ?synset skos:definition ?definition .
     }"))

(def supersense-query
  (op/sparql
    "SELECT ?synset ?supersense
     WHERE {
       ?synset dns:supersense ?supersense .
     }"))

(def pos-str
  {:wn/adjective "a"
   :wn/noun      "n"
   :wn/verb      "v"})

(defn lexical-entry
  [[id ms]]
  (into [:LexicalEntry {:id (name id)}
         [:Lemma {:writtenForm  (-> ms first (get '?writtenRep) str)
                  :partOfSpeech (-> ms first (get '?pos) pos-str)}]]
        (mapcat (fn [[synset ms]]
                  (for [{:syms [?sense]} ms]
                    [:Sense {:id     (name ?sense)
                             :synset (name synset)}]))
                (->> (group-by '?synset ms)
                     (sort-by first)))))

(defn synset-relations
  [ms]
  (for [{:syms [?rel ?object]} ms]
    [:SynsetRelation {:relType (name ?rel)
                      :target  (name ?object)}]))

(defn synset
  [synset-props [id ms]]
  (let [{:keys [ili pos definition lexfile examples members]} (get synset-props id)]
    (into [:Synset (cond-> {:id           (name id)
                            :members      (str/join " " members)
                            :lexfile      lexfile
                            :ili          (or ili "")       ; attr required by https://github.com/goodmami/wn
                            :partOfSpeech (or pos "")})     ; attr required by https://github.com/goodmami/wn
           (when definition
             [:Definition definition])]
          (concat
            (for [example examples]
              [:Example (str example)])
            (synset-relations ms)))))

(def lexicon
  [:Lexicon
   {:id       "dn"
    :label    "DanNet"
    :language "da"
    :email    "simongray@hum.ku.dk"
    :license  "https://creativecommons.org/licenses/by-sa/4.0/" ; TODO: change licence? See relevant issue
    :version  bootstrap/new-release
    :citation "Pedersen, Bolette S. Sanni Nimb, Jørg Asmussen, Nicolai H. Sørensen, Lars Trap-Jensen og Henrik Lorentzen (2009). DanNet – the challenge of compiling a WordNet for Danish by reusing a monolingual dictionary (pdf). Lang Resources & Evaluation 43:269–299."
    :url      "https://wordnet.dk/dannet"}])

(defn lexical-resource
  [[entry-grouping relation-grouping synset-props]]
  [:LexicalResource {:xmlns/dc "https://globalwordnet.github.io/schemas/dc/"}
   (into lexicon
         (concat
           (map lexical-entry entry-grouping)
           (map #(synset synset-props %) relation-grouping)))])

;; TODO: use later standard, e.g. 1.3? Does goodmami/wn support that?
(def doctype
  "<!DOCTYPE LexicalResource SYSTEM \"http://globalwordnet.github.io/schemas/WN-LMF-1.1.dtd\">")

(defn add-doctype
  [xml]
  (let [[before after] (str/split xml #"\r?\n" 2)]
    (str before "\n" doctype "\n" after)))

(defmacro label-time
  "A version of the built-in (time expr) macro that allows for a label."
  {:added "1.0"}
  [label expr]
  `(let [start# (. System (nanoTime))
         nada#  (print ~label)
         ret#   ~expr]
     (println (str " ... " (/ (double (- (. System (nanoTime)) start#)) 1000000000.0) " secs"))
     ret#))

(defn get-supported-relations
  [g]
  (loop [out []
         [rel & rels] supported-wn-relations]
    (if rel
      (recur (->> (q/run g (->wn-relations-query rel))
                  (map #(assoc % '?rel rel))
                  (concat out))
             rels)
      out)))

;; #146 - At the time of writing, 1194 DN synsets have links to the same ILIs.
;; It requires too much labour to correct these, so they are left out for now.
(defn remove-bad-ili-links
  [ili-res]
  (->> (group-by '?ili ili-res)
       (remove (comp (partial not= 1) count second))
       (mapcat second)))

(defn run-queries
  "Fetch data from `g` and prepare it for populating the XML file."
  [{:keys [dataset graph] :as db}]
  (let [dannet-graph          (db/get-graph dataset prefix/dn-uri)
        lexical-entry-res     (label-time
                                'lexical-entry-res
                                (q/run dannet-graph lexical-entry-query))
        sense-example-res     (label-time
                                'sense-example-res
                                (q/run dannet-graph sense-example-query))
        ili-query-res         (label-time
                                'ili-query-res
                                (q/run dannet-graph ili-query))
        definition-query-res  (label-time
                                'definition-query-res
                                (q/run dannet-graph definition-query))
        supersense-query-res  (label-time
                                'supersense-query-res
                                (q/run dannet-graph supersense-query))
        get-relations-res     (label-time
                                'get-supported-relations
                                (get-supported-relations graph))
        lexical-entry-res'    (remove (comp excluded-synsets '?synset)
                                      lexical-entry-res)
        entry-synset-grouping (group-by '?synset lexical-entry-res')
        synset-entries        (set (keys entry-synset-grouping))
        has-entry             (every-pred
                                (comp synset-entries '?subject)
                                (comp synset-entries '?object))
        exclude-synsets       (fn [m] (apply dissoc m excluded-synsets))
        entry-grouping        (group-by '?lexicalEntry lexical-entry-res')
        relations-grouping    (->> get-relations-res
                                   (remove (fn [{:syms [?rel ?subject ?object]}]
                                             (or (and (= ?rel :wn/hypernym)
                                                      (get (get @excluded-hypernymy ?subject) ?object))
                                                 (and (= ?rel :wn/hyponym)
                                                      (get (get @excluded-hyponymy ?subject) ?object)))))
                                   (filter has-entry)
                                   (group-by '?subject))
        orphan-synsets        (set/difference
                                (set (keys entry-synset-grouping))
                                (set (keys relations-grouping)))]
    [entry-grouping
     (exclude-synsets
       (merge
         relations-grouping
         ;; also add synsets with no relations emanating from them
         (zipmap orphan-synsets (repeat nil))))
     (exclude-synsets
       (merge-with merge
                   (update-vals
                     entry-synset-grouping
                     (fn [ms]
                       {:pos     (-> ms first (get '?pos) pos-str)
                        :members (sort (set (map (comp name '?sense) ms)))}))
                   (update-vals
                     (group-by '?synset sense-example-res)
                     (fn [ms]
                       {:examples (sort (set (map (comp str '?example) ms)))}))
                   (update-vals
                     (group-by '?synset (remove-bad-ili-links ili-query-res))
                     (fn [ms]
                       {:ili (-> ms first (get '?ili) name)}))
                   (update-vals
                     (group-by '?synset definition-query-res)
                     (fn [ms]
                       {:definition (-> ms first (get '?definition) str)}))
                   (update-vals
                     (group-by '?synset supersense-query-res)
                     (fn [ms]
                       {:lexfile (-> ms first (get '?supersense))}))))]))

(defn xml-str
  "Create a valid WN-LMF XML string from `query-results`."
  [[entry-grouping relations-grouping _synset-props :as query-results]]
  (println (count entry-grouping) "lexical entries found")
  (println (count relations-grouping) "synsets found")
  (-> (lexical-resource query-results)
      (xml/sexp-as-element)
      (xml/indent-str)
      (add-doctype)))

(defn export-xml!
  "Write WN-LMF to `f`."
  [f]
  (println "Exporting" f)
  (io/make-parents f)
  (spit f (xml-str (run-queries @dk.cst.dannet.web.resources/db))))

;; Taken from here:
;; https://gist.github.com/mikeananev/b2026b712ecb73012e680805c56af45f
(defn gzip
  "compress data.
    input: something which can be copied from by io/copy (e.g. filename ...).
    output: something which can be opend by io/output-stream.
        The bytes written to the resulting stream will be gzip compressed."
  [input output & opts]
  (with-open [output (-> output io/output-stream GZIPOutputStream.)]
    (apply io/copy input output opts)))

(defn export-wn-lmf!
  "Export DanNet into `dir` as WordNet LMF."
  [dir]
  (println "Beginning WN-LMF export of DanNet into" dir)
  (println "----")
  (let [f  (str dir "dannet-wn-lmf.xml")
        gz (str dir "dannet-wn-lmf.xml.gz")]
    (export-xml! f)
    (gzip (io/file f) (io/file gz)))
  (println "----")
  (println "WN-LMF export of DanNet complete!"))

(comment
  (get @excluded-hypernymy :dn/synset-30829)
  @excluded-hyponymy
  (xml-str (run-queries @dk.cst.dannet.web.resources/db))
  (time (export-xml! "export/wn-lmf/dannet-wn-lmf.xml"))
  (time (export-wn-lmf! "export/wn-lmf/"))
  #_.)
