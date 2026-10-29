(ns dk.cst.dannet.db.export.csv
  "CSV export functionality."
  (:require [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [clojure.data.json :as json]
            [clj-file-zip.core :as zip]
            [dk.cst.theophrastus :as theo]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.db.query :as q]
            [dk.cst.dannet.db.query.operation :as op]
            [dk.cst.dannet.db.export.rdf :as rdf]
            [dk.cst.dannet.release :as release]
            [dk.cst.dannet.shared :as shared]))

(defn- expand-kw
  [x]
  (cond
    (and (keyword? x)
         (namespace x))
    (prefix/kw->uri x)

    ;; A symbol stands for a JSON-LD keyword, e.g. 'context becomes "@context".
    (symbol? x)
    (str "@" x)

    :else x))

(defn expand-kws
  "Expands any recognised namespaced keywords found in the input `m` to URIs."
  [m]
  (walk/postwalk expand-kw m))

(def synsets-metadata
  {'context    "http://www.w3.org/ns/csvw"
   "dc:license" "https://creativecommons.org/licenses/by-sa/4.0/"
   :tables  [{:url "synsets.csv"
              :tableSchema
              {:aboutUrl   (str prefix/dn-uri "{synset}")
               :primaryKey "synset"
               :columns    [{:name   "synset"
                             :titles "Synset"}

                            {:name        "definition"
                             :titles      "Definition"
                             :propertyUrl :skos/definition}

                            {:name        "ontotype"
                             :titles      "Ontological Type(s)"
                             :separator   "; "
                             :propertyUrl :dnc/ontologicalType}

                            {:virtual     true
                             :propertyUrl "rdf:type"
                             :valueUrl    :ontolex/LexicalConcept}]}}]
   :dialect {:header false}})

(def words-metadata
  {'context    "http://www.w3.org/ns/csvw"
   "dc:license" "https://creativecommons.org/licenses/by-sa/4.0/"
   :tables  [{:url "words.csv"
              :tableSchema
              {:aboutUrl   (str prefix/dn-uri "{word}")
               :primaryKey "word"
               :columns    [{:name   "word"
                             :titles "Word"}

                            {:name        "form"
                             :titles      "Written representation"
                             :propertyUrl :ontolex/writtenRep}

                            ;; The values have always come from wn:partOfSpeech
                            ;; (see op/csv-words), which is also the sole
                            ;; asserted PoS since GitHub issue #17.
                            {:name        "pos"
                             :titles      "Part-of-speech"
                             :propertyUrl :wn/partOfSpeech}

                            {:virtual     true
                             :propertyUrl "rdf:type"
                             :valueUrl    :ontolex/LexicalEntry}]}}]
   :dialect {:header false}})

(def senses-metadata
  {'context    "http://www.w3.org/ns/csvw"
   "dc:license" "https://creativecommons.org/licenses/by-sa/4.0/"
   :tables  [{:url "senses.csv"
              :tableSchema
              {:aboutUrl   (str prefix/dn-uri "{sense}")
               :primaryKey "sense"
               :columns    [{:name   "sense"
                             :titles "Sense"}

                            {:name        "synset"
                             :titles      "Synset"
                             :propertyUrl :ontolex/isLexicalizedSenseOf}

                            {:name        "word"
                             :titles      "Word"
                             :propertyUrl :ontolex/isSenseOf}

                            {:name        "note"
                             :titles      "Note"
                             :propertyUrl :lexinfo/usageNote}

                            {:virtual     true
                             :propertyUrl "rdf:type"
                             :valueUrl    :ontolex/LexicalSense}]}}]
   :dialect {:header false}})

(def inheritance-metadata
  {'context    "http://www.w3.org/ns/csvw"
   "dc:license" "https://creativecommons.org/licenses/by-sa/4.0/"
   :tables  [{:url "inheritance.csv"
              :tableSchema
              {:columns [{:name   "to"
                          :titles "To synset"}

                         {:name        "relation"
                          :propertyUrl :dns/inheritedRelation
                          :titles      "Inherited relation"}

                         {:name        "from"
                          :propertyUrl :dns/inheritedFrom
                          :titles      "From synset"}

                         {:virtual     true
                          :propertyUrl "rdf:type"
                          :valueUrl    :dns/Inheritance}]}}]
   :dialect {:header false}})

(def examples-metadata
  {'context    "http://www.w3.org/ns/csvw"
   "dc:license" "https://creativecommons.org/licenses/by-sa/4.0/"
   :tables  [{:url "examples.csv"
              :tableSchema
              {:columns [{:name   "sense"
                          :titles "Sense"}

                         {:name        "example"
                          :propertyUrl :lexinfo/senseExample
                          :titles      "Example"}]}}]
   :dialect {:header false}})

(def relations-metadata
  {'context    "http://www.w3.org/ns/csvw"
   "dc:license" "https://creativecommons.org/licenses/by-sa/4.0/"
   :tables  [{:url "relations.csv"
              :tableSchema
              {:columns [{:name   "from"
                          :titles "From synset"}

                         {:name   "relation"
                          :titles "Relation"}

                         {:name   "to"
                          :titles "To synset"}]}}]
   :dialect {:header false}})

(defn metadata->json
  "Convert a `metadata` map into the JSON-LD format used for CSVW metadata."
  [metadata]
  (->> (expand-kws metadata)
       (json/pprint)
       (with-out-str)))

(defn export-metadata!
  "Export a `metadata` map as the given `f`."
  [f metadata]
  (println "Exporting" f)
  (spit f (metadata->json metadata)))


(defn- csv-table-cell
  ([separator x]
   (->> (shared/setify x)
        (map (fn [x]
               (cond
                 (keyword? x) (name x)
                 :else (str x))))
        (sort)
        (str/join separator)))
  ([x]
   (csv-table-cell "; " x)))

(defn- csv-row
  "Convert `row` values into CSVW-compatible strings."
  [row]
  (mapv csv-table-cell row))

;; A query for the relations of every synset at once is very slow, while
;; theo/entity reads each synset with Graph.find.
(defn synset-rel-table
  "The synset->synset relations of `synset` in `g`, as rows of the synset,
  the IRI of the relation and the other synset."
  [g synset]
  (for [[p v] (dissoc (theo/entity g synset) :rdf/about)
        o     (shared/setify v)
        :when (and (keyword? o)
                   (= "dn" (namespace o))
                   (str/starts-with? (name o) "synset-"))]
    [synset (prefix/kw->uri p) o]))

(defn export-csv-rows!
  "Write CSV `rows` to file `f`."
  [f rows]
  (println "Exporting" f)
  (io/make-parents f)
  (with-open [writer (io/writer f)]
    (csv/write-csv writer rows)))

(defn export-table!
  "Write the `rows` of `table` as CSV into `dir`, next to its CSVW `metadata`."
  [dir table rows metadata]
  (export-csv-rows! (str dir table ".csv") rows)
  (export-metadata! (str dir table "-metadata.json") metadata))

(defn- non-zip-files
  [dir-path]
  (let [dir-file (io/file dir-path)]
    (->> (file-seq dir-file)
         (remove (partial = dir-file))
         (remove #(str/starts-with? (.getName %) "."))
         (map #(.getPath %))
         (remove #(str/ends-with? % ".zip")))))

(defn export-csv!
  "Export the `dannet` dataset to `dir` as CSVW."
  ([{:keys [dataset] :as dannet} dir]
   (println "Beginning CSV export of DanNet into" dir)
   (println "----")
   (let [g          (theo/named-graph dataset prefix/dn-graph)
         synsets-ks '[?synset ?definition ?onto]
         words-ks   '[?word ?writtenRep ?pos ?rdfType]
         senses-ks  '[?sense ?synset ?word ?note]
         zip-path   (str dir (prefix/export-file "csv" 'dn))]
     (println "Fetching table rows:" synsets-ks)
     (export-table! dir "synsets"
                    (map csv-row (q/table-query g synsets-ks op/csv-synsets))
                    synsets-metadata)

     (println "Fetching table rows:" words-ks)
     (export-table! dir "words"
                    (map csv-row (q/table-query g words-ks op/csv-words))
                    words-metadata)

     (println "Fetching table rows:" senses-ks)
     (export-table! dir "senses"
                    (map csv-row (q/table-query g senses-ks op/csv-senses))
                    senses-metadata)

     (println "Fetching inheritance data...")
     (export-table! dir "inheritance"
                    (map (fn [{:syms [?synset ?rel ?from]}]
                           [(name ?synset)
                            (prefix/kw->uri ?rel)
                            (name ?from)])
                         (theo/q g op/csv-inheritance))
                    inheritance-metadata)

     (println "Fetching example data...")
     (export-table! dir "examples"
                    (map (comp csv-row (juxt '?sense '?example))
                         (theo/q g op/csv-examples))
                    examples-metadata)

     (println "Fetching synset relations...")
     (let [synset->triples (partial synset-rel-table g)
           synsets         (->> (theo/q g op/synsets)
                                (map '?synset)
                                (set))]
       (export-table! dir "relations"
                      (->> (mapcat synset->triples synsets)
                           (map csv-row))
                      relations-metadata))

     ;; LICENSE + README.txt are written into the export dir so non-zip-files
     ;; picks them up and ships them under those exact names inside the zip.
     (rdf/copy-license! :cc-by-sa (str dir "LICENSE"))
     (spit (str dir "README.txt")
           (rdf/render-readme "dannet-csv.txt" release/to))

     (println "Zipping CSV files and associated metadata into" zip-path "...")
     (zip/zip-files (non-zip-files dir) zip-path))

   (println "----")
   (println "CSV export of DanNet complete!"))
  ([dannet]
   (export-csv! dannet "export/csv/")))

(comment
  ;; Export DanNet as CSV
  (export-csv! @dk.cst.dannet.web.instance/db)

  (spit "synsets-metadata.json" (metadata->json synsets-metadata))
  (export-metadata! "synsets-metadata.json" synsets-metadata)

  ;; Generate new synset labels for Thomas Troelsgård
  (->> (theo/q (:inference @dk.cst.dannet.web.instance/db) op/synset-long-short-labels)
       (map (fn [{:syms [?synset ?label ?shortLabel]}]
              [(second (str/split (name ?synset) #"-s?"))
               (->> (or ?shortLabel ?label)
                    (str)
                    (shared/sense-labels shared/synset-sep)
                    (map #(str/replace % #"_[^; ]+" ""))
                    (set)
                    (sort)
                    (str/join "; "))]))
       (sort-by (comp #(Integer/parseInt %) first))
       (export-csv-rows! "synset-labels.csv"))

  ;; Test CSV table data
  (let [g (theo/named-graph (:dataset @dk.cst.dannet.web.instance/db)
                            prefix/dn-graph)]
    (->> (q/table-query g '[?synset ?definition ?onto] op/csv-synsets)
         (take 10)))
  #_.)
