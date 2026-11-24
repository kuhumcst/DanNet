(ns dk.cst.dannet.editable
  "What edit mode lets an editor change and what it offers, as data.

  Edit mode knows nothing about DanNet: its locks and fields read these
  rules from the :edit-rules of the page (see dk.cst.dannet.web.ui.lock and
  dk.cst.dannet.web.ui.edit, which say what each key means). To let more of
  the data be edited, change the rules.

  The rules are about the editor only. What a property is belongs in the
  RDF schemas, and what a valid entry looks like belongs in the editorial
  shapes, which edit mode reads as the constraints of a page (see
  dk.cst.dannet.db.shapes/property-constraints)."
  (:require [dk.cst.dannet.shared :as shared]))

(def rules
  "DanNet's rules for edit mode: which resources have fields, which of their
  properties never do, which languages the dropdown of a text offers, and
  what a new entry is made of."
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
   ;; label of a word is written by hand. The synsets of a word follow from
   ;; its senses.
   :derived-by-type {:ontolex/LexicalConcept      #{:rdfs/label :dns/shortLabel}
                     :ontolex/Word                #{:ontolex/evokes}
                     :ontolex/MultiwordExpression #{:ontolex/evokes}
                     :ontolex/Affix               #{:ontolex/evokes}}

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
   :translated      #{:dc/description :dc/subject :rdfs/comment}

   ;; What the plus of a row adds when its values are new entries: a
   ;; template for each class of entry, which is a change with gaps (see
   ;; dk.cst.dannet.web.ui.edit.entry for what each key means). A template
   ;; only says how the entries are linked and what a value starts as. What
   ;; an entry must have besides is in the editorial shapes.
   ;;
   ;; The label of a sense starts as the lemma, which is the label of its
   ;; word without the quotes. A word that evokes a synset has a sense in it
   ;; already, since the build writes that link from the senses.
   :entries
   '{:ontolex/LexicalSense
     {:new  {?sense "sense"}
      :add  #{[?sense :rdf/type :ontolex/LexicalSense]
              [?sense :rdfs/label ?label]
              [?word :ontolex/sense ?sense]
              [?synset :ontolex/lexicalizedSense ?sense]}
      :ask  {?word   :ontolex/Word
             ?synset :ontolex/LexicalConcept}
      :from {?label [?word :rdfs/label #"^\"(.*)\"$"]}
      :note {:da "En ny betydning knytter ordet til synsettet."
             :en "A new sense links the word to the synset."}
      :once {:link   [?word :ontolex/evokes ?synset]
             :reason {:da "Ordet er allerede i synsettet"
                      :en "The word is already in the synset"}}}

     ;; A synset has nothing of its own from the start: the build writes its
     ;; label from its senses (see :derived-labels).
     :ontolex/LexicalConcept
     {:name {:da "Nyt synset" :en "New synset"}
      :new  {?synset "synset"}
      :add  #{[?synset :rdf/type :ontolex/LexicalConcept]}}

     ;; A word is made from its lemma, which is the written form of its
     ;; canonical form, and its label in quotes. A lemma with a space is a
     ;; multiword expression, which is a class of its own without a template.
     :ontolex/Word
     {:name {:da "Nyt ord" :en "New word"}
      :new  {?word "word"}
      :add  #{[?word :rdf/type :ontolex/Word]
              [?word :rdfs/label ?label]
              [?word :ontolex/canonicalForm _form]
              [_form :ontolex/writtenRep ?lemma]}
      :ask  {?lemma {:lang "da" :pattern #"^\S+$"}}
      :from {?label [?lemma "\"%s\""]}}}

   ;; The labels that the build writes, which a new entry is shown with
   ;; until then: for each class, the property that leads to the entries
   ;; whose labels it is made of, and the function that makes it of them.
   :derived-labels  {:ontolex/LexicalConcept [:ontolex/lexicalizedSense
                                              shared/synset-label]}})
