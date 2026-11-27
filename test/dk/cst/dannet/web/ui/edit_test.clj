(ns dk.cst.dannet.web.ui.edit-test
  "Tests for the buttons, the mark and the messages of a field in edit mode."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rum.core :as rum]
            [dk.cst.dannet.web.ui.edit :as edit]))

(deftest field-buttons
  (is (= [:edit :remove] (edit/field-buttons false nil))
      "a value as the data has it can be edited and removed")
  (is (= [:edit :undo :remove] (edit/field-buttons false :edited))
      "an edited value can also be put back")
  (is (= [:edit :remove] (edit/field-buttons false :added))
      "an added value has no earlier state, so it is taken away again")
  (is (= [:edit :undo :remove] (edit/field-buttons false :moved))
      "a moved value can be put back in its row, as an edited one can")
  (is (= [:undo] (edit/field-buttons false :retracted))
      "a removed value can only be put back")
  (is (= [] (edit/field-buttons true nil))
      "an input has no buttons of the field")
  (is (= [:undo] (edit/field-buttons true :edited))
      "unless the field differs from the data")
  (is (= [:remove] (edit/field-buttons true :added))
      "and an added value can be taken away from its input too"))

(deftest required-marks
  (let [[_ attrs] (edit/required-mark ["en"] {:min 1 :max 1})]
    (is (= "The row needs exactly one value, so it can be edited but not removed"
           (:title attrs)
           (:aria-label attrs))
        "the tooltip of the mark says why the value stays"))
  (is (= "The row needs at least one value, so the last one can't be removed"
         (edit/required-message ["en"] {:min 1}))))

(deftest node-texts
  (let [opts {:languages ["da"]
              :k->label  {:marl/Negative  #rdf/langstring ["negativ" "da"]
                          :dns/sentiment  #rdf/langstring ["sentiment" "da"]}}]
    (is (= "snakke sammen, snakke ˈsammen"
           (edit/node-text opts :ontolex/otherForm
                           ["snakke sammen" "snakke ˈsammen" "snakke sammen"]))
        "a node is named by its texts, each of them once")
    (is (= "negativ" (edit/node-text opts :dns/sentiment [:marl/Negative]))
        "a resource among its values by its label")
    (is (= "sentiment" (edit/node-text opts :dns/sentiment []))
        "and a node without values by the name of its row")))

(deftest invalid-messages
  (let [description [#rdf/langstring ["Skal være cifre" "da"]
                     #rdf/langstring ["Must be digits" "en"]]]
    (is (= "Skal være cifre"
           (edit/invalid-message ["da" "en"] {:description description}))
        "what a value has to be, in the language of the reader")
    (is (= "Must be digits"
           (edit/invalid-message ["en"] {:description description})))
    (is (= "Not a valid value" (edit/invalid-message ["en"] {}))
        "an input type without a description has a general message")))

(deftest pending-entries
  (let [search  {:results-fn (constantly [])
                 :value-fn   :rdf/about
                 :label-fn   :rdfs/label
                 :hiccup-fn  (fn [{:keys [lemma]} _] [:strong lemma])}
        vuffer  {:pending :dn/new-word-1
                 :label   "\"vuffer\""
                 :kind    {:da "Nyt ord" :en "New word"}}
        vaffer  {:pending :dn/new-word-2
                 :label   "\"vaf vaf\""
                 :kind    {:da "Nyt flerordsudtryk"
                           :en "New multiword expression"}}
        {:keys [results-fn value-fn label-fn hiccup-fn] :as with}
        (edit/with-pending search [vuffer vaffer])]
    (is (= search (edit/with-pending search []))
        "a search stays as it is when there are no new entries")
    (is (= [vuffer vaffer] (results-fn ""))
        "every new entry is listed before a text is typed")
    (is (:list-all? with))
    (is (= [vuffer] (results-fn " VUF "))
        "and then the ones whose label has the text in it")
    (is (= :dn/new-word-1 (value-fn vuffer)))
    (is (= :dn/word-1 (value-fn {:rdf/about :dn/word-1}))
        "a result of the search itself is read as before")
    (is (nil? (label-fn vuffer))
        "the label of a new entry is in the changes")
    (is (= [[:<> [:strong "\"vuffer\""] [:span "Nyt ord"]]
            [:<> [:strong "\"vaf vaf\""] [:span "Nyt flerordsudtryk"]]]
           (map #(hiccup-fn % {:languages ["da" "en"]}) [vuffer vaffer]))
        "a new entry says what it is after its label")
    (is (= [:strong "hund"] (hiccup-fn {:lemma "hund"} {})))))

(deftest removing-a-new-entry
  (let [[_ {:keys [aria-label]} text]
        (edit/discard-entry-button
          {:languages ["da" "en"]
           :k->label  {:dn/new-word-1 "\"vuffer\""}}
          :dn/new-word-1
          identity)]
    (is (= "Fjern den nye ressource" text))
    (is (= "Fjern den nye ressource: \"vuffer\"" aria-label)
        "the name of the button has its text, and says which entry")))

(deftest entries-of-the-data
  (let [removal {:retract #{[:dn/sense-1 :rdf/type :ontolex/LexicalSense]
                            [:dn/synset-1 :ontolex/lexicalizedSense
                             :dn/sense-1]}}
        opts    {:languages ["da" "en"]
                 :subject   :dn/synset-1
                 :k->label  {:dn/sense-1 "hund_1§1"}}
        opts'   (assoc opts :changes [removal])
        html    (fn [{:keys [status] :as props}]
                  (rum/render-static-markup
                    (edit/data-entry-field (if status opts' opts)
                                           :ontolex/lexicalizedSense
                                           :dn/sense-1 props)))]
    (is (str/includes? (html {}) "Fjern hele ressourcen: hund_1§1")
        "the cross of an entry in a row says that the whole entry goes")
    (is (not (str/includes? (html {}) "edit-value__icon--edit"))
        "and the entry is edited on its own page, so the field has no pencil")
    (let [removed (html {:status :retracted})]
      (is (str/includes? removed "Hele ressourcen fjernes")
          "a removed entry says that all of it is removed")
      (is (str/includes? removed "Fortryd fjernelse af: hund_1§1"))
      (is (not (str/includes? removed "Fjern hele ressourcen"))))
    (let [last-one (html {:required {:min 1}})]
      (is (str/includes? last-one "Rækken skal have mindst én værdi"))
      (is (not (str/includes? last-one "Fjern hele ressourcen"))
          "the last entry of a row that needs one has the mark for its cross"))
    (let [[_ {:keys [aria-label]} text]
          (edit/restore-entry-button opts :dn/sense-1 identity)]
      (is (= "Fortryd fjernelsen af ressourcen" text))
      (is (= "Fortryd fjernelsen af ressourcen: hund_1§1" aria-label)
          "the button that puts a removed entry back says which entry"))
    (is (str/includes? (rum/render-static-markup
                         (edit/remove-entry-button opts :dn/sense-1))
                       "aria-label=\"Fjern ressourcen: hund_1§1\"")
        "and so does the button that removes an entry on its own page")
    (testing "a removed link to an entry that is removed as a whole"
      (is (= "Hele ressourcen fjernes"
             (peek (edit/removed-note opts' :dn/sense-1)))
          "says that the whole entry goes, since undoing it puts that back")
      (is (nil? (edit/removed-note opts' :dn/synset-1)))
      (is (nil? (edit/removed-note opts :dn/sense-1))))))
