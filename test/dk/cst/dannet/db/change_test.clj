(ns dk.cst.dannet.db.change-test
  (:require [clojure.test :refer [deftest is testing]]
            [dk.cst.theophrastus :as theo]
            [dk.cst.theophrastus.term :as term]
            [dk.cst.dannet.change :as change]
            [dk.cst.dannet.db.change :as db.change]
            [dk.cst.dannet.test-util :as util]))

(def ^:private g
  (util/ttl->db "
@prefix dn: <https://wordnet.dk/dannet/data/> .
@prefix ontolex: <http://www.w3.org/ns/lemon/ontolex#> .
dn:word-1 a ontolex:Word ;
  ontolex:canonicalForm [ ontolex:writtenRep \"hund\"@da ] .
dn:word-2 ontolex:canonicalForm [ ontolex:writtenRep \"kat\"@da ] ,
                                [ ontolex:writtenRep \"kat\"@da ] ."))

(def ^:private hund #rdf/langstring ["hund" "da"])
(def ^:private hunde #rdf/langstring ["hunde" "da"])

(defn- matches
  "The number of matches that made `change` fail to resolve in g."
  [change]
  (try
    (db.change/resolve-change g change)
    nil
    (catch clojure.lang.ExceptionInfo e
      (:matches (ex-data e)))))

(def ^:private hund->hunde
  {:where   [[:dn/word-1 :ontolex/canonicalForm '?f]]
   :retract #{['?f :ontolex/writtenRep hund]}
   :add     #{['?f :ontolex/writtenRep hunde]}})

(deftest resolving-changes
  (testing "patterns that match once bind the variables"
    (let [{:keys [retract add]} (db.change/resolve-change g hund->hunde)
          [[form _ _]] (seq retract)]
      (is (term/blank-node? form))
      (is (= #{[form :ontolex/writtenRep hund]} retract))
      (is (= #{[form :ontolex/writtenRep hunde]} add))))
  (testing "a plain change stays as it is"
    (is (= {:add #{[:dn/word-1 :ontolex/sense :dn/sense-1]}}
           (db.change/resolve-change
             g {:add #{[:dn/word-1 :ontolex/sense :dn/sense-1]}}))))
  (testing "a :where without variables checks that its triples are there"
    (is (= {:add #{[:dn/word-1 :ontolex/sense :dn/sense-1]}}
           (db.change/resolve-change
             g {:where [[:dn/word-1 :rdf/type :ontolex/Word]]
                :add   #{[:dn/word-1 :ontolex/sense :dn/sense-1]}})))
    (is (= 0 (matches {:where [[:dn/word-2 :rdf/type :ontolex/Word]]
                       :add   #{[:dn/word-2 :ontolex/sense :dn/sense-2]}}))))
  (testing "a stale change matches nothing"
    (is (= 0 (matches {:where   [[:dn/word-1 :ontolex/canonicalForm '?f]]
                       :retract #{['?f :ontolex/writtenRep hunde]}})))
    (is (= 0 (matches {:retract #{[:dn/word-1 :ontolex/sense :dn/sense-9]}}))
        "a retracted triple must exist"))
  (testing "duplicate blank nodes match more than once"
    (is (= 2 (matches {:where   [[:dn/word-2 :ontolex/canonicalForm '?f]]
                       :retract #{['?f :ontolex/writtenRep
                                   #rdf/langstring ["kat" "da"]]}}))))
  (testing "a change with problems fails before it's matched"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Not a valid change"
                          (db.change/resolve-change
                            g {:add #{['?g :ontolex/writtenRep hunde]}})))))

(deftest located-nodes
  (let [form    (-> (theo/entity g :dn/word-1) :ontolex/canonicalForm)
        label   (:rdf/about form)
        pending [{:retract #{[label :ontolex/writtenRep hund]}
                  :add     #{[label :ontolex/writtenRep hunde]}}]
        nodes   {label {:subject  :dn/word-1
                        :property :ontolex/canonicalForm
                        :values   {:ontolex/writtenRep #{hund}}}}
        located (change/locate-nodes pending nodes)]
    (is (= (first pending)
           (db.change/resolve-change g (first located)))
        "a pending change to a blank node resolves to the same triples")))

(deftest applying-changes
  (let [db (util/ttl->db "
@prefix dn: <https://wordnet.dk/dannet/data/> .
@prefix ontolex: <http://www.w3.org/ns/lemon/ontolex#> .
dn:word-1 ontolex:canonicalForm [ ontolex:writtenRep \"hund\"@da ] .")]
    (let [{:keys [where retract]} (db.change/apply-change! db hund->hunde)
          [[form _ value]] (seq retract)]
      (is (nil? where) "it returns the plain change")
      (is (term/blank-node? form))
      (is (= hund value)))
    (is (= [hunde] (map last (theo/triples db '[_ :ontolex/writtenRep _]))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (db.change/apply-change! db hund->hunde))
        "the second time, it's stale")
    (testing "a blank node label in :add makes a node"
      (let [new-form {:add #{[:dn/word-1 :ontolex/otherForm '_form]
                             ['_form :ontolex/writtenRep hund]}}
            node     (fn [{:keys [add]}]
                       (first (keep (fn [[s _ o]]
                                      (when (= s :dn/word-1) o))
                                    add)))
            first-node  (node (db.change/apply-change! db new-form))
            second-node (node (db.change/apply-change! db new-form))]
        (is (term/blank-node? first-node))
        (is (not= '_form first-node) "under a label of its own")
        (is (not= first-node second-node) "and each change makes its own")
        (is (= #{[first-node :ontolex/writtenRep hund]
                 [second-node :ontolex/writtenRep hund]}
               (set (theo/triples db ['_ :ontolex/writtenRep hund]))))))
    (testing "and a node is removed with its values"
      (let [[[_ _ node]] (theo/triples db '[:dn/word-1 :ontolex/canonicalForm _])
            nodes   {node {:subject  :dn/word-1
                           :property :ontolex/canonicalForm
                           :values   {:ontolex/writtenRep #{hunde}}}}
            removal (change/locate-nodes (change/retract-node [] node
                                                              (get nodes node))
                                         nodes)]
        (run! #(db.change/apply-change! db %) removal)
        (is (empty? (theo/triples db '[:dn/word-1 :ontolex/canonicalForm _])))
        (is (empty? (theo/triples db ['_ :ontolex/writtenRep hunde])))))))

(deftest removals
  (let [db     (util/ttl->db "
@prefix dn: <https://wordnet.dk/dannet/data/> .
@prefix dns: <https://wordnet.dk/dannet/schema/> .
@prefix ontolex: <http://www.w3.org/ns/lemon/ontolex#> .
@prefix owl: <http://www.w3.org/2002/07/owl#> .
@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
@prefix wn: <https://globalwordnet.github.io/schemas/wn#> .
dn:word-1 a ontolex:Word ;
  ontolex:canonicalForm [ ontolex:writtenRep \"hund\"@da ] ;
  ontolex:evokes dn:synset-1 ;
  ontolex:sense dn:sense-1 .
dn:word-2 a ontolex:Word ;
  ontolex:sense dn:sense-2 , dn:sense-3 .
dn:synset-1 a ontolex:LexicalConcept ;
  ontolex:lexicalizedSense dn:sense-1 , dn:sense-2 .
dn:synset-2 wn:hypernym dn:synset-1 ;
  dns:inherited [ dns:inheritedFrom dn:synset-1 ;
                  dns:inheritedRelation wn:mero_part ] ,
                [ dns:inheritedFrom dn:synset-1 , dn:synset-3 ] .
dn:sense-1 a ontolex:LexicalSense ;
  rdfs:label \"hund\"@da .
[] owl:sourceIndividual dn:synset-1 .")
        rules  {:links   #{:ontolex/sense :ontolex/lexicalizedSense}
                :derived #{:ontolex/evokes}}
        sense  #{[:dn/sense-1 :rdf/type :ontolex/LexicalSense]
                 [:dn/sense-1 :rdfs/label hund]
                 [:dn/word-1 :ontolex/sense :dn/sense-1]
                 [:dn/synset-1 :ontolex/lexicalizedSense :dn/sense-1]}
        removal #(db.change/removal db % rules)
        synset (removal :dn/synset-1)
        word   (removal :dn/word-1)]
    (testing "a sense goes with the links to it"
      (is (= sense (:entry (removal :dn/sense-1)))
          "every triple that names it, as a subject or as an object")
      (is (empty? (:references (removal :dn/sense-1))))
      (is (= [:dn/word-1] (keys (:orphans (removal :dn/sense-1))))
          "and its word is an orphan, since the word has no other sense")
      (is (empty? (:orphans (removal :dn/sense-2)))
          "a word and a synset with another sense aren't orphans")
      (is (nil? (removal :dn/sense-9))
          "nothing removes an entry that the graph doesn't name"))
    (testing "a synset goes with its senses and with what the build wrote"
      (is (= (conj sense
                   [:dn/synset-1 :rdf/type :ontolex/LexicalConcept]
                   [:dn/synset-1 :ontolex/lexicalizedSense :dn/sense-2]
                   [:dn/word-2 :ontolex/sense :dn/sense-2]
                   [:dn/word-1 :ontolex/evokes :dn/synset-1])
             (:entry synset)))
      (is (= [:dn/word-1] (keys (:orphans synset)))))
    (testing "the references to a synset are counted, not listed"
      (is (= {:wn/hypernym 1 :dns/inheritedFrom 2 :owl/sourceIndividual 1}
             (:references synset))
          "by the property that they point to it with")
      (is (not-any? #(= :wn/hypernym (second %)) (:entry synset)))
      (is (= {:dn/synset-1 (:references synset)}
             (db.change/references db [:dn/synset-1 :dn/sense-1] rules))
          "also on their own, for each resource that something points to")
      (is (= {:dn/synset-1 {:owl/sourceIndividual 1}}
             (db.change/references db [:dn/synset-1 :dn/synset-2] rules))
          "a resource that goes too doesn't refer to it, nor do the blank
          nodes that it holds"))
    (testing "a word goes with its senses and its blank nodes"
      (let [{:keys [entry nodes orphans]} word
            [form place] (first nodes)
            change {:retract (into entry (:entry (get orphans :dn/synset-1)))}]
        (is (= {:subject  :dn/word-1
                :property :ontolex/canonicalForm
                :values   {:ontolex/writtenRep #{hund}}}
               place))
        (is (= (conj sense
                     [:dn/word-1 :rdf/type :ontolex/Word]
                     [:dn/word-1 :ontolex/canonicalForm form]
                     [form :ontolex/writtenRep hund]
                     [:dn/word-1 :ontolex/evokes :dn/synset-1])
               entry))
        (is (empty? orphans)
            "its synset isn't an orphan, since the synset has another sense")
        (is (= 1 (count nodes)))
        (run! #(db.change/apply-change! db %)
              (change/locate-nodes [{:retract entry}] nodes))
        (is (empty? (theo/triples db '[:dn/word-1 _ _])))
        (is (empty? (theo/triples db ['_ :ontolex/writtenRep hund])))
        (is (empty? (theo/triples db '[:dn/sense-1 _ _])))
        (is (seq (theo/triples db '[:dn/synset-1 _ _]))
            "and the synset of a sense stays, without the sense")))
    (testing "a change that unreferences a resource retracts what refers"
      (let [made (db.change/apply-change!
                   db {:retract     #{[:dn/synset-1 :rdf/type
                                       :ontolex/LexicalConcept]}
                       :unreference #{:dn/synset-1}})]
        (is (contains? (:retract made) [:dn/synset-2 :wn/hypernym :dn/synset-1])
            "the change that was made names the triples")
        (is (not (contains? made :unreference)))
        (is (empty? (theo/triples db '[_ _ :dn/synset-1]))
            "nothing points to the resource any more")
        (is (= [:dn/synset-3]
               (map peek (theo/triples db '[_ :dns/inheritedFrom _])))
            "a blank node that points elsewhere too keeps that value")
        (is (= 1 (count (theo/triples db '[:dn/synset-2 :dns/inherited _])))
            "and one that only pointed to the resource is gone")
        (is (empty? (theo/triples db '[_ :dns/inheritedRelation _])))
        (is (empty? (theo/triples db '[_ :owl/sourceIndividual _]))
            "also one that nothing holds")
        (is (= {:add #{[:dn/synset-9 :wn/hypernym :dn/synset-8]}}
               (db.change/apply-change!
                 db {:add         #{[:dn/synset-9 :wn/hypernym :dn/synset-8]}
                     :unreference #{:dn/synset-7}}))
            "a resource that nothing refers to is no problem")))))

(deftest many-patterns
  (let [db      (util/ttl->db "")
        triples (set (for [n (range 450)]
                       [:dn/synset-1 :wn/hypernym
                        (keyword "dn" (str "synset-" (+ 2 n)))]))]
    (db.change/apply-change! db {:add triples})
    (is (= {:retract triples}
           (db.change/resolve-change db {:retract triples}))
        "the triples of a large removal are looked up in parts")
    (is (thrown? clojure.lang.ExceptionInfo
                 (db.change/resolve-change
                   db {:retract (conj triples [:dn/synset-1 :wn/hypernym
                                               :dn/synset-999])}))
        "and one that is missing still fails the change")))
