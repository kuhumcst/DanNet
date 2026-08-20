(ns dk.cst.dannet.db.export.dmlex-validate-test
  "Fixture-based tests for the DMLex validation. These are self-contained:
  every fixture is written to a temporary file and validated against the real
  schemas in doc/dmlex/spec/, with no export and no database in sight.

  Both halves of the validation trade a whole-document reading for a streaming
  one, so most tests here are equivalence tests: they hold the cheap reading
  against the expensive one it replaced. Needs the :validate alias for the two
  validators, hence `clojure -X:validate:test`."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [dk.cst.dannet.db.export.dmlex-validate :as v])
  (:import [com.fasterxml.jackson.databind JsonNode ObjectMapper]
           [javax.xml.transform.stream StreamSource]
           [javax.xml.validation SchemaFactory]))

(defn- temp-file
  [suffix ^String content]
  (doto (java.io.File/createTempFile "dmlex-fixture" suffix)
    (spit content)
    (.deleteOnExit)))

;; -----------------------------------------------------------------------
;; JSON: the streaming passes against the whole-document reading they replace
;; -----------------------------------------------------------------------

(defn- whole-document-errors
  "The errors of the JSON file `f` read whole, the way validate-json used to.
  This is the oracle the streaming implementation is held against."
  [f]
  (let [mapper (ObjectMapper.)]
    (mapv str (.validate (.getSchema (v/json-schema-factory)
                                     ^JsonNode (.readTree mapper (io/file v/json-schema)))
                         (.readTree mapper (io/file f))))))

(defn- json-fixture
  [m]
  (temp-file ".json" (json/write-str m)))

(def ^:private resource
  {"langCode"             "da"
   "translationLanguages" ["en"]
   "entries"              [{"headword" "hund"}]})

(def ^:private equivalent-fixtures
  "Documents on which the streaming and whole-document readings must agree,
  down to the count of messages."
  {"a valid resource"          (json/write-str resource)
   "an empty entries array"    (json/write-str (assoc resource "entries" []))
   "no entries at all"         (json/write-str (dissoc resource "entries"))
   "a missing langCode"        (json/write-str (dissoc resource "langCode"))
   "an unknown property"       (json/write-str (assoc resource "nonsense" true))
   "a repeated language"       (json/write-str (assoc resource "translationLanguages" ["en" "en"]))
   "a standalone entry"        (json/write-str {"headword" "hund"})
   "a bad standalone entry"    (json/write-str {"homographNumber" 1})
   "an array at the root"      "[1,2,3]"
   "a string at the root"      "\"hello\""
   "an empty file"             ""
   "an empty object"           "{}"})

(deftest json-matches-whole-document
  (doseq [[label content] equivalent-fixtures]
    (testing label
      (let [f (temp-file ".json" content)]
        (is (= (count (whole-document-errors f))
               (count (v/validate-json f 1000)))
            "the same number of errors as reading the document whole")))))

(deftest json-reports-errors-inside-entries
  (testing "an error deep in a streamed array names its property and index"
    (let [f (json-fixture (assoc resource "entries" [{"headword" "hund"}
                                                     {"homographNumber" 1}]))]
      (is (every? #(str/starts-with? % "entries[1] ") (v/validate-json f))))))

(deftest json-applies-the-root-condition
  (testing "a translation may omit its langCode under exactly one translationLanguage"
    (is (empty? (v/validate-json
                  (json-fixture (assoc resource "entries"
                                       [{"headword" "hund"
                                         "senses"   [{"headwordTranslations" [{"text" "dog"}]}]}]))))))
  (testing "but not under two, which only the whole document can tell"
    (let [f (json-fixture (assoc resource
                                 "translationLanguages" ["en" "de"]
                                 "entries" [{"headword" "hund"
                                             "senses"   [{"headwordTranslations" [{"text" "dog"}]}]}]))]
      (is (= 1 (count (v/validate-json f))))
      (is (str/includes? (first (v/validate-json f)) "langCode")))))

(deftest json-reports-a-broken-file
  (testing "an unparseable document is reported, not thrown"
    (let [f (temp-file ".json" "{\"langCode\":\"da\",\"entries\":[{\"headword\":\"h\"}")]
      (is (= 1 (count (v/validate-json f))))
      (is (str/starts-with? (first (v/validate-json f)) "could not parse the file: ")))))

(deftest json-names-duplicate-properties-as-a-known-difference
  (testing "a document naming entries twice is reported, where a whole-document
            reading keeps only the last of them"
    (let [f (temp-file ".json"
                       (str "{\"langCode\":\"da\",\"translationLanguages\":[\"en\"],"
                            "\"entries\":[{\"homographNumber\":1}],"
                            "\"entries\":[{\"headword\":\"ok\"}]}"))]
      (is (empty? (whole-document-errors f)))
      (is (seq (v/validate-json f))))))

(deftest json-schema-still-supports-streaming
  (testing "the checked-in schema meets what item-by-item validation needs"
    (let [root (.readTree (ObjectMapper.) (io/file v/json-schema))]
      (is (nil? (v/streaming-obstacle root (v/streamable-properties root))))
      (is (empty? (v/foreign-refs root)))
      (is (contains? (set (v/condition-names root)) "translationLanguages")
          "the condition still weighs the one array the skeleton keeps whole"))))

(defn- mutate-schema
  "A copy of the DMLex JSON schema with `f` applied to its root node."
  [f]
  (let [mapper (ObjectMapper.)
        root   (.deepCopy ^JsonNode (.readTree mapper (io/file v/json-schema)))]
    (f mapper root)
    root))

(deftest guard-catches-a-schema-it-cannot-stream
  (testing "a condition weighing a streamed property is refused"
    (let [root (mutate-schema
                 (fn [^ObjectMapper mapper root]
                   (.set ^com.fasterxml.jackson.databind.node.ObjectNode
                         (v/resource-branch root)
                         "if" (.readTree mapper "{\"required\":[\"entries\"]}"))))]
      (is (str/includes? (v/streaming-obstacle root (v/streamable-properties root))
                         "entries"))))

  (testing "a $ref pointing outside $defs is refused"
    (let [root (mutate-schema
                 (fn [^ObjectMapper mapper root]
                   (.set ^com.fasterxml.jackson.databind.node.ObjectNode
                         (.get (v/resource-properties root) "entries")
                         "items" (.readTree mapper "{\"$ref\":\"https://example.org/entry.json\"}"))))]
      (is (str/includes? (v/streaming-obstacle root (v/streamable-properties root))
                         "https://example.org/entry.json"))))

  (testing "validate-json refuses outright rather than judging half a document"
    (let [broken (mutate-schema
                   (fn [^ObjectMapper mapper root]
                     (.set ^com.fasterxml.jackson.databind.node.ObjectNode
                           (v/resource-branch root)
                           "if" (.readTree mapper "{\"required\":[\"entries\"]}"))))]
      (with-redefs [v/json-schema (.getPath (temp-file ".json" (str broken)))]
        (is (thrown? clojure.lang.ExceptionInfo
                     (v/validate-json (json-fixture resource))))))))

(deftest a-schema-without-a-condition
  (testing "the no-crosslingual variant carries no condition and still works"
    (with-redefs [v/json-schema "doc/dmlex/spec/dmlex_no-crosslingual.schema.json"]
      (let [f (json-fixture (dissoc resource "translationLanguages"))]
        (is (= (count (whole-document-errors f))
               (count (v/validate-json f 1000)))))))) 

;; -----------------------------------------------------------------------
;; XML: the streaming assert pass against the asserts of the schema itself
;; -----------------------------------------------------------------------

(defn- xerces-assert-failures
  "The elements whose xs:assert the unmodified schema rejects in the file `f`,
  with a count each. This is the oracle validate-asserts is held against."
  [f]
  (let [validator (.newValidator (.newSchema (SchemaFactory/newInstance v/xsd11-uri)
                                             (io/file v/xml-schema)))
        errors    (atom [])]
    (.setErrorHandler validator (v/error-collector errors 500000))
    (try
      (.validate validator (StreamSource. (io/file f)))
      (catch Exception _ nil))
    (->> @errors
         (map :message)
         (filter #(str/starts-with? % "cvc-assertion"))
         (map #(second (re-find #"for element '([^']+)'" %)))
         (frequencies))))

(defn- our-assert-failures
  [f]
  (->> (v/validate-asserts f)
       (map :message)
       (map #(second (re-find #"^<([^>]+)>" %)))
       (frequencies)))

(defn- xml-fixture
  [body]
  (temp-file ".xml"
             (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                  "<lexicographicResource"
                  " xmlns=\"http://docs.oasis-open.org/lexidma/ns/dmlex-1.0\""
                  " xmlns:x=\"http://example.org/other\""
                  " title=\"fixture\" langCode=\"da\">\n"
                  body
                  "\n</lexicographicResource>\n")))

(def ^:private xml-fixtures
  "Bodies exercising every xs:assert of the schema, and the ways of writing
  text that could be mistaken for an empty one."
  {"text only inside a marker"
   "<entry id=\"e\"><headword><placeholderMarker>x</placeholderMarker></headword></entry>
    <translationLanguage langCode=\"en\"/>"

   "whitespace, CDATA, markers and entities"
   "<entry id=\"e\"><headword>&amp;</headword>
      <sense id=\"s\">
        <definition><text> </text></definition>
        <example><text><![CDATA[cd]]></text></example>
        <example><text>a<headwordMarker>b</headwordMarker>c</text></example>
      </sense>
    </entry>
    <translationLanguage langCode=\"en\"/>"

   "an empty headword and an empty definition"
   "<entry id=\"e\"><headword></headword>
      <sense id=\"s\"><definition><text><!-- nothing --></text></definition></sense>
    </entry>
    <translationLanguage langCode=\"en\"/>"

   "a pronunciation with neither transcription nor soundFile"
   "<entry id=\"e\"><headword>h</headword><pronunciation/></entry>
    <translationLanguage langCode=\"en\"/>"

   "a pronunciation with only a soundFile"
   "<entry id=\"e\"><headword>h</headword><pronunciation soundFile=\"a.mp3\"/></entry>
    <translationLanguage langCode=\"en\"/>"

   "a partOfSpeechTag without a description"
   "<entry id=\"e\"><headword>h</headword></entry>
    <translationLanguage langCode=\"en\"/>
    <partOfSpeechTag tag=\"noun\" for=\"entry\"/>"

   "translations without a langCode under two languages"
   "<entry id=\"e\"><headword>h</headword>
      <sense id=\"s\"><headwordTranslation><text>dog</text></headwordTranslation></sense>
    </entry>
    <translationLanguage langCode=\"en\"/>
    <translationLanguage langCode=\"de\"/>"

   "elements of another namespace"
   "<entry id=\"e\"><headword>h</headword></entry>
    <x:entry><x:headword/><x:text/></x:entry>
    <translationLanguage langCode=\"en\"/>"})

(deftest asserts-match-the-schema
  (doseq [[label body] xml-fixtures]
    (testing label
      (let [f (xml-fixture body)]
        (is (= (xerces-assert-failures f) (our-assert-failures f)))))))

(deftest asserts-report-a-broken-file
  (testing "an unparseable document is reported, not thrown"
    (let [f (temp-file ".xml" "<lexicographicResource><entry>")]
      (is (= [:fatal] (map :severity (v/validate-asserts f)))))))

(deftest every-assert-is-stripped
  (testing "the schema compiles with no assert left to cost a whole-document tree"
    (let [factory (doto (javax.xml.parsers.DocumentBuilderFactory/newInstance)
                    (.setNamespaceAware true))
          doc     (.parse (.newDocumentBuilder factory) (io/file v/xml-schema))
          removed (v/strip-asserts! doc)]
      (is (pos? removed))
      (is (zero? (.getLength (.getElementsByTagNameNS doc v/xs-uri "assert"))))
      (is (= removed (count (re-seq #"<xs:assert " (slurp v/xml-schema))))))))
