(ns dk.cst.dannet.editable
  "What edit mode lets an editor change and what it offers, as data.

  Edit mode knows nothing about DanNet: its locks and fields read these
  rules from the :edit-rules of the page (see dk.cst.dannet.web.ui.lock and
  dk.cst.dannet.web.ui.edit, which say what each key means). To let more of
  the data be edited, change the rules.

  The rules are about the editor only. What a property is belongs in the
  RDF schemas, and what a valid entry looks like belongs in the editorial
  shapes, which edit mode reads as the constraints of a page (see
  dk.cst.dannet.db.shapes/property-constraints).")

(def rules
  "DanNet's rules for edit mode: which resources have fields, which of their
  properties never do, and which languages the dropdown of a text offers."
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
   :derived-by-type {:ontolex/LexicalConcept #{:rdfs/label :dns/shortLabel}}

   ;; The properties that say what a resource is. A synset stays a synset:
   ;; its page, its shapes and its place in the dataset all follow from its
   ;; type.
   :fixed           #{:rdf/type}

   ;; The languages that the dropdown of a text offers when no shape names
   ;; those of its property, where nil is none.
   :languages       ["da" "en" nil]

   ;; The properties whose rows suggest the language that has no text yet.
   ;; This is a hint and not a rule of the data: a synset may have no subject
   ;; at all, which SHACL Core can't tell apart from a missing translation.
   :translated      #{:dc/description :dc/subject :rdfs/comment}})
