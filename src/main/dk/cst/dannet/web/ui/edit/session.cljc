(ns dk.cst.dannet.web.ui.edit.session
  "What edit mode keeps in the session of the user (see the default-state of
  dk.cst.dannet.web.app): whether it is on, the pending changes, and the
  places and the labels that go with the changes."
  (:require [dk.cst.dannet.web.app :as app]))

(defn toggle-edit-mode!
  "Toggle the `:edit-mode` cookie."
  []
  #?(:cljs (app/update-cookie! :edit-mode not)))

(defn remember-node!
  "Keep the `place` of the blank node with the `label` in the session. A
  change to the node is located by it when the changes are submitted (see
  change/locate-nodes)."
  [label place]
  (swap! app/session assoc-in [:nodes label] place))

(defn remember-labels!
  "Keep the labels in `k->label`, a map of a resource to its label, in the
  session, so that another page can show the resources."
  [k->label]
  (swap! app/session update :labels merge k->label))

(defn update-changes!
  "Apply `f` to the pending changes of the session and `args`, e.g. a
  function of dk.cst.dannet.change and a triple. The changes are only kept
  in memory, so loading the page again loses them."
  [f & args]
  ;; a check of other changes says nothing about these
  (swap! app/session #(-> (apply update % :changes f args)
                          (dissoc :check))))
