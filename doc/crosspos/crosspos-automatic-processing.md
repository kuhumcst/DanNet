# Cross-PoS hypernymy: automatically processed synsets

This note documents which cross-PoS hypernymy cases were handled (or triaged)
automatically and, more importantly, *why* automatic classification was sound
in each case. The pairs that still need lexicographic judgment live in CSVs
alongside this file, regenerated from the graph by
`dk.cst.dannet.crosspos-review/regenerate!`:

| file | pairs | see |
|---|---|---|
| `2d-cross-pos-taxonomy.csv` | 285 | §2d |
| `a4-deferred-crosspos.csv` | 104 | §1a |
| `2a-verb-phrase-pos-flip.csv` | 110 | §2a — record of an applied decision, not a worklist |

§2b adds a further 149 pairs, not yet extracted to a CSV, giving a review
backlog of 538.

## 1. `dns:crossPoSHypernym` — executed in the release pipeline (#146)

The 5,636 `dns:crossPoSHypernym` triples were resolved by
`fix-cross-pos-hypernymy!`. Automation was possible because the triples
concentrate on only 72 distinct *target* synsets: each target was analysed
manually once, and the decision then applies mechanically to every triple
pointing at it.

| treatment | triples | criterion |
|---|---|---|
| → `wn:attribute` | 5,413 | The target is a quality/property noun ({egenskab}, {karaktertræk}, {farve}, …). GWA defines `attribute` exactly for this: an adjective expressing a value of a nominal quality. GWA also records `attribute` as the successor of EuroWordNet's `XPOS_Hyponymy`, so this is a relabelling to the current standard rather than a new claim. |
| PoS correction | 2 | {gøre rent}, {gøre sig til gode}: verb phrases mistagged `adj.ppl`. The hypernym itself was correct, so the word PoS was flipped to verb and the relation restored as plain `wn:hypernym`. Lexfiles inherited from the hypernyms. |
| deletion | 117 | Pertainym/participle-flavoured pairs ({kvartårlig} → {år}, {hjelmklædt} → {klæde}). The correct GWA relations (pertainym, participle) are *sense-level*, so no valid synset-level replacement exists. |
| retained, pending review | 104 | Substantivised adjectives and register labels; see §1a. Left as `dns:crossPoSHypernym`, which the WN-LMF exporter does not emit. |

### 1a. The retained 104 pairs

These were originally converted to `wn:classified_by` on the grounds that the
target is "a substantivised adjective classifying the source". That was wrong
on both counts.

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

The 78 substantivisation pairs are the same phenomenon as the two `adj.ppl`
cases above, but automating them would assert an established nominal reading
for each of {ikkedansk}, {vestdansk}, {syg} and so on: a claim about Danish
that needs DDO evidence per item, not a mechanical decision from one target
analysis. They are therefore deferred rather than scripted.

None of the 104 sources have another `wn:hypernym`, so deleting the relation
would orphan every one of them. `fix-cross-pos-hypernymy!` exempts these
targets from its final deletion and asserts that exactly 104 triples survive,
so narrowing a target set cannot silently orphan them.

## 2. Plain `wn:hypernym` cross-PoS pairs — triaged (#153)

`dns:HypernymPOSShape` flags 596 distinct `wn:hypernym` pairs whose
lexicalizing words disagree in PoS. The shape fires whenever *any* word pair
differs, so the 596 split first by whether the two synsets' PoS sets are
actually disjoint:

| group | pairs | status |
|---|---|---|
| 2a mistagged verb phrases | 110 | 107 fixed in the pipeline, 3 excluded |
| 2b mixed-PoS synsets (not disjoint) | 149 | deferred, mostly needs judgement |
| 2c malformed placeholder word | 52 | technical fix, no lexicography |
| 2d genuine cross-PoS taxonomy | 285 | review CSV |

These four are disjoint and sum to 596 exactly, verified against the live
graph. Note that 2a and 2c are classifiable automatically because the *cause*
of the mismatch is detectable from the data itself, not because the pairs were
individually inspected.

### 2a. Mistagged verb phrases (110 pairs) — executed in the pipeline

Sources where **every** lemma is a multi-word phrase and the hypernym is a
verb, e.g. {gøre kål på} → {spise; æde}, {bryde brødet} → {uddele},
{drikke en skål} → {hilse; skåle}. A multi-word expression under a verb
hypernym is a verb phrase, not a noun: the taxonomy is correct and only the
PoS tag is wrong. Same fix as the two `adj.ppl` cases in #146.

Implemented as `fix-verb-phrase-pos!`: **107 synsets, 113 words** flipped to
verb, with lexfiles inherited from the hypernym (every source was `noun.*`,
every hypernym `verb.*`). The criterion is evaluated against the data at build
time and the resulting count asserted, so it cannot drift silently.

Verified on the rebuild: `HypernymPOSShape` dropped from 596 to **489**, and
the 3 pairs still matching the criterion are exactly the hand-excluded ones.

Note that this CSV cannot be regenerated from a built database. Once the fix
has run, the 107 corrected synsets are verbs and no longer match the
noun-source criterion, so `crosspos-review/regenerate!` deliberately leaves
`2a-verb-phrase-pos-flip.csv` alone and only checks the 3-row remainder.

Three of the 110 are excluded by hand, because "contains a space" also admits
non-verbal multi-word entries:

| synset | lemma | hypernym | why excluded |
|---|---|---|---|
| `dn:synset-27542` | over kors | {anbringe} | prepositional fragment of *lægge armene over kors* |
| `dn:synset-27572` | i pleje | {anbringe} | prepositional fragment of *anbringe i pleje* |
| `dn:synset-30501` | skåret (bygget, ..) over samme læst | {frembringe} | participial/adjectival, not a verb phrase |

The first two are stranded fragments that arguably should not be synsets under
{anbringe} at all; that is a separate defect, not a PoS question.

Two entries are verbal but are full clauses rather than phrases
({han (hun, ..) snakker, og røven går}, {munden står ikke stille på nogen}).
They are included: the alternative is to call them nouns.

### 2b. Mixed-PoS synsets (149 pairs)

Pairs where one synset itself contains words of *differing* PoS, e.g.
{sove længe}(verb) → {knalde brikker; sove}(noun+verb). `HypernymPOSShape`
fires whenever *any* word pair disagrees (`FILTER(?pos1 != ?pos2)`), so these
light up even though the two synsets share a PoS. They are not cross-PoS edges
at all; the defect is intra-synset word-level PoS inconsistency.

The 149 pairs come from 81 distinct mixed-PoS synsets. Only 23 of those have a
clear majority PoS to retag towards; the other 58 have all-singleton counts
(e.g. one noun word and one verb word), so fixing them means deciding which of
the two is mistagged, one at a time. Using the hypernym's PoS as a tiebreaker
would assume the edge is correct in order to fix the words, which is the
reasoning this whole exercise is meant to avoid. Deferred to manual review.

### 2c. Malformed placeholder word (52 pairs)

Every blank-PoS pair involves {2ndOrder} (`dn:synset-42970`), which sits under
{TOP} with 51 synsets pointing at it. Its single lexicalizing word,
`dn:word-temporary_3`, carries *empty IRIs* as its PoS values:

    lexinfo:partOfSpeech  lexinfo:
    wn:partOfSpeech       wn:

It is the only word in DanNet with such a value. This is a malformed datum,
not an ontological decision: dropping the two triples, or exempting
ontological-type nodes in the shape, clears all 52 pairs without touching any
lexicographic content.

### 2d. The remainder (285 pairs, 101 targets)

Genuinely cross-PoS taxonomy: deverbal nouns under verbs ({filtrering} →
{fjerne}), verbs under nouns ({tilsvine} → {talehandling}), nouns under
adjectives ({karmin} → {rød}). Only 2 of the 596 sources have an alternative
hypernym, so deletion would orphan them — each pair needs a retargeting or
re-relation decision. These are in `2d-cross-pos-taxonomy.csv`, grouped by
target (the top 15 targets cover over half the pairs) with same-lemma retarget
candidates precomputed where they exist.

## 3. Words without a written representation (unrelated defect, found in passing)

15 words in DanNet carry a `wn:partOfSpeech` but have no reachable
`ontolex:canonicalForm` → `ontolex:writtenRep`. They are invisible to any query
that joins PoS and lemma in a single pattern, which makes them a trap rather
than merely a gap.

They cost us a build. The first version of `fix-verb-phrase-pos!` derived both
the PoS test and the word list from one joined query, so for
`dn:synset-28142` ({slå benene væk under nogen}) it saw one of the two words
and flipped only that one, leaving the synset half noun and half verb —
worse than the uniform error it replaced. `HypernymPOSShape` read 490 instead
of the predicted 489, and the extra pair was that synset. The fix was to query
PoS and lemmas separately; the same split is commented in
`crosspos-review/index` and in `fix-verb-phrase-pos!`.

The direction of the hazard is worth noting. Five of the 15 synsets have *no*
rep-bearing word at all, so a joined query gives them an empty PoS set and they
silently fail every PoS test. Correcting the query makes them newly eligible,
so such a fix can raise a count as easily as lower it:

| synset | label |
|---|---|
| `dn:synset-73853` | {snakke sort} |
| `dn:synset-73849` | {snakke (for) højt om} |
| `dn:synset-73852` | {snakke sammen} |
| `dn:synset-73846` | {tale sig fra noget} |
| `dn:synset-70967` | {køre af} |

None of them turned out to be reachable as a hypernym of a multi-word noun
synset, so the corrected criterion still yields exactly 107. But that was
verified, not assumed.

This is not a cross-PoS issue and belongs in its own GitHub issue. It is
recorded here only because it is invisible to the obvious query and will bite
the next person who writes one.
