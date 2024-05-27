(ns dk.cst.dannet.db.export.wn-lmf
  "WordNet LMF export functionality."
  (:require [clj-file-zip.core :as zip]
            [clojure.data.xml :as xml]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [dk.cst.dannet.db :as db]
            [dk.cst.dannet.db.bootstrap :as bootstrap]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.query :as q]
            [dk.cst.dannet.query.operation :as op]))

(def dannet-graph
  (delay (let [dataset (:dataset @dk.cst.dannet.web.resources/db)]
           (db/get-graph dataset prefix/dn-uri))))

(def lexical-entry-query
  (op/sparql
    "SELECT ?lexicalEntry ?synset ?sense ?pos ?writtenRep ?example ?ili ?definition
     WHERE {
       ?lexicalEntry ontolex:evokes ?synset ;
                     ontolex:sense ?sense ;
                     wn:partOfSpeech ?pos ;
                     ontolex:canonicalForm ?form .

       ?form ontolex:writtenRep ?writtenRep .

       OPTIONAL { ?synset wn:ili ?ili . }
       OPTIONAL { ?synset skos:definition ?definition . }
       OPTIONAL { ?sense lexinfo:senseExample ?example . }
     }"))

(defn ->synset-relations-query
  [synset]
  (op/sparql
    "SELECT ?rel ?object
     WHERE {
       " (prefix/kw->qname synset) " ?rel ?object .
       ?object a ontolex:LexicalConcept .
     }"))

(def pos-str
  {:wn/adjective "a"
   :wn/noun      "n"
   :wn/verb      "v"})

(defn lexical-entry
  [[id vs]]
  (into [:LexicalEntry {:id (name id)}
         [:Lemma {:writtenForm  (-> vs first (get '?writtenRep) str)
                  :partOfSpeech (-> vs first (get '?pos) pos-str)}]]
        (map (fn [{:syms [?sense ?synset]}]
               [:Sense {:id     (name ?sense)
                        :synset (name ?synset)}])
             vs)))

(defn synset-relations
  [g synset]
  (->> (q/run g (->synset-relations-query synset))
       (filter (comp #{"wn"} namespace '?rel))
       (map (fn [{:syms [?rel ?object]}]
              [:SynsetRelation {:relType (name ?rel)
                                :target  (name ?object)}]))))

(defn synset
  [g [id vs]]
  (let [pos     (-> vs first (get '?pos) pos-str)
        ili     (-> vs first (get '?ili))
        members (str/join " " (map (comp name '?sense) vs))]
    (into [:Synset (cond-> {:id      (name id)
                            :members members}
                     pos (assoc :partOfSpeech pos)
                     ili (assoc :ili (name ili)))
           (when-let [definition (some-> vs first (get '?definition) str)]
             [:Definition definition])]
          (concat
            (->> (keep '?example vs)
                 (map (fn [example]
                        [:Example (str example)])))
            (synset-relations g id)))))

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
  [g ms]
  [:LexicalResource {:xmlns/dc "https://globalwordnet.github.io/schemas/dc/"}
   (into lexicon
         (concat
           (->> (group-by '?lexicalEntry ms)
                (map lexical-entry))
           (->> (group-by '?synset ms)
                (map (partial synset g)))))])

(def doctype
  "<!DOCTYPE LexicalResource SYSTEM \"http://globalwordnet.github.io/schemas/WN-LMF-1.1.dtd\">")

(defn add-doctype
  [xml]
  (let [[before after] (str/split xml #"\r?\n" 2)]
    (str before "\n" doctype "\n" after)))

(defn xml-str
  [g]
  (let [ms (q/run g lexical-entry-query)]
    (-> (lexical-resource g ms)
        (xml/sexp-as-element)
        (xml/indent-str)
        (add-doctype))))

(defn export-xml!
  [f]
  (println "Exporting" f)
  (io/make-parents f)
  (spit f (xml-str @dannet-graph)))

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
  (->> (q/run @dannet-graph lexical-entry-query)
       (group-by (comp name '?lexicalEntry))
       (map lexical-entry))

  ;; the full dataset takes ~20 minutes to write (expensive synset queries)
  (time (xml-str @dannet-graph))
  (time (export-xml! "export/wn-lmf/dannet-wn-lmf.xml"))
  (time (export-wn-lmf! "export/wn-lmf/"))
  #_.)
