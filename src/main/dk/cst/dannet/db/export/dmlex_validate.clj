(ns dk.cst.dannet.db.export.dmlex-validate
  "Validation of the DMLex export against the schemas in doc/dmlex/spec/.
  The export carries crosslingual content (headwordTranslations), so the full
  schemas are used rather than the no-crosslingual variants.

  Needs the :validate alias, since neither validator ships with the JDK. The
  XML schemas are XSD 1.1 and the JSON schemas are draft 2020-12."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [com.fasterxml.jackson.databind ObjectMapper]
           [com.networknt.schema JsonSchemaFactory SpecVersion$VersionFlag]
           [javax.xml.transform.stream StreamSource]
           [javax.xml.validation SchemaFactory]
           [org.xml.sax ErrorHandler]))

(def xsd11-uri
  "http://www.w3.org/XML/XMLSchema/v1.1")

(def spec-dir
  "doc/dmlex/spec/")

(def xml-schema
  (str spec-dir "dmlex.xsd"))

(def json-schema
  (str spec-dir "dmlex.schema.json"))

(defn error-collector
  "A SAX ErrorHandler that collects messages into `errors` and stops the
  validation once it holds `limit` of them."
  [errors limit]
  (letfn [(collect [severity ^Exception e]
            (when (>= (count (swap! errors conj
                                    {:severity severity
                                     :line     (.getLineNumber ^org.xml.sax.SAXParseException e)
                                     :message  (.getMessage e)}))
                      limit)
              (throw e)))]
    (reify ErrorHandler
      (warning [_ e] (collect :warning e))
      (error [_ e] (collect :error e))
      (fatalError [_ e] (collect :fatal e)))))

(def schema-defect-codes
  "Xerces error codes that a defect in the DMLex XSD causes, not our document.
  Every identity constraint of the schema keys on a mixed content element, e.g.
  entryUnique on headword, and XSD needs a simple type there. A field then
  gives cvc-id.3, or a null value that looks like a duplicate."
  #{"cvc-id.3" "cvc-identity-constraint.4.1"})

(defn schema-defect?
  [{:keys [message]}]
  (some #(str/starts-with? message %) schema-defect-codes))

(defn validate-xml
  "Validate the DMLex XML file `f` against the XSD 1.1 schema. Returns the first
  `limit` errors, in :errors and in :schema-defects."
  ([f]
   (validate-xml f 500000))
  ([f limit]
   (let [factory   (SchemaFactory/newInstance xsd11-uri)
         schema    (.newSchema factory (io/file xml-schema))
         validator (.newValidator schema)
         errors    (atom [])]
     (.setErrorHandler validator (error-collector errors limit))
     (try
       (.validate validator (StreamSource. (io/file f)))
       (catch Exception e
         (when (empty? @errors)
           (swap! errors conj {:severity :fatal :message (.getMessage e)}))))
     (let [{defects true errors false} (group-by schema-defect? @errors)]
       {:errors         (vec errors)
        :schema-defects (count defects)}))))

(defn validate-json
  "Validate the DMLex JSON file `f` against the 2020-12 schema. Returns the
  first `limit` messages, or an empty vector when the file is valid."
  ([f]
   (validate-json f 25))
  ([f limit]
   (let [factory (JsonSchemaFactory/getInstance SpecVersion$VersionFlag/V202012)
         schema  (with-open [in (io/input-stream json-schema)]
                   (.getSchema factory in))
         node    (.readTree (ObjectMapper.) (io/file f))]
     (->> (.validate schema node)
          (map str)
          (take limit)
          (vec)))))

(defn validate-dmlex!
  "Validate the two DMLex files of the `lang` variant in `dir` and print the
  outcome."
  [dir lang]
  (let [xml-file  (str dir "dannet-dmlex-" lang ".xml")
        json-file (str dir "dannet-dmlex-" lang ".json")
        {:keys [errors schema-defects]} (validate-xml xml-file)
        json-errors (validate-json json-file)]
    (println "Validating" xml-file)
    (when (pos? schema-defects)
      (println " " schema-defects "errors ignored, caused by the XSD itself"))
    (if (empty? errors)
      (println "  XML is valid")
      (doseq [error errors]
        (println "  " error)))
    (println "Validating" json-file)
    (if (empty? json-errors)
      (println "  JSON is valid")
      (doseq [error json-errors]
        (println "  " error)))))

(comment
  (validate-json "export/dmlex/dannet-dmlex-da.json")
  (validate-xml "export/dmlex/dannet-dmlex-da.xml")
  (validate-dmlex! "export/dmlex/" "da")
  (validate-dmlex! "export/dmlex/" "en")
  #_.)
