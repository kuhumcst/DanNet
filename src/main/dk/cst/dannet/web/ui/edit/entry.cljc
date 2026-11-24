(ns dk.cst.dannet.web.ui.edit.entry
  "The new entries of edit mode as data: the change that makes one, and what
  the pending changes say about it. A new entry goes by a temporary ID until
  a curator accepts it (see dk.cst.dannet.change).

  A sense is the link between a word and a synset: the word points to it
  with ontolex:sense and the synset with ontolex:lexicalizedSense. So a word
  joins a synset through a new sense."
  (:require [dk.cst.dannet.change :as change]
            [dk.cst.dannet.prefix :as prefix]))

(defn new-sense
  "The change that makes a new sense of the `word` in the `synset`, with
  the `label`. The sense gets a temporary ID that the pending `changes`
  don't have yet."
  [changes synset word label]
  (let [sense (change/temporary-id changes "sense")]
    {:add #{[sense :rdf/type :ontolex/LexicalSense]
            [sense :rdfs/label label]
            [word :ontolex/sense sense]
            [synset :ontolex/lexicalizedSense sense]}}))

(defn label
  "The label that the pending `changes` give the new entry with the `id`,
  or nil when they give it none."
  [changes id]
  (some (fn [[s p o]]
          (when (and (= s id) (= p :rdfs/label))
            o))
        (:add (change/net changes))))

(defn labels
  "The label of each new entry that the pending `changes` give one, as a
  map of its temporary ID to the label."
  [changes]
  (into {}
        (keep (fn [[s p o]]
                (when (and (= p :rdfs/label) (change/temporary? s))
                  [s o])))
        (:add (change/net changes))))

(def inverses
  "The property that a new entry shows a link to itself under, for each
  property that can point to one. A sense shows its word and its synset, as
  the page of a sense in the data does."
  {:ontolex/sense            :ontolex/isSenseOf
   :ontolex/lexicalizedSense :ontolex/isLexicalizedSenseOf})

(defn entity
  "The new entry with the `id` as the pending `changes` leave it: its own
  values, and the resources that point to it, under the inverse of the
  property that they do it with (see inverses). Nil when the changes say
  nothing about the entry itself."
  [changes id]
  (let [{:keys [add]} (change/net changes)
        own (reduce (fn [entity [s p o]]
                      (if (= s id)
                        (update entity p (fnil conj #{}) o)
                        entity))
                    {}
                    add)]
    (when (seq own)
      (->> (reduce (fn [entity [s p o]]
                     (if-let [inverse (and (= o id) (get inverses p))]
                       (update entity inverse (fnil conj #{}) s)
                       entity))
                   own
                   add)
           ;; a single value stands alone, as in the entity of a page
           (into {} (map (fn [[p vs]]
                           [p (if (next vs) vs (first vs))])))))))

(defn page-data
  "The data of the page of the new entry with the `id`, as the page of an
  entry in the data has it, when the server has given the :constraints and
  :entities of its type as `content`. The page is a :draft?, so that its
  entity is made from the pending changes each time it is drawn."
  [id content]
  (assoc content
    :subject id
    :draft? true
    ;; the graph that changes go to holds what the entry will have
    :default-origin prefix/dn-graph
    ;; a link to the entry isn't a value of the entry itself
    :origins (zipmap (vals inverses) (repeat :inferred))))

(defn sense-words
  "The words that the pending `changes` give a new sense in the `synset`,
  as a set."
  [changes synset]
  (let [{:keys [add]} (change/net changes)
        senses (into #{}
                     (keep (fn [[s p o]]
                             (when (and (= s synset)
                                        (= p :ontolex/lexicalizedSense)
                                        (change/temporary? o))
                               o)))
                     add)]
    (into #{}
          (keep (fn [[s p o]]
                  (when (and (= p :ontolex/sense) (contains? senses o))
                    s)))
          add)))
