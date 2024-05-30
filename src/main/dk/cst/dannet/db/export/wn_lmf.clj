(ns dk.cst.dannet.db.export.wn-lmf
  "WordNet LMF export functionality."
  (:require [clj-file-zip.core :as zip]
            [clojure.data.xml :as xml]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [dk.cst.dannet.db :as db]
            [dk.cst.dannet.db.bootstrap :as bootstrap]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.query :as q]
            [dk.cst.dannet.query.operation :as op]))

(def wn-relations
  "Taken from https://globalwordnet.github.io/schemas/"
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

(def dannet-graph
  (delay (let [dataset (:dataset @dk.cst.dannet.web.resources/db)]
           (db/get-graph dataset prefix/dn-uri))))

(defn ->wn-relations-query
  [rel]
  (op/sparql
    "SELECT ?subject ?object
     WHERE {
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
  (let [{:keys [ili pos definition examples members]} (get synset-props id)]
    (into [:Synset (cond-> {:id           (name id)
                            :members      (str/join " " members)
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
    :version  bootstrap/current-release
    :citation "Pedersen, Bolette S. Sanni Nimb, Jørg Asmussen, Nicolai H. Sørensen, Lars Trap-Jensen og Henrik Lorentzen (2009). DanNet – the challenge of compiling a WordNet for Danish by reusing a monolingual dictionary (pdf). Lang Resources & Evaluation 43:269–299."
    :url      "https://wordnet.dk/dannet"}])

(defn lexical-resource
  [[entry-grouping relation-grouping synset-props]]
  [:LexicalResource {:xmlns/dc "https://globalwordnet.github.io/schemas/dc/"}
   (into lexicon
         (concat
           (map lexical-entry entry-grouping)
           (map #(synset synset-props %) relation-grouping)))])

(def doctype
  "<!DOCTYPE LexicalResource SYSTEM \"http://globalwordnet.github.io/schemas/WN-LMF-1.1.dtd\">")

(defn add-doctype
  [xml]
  (let [[before after] (str/split xml #"\r?\n" 2)]
    (str before "\n" doctype "\n" after)))

(defmacro label-time
  "Evaluates expr and prints the time it took.  Returns the value of
 expr."
  {:added "1.0"}
  [label expr]
  `(let [start# (. System (nanoTime))
         nada#  (print ~label)
         ret#   ~expr]
     (println (str " ... " (/ (double (- (. System (nanoTime)) start#)) 1000000000.0) " secs"))
     ret#))

(defn get-relations
  [g]
  (loop [out []
         [rel & rels] wn-relations]
    (if rel
      (recur (->> (q/run g (->wn-relations-query rel))
                  (map #(assoc % '?rel rel))
                  (concat out))
             rels)
      out)))

(defn run-queries
  [g]
  (let [lexical-entry-res     (label-time
                                'lexical-entry-res
                                (q/run g lexical-entry-query))
        sense-example-res     (label-time
                                'sense-example-res
                                (q/run g sense-example-query))
        ili-query-res         (label-time
                                'ili-query-res
                                (q/run g ili-query))
        definition-query-res  (label-time
                                'definition-query-res
                                (q/run g definition-query))
        get-relations-res     (label-time
                                'get-relations
                                (get-relations g))

        entry-synset-grouping (group-by '?synset lexical-entry-res)
        synset-entries        (set (keys entry-synset-grouping))
        has-entry             (every-pred
                                (comp synset-entries '?subject)
                                (comp synset-entries '?object))

        entry-grouping        (group-by '?lexicalEntry lexical-entry-res)
        relations-grouping    (->> get-relations-res
                                   (filter has-entry)
                                   (group-by '?subject))
        orphan-synsets        (set/difference
                                (set (keys entry-synset-grouping))
                                (set (keys relations-grouping)))]
    [entry-grouping
     (merge
       relations-grouping
       ;; also add synsets with no relations emanating from them
       (zipmap orphan-synsets (repeat nil)))
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
                   (group-by '?synset ili-query-res)
                   (fn [ms]
                     {:ili (-> ms first (get '?ili) name)}))
                 (update-vals
                   (group-by '?synset definition-query-res)
                   (fn [ms]
                     {:definition (-> ms first (get '?definition) str)})))]))

(defn xml-str
  [[entry-grouping relations-grouping _synset-props :as query-results]]
  (println (count entry-grouping) "lexical entries found")
  (println (count relations-grouping) "synsets found")
  (-> (lexical-resource query-results)
      (xml/sexp-as-element)
      (xml/indent-str)
      (add-doctype)))

(defn export-xml!
  [f]
  (println "Exporting" f)
  (io/make-parents f)
  (spit f (xml-str (run-queries @dannet-graph))))

(defn export-wn-lmf!
  "Export DanNet into `dir` as WordNet LMF."
  [dir]
  (println "Beginning WN-LMF export of DanNet into" dir)
  (println "----")
  (let [f (str dir "dannet-wn-lmf.xml")
        z (str dir "dannet-wn-lmf.zip")]
    (export-xml! f)
    (zip/zip-files [f] z))
  (println "----")
  (println "WN-LMF export of DanNet complete!"))

(comment
  (xml-str (run-queries @dannet-graph))
  (time (export-xml! "export/wn-lmf/dannet-wn-lmf.xml"))
  (time (export-wn-lmf! "export/wn-lmf/"))
  #_.)
