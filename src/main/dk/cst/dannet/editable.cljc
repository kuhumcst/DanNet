(ns dk.cst.dannet.editable
  "The parts of the DanNet data that can be edited by hand, as data.

  The locks of edit mode know nothing about DanNet: they read these rules
  from the :edit-rules of the page (see dk.cst.dannet.web.ui.lock, which
  says what each key means). To let more of the data be edited, change the
  rules.")

(def rules
  "DanNet's rules for edit mode: which resources have fields, and which of
  their properties never do."
  {;; The namespaces of the resources that can be edited. Only the dataset
   ;; itself is: the ontological types (dnt:), the concepts (dnc:) and the
   ;; schema (dns:) define what the entries mean, so a change to one of them
   ;; changes every entry that uses it.
   :namespaces      #{"dn"}

   ;; The properties that the release build writes for every resource. An
   ;; edit to one would be overwritten at the next release.
   :derived         #{:dns/inherited :skos/inScheme}

   ;; The properties that the release build only writes for the resources
   ;; of a type. The labels of a synset are made from its senses, while the
   ;; label of a word is written by hand.
   :derived-by-type {:ontolex/LexicalConcept #{:rdfs/label :dns/shortLabel}}})
