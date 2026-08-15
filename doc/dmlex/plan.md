# DMLex export plan for DanNet, COR and the sentiment data

## 1. Purpose and scope

The ELEXAI project needs Danish lexicographic data in the DMLex format. DMLex is
the input format of a knowledge graph that an AI model reads from. The format is
therefore a means and not a goal.

This has a consequence for the scope. A knowledge graph is more useful when the
facts about one word stay together. Therefore the export builds one artefact
from three sources:

| Source | Contribution | Link to DanNet |
|---|---|---|
| DanNet | entries, senses, definitions, examples, relations | the source itself |
| COR | inflected forms | `owl:sameAs` at word level |
| Det Danske Sentimentleksikon | polarity of a word or a sense | `dns:sentiment` at word, sense and synset level |

The three sources fit together already. COR points at DanNet words and the
sentiment data points at DanNet words, senses and synsets. No new alignment work
is necessary.

This document describes how to build that artefact. It also lists the open
questions for the ELEXAI project.

NOTE: Work packages 9.1 to 9.9 are complete. Work package 9.10 holds the third
round of changes, which followed the presentation review of 15 August 2026.

## 2. Reference documents

| Item | Location |
|---|---|
| DMLex Version 1.0, OASIS Standard, 29 April 2025 | `https://docs.oasis-open.org/lexidma/dmlex/v1.0/os/dmlex-v1.0-os.html` |
| XML schemas | `https://docs.oasis-open.org/lexidma/dmlex/v1.0/os/schemas/XML/` |
| JSON schemas | `https://docs.oasis-open.org/lexidma/dmlex/v1.0/os/schemas/JSON/` |
| RDF ontology and SHACL file | `https://docs.oasis-open.org/lexidma/dmlex/v1.0/os/schemas/RDF/` |
| DMLex viewer, a spin-off project that displays this export | `https://github.com/kuhumcst/dmlex-viewer` |

## 3. Decisions

These decisions are firm.

1. DanNet keeps its OntoLex RDF as the master data. DMLex is an additional export
   target.
2. The export generates DMLex XML and DMLex JSON. It does not generate DMLex RDF.
3. One intermediate Clojure data structure feeds both serializations.
4. The export must be valid against the DMLex schemas. A complete conversion of
   all DanNet data is not a goal.
5. The tag inventories are Clojure data next to the export code. They are not RDF.
6. Every member reference points to an object in the same file. Identity and
   external mapping use the Controlled Values module. Section 12 gives the reason.
7. The export leaves out inconsistent DanNet data. It does not repair it. A
   correction in DanNet propagates into the export at the next release. The
   WN-LMF export works the same way.
8. The export builds one artefact from DanNet, COR and the sentiment data. It
   does not build one artefact for each source. Section 1 gives the reason.

Reason for decision 8: the consumer is a knowledge graph, not a dictionary
reader. With a separate COR file, each consumer must join the inflections back
onto the words. This export can do that join once, for all consumers.

Reason for decision 2: DanNet already publishes its data as RDF. A DMLex RDF
export adds a second RDF view of the same data. It gives no new capability. The
tools that consume DMLex consume the XML serialization or the JSON
serialization.

## 4. Model differences

DanNet is concept-centric. The synset is the central object. Many lexical entries
share one synset.

DMLex is entry-centric. The entry is the root object. Each sense belongs to
exactly one entry. DMLex has no object for a shared concept.

DMLex trees have two constraints. Each object has a maximum of one parent. An
object is never an ancestor of another object of the same type. All other links
use the Linking module.

| DanNet | DMLex |
|---|---|
| `ontolex:LexicalEntry` (word) | `entry` |
| written form of the word | `headword` |
| `lexinfo:partOfSpeech` | `partOfSpeech` with a `tag` |
| `ontolex:LexicalSense` | `sense` |
| `ontolex:LexicalConcept` (synset) | a `labelTag` and a `synonym` relation, see section 5 |
| synset definition | `definition` |
| synset example | `example` |
| `wn:` and `dns:` relations | `relation` with `member` objects |
| ontological type | one `label` for each member concept |
| DDO subject field (`dc:subject`) | `label` with a `tag` |
| WordNet lexicographer file (`wn:lexfile`) | `label` with a `tag` |
| synset label | `indicator` on the sense, see section 5.4 |
| COR inflected form | `inflectedForm` on the entry, with a `tag` |
| variant form of a multiword expression | `inflectedForm` without a `tag` |
| sentiment polarity | `label` with a `tag` |
| sentiment value | a second `label` with a `tag` |

### 4.1 Form and meaning

DMLex keeps the form of a headword apart from its meaning. The formal properties
stay on the `entry`. These are the orthography, the morphology and the phonology.
The semantic properties stay on the `sense`. A part of speech on a sense is not
permitted.

The entry and its senses are the only tree in the model. Every other structure is
a `relation` between entries or between senses.

DanNet agrees with this separation. `wn:partOfSpeech` is a property of the word.
The definition, the examples and the ontological type are properties of the
synset or of the sense.

Three rules follow for the export:

1. The part of speech becomes a property of the entry.
2. The synset label, the definition, the examples and the ontological types
   become properties of the sense.
3. A relation between two synsets becomes a relation between their senses.

NOTE: An entry with no senses is permitted. DMLex uses such an entry as a member
in a relation, for example for a spelling variant.

### 4.2 Multiword expressions

A DMLex `headword` is a string. A multiword expression therefore becomes an
ordinary entry with a space in its headword. DanNet has 3126 of these. It also
has 48 affixes, which become ordinary entries in the same way.

DMLex also has a subentry method. That method puts a multiword entry inside the
entry of one of its words at display time, with a `relation` between the two.
DanNet does not record which word an expression belongs under. Therefore the
export does not use the subentry method.

## 5. Synsets

DMLex has no object for a synset. A DanNet synset with five members becomes five
`sense` objects. Each of these five senses belongs to a different entry.

The standard gives a method for synonymy. The Linking module builds relations
between senses that are synonyms. Section A.1.15 of the standard shows this
method. This document uses this method.

NOTE: DMLex models dictionaries. A shared concept object is out of the scope of
DMLex. DanNet keeps the concept-centric view in its OntoLex RDF.

### 5.1 Relations between synsets

Three encodings of a relation between two synsets are valid DMLex.

1. Full expansion. One relation for each pair of senses. A link between a synset
   of five members and a synset of four members becomes 20 relations.
2. One relation with all members. The relation holds nine members. Five members
   have the role `hypernym`. Four members have the role `hyponym`.
3. Representative sense. One sense of each synset represents the synset. The
   relation holds two members.

Option 2 is the plan. It keeps the meaning of the original relation. It also
keeps the output small. Option 1 makes very large files. Option 3 loses
information that a consumer cannot recover.

### 5.2 Synset membership

Synset membership becomes a `relation` of type `synonym`. This relation holds all
senses of the synset. Each member has the role `synonym`.

The `relationType` declaration for `synonym` holds one `memberType`. This
`memberType` has the role `synonym`, the type `sense` and a `min` value of 2.

A relation needs a minimum of two members. Therefore a synset with one sense has
no relation. Section 5.3 gives a method that keeps the identity of these synsets.

The XML schema makes each pair of `ref` and `role` unique. Therefore the members
are a set, as in a synset.

The definition of a synset is repeated in each member sense. This repetition is
correct DMLex.

### 5.3 Synset identity

A `relation` has no identifier of its own. It has a `type` attribute, an optional
`description` and its members. Without more data, a consumer can rebuild the
groups but cannot address one group or match it to a DanNet synset.

A member reference cannot hold a DanNet URI. Section 12 gives the reason.

The Controlled Values module gives the identity. Declare one `labelTag` for each
exported synset:

- `tag` is the local name of the synset URI, for example `synset-1876`.
- `typeTag` is `synset`.
- `for` is `sense`.
- `description` is the short label of the synset.
- `sameAs` is the DanNet synset URI.

Each sense of the synset then carries a `label` with this tag.

```xml
<labelTag tag="synset-1876" typeTag="synset" for="sense">
  <description>hund, køter, vovhund</description>
  <sameAs uri="https://wordnet.dk/dannet/data/synset-1876"/>
</labelTag>
```

This method has three results. A consumer reads the identity from the `sameAs`
URI. A synset with one sense keeps its identity. Membership is available from the
labels and from the `synonym` relations.

NOTE: The standard describes a label as a restriction such as a domain or a
register. A synset label is a wider use of the object. The `typeTag` value
separates these labels from the labels of section 7.

### 5.4 Sense indicators

DMLex gives a sense an optional `indicator`: a short statement that separates
the senses of one entry. The DanNet synset label is that statement, so each
sense carries its synset label as the indicator, without the braces and the
DDO sense markers, for example `hund, køter, vovhund`.

The XML schema makes the indicators of one entry unique. When two senses of an
entry produce the same indicator, both keep the DDO sense markers instead, for
example `abe 1§1a` against `abe 1§2`. When even the marked forms collide, the
senses get no indicator. 77129 of the 77285 senses carry an indicator.

## 6. Identifiers

DMLex identifiers are local to one serialization. The standard puts exchange
between serializations out of scope.

NOTE: The abstract model calls the member reference `memberID`. The XML
serialization and the JSON serialization both call it `ref`. This document uses
`ref`. A `ref` value is a string, not a reference to a resource.

The export must mint a stable string identifier for each entry and each sense.
The identifiers must not change between releases.

Rules for identifiers:

1. Derive each identifier from the DanNet URI.
2. Use the same identifier in the XML output and in the JSON output.
3. A separate mapping file is not necessary. Each identifier is the local
   name of its DanNet URI, and the `uri` attribute of the resource gives the
   base. The concatenation of the two is the URI.

DMLex also makes the combination of headword, homograph number and part of
speech unique. DanNet has 1384 groups of words that share a headword and a part
of speech. Give each word in such a group a `homographNumber`. Order the group
by the DanNet word URI, so that the numbers stay the same between releases.

NOTE: The order is stable, but the numbers are not stable against a change of
the group. When a release adds or removes a word of such a group, the numbers
of that group shift. The identifiers are the stable handles, not the homograph
numbers.

## 7. Controlled values

The Controlled Values module declares the tag inventories inside the resource.
Each declaration holds a tag, a description and zero or more `sameAs` URIs. A
consumer therefore reads the meaning of a tag from the export itself.

DanNet needs fourteen inventories.

| Inventory | DMLex object | External mapping |
|---|---|---|
| parts of speech | `partOfSpeechTag` | LexInfo URIs |
| synsets | `labelTag` and `labelTypeTag` | DanNet synset URIs and ILI URIs |
| ontological type concepts | `labelTag` and `labelTypeTag` | DanNet concept URIs, see below |
| subject fields | `labelTag` and `labelTypeTag` | `dc:subject`, see below |
| lexicographer files | `labelTag` and `labelTypeTag` | `wn:lexfile`, see below |
| gender | `labelTag` and `labelTypeTag` | DanNet schema URIs |
| register, dating and frequency | `labelTag` and `labelTypeTag` | LexInfo URIs |
| usage notes | `labelTag` and `labelTypeTag` | none |
| norm status | `labelTag` and `labelTypeTag` | none, see section 9.7 |
| sentiment polarity | `labelTag` and `labelTypeTag` | MARL class URIs |
| sentiment value | `labelTag` and `labelTypeTag` | `marl:polarityValue` |
| example source | `sourceIdentityTag` | the DDO front page |
| COR inflections | `inflectedFormTag` | none, see section 9.7 |
| relation types | `relationType` and `memberType` | `wn:` and `dns:` URIs |

Each `relationType` also carries the Danish description of its relation from
the DanNet schema, so the file explains its relations as well as it constrains
them.

The synset inventory holds one tag for each exported synset. This inventory is
therefore much larger than the others. This inventory also replaces one
`synonym` relation for each synset. Therefore the total size of the output does
not increase much.

`dns:gender` is a property of a DanNet synset, not of a word. It gives the
gender of the person that the synset denotes. It is not the grammatical gender
of the headword. Therefore its label goes on the sense, not on the entry, and
the export does not put it into the part of speech tag. The two tags are
`Female` and `Male`. 721 senses carry one of them.

The ontological type of a synset is an `rdf:Bag` of DanNet concepts. Each
member concept becomes one label on each sense of the synset. The export
declares one `labelTag` for each of the 61 concepts, with the DanNet concept
URI as `sameAs` and the Danish description of the concept from the schema.
The labels of a sense keep the order that the `rdf:_N` index of the bag
gives. Document order is `listingOrder` in DMLex, so the order survives in
both serializations.

An earlier version of the export kept the bag together as one composite tag,
for example `{LanguageRepresentation; Artifact; Object}`. That method
preserves the bag as one unit, but it needs 203 tags without a `sameAs` URI
or a description, and a consumer must parse each tag with a private grammar.
The member concepts are conjunctive features, so separate labels state the
same facts with a declared vocabulary.

`dc:subject` gives the Den Danske Ordbog subject field of a synset, for
example `zoo` or `med`. The 154 codes become one `labelTag` each, with the
type `domain`. A domain is a label use that the standard itself names. The
codes have no URIs and no expansions in the graph, so these tags get no
`sameAs` URI and no description.

`wn:lexfile` gives the WordNet lexicographer file of a synset, for example
`noun.animal`. The 52 values become one `labelTag` each, with the type
`lexfile`. A lexicographer file has no URI of its own, so these tags get no
`sameAs` URI.

The `memberType` object also declares constraints. It gives the object type of a
member, the minimum count, the maximum count and a display hint. Use these fields
to declare the arity of each DanNet relation.

DanNet marks some senses with `lexinfo:register`, `lexinfo:dating` and
`lexinfo:frequency`. The standard names these categories in its definition of a
label, so each value becomes a `labelTag` with the LexInfo URI as `sameAs`. The
tags are `slangRegister`, `inHouseRegister`, `old` and `rarelyUsed`.

`lexinfo:usageNote` is free text, for example `sj.` or `især børn`. But
DanNet uses only 86 different notes, and 1270 senses share the most common one.
The notes are therefore an inventory in practice, and each one becomes a
`labelTag` with the type `usage`. These tags get no `sameAs` URI, because the
notes have no URI.

The sentiment data needs two inventories. The polarity inventory holds three
tags: `Positive`, `Neutral` and `Negative`. Each tag carries the MARL class URI
as `sameAs`. The value inventory holds one tag for each value from `-3` to `3`.
Its `labelTypeTag` carries `marl:polarityValue` as `sameAs`.

The two inventories stay apart, because they hold two different kinds of fact. A
consumer that needs the direction of the sentiment reads the polarity labels
only.

A DMLex label is a category and not a number. The value inventory is therefore a
wider use of the object, like the synset inventory of section 5.3. Finding 8 in
`findings.md` records this limitation of the model.

A sentiment label goes on an entry and on a sense. See section 9.8. A `for`
value holds one object type only. Therefore both sentiment inventories declare
no `for` value, which leaves the tag unrestricted.

The COR inventory holds one `inflectedFormTag` for each inflection code. A code
has no URI of its own, so these tags get no `sameAs` URI. The description gives
the meaning of the code. Section 9.7 gives the source of the descriptions.

## 8. Modules

| Module | Use | Reason |
|---|---|---|
| Core | yes | entries, senses, definitions, examples |
| Linking | yes | all semantic relations |
| Controlled Values | yes | tag inventories, synset identity, external mappings |
| Crosslingual | no | DanNet content is Danish only |
| Annotation | no | DanNet has no inline markers |
| Etymology | no | DanNet has no etymology |

The XML schema `dmlex_no-crosslingual.xsd` matches this selection. Use it for
validation.

## 9. Work packages

Do the work in this order.

### 9.1 Prepare

1. Download the OASIS Standard package.
2. Read the XML serialization section and the JSON serialization section.
3. Count the DanNet synsets, senses and relations. Estimate the output size.

The schemas are already in `doc/dmlex/spec/`. Section 12 gives the results of the
schema examination.

### 9.2 Build the intermediate structure

1. Define one Clojure map for a lexicographic resource.
2. Define the maps for entry, sense, definition, example, relation and tag.
3. Give each map the fields of the matching DMLex object.
4. Use the DMLex property names as the field names.

### 9.3 Build the extraction

Read every value from the raw DanNet graph.

CAUTION: Do not read the relations from the inference graph. That graph
materializes both directions of each inverse relation and the transitive closure
of `wn:hypernym`. A lexicographer stated none of these triples. The raw graph
holds 58739 `wn:hypernym` triples. The inference graph holds 146841.

1. Read the words, senses, synsets and relations from the graph.
2. Group the senses under their entries.
3. Copy each synset definition into each member sense.
4. Build one `labelTag` for each synset.
5. Give each member sense a `label` with the tag of its synset.
6. Build one `relation` of type `synonym` for each synset of two or more senses.
7. Build one `relation` for each semantic relation between two synsets.

The obverse of each relation needs no table of its own. The DanNet schema
declares `owl:inverseOf` for 50 pairs of relations. One query gives the pairs.
A symmetric relation is a pair that maps to itself, for example `wn:similar`.
Only the direction of each pair is a decision for the export.

The roles of the members come from the pair. For `A wn:hypernym B`, the senses
of A get the role `hyponym` and the senses of B get the role `hypernym`. A
symmetric relation gives both ends the same role. Three DanNet relations have no
declared obverse: `dns:usedFor`, `dns:usedForObject` and `dns:subsumed`. These
have no second name available, so their ends get the roles `source` and
`target`.

The export keeps the direction that the lexicographers used. `wn:meronym` and
`wn:mero_location` are the exceptions, where the meronymy relations stay
consistent instead. This flips 2947 statements into a direction that DanNet
does not state, which is a change of form and not of meaning.

Both directions of a relation are present in the raw data:

- `wn:mero_part` has 13736 triples and `wn:holo_part` has 5865. Only 72 pairs
  are stated in both directions. Therefore the export must flip the obverse
  into the canonical direction and then remove the duplicates.
- `wn:similar` is stated in both directions 42176 times out of 55795. Therefore
  the export must give each pair of synsets a canonical order.

### 9.4 Build the serializers

1. Write the XML serializer.
2. Write the JSON serializer.
3. Use document order and array order for `listingOrder`. The standard permits
   this.

DMLex keeps the listing order of everything: the entries, the senses, the
examples and the members of a relation. Therefore the export must give the same
order at each release. Sort the entries and the senses by their DanNet URI.

The XML shape and the JSON shape of some objects are different. In XML, a label
is `<label tag="x"/>`. In JSON, a label is the string `"x"` in a `labels` array.
The same difference applies to `partOfSpeech` and to `sameAs`.

The two schemas also do not agree on the type of `homographNumber`. The XSD makes it
an `xs:integer` and the JSON schema makes it a string. Therefore the JSON
serializer converts the value. This is a fault in the standard, and a report to
the LEXIDMA committee can remove it.

### 9.5 Validate

1. Validate the XML output against `dmlex_no-crosslingual.xsd`.
2. Validate the JSON output against the matching JSON schema.
3. Compare the counts in the output with the counts in the graph.

Section 12 lists the validators that these schemas need.

### 9.6 Release

1. Add the two files to the DanNet download page.
2. Add the license information for the new files.
3. Add the export to the release pipeline.

The license of the artefact is not the license of DanNet alone. COR and the
sentiment data have their own terms. Record all three.

### 9.7 Add the COR inflections

COR holds 623981 `ontolex:otherForm` objects and links to DanNet with 94374
`owl:sameAs` statements at word level, for example
`cor:COR.89949 owl:sameAs dn:word-11004954`.

CAUTION: COR states each `owl:sameAs` statement in both directions. The 94374
statements are 47187 links. Give each pair a canonical direction, as section 9.3
does for `wn:similar`.

DMLex Core holds an inflected form on the entry:

```xml
<entry id="word-11004954">
  <headword>hund</headword>
  <inflectedForm tag="117"><text>hundenes</text></inflectedForm>
</entry>
```

1. Read the COR graph at `https://ordregister.dk/id/`, not the inference graph.
2. Build a map from DanNet word to COR word with the `owl:sameAs` statements.
3. Read the `ontolex:otherForm` objects and their `ontolex:writtenRep` values.
4. Give each form the last segment of its COR identifier as the `tag` value.
   The identifier `cor:COR.60986.117` gives the code `117`.
5. Remove the duplicate pairs of code and text from each entry.
6. Declare one `inflectedFormTag` for each code that the export uses.
7. Add the forms to the entry of the DanNet word.

A COR identifier has one of two shapes. 448298 forms have the shape
`COR.NNNNN.CCC`. 175683 forms have the shape `COR.EXT.NNNNNN.CCC`, where `EXT`
is a part of the resource name and not a subfield of its own. The inflection
code is the last segment of both shapes. The RDF holds 76 different codes, and
all of them are numeric.

NOTE: The COR documentation describes a fourth subfield for a spelling variant.
The DanNet copy of COR holds no such subfield. A spelling variant is instead a
second `ontolex:writtenRep` value on one form. 28775 forms hold more than one
value, up to six. Two forms of one entry therefore share one code, which
`inflectedFormUnique` permits. But a repeated pair of code and text is a
real error. Step 5 removes it. Finding 5 in `findings.md` gives the reason.

CAUTION: The `inflectedForm` element belongs to the entry, not to the sense. An
inflection is a property of the form, and section 4.1 keeps the form apart from
the meaning. COR also holds 67854 senses, but the export does not need them,
because DanNet gives the senses.

The links give 46790 different DanNet words. 46789 of these words are exported
entries. The other word holds no headword and no sense. 59 COR words hold no
forms. No `owl:sameAs` statement points at a word that is absent from DanNet.

The export adds 348848 `inflectedForm` elements to 46775 entries. It declares 50
`inflectedFormTag` objects.

372 entries link to more than one COR word. Section 14.2 gives the treatment.

NOTE: The export does not compare the lemma of a COR word with the headword of
the entry in general. The lemmas of a COR word are the `ontolex:writtenRep`
values of its canonical form. The export keeps 23 entries whose COR word holds
no lemma equal to the headword, and these links are correct. COR holds the
normed spelling, for example `Helligånd` against the DanNet headword
`helligånd`. COR is the authority for the spelling.

The `rdfs:label` of each COR form ends with a readable label for its code in
parentheses, for example `(vb.perf.part)` for the code 208. Take the
`inflectedFormTag` descriptions from these labels, not from an external file.
Then an update of the COR graph also updates the descriptions. A form outside
the spelling norm has the prefix `unormeret: ` before its code label. This
prefix is a property of the form, not of the code. Remove it. Every code in the
graph has a label. If the labels of one code do not agree after the removal of
the prefix, write no description for that code. An `inflectedFormTag`
description is optional, and section 12 gives the proof.

The norm status of a form becomes a `label` on the `inflectedForm`. A form
whose code label carries the `unormeret: ` prefix is outside the spelling
norm and gets the label `unormeret`, declared as a `labelTag` with the type
`norm`. A pair of code and text that COR holds both inside and outside the
norm counts as inside: the merged form keeps the label only when every copy
carries it. The export marks 14596 forms.

### 9.8 Add the sentiment data

The sentiment graph at `https://wordnet.dk/sentiment/` holds 29971
`dns:sentiment` statements. The subjects are 7797 DanNet words, 11382 DanNet
senses and 10792 DanNet synsets. The DanNet graph holds 181 more, on synsets.
The total is 30152.

NOTE: A word and one of its senses often share one blank node. 2487 blank nodes
are the object of more than one `dns:sentiment` statement. This is not a fault.
A shared node states one sentiment at both levels.

Each statement points at a blank node with two properties:

```
marl:hasPolarity   marl:Positive, marl:Neutral or marl:Negative
marl:polarityValue an integer from -3 to 3
```

1. Declare a `labelTypeTag` with the tag `sentiment`.
2. Declare one `labelTag` for each of the three polarities, with the MARL class
   URI as `sameAs`.
3. Declare a `labelTypeTag` with the tag `sentimentValue`, with
   `marl:polarityValue` as `sameAs`.
4. Declare one `labelTag` for each value from `-3` to `3`.
5. If the subject is a word, give the labels to the entry.
6. If the subject is a sense, give the labels to the sense.
7. If the subject is a synset, give the labels to each sense of the synset.

The 181 statements of the DanNet graph hold a polarity and no value. These
subjects get a polarity label only.

A sense takes the sentiment of its synset only when the sense holds no sentiment
of its own. Two different polarities on one sense are nonsense. The current data
holds no such case, and this rule is a safeguard.

An entry and its senses do not always agree. 437 words state a polarity that
one of their own senses does not state. The export keeps both statements,
because the source states both. A consumer therefore sees the difference.

7793 words and 12181 senses get a label. 820 of these senses get the label from
their synset only. A word that has no entry gets no label.

Section 14.2 gives the treatment of the three faults in the sentiment data.

### 9.9 Second round: more DanNet data, better fit

Date: 14 August 2026. This round follows the export review. It adds the data
that the review found without a home, and it uses more of the standard where
the standard fits.

1. Export `dc:subject` and `wn:lexfile` as label inventories. See section 7.
2. Give each sense an `indicator` from its synset label. See section 5.4.
3. Keep the norm status of each COR form as a label. See section 9.7.
4. Export the `ontolex:otherForm` variants of DanNet multiword expressions as
   `inflectedForm` objects without a `tag`. DanNet holds 140 of these variant
   phrasings. A variant whose text equals the headword or an existing
   inflected form of the entry is left out.
5. Mark every example with `sourceIdentity="DDO"` and declare one
   `sourceIdentityTag`. Every `lexinfo:senseExample` in DanNet is a Den
   Danske Ordbog citation.
6. Give every `relationType` the Danish description of its relation from the
   DanNet schema. See section 7.
7. Declare the ontological type per member concept instead of per composite.
   See section 7.
8. Declare `for="entry"` on each `inflectedFormTag`.

### 9.10 Third round: how DanNet wants to be read

Date: 15 August 2026. This round follows the review of the presentation config
of the DMLex viewer. The export stays the same data. The round is about the
reading of it.

1. Ship `presentation.json` next to the data, as `metadata.json` already is.
   The file holds DanNet's own display taste. It carries the order and the
   Danish names of the label types. It also carries the grouping of the
   relation types and the Danish name of every relation role. DMLex has no
   slot for any of it. The file lives at
   `resources/export/dmlex/presentation.json` and goes into the zip.
2. Give a relation with no declared obverse a distinct name for its subject
   end. The name is `involved_` plus its own name, instead of the shared pair
   `source` and `target`. Only `dns:usedFor` and `dns:usedForObject` lack an
   `owl:inverseOf`, and the shared pair made their rows indistinguishable to a
   consumer that reads the roles.
3. Give the three sentiment polarity tags a Danish description. The tags
   themselves are the MARL class names.
4. Give every relation member an `obverseListingOrder`, derived from the
   indegree of its own synset by subtracting it from the highest indegree. A
   pair states no order of its own, so a consumer that merges many relations
   into one list has nothing to rank them by. This carries the prominence
   measure that already ranks the DanNet search results. Synsets of equal
   indegree get equal positions, which leaves the tie-break to the consumer.
   The synonym members carry no order: they all belong to the one synset.
5. Order the senses of an entry the same way, the best-connected synset first.
   DMLex gives a sense no `listingOrder`, so the document order is the sense
   order. Before this, an entry listed its senses by sense identifier. That
   order put `{menneske_§1; menneskebarn_§2}` ahead of
   `{menneske_§1a; individ_§1}`, while the DanNet search puts them the other
   way round.
6. Strip the DDO sense markers from the description of each synset `labelTag`,
   so that a reader sees `{menneske; individ; …}` rather than
   `{menneske_§1a; individ_§1; …}`. A stripped label that names a second
   synset keeps its markers. 71% of the multi-sense entries hold such a pair,
   and `{abe}` four times over says less than `{abe_1§1a}` once. Section 5.4
   already uses this rule for the sense indicators. The two agree, because a
   label keeps its markers exactly where the indicator falls back to its
   marked form. 50377 of the 70471 labels lose their markers.

The order costs about 20 MB in each serialization and about 1 MB in the zip.

## 10. Open questions

For the ELEXAI project:

1. Which serialization does ELEXAI want: XML, JSON, or both?
2. Is the sense-level encoding of synset relations acceptable? See section 5.
3. Is the label-based encoding of synset identity acceptable? See section 5.3.
   DMLex has no object for synset identity. The ELEXAI project can report this
   finding to the LEXIDMA committee.
4. Does ELEXAI need the ILI identifier of each synset? Section 13 gives the
   method.
5. Does ELEXAI want DSL as a fourth source? COR and the sentiment data are in
   scope already.

Internal:

6. ANSWERED. No identifier mapping file is necessary. Each identifier is the
   local name of its DanNet URI. Section 6 gives the rule.
7. ANSWERED. One tag for each DanNet concept replaced the composite tag in
   the second round. Section 7 gives the reason, and the bag order survives
   as the label order of each sense.
8. PARTLY ANSWERED. Every example now carries `sourceIdentity="DDO"`. The
   sense-level DDO URLs stay out: a DMLex sense has no `sameAs`, and one
   `labelTag` for each URL would double the identity register of section 5.3.
   They return only if ELEXAI asks for them. See section 14.3.
9. ANSWERED. What do the COR inflection codes mean? The `rdfs:label` of each
   COR form gives a readable label for its code. Section 9.7 gives the method.
10. ANSWERED. What happens to `marl:polarityValue`? The export declares a second
    inventory with one `labelTag` for each value. Section 7 and section 9.8 give
    the method. Finding 8 in `findings.md` records the limitation of the model.
11. ANSWERED. Do the sentiment labels go on the entry, the sense, or both? Both.
    Section 9.8 gives the method.
12. OPEN. What do the `dc:subject` domain codes mean? The 154 codes are the Den
    Danske Ordbog subject-field abbreviations. DanNet holds no expansion of
    them, so a reader sees `zoo` and `håa` with nothing to read them by. They
    cannot be derived from the graph. 130 of the codes never co-occur with a
    `wn:domain_topic`, and the ones that do give the wrong answer. For example,
    `bot` pairs most often with *drik*. The fix is a map of code to Danish name
    next to the other tag inventories, filled from the DDO list. Decision 5
    allows it. A better fix is out of scope here. If DanNet makes the domains
    resources instead of literals, they can carry an `rdfs:label`. The web UI
    and the CSV export then gain the names too.

## 11. Out of scope

- The DMLex RDF serialization.
- The DMLex relational database serialization.
- A DMLex import into DanNet.
- Changes to the DanNet OntoLex model.
- The relations to other datasets: `wn:eq_synonym`, `dns:eqHypernym`,
  `dns:eqHyponym` and `dns:eqSimilar`. Section 14.1 gives the reason.
- The Crosslingual module. DanNet holds no words of another language.

NOTE: The sentiment data and COR were out of scope in an earlier version of this
document. Decision 8 puts them in scope. Work packages 9.7 and 9.8 describe the
work.

## 12. Schema examination

Date of examination: 12 August 2026. Four schemas are in `doc/dmlex/spec/`. They
are the full XML and JSON schemas, and the two no-crosslingual variants.

| Question | Result |
|---|---|
| Does `dmlex.xsd` contain `xs:any` or `anyAttribute`? | No |
| Does `dmlex.xsd` contain `processContents`? | No |
| Does `dmlex_no-crosslingual.xsd` contain either? | No |
| Does `dmlex.schema.json` permit extra keys? | No. All 30 object definitions set `additionalProperties` to `false` |
| Does `dmlex_no-crosslingual.schema.json` permit extra keys? | No. The same for all 27 definitions |
| Which objects accept `sameAs`? | The tag objects only |
| Does the XML schema constrain `member/@ref`? | Yes. A `keyref` makes each value match the `id` of an `entry`, a `sense` or a `collocateMarker` in the same document |
| Does the JSON schema constrain `ref`? | No. JSON Schema cannot express this rule |
| Do the two schemas agree on the types? | No. `homographNumber` is an `xs:integer` in the XSD and a string in the JSON schema |
| Does `inflectedFormTagType` assert a non-empty description? | No. The description of an inflected form tag is optional in fact, unlike the description of a part of speech tag |
| Does `inflectedFormUnique` work? | Yes. It keys on `text` and on `@tag`, and both of these are simple types. Finding 5 in `findings.md` gives the contrast with `entryUnique` |

The XML target namespace is `http://docs.oasis-open.org/lexidma/ns/dmlex-1.0`.

The `sameAs` element is available on `definitionTypeTag`, `inflectedFormTag`,
`labelTag`, `labelTypeTag`, `partOfSpeechTag`, `sourceIdentityTag`,
`relationType` and `memberType`. It is not available on `entry` or on `sense`.

Conformance clause 1.b of the standard permits custom extensions. But both
schemas are closed. Therefore an extension makes the output invalid. Do not use
custom extensions.

### 12.1 The member reference rule

The `keyref` is stricter than the text of the standard. The standard defines the
member reference as a reference to an object such as an entry or a sense, does
not prescribe the form of the identifier, and lets a relation hold members in a
different lexicographic resource. A `scopeRestriction` value of `any` means no
restriction at all.

The XML schema permits none of this. Every `ref` must match an `id` in the same
document. Therefore an external URI in a `ref` attribute is not valid XML, and
the export cannot use one.

This rule removes two earlier methods from this document. A synset URI cannot be
a member of a `synonym` relation. An ILI identifier cannot be a member of a
relation. Section 5.3 and section 13 give the replacement methods.

### 12.2 Validators

The XML schemas are XSD 1.1. They use `xs:assert` and `xs:unique` with a `ref`
attribute. `xmllint` and the default `SchemaFactory` of the JDK read XSD 1.0
only. Neither tool can read these schemas.

The XML validation needs Xerces 2.12 or later with the XSD 1.1 factory. The JSON
validation needs a library for JSON Schema draft 2020-12. The `:validate` alias
in `deps.edn` holds both libraries. Finding 3 in `findings.md` gives their
history.

The XML validation of a full export also needs a large JVM heap. Xerces keeps
the identity-constraint tables in memory for the full document. The 127 MB
export needs a heap of approximately 16 GB. If the heap is too small, the JVM
becomes unresponsive, and an attached REPL is lost with it. Validate a full
export in its own process, for example with `clj -J-Xmx16g -M:validate`.

## 13. External identifiers

DanNet synsets carry ILI identifiers. A DMLex `sense` has no `sameAs` property.
Section 12 removes the extension method and the member method. The Controlled
Values module is the conformant method.

Use these rules for each external identifier system:

1. If the identifier belongs to a synset, add one more `sameAs` to the synset
   `labelTag` of section 5.3. ILI works this way.
2. If the identifier belongs to a word, declare a `labelTag` with a `for` value
   of `entry`. Then give the entry a `label` with this tag. COR can work this
   way.
3. If two identifier systems must stay apart, give each system its own
   `labelTypeTag`.

```xml
<labelTag tag="synset-1876" typeTag="synset" for="sense">
  <sameAs uri="https://wordnet.dk/dannet/data/synset-1876"/>
  <sameAs uri="http://globalwordnet.org/ili/i35958"/>
</labelTag>
```

NOTE: A `sameAs` URI has no type. A consumer separates the DanNet URI from the
ILI URI by the namespace of the URI.

## 14. Findings from the data

Date of examination: 12 August 2026 for the DanNet numbers, and 13 August 2026
for the COR numbers and the sentiment numbers. The numbers come from the raw
graphs of the 2025-07-03 release, not from the inference graph.

NOTE: `findings.md` gives the same findings, with the reasons
and the examples. Read that document first.

### 14.1 Relations to other datasets

Five DanNet properties point at objects in other datasets. `wn:eq_synonym`
points at Open English WordNet. `dns:eqSimilar` points at ILI. `dns:eqHypernym`
and `dns:eqHyponym` point at both. `wn:ili` points at ILI.

The test is not the dataset. The test is the claim. DMLex has one conformant
method for an external object, the `sameAs` URI of section 13. A `sameAs` URI
says that the two objects are the same. It says nothing else.

The DanNet schema separates the five properties:

| Property | Declaration in the schema | Method |
|---|---|---|
| `wn:ili` | a sub-property of `skos:exactMatch` | `sameAs` on the synset `labelTag` |
| `dns:eqHypernym` | a sub-property of `skos:broadMatch` | none |
| `dns:eqHyponym` | a sub-property of `skos:narrowMatch` | none |
| `dns:eqSimilar` | a mapping relation, not an exact match | none |
| `wn:eq_synonym` | a `wn:SynsetRelType`, with no mapping declaration | none |

`wn:ili` is an identity claim, so `sameAs` holds it without loss. The other four
are not identity claims. A `sameAs` URI for a broader concept in another dataset
says something that DanNet does not say.

These four properties also cannot become DMLex relations. A member reference
must point at an object in the same file (see section 12.1). The Crosslingual
module does not help, because that module models the translations of a headword.

NOTE: `wn:eq_synonym` says that the object is synonymous with the subject. A
translation of the Danish headword is a possible use for this property. But
DanNet holds only a pointer to an Open English WordNet synset, not the English
words of that synset. The Crosslingual module needs the words. Therefore this
property becomes useful only if the export also reads Open English WordNet.

### 14.2 Data faults

These faults are in the source data, not in the conversion. Decision 7 applies to
all of them. The export leaves the data out. A correction in the source removes
the fault from the export at the next release, with no change to the export code.

Each treatment is a condition on the data. No treatment holds a list of
identifiers. Therefore each exclusion repairs itself.

A fault removes only the part of the data that it makes unusable. A word that
keeps a usable headword and a usable sense keeps its entry.

| Fault | Count | Treatment in the export |
|---|---|---|
| A word has an `ontolex:canonicalForm` blank node with no properties, and therefore no written form | 15 | The word gets no entry, because DMLex requires a headword |
| A word has `wn:partOfSpeech` with an empty local name | 1 | The word keeps its entry and gets no `partOfSpeech`, which DMLex permits |
| A synset has more than one ILI identifier, up to five | 385 | The synset gets no ILI `sameAs` URI |
| One ILI identifier is claimed by more than one synset | 580 | These synsets get no ILI `sameAs` URI |
| A synset has an ILI but no lexicalized sense | 8 | The synset gets no `labelTag` |
| A relation statement points at a synset with no lexicalized sense | 12 | The pair produces no relation, because every DMLex member is a sense |
| Every `dns:subsumed` statement points at a sense that has no statements of its own | 188 | `dns:subsumed` gets no relation type |
| An entry links to more than one COR word, and these COR words hold different lemmas | 128 | The export keeps the COR words whose lemma is the headword of the entry. This condition corrects 112 entries. If no lemma is the headword, the export keeps all of the COR words |
| A COR word holds no `ontolex:canonicalForm`, and therefore no lemma | 1 | The export takes no forms from this COR word, because the lemma condition above cannot test them |
| A sentiment blank node states more than one polarity | 13 | The 14 subjects of these nodes get no sentiment label |
| A sentiment blank node states one polarity and more than one value | 40 | The 41 subjects of these nodes keep the polarity label and get no value label |
| A sentiment blank node states a polarity and a value that do not agree | 57 | The subject keeps the polarity label and gets no value label |

An ILI identifier is an identity claim. Two ILI identifiers on one synset
therefore say that the synset is two concepts. One ILI identifier on two synsets
says that the two synsets are one concept. The WN-LMF export already removes the
second fault.

Of the 8024 ILI statements in DanNet, 6085 are free of both faults. The export
holds these 6085.

Three synsets hold relation statements but no lexicalized sense, for example
{Republikken Congo}. Their twelve relation pairs produce no relation. Without
this condition, three of the pairs would produce a relation with members in one
role only. The `min` constraint of a `memberType` forbids this, but the
constraint is declarative data, so no schema validator can detect the
violation.

A COR word holds the paradigm of one lemma. 372 entries link to more than one
COR word. Most of these links are spelling variants, and a merge of the
paradigms is correct. 128 entries link to COR words with different lemmas, for
example the word `holde` to the COR words `holde` and `ved lige`. A merge of
these paradigms puts the forms of `ved lige` on the entry `holde`. Therefore the
export keeps the COR words whose lemma is the headword.

One COR word, the word `ret`, holds no `ontolex:canonicalForm` and therefore no
lemma. The lemma condition cannot test a COR word without a lemma. Therefore
the forms of this COR word stay out of the export.

A polarity and a value state the same fact twice. The value agrees with the
polarity in all but 57 statements. Each of these 57 statements holds
`marl:Positive` with the value 0, and each subject is a synset. The polarity is
the primary statement of the sentiment data, so the export keeps the polarity.

A blank node with two polarities states two facts about one subject. The export
cannot choose between them, so the subject gets no sentiment label at all. A
blank node with one polarity and two values keeps the polarity, because the
polarity is not in doubt.

### 14.3 Data that the export leaves out on purpose

Date of examination: 14 August 2026. A predicate census of the raw graph found
the data below without a home in the export. Each row is a decision on record,
so that "left out" is never an accident of the query list.

| Data | Count | Reason |
|---|---|---|
| `dns:source`, the DDO URL of a sense or a word | 120416 | a DMLex sense has no `sameAs`; see open question 8 |
| `dns:dslSense`, the DSL id of a sense | 2203 | the same reason |
| `skos:altLabel` on merged senses | 63 | the same reason |
| `dns:inherited`, `dns:inheritedRelation`, `dns:inheritedFrom` | 70775 | DMLex has no property for the provenance of a relation |

NOTE: The inheritance markers show that the raw graph does not hold only what
a lexicographer stated. 70775 of the exported relation statements are
inherited from ancestor synsets, and the raw graph materialises them next to
the curated statements. The export keeps them, because DanNet publishes them,
and drops the markers. A consumer therefore cannot separate a curated relation
from an inherited one.

## 15. Fit between the export and the DMLex model

The export is valid DMLex. The fit with the model is not equal for all parts of
the data. The fit follows the distance from the dictionary tradition that DMLex
models.

| Part of the data | Fit | Reason |
|---|---|---|
| COR inflections and norm status | full | `inflectedForm` with a declared tag and a label is the DMLex model for morphology |
| headwords, homograph numbers, parts of speech | full | the core objects of a dictionary |
| sense indicators | full | a purpose-built property, filled from the synset label |
| register, dating, frequency, usage, gender labels | full | a label is a category from a declared inventory |
| subject fields and lexicographer files | full | a domain is the label use that the standard itself names |
| ontological type concepts | full | one label per declared concept, in bag order |
| example sources | full | `sourceIdentity` with a declared tag |
| semantic relations | full | the Linking module carries them with declared roles |
| synset identity | by convention | DMLex has no object for a shared concept. See finding 1 |
| sentiment value | by convention | a number becomes an inventory of category tags. See finding 8 |
| relations to other datasets | none | a member reference cannot leave the file. See finding 2 |

A convention is valid DMLex. But only a consumer that knows the convention
can decode it. The `labelTag` inventory of the synsets has the cardinality of
the data, not of a vocabulary, and the `sentimentValue` tags hold an order that
the file cannot state.

The two serializations hold the same content because one intermediate structure
produces both. The schemas do not guarantee this equality. Section 12 gives the
schema conflicts.

`findings.md` gives this assessment in full.
