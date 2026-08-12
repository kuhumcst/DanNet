# DMLex export plan for DanNet

## 1. Purpose and scope

The ELEXAI project needs Danish lexicographic data in the DMLex format. DanNet is
the first source. COR and DSL can follow later.

This document describes how to add a DMLex export to DanNet. It also lists the
open questions for the ELEXAI project.

## 2. Reference documents

| Item | Location |
|---|---|
| DMLex Version 1.0, OASIS Standard, 29 April 2025 | `https://docs.oasis-open.org/lexidma/dmlex/v1.0/os/dmlex-v1.0-os.html` |
| XML schemas | `https://docs.oasis-open.org/lexidma/dmlex/v1.0/os/schemas/XML/` |
| JSON schemas | `https://docs.oasis-open.org/lexidma/dmlex/v1.0/os/schemas/JSON/` |
| RDF ontology and SHACL file | `https://docs.oasis-open.org/lexidma/dmlex/v1.0/os/schemas/RDF/` |

## 3. Decisions

These decisions are firm.

1. DanNet keeps its OntoLex RDF as the master data. DMLex is an additional export
   target.
2. The export generates DMLex XML and DMLex JSON. It does not generate DMLex RDF.
3. One intermediate Clojure data structure feeds both serializations.
4. The export must be valid against the DMLex schemas. A complete conversion of
   all DanNet data is not a goal.
5. The tag inventories are Clojure data next to the export code. They are not RDF.

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
| `ontolex:LexicalConcept` (synset) | no direct equivalent, see section 5 |
| synset definition | `definition` |
| synset example | `example` |
| `wn:` and `dns:` relations | `relation` with `member` objects |
| ontological type | `label` with a `tag` |

## 5. Synsets

DMLex has no object for a synset. A DanNet synset with five members becomes five
`sense` objects. Each of these five senses belongs to a different entry.

The Linking module can still express the meaning of a synset. Section 5.2 gives
the method.

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

### 5.2 Synset membership and synset identity

Synset membership becomes a separate relation of type `synonym`. This relation
holds all senses of the synset. The XML schema makes each pair of `ref` and
`role` unique. Therefore the members are a set, as in a synset.

A `relation` has no identifier of its own. It has a `type` attribute, an optional
`description` and its members. Without more data, a consumer can rebuild the
groups but cannot address one group or match it to a DanNet synset.

To keep the identity, give each `synonym` relation one more member. Give this
member the role `synsetIdentity`. Put the DanNet synset URI in its `ref`
attribute. The `synonym` relationType therefore needs a `scopeRestriction` value
of `any`. Section 13 gives the same pattern for external identifiers.

The definition of a synset is repeated in each member sense. This repetition is
correct DMLex.

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
3. Record the mapping from identifier to DanNet URI in a separate file.

## 7. Controlled values

The Controlled Values module declares the tag inventories inside the resource.
Each declaration holds a tag, a description and zero or more `sameAs` URIs. A
consumer therefore reads the meaning of a tag from the export itself.

DanNet needs four inventories.

| Inventory | DMLex object | External mapping |
|---|---|---|
| parts of speech | `partOfSpeechTag` | LexInfo URIs |
| ontological types | `labelTag` | DanNet schema URIs |
| other labels | `labelTag` and `labelTypeTag` | open question 5 |
| relation types | `relationType` and `memberType` | `wn:` and `dns:` URIs |

The `memberType` object also declares constraints. It gives the object type of a
member, the minimum count, the maximum count and a display hint. Use these fields
to declare the arity of each DanNet relation.

## 8. Modules

| Module | Use | Reason |
|---|---|---|
| Core | yes | entries, senses, definitions, examples |
| Linking | yes | all semantic relations |
| Controlled Values | yes | tag inventories and external mappings |
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

1. Read the words, senses, synsets and relations from the graph.
2. Group the senses under their entries.
3. Copy each synset definition into each member sense.
4. Build one `relation` of type `synonym` for each synset.
5. Build one `relation` for each semantic relation between two synsets.

### 9.4 Build the serializers

1. Write the XML serializer.
2. Write the JSON serializer.
3. Use document order and array order for `listingOrder`. The standard permits
   this.

### 9.5 Validate

1. Validate the XML output against `dmlex_no-crosslingual.xsd`.
2. Validate the JSON output against the matching JSON schema.
3. Compare the counts in the output with the counts in the graph.

### 9.6 Release

1. Add the two files to the DanNet download page.
2. Add the license information for the new files.
3. Add the export to the release pipeline.

## 10. Open questions

For the ELEXAI project:

1. Which serialization does ELEXAI want: XML, JSON, or both?
2. Is the sense-level encoding of synset relations acceptable? See section 5.
3. Does ELEXAI need a link from each DanNet sense to its ILI identifier? Section 13
   gives the only conformant method. The ILI identifier arrives as an opaque
   string in a relation. It is not a property of the sense.
4. Does ELEXAI want COR and DSL next? Both fit the DMLex model better than DanNet.

Internal:

5. Which DanNet labels become `labelTag` objects?
6. Where does the identifier mapping file live?

## 11. Out of scope

- The DMLex RDF serialization.
- The DMLex relational database serialization.
- A DMLex import into DanNet.
- Changes to the DanNet OntoLex model.

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

The XML target namespace is `http://docs.oasis-open.org/lexidma/ns/dmlex-1.0`.

The `sameAs` element is available on `definitionTypeTag`, `inflectedFormTag`,
`labelTag`, `labelTypeTag`, `partOfSpeechTag`, `sourceIdentityTag`,
`relationType` and `memberType`. It is not available on `entry` or on `sense`.

Conformance clause 1.b of the standard permits custom extensions. However, both
schemas are closed. Therefore an extension makes the output invalid. Do not use
custom extensions.

## 13. External identifiers

DanNet senses carry ILI identifiers. A DMLex sense has no `sameAs` property, and
section 12 removes the extension method. The Linking module is the only
conformant method.

Use this pattern for each external identifier system:

1. Declare a `relationType`. Give it a `scopeRestriction` value of `any`.
2. Give the `relationType` a `sameAs` URI for the external vocabulary.
3. Declare a `memberType` for the DanNet side. Give it the type `sense`.
4. Declare a `memberType` for the external side.
5. Build one `relation` for each link. Put the external identifier in the second
   `memberID`.

CAUTION: The external identifier is an opaque string. A consumer cannot resolve
it without the `relationType` declaration.
