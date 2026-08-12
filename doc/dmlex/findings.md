# What we learned about DMLex by implementing it

This is the plain-language companion to `plan.md`. The plan says what the export
does; this document says what surprised us on the way there and why. Each entry
is written to be readable cold, months later, by someone who has not been
staring at the schemas.

Four of the six findings are faults in the standard rather than in DanNet. Those
are collected at the end as feedback for the LEXIDMA committee.

## 1. There is no synset in DMLex

DMLex models dictionaries, so its world is entries and senses. An entry has one
headword and holds its senses. There is no object for a shared concept, which
means DanNet's central object has nowhere to live.

The standard's answer, shown in its own example A.1.15, is that a synset is a
`relation` of type synonymy between the member senses. That works, and it is
what the export does. What the standard does not give you is a *name* for that
group: a `relation` has a type and members and no identifier. So a consumer can
rebuild the groups but cannot point at one and say "this is `dn:synset-1876`".

Our answer is the Controlled Values module. Each synset gets a `labelTag`, whose
`sameAs` holds the DanNet URI, and every sense of that synset carries the label:

```xml
<labelTag tag="synset-1876" typeTag="synset" for="sense">
  <description>{have_1§1}</description>
  <sameAs uri="https://wordnet.dk/dannet/data/synset-1876"/>
</labelTag>
```

This is a slightly wider use of a label than the standard imagines, since labels
are meant for things like register and domain markers. It is fully valid, and it
is the only place in the model where an object can be declared once, attached to
a sense, and carry an external URI.

Worth knowing: this is not an oversight by the DMLex authors. John McCrae, who
is behind OntoLex and the ILI, is one of the editors. From that vantage point a
concept object in DMLex would duplicate OntoLex, which DanNet already publishes.
The concept-centric view stays in the RDF; DMLex is the dictionary view.

## 2. A relation member must point inside the same file

This one cost us a plan rewrite, because we got it wrong first.

A `relation` holds `member` objects, and each member has a `ref`. We assumed a
`ref` could hold any string, including a DanNet URI or an ILI identifier, and
built two parts of the plan on that. It cannot. The XML schema enforces it:

```xml
<xs:keyref name="memberRef" refer="entryOrSenseOrCollocateMarkerKey">
  <xs:selector xpath=".//member"/>
  <xs:field xpath="@ref"/>
</xs:keyref>
```

Every `ref` must match the `id` of an entry or sense in the same document. That
is the schema. The prose says something looser: a member reference points at an
object such as an entry or a sense, DMLex "does not prescribe the exact form of
these IDs", and the Linking Module explicitly advertises relations "between
objects residing in different lexicographic resources", with a recommended IRI
scheme for exactly that purpose in section 3.2.1.

So the XSD is stricter than the standard it implements: cross-resource linking
is a documented feature of the Linking Module that the XML serialization cannot
express. `scopeRestriction="any"`, which the prose defines as "no restriction",
cannot be honoured in XML at all.

Concretely, the standard says you can write a relation whose two members live in
two different dictionaries, using the IRI scheme of section 3.2.1 for the far
one:

```xml
<relation type="translation">
  <member ref="sense-21955" role="source"/>
  <member ref="example.com/other-dictionary/entry/dog~noun/sense/0~an%20animal" role="target"/>
</relation>
```

The second member is exactly what section 3.2.1 was written for, and the XML
schema rejects it, because no entry or sense inside this file has that id. The
feature exists in the prose and is unreachable in XML.

For us the practical outcome is the same either way, because the things DanNet
would want to point at are not DMLex objects in another lexicographic resource.
They are OEWN synsets and ILI concepts, published as RDF. So any DanNet relation
pointing outside DanNet still cannot be a DMLex relation. But note the reason:
it is the XSD that forbids it, not the standard.

The one external thing that does survive is `wn:ili`, and the reason is worth
holding onto: it is not about the dataset, it is about the kind of claim. DMLex
has exactly one way to reference something external, the `sameAs` URI, and a
`sameAs` URI asserts that two things are the same. The DanNet schema declares
`wn:ili` a sub-property of `skos:exactMatch`, so it is an identity claim and
fits. `dns:eqHypernym` is a sub-property of `skos:broadMatch`, so putting it in
`sameAs` would assert something DanNet does not say. Identity claims can cross
the file boundary; other relations cannot.

Note also that the JSON schema cannot express this rule at all, since JSON
Schema has no referential integrity. So the same mistake would have produced a
valid JSON file and an invalid XML one.

## 3. Only about two validators in the world can read the XML schema

The DMLex XSD files are XSD 1.1. They use `xs:assert` and the 1.1-only
`xpathDefaultNamespace` attribute.

XSD 1.1 became a W3C Recommendation in 2012, by which time interest in XML
schema languages had faded, and almost nobody implemented it. The practical set
is Xerces-J and Saxon EE. The JDK ships a fork of Xerces that only does XSD 1.0,
`xmllint` and libxml2 never implemented 1.1, and neither did .NET or the usual
Python libraries. So the file cannot be validated by any of the tools a person
would normally reach for.

Worse, the XSD 1.1 work never merged into the Xerces trunk. `xerces:xercesImpl`
on Maven Central, at any version, is 1.0 only. The 1.1 build lives on the
`xml-schema-1.1-dev` branch, and the only copy published to Maven is a
third-party repackaging by the OGC conformance-testing project:

```clojure
org.opengis.cite.xerces/xercesImpl-xsd11 {:mvn/version "2.12-beta-r1667115"}
com.ibm.icu/icu4j                        {:mvn/version "77.1"}
```

It is a 2015 beta built for JDK 7. It works. The ICU4J line is needed because
assertions are XPath 2.0, which Xerces delegates to an Eclipse XPath processor
that uses ICU4J and declares no dependencies at all in its pom, so you get a
`NoClassDefFoundError` on the first document you try to validate and no clue
from the metadata.

These are in the `:validate` alias, so only validation pays for them.

## 4. The two schemas disagree about `homographNumber`

The XSD says it is a number:

```xml
<xs:attribute name="homographNumber" use="optional" type="xs:integer"/>
```

The JSON schema says it is a string:

```json
"homographNumber": { "type": "string" }
```

Same property, same standard, same version, two incompatible types. One
intermediate data structure therefore cannot serialize into both without
converting, which is what `json-safe` in `dmlex.clj` exists to do. Delete it if
the committee ever aligns the two.

## 5. Three of the schema's uniqueness rules can never be satisfied

This is the big one, and it took a minimal reproduction to be sure of it,
because the error message points somewhere misleading.

What the schema wants to say is reasonable: no two entries may share the same
headword, homograph number and part of speech. In XSD you write that like a
UNIQUE index over three columns:

```xml
<xs:unique name="entryUnique">
  <xs:selector xpath="entry"/>          <!-- the rows -->
  <xs:field xpath="headword"/>          <!-- column 1 -->
  <xs:field xpath="@homographNumber"/>  <!-- column 2 -->
  <xs:field xpath="partOfSpeech/@tag"/> <!-- column 3 -->
</xs:unique>
```

The rule you must obey is that a field has to point at something holding one
plain value: an attribute, or an element containing nothing but text. The
validator has to turn it into a single string in order to compare rows.

DMLex points at `headword`, and declares `headword` as mixed content, so that it
can hold markers:

```xml
<headword>gå <placeholderMarker>rundt</placeholderMarker></headword>
```

Now there is no single value to compare. Is the column `gå rundt`, or `gå`, or
the markup too? XSD does not guess, it errors. And it errors on every entry,
including the simplest possible one:

```xml
<lexicographicResource xmlns="http://docs.oasis-open.org/lexidma/ns/dmlex-1.0" langCode="da">
  <entry><headword>hund</headword></entry>
</lexicographicResource>
```

Our export produced 173,564 of these, one per entry and per definition, plus 265
of a second kind. The second kind is the same fault with a different symptom:
for `example/text` the validator takes the unusable value as null rather than
erroring, so two examples under one sense both come out null and look like
duplicates of each other.

The misleading part: the message reads `A field of identity constraint
'entryUnique' matched element 'lexicographicResource', but this element does not
have a simple type`. It names the element that *owns* the constraint, not the
element the field landed on, so it looks like the root element is at fault.

If you ever need to prove this again, here is the whole reproduction. Case A
fails, case B is identical except the field has a simple type, and it passes:

```xml
<!-- A: fails -->
<xs:element name='label'>
  <xs:complexType mixed='true'>
    <xs:sequence><xs:element name='marker' minOccurs='0'/></xs:sequence>
  </xs:complexType>
</xs:element>
<xs:unique name='itemUnique'>
  <xs:selector xpath='item'/><xs:field xpath='label'/>
</xs:unique>

<!-- B: passes, and correctly catches real duplicates -->
<xs:element name='label' type='xs:string'/>
```

The three affected constraints are `entryUnique` on `headword`,
`definitionUnique` on `definition/text` and `exampleUnique` on `example/text`.
The committee cannot fix them by making those elements plain text, since the
markers are the point. They would have to key on something else or drop the
rules, which is what they already did to seven other constraints, as the comment
at the top of the XSD explains.

So: our file is otherwise valid, and everything else the validator checks
passes, including the 753,858 member references of finding 2. The validation
code separates these known schema errors from real ones rather than hiding them,
so a genuine error cannot disappear into the pile.

One caveat: we confirmed this with one processor. The XSD rule is clear enough
that we believe Xerces is applying it correctly, but Saxon EE is the only other
implementation and we do not have it.

## 6. An optional description that is not optional

Smaller, same family. `partOfSpeechTagType` declares its description optional
and then asserts that it is not empty:

```xml
<xs:element name="description" minOccurs="0" type="xs:string"/>
<xs:assert test="string-length(description)>0"/>
```

An absent element has string length 0, so the assertion fails whenever the
optional element is left out. In effect the description is mandatory. Cheap to
satisfy: our three part of speech tags carry Danish descriptions.

## 7. You do not have to ship all four serializations

Section 1.2 of the specification reads:

> In this document, we specify REQUIRED serializations for: XML, JSON, RDF, and
> relational databases. An informative serialization specification is provided
> for: NVH.

Read quickly, that looks like it says a conformant implementation must provide
all four. It does not, and the conformance section says so plainly:

> Conformant DMLex Instances MUST be well formed and valid instances according
> to **one of** the normative DMLex Serialization Specifications.

with a note underneath that an instance cannot be conformant without being
conformant to a specific serialization. So conformance is a property of a single
file in a single serialization. Our XML file is conformant on its own, and so is
our JSON file, and shipping only one of them would also have been fine.

What "REQUIRED" is doing in section 1.2 is classifying the parts of the
specification document, not imposing a duty on implementers: those four
serializations are specified normatively, whereas NVH is an appendix and
informative. The section headings make it clearer than the sentence does, since
they read "5 DMLex REQUIRED Serializations (Normative)" against "A.2 DMLex NVH
serialization (Informative)".

The wording is unfortunate, because REQUIRED is an RFC 2119 keyword, the
document states in its front matter that these keywords are to be read as in RFC
2119, and here it is being used to label document sections rather than to place
a requirement on anybody. If it misled us it will mislead others.

Related, and worth remembering when someone asks for both formats: the standard
puts round-tripping between serializations out of scope. The two files are
semantically compatible but their identifiers, uniqueness scopes and addressing
mechanisms are not guaranteed to match, which is why our export mints the same
ids for both rather than relying on the standard to relate them.

## Feedback for the LEXIDMA committee

Findings 2 to 7 are defects in the standard's artifacts or its wording, and
finding 1 is a design limitation worth reporting even though it is deliberate:

1. Synset identity has no home in the model. Wordnet content can be carried, but
   concept identity survives only by convention, through a Controlled Values
   tag. See finding 1.
2. The XSD files require an XSD 1.1 processor, of which roughly two exist, and
   none of the common command-line tools qualifies. Nothing in the standard
   warns an implementer of this. See finding 3.
3. The XML schema forbids the cross-resource linking that the Linking Module
   documents. A `keyref` makes every `member/@ref` local, so
   `scopeRestriction="any"` cannot be honoured in XML. See finding 2.
4. `homographNumber` has contradictory types in the XML and JSON schemas of the
   same standard, and the JSON schema also contradicts the prose, which says the
   property is a number. See finding 4.
5. `entryUnique`, `definitionUnique` and `exampleUnique` cannot be satisfied by
   any document, because they key on mixed content elements. A minimal
   reproduction is in finding 5.
6. `partOfSpeechTagType` asserts a non-empty description while declaring the
   element optional. See finding 6.
7. Section 1.2 uses the RFC 2119 keyword REQUIRED to classify parts of the
   specification document, which reads as an obligation to implement all four
   serializations. See finding 7.
