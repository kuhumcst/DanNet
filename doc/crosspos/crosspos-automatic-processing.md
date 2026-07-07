# Cross-PoS hypernymy: automatically processed synsets

This note documents which cross-PoS hypernymy cases were handled (or triaged)
automatically and, more importantly, *why* automatic classification was sound
in each case. The companion spreadsheet `crosspos-hypernym-review.xlsx`
contains the 283 pairs that still require lexicographic judgment.

## 1. `dns:crossPoSHypernym` — executed in the release pipeline (#146)

The 5,636 `dns:crossPoSHypernym` triples were resolved by
`fix-cross-pos-hypernymy!`. Automation was possible because the triples
concentrate on only 72 distinct *target* synsets: each target was analysed
manually once, and the decision then applies mechanically to every triple
pointing at it.

| treatment | triples | criterion |
|---|---|---|
| → `wn:attribute` | 5,413 | The target is a quality/property noun ({egenskab}, {karaktertræk}, {farve}, …). GWA defines `attribute` exactly for this: an adjective expressing a value of a nominal quality. |
| → `wn:classified_by` | 104 | The target is a substantivised adjective classifying the source. |
| PoS correction | 2 | {gøre rent}, {gøre sig til gode}: verb phrases mistagged `adj.ppl`. The hypernym itself was correct, so the word PoS was flipped to verb and the relation restored as plain `wn:hypernym`. Lexfiles inherited from the hypernyms. |
| deletion | 117 | Pertainym/participle-flavoured pairs ({kvartårlig} → {år}, {hjelmklædt} → {klæde}). The correct GWA relations (pertainym, participle) are *sense-level*, so no valid synset-level replacement exists. |

## 2. Plain `wn:hypernym` cross-PoS pairs — triaged (#153)

`dns:HypernymPOSShape` flags 596 distinct `wn:hypernym` pairs whose
lexicalizing words disagree in PoS. 313 of these could be classified
automatically because the *cause* of the PoS mismatch is detectable from the
data itself:

### 2a. Mistagged verb phrases (110 pairs)

Sources where **every** lemma is a multi-word phrase and the hypernym is a
verb, e.g. {gøre kål på} → {spise; æde}, {bryde brødet} → {uddele},
{drikke en skål} → {hilse; skåle}. A multi-word expression under a verb
hypernym is a verb phrase, not a noun: the taxonomy is correct and only the
PoS tag is wrong. Same fix as the two `adj.ppl` cases in #146
(flip PoS, keep the relation) — scriptable after a quick eyeball of the list.

### 2b. Mixed-PoS synsets (151 pairs)

Pairs where one synset itself contains words of *differing* PoS, e.g.
{sove længe}(verb) → {knalde brikker; sove}(noun+verb). Here the shape
violation is caused by intra-synset word-level PoS inconsistency, not by the
hypernym edge: the synsets are plausibly same-PoS once the mistagged word is
corrected. These are word-data corrections, to be fixed first, after which the
shape should be re-run — most of these pairs are expected to disappear.

### 2c. Artificial top-ontology node (52 pairs)

Every blank-PoS pair involves {2ndOrder}, an artificial EuroWordNet top node
whose words carry no `wn:partOfSpeech` at all. This is one structural decision,
not 52 lexicographic ones: give the node a PoS, exempt ontological-type nodes
in the shape, or retarget its children.

### 2d. The remainder (283 pairs, 101 targets)

Genuinely cross-PoS taxonomy: deverbal nouns under verbs ({filtrering} →
{fjerne}), verbs under nouns ({tilsvine} → {talehandling}), nouns under
adjectives ({karmin} → {rød}). Only 2 of the 596 sources have an alternative
hypernym, so deletion would orphan them — each pair needs a retargeting or
re-relation decision. These are in the review spreadsheet, grouped by target
(the top 15 targets cover over half the pairs) with same-lemma retarget
candidates precomputed where they exist.
