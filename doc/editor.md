# The editor

Edit mode lets you change the values of an entry on its own page. It is a
preview: the browser keeps each edit as a pending change, and nothing writes
to the database. This page explains how the parts fit together, and the
docstrings say what each function does.

## Edit mode

The pencil button in the header of an entry page turns edit mode on and off
by setting the `edit-mode` cookie. The button, the cookie and the routes
under `/dannet/edit/` (see [web.edit](/src/main/dk/cst/dannet/web/edit.clj))
only work in a development build.

In edit mode, an entry page shows each of its sections as a table. A row
with a value that you can edit shows its values as fields. Every other row is
faded, with a padlock that says why it's locked.

## Changes

A change is a map of the triples to `:retract` and the triples to `:add`. The
docstring of [change](/src/main/dk/cst/dannet/change.cljc) describes the
whole format, which the release build uses for its corrections too.

The pending changes are a vector with one change for each edit, so you can
undo each edit on its own:

```clojure
[{:add #{[:dn/synset-3047 :wn/hypernym :dn/synset-3049]}}
 {:retract #{[:dn/synset-3047 :wn/lexfile "noun.animal"]}
  :add     #{[:dn/synset-3047 :wn/lexfile "noun.Tops"]}}]
```

They are the `:changes` of the session, which is an atom in
[web.app](/src/main/dk/cst/dannet/web/app.cljc). The session only lives in
memory, so you lose the changes when you reload the page. The docstring of
`default-state` describes the other keys that go with the changes.

## From a click to a redrawn row

1. You click a field, type a text in its input and press Enter.
2. The input parses the text with the input type of the row (see
   [ui.input-type](/src/main/dk/cst/dannet/web/ui/input_type.cljc)), e.g. as
   a text in a language, or as a resource that has to exist.
3. The field passes a function such as `change/replace` to `value/edit!`,
   which applies it to the pending changes.
4. The session has changed, so Rum draws the page again from `render-opts`
   in [ui.model](/src/main/dk/cst/dannet/web/ui/model.cljc), which merges
   the page data with the session.
5. For each row, `cell` in
   [ui.edit.row](/src/main/dk/cst/dannet/web/ui/edit/row.cljc) works out the
   fields from the values in the page data and the pending changes. A
   changed value has a status such as `:added` or `:retracted`, which its
   field shows as a tint.

The page data itself never changes: a page shows the data as the pending
changes would leave it.

The docstring of [ui.edit](/src/main/dk/cst/dannet/web/ui/edit.cljc) has the
rules that every field follows, e.g. what each tint means, and says where
each kind of field is.

## What decides the fields

The rules, the shapes and the origins decide which values have fields and
what a field accepts.

### The rules

The rules are a map in
[editable](/src/main/dk/cst/dannet/editable.cljc). They say which resources
you can edit (only those in `dn:` for now), which properties never have
fields and what a new entry is made of. The components read them as the
`:edit-rules` of a page. To make more of the data editable, change the
rules.

### The shapes

The editorial shapes say what a valid entry looks like. The fields and the
check of your changes read the same shapes, so you only write a rule once.
E.g. this shape in
[editorial.ttl](/resources/schemas/internal/shapes/editorial.ttl) is about
the part of speech of a word:

```turtle
dns:EditorialWordShape-partOfSpeech a sh:PropertyShape ;
    sh:path wn:partOfSpeech ;
    sh:maxCount 1 ;
    sh:in ( wn:noun wn:verb wn:adjective ) ;
    sh:severity sh:Violation ;
    sh:message "wn:partOfSpeech must be one of wn:noun, wn:verb, or wn:adjective." .
```

In [db.shapes](/src/main/dk/cst/dannet/db/shapes.clj),
`property-constraints` turns it and the base shape of a word into a
constraint:

```clojure
(get-in @shapes/editorial-constraints [:ontolex/Word :wn/partOfSpeech])
; => {:must {:min 1 :max 1 :in [:wn/noun :wn/verb :wn/adjective]}}
```

The server adds the constraints of an entry's classes to its page data as
`:constraints`, in development only. The row of the part of speech then has
a dropdown of the three values, takes no second value and marks its value as
one that you can't remove.

A constraint is under `:must` when its shape blocks a write, and under
`:should` when it only warns. Only a shape of a single property becomes a
constraint. A rule about several values, e.g. one written as a SPARQL query,
only runs when you check the changes.

### The origins

Theophrastus returns each value of an entity with its origins, which say
where the value comes from, e.g. the `dn:` graph or inference. The page data
has them as `:origins`. You can only edit a value that the `dn:` graph
holds, since that's where changes go. For any other value, `lock` in
[ui.lock](/src/main/dk/cst/dannet/web/ui/lock.cljc) returns the reason that
the padlock shows, e.g. that the value is inferred.

## New entries

A template in the `:entries` of the rules is a change with gaps (see
[entry](/src/main/dk/cst/dannet/entry.cljc)). E.g. the template of a sense
has a gap for its word and a gap for its synset. In a row with a template,
the add button opens a dialog where you find or make the entry that fills
the gap. A new entry has a temporary ID such as `dn:new-sense-1`.

The server has no page for a new entry. The browser makes the page from the
pending changes, and only fetches the constraints of the entry's class from
`/dannet/edit/entry`.

## Removals

You remove an entry of the data as a whole, since removing only a link would
leave a broken entry, e.g. a sense without a synset. A wizard fetches what
goes with the entry from `/dannet/edit/removal` (see `removal-parts` in
[db.change](/src/main/dk/cst/dannet/db/change.clj)), e.g. the senses of a
word. It asks you about what the removal would leave empty, e.g. the synset
of a last sense.

The removal is one change. It retracts the triples of the entry and names
the entry under `:unreference`, which also retracts whatever points to the
entry when the change is applied.

The code uses one verb for each case: a change retracts a triple, you remove
an entry of the data, and you discard a new entry.

## Checking and submitting

The changes page lists the pending changes by resource (see
[ui.changes](/src/main/dk/cst/dannet/web/ui/changes.cljc)). Its check button
posts them to `/dannet/edit/check` as the text of a change file. The server
then calls `validate-changes` in db.shapes. It makes the changes in a copy
of the `dn:` graph in memory, adds the values that the release build would
derive and validates every resource that the changes touch. It leaves out
the problems that a resource already had, so you only see what your changes
break.

Blank nodes need a step of their own, since their labels don't last from one
release to the next. A pending change names a blank node by its label on the
page, and the session keeps the place of the node under `:nodes`. When you
check or submit the changes, `submission` in change swaps each label for a
pattern that finds the node:

```clojure
[{:where   [[:dn/word-11021628 :ontolex/canonicalForm ?node]]
  :retract #{[?node :ontolex/writtenRep #rdf/langstring ["hund" "da"]]}
  :add     #{[?node :ontolex/writtenRep #rdf/langstring ["hunde" "da"]]}}]
```

The submit button only shows this text for now, since nothing accepts a
submitted change yet.

## Tests

The data of the editor and its components are in separate namespaces, so
most of the code has tests that run on the JVM. One of them,
[random_test](/test/dk/cst/dannet/web/ui/edit/random_test.clj), makes random
mixes of edits and checks what has to hold after each one. No test runs in a
browser, so you have to try the clicks, the focus and the dialogs by hand.
