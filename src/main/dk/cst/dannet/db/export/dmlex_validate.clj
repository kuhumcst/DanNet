(ns dk.cst.dannet.db.export.dmlex-validate
  "Validation of the DMLex export against the schemas in doc/dmlex/spec/.
  The export carries crosslingual content (headwordTranslations), so the full
  schemas are used rather than the no-crosslingual variants.

  Needs the :validate alias, since neither validator ships with the JDK. The
  XML schemas are XSD 1.1 and the JSON schemas are draft 2020-12.

  Neither file is ever held in memory whole. The XSD asserts are stripped from
  the schema before it is compiled and re-checked in a streaming pass instead,
  for the reason `strip-asserts!` gives, and the JSON is validated an item at a
  time as `validate-json` describes."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [com.fasterxml.jackson.core JsonParser JsonProcessingException JsonToken]
           [com.fasterxml.jackson.databind JsonNode ObjectMapper]
           [com.fasterxml.jackson.databind.node ArrayNode ObjectNode]
           [com.networknt.schema JsonSchema JsonSchemaFactory SpecVersion$VersionFlag]
           [javax.xml.parsers DocumentBuilderFactory]
           [javax.xml.stream XMLInputFactory XMLStreamConstants XMLStreamException
            XMLStreamReader]
           [javax.xml.transform.dom DOMSource]
           [javax.xml.transform.stream StreamSource]
           [javax.xml.validation Schema SchemaFactory]
           [org.w3c.dom Document Element Node]
           [org.xml.sax ErrorHandler]))

(def xsd11-uri
  "http://www.w3.org/XML/XMLSchema/v1.1")

(def xs-uri
  "http://www.w3.org/2001/XMLSchema")

(def dmlex-uri
  "http://docs.oasis-open.org/lexidma/ns/dmlex-1.0")

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
  gives cvc-id.3, or a null value that looks like a duplicate. Filtering them
  also throws away what the constraints were for, which is why
  `validate-identity` checks the ids and references itself."
  #{"cvc-id.3" "cvc-identity-constraint.4.1"})

(defn schema-defect?
  "Is `error` one that the XSD itself causes rather than our document?"
  [{:keys [message] :as error}]
  ;; boolean, since validate-xml groups the errors by this very value
  (boolean (some #(str/starts-with? message %) schema-defect-codes)))

(def checked-asserts
  "Every xs:assert of dmlex.xsd, as the complexType that carries it and the
  test it reads, that `validate-asserts` re-checks in full.

  `strip-asserts!` takes every assert out of the schema, so one that nobody
  transcribed into `validate-asserts` would quietly stop being checked at all.
  Holding the schema against this list turns that silence into a refusal. The
  entryTypeRequiredLangCode assert is listed as covered by omission: it only
  bites in a standalone <entry> document, which the export never writes."
  #{["lexicographicResourceType"
     (str "count(translationLanguage)=1 or "
          "(count(.//headwordTranslation[not(@langCode)])=0 and "
          "count(.//headwordExplanation[not(@langCode)])=0 and "
          "count(.//exampleTranslation[not(@langCode)])=0)")]
    ["entryType" "string-length(headword)>0"]
    ["entryTypeRequiredLangCode"
     (str "count(.//headwordTranslation[not(@langCode)])=0 and "
          "count(.//headwordExplanation[not(@langCode)])=0 and "
          "count(.//exampleTranslation[not(@langCode)])=0")]
    ["definitionType" "string-length(text)>0"]
    ["exampleType" "string-length(text)>0"]
    ["headwordTranslationType" "string-length(text)>0"]
    ["exampleTranslationType" "string-length(text)>0"]
    ["pronunciationType" "transcription or @soundFile"]
    ["partOfSpeechTagType" "string-length(description)>0"]})

(defn assert-owner
  "The name of the complexType that the xs:assert `node` sits in."
  [^Node node]
  (loop [^Node parent (.getParentNode node)]
    (cond
      (nil? parent)                     nil
      (= "complexType" (.getLocalName parent)) (.getAttribute ^Element parent "name")
      :else                             (recur (.getParentNode parent)))))

(defn schema-asserts
  "Every xs:assert of the schema document `doc`, as a [complexType test] pair."
  [^Document doc]
  (let [nodes (.getElementsByTagNameNS doc xs-uri "assert")]
    (into #{} (for [i (range (.getLength nodes))
                    :let [^Element node (.item nodes i)]]
                [(assert-owner node) (.getAttribute node "test")]))))

(defn unchecked-asserts
  "The xs:asserts of the schema document `doc` that nothing re-checks once
  `strip-asserts!` has taken them out."
  [^Document doc]
  (remove checked-asserts (schema-asserts doc)))

(defn strip-asserts!
  "Remove every xs:assert from the schema document `doc` and return the number
  of asserts removed.

  Xerces evaluates an xs:assert over an in-memory tree, and it builds that tree
  for the whole instance document as soon as the schema holds a single assert,
  wherever that assert sits. Validating the DMLex export therefore takes ~14 GB
  of heap and ~110 seconds, against ~300 MB and ~3 seconds once the asserts are
  gone. `validate-asserts` covers the stripped rules instead."
  [^Document doc]
  ;; getElementsByTagNameNS returns a live NodeList, so the nodes have to be
  ;; realised before any of them is detached from the document.
  (let [nodes   (.getElementsByTagNameNS doc xs-uri "assert")
        asserts (mapv #(.item nodes %) (range (.getLength nodes)))]
    (doseq [^Node node asserts]
      (.removeChild (.getParentNode node) node))
    (count asserts)))

(defn ^Schema assert-free-schema
  "Compile the XSD 1.1 schema in the file `f` with its xs:asserts stripped.

  Refuses a schema that carries an assert `validate-asserts` does not cover,
  rather than dropping it from validation without a word."
  [f]
  (let [factory (doto (DocumentBuilderFactory/newInstance)
                  ;; getElementsByTagNameNS only matches in an aware document
                  (.setNamespaceAware true))
        doc     (.parse (.newDocumentBuilder factory) (io/file f))]
    (when-let [unchecked (seq (unchecked-asserts doc))]
      (throw (ex-info (str "refusing to strip asserts from " f
                           " that nothing re-checks: " (pr-str unchecked))
                      {:schema f :unchecked unchecked})))
    (strip-asserts! doc)
    (.newSchema (SchemaFactory/newInstance xsd11-uri) (DOMSource. doc))))

(def non-empty-child
  "The DMLex elements whose xs:assert demands a non-empty child element, by the
  name of that child."
  {"entry"               "headword"
   "definition"          "text"
   "example"             "text"
   "headwordTranslation" "text"
   "exampleTranslation"  "text"
   "partOfSpeechTag"     "description"})

(def translation-elements
  "The DMLex elements that the lexicographicResource assert counts when they
  carry no @langCode."
  #{"headwordTranslation" "headwordExplanation" "exampleTranslation"})

(defn dmlex-name
  "The local name of the element that `r` points at, or nil when the element
  is not in the DMLex namespace."
  [^XMLStreamReader r]
  (when (= dmlex-uri (.getNamespaceURI r))
    (.getLocalName r)))

(defn assert-frame
  "A frame tracking the xs:assert of the element `el` that `r` just opened, or
  nil when `el` carries no assert.

  A frame gathers what its assert needs while the element is open: :chars
  counts the characters inside the child element named by :child, while
  :sound-file? and :transcription? record what a <pronunciation> offers."
  [^XMLStreamReader r el]
  (let [line (.getLineNumber (.getLocation r))]
    (cond
      (non-empty-child el)
      {:el el :line line :child (non-empty-child el)}

      (= "pronunciation" el)
      {:el          el
       :line        line
       :sound-file? (some? (.getAttributeValue r nil "soundFile"))})))

(defn frame-error
  "The assert violation that the just closed `frame` represents, or nil."
  [{:keys [el line child chars sound-file? transcription?] :as frame}]
  (cond
    ;; XPath counts whitespace, so " " passes the assert just like "x" does.
    (and child (not (pos? (or chars 0))))
    {:severity :error
     :line     line
     :message  (str "<" el "> has an empty or missing <" child ">")}

    (and (= "pronunciation" el) (not sound-file?) (not transcription?))
    {:severity :error
     :line     line
     :message  "<pronunciation> has neither a <transcription> nor a @soundFile"}))

(defn resource-error
  "The lexicographicResource assert violation in `resource`, or nil.

  A translation may only omit its @langCode when the resource declares exactly
  one <translationLanguage>. A document that is not one lexicographicResource
  is reported as such instead, since these counts then say nothing: they run
  across the whole document rather than per resource."
  [{:keys [line langs langless resources] :as resource}]
  (cond
    (not= 1 resources)
    {:severity :fatal
     :line     line
     :message  (str "found " resources " <lexicographicResource> where this "
                    "check covers exactly one, so its asserts went unchecked")}

    (and (not= 1 langs) (pos? langless))
    {:severity :error
     :line     line
     :message  (str "<lexicographicResource> declares " langs
                    " <translationLanguage>, but holds " langless
                    " translations without a @langCode")}))

(defn ^XMLInputFactory input-factory
  "A StAX factory for reading a DMLex export.

  Coalescing gives one CHARACTERS event per run of text, CDATA included, which
  keeps a running character count a plain sum. The entity limits are lifted
  because the DDO URLs escape their & as &amp;, and a document holding enough
  of those trips the accounting the JDK keeps against entity-expansion
  attacks. These are files we wrote ourselves."
  []
  (doto (XMLInputFactory/newInstance)
    (.setProperty XMLInputFactory/IS_COALESCING true)
    (.setProperty "jdk.xml.maxGeneralEntitySizeLimit" "0")
    (.setProperty "jdk.xml.totalEntitySizeLimit" "0")
    (.setProperty "jdk.xml.entityExpansionLimit" "0")))

(defn stream-error
  "The fatal error that the parse failure `e` represents."
  [^XMLStreamException e]
  {:severity :fatal
   :line     (some-> (.getLocation e) (.getLineNumber))
   :message  (.getMessage e)})

(defn open-frame
  "The `frames` updated for the DMLex element `el` that `r` just opened.

  An element that carries an assert opens a frame of its own. Any other
  element may be the one the innermost frame is waiting for."
  [frames ^XMLStreamReader r el]
  (if-let [frame (assert-frame r el)]
    (conj frames frame)
    (let [top (peek frames)]
      (cond
        (and top (= el (:child top)) (nil? (:chars top)))
        (conj (pop frames) (assoc top :chars 0 :open? true))

        (and top (= "transcription" el) (= "pronunciation" (:el top)))
        (conj (pop frames) (assoc top :transcription? true))

        :else frames))))

(defn count-translations
  "The `resource` counts updated for the DMLex element `el` that `r` just
  opened.

  The lexicographicResource assert weighs the whole document, so it can only
  be settled once these counts are final."
  [resource ^XMLStreamReader r el]
  (cond-> resource
    (= "lexicographicResource" el)
    (-> (assoc :line (.getLineNumber (.getLocation r)))
        (update :resources inc))

    (= "translationLanguage" el)
    (update :langs inc)

    (and (translation-elements el)
         (nil? (.getAttributeValue r nil "langCode")))
    (update :langless inc)))

(defn open-element
  "The `scan` updated for the element that `r` just opened."
  [scan ^XMLStreamReader r]
  (if-let [el (dmlex-name r)]
    (-> scan
        (update :frames open-frame r el)
        (update :resource count-translations r el))
    scan))

(defn close-element
  "The `scan` updated for the element that `r` just closed.

  Closing the child element a frame waits for freezes its character count,
  while closing the element of the frame itself settles its assert."
  [{:keys [frames] :as scan} ^XMLStreamReader r]
  (let [el  (dmlex-name r)
        top (peek frames)]
    (cond
      (and top (:open? top) (= el (:child top)))
      (assoc scan :frames (conj (pop frames) (dissoc top :open?)))

      (and top (= el (:el top)))
      (let [scan (assoc scan :frames (pop frames))]
        (if-let [error (frame-error top)]
          (update scan :errors conj error)
          scan))

      :else scan)))

(defn read-characters
  "The `scan` with the `n` characters just read added to every open frame.

  XPath takes the string value of an element, i.e. the characters of its whole
  subtree, so an open frame counts every run of text below it."
  [scan n]
  (update scan :frames
          (partial mapv #(cond-> % (:open? %) (update :chars + n)))))

(defn scan-errors
  "The errors of the finished `scan`, including the lexicographicResource
  assert that only the end of the document can settle."
  [{:keys [resource errors]}]
  (if-let [error (resource-error resource)]
    (conj errors error)
    errors))

(defn validate-asserts
  "Check the xs:assert rules of the DMLex XSD on the XML file `f` in a single
  streaming pass. Returns a vector of errors shaped like the Xerces ones.

  `strip-asserts!` takes the asserts out of the schema, so this pass is what
  keeps them covered. It assumes a document whose element is a single
  <lexicographicResource>, which is what the DMLex export writes; the assert
  guarding a standalone <entry> document is out of scope.

  A malformed document yields the parse failure alone, since nothing found
  before it can be trusted."
  [f]
  (with-open [in (io/input-stream f)]
    (let [r (.createXMLStreamReader (input-factory) in)]
      (try
        (loop [scan {:frames [] :resource {:resources 0 :langs 0 :langless 0} :errors []}]
          (if-not (.hasNext r)
            (scan-errors scan)
            (recur (condp = (.next r)
                     XMLStreamConstants/START_ELEMENT (open-element scan r)
                     XMLStreamConstants/END_ELEMENT   (close-element scan r)
                     XMLStreamConstants/CHARACTERS    (read-characters scan (.getTextLength r))
                     scan))))
        (catch XMLStreamException e
          [(stream-error e)])))))

(def identified-elements
  "The DMLex elements whose @id the schema requires to be unique, and which a
  <member> may name."
  #{"entry" "sense" "collocateMarker"})

(defn note-identity
  "The `scan` updated for the element that `r` just opened, gathering the ids
  it declares and the references it makes."
  [{:keys [ids] :as scan} ^XMLStreamReader r]
  (let [el   (dmlex-name r)
        line (delay (.getLineNumber (.getLocation r)))]
    (cond
      (identified-elements el)
      (if-let [id (.getAttributeValue r nil "id")]
        (if (ids id)
          (update scan :errors conj
                  {:severity :error
                   :line     @line
                   :message  (str "<" el "> repeats the id \"" id "\"")})
          (update scan :ids conj id))
        scan)

      (= "member" el)
      (if-let [ref (.getAttributeValue r nil "ref")]
        (update scan :refs #(if (% ref) % (assoc % ref @line)))
        scan)

      :else scan)))

(defn dangling-errors
  "The errors of the finished `scan` for every @ref that names no id."
  [{:keys [ids refs]}]
  (for [[ref line] (sort-by val refs)
        :when      (not (ids ref))]
    {:severity :error
     :line     line
     :message  (str "<member> refers to \"" ref "\", which names no id")}))

(defn validate-identity
  "Check that the ids of the XML file `f` are unique and that every <member>
  names one, in a single streaming pass. Returns a vector of errors shaped
  like the Xerces ones.

  The schema states both rules, as entryOrSenseOrCollocateMarkerKey and the
  memberRef keyref, but Xerces never gets to apply them: every identity
  constraint of the schema keys on a mixed content element, so the machinery
  collapses into the cvc-id.3 errors that `schema-defect-codes` filters away,
  and duplicate ids and references to nothing pass unseen. Doing it here costs
  a set of the ids and a map of the references.

  A malformed document yields the parse failure alone, since nothing found
  before it can be trusted."
  [f]
  (with-open [in (io/input-stream f)]
    (let [r (.createXMLStreamReader (input-factory) in)]
      (try
        (loop [scan {:ids #{} :refs {} :errors []}]
          (if-not (.hasNext r)
            (into (:errors scan) (dangling-errors scan))
            (recur (if (= XMLStreamConstants/START_ELEMENT (.next r))
                     (note-identity scan r)
                     scan))))
        (catch XMLStreamException e
          [(stream-error e)])))))

(defn validate-xml
  "Validate the DMLex XML file `f` against the XSD 1.1 schema. Returns the
  errors in :errors, at most `limit` from either pass, and the number of
  errors that the schema itself causes in :schema-defects.

  The schema is compiled without its asserts, so :errors holds what Xerces
  found, then what `validate-asserts` and `validate-identity` found."
  ([f]
   (validate-xml f 500000))
  ([f limit]
   (let [validator (.newValidator (assert-free-schema xml-schema))
         errors    (atom [])]
     (.setErrorHandler validator (error-collector errors limit))
     (try
       (.validate validator (StreamSource. (io/file f)))
       (catch Exception e
         (when (empty? @errors)
           (swap! errors conj {:severity :fatal :message (.getMessage e)}))))
     (let [{defects true errors false} (group-by schema-defect? @errors)]
       {:errors         (into (vec errors) (take limit)
                              (concat (validate-asserts f) (validate-identity f)))
        :schema-defects (count defects)}))))

(def item-only-keywords
  "The JSON Schema keywords an array property may use and still be validated
  one item at a time.

  Anything else, uniqueItems above all, weighs the array as a whole and so
  needs every item in memory at once. Such an array is kept whole, which is
  safe but not cheap: were a later schema to put one of those keywords on
  entries, validation would stay correct and quietly grow again."
  #{"type" "items" "description" "$comment"})

(defn ^JsonSchemaFactory json-schema-factory
  "A factory for the 2020-12 dialect that the DMLex JSON schemas declare."
  []
  (JsonSchemaFactory/getInstance SpecVersion$VersionFlag/V202012))

(defn field-names
  "The names of the fields of the JSON object `node`."
  [^JsonNode node]
  (iterator-seq (.fieldNames node)))

(defn ^JsonSchema sub-schema
  "Compile the `fragments` of the root schema node `root` into one schema.

  The $defs of `root` come along, so a $ref inside a fragment still resolves."
  [^JsonNode root fragments]
  (let [mapper (ObjectMapper.)
        array  (reduce (fn [^ArrayNode a ^JsonNode f] (.add a f))
                       (.createArrayNode mapper) fragments)
        node   (doto (.createObjectNode mapper)
                 (.set "$schema" (.get root "$schema"))
                 (.set "$defs" (.get root "$defs"))
                 (.set "allOf" array))]
    (.getSchema (json-schema-factory) ^JsonNode node)))

(defn ^JsonNode resource-properties
  "The properties of a lexicographicResource in the root schema node `root`."
  [^JsonNode root]
  (-> root (.get "$defs") (.get "lexicographicResource") (.get "properties")))

(defn ^JsonNode resource-branch
  "The oneOf branch of the root schema node `root` that covers a
  lexicographicResource, the other one being a standalone entry."
  [^JsonNode root]
  (->> (.get root "oneOf")
       (filter #(= "#/$defs/lexicographicResource"
                   (some-> ^JsonNode % (.get "$ref") (.asText))))
       (first)))

(defn streamable?
  "Does per-item validation cover the array property `property` in full?"
  [^JsonNode property]
  (and (= "array" (some-> (.get property "type") (.asText)))
       (every? item-only-keywords (field-names property))))

(defn streamable-properties
  "The names of the array properties of the root schema node `root` that can be
  read and validated one item at a time."
  [^JsonNode root]
  (let [properties (resource-properties root)]
    (into #{}
          (filter (fn [^String k] (streamable? (.get properties k))))
          (field-names properties))))

(defn applied-conditions
  "The extra per-property constraints that the root schema node `root` puts on
  the document `doc`, by property name, or nil when there is no condition or
  it does not hold.

  The schema only compels a translation to name its langCode when the resource
  does not declare exactly one translationLanguage. Nothing short of the whole
  document settles that, which is why the skeleton is read first."
  [^JsonNode root ^JsonNode doc]
  ;; the answer is sound because the condition weighs translationLanguages,
  ;; which the skeleton keeps whole; one weighing a streamed property would
  ;; meet an empty array here, which is what streaming-obstacle refuses. A
  ;; schema may also carry no condition at all, as the no-crosslingual
  ;; variant does.
  (when-let [condition (some-> (resource-branch root) (.get "if"))]
    (when (empty? (.validate (sub-schema root [condition]) doc))
      (let [properties (-> (resource-branch root) (.get "then") (.get "properties"))]
        (into {}
              (map (juxt identity (fn [^String k] (.get properties k))))
              (field-names properties))))))

(defn item-schemas
  "The schema for one item of each array property named in `streamed`, by that
  name, drawn from the root schema node `root` and the `conditions` in force."
  [^JsonNode root streamed conditions]
  (let [properties (resource-properties root)]
    (into {} (for [^String k streamed]
               [k (sub-schema root (keep identity
                                         [(.get ^JsonNode (.get properties k) "items")
                                          (some-> ^JsonNode (get conditions k) (.get "items"))]))]))))

(defn node-seq
  "The JSON node `node` and every node below it."
  [^JsonNode node]
  (tree-seq #(.isContainerNode ^JsonNode %)
            #(iterator-seq (.elements ^JsonNode %))
            node))

(defn foreign-refs
  "The $ref targets of the schema node `root` that point outside its $defs."
  [^JsonNode root]
  (->> (node-seq root)
       (keep #(some-> ^JsonNode % (.get "$ref") (.asText)))
       (remove #(str/starts-with? % "#/$defs/"))
       (distinct)))

(defn condition-names
  "Every name the condition of the root schema node `root` mentions.

  A condition can name a property as a field, under properties, or as a plain
  string, under required. Both are gathered, since either would mean the
  condition weighs it."
  [^JsonNode root]
  (if-let [condition (some-> (resource-branch root) (.get "if"))]
    (let [nodes (node-seq condition)]
      (distinct (concat (mapcat field-names nodes)
                        (keep #(when (.isTextual ^JsonNode %) (.asText ^JsonNode %))
                              nodes))))
    ()))

(defn streaming-obstacle
  "What stops the schema node `root` from being validated one item at a time,
  as a sentence, or nil when nothing does.

  Reading the document item by item rests on two properties of the schema.
  Every $ref must point into $defs, because an item is judged by a subschema
  that carries the $defs and nothing else. And the root condition must weigh
  no property that the skeleton empties, or it would be settled against an
  empty array. Both hold for DMLex 1.0; checking them means a later schema
  fails loudly rather than passing documents nobody looked at."
  [^JsonNode root streamed]
  (let [refs      (foreign-refs root)
        condition (filter streamed (condition-names root))]
    (cond
      (seq refs)
      (str "these $ref targets point outside $defs: " (str/join ", " refs))

      (seq condition)
      (str "the root condition weighs " (str/join ", " condition)
           ", which is read one item at a time and so reaches it empty"))))

(defn skeleton
  "The JSON document in the file `f` with every property named in `streamed`
  emptied out.

  What remains carries the constraints that no single item can answer for:
  the required properties, additionalProperties, and the array keywords that
  weigh a whole array."
  [f streamed]
  (let [mapper (ObjectMapper.)]
    (with-open [parser (.createParser mapper (io/file f))]
      (if-not (= JsonToken/START_OBJECT (.nextToken parser))
        ;; nothing to leave out of a document that is not even an object, so
        ;; hand the schema the value itself and let it say what it is
        (.readTree mapper (io/file f))
        (loop [^ObjectNode node (.createObjectNode mapper)]
          (if-not (= JsonToken/FIELD_NAME (.nextToken parser))
            node
            (let [k (.currentName parser)
                  v (do (.nextToken parser)
                        (if (streamed k)
                          (do (.skipChildren parser)
                              (.createArrayNode mapper))
                          (.readValueAsTree parser)))]
              (recur (doto node (.set k ^JsonNode v))))))))))

(defn array-errors
  "The errors of the array that `parser` points at, validated item by item
  against `schema`.

  Every message names the `property` and the index it came from, since the
  schema itself only ever sees one item."
  [^JsonParser parser ^JsonSchema schema property]
  (loop [i      0
         errors []]
    (if (= JsonToken/END_ARRAY (.nextToken parser))
      errors
      (recur (inc i)
             (into errors
                   (map #(str property "[" i "] " %))
                   (.validate schema ^JsonNode (.readValueAsTree parser)))))))

(defn item-errors
  "The errors of every item of the array properties in `schemas`, read from the
  JSON file `f` one item at a time."
  [f schemas]
  (with-open [parser (.createParser (ObjectMapper.) (io/file f))]
    (.nextToken parser)                                     ; the opening brace
    (loop [errors []]
      (if-not (= JsonToken/FIELD_NAME (.nextToken parser))
        errors
        (let [k (.currentName parser)]
          (.nextToken parser)
          (if-let [schema (schemas k)]
            (recur (into errors (array-errors parser schema k)))
            (do (.skipChildren parser)
                (recur errors))))))))

(defn validate-json
  "Validate the DMLex JSON file `f` against the 2020-12 schema. Returns the
  first `limit` messages, or an empty vector when the file is valid.

  The file is read twice and never held whole: a skeleton pass for what the
  document says as a whole, then an item pass that judges each entry, relation
  and tag on its own and drops it again. `streaming-obstacle` guards the two
  properties of the schema that this rests on.

  One document defeats the item pass: JSON that names a top-level property
  twice. A whole-document reader keeps the last of them, while a pass that
  goes forward only sees both, so duplicate entries arrays are reported rather
  than shadowed. The DMLex export writes from a map and cannot produce one."
  ([f]
   (validate-json f 25))
  ([f limit]
   (let [root     (.readTree (ObjectMapper.) (io/file json-schema))
         streamed (streamable-properties root)]
     (when-let [obstacle (streaming-obstacle root streamed)]
       (throw (ex-info (str "cannot validate " json-schema " one item at a "
                            "time: " obstacle)
                       {:schema json-schema :obstacle obstacle})))
     (try
       (let [doc        (skeleton f streamed)
             conditions (applied-conditions root doc)
             schemas    (item-schemas root streamed conditions)]
         (->> (concat (map str (.validate (.getSchema (json-schema-factory) ^JsonNode root) doc))
                      (item-errors f schemas))
              (take limit)
              (vec)))
       (catch JsonProcessingException e
         [(str "could not parse the file: " (.getMessage e))])))))

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
  (validate-asserts "export/dmlex/dannet-dmlex-da.xml")
  (validate-dmlex! "export/dmlex/" "da")
  (validate-dmlex! "export/dmlex/" "en")
  #_.)
