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
  (let [db    (util/ttl->db "
@prefix dn: <https://wordnet.dk/dannet/data/> .
@prefix ontolex: <http://www.w3.org/ns/lemon/ontolex#> .
@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
dn:word-1 a ontolex:Word ;
  ontolex:canonicalForm [ ontolex:writtenRep \"hund\"@da ] ;
  ontolex:sense dn:sense-1 , dn:sense-2 .
dn:synset-1 ontolex:lexicalizedSense dn:sense-1 .
dn:sense-1 a ontolex:LexicalSense ;
  rdfs:label \"hund\"@da .")
        sense (db.change/removal db :dn/sense-1)
        {:keys [change nodes] :as word} (db.change/removal db :dn/word-1)
        [form place] (first nodes)]
    (is (= {:change {:retract #{[:dn/sense-1 :rdf/type :ontolex/LexicalSense]
                                [:dn/sense-1 :rdfs/label hund]
                                [:dn/word-1 :ontolex/sense :dn/sense-1]
                                [:dn/synset-1 :ontolex/lexicalizedSense
                                 :dn/sense-1]}}
            :nodes  {}}
           sense)
        "every triple that names the entry, as a subject or as an object")
    (is (nil? (db.change/removal db :dn/sense-9))
        "nothing removes an entry that the graph doesn't name")
    (testing "a blank node of the entry goes with it, found by its place"
      (is (= {:subject  :dn/word-1
              :property :ontolex/canonicalForm
              :values   {:ontolex/writtenRep #{hund}}}
             place))
      (is (= #{[:dn/word-1 :rdf/type :ontolex/Word]
               [:dn/word-1 :ontolex/canonicalForm form]
               [form :ontolex/writtenRep hund]
               [:dn/word-1 :ontolex/sense :dn/sense-1]
               [:dn/word-1 :ontolex/sense :dn/sense-2]}
             (:retract change)))
      (run! #(db.change/apply-change! db %)
            (change/locate-nodes [change] nodes))
      (is (empty? (theo/triples db '[:dn/word-1 _ _])))
      (is (empty? (theo/triples db ['_ :ontolex/writtenRep hund])))
      (is (= 2 (count (theo/triples db '[:dn/sense-1 _ _])))
          "and a sense that it pointed to stays"))))
