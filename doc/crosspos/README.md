# Cross-PoS hypernymy in DanNet (#146, #153)

What was changed, what was deliberately not changed, and the justification for
each decision. This is a reference to cite from, not a narrative: every section
gives the counts, the criterion the code applies, and the sources the argument
rests on.

Two populations are involved and should not be conflated:

- **A.** `dns:crossPoSHypernym`, DanNet's own bandaid relation, 5,636 triples.
- **B.** plain `wn:hypernym` pairs whose synsets disagree in PoS, 596 pairs,
  flagged as warnings by `dns:HypernymPOSShape`.

## Files in this folder

| file | contents |
|---|---|
| `README.md` | this document |
| `vejledning-da.md` | reviewer's guide in Danish, for filling in the CSVs |
| `2d-cross-pos-taxonomy.csv` | 285 pairs awaiting judgement (§B4) |
| `a4-deferred-crosspos.csv` | 104 pairs awaiting judgement (§A4) |
| `2a-verb-phrase-pos-flip.csv` | 110 pairs, record of an applied decision (§B1) |

The CSVs are regenerated from the graph by
`dk.cst.dannet.crosspos-review/regenerate!`, which preserves manually entered
columns and refuses to write if the counts below have drifted. §B2 adds a
further 149 pairs not yet extracted to a CSV, giving a review backlog of 538.

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

Verified against the rebuilt SNAPSHOT database: `dns:crossPoSHypernym` 104,
`wn:attribute` 5,413, `wn:classified_by` 0, and `HypernymPOSShape` down from
596 to 489. Every count here is asserted at build time, so the build fails
rather than shipping data that contradicts this document.

## The core argument

The objection to anticipate is that EuroWordNet deliberately sanctioned
cross-PoS hyponymy, that DanNet inherits that tradition, and that removing it
is a regression rather than a fix.

The answer is that we are not removing the relation. We are writing it under
the name the Global WordNet Association gives it today. GWA's relation
documentation lists, for `attribute`, the corresponding EuroWordNet relation
name as `XPOS_Hyponymy` (<https://globalwordnet.github.io/gwadoc/>, section
"Attribute", table "Project-specific Names"). The same table gives Princeton's
pointer as `=`. So `attribute` is simultaneously the EuroWordNet cross-PoS
relation and the Princeton attribute relation, unified under one name.

**DanNet's own specification supports this.** *Lingvistiske specifikationer for
DanNet Version 2* (Pedersen et al. 2011) gives the full relation inventory in
§3, and there is no cross-PoS hyperonym in it. Both `has_hyperonym` examples
are noun→noun (*birketræ*, *vejtræ* → *træ*); the only cross-PoS relation in
the inventory is `xpos_near_synonym` (*behandle* → *behandling*), which appears
in every coding template in §6 under *Synonymi*, never under *Formal:
has_hyperonym*.

The same document predicts where the noise is. §1 records that roughly 30% of
the material was produced semiautomatically without further enrichment, naming
actions, events, **properties** and abstract entities; §6 adds that those areas
got essentially no relation other than `has_hyperonym`. Properties are where
the adjectives live. §2 supplies the mechanism: hypernyms were extracted
automatically from DDO's genus proximum, and the specification notes that DDO
editors chose genus terms ad hoc, giving *lære*, *fag* and *videnskab* used
inconsistently for the same kind of concept.

So these are not a considered editorial position being overturned. They are the
residue of a process the specification itself describes. §7 adds that only 2%
of the material was ever validated, and that `has_hyperonym` was applied fairly
consistently while the other relations varied considerably, which is what you
would expect if it was the catch-all slot rather than a considered choice.

Section-by-section locators for all of this are in the
[verification appendix](#appendix-where-to-verify-each-claim).

**Why automation was possible at all:** the 5,636 triples concentrate on only
72 distinct *target* synsets. Each target was analysed by hand once, and the
decision then applies mechanically to every triple pointing at it.

---

# Population A: `dns:crossPoSHypernym` (5,636 triples)

Resolved by `fix-cross-pos-hypernymy!` in the release pipeline.

## A1: `wn:attribute` (5,413 triples)

**Criterion.** The target is a quality/property noun ({egenskab},
{karaktertræk}, {farve}, …).

**Justification.** GWA defines `attribute` as holding between a nominal and an
adjectival concept, where one is an attribute of the other. It is
self-reciprocal, and the documentation states it should link only adjectives to
nouns and the reverse. The diagnostic is EuroWordNet test 14: *A is an
attribute of B* / *B is an attribute of A*, with A a singular noun and B an
adjective. Princeton's treatment is the same idea from the other side:
adjectives are organised around attribute nouns, with `=` linking a value
adjective to the attribute it is a value of (Fellbaum 1998).

The 5,413 triples land on 40 target synsets, every one a quality-, property- or
state-denoting noun:

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

Note the concentration: 4,338 of 5,413 point at a single synset, {egenskab}.
Nobody decided lexicographically, 4,338 separate times, that a given adjective
is a *kind of* property. The relation was the only slot available.

## A2 and B1: PoS corrections

The only changes on this branch that alter word data, and they share one
defence: **the evidence is internal to DanNet, so no external authority is
needed.**

**A2 (2 synsets).** {gøre rent} and {gøre sig til gode}, verb phrases carrying
the `adj.ppl` lexfile. PoS flipped to verb, relation restored as plain
`wn:hypernym`, lexfiles inherited from the hypernyms.

**B1 (110 candidates, 107 applied).** Sources where **every** lemma is a
multi-word phrase and the hypernym is a verb: {gøre kål på} → {spise; æde},
{slå mønt} → {fremstille}, {drage nytte af} → {anvende}. Implemented as
`fix-verb-phrase-pos!`: **107 synsets, 113 words** flipped to verb, lexfiles
inherited from the hypernym (every source was `noun.*`, every hypernym
`verb.*`). The criterion is evaluated against the data at build time and the
count asserted, so it cannot drift silently. On the rebuild `HypernymPOSShape`
dropped from 596 to 489.

**The argument** is not that a paper says so. It is that a Danish multi-word
expression headed by a verb, sitting under a verb hypernym, cannot be a noun.
The taxonomy was already correct; only the tag was wrong. This is deliberately
a narrower claim than the one declined for the A4 substantivisations: deciding
that *koreansk* has an established nominal reading requires DDO evidence per
item, whereas deciding that *slå mønt* is not a noun requires only reading it.
**Automate where the data proves the tag wrong; defer where it takes
lexicographic knowledge.** That line is what keeps A4, B2 and B4 out of the
pipeline.

**Three exclusions**, because "contains a space" also admits non-verbal
multi-word entries:

| synset | lemma | hypernym | why excluded |
|---|---|---|---|
| `dn:synset-27542` | over kors | {anbringe} | prepositional fragment of *lægge armene over kors* |
| `dn:synset-27572` | i pleje | {anbringe} | prepositional fragment of *anbringe i pleje* |
| `dn:synset-30501` | skåret (bygget, ..) over samme læst | {frembringe} | participial/adjectival |

The first two are stranded fragments that arguably should not be synsets under
{anbringe} at all; a separate defect, not a PoS question. Two further entries
are verbal but are full clauses rather than phrases ({han (hun, ..) snakker, og
røven går}, {munden står ikke stille på nogen}); they are included, since the
alternative is to call them nouns.

`2a-verb-phrase-pos-flip.csv` cannot be regenerated from a built database: once
the fix has run the 107 corrected synsets are verbs and no longer match the
noun-source criterion, so `regenerate!` leaves that file alone and only checks
that the 3 still matching are exactly the excluded ones.

## A3: deletions (117 triples)

**Criterion.** Pertainym- and participle-flavoured pairs ({kvartårlig} → {år},
{hjelmklædt} → {klæde}).

**Justification.** GWA does define `pertainym` and `participle`, but both are
sense-level (word-to-word) relations, whereas `dns:crossPoSHypernym` is
asserted between synsets. There is no synset-level relation with the right
meaning, so no faithful replacement exists. This is a deferral rather than a
judgement: the information can be restored properly once DanNet expresses
sense-level relations, and the pairs are listed so nothing is lost silently.

## A4: the retained 104 pairs

These were originally converted to `wn:classified_by`, on the grounds that the
target is "a substantivised adjective classifying the source". That was wrong
on both counts, and the treatment has been withdrawn.

GWA's `classified_by` is a *numeral classifier* relation, introduced for
non-referential concepts by Morgado Da Costa & Bond (2016): 'head' classifying
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

The 78 substantivisation pairs are mechanically the same as the A2 cases, but
applying that treatment at scale would assert an established nominal reading
for each of {ikkedansk}, {vestdansk}, {syg} and so on. That is a claim about
Danish requiring DDO evidence per item, and a *larger* lexicographic step than
the attribute relabelling, not a smaller one.

Retention is safe on the export side: `dns:crossPoSHypernym` is not among the
`supported-wn-relations` the WN-LMF exporter emits, so nothing from #146
returns. It is also necessary, since none of the 104 sources have another
`wn:hypernym` and deletion would orphan all of them.
`fix-cross-pos-hypernymy!` exempts these targets from its final deletion and
asserts that exactly 104 survive, so narrowing a target set cannot silently
orphan them.

---

# Population B: plain `wn:hypernym` cross-PoS pairs (596)

`dns:HypernymPOSShape` flags 596 distinct pairs whose lexicalizing words
disagree in PoS. The shape fires whenever *any* word pair differs, so they
split first by whether the two synsets' PoS sets are actually disjoint:

| group | pairs | status |
|---|---|---|
| B1 (2a) mistagged verb phrases | 110 | 107 fixed in the pipeline, 3 excluded |
| B2 (2b) mixed-PoS synsets, not disjoint | 149 | deferred |
| B3 (2c) malformed placeholder word | 52 | technical fix, no lexicography |
| B4 (2d) genuine cross-PoS taxonomy | 285 | review CSV |

Disjoint, summing to 596 exactly, verified against the live graph. B1 and B3
are classifiable automatically because the *cause* of the mismatch is
detectable from the data itself, not because the pairs were individually
inspected. B1 is covered under [A2/B1](#a2-and-b1-pos-corrections) above.

## B2: mixed-PoS synsets (149 pairs)

Pairs where one synset itself contains words of *differing* PoS, e.g.
{sove længe}(verb) → {knalde brikker; sove}(noun+verb). `HypernymPOSShape`
fires on `FILTER(?pos1 != ?pos2)`, so these light up even though the two
synsets share a PoS. They are not cross-PoS edges at all; the defect is
intra-synset word-level PoS inconsistency.

The 149 pairs come from 81 distinct synsets. Only 23 have a clear majority PoS
to retag towards; the other 58 have all-singleton counts (one noun word, one
verb word), so fixing them means deciding which of the two is mistagged, one at
a time. Using the hypernym's PoS as a tiebreaker would assume the edge is
correct in order to fix the words, which is exactly the circularity this
exercise avoids. Deferred to manual review.

## B3: `{2ndOrder}` (52 pairs)

Not a lexicographic question and not addressed by this branch. {2ndOrder}
(`dn:synset-42970`) sits under {TOP} with 51 synsets pointing at it. Its single
lexicalizing word, `dn:word-temporary_3`, carries *empty IRIs* as its PoS
values:

    lexinfo:partOfSpeech  lexinfo:
    wn:partOfSpeech       wn:

It is the only word in DanNet with such a value. Dropping those two triples, or
exempting ontological-type nodes in the shape, clears all 52 pairs without
touching any lexicographic content. Worth raising separately so it is not
mistaken for a EuroWordNet structural decision.

## B4: the remainder (285 pairs, 101 targets)

Genuinely cross-PoS taxonomy: deverbal nouns under verbs ({filtrering} →
{fjerne}), verbs under nouns ({tilsvine} → {talehandling}), nouns under
adjectives ({karmin} → {rød}). Each pair needs a retargeting or re-relation
decision. Only 2 of the 596 sources have an alternative hypernym, so deletion
would orphan them.

In `2d-cross-pos-taxonomy.csv`, grouped by target (the top 15 targets cover
over half the pairs) with same-lemma retarget candidates precomputed where they
exist.

---

# Likely objections

**"EuroWordNet allowed cross-PoS hyponymy."** It did, and GWA maps that exact
relation to `attribute`. We keep the link and use the current name.

**"This is a unilateral lexicographic change."** For 5,413 pairs it is a
relabelling with both endpoints preserved. 109 are tag corrections where the
data proves the tag wrong. Everything requiring judgement (538 pairs across A4,
B2 and B4) is deliberately left for editorial review.

**"DanNet's structure is being altered to suit an export format."** WN-LMF is
why it surfaced, but not the justification. The justification is that DanNet's
own Version 2 specification never sanctioned this relation and documents the
semiautomatic process that produced it.

**"How do we know the targets were classified correctly?"** The 5,636 triples
concentrate on 72 distinct targets. Each was analysed once by hand and the
decision then applies mechanically. Target lists live in the source and the
resulting counts are asserted at build time, so drift fails the build.

# Appendix: words without a written representation

An unrelated defect found in passing, recorded here because it is invisible to
the obvious query. It belongs in its own GitHub issue.

15 words carry a `wn:partOfSpeech` but have no reachable
`ontolex:canonicalForm` → `ontolex:writtenRep`, so any query joining PoS and
lemma in one pattern silently drops them.

It cost a build. The first version of `fix-verb-phrase-pos!` derived both the
PoS test and the word list from one joined query, so for `dn:synset-28142`
({slå benene væk under nogen}) it saw one of two words and flipped only that
one, leaving the synset half noun and half verb, worse than the uniform error
it replaced. `HypernymPOSShape` read 490 instead of 489. The fix was to query
PoS and lemmas separately; the split is commented in `crosspos-review/index`
and in `fix-verb-phrase-pos!`.

The direction of the hazard is worth noting. Five of the 15 synsets have *no*
rep-bearing word at all, so a joined query gives them an empty PoS set and they
fail every PoS test silently. Correcting the query makes them newly eligible,
so such a fix can raise a count as easily as lower it:

| synset | label |
|---|---|
| `dn:synset-73853` | {snakke sort} |
| `dn:synset-73849` | {snakke (for) højt om} |
| `dn:synset-73852` | {snakke sammen} |
| `dn:synset-73846` | {tale sig fra noget} |
| `dn:synset-70967` | {køre af} |

None turned out to be reachable as a hypernym of a multi-word noun synset, so
the corrected criterion still yields exactly 107. That was verified, not
assumed.

# Appendix: where to verify each claim

Copyright limits how much of these sources can be reproduced here, so this is a
locator guide rather than a quote collection: each entry gives a direct link
and the exact section to look at. All links verified working.

## Global WordNet Association, relation documentation

<https://globalwordnet.github.io/gwadoc/> — has per-relation anchors, so these
are deep links:

| claim | where |
|---|---|
| `attribute` corresponds to EuroWordNet's `XPOS_Hyponymy`, and to Princeton's `=` pointer | [#attribute](https://globalwordnet.github.io/gwadoc/#attribute), table "Project-specific Names" |
| `attribute` holds between a nominal and an adjectival concept, and should link only adjectives to nouns and the reverse | [#attribute](https://globalwordnet.github.io/gwadoc/#attribute), definition and comments |
| EuroWordNet test 14, the *A is an attribute of B* diagnostic | [#attribute](https://globalwordnet.github.io/gwadoc/#attribute), test section |
| `classified_by` is a numeral classifier relation ('head' of cattle, 匹 for 猫) | [#classified_by](https://globalwordnet.github.io/gwadoc/#classified_by) and [#classifies](https://globalwordnet.github.io/gwadoc/#classifies) |
| `exemplifies` is the Usage subtype of Domain, Princeton's usage-domain pointer | [#exemplifies](https://globalwordnet.github.io/gwadoc/#exemplifies) |
| `pertainym` and `participle` are sense-level, not synset-level | [#pertainym](https://globalwordnet.github.io/gwadoc/#pertainym), [#participle](https://globalwordnet.github.io/gwadoc/#participle) |

## Lingvistiske specifikationer for DanNet Version 2 (2011)

<https://cst.ku.dk/projekter/dannet/dannetspecifikationer_v2.pdf> (PDF, ~300 KB)

The internal source, and the one most worth citing. Four passages carry the
argument:

**§1 Indledning, p. 3** states that about 30% of the material was produced
semiautomatically without further enrichment, naming the affected areas as
actions, events, **properties** and abstract entities. The opening clause reads
"Ca. 30 % af materialet er blevet produceret semiautomatisk uden yderligere
berigelse". Properties are where the adjectives live, so this is the single
most direct statement that the cross-PoS hypernyms come from an un-enriched
automatic process.

**§2, subsection "Organisering af det leksikalske net", pp. 5-6** explains the
mechanism: the net is organised on the nearest Danish superordinate, extracted
automatically from DDO. It then notes that DDO's superordinates were not taken
from any established ontological system but chosen by the individual editor,
giving the worked example of one editor choosing *lære* for *informatik* and
*bromatologi*, another *fag* for *samfundsfag*, and a third *videnskab* for
*datalogi*. The same passage says harmonising these was DanNet's own job.

**§3 Relationer og træk, pp. 8-9** is the complete relation inventory. Two
things to point at: both `has_hyperonym` examples are noun→noun (*birketræ* →
*træ*, *vejtræ* → *træ* with the `ortho` feature), and the only cross-PoS
relation listed anywhere in the table is `xpos_near_synonym`, exemplified by
*behandle* → *behandling*. There is no cross-PoS hyperonym relation.

**§6 Eksempler, p. 25** notes that semantic relations are most prominent for
concrete objects, and that for actions and abstract entities this version of
DanNet has essentially nothing but `has_hyperonym`. The coding templates that
follow (pp. 26-37) put `xpos_near_synonym` under *Synonymi* in every single
template, never under *Formal: has_hyperonym*.

**§7 Validering, p. 38** is a useful supplement: only 2% of the material was
validated, and it reports that while `has_hyperonym` is given fairly
consistently throughout, the other relations vary considerably in how finely
they were applied. That supports reading `has_hyperonym` as the catch-all slot
rather than a considered choice.

## Morgado Da Costa & Bond (2016)

<https://aclanthology.org/L16-1685.pdf> (PDF, ~470 KB)

Introduces the non-referential concepts that `classifies`/`classified_by` were
added for. Cite for the point that the relation was designed for numeral
classifiers, which is what rules it out for the substantivised adjectives in
§A4.

## EuroWordNet General Document (Vossen, ed., 1999)

<https://globalwordnet.github.io/gwadoc/pdf/EWN_general.pdf> (PDF, ~1.3 MB)

The primary source for the EuroWordNet relation set and its tests, including
the XPOS relations. Useful if she wants the original wording of the cross-PoS
relations rather than GWA's mapping of them.

## Princeton WordNet

Fellbaum, C. (ed.) (1998). *WordNet: An Electronic Lexical Database*. MIT Press.
No open link; the DanNet specification cites pp. 23-47 for the relevant
chapter. Cite for adjectives being organised around attribute nouns rather than
taxonomically, with `=` linking a value adjective to its attribute.

## Global Wordnet Formats

<https://globalwordnet.github.io/schemas/> — Bond, Vossen, McCrae & Fellbaum
(2016). The schema definitions behind WN-LMF, i.e. the format constraint that
surfaced all of this in #146.

## Pedersen et al. (2009)

*DanNet: the challenge of compiling a wordnet for Danish by reusing a
monolingual dictionary*. Language Resources and Evaluation 43(3), 269-299.
<https://doi.org/10.1007/s10579-009-9092-1> (likely paywalled outside the
university network). The specification points here for the fuller methodological
account.

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
