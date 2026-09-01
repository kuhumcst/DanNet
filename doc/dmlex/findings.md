# What we learned about DMLex when we implemented it

This document is the companion to `plan.md`. The plan says what the export
does. This document says what surprised us on the way, and why. Each entry must
be readable months later, by a reader who does not know the schemas.

Seven of the nine findings are faults in the artifacts of the standard or in
its wording. They are not related to DanNet. Findings 1 and 9 are limits of the
model itself. The first is a design limitation which the editors made on
purpose. The second is a gap that the schemas then close. The end of this
document collects all of them as feedback for the LEXIDMA committee.

## 1. There is no synset in DMLex

DMLex models dictionaries. Its world is entries and senses. An entry has one
headword and holds its senses. The standard has no object for a shared
concept. Therefore the central object of DanNet has no home.

The standard gives an answer in its own example A.1.15: a synset becomes a
`relation` of the type `synonym` between the member senses. The export uses
this method. But the standard gives no name for the group. A `relation` has a
type and members, and no identifier. A consumer can rebuild the groups, but
cannot point at one group and say "this is `dn:synset-1876`".

Our answer is the Controlled Values module. Each synset gets a `labelTag`. The
`sameAs` of the tag holds the DanNet URI. Each sense of the synset carries the
label:

```xml
<labelTag tag="synset-1876" typeTag="synset" for="sense">
  <description>{have_1§1}</description>
  <sameAs uri="https://wordnet.dk/dannet/data/synset-1876"/>
</labelTag>
```

This use of a label is wider than the standard intends. The standard describes
labels as restrictions, for example a register or a domain. But the method is
fully valid. It is also the only place in the model where one declared object
can attach to a sense and carry an external URI.

This gap is not an oversight by the DMLex authors. John McCrae, the author of
OntoLex and of the ILI, is one of the editors. A concept object in DMLex is a
copy of OntoLex, which DanNet already publishes. The concept-centric view
stays in the RDF. DMLex is the dictionary view.

## 2. A relation member must point inside the same file

This finding caused a rewrite of the plan, because our first method was not
correct.

A `relation` holds `member` objects, and each member has a `ref`. Our first
method put a DanNet URI or an ILI identifier into a `ref`, as a free string.
Two parts of the plan used this method. The XML schema rejects it:

```xml
<xs:keyref name="memberRef" refer="entryOrSenseOrCollocateMarkerKey">
  <xs:selector xpath=".//member"/>
  <xs:field xpath="@ref"/>
</xs:keyref>
```

Each `ref` must be equal to the `id` of an entry or of a sense in the same
document. That is the schema. The prose of the standard is less strict. It
says that DMLex "does not prescribe the exact form of these IDs". The Linking
module advertises relations "between objects residing in different
lexicographic resources". Section 3.2.1 of the standard gives a recommended
IRI scheme for that exact purpose.

Therefore the XSD is more strict than the standard that it implements.
Cross-resource links are a documented feature of the Linking module, and the
XML serialization cannot express them. The prose defines the value
`scopeRestriction="any"` as "no restriction". No XML document can obey it.

As a concrete example, the standard permits one relation with members in two
different dictionaries. The far member uses the IRI scheme of section 3.2.1:

```xml
<relation type="translation">
  <member ref="sense-21955" role="source"/>
  <member ref="example.com/other-dictionary/entry/dog~noun/sense/0~an%20animal" role="target"/>
</relation>
```

The second member is the exact use case of section 3.2.1. The XML schema
rejects it, because no entry or sense in the file has that id. The feature
exists in the prose and is not available in XML.

For the export, the result is the same in each case. The objects that DanNet
points at are not DMLex objects in another lexicographic resource. They are
OEWN synsets and ILI concepts, published as RDF. Therefore a DanNet relation
that points out of DanNet cannot become a DMLex relation. But the reason is
important: the XSD forbids it, not the standard.

One external claim survives: `wn:ili`. The reason is important to keep. The
test is the type of the claim, not the dataset. DMLex has one method for an
external reference, the `sameAs` URI, and a `sameAs` URI says that two things
are the same. The DanNet schema declares `wn:ili` as a sub-property of
`skos:exactMatch`. That is an identity claim, and it fits. The schema declares
`dns:eqHypernym` as a sub-property of `skos:broadMatch`. A `sameAs` URI for it
says something that DanNet does not say. An identity claim can cross the file
boundary. Other relations cannot.

NOTE: The JSON schema cannot express this rule, because JSON Schema has no
referential integrity. The same error gives a valid JSON file and an invalid
XML file.

## 3. About two validators in the world can read the XML schema

The DMLex XSD files are XSD 1.1. They use `xs:assert` and the 1.1-only
attribute `xpathDefaultNamespace`.

XSD 1.1 became a W3C Recommendation in 2012. By that time, interest in XML
schema languages was small, and almost nobody implemented the new version. The
practical set of processors is Xerces-J and Saxon EE. The JDK contains a fork
of Xerces that reads only XSD 1.0. The tools `xmllint` and libxml2 did not
implement 1.1, and .NET and the usual Python libraries also did not.
Therefore no common tool can validate the file.

The XSD 1.1 work also never merged into the Xerces trunk. Each version of
`xerces:xercesImpl` on Maven Central is 1.0 only. The 1.1 build lives on the
branch `xml-schema-1.1-dev`. The only copy on Maven Central is a third-party
package from the OGC conformance-test project:

```clojure
org.opengis.cite.xerces/xercesImpl-xsd11 {:mvn/version "2.12-beta-r1667115"}
com.ibm.icu/icu4j                        {:mvn/version "77.1"}
```

This package is a 2015 beta, built for JDK 7. It works. The ICU4J line is
necessary because the assertions are XPath 2.0. Xerces sends them to an
Eclipse XPath processor that uses ICU4J and declares no dependencies in its
pom. Without ICU4J, the first document gives a `NoClassDefFoundError`, and the
metadata gives no clue.

These dependencies are in the `:validate` alias. Therefore only validation
pays for them.

Memory is the other cost, and we learned it late. Xerces checks the identity
constraints, for example the `keyref` over 753,858 member references, with
tables that stay in memory for the full document. The 92 MB DanNet-only file
validated in seconds under a default JVM heap. The 127 MB file with the COR
inflections needs a heap of approximately 16 GB. If the heap is too small, the
JVM becomes unresponsive or stops, and an attached REPL is lost with it.
Therefore validate a full export in its own process, for example with
`clj -J-Xmx16g -M:validate`.

## 4. The two schemas do not agree about `homographNumber`

The XSD says that the property is a number:

```xml
<xs:attribute name="homographNumber" use="optional" type="xs:integer"/>
```

The JSON schema says that it is a string:

```json
"homographNumber": { "type": "string" }
```

The same property, in the same standard, at the same version, has two types
that do not agree. Therefore one intermediate data structure cannot serialize
into both formats without a conversion. The function `json-safe` in
`dmlex.clj` does this conversion. If the committee aligns the two schemas,
delete the function.

## 5. No document can satisfy three of the uniqueness rules

This finding is the large one. The error message points at the wrong place.
Therefore only a minimal reproduction made us sure.

The intent of the schema is reasonable: two entries must not share one
headword, one homograph number, and one part of speech. In XSD, this
constraint is like a UNIQUE index over three columns:

```xml
<xs:unique name="entryUnique">
  <xs:selector xpath="entry"/>          <!-- the rows -->
  <xs:field xpath="headword"/>          <!-- column 1 -->
  <xs:field xpath="@homographNumber"/>  <!-- column 2 -->
  <xs:field xpath="partOfSpeech/@tag"/> <!-- column 3 -->
</xs:unique>
```

The rule to obey is this: a field must point at one plain value, an attribute
or an element that holds only text. The validator must make one string of the
field to compare the rows.

DMLex points the field at `headword`, and declares `headword` as mixed
content, so that it can hold markers:

```xml
<headword>gå <placeholderMarker>rundt</placeholderMarker></headword>
```

Then no single value is available for the comparison. Is the column
`gå rundt`, or `gå`, or the markup also? XSD does not guess. XSD gives an
error, on every entry, and also on the most simple document possible:

```xml
<lexicographicResource xmlns="http://docs.oasis-open.org/lexidma/ns/dmlex-1.0" langCode="da">
  <entry><headword>hund</headword></entry>
</lexicographicResource>
```

Our export gave 173,564 of these errors, one per entry and one per definition,
plus 265 errors of a second type. The second type is the same fault with a
different symptom. For `example/text`, the validator takes the unusable value
as null, without an error. Two examples under one sense then both become null
and look like copies of each other.

The message text is the misleading part. It reads `A field of identity
constraint 'entryUnique' matched element 'lexicographicResource', but this
element does not have a simple type`. The message names the element that owns
the constraint, not the element that the field found. Therefore the root
element looks at fault.

If a proof is necessary again, here is the full reproduction. Case A fails.
Case B is identical, but its field has a simple type, and it passes:

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

The three constraints with this fault are `entryUnique` on `headword`,
`definitionUnique` on `definition/text`, and `exampleUnique` on
`example/text`. The committee cannot make these elements plain text, because
the markers are the point. They must key on something else, or remove the
rules. They already removed seven other constraints, as the comment at the top
of the XSD explains.

Our file is otherwise valid. Each other check of the validator passes, which
includes the 753,858 member references of finding 2. The validation code
counts these known schema errors apart from the real errors, and does not
hide them. Therefore a genuine error cannot disappear into the pile.

One caution: we confirmed this with one processor. The XSD rule is clear, and
we believe that Xerces applies it correctly. But Saxon EE is the only other
implementation, and we do not have it.

The other direction is also important. `inflectedFormUnique` keys on `text`
and on `@tag`. Both fields are simple types, so this constraint operates as
intended. Therefore the export removes each duplicate pair of code and text
before serialization. A repeated pair is a genuine error in `:errors`, not one
of the 173,829 errors that the validator ignores. This generalizes into a
cheap test: read the fields of a uniqueness constraint. If a field lands on
mixed content, the constraint is dead, and its checks are your work. If all
fields are simple types, the constraint applies, and you must satisfy it.

## 6. An optional description that is not optional

This fault is smaller and of the same family. `partOfSpeechTagType` declares
its description as optional, and then asserts that the description is not
empty:

```xml
<xs:element name="description" minOccurs="0" type="xs:string"/>
<xs:assert test="string-length(description)>0"/>
```

An absent element has the string length 0. Therefore the assertion fails when
the optional element is not present. In effect, the description is mandatory.
The correction is cheap: our three part-of-speech tags carry Danish
descriptions.

NOTE: This fault is specific to `partOfSpeechTagType`. `inflectedFormTagType`
declares its description in the same way and has no assertion. There, the
optional element is optional. Do not apply the rule of one tag type to another
tag type. The assertions are hand-written per type and are not consistent.

## 7. One serialization is sufficient for conformance

Section 1.2 of the specification reads:

> In this document, we specify REQUIRED serializations for: XML, JSON, RDF, and
> relational databases. An informative serialization specification is provided
> for: NVH.

A quick read says that a conformant implementation must provide all four
serializations. That is not correct, and the conformance section says so:

> Conformant DMLex Instances MUST be well formed and valid instances according
> to **one of** the normative DMLex Serialization Specifications.

A note under it adds that an instance cannot be conformant without conformance
to one specific serialization. Therefore conformance is a property of one file
in one serialization. Our XML file is conformant alone, and our JSON file is
conformant alone. One of the two files is also sufficient.

In section 1.2, the word "REQUIRED" classifies the parts of the specification
document. It does not give a duty to implementers. The four serializations are
normative parts of the document, and NVH is an informative appendix. The
section headings say this more clearly than the sentence does. They read
"5 DMLex REQUIRED Serializations (Normative)" against "A.2 DMLex NVH
serialization (Informative)".

The wording is unfortunate. REQUIRED is an RFC 2119 keyword. The front matter
of the document says that these keywords have their RFC 2119 sense. Here the
keyword classifies document sections and puts a requirement on nobody. It
misled us, and it will mislead others.

One related point is important when a user asks for both formats. The standard
puts a round trip between serializations out of its scope. The two files are
compatible in meaning, but their identifiers, uniqueness scopes, and
addressing mechanisms have no guarantee of a match. Therefore the export mints
the same identifiers for both files, and does not depend on the standard to
relate them.

## 8. Nothing in DMLex holds a number that you supply

The sentiment data adds two facts to a word. The first is a direction:
Positive, Neutral, or Negative. The second is a strength: an integer from -3
to 3. The direction has an obvious home, because a label is a category tag
from a declared inventory. The strength has no home.

The first place to look is the Annotation module, because "annotation" is the
word for this data. The module does not help. It holds inline markers only:
`headwordMarker` and `collocateMarker` in the text of a definition or of an
example, and `placeholderMarker` in a headword. No annotation object attaches
to a sense or to an entry. Core, Linking, and Controlled Values also have no
numeric property that an exporter can fill. `listingOrder` and
`homographNumber` are numbers, but they belong to the model, not to your data.

Therefore the label is the only mechanism in the model that attaches a typed
value to a sense or to an entry, and a label is a category. We used it: a
second inventory, `sentimentValue`, with one tag per value, and with
`marl:polarityValue` as the `sameAs` of its `labelTypeTag`. The polarity
inventory stays separate, so that a consumer can take one inventory and
ignore the other. The number survives the trip.

The numeric nature does not survive. Nothing in the file says that these
seven tags are numeric, or which scale they use, or that `-3` is stronger
than `-2`. The model cannot express the order. A generic DMLex consumer sees
seven opaque strings, and only a consumer that knows the scheme can decode
them. For us this trade is acceptable, because our consumer is a knowledge
graph that also holds the RDF. It is not a general answer.

The limitation is wider than sentiment. A confidence score, a corpus
frequency, a salience weight, and every other graded annotation hit the same
wall. DMLex can say that a sense is rare, because `rarelyUsed` is a category.
DMLex cannot say how rare. We have a workaround, but the finding is worth a
report to the committee. The workaround is exactly the type of private
convention that a standard exists to prevent.

## 9. A DMLex file cannot say how it wants to be read

DMLex describes itself as a data model and not an encoding format, so a
silence about presentation is defensible. But the model is not silent about
names: it obliges an exporter to invent them. Every label type, every relation
type, and every member role is a tag that the exporter coins. A reader then
sees those tags. Ours are `slangRegister`, `holo_substance`,
`co_agent_instrument`, `noun.animal`, `Female` and `zoo`. They are good
identifiers and poor Danish.

The obvious answer is the `description` of a tag. It does not answer. A tag has
exactly one, and the model does not say what it is for. Ours are definitions,
because that is the useful thing to carry: "Dyr, i modsætning til mennesker,
planter og fantasivæsener; f.eks. dyr, hund." A reader wants the word *dyr* in
the list and the sentence on hover. One field cannot be both, and nothing marks
which one it holds.

Direction is the sharper case. A relation type has one name and two ends. The
name of the type is almost never the name to show a reader, because the reader
stands at one end of it. `wn:hypernym` reads as *har overbegreb* from below and
*har underbegreb* from above. DMLex has the right hook, `memberType/@role`, and
we use it. But a role is again a machine name, and there is nowhere to say what
to call it in the language of the resource.

Beyond names, four ordinary editorial acts have no expression at all. A
dataset cannot order the types, and it cannot gather five of them under one
heading. It cannot hide a type from a reader, and it cannot show a qualifier
inside its host. The model does have one presentation hook,
`obverseListingOrder` on a member, and `listingOrder` on senses and other
objects. We use the first, to carry the prominence measure that ranks the
DanNet relations. That the editors provided it is the clearest evidence that
the need is real, but the model stops there.

The gap alone is a small finding, but the schemas then close the door.
`lexicographicResource` allows fifteen properties, and the JSON schema ends
the object with `additionalProperties: false`. The XSD fixes the sequence.
There is no extension point, no `<extension>` element, no namespace for
private content. An exporter with anything to say beyond the fifteen cannot
say it in the file at all. Licence and rights hit the same wall, which is why
this export already ships a `metadata.json` beside the data.

So we ship a second companion, `presentation.json`. It holds the Danish names,
the order, the grouping, and the display choices. It works, and the viewer
applies it without knowledge of what any tag means. It is also, once again,
exactly the sort of private convention that a standard exists to prevent. This
export now carries two of them. See finding 8 for the first.

## How well the export follows the tenets of DMLex

The full export is complete: DanNet, COR, and the sentiment data, valid in
both serializations. Therefore we can answer a question that the findings
above answer only piece by piece. The export is conformant in every part, but
the fit with the model is not equal in every part. The fit follows the
distance from the dictionary tradition that DMLex models. The morphology fits
fully. The labels fit well. The wordnet structure fits by convention. The
numbers do not fit.

Some parts truly follow the tenets. Serialization independence, "a data model,
not an encoding format", is real in our pipeline. One intermediate structure
produces two serializations with identical content. Our pipeline follows this
tenet more faithfully than the artifacts of the standard do. The two schemas
give `homographNumber` two different types (finding 4), and referential
integrity exists only in the XSD (finding 2). Therefore the shared
intermediate structure, not the schemas, makes sure that the XML and the JSON
say the same thing.

The discipline of trees plus Linking also fits cleanly. Entry to sense is the
only hierarchy. All 273,025 relations go through the Linking module, with
roles from `owl:inverseOf`. The best citizen is the newest arrival. A COR
inflection lands on `inflectedForm`, with a tag from a declared
`inflectedFormTag` inventory: a form, its text, its paradigm slot, its
readable description. This is the exact dictionary use case that the Core
module models. No part of it is a workaround.

Other parts conform to the letter and lean on convention. The core of DMLex
is semasiological: from word to meaning. DanNet asserts the opposite
direction. The inversion loses no content, but the shared concept survives
only as a `labelTag` with a `sameAs` URI (finding 1). A consumer that does not
know the convention sees 77,285 senses with copied definitions, and must
discover that 70,000 of the "controlled values" are really an identity
register. This stretches the Controlled Values tenet: an inventory with the
cardinality of the data is not really a controlled vocabulary. The
`sentimentValue` inventory goes further. Its seven category tags encode an
ordered numeric scale, and the file cannot state the order (finding 8). Both
methods are valid, but no generic DMLex consumer can decode them.

One tenet is not available to us at all. The Linking module advertises
cross-resource relations, but the XSD makes every member reference local
(finding 2). Therefore the outward claims of DanNet, at Open English WordNet
and at the non-identity ILI mappings, have no conformant home. Only `wn:ili`
survives, as the identity claim in `sameAs`.

One question falls outside the tenets altogether. Everything above weighs how
the content fits, and the content fits. What the file cannot carry is its own
reading. The Danish names, the order, and the grouping turn 273,025 relations
into a page a Dane can use. They travel in a companion file, because the model
has no room for them and both schemas refuse an extension (finding 9). The
export is therefore conformant and incomplete at the same time, in a way that
conformance was never asked to measure.

The summary: as a dictionary, the export is close to a model citizen. The
headwords, homograph numbers, parts of speech, inflection paradigms, labels,
definitions, and examples are idiomatic DMLex. As a wordnet carrier, the
export is a conformant encoding whose meaning lives partly in conventions
outside the file. This gap is not a fault of the conversion. It is the
measured distance between two lexicographic traditions. It is also exactly
what the feedback list below gives to the LEXIDMA committee.

## Feedback for the LEXIDMA committee

Ten points worth a report:

1. Synset identity has no home in the model. Concept identity survives only
   by convention, as a Controlled Values tag. See finding 1.
2. The XSD files need an XSD 1.1 processor. About two implementations exist,
   and no common command-line tool is one of them. The standard does not
   warn about this. See finding 3.
3. The XML schema forbids the cross-resource links that the Linking module
   documents: a `keyref` makes every `member/@ref` local, so no XML document
   can obey `scopeRestriction="any"`. See finding 2.
4. `homographNumber` is a number in the XML schema and in the prose, but a
   string in the JSON schema. See finding 4.
5. No document can satisfy `entryUnique`, `definitionUnique` or
   `exampleUnique`: they key on mixed-content elements. See finding 5.
6. `partOfSpeechTagType` declares the description optional, but asserts that
   it is not empty. See finding 6.
7. Section 1.2 uses the RFC 2119 keyword REQUIRED to classify parts of the
   specification document. It reads as a duty to implement all four
   serializations. See finding 7.
8. No property holds a graded value. A number from the exporter, such as a
   sentiment strength, becomes an inventory of opaque tags with no order and
   no scale. See finding 8.
9. A file cannot say how it wants to be read. Tags are machine names, member
   roles have no display names, and ordering, grouping and hiding have no
   expression. Both schemas refuse extensions, so none of this can travel
   with the file. See finding 9.
10. The one display hint that the model has is costly. In our JSON file,
    `obverseListingOrder` takes 21.6 MB of a 155 MB export: a 21-character
    property name and a number on each of 754044 relation members. A
    shorter name alone can save most of that.
