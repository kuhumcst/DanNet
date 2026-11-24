(ns dk.cst.dannet.web.ui.edit.entry
  "The new entries of edit mode as data: the change that makes one, and what
  the pending changes say about it. A new entry goes by a temporary ID until
  a curator accepts it (see dk.cst.dannet.change).

  A sense is the link between a word and a synset: the word points to it
  with ontolex:sense and the synset with ontolex:lexicalizedSense. So a word
  joins a synset through a new sense."
  (:require [dk.cst.dannet.change :as change]))

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
