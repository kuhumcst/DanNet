(ns dk.cst.dannet.db.export.appledict
  "Apple Dictionary.app export of DanNet.

  Consumes the DMLex intermediate structure of dk.cst.dannet.db.export.dmlex
  and serializes it as a Dictionary Development Kit source project: the
  d:dictionary XML, a CSS file, an Info.plist and a Makefile. Building the
  final .dictionary bundle requires the DDK from Apple's Additional Tools for
  Xcode, and therefore a Mac: run `make && make install` in the export dir.

  The XML mixes a default XHTML namespace with d:-prefixed elements in the
  exact shape that the DDK scripts and WebKit expect, so the emitter is a
  small string-based hiccup renderer rather than clojure.data.xml, whose
  namespace-aware emission cannot reproduce that shape verbatim."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [dk.cst.dannet.db.export.dmlex :as dmlex]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.release :as release]))

;; -----------------------------------------------------------------------------
;; XML emission

(defn escape
  "Escape `s` for use as XML text content or an attribute value."
  [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn xml-name
  "The XML name of the keyword `k`; its namespace becomes an XML prefix,
  e.g. :d/entry -> d:entry."
  [k]
  (if-let [prefix (namespace k)]
    (str prefix ":" (name k))
    (name k)))

(defn hiccup->xml
  "Render the hiccup `x` — nil, a string, a [tag attrs? & children] vector or
  a seq of hiccup — as an XML string. A nil child or attribute renders as
  nothing, and a childless element self-closes. Only a vector opening with a
  keyword is an element; any other sequential value is a seq of hiccup."
  [x]
  (cond
    (nil? x) ""
    (string? x) (escape x)
    (and (vector? x)
         (keyword? (first x))) (let [[tag & more] x
                                     [attrs children] (if (map? (first more))
                                                        [(first more) (rest more)]
                                                        [nil more])
                                     attrs' (str/join (for [[k v] attrs
                                                            :when (some? v)]
                                                        (str " " (xml-name k)
                                                             "=\"" (escape v) "\"")))]
                                 (if (empty? children)
                                   (str "<" (xml-name tag) attrs' "/>")
                                   (str "<" (xml-name tag) attrs' ">"
                                        (str/join (map hiccup->xml children))
                                        "</" (xml-name tag) ">")))
    (seqable? x) (str/join (map hiccup->xml x))
    :else (escape x)))

;; -----------------------------------------------------------------------------
;; Display names

(def pos-da
  "DMLex partOfSpeech tag -> Danish display name."
  (into {} (map (juxt :tag :description)) dmlex/part-of-speech-tags))

(def role-da
  "Relation member role -> Danish display key. An untranslated role keeps its
  name, which is what the lexicographers know it by."
  {"synonym"  "synonymer"
   "hypernym" "overbegreb"
   "hyponym"  "underbegreber"})

(def label-key-da
  "DMLex labelTypeTag -> Danish display key, in display order. Label types
  outside this map (the synset identity and the sentiment pair, which get
  special treatment) do not render as plain labels."
  [["register" "register"]
   ["temporal" "datering"]
   ["frequency" "frekvens"]
   ["usage" "brug"]
   ["domain" "fagområde"]
   ["lexfile" "semantisk felt"]
   ["ontologicalType" "ontologisk type"]
   ["gender" "køn"]])

(def polarity-da
  {"Positive" "positiv"
   "Neutral"  "neutral"
   "Negative" "negativ"})

;; -----------------------------------------------------------------------------
;; Entry rendering

(defn ->context
  "Precompute the lookups over the DMLex `resource` that entry rendering
  needs: sense id -> owning entry, sense id -> its relations, label tag ->
  labelTypeTag, and inflection code -> description."
  [{:keys [entries relations labelTags inflectedFormTags]}]
  {:entry-of     (into {} (for [{:keys [id headword senses]} entries
                                sense senses]
                            [(:id sense) {:id id :headword headword}]))
   :relations-of (reduce (fn [m {:keys [members] :as relation}]
                           (reduce #(update %1 (:ref %2) (fnil conj []) relation)
                                   m
                                   members))
                         {}
                         relations)
   :type-of      (into {} (map (juxt :tag :typeTag)) labelTags)
   :desc-of      (into {} (map (juxt :tag :description)) inflectedFormTags)})

(defn ->index
  "The d:index elements of one entry: the `headword` plus every distinct
  inflected form and variant in `forms`, which redirect to the headword."
  [headword forms]
  (cons [:d/index {:d/value headword}]
        (for [text (distinct (map :text forms))
              :when (not= text headword)]
          [:d/index {:d/value text :d/title (str text " (" headword ")")}])))

(defn ->labels
  "The label dl of a sense or entry: its `labels` grouped by labelTypeTag via
  `type-of`, rendered in the fixed order of `label-key-da`, followed by the
  sentiment and a link to the synset page on wordnet.dk."
  [{:keys [type-of]} labels]
  (let [by-type   (group-by type-of labels)
        sentiment (when-let [[polarity] (by-type "sentiment")]
                    (str (polarity-da polarity polarity)
                         (when-let [[value] (by-type "sentimentValue")]
                           (str " (" value ")"))))
        synset    (first (by-type "synset"))
        rows      (concat
                    (for [[type-tag da-key] label-key-da
                          :let [values (by-type type-tag)]
                          :when (seq values)]
                      [da-key (map #(vector :dd %) values)])
                    (when sentiment
                      [["sentiment" [[:dd sentiment]]]])
                    (when synset
                      [["synset" [[:dd [:a {:href (str prefix/dn-uri synset)}
                                        synset]]]]]))]
    (when (seq rows)
      [:div {:class "labels" :d/priority "2"}
       [:dl (for [[da-key dds] rows]
              [:div [:dt da-key] dds])]])))

(defn relation-links
  "The relations of the sense `id` as [da-key links] pairs: every other member
  of each relation, grouped by its role and rendered as an x-dictionary link
  to its owning entry. Synonyms come first; a member whose sense is not in the
  export, or who shares the entry `eid`, is dropped."
  [{:keys [entry-of relations-of]} eid id]
  (let [others (for [{:keys [members]} (relations-of id)
                     {:keys [ref role]} members
                     :let [target (entry-of ref)]
                     :when (and target
                                (not= ref id)
                                (not= (:id target) eid))]
                 [role target])]
    (for [[role pairs] (sort-by (fn [[role]] [(if (= role "synonym") 0 1) role])
                                (group-by first others))]
      [(role-da role role)
       (for [{:keys [id headword]} (distinct (map second pairs))]
         [:dd [:a {:href (str "x-dictionary:r:" id)} headword]])])))

(defn ->sense
  "One li.sense of the entry `eid`: indicator, definitions, examples, labels
  and relations, everything but the indicator and definition at d:priority 2
  so that the small Look Up panel shows only the core."
  [ctx eid {:keys [id indicator labels definitions examples]}]
  [:li {:class "sense"}
   (when indicator
     (list [:span {:class "indicator"} indicator]
           [:span {:class "sep"} "·"]))
   [:span {:class "definition"}
    (str/join "; " (map :text definitions))]
   (for [{:keys [text sourceIdentity]} examples]
     [:div {:class "example" :d/priority "2"}
      [:q text]
      (when sourceIdentity [:cite sourceIdentity])])
   (->labels ctx labels)
   (when-let [rows (seq (relation-links ctx eid id))]
     [:div {:class "relations" :d/priority "2"}
      [:dl (for [[da-key dds] rows]
             [:div [:dt da-key] dds])]])])

(defn ->inflections
  "The inflected `forms` of an entry as one comma-separated line, each form
  with its inflection-code description via `desc-of` as a hover title and
  the unormeret class when it falls outside the spelling norm."
  [{:keys [desc-of]} forms]
  (when (seq forms)
    [:div {:class "inflections" :d/priority "2"}
     [:dl [:div [:dt "bøjning"]
           (for [{:keys [text tag labels]} forms]
             [:dd [:span {:class (when (seq labels) "unormeret")
                          :title (desc-of tag)}
                   text]])]]]))

(defn ->entry
  "One d:entry of the DMLex `entry`, rendered with the lookups in `ctx`."
  [ctx {:keys [id headword homographNumber partsOfSpeech labels
               inflectedForms senses]}]
  [:d/entry {:id id :d/title headword}
   (->index headword inflectedForms)
   [:h1 {:class "headword"}
    headword
    (when homographNumber [:sup {:class "hom"} homographNumber])]
   (when-let [[pos] partsOfSpeech]
     [:p {:class "pos"} (pos-da pos pos)])
   (->inflections ctx inflectedForms)
   (->labels ctx labels)
   [:ol {:class (if (next senses) "senses" "senses single")}
    (map (partial ->sense ctx id) senses)]])

(def front-matter
  "The front matter entry of the dictionary: about, sources and licence.
  Info.plist points at it via DCSDictionaryFrontMatterReferenceID."
  [:d/entry {:id "front_back_matter" :d/title "Om DanNet"}
   [:d/index {:d/value "DanNet"}]
   [:div {:class "front-matter"}
    [:h1 "DanNet"]
    [:p "Det danske WordNet, kombineret med bøjningsformer fra COR og "
     "sentiment-annoteringer fra DDS. En bøjningsform markeret med * ligger "
     "uden for retskrivningsnormen."]
    [:p "DanNet og DDS er udgivet under CC BY-SA 4.0, COR under CC0. "
     "Det samlede datasæt: "
     [:a {:href "https://creativecommons.org/licenses/by-sa/4.0/"}
      "CC BY-SA 4.0"]
     ". Ophavsret © Det Danske Sprog- og Litteraturselskab (DSL) og Center "
     "for Sprogteknologi (CST), Københavns Universitet."]
    [:p [:a {:href "https://wordnet.dk"} "wordnet.dk"]]]])

;; -----------------------------------------------------------------------------
;; Project files

(def xml-preamble
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
       "<d:dictionary xmlns=\"http://www.w3.org/1999/xhtml\" "
       "xmlns:d=\"http://www.apple.com/DTDs/DictionaryService-1.0.rng\">\n"))

(defn write-xml!
  "Stream the d:dictionary XML of the DMLex `resource` to `file`."
  [file resource]
  (let [ctx (->context resource)]
    (with-open [w (io/writer file)]
      (.write w xml-preamble)
      (.write w (hiccup->xml front-matter))
      (.write w "\n")
      (doseq [entry (:entries resource)]
        (.write w (hiccup->xml (->entry ctx entry)))
        (.write w "\n"))
      (.write w "</d:dictionary>\n"))))

(defn info-plist
  "The Info.plist of the DanNet `version` dictionary bundle."
  [version]
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">
<plist version=\"1.0\">
<dict>
	<key>CFBundleDevelopmentRegion</key>
	<string>Danish</string>
	<key>CFBundleDisplayName</key>
	<string>DanNet</string>
	<key>CFBundleIdentifier</key>
	<string>dk.cst.dannet.dictionary</string>
	<key>CFBundleName</key>
	<string>DanNet</string>
	<key>CFBundleShortVersionString</key>
	<string>" version "</string>
	<key>DCSDictionaryCopyright</key>
	<string>Copyright © DSL &amp; CST, Københavns Universitet. CC BY-SA 4.0.</string>
	<key>DCSDictionaryManufacturerName</key>
	<string>Center for Sprogteknologi, Københavns Universitet</string>
	<key>DCSDictionaryFrontMatterReferenceID</key>
	<string>front_back_matter</string>
	<key>DCSDictionaryLanguages</key>
	<array>
		<dict>
			<key>DCSDictionaryDescriptionLanguage</key>
			<string>da</string>
			<key>DCSDictionaryIndexLanguage</key>
			<string>da</string>
		</dict>
	</array>
</dict>
</plist>
"))

(defn makefile
  "The Makefile of the export dir, pointing at the DDK in `ddk-dir`."
  [ddk-dir]
  (str "DICT_NAME\t\t=\t\"DanNet\"
DICT_SRC_PATH\t\t=\tDanNet.xml
CSS_PATH\t\t=\tDanNet.css
PLIST_PATH\t\t=\tDanNet-Info.plist
DICT_BUILD_OPTS\t\t=
DICT_BUILD_TOOL_DIR\t=\t\"" ddk-dir "\"
DICT_BUILD_TOOL_BIN\t=\t\"$(DICT_BUILD_TOOL_DIR)/bin\"
DICT_DEV_KIT_OBJ_DIR\t=\t./objects
export\tDICT_DEV_KIT_OBJ_DIR
DESTINATION_FOLDER\t=\t~/Library/Dictionaries

all:
\t\"$(DICT_BUILD_TOOL_BIN)/build_dict.sh\" $(DICT_BUILD_OPTS) $(DICT_NAME) $(DICT_SRC_PATH) $(CSS_PATH) $(PLIST_PATH)
\techo \"Done.\"

install:
\tmkdir -p $(DESTINATION_FOLDER)
\tditto --noextattr --norsrc $(DICT_DEV_KIT_OBJ_DIR)/$(DICT_NAME).dictionary $(DESTINATION_FOLDER)/$(DICT_NAME).dictionary
\ttouch $(DESTINATION_FOLDER)
\techo \"Done.\"

clean:
\t/bin/rm -rf $(DICT_DEV_KIT_OBJ_DIR)
"))

(defn export-appledict!
  "Export the DMLex `resource` as an Apple Dictionary source project in `dir`,
  with a Makefile pointing at the Dictionary Development Kit in `ddk-dir`.
  Build and install the .dictionary bundle with `make && make install`."
  [dir resource ddk-dir]
  (println "Beginning Apple Dictionary export of DanNet into" dir)
  (let [xml-file (str dir "DanNet.xml")]
    (io/make-parents xml-file)
    (write-xml! xml-file resource)
    (spit (str dir "DanNet.css") (slurp (io/resource "export/appledict/DanNet.css")))
    (spit (str dir "DanNet-Info.plist") (info-plist release/to))
    (spit (str dir "Makefile") (makefile ddk-dir)))
  (println "Apple Dictionary export of DanNet complete!"))

(comment
  ;; Reuses the DMLex intermediate resource, cf. the comment block in
  ;; dk.cst.dannet.db.export.dmlex.
  (def resource
    (dmlex/->resource (dmlex/run-queries @dk.cst.dannet.web.instance/db)))

  (hiccup->xml (->entry (->context resource) (first (:entries resource))))

  (export-appledict! "export/appledict/" resource
                     "/Library/Developer/Extras/Dictionary Development Kit")
  #_.)
