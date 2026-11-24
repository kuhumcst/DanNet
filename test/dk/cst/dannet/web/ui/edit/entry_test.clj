(ns dk.cst.dannet.web.ui.edit.entry-test
  "Tests for the templates that make new entries, and for what the pending
  changes say about a new entry."
  (:require [clojure.test :refer [deftest is testing]]
            [dk.cst.dannet.change :as change]
            [dk.cst.dannet.editable :as editable]
            [dk.cst.dannet.web.ui.edit.entry :as entry]
            [dk.cst.dannet.web.ui.input-type :as input-type]))

(def vovse #rdf/langstring ["vovse" "da"])

(def templates
  (:entries editable/rules))

(def sense
  (:ontolex/LexicalSense templates))

(def inverses
  {:ontolex/sense            :ontolex/isSenseOf
   :ontolex/lexicalizedSense :ontolex/isLexicalizedSenseOf
   :ontolex/evokes           :ontolex/isEvokedBy})

(defn new-sense
  "The change that fills the template of a sense for the `word` in the
  `synset`, with the `label`."
  [changes synset word label]
  (entry/fill sense changes {'?synset synset '?word word '?label label}))

(deftest rows
  (is (= {:template sense :subject '?synset}
         (entry/for-row templates :ontolex/lexicalizedSense))
      "the senses of a synset are new senses, and the synset fills its gap")
  (is (= '?word (:subject (entry/for-row templates :ontolex/sense)))
      "one template covers the senses of a word too")
  (is (nil? (entry/for-row templates :wn/hypernym)))
  (is (nil? (entry/for-row templates :rdfs/label))
      "a gap that isn't a new entry doesn't make its row take entries"))

(deftest questions
  (is (= '{?word :ontolex/Word} (entry/asked sense '[?synset]))
      "the page fills its own gap, and the editor asks for the other")
  (is (= '{?synset :ontolex/LexicalConcept} (entry/asked sense '[?word])))
  (is (empty? (entry/asked sense '[?word ?synset])))
  (is (every? (fn [{:keys [new add] :as template}]
                (every? (fn [[s _ o]]
                          (or (not (contains? new o))
                              (= 1 (count (entry/asked template [s])))))
                        add))
              (vals templates))
      "every row of a template leaves one gap to ask for")
  (is (every? #(:search (get input-type/class-input-types %))
              (mapcat (comp vals :ask) (vals templates)))
      "every class that a template asks for has a search")
  (testing "a value that starts as a value of another gap"
    (let [quoted #rdf/langstring ["\"vovse\"" "da"]]
      (is (= {'?label vovse}
             (entry/starting sense {'?word {:rdfs/label quoted}}))
          "the label of a sense is the label of its word without the quotes")
      (is (= {'?label vovse}
             (entry/starting sense {'?word {:rdfs/label #{quoted}}})))
      (is (= {} (entry/starting sense {'?word {:rdfs/label vovse}}))
          "none when the pattern doesn't match")
      (is (= {} (entry/starting sense {'?synset {:rdfs/label quoted}}))))))

(deftest filling
  (let [added   (new-sense [] :dn/synset-1 :dn/word-1 vovse)
        changes [added
                 {:add #{[:dn/synset-1 :skos/definition "et dyr"]}}]
        second  (new-sense changes :dn/synset-2 :dn/word-2 vovse)]
    (is (= {:add #{[:dn/new-sense-1 :rdf/type :ontolex/LexicalSense]
                   [:dn/new-sense-1 :rdfs/label vovse]
                   [:dn/word-1 :ontolex/sense :dn/new-sense-1]
                   [:dn/synset-1 :ontolex/lexicalizedSense :dn/new-sense-1]}}
           added)
        "a sense is the link between a word and a synset")
    (is (empty? (change/problems added)))
    (is (contains? (:add second)
                   [:dn/synset-2 :ontolex/lexicalizedSense :dn/new-sense-2])
        "under an ID that the pending changes don't have yet")
    (is (= 3 (count (:add (new-sense [] :dn/synset-1 :dn/word-1 nil))))
        "a triple with a gap that has no value is left out")
    (testing "the label of a new entry is the one that the changes leave it"
      (is (= {:dn/new-sense-1 vovse} (entry/labels changes)))
      (is (= {:dn/new-sense-1 "vovse_§2"}
             (entry/labels (change/replace
                             changes
                             [:dn/new-sense-1 :rdfs/label vovse]
                             [:dn/new-sense-1 :rdfs/label "vovse_§2"]))))
      (is (= {} (entry/labels [{:add #{[:dn/synset-1 :rdfs/label "{x}"]}}]))
          "an entry in the data has its label there"))
    (testing "the other ends of the new entries of a page"
      (let [words   #(entry/linked sense %1 '?synset %2 '?word)
            synsets #(entry/linked sense %1 '?word %2 '?synset)]
        (is (= #{:dn/word-1} (words changes :dn/synset-1)))
        (is (= #{} (words changes :dn/synset-2)))
        (is (= #{:dn/word-2} (words (conj changes second) :dn/synset-2)))
        (is (= #{:dn/synset-1} (synsets changes :dn/word-1))
            "from the page of the word too")
        (is (= #{}
               (words (change/without-node changes :dn/new-sense-1)
                      :dn/synset-1))
            "not one whose new sense is taken away")))
    (testing "the other ends that the data has already"
      (is (= #{:dn/word-1 :dn/word-2}
             (entry/known sense inverses
                          {:ontolex/isEvokedBy #{:dn/word-1 :dn/word-2}}
                          '?synset '?word))
          "a link that points to the page is read under its inverse")
      (is (= #{:dn/synset-1}
             (entry/known sense inverses {:ontolex/evokes :dn/synset-1}
                          '?word '?synset)))
      (is (= #{} (entry/known sense inverses {} '?word '?synset)))
      (is (= #{} (entry/known (dissoc sense :once) inverses
                              {:ontolex/evokes :dn/synset-1}
                              '?word '?synset))))
    (is (= #{:rdf/type :rdfs/label :ontolex/sense :ontolex/lexicalizedSense
             :ontolex/evokes}
           (entry/properties templates)))))

(deftest pages-of-new-entries
  (let [example [:dn/new-sense-1 :lexinfo/senseExample "en vovse"]
        changes [(new-sense [] :dn/synset-1 :dn/word-1 vovse)
                 {:add #{example}}
                 {:add #{[:dn/new-sense-1 :lexinfo/senseExample "to vovser"]}}
                 {:add #{[:dn/synset-1 :skos/definition "et dyr"]}}]]
    (testing "a new entry is what the changes say about it"
      (is (= {:rdf/type                     :ontolex/LexicalSense
              :rdfs/label                   vovse
              :lexinfo/senseExample         #{"en vovse" "to vovser"}
              :ontolex/isSenseOf            :dn/word-1
              :ontolex/isLexicalizedSenseOf :dn/synset-1}
             (entry/entity inverses changes :dn/new-sense-1))
          "with the word and the synset that point to it")
      (is (= :ontolex/LexicalSense
             (:rdf/type (entry/entity nil changes :dn/new-sense-1)))
          "its own values need no inverses")
      (is (= "en vovse"
             (:lexinfo/senseExample
               (entry/entity inverses
                             (change/without-addition
                               changes
                               [:dn/new-sense-1 :lexinfo/senseExample
                                "to vovser"])
                             :dn/new-sense-1)))
          "a single value stands alone")
      (is (nil? (entry/entity inverses changes :dn/new-sense-2)))
      (is (nil? (entry/entity inverses
                              (change/without-node changes :dn/new-sense-1)
                              :dn/new-sense-1))
          "an entry that is taken away is gone"))
    (testing "the page has what the server knows of the type"
      (let [content {:constraints {:rdfs/label {:must {:min 1}}}
                     :entities    {:rdfs/label {:rdfs/label #{"label"}}}
                     :inverses    inverses}
            data    (entry/page-data :dn/new-sense-1 content)]
        (is (= content (select-keys data [:constraints :entities :inverses])))
        (is (= :dn/new-sense-1 (:subject data)))
        (is (:draft? data))
        (is (some? (:default-origin data))
            "so that its values can be edited")
        (is (= :inferred (get-in data [:origins :ontolex/isSenseOf]))
            "a link to the entry is locked, as on the page of a sense")
        (is (nil? (get-in data [:origins :ontolex/sense]))
            "but a link from the entry is a value of its own")))))
