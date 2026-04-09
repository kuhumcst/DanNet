# Praktisk brug af DanNet
## Præsentationsoversigt — 45 minutter

*Forudsætter at de studerende lige har fået en generel introduktion til WordNets.*

---

### 1. Hvad er DanNet, helt konkret? (5 min)

- Dansk WordNet: ~70K synsets, ~62K ord
- Bygget på internationale standarder (Ontolex-lemon, GWA) — interoperabelt med andre WordNets
- Integrerer med andre danske ressourcer: COR (Det Centrale Ordregister), DDS (Det Danske Sentimentleksikon), Open English WordNet
- Open source (MIT), open data (CC BY-SA 4.0)

**Live demo:** Gå til wordnet.dk, slå et ord op (f.eks. "stol"), vis entity-siden — synsets, relationer, hypernym-kæde. Vis at man kan klikke sig rundt i grafen.

---

### 2. Adgangsmetode 1: Python + WN-LMF (10 min)

*Den mest tilgængelige vej ind for de studerende.*

- Python-biblioteket `wn` + DanNets WN-LMF-eksport
- **Vigtig bemærkning:** `wn`-bibliotekets indbyggede indeks (`wn.download('omw-da31:1.4')`) indeholder en gammel version af DanNet som ikke er vedligeholdt i mange år. Vi indlæser derfor altid fra en lokal WN-LMF-fil som downloades fra wordnet.dk
- Gennemgå `examples/dannet_tutorial.py` (i DanNet-repoet) — kan køres med `uv run dannet_tutorial.py` eller udforskes interaktivt med `uv run --with wn python`
- Tutorialen dækker polysemi, relationer, taksonomi, lighed, ILI m.m. i 10 sektioner

```python
import wn
from wn import taxonomy, similarity

wn.add("dannet-wn-lmf.xml.gz")   # indlæs fra lokal fil
dn = wn.Wordnet("dn")             # afgræns til DanNet

# Polysemi: flere veje til samme data
for ss in dn.synsets("land"):         # direkte opslag
    print(ss.definition())

for w in dn.words("tone"):           # via word -> senses -> synset
    for sense in w.senses():
        print(sense.synset().definition())

# Relationer
ss = dn.synsets("hund")[0]
print(ss.hypernyms())
print(ss.hyponyms())

# Semantisk lighed
ss1 = dn.synsets("hund", pos="n")[0]
ss2 = dn.synsets("kat", pos="n")[0]
print(similarity.path(ss1, ss2))    # 0.25
print(similarity.wup(ss1, ss2))     # 0.84

# Interlingual Index (kobling til andre sprogs WordNets)
print(ss1.ili)  # ILI('i46360')
```

- Bemærk: WN-LMF indeholder kun officielle GWA-relationer — DanNet-specifikke relationer (f.eks. `used_for`) kræver RDF-formatet
- **Projektidé:** Brug similarity-metrikker til at klynge danske ord; kombiner DanNet med et tekstkorpus til ordbetydningsdisambiguering eller tekstklassificering

---

### 3. Adgangsmetode 2: SPARQL-endpoint (15 min)

*Nyt koncept for de studerende — hold det praktisk, ikke teoretisk.*

- Hvad er SPARQL på 2 minutter: "SQL for grafdata" — subjekt/prædikat/objekt-tripler, man skriver mønstre som matches
- Den interaktive editor på wordnet.dk/dannet/sparql
- Gennemgå 2–3 forespørgsler live med stigende kompleksitet:

**Forespørgsel 1** — Find alle betydninger af et ord:
```sparql
PREFIX ontolex: <http://www.w3.org/ns/lemon/ontolex#>

SELECT ?synset WHERE {
  ?entry ontolex:canonicalForm/ontolex:writtenRep "hund"@da .
  ?entry ontolex:sense/ontolex:isLexicalizedSenseOf ?synset .
}
```

**Forespørgsel 2** — Hent hypernymer (hvad er det for en slags ting?):
```sparql
PREFIX wn: <https://globalwordnet.github.io/schemas/wn#>
PREFIX ontolex: <http://www.w3.org/ns/lemon/ontolex#>

SELECT ?hypernym ?label WHERE {
  ?entry ontolex:canonicalForm/ontolex:writtenRep "hund"@da .
  ?entry ontolex:sense/ontolex:isLexicalizedSenseOf ?synset .
  ?synset wn:hypernym ?hypernym .
  ?hypernym wn:ili/wn:definition ?label .
}
```

**Forespørgsel 3** — Sentimentdata (hvis tid):
Vis at DanNet integrerer med Det Danske Sentimentleksikon, så man kan forespørge ord med positiv/negativ sentiment.

- Påpeg: SPARQL-endpointet er også et programmerbart API (GET/POST med `query`-parameter, returnerer JSON) — kan kaldes fra Python med `requests`
- Nævn SPARQL-guiden på sitet til selvstudium
- **Projektidé:** Byg en sentiment-baseret applikation ved at forespørge DanNets sentimentdata programmatisk; eller udtræk domænespecifikke ordforråd med SPARQLs grafnavigation

---

### 4. Adgangsmetode 3: Content negotiation (3 min)

- Hver entitet i DanNet er en dereferencerbar URI
- Hurtig `curl`-demo:
```bash
curl -H "Accept: application/ld+json" https://wordnet.dk/dannet/data/synset-5028
```
- Returnerer JSON-LD, Turtle eller HTML afhængigt af Accept-headeren
- Nyttigt til scripting: hent individuelle synsets uden SPARQL

---

### 5. Adgangsmetode 4: AI-integration via MCP (7 min)

- DanNet har en MCP-server — kobles direkte til Claude, ChatGPT m.fl.
- **Live demo:** I Claude (med DanNet-connector aktiveret), stil spørgsmål som:
    - "Hvad er de forskellige betydninger af 'bro' på dansk?"
    - "Find alle hyponymer af 'møbel' i DanNet"
    - "Hvilke sentimentdata har DanNet for 'dejlig'?"
- AI'en får struktureret data retur (JSON-LD) og fortolker det — ingen grund til at skrive forespørgsler selv
- **Projektidé:** Brug en LLM + DanNet som backend til et dansk sproglæringsværktøj, en krydsordsgenerator eller semantisk søgning

---

### 6. Opsummering: valg af adgangsmetode (5 min)

| Behov | Bedste metode |
|---|---|
| Hurtig Python-integration, standardrelationer | `wn`-biblioteket + WN-LMF |
| Fuldt datasæt, DanNet-specifikke relationer, komplekse forespørgsler | SPARQL-endpoint |
| Hent individuelle ressourcer fra scripts | Content negotiation (curl/requests) |
| Udforskende / samtalebaseret / prototyping | MCP + AI-assistent |
| Offline / tung analyse | Download RDF-datasæt, indlæs lokalt |

- Alle datasæt kan downloades fra wordnet.dk og GitHub releases
- Henvis til SPARQL-guiden og developer-introsiden for videre læsning
- Opfordr til spørgsmål / projektidéer

---

## Praktiske noter

- **Forberedelse:** Hav SPARQL-editoren åben, en Python-notebook klar med `wn` installeret og DanNet importeret, og Claude med DanNet MCP-connector aktiveret.
- **Fallback:** Hvis live-demoer fejler, hav screenshots/optagelser klar.
- **Handout/link:** Del developer-introsiden (wordnet.dk/dannet/page/intro-developer) — den dækker alle adgangsmetoder kort og godt.
