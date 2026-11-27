(ns dk.cst.dannet.web.ui.edit.entry-test
  "Tests for the fields and the buttons of an entry in edit mode."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [rum.core :as rum]
            [dk.cst.dannet.web.ui.edit.entry :as entry-field]))

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
        (entry-field/with-pending search [vuffer vaffer])]
    (is (= search (entry-field/with-pending search []))
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
        (entry-field/discard-entry-button
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
                    (entry-field/data-entry-field (if status opts' opts)
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
          (entry-field/restore-entry-button opts :dn/sense-1 identity)]
      (is (= "Fortryd fjernelsen af ressourcen" text))
      (is (= "Fortryd fjernelsen af ressourcen: hund_1§1" aria-label)
          "the button that puts a removed entry back says which entry"))
    (is (str/includes? (rum/render-static-markup
                         (entry-field/remove-entry-button opts :dn/sense-1))
                       "aria-label=\"Fjern ressourcen: hund_1§1\"")
        "and so does the button that removes an entry on its own page")))
