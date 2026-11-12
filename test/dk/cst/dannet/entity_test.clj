(ns dk.cst.dannet.entity-test
  "Tests for the reduction of entity data for display."
  (:require [clojure.test :refer [deftest is]]
            [dk.cst.dannet.entity :as ent]))

(deftest pruned-blank-nodes
  (let [origins {:origins        {:ontolex/representation :inferred}
                 :default-origin :dn}
        form    (with-meta {:rdf/about              '_form
                            :ontolex/writtenRep     #{"hund"}
                            :ontolex/representation #{"hund"}}
                           origins)
        pruned  (-> {:ontolex/canonicalForm form}
                    (->> (ent/prune-embedded-entities
                           {:ontolex/writtenRep #{:ontolex/representation}}))
                    :ontolex/canonicalForm)]
    (is (= {:rdf/about '_form :ontolex/writtenRep #{"hund"}} pruned)
        "a property that another one entails is folded into it")
    (is (= (assoc origins
             :folded {:ontolex/writtenRep #{:ontolex/representation}})
           (meta pruned))
        "and the blank node keeps the origins of its values")))
