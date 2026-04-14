# /// script
# requires-python = ">=3.10"
# dependencies = ["wn>=0.10"]
# ///
"""
DanNet tutorial: practical usage of DanNet with the Python wn library.

Run with:
    uv run dannet_tutorial.py

Interactive REPL (for copy-pasting individual lines):
    uv run --with wn python

NOTE: The wn library's built-in index contains an old version of DanNet
that is no longer maintained. We therefore always load from a local
WN-LMF file, which can be downloaded from:

    https://wordnet.dk/dannet/page/downloads

Alternatively, the latest version can be fetched directly:

    https://wordnet.dk/export/wn-lmf/dannet-wn-lmf.xml.gz

Tip: wn stores a local SQLite database (typically ~/.wn_data/wn.db).
If you want to start fresh, you can delete that file.

See also the official wn documentation: https://wn.readthedocs.io/
"""

import wn
from wn import taxonomy, similarity

# --- Load DanNet from a local file ----------------------------------------
# Only needed the first time (or after deleting wn.db).
# Subsequent runs skip the import automatically.
WN_LMF_PATH = "../export/wn-lmf/dannet-wn-lmf.xml.gz"
try:
    wn.add(WN_LMF_PATH)
except wn.DatabaseError:
    # Rebuild the database if the schema has changed (e.g. wn version upgrade).
    wn.reset_database()
    wn.add(WN_LMF_PATH)

# Create a Wordnet object to scope queries to DanNet.
# Useful when multiple wordnets are loaded at the same time.
dn = wn.Wordnet("dn")


# --- 1. Polysemy: words with multiple meanings ----------------------------
print("=" * 60)
print("1. Polysemi: ord med flere betydninger")
print("=" * 60)

# The wn library offers several paths to the same data.
# You can look up synsets directly for a word...
print("  Via synsets('land'):")
for ss in dn.synsets("land"):
    defn = ss.definition() or "(ingen definition)"
    words = [w.lemma() for w in ss.words()]
    print(f"    {words}: {defn}")
print()

# ...or navigate from word -> senses -> synset for more detail.
print("  Via words('tone') -> senses -> synset:")
for w in dn.words("tone"):
    print(f"    Ord: {w.lemma()}, ordklasse: {w.pos}")
    for sense in w.senses():
        ss = sense.synset()
        defn = ss.definition() or "?"
        print(f"      -> {ss.id}: {defn}")
    print()


# --- 2. Semantic relations: hypernyms and hyponyms ------------------------
print("=" * 60)
print("2. Hypernymer (overbegreber) og hyponymer (underbegreber)")
print("=" * 60)

# Hypernyms of 'hund'
print("  Hypernymer af 'hund':")
for ss in dn.synsets("hund"):
    words = [w.lemma() for w in ss.words()]
    for hyp in ss.hypernyms():
        hyp_words = [w.lemma() for w in hyp.words()]
        print(f"    {words} -> {hyp_words}")

print()

# Hyponyms of 'møbel'
print("  Hyponymer af 'møbel':")
for ss in dn.synsets("møbel"):
    hypos = ss.hyponyms()
    print(f"    {ss.id} har {len(hypos)} hyponymer:")
    for h in hypos[:8]:
        hw = [w.lemma() for w in h.words()]
        hd = h.definition() or "?"
        print(f"      {hw} -- {hd}")
    if len(hypos) > 8:
        print(f"      ... og {len(hypos) - 8} flere")
    print()


# --- 3. Traverse the hypernym chain up to the root -----------------------
print("=" * 60)
print("3. Hypernym-kæde: fra 'hund' til rod")
print("=" * 60)

hund_synsets = dn.synsets("hund")
if hund_synsets:
    ss = hund_synsets[0]  # pick the first (most typical) synset
    chain = []
    current = [ss]
    while current:
        chain.append(current[0])
        current = current[0].hypernyms()
    for i, node in enumerate(chain):
        indent = "  " * i
        nw = [w.lemma() for w in node.words()]
        print(f"  {indent}{nw}")
    print()


# --- 4. Synonyms: all words in the same synset ---------------------------
print("=" * 60)
print("4. Synonymer: alle ord i samme synset")
print("=" * 60)

for ss in dn.synsets("glad"):
    words = [w.lemma() for w in ss.words()]
    defn = ss.definition() or "?"
    print(f"  {ss.id}: {words}")
    print(f"    {defn}")
    print()


# --- 5. Filter by part of speech -----------------------------------------
print("=" * 60)
print("5. Filtrer på ordklasse: kun navneord for 'have'")
print("=" * 60)

for ss in dn.synsets("have", pos="n"):
    words = [w.lemma() for w in ss.words()]
    defn = ss.definition() or "?"
    print(f"  {ss.id}: {words} -- {defn}")
print()


# --- 6. Taxonomy: depth in the hierarchy ----------------------------------
print("=" * 60)
print("6. Taksonomi: dybde i hierarkiet")
print("=" * 60)

for word in ["hund", "møbel", "frugt", "tanke"]:
    for ss in dn.synsets(word, pos="n")[:1]:
        words = [w.lemma() for w in ss.words()]
        print(f"  {words}: min_depth={taxonomy.min_depth(ss)}, "
              f"max_depth={taxonomy.max_depth(ss)}")
print()


# --- 7. Semantic similarity -----------------------------------------------
print("=" * 60)
print("7. Semantisk lighed mellem ordpar")
print("=" * 60)

word_pairs = [
    ("hund", "kat"),
    ("hund", "bil"),
    ("stol", "bord"),
    ("stol", "tanke"),
]

for word_a, word_b in word_pairs:
    ss_a = dn.synsets(word_a, pos="n")
    ss_b = dn.synsets(word_b, pos="n")
    if ss_a and ss_b:
        path_sim = similarity.path(ss_a[0], ss_b[0])
        wup_sim = similarity.wup(ss_a[0], ss_b[0])
        print(f"  {word_a} / {word_b}:")
        print(f"    path similarity = {path_sim:.4f}")
        print(f"    Wu-Palmer       = {wup_sim:.4f}")
print()


# --- 8. Interlingual Index (ILI) -----------------------------------------
print("=" * 60)
print("8. Interlingual Index: kobling til andre WordNets")
print("=" * 60)

# Each synset can have an ILI (Interlingual Index) that links it
# to the same concept in other languages. To do cross-lingual lookups,
# you also need another WordNet loaded, e.g. the Open English WordNet.
for word in ["hund", "kat", "bil"]:
    for ss in dn.synsets(word, pos="n")[:1]:
        words = [w.lemma() for w in ss.words()]
        defn = ss.definition() or "?"
        print(f"  {words}: ILI = {ss.ili}")
        print(f"    {defn}")
print()
print("  Tip: Indlæs også Open English WordNet for tværsproglige opslag:")
print("    wn.download('oewn:2024')")
print("    en = wn.Wordnet('oewn:2024')")
print("    ss.translate(en)  # find engelske synsets via ILI")
print()


# --- 9. Statistics -------------------------------------------------------
print("=" * 60)
print("9. Statistik over DanNet")
print("=" * 60)

all_synsets = dn.synsets()
all_words = dn.words()
print(f"  Antal synsets: {len(all_synsets)}")
print(f"  Antal ord (lemmaer): {len(all_words)}")
print()


# --- 10. Mini-project: recursive exploration of a domain ------------------
print("=" * 60)
print("10. Mini-projekt: udforsk alle hyponymer af 'frugt'")
print("=" * 60)


def collect_hyponyms(synset, depth=0, max_depth=3):
    """Collect all hyponyms recursively (with a depth limit)."""
    results = []
    if depth > max_depth:
        return results
    for h in synset.hyponyms():
        words = [w.lemma() for w in h.words()]
        results.append((depth + 1, words, h.definition() or "?"))
        results.extend(collect_hyponyms(h, depth + 1, max_depth))
    return results


for ss in dn.synsets("frugt", pos="n"):
    words = [w.lemma() for w in ss.words()]
    defn = ss.definition() or "?"
    hypos = collect_hyponyms(ss)
    if not hypos:
        continue
    print(f"  Rod-synset: {words} -- {defn}")
    for depth, hw, hd in hypos:
        indent = "    " * depth
        print(f"  {indent}{hw}")
    print(f"\n  I alt {len(hypos)} hyponymer (maks. 3 niveauer dybt)")
    print()
