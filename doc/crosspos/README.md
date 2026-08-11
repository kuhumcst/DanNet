# Cross-PoS hypernymy in DanNet (#146, #153)

This document records what changed, what deliberately did not change, and the
justification for each decision. It is a reference to cite from, not a
narrative. Each section gives the counts, the criterion the code applies, and
the sources the argument rests on.

Two populations are involved. Do not conflate them.

- **A.** `dns:crossPoSHypernym`, DanNet's own bandaid relation, 5,636 triples.
- **B.** plain `wn:hypernym` pairs whose synsets disagree in PoS, 596 pairs.
  `dns:HypernymPOSShape` flags them as warnings.

## Files in this folder

| file | contents |
|---|---|
| `README.md` | this document |
| `vejledning-da.md` | reviewer's guide in Danish, for filling in the CSVs |
| `2d-cross-pos-taxonomy.csv` | 285 pairs awaiting judgement (§B4) |
| `a4-deferred-crosspos.csv` | 104 pairs awaiting judgement (§A4) |
| `2a-verb-phrase-pos-flip.csv` | 110 pairs, record of an applied decision (§B1) |

`dk.cst.dannet.crosspos-review/regenerate!` regenerates the CSVs from the
graph. It preserves manually entered columns. If the counts below have drifted,
it refuses to write. §B2 adds 149 more pairs that no CSV holds yet, so the
review backlog is 538.

## Summary of decisions

| # | batch | count | decision | §|
|---|---|---|---|---|
| A1 | quality-noun targets | 5,413 | → `wn:attribute` | [A1](#a1-wnattribute-5413-triples) |
| A2 | `adj.ppl` verb phrases | 2 | PoS fix + `wn:hypernym` | [A2/B1](#a2-and-b1-pos-corrections) |
| A3 | pertainym/participle leftovers | 117 | deleted | [A3](#a3-deletions-117-triples) |
| A4 | substantivisations, register labels | 104 | retained for review | [A4](#a4-the-retained-104-pairs) |
| B1 | mistagged verb phrases (2a) | 110 | 107 fixed, 3 excluded | [A2/B1](#a2-and-b1-pos-corrections) |
| B2 | mixed-PoS synsets (2b) | 149 | deferred | [B2](#b2-mixed-pos-synsets-149-pairs) |
| B3 | `{2ndOrder}` placeholder (2c) | 52 | not addressed here | [B3](#b3-2ndorder-52-pairs) |
| B4 | genuine cross-PoS taxonomy (2d) | 285 | deferred | [B4](#b4-the-remainder-285-pairs-101-targets) |

The rebuilt SNAPSHOT database gives these counts: `dns:crossPoSHypernym` 104,
`wn:attribute` 5,413, `wn:classified_by` 0, and `HypernymPOSShape` down from
596 to 489. The build asserts every count in this document. Data that
contradicts the document fails the build.

## The core argument

One objection to anticipate: EuroWordNet deliberately sanctioned cross-PoS
hyponymy, DanNet inherits that tradition, and removing it is a regression
rather than a fix.

The answer is that we do not remove the relation. We write it under the name
the Global WordNet Association gives it today. For `attribute`, GWA's relation
documentation lists the corresponding EuroWordNet relation name as
`XPOS_Hyponymy` (<https://globalwordnet.github.io/gwadoc/>, section
"Attribute", table "Project-specific Names"). The same table gives Princeton's
pointer as `=`. So `attribute` is both the EuroWordNet cross-PoS relation and
the Princeton attribute relation, unified under one name.

**DanNet's own specification supports this.** *Lingvistiske specifikationer for
DanNet Version 2* (Pedersen et al. 2011) gives the full relation inventory in
§3, and that inventory contains no cross-PoS hyperonym. Both `has_hyperonym`
examples are noun→noun (*birketræ*, *vejtræ* → *træ*). The only cross-PoS
relation in the inventory is `xpos_near_synonym` (*behandle* → *behandling*),
which appears in every coding template in §6 under *Synonymi*, never under
*Formal: has_hyperonym*.

The same document predicts where the noise is. §1 records that about 30% of the
material came from a semiautomatic process without further enrichment, and
names actions, events, **properties** and abstract entities. §6 adds that those
areas got almost no relation other than `has_hyperonym`. The adjectives live
under properties. §2 supplies the mechanism: the process extracted hypernyms
automatically from DDO's genus proximum. The specification notes that DDO
editors chose genus terms ad hoc, and gives *lære*, *fag* and *videnskab* used
inconsistently for the same kind of concept.

So these triples are not a considered editorial position that we overturn. They
are the residue of a process the specification itself describes. §7 adds that
validation covered only 2% of the material. It reports that `has_hyperonym` was
applied fairly consistently while the other relations varied considerably. That
is the expected pattern for a catch-all slot rather than a considered choice.

The [verification appendix](#appendix-where-to-verify-each-claim) gives
section-by-section locators for all of this.

**Why automation was possible at all:** the 5,636 triples concentrate on only
72 distinct *target* synsets. We analysed each target by hand once. The
decision then applies mechanically to every triple that points at it.

---

# Population A: `dns:crossPoSHypernym` (5,636 triples)

`fix-cross-pos-hypernymy!` resolves these in the release pipeline.

## A1: `wn:attribute` (5,413 triples)

**Criterion.** The target is a quality or property noun ({egenskab},
{karaktertræk}, {farve}, and similar).

**Justification.** GWA defines `attribute` as a relation between a nominal and
an adjectival concept, where one is an attribute of the other. The relation is
self-reciprocal, and the documentation states that it must link only adjectives
to nouns and the reverse. The diagnostic is EuroWordNet test 14: *A is an
attribute of B* / *B is an attribute of A*, with A a singular noun and B an
adjective. Princeton treats the same idea from the other side. It organises
adjectives around attribute nouns, with `=` linking a value adjective to the
attribute it is a value of (Fellbaum 1998).

The 5,413 triples land on 40 target synsets. Every one is a quality, property
or state noun:

| target | triples |
|---|---|
| {beskaffenhed; egenskab; side} | 4,338 |
| {karakteregenskab; karaktertræk; personlighedstræk} | 498 |
| {form} | 128 |
| {stilling; tilstand} | 59 |
| {udseende} | 48 |
| {dygtighed; evne; kapacitet; talent} | 32 |
| {sindstilstand} | 32 |
| {oprindelse} | 29 |
| colour nouns ({farve; kulør}, {grøn}, {brun}, …) | ~150 combined |

`{lilla} attribute {farve}` and `{gråhåret} attribute {udseende}` pass EWN test
14 cleanly. `{lilla} hypernym {farve}` does not pass the hyponymy test (*en
lilla er en slags farve*), which is the point.

Note the concentration: 4,338 of 5,413 point at one synset, {egenskab}. Nobody
decided lexicographically, 4,338 separate times, that a given adjective is a
*kind of* property. The relation was the only slot available.

## A2 and B1: PoS corrections

These are the only changes on this branch that alter word data. They share one
defence: **the evidence is internal to DanNet, so no external authority is
necessary.**

**A2 (2 synsets).** {gøre rent} and {gøre sig til gode} are verb phrases that
carry the `adj.ppl` lexfile. We flipped the PoS to verb and restored the
relation as plain `wn:hypernym`. The lexfiles come from the hypernyms.

**B1 (110 candidates, 107 applied).** These are sources where **every** lemma
is a multi-word phrase and the hypernym is a verb: {gøre kål på} → {spise;
æde}, {slå mønt} → {fremstille}, {drage nytte af} → {anvende}.
`fix-verb-phrase-pos!` implements the fix: **107 synsets, 113 words** flipped
to verb, with lexfiles from the hypernym. Every source was `noun.*` and every
hypernym `verb.*`. The build evaluates the criterion against the data and
asserts the count, so it cannot drift silently. On the rebuild
`HypernymPOSShape` dropped from 596 to 489.

**The argument** is not that a paper says so. It is that a Danish multi-word
expression headed by a verb, sitting under a verb hypernym, cannot be a noun.
The taxonomy was already correct. Only the tag was wrong. This claim is
deliberately narrower than the one we declined for the A4 substantivisations.
To decide that *koreansk* has an established nominal reading needs DDO evidence
per item. To decide that *slå mønt* is not a noun needs only reading it.
**Automate where the data proves the tag wrong. Defer where it takes
lexicographic knowledge.** That line keeps A4, B2 and B4 out of the pipeline.

**Three exclusions**, because "contains a space" also admits non-verbal
multi-word entries:

| synset | lemma | hypernym | why excluded |
|---|---|---|---|
| `dn:synset-27542` | over kors | {anbringe} | prepositional fragment of *lægge armene over kors* |
| `dn:synset-27572` | i pleje | {anbringe} | prepositional fragment of *anbringe i pleje* |
| `dn:synset-30501` | skåret (bygget, ..) over samme læst | {frembringe} | participial/adjectival |

The first two are stranded fragments. They probably do not belong under
{anbringe} as synsets at all, which is a separate defect and not a PoS
question. Two further entries are verbal but are full clauses rather than
phrases ({han (hun, ..) snakker, og røven går}, {munden står ikke stille på
nogen}). We include them, because the alternative is to call them nouns.

`2a-verb-phrase-pos-flip.csv` cannot come from a built database. After the fix
runs, the 107 corrected synsets are verbs and no longer match the noun-source
criterion. So `regenerate!` leaves that file alone. It only makes sure that the
3 still matching are exactly the excluded ones.

## A3: deletions (117 triples)

**Criterion.** Pertainym-flavoured and participle-flavoured pairs
({kvartårlig} → {år}, {hjelmklædt} → {klæde}).

**Justification.** GWA does define `pertainym` and `participle`. Both are
sense-level relations between words, but `dns:crossPoSHypernym` holds between
synsets. No synset-level relation carries the right meaning, so no faithful
replacement exists. This is a deferral rather than a judgement. DanNet can
restore the information properly once it expresses sense-level relations, and
the pairs are listed so that nothing is lost silently.

## A4: the retained 104 pairs

We originally converted these to `wn:classified_by`, on the grounds that the
target is "a substantivised adjective classifying the source". That was wrong
on both counts, and we withdrew the treatment.

GWA's `classified_by` is a *numeral classifier* relation. Morgado Da Costa &
Bond (2016) introduced it for non-referential concepts: 'head' classifying
'cattle', 'rasher' classifying 'bacon', 匹 classifying 猫. Read that way,
`{koreansk} classified_by {sprog}` asserts that *sprog* is the counting word
for *koreansk*.

The heuristic also merged three unrelated phenomena:

| group | pairs | example | what it actually is |
|---|---|---|---|
| languages and dialects | 62 | {koreansk} → {sprog}, {skånsk} → {dialekt} | substantivised adjective; *koreansk* as a noun genuinely **is** a language |
| people | 13 | {hjemløs} → {person}, {deltidsansat} → {ansat} | substantivised adjective; nominal hypernymy once the PoS is corrected |
| swear words and abuse | 26 | {helvedes} → {bandeord; ed; kraftudtryk}, {dum} → {skældsord} | not hypernymy at all; a register label, candidate for `wn:exemplifies` |
| nominal idioms | 3 | {en lille sort} → {kaffe}, {de gamle} → {forældre} | lexicalised nominal; hypernymy after PoS correction |

The 78 substantivisation pairs are mechanically the same as the A2 cases. But
to apply that treatment at scale asserts an established nominal reading for
each of {ikkedansk}, {vestdansk}, {syg} and so on. That is a claim about Danish
that needs DDO evidence per item, and a *larger* lexicographic step than the
attribute relabelling, not a smaller one.

Retention is safe on the export side. `dns:crossPoSHypernym` is not among the
`supported-wn-relations` the WN-LMF exporter emits, so nothing from #146
returns. Retention is also necessary: none of the 104 sources have another
`wn:hypernym`, and deletion orphans all of them. `fix-cross-pos-hypernymy!`
exempts these targets from its final deletion and asserts that exactly 104
survive, so a narrowed target set cannot orphan them silently.

---

# Population B: plain `wn:hypernym` cross-PoS pairs (596)

`dns:HypernymPOSShape` flags 596 distinct pairs whose lexicalizing words
disagree in PoS. The shape fires whenever *any* word pair differs. The pairs
therefore split first by whether the two synsets' PoS sets are disjoint:

| group | pairs | status |
|---|---|---|
| B1 (2a) mistagged verb phrases | 110 | 107 fixed in the pipeline, 3 excluded |
| B2 (2b) mixed-PoS synsets, not disjoint | 149 | deferred |
| B3 (2c) malformed placeholder word | 52 | technical fix, no lexicography |
| B4 (2d) genuine cross-PoS taxonomy | 285 | review CSV |

The four groups are disjoint and sum to 596 exactly, verified against the live
graph. B1 and B3 are classifiable automatically because the data itself shows
the *cause* of the mismatch, not because anybody inspected the pairs
individually. [A2/B1](#a2-and-b1-pos-corrections) above covers B1.

## B2: mixed-PoS synsets (149 pairs)

Pairs where one synset contains words of *differing* PoS, for example
{sove længe}(verb) → {knalde brikker; sove}(noun+verb). `HypernymPOSShape`
fires on `FILTER(?pos1 != ?pos2)`, so these pairs light up even though the two
synsets share a PoS. They are not cross-PoS edges at all. The defect is
word-level PoS inconsistency inside one synset.

The 149 pairs come from 81 distinct synsets. Only 23 have a clear majority PoS
to retag towards. The other 58 have all-singleton counts, with one noun word
and one verb word, so a fix means deciding which of the two is mistagged, one
at a time. To use the hypernym's PoS as a tiebreaker assumes the edge is
correct in order to fix the words, which is the circularity this exercise
avoids. Deferred to manual review.

## B3: `{2ndOrder}` (52 pairs)

This is not a lexicographic question, and this branch does not address it.
{2ndOrder} (`dn:synset-42970`) sits under {TOP} with 51 synsets that point at
it. Its single lexicalizing word, `dn:word-temporary_3`, carries *empty IRIs*
as its PoS values:

    lexinfo:partOfSpeech  lexinfo:
    wn:partOfSpeech       wn:

It is the only word in DanNet with such a value. Two fixes clear all 52 pairs
without touching lexicographic content: drop those two triples, or exempt
ontological-type nodes in the shape. Raise this separately, so that nobody
mistakes it for a EuroWordNet structural decision.

## B4: the remainder (285 pairs, 101 targets)

Genuinely cross-PoS taxonomy: deverbal nouns under verbs ({filtrering} →
{fjerne}), verbs under nouns ({tilsvine} → {talehandling}), nouns under
adjectives ({karmin} → {rød}). Each pair needs a retargeting decision or a
re-relation decision. Only 2 of the 596 sources have an alternative hypernym,
so deletion orphans them.

`2d-cross-pos-taxonomy.csv` holds them, grouped by target. The top 15 targets
cover over half the pairs. Same-lemma retarget candidates are precomputed where
they exist.

---

# Likely objections

**"EuroWordNet allowed cross-PoS hyponymy."** It did, and GWA maps that exact
relation to `attribute`. We keep the link and use the current name.

**"This is a unilateral lexicographic change."** For 5,413 pairs it is a
relabelling that preserves both endpoints. 109 pairs are tag corrections where
the data proves the tag wrong. We leave everything that needs judgement to
editorial review, which is 538 pairs across A4, B2 and B4.

**"DanNet's structure is being altered to suit an export format."** WN-LMF is
why the problem surfaced, but it is not the justification. The justification is
that DanNet's own Version 2 specification never sanctioned this relation, and
that it documents the semiautomatic process that produced it.

**"How do we know the targets were classified correctly?"** The 5,636 triples
concentrate on 72 distinct targets. We analysed each one by hand once, and the
decision then applies mechanically. The source holds the target lists, and the
build asserts the resulting counts, so drift fails the build.

# Appendix: words without a written representation

This is an unrelated defect that we found in passing. It is recorded here
because the obvious query cannot see it. It belongs in its own GitHub issue.

15 words carry a `wn:partOfSpeech` but have no reachable
`ontolex:canonicalForm` → `ontolex:writtenRep`. Any query that joins PoS and
lemma in one pattern therefore drops them silently.

It cost a build. The first version of `fix-verb-phrase-pos!` derived both the
PoS test and the word list from one joined query. For `dn:synset-28142`
({slå benene væk under nogen}) it saw one of two words and flipped only that
one. The synset was left half noun and half verb, which is worse than the
uniform error it replaced. `HypernymPOSShape` then read 490 instead of 489. The
fix was to query PoS and lemmas separately. A comment in
`crosspos-review/index` and in `fix-verb-phrase-pos!` records the split.

The direction of the hazard is important. Five of the 15 synsets have *no*
rep-bearing word at all. A joined query gives them an empty PoS set, and they
fail every PoS test silently. A corrected query makes them newly eligible, so
such a fix can raise a count as easily as lower it:

| synset | label |
|---|---|
| `dn:synset-73853` | {snakke sort} |
| `dn:synset-73849` | {snakke (for) højt om} |
| `dn:synset-73852` | {snakke sammen} |
| `dn:synset-73846` | {tale sig fra noget} |
| `dn:synset-70967` | {køre af} |

None of the five turned out to be reachable as a hypernym of a multi-word noun
synset. The corrected criterion therefore still yields exactly 107. We verified
that rather than assuming it.

# Appendix: where to verify each claim

Copyright limits how much of these sources this document can reproduce. This is
therefore a locator guide and not a quote collection. Each entry gives a direct
link and the exact section to look at. All links work.

## Global WordNet Association, relation documentation

<https://globalwordnet.github.io/gwadoc/> — has per-relation anchors, so these
are deep links:

| claim | where |
|---|---|
| `attribute` corresponds to EuroWordNet's `XPOS_Hyponymy`, and to Princeton's `=` pointer | [#attribute](https://globalwordnet.github.io/gwadoc/#attribute), table "Project-specific Names" |
| `attribute` holds between a nominal and an adjectival concept, and must link only adjectives to nouns and the reverse | [#attribute](https://globalwordnet.github.io/gwadoc/#attribute), definition and comments |
| EuroWordNet test 14, the *A is an attribute of B* diagnostic | [#attribute](https://globalwordnet.github.io/gwadoc/#attribute), test section |
| `classified_by` is a numeral classifier relation ('head' of cattle, 匹 for 猫) | [#classified_by](https://globalwordnet.github.io/gwadoc/#classified_by) and [#classifies](https://globalwordnet.github.io/gwadoc/#classifies) |
| `exemplifies` is the Usage subtype of Domain, Princeton's usage-domain pointer | [#exemplifies](https://globalwordnet.github.io/gwadoc/#exemplifies) |
| `pertainym` and `participle` are sense-level, not synset-level | [#pertainym](https://globalwordnet.github.io/gwadoc/#pertainym), [#participle](https://globalwordnet.github.io/gwadoc/#participle) |

## Lingvistiske specifikationer for DanNet Version 2 (2011)

<https://cst.ku.dk/projekter/dannet/dannetspecifikationer_v2.pdf> (PDF, ~300 KB)

The internal source, and the one most worth citing. Four passages carry the
argument:

**§1 Indledning, p. 3** states that about 30% of the material came from a
semiautomatic process without further enrichment. It names the affected areas
as actions, events, **properties** and abstract entities. The opening clause
reads "Ca. 30 % af materialet er blevet produceret semiautomatisk uden
yderligere berigelse". The adjectives live under properties, so this is the
most direct statement that the cross-PoS hypernyms come from an un-enriched
automatic process.

**§2, subsection "Organisering af det leksikalske net", pp. 5-6** explains the
mechanism. The net is organised on the nearest Danish superordinate, extracted
automatically from DDO. The section then notes that DDO's superordinates came
from no established ontological system. The individual editor chose them. It
gives the worked example of one editor choosing *lære* for *informatik* and
*bromatologi*, another *fag* for *samfundsfag*, and a third *videnskab* for
*datalogi*. The same passage says that harmonising these was DanNet's own job.

**§3 Relationer og træk, pp. 8-9** is the complete relation inventory. Point at
two things. Both `has_hyperonym` examples are noun→noun (*birketræ* → *træ*,
*vejtræ* → *træ* with the `ortho` feature). The only cross-PoS relation
anywhere in the table is `xpos_near_synonym`, with the example *behandle* →
*behandling*. The table has no cross-PoS hyperonym relation.

**§6 Eksempler, p. 25** notes that semantic relations are most prominent for
concrete objects. For actions and abstract entities, this version of DanNet has
almost nothing but `has_hyperonym`. The coding templates that follow (pp.
26-37) put `xpos_near_synonym` under *Synonymi* in every template, never under
*Formal: has_hyperonym*.

**§7 Validering, p. 38** is a useful supplement. Validation covered only 2% of
the material. The section reports that `has_hyperonym` is given fairly
consistently throughout, while the other relations vary considerably in how
finely they were applied. That supports reading `has_hyperonym` as the catch-all
slot rather than a considered choice.

## Morgado Da Costa & Bond (2016)

<https://aclanthology.org/L16-1685.pdf> (PDF, ~470 KB)

This paper introduces the non-referential concepts that `classifies` and
`classified_by` were added for. Cite it for the point that the relation was
designed for numeral classifiers. That is what rules the relation out for the
substantivised adjectives in §A4.

## EuroWordNet General Document (Vossen, ed., 1999)

<https://globalwordnet.github.io/gwadoc/pdf/EWN_general.pdf> (PDF, ~1.3 MB)

The primary source for the EuroWordNet relation set and its tests, including
the XPOS relations. Use it for the original wording of the cross-PoS relations
rather than GWA's mapping of them.

## Princeton WordNet

Fellbaum, C. (ed.) (1998). *WordNet: An Electronic Lexical Database*. MIT Press.
There is no open link. The DanNet specification cites pp. 23-47 for the
relevant chapter. Cite it for adjectives organised around attribute nouns
rather than taxonomically, with `=` linking a value adjective to its attribute.

## Global Wordnet Formats

<https://globalwordnet.github.io/schemas/> — Bond, Vossen, McCrae & Fellbaum
(2016). The schema definitions behind WN-LMF, that is, the format constraint
that surfaced all of this in #146.

## Pedersen et al. (2009)

*DanNet: the challenge of compiling a wordnet for Danish by reusing a
monolingual dictionary*. Language Resources and Evaluation 43(3), 269-299.
<https://doi.org/10.1007/s10579-009-9092-1> (probably paywalled outside the
university network). The specification points here for the fuller
methodological account.

# References

- Bond, F., Vossen, P., McCrae, J. P. & Fellbaum, C. (2016). *Global Wordnet
  Formats*. Proceedings of the Global WordNet Conference 2016.
  <https://globalwordnet.github.io/schemas/>
- Fellbaum, C. (ed.) (1998). *WordNet: An Electronic Lexical Database*. MIT Press.
- Global WordNet Association. *Open Wordnet Documentation*.
  <https://globalwordnet.github.io/gwadoc/> (relations `attribute`,
  `classifies`/`classified_by`, `exemplifies`, `pertainym`, `participle`)
- Morgado Da Costa, L. & Bond, F. (2016). *Wow! What a Useful Extension!
  Introducing Non-Referential Concepts to Wordnet*. LREC 2016.
  <https://www.aclweb.org/anthology/L16-1685.pdf>
- Pedersen, B. S., Braasch, A., Nimb, S., Asmussen, J., Sørensen, N.,
  Lorentzen, H. & Trap-Jensen, L. (2011). *Lingvistiske specifikationer for
  DanNet Version 2*.
  <https://cst.ku.dk/projekter/dannet/dannetspecifikationer_v2.pdf>
- Pedersen, B. S., Nimb, S., Asmussen, J., Sørensen, N., Trap-Jensen, L. &
  Lorentzen, H. (2009). *DanNet: the challenge of compiling a wordnet for
  Danish by reusing a monolingual dictionary*. Language Resources and
  Evaluation 43(3), 269–299.
- Vossen, P. (ed.) (1999). *EuroWordNet General Document*. University of
  Amsterdam. <https://globalwordnet.github.io/gwadoc/pdf/EWN_general.pdf>
