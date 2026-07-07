# Rationale for the cross-PoS changes

Source-backed justification for every decision on this branch, cross-referenced
against what the release code actually does. Counts are verified against the
live graph; the pipeline asserts them at build time.

Two separate populations are involved and should not be conflated:

- **A.** `dns:crossPoSHypernym`, DanNet's own bandaid relation, 5,636 triples.
- **B.** plain `wn:hypernym` pairs whose synsets disagree in PoS, 596 pairs,
  flagged as warnings by `dns:HypernymPOSShape`.

## Summary of decisions

| # | batch | count | decision | defence rests on |
|---|---|---|---|---|
| A1 | quality-noun targets | 5,413 | → `wn:attribute` | GWA relation docs (§2) |
| A2 | `adj.ppl` verb phrases | 2 | PoS fix + `wn:hypernym` | data-internal (§5) |
| A3 | pertainym/participle leftovers | 117 | deleted | GWA relation docs (§6) |
| A4 | substantivisations, register labels | 104 | retained for review | GWA relation docs (§4) |
| B1 | mistagged verb phrases (2a) | 107 | PoS fix | data-internal (§5) |
| B2 | mixed-PoS synsets (2b) | 149 | deferred | §7 |
| B3 | `{2ndOrder}` placeholder (2c) | 52 | not addressed here | §8 |
| B4 | genuine cross-PoS taxonomy (2d) | 285 | deferred | §7 |

Nothing in population B other than B1 is changed by this branch.

**Verified against the rebuilt SNAPSHOT database:** `dns:crossPoSHypernym` 104,
`wn:attribute` 5,413, `wn:classified_by` 0, and `HypernymPOSShape` down from
596 to 489 (3 excluded + 52 `{2ndOrder}` + 434 deferred for review). Every
count in this document is asserted at build time, so the build fails rather
than shipping data that contradicts the argument made here.

## 1. The central argument

The objection to anticipate is that EuroWordNet deliberately sanctioned
cross-PoS hyponymy, that DanNet inherits that tradition, and that removing it
is a regression rather than a fix.

The answer is that we are not removing the relation. We are writing it under
the name the Global WordNet Association gives it today.

GWA's relation documentation lists, for `attribute`, the corresponding
EuroWordNet relation name as `XPOS_Hyponymy`.
(<https://globalwordnet.github.io/gwadoc/>, section "Attribute", table
"Project-specific Names".)

That single line is the whole bridge. GWA's position is not that EuroWordNet
was wrong to relate adjectives to nouns; it is that this relation is now called
`attribute`. The same table gives Princeton's pointer for it as `=`. So
`attribute` is simultaneously the EuroWordNet cross-PoS relation and the
Princeton attribute relation, unified under one name.

The change is therefore a renaming to the current standard, not a deletion of
lexicographic content. 5,413 of 5,636 triples keep both endpoints.

## 2. A1: `wn:attribute` (5,413 triples)

GWA defines `attribute` as holding between a nominal and an adjectival concept,
where one is an attribute of the other. It is self-reciprocal, and the
documentation states it should link only adjectives to nouns and the reverse.
The diagnostic is EuroWordNet test 14: *A is an attribute of B* / *B is an
attribute of A*, with A a singular noun and B an adjective.

Princeton's treatment is the same idea from the other side. Adjectives there
are organised not taxonomically but around attribute nouns, with `=` linking a
value adjective to the attribute it is a value of (Fellbaum 1998).

Cross-referenced against the data, the fit is close to exact. The 5,413
converted triples land on 40 target synsets, every one a quality-, property- or
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

## 3. DanNet's own specification supports the change

The strongest material here is internal. *Lingvistiske specifikationer for
DanNet Version 2* (Pedersen, Braasch, Nimb, Asmussen, Sørensen, Lorentzen &
Trap-Jensen, 2011) gives the full inventory of DanNet relations in §3.

1. **There is no cross-PoS hyperonym relation in it.** The inventory lists
   `has_hyperonym` and `has_hyperonym ortho`, and both examples are noun→noun
   (*birketræ*, *vejtræ* → *træ*). The only cross-PoS relation in the entire
   inventory is `xpos_near_synonym` (*behandle* → *behandling*), and in every
   coding template in §6 it appears under *Synonymi*, never under *Formal:
   has_hyperonym*.

2. **The specification predicts where the noise is.** §1 records that roughly
   30% of the material was produced semiautomatically without further
   enrichment, and names the affected areas as actions, events, **properties**
   and abstract entities. §6 adds that for those areas essentially no relation
   other than `has_hyperonym` was assigned.

Properties are where the adjectives live. The cross-PoS hypernyms are not a
considered editorial position being overturned; they are the residue of that
process, and the specification says so.

§2 supplies the mechanism: the hypernyms were extracted automatically from
DDO's genus proximum, and the specification openly notes that DDO editors chose
genus terms ad hoc, giving *lære*, *fag* and *videnskab* used inconsistently
for the same kind of concept.

## 4. A4: why `wn:classified_by` was withdrawn (104 triples)

An earlier version of this branch converted these to `wn:classified_by`. That
was wrong.

GWA's `classified_by` is a *numeral classifier* relation, introduced for
non-referential concepts by Morgado Da Costa & Bond (2016): 'head' classifying
'cattle', 'rasher' classifying 'bacon', 匹 classifying 猫. Read that way,
`{koreansk} classified_by {sprog}` asserts that *sprog* is the counting word
for *koreansk*.

The heuristic also merged three unrelated phenomena:

| group | pairs | example | what it is |
|---|---|---|---|
| languages, dialects | 62 | {koreansk} → {sprog} | substantivised adjective |
| people | 13 | {hjemløs} → {person} | substantivised adjective |
| swear words, abuse | 26 | {helvedes} → {bandeord} | register label, candidate for `wn:exemplifies` |
| nominal idioms | 3 | {en lille sort} → {kaffe} | lexicalised nominal |

All 104 are now retained as `dns:crossPoSHypernym` pending review. Retention is
safe: the relation is not among the `supported-wn-relations` the WN-LMF
exporter emits, so nothing from #146 returns. It is also necessary, since none
of the 104 sources have another `wn:hypernym` and deletion would orphan them.
`fix-cross-pos-hypernymy!` exempts these targets and asserts that exactly 104
survive.

## 5. A2 and B1: PoS corrections (2 + 107 synsets)

These are the only changes on the branch that alter word data, and they share
one defence: **the evidence is internal to DanNet, so no external authority is
needed.**

A2 covers {gøre rent} and {gøre sig til gode}, verb phrases carrying the
`adj.ppl` lexfile. B1 covers 107 synsets, 113 words, where every lemma is
multi-word and the single hypernym is a verb synset: {slå mønt} →
{fremstille}, {drage nytte af} → {anvende}, {spise som en fugl} → {spise; æde}.

The argument is not that some paper says so. It is that a Danish multi-word
expression headed by a finite verb, sitting under a verb hypernym, cannot be a
noun. The taxonomy was already correct; only the tag was wrong. This is the
same class of defect the DanNet specification describes for semiautomatically
produced material (§3).

This is deliberately a narrower claim than the one we declined to make for the
A4 substantivisations. Deciding that *koreansk* has an established nominal
reading requires DDO evidence per item. Deciding that *slå mønt* is not a noun
requires only reading it. **Automate where the data proves the tag wrong;
defer where it takes lexicographic knowledge.** That line is what keeps A4,
B2 and B4 out of the pipeline.

Three of the 110 B1 candidates are excluded by hand, because "contains a
space" also admits non-verbal entries: {over kors} and {i pleje} are stranded
prepositional fragments, {skåret over samme læst} is participial. Two included
entries are full clauses rather than phrases; they are tagged verb because the
alternative is calling them nouns.

Lexfiles are inherited from the hypernym. Every source was `noun.*` (91 of them
`noun.act`) and every hypernym `verb.*`, so the mapping is unambiguous.

## 6. A3: deletions (117 triples)

Pertainym- and participle-flavoured pairs such as {kvartårlig} → {år} and
{hjelmklædt} → {klæde}. GWA does define `pertainym` and `participle`, but both
are sense-level (word-to-word) relations, whereas `dns:crossPoSHypernym` is
asserted between synsets. There is no synset-level relation with the right
meaning, so no faithful replacement exists.

This is a deferral rather than a judgement: the information can be restored
properly once DanNet expresses sense-level relations, and the pairs are listed
so nothing is lost silently.

## 7. B2 and B4: what is deliberately not automated (149 + 285 pairs)

B4 (2d) is genuinely cross-PoS taxonomy: deverbal nouns under verbs
({filtrering} → {fjerne}), verbs under nouns ({tilsvine} → {talehandling}),
nouns under adjectives ({karmin} → {rød}). Each needs a retargeting or
re-relation decision. Only 2 of the 596 sources have an alternative hypernym,
so deletion would orphan them.

B2 (2b) is not cross-PoS at all. `HypernymPOSShape` fires whenever *any* word
pair disagrees (`FILTER(?pos1 != ?pos2)`), so a synset holding one stray word
lights up even though the two synsets share a PoS. The 149 pairs come from 81
distinct synsets, of which only 23 have a clear majority PoS to retag towards.
The other 58 have all-singleton counts, so fixing them means deciding which of
two words is mistagged. Using the hypernym's PoS as a tiebreaker would assume
the edge is correct in order to fix the words, which is exactly the circularity
this exercise avoids.

Both are in the review backlog. Their presence is the evidence that the branch
is not automating lexicographic judgement wholesale.

## 8. B3: `{2ndOrder}` (52 pairs)

Not a lexicographic question and not addressed by this branch. {2ndOrder}
(`dn:synset-42970`) sits under {TOP} with 51 synsets pointing at it. Its single
lexicalizing word, `dn:word-temporary_3`, carries empty IRIs as its PoS values
(`lexinfo:partOfSpeech lexinfo:` and `wn:partOfSpeech wn:`). It is the only
word in DanNet with such a value.

Dropping those two triples, or exempting ontological-type nodes in the shape,
clears all 52 pairs. Worth raising separately so it is not mistaken for a
EuroWordNet structural decision.

## 9. Likely objections

**"EuroWordNet allowed cross-PoS hyponymy."** It did, and GWA maps that exact
relation to `attribute`. We keep the link and use the current name. See §1.

**"This is a unilateral lexicographic change."** For 5,413 pairs it is a
relabelling with both endpoints preserved. 109 are tag corrections where the
data proves the tag wrong. Everything requiring judgement (538 pairs across
A4, B2 and B4) is deliberately left for editorial review.

**"DanNet's structure is being altered to suit an export format."** WN-LMF is
why it surfaced, but not the justification. The justification is that DanNet's
own Version 2 specification never sanctioned this relation and documents the
semiautomatic process that produced it (§3).

**"How do we know the targets were classified correctly?"** The 5,636 triples
concentrate on 72 distinct targets. Each target was analysed once by hand and
the decision then applies mechanically. Target lists live in the source and the
resulting counts are asserted at build time, so drift fails the build.

## References

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
