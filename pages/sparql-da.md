# SPARQL-guide

Dette er en praktisk introduktion for folk uden forhåndskendskab til RDF eller SPARQL.
Ved at læse denne guide lærer du at bruge [SPARQL](https://www.w3.org/TR/sparql11-query/)-forespørgsler til at hente data fra DanNet-databasen.

1. [Hvad er RDF?](#hvad-er-rdf)
2. [Hvad er SPARQL?](#hvad-er-sparql)
3. [Udforsk relationer](#udforsk-relationer)
4. [OPTIONAL: håndtering af manglende data](#optional-haandtering-af-manglende-data)
5. [FILTER: indsnævring af resultater](#filter-indsnaevring-af-resultater)
6. [Aggregering: optælling, gruppering og begrænsning](#aggregering-optaelling-gruppering-og-begraensning)
7. [DanNet-specifikke relationer](#dannet-specifikke-relationer)
8. [Praktiske tips](#praktiske-tips)
9. [Appendiks A: SPARQL-editoren](#appendiks-a-sparql-editoren)
10. [Appendiks B: Hurtig oversigt](#appendiks-b-hurtig-oversigt)


## Hvad er RDF?

RDF (Resource Description Framework) er en måde at repræsentere viden som en eller flere sammenkædede grafer. DanNet-databasen modellerer sådanne grafer. Hvor relationelle databaser (f.eks. med SQL) gemmer data i rækker og kolonner, gemmer RDF-databaser data som **tripler**, dvs. udsagn af formen:

```
subjekt  prædikat  objekt .
```

Hver tripel er et enkelt faktum. Et subjekt er forbundet med et objekt via et prædikat (også kaldet en *egenskab*). Så simpelt er det. Al information i databasen er udtrykt på denne måde. Derfor kaldes en RDF-database også ofte en **triplestore**.

> **BEMÆRK:** Hvis du vil vide hvorfor DanNet gemmer sine data som tripler, kan du læse den oprindelige [begrundelse](/dannet/page/rationale).

### Et eksempel: definitionen af "kage"
Her er en rigtig tripel fra DanNet, skrevet i **Turtle**-syntaks (det mest udbredte menneskelæsbare RDF-format):

```turtle
dn:synset-52

skos:definition

"sødt bagværk der især serveres til kaffe el. te el…"@da

.
```

Eller med lidt mindre whitespace:

```turtle
dn:synset-52  skos:definition  "sødt bagværk der især serveres til kaffe el. te el…"@da  .
```

Det kan læses som:

> Synsettet `synset-52` har definitionen "sødt bagværk der især serveres til kaffe el. te el…" på dansk.

Bemærk følgende:

- `dn:synset-52` er en **URI**, en globalt unik identifikator for dette begreb. `dn:`-delen er et *præfiks*, en forkortelse for `https://wordnet.dk/dannet/data/`. Den fulde URI er altså `https://wordnet.dk/dannet/data/synset-52`. Du kan faktisk [besøge den side](/dannet/data/synset-52) i din browser og se alt om den.
- `skos:definition` er også en URI (fra [SKOS-vokabularet](https://www.w3.org/TR/skos-reference/)). Det er prædikatet.
- `"…"@da` er en **literal**, dvs. en ren tekstværdi. `@da`-tagget markerer den som dansk.
- Triplen afsluttes med et **punktum**.

Et RDF-datasæt er blot en stor samling af sådanne tripler. DanNet indeholder ca. ~70.000 danske synsets der dækker ~62.000 ord, alle beskrevet som tripler. Afsnittet om [DanNets datamodel](#dannets-datamodel-forenklet) nedenfor forklarer hvordan disse tripler er organiseret.

### Turtle-forkortelser

Turtle gør det muligt at forkorte. Når flere [tripler](https://en.wikibooks.org/wiki/SPARQL/Triples) deler samme subjekt, kan man bruge et **semikolon** til at fortsætte med det næste prædikat:

```turtle
dn:synset-52 rdf:type ontolex:LexicalConcept ;
             rdfs:label "{kage_1§1}"@da ;
             skos:definition "sødt bagværk der især serveres til kaffe el. te el…"@da ;
             wn:hypernym dn:synset-135 .
```

Her beskrives fire fakta om det samme subjekt. Man kan se præcis dette Turtle-output på enhver DanNet-ressourceside. Prøv for eksempel at downloade [Turtle-filen for synset-52](/dannet/data/synset-52?format=text/turtle). Man vil også se denne forkortelse i [SPARQL-forespørgsler](#din-foerste-forespoergsel), hvor den samme semikolon-syntaks bruges inde i `WHERE`-blokke.

### DanNets datamodel (forenklet)

DanNet følger [OntoLex-Lemon](https://www.w3.org/2016/05/ontolex/)-standarden. De centrale byggesten er:

- **Ord** (*LexicalEntry*), f.eks. "kage", med en skriftform og ordklasse.
- **Betydninger** (*LexicalSense*), en kobling mellem et ord og en bestemt betydning.
- **Synsets** (*LexicalConcept*), en betydning der deles af et eller flere ord, med en definition og relationer til andre synsets (hypernymer, hyponymer, osv.).

Kæden ser således ud:

```
LexicalEntry (ord)  →  LexicalSense  →  LexicalConcept (synset)
                                               ↕
                               andre synsets (hypernymer, hyponymer, osv.)
```
> **BEMÆRK:** Selvom de formelle OntoLex-Lemon-termer (*LexicalEntry*, *LexicalSense*, *LexicalConcept*) optræder i SPARQL-forespørgsler, vil vi i resten af denne guide blot referere til dem som **ord**, **betydninger** og **synsets**.

## Hvad er SPARQL?

SPARQL er forespørgselssproget for RDF-data. Hvis SQL er måden man forespørger tabeller på, er SPARQL måden man forespørger grafer af tripler på. Det er ikke kun en DanNet-ting: SPARQL bruges i hele Linked Data-verdenen. Det mest kendte eksempel er [Wikidata](https://www.wikidata.org/), der eksponerer hele sin vidensbase (over 100 millioner elementer) gennem et [SPARQL-endpoint](https://query.wikidata.org/). De samme forespørgselskoncepter du lærer her, kan bruges direkte der. Faktisk bruger [SPARQL-wikibogen](https://en.wikibooks.org/wiki/SPARQL) Wikidata som det gennemgående eksempel og er et godt supplement til denne tutorial.

Det afgørende er: **en SPARQL-forespørgsel er et mønster af tripler med variabler i.** Databasen finder alle tripler der matcher ens mønster, og returnerer variablernes bindinger. Hvis du ikke har læst [afsnittet om RDF](#hvad-er-rdf) endnu, er det et godt tidspunkt nu.

> **BEMÆRK:** Alle eksempler i guiden kører mod [DanNets SPARQL-endpoint](/dannet/sparql). Vær opmærksom på at denne offentlige version af DanNet har begrænsninger på både størrelsen af resultatsæt _og_ forespørgslens køretid. Vi begrænser også antallet af samtidige forespørgsler der kræver inferens.


### Din første forespørgsel

Lad os finde alle betydninger (synsets) af det danske ord "kage":

```sparql
SELECT DISTINCT  ?synset ?label ?definition
WHERE
  { ?entry ontolex:canonicalForm/ontolex:writtenRep "kage"@da .
    ?entry ontolex:sense/ontolex:isLexicalizedSenseOf ?synset .
    ?synset  rdfs:label       ?label ;
             skos:definition  ?definition
  }
```

[Kør denne forespørgsel](/dannet/sparql?query=PREFIX++skos%3A+%3Chttp%3A//www.w3.org/2004/02/skos/core%23%3E%0APREFIX++ontolex%3A+%3Chttp%3A//www.w3.org/ns/lemon/ontolex%23%3E%0APREFIX++rdfs%3A+%3Chttp%3A//www.w3.org/2000/01/rdf-schema%23%3E%0A%0ASELECT+DISTINCT++%3Fsynset+%3Flabel+%3Fdefinition%0AWHERE%0A++%7B+%3Fentry+ontolex%3AcanonicalForm/ontolex%3AwrittenRep+%22kage%22@da+.%0A++++%3Fentry+ontolex%3Asense/ontolex%3AisLexicalizedSenseOf+%3Fsynset+.%0A++++%3Fsynset++rdfs%3Alabel+++++++%3Flabel+%3B%0A+++++++++++++skos%3Adefinition++%3Fdefinition%0A++%7D%0A&offset=0&limit=100&inference=auto&distinct=true)

Lad os gennemgå det:

- `SELECT DISTINCT ?synset ?label ?definition` fortæller databasen hvilke variabler der skal returneres (`DISTINCT` fjerner duplikerede rækker).
- `WHERE { … }` indeholder det grafmønster der skal matche.
- `?entry`, `?synset`, `?label`, `?definition` er [**variabler**](https://en.wikibooks.org/wiki/SPARQL/Variables) (starter altid med `?`).
- `"kage"@da` er en konkret værdi: den danske streng "kage".
- Hver linje inde i `WHERE`-blokken er et **tripelmønster**, der afsluttes med et punktum.

Forespørgslen kan læses som:

> Find enhver entry hvis skriftform er 'kage', følg dens betydninger til deres synsets, og returnér hvert synsets etiket (label) og definition.

Bemærk skråstreg-syntaksen i `ontolex:canonicalForm/ontolex:writtenRep` - dette er en såkaldt [property path](#property-paths), som forklares nedenfor.

Denne forespørgsel returnerer 3 rækker:

| ?synset | ?label | ?definition |
|---|---|---|
| [dn:synset-52](/dannet/data/synset-52) | {kage_1§1} | sødt bagværk der især serveres til kaffe el. te… |
| [dn:synset-21205](/dannet/data/synset-21205) | {kage_1§2} | mere el. mindre fast (størknet el. sammenpresset) … |
| [dn:synset-40950](/dannet/data/synset-40950) | {en del/bid af kagen_1§6} | en andel af noget fordelagtigt, især et økonomisk … |

Ordet "kage" har tre betydninger: kage (bagværket), en mere eller mindre fast masse (størknet eller sammenpresset), og det idiomatiske "en del af kagen" (en andel af noget fordelagtigt).

### Læsning af synset-etiketter (labels)

Etiketter som `{kage_1§1}` kræver en forklaring. De opremser alle de ord der deler denne betydning, dvs. **synonymerne**. Når et synset har flere, vil du se dem adskilt af semikoloner: `{bag_2§1; bagværk_§1; brød_1§1b}`. `§1`-notationen stammer fra [Den Danske Ordbog (DDO)](https://ordnet.dk/ddo) og peger på et bestemt definitionsnummer i den ordbog.

### Property paths

Bemærk skråstreg-syntaksen:

```sparql
?entry ontolex:canonicalForm/ontolex:writtenRep "kage"@da .
```

Dette er en [**property path**](https://en.wikibooks.org/wiki/SPARQL/Property_paths). Det er en forkortelse for to tripelmønstre kædet sammen:

```sparql
?entry ontolex:canonicalForm ?form .
?form  ontolex:writtenRep    "kage"@da .
```

`/` betyder "følg denne egenskab, og følg derefter den egenskab". Det sparer én for at introducere mellemliggende variabler, man ikke har brug for.


## Udforsk relationer

### Ontologiske typer og RDF Bags

Hvert synset i DanNet er annoteret med en eller flere **ontologiske typer** fra `dnc:`-namespacet (f.eks. `dnc:Animal`, `dnc:Container`, `dnc:Comestible`). Disse typer er gemt i en [RDF Bag](https://www.w3.org/TR/rdf12-schema/#ch_bag), en beholder der rummer en uordnet samling af værdier. For at tilgå værdierne i en Bag bruger man `rdfs:member` (som matcher ethvert medlem af Bag'en) i en [property path](#property-paths):

```sparql
SELECT  ?synset ?definition
WHERE
  { ?synset dns:ontologicalType/rdfs:member dnc:Animal .
    ?synset   skos:definition   ?definition ;
              dns:sentiment     ?opinion .
    ?opinion  marl:hasPolarity  marl:Negative
  }
```

[Kør denne forespørgsel](/dannet/sparql?query=PREFIX++dns%3A++%3Chttps%3A//wordnet.dk/dannet/schema/%3E%0APREFIX++dnc%3A++%3Chttps%3A//wordnet.dk/dannet/concepts/%3E%0APREFIX++skos%3A+%3Chttp%3A//www.w3.org/2004/02/skos/core%23%3E%0APREFIX++rdfs%3A+%3Chttp%3A//www.w3.org/2000/01/rdf-schema%23%3E%0APREFIX++marl%3A+%3Chttp%3A//www.gsi.upm.es/ontologies/marl/ns%23%3E%0A%0ASELECT++%3Fsynset+%3Fdefinition%0AWHERE%0A++%7B+%3Fsynset+dns%3AontologicalType/rdfs%3Amember+dnc%3AAnimal+.%0A++++%3Fsynset+++skos%3Adefinition+++%3Fdefinition+%3B%0A++++++++++++++dns%3Asentiment+++++%3Fopinion+.%0A++++%3Fopinion++marl%3AhasPolarity++marl%3ANegative%0A++%7D%0A&offset=0&limit=100&inference=auto&distinct=true&enrichment=true)

Denne forespørgsel finder dyrebegreber med en negativ sentimentannotering. Den kombinerer flere ting: navigation ind i en RDF Bag via `dns:ontologicalType/rdfs:member`, matching af en specifik ontologisk type (`dnc:Animal`), og traversering af sentimentdata der bruger [MARL](http://www.gsi.upm.es/ontologies/marl/)-vokabularet. Det er den type tværgående forespørgsel der ville være meget svær at besvare blot ved at browse webgrænsefladen.

> **BEMÆRK:** "Kør denne forespørgsel"-linket ovenfor har **etiketberigelse** (label enrichment) aktiveret. Med dette aktiveret mapper editoren automatisk etiketter til alle ressource-URI'er i resultaterne, så du ikke behøver manuelt at hente `rdfs:label` for hver variabel.

### Op gennem hypernym-hierarkiet

Man kan også bruge `+` i en [property path](#property-paths) til at betyde "et eller flere trin". Denne forespørgsel finder alle forfædre til "delfin" ([synset-3346](/dannet/data/synset-3346)), hele vejen op til roden:

```sparql
SELECT  ?ancestor
WHERE
  { dn:synset-3346 wn:hypernym+ ?ancestor }
```

[Kør denne forespørgsel](/dannet/sparql?query=PREFIX++dn%3A+++%3Chttps%3A//wordnet.dk/dannet/data/%3E%0APREFIX++wn%3A+++%3Chttps%3A//globalwordnet.github.io/schemas/wn%23%3E%0A%0ASELECT++%3Fancestor%0AWHERE%0A++%7B+dn%3Asynset-3346+%28wn%3Ahypernym%29%2B+%3Fancestor+%7D%0A&offset=0&limit=10&inference=auto&distinct=true&enrichment=true)

Dette følger den fulde hypernym-kæde: delfin → tandhval → hval → pattedyr → hvirveldyr → dyr → levende væsen → … og så videre op til de mest generelle begreber. Hver række er ét trin i hierarkiet. Uden `+`-operatoren ville `wn:hypernym` kun returnere den umiddelbare forælder (tandhval).

### Mere end bare hypernymer

DanNet koder langt rigere relationer end blot "er en slags". Kage-synsettet ([synset-52](/dannet/data/synset-52)) ved også at kage *indeholder* mel og sukker (substansmeronymer), at kage er *resultatet af* bagning, og at kage *bruges til* spisning:

```sparql
SELECT  ?relation ?targetLabel
WHERE
  { VALUES ?relation { wn:mero_substance wn:result dns:usedFor }
    dn:synset-52  ?relation  ?target .
    ?target   rdfs:label  ?targetLabel
  }
```

[Kør denne forespørgsel](/dannet/sparql?query=PREFIX++dns%3A++%3Chttps%3A//wordnet.dk/dannet/schema/%3E%0APREFIX++dn%3A+++%3Chttps%3A//wordnet.dk/dannet/data/%3E%0APREFIX++rdfs%3A+%3Chttp%3A//www.w3.org/2000/01/rdf-schema%23%3E%0APREFIX++wn%3A+++%3Chttps%3A//globalwordnet.github.io/schemas/wn%23%3E%0A%0ASELECT++%3Frelation+%3FtargetLabel%0AWHERE%0A++%7B+VALUES+%3Frelation+%7B+wn%3Amero_substance+wn%3Aresult+dns%3AusedFor+%7D%0A++++dn%3Asynset-52++%3Frelation++%3Ftarget+.%0A++++%3Ftarget+++rdfs%3Alabel++%3FtargetLabel%0A++%7D%0A&offset=0&limit=10&inference=auto&distinct=true&enrichment=true)

Vi ser nærmere på flere af disse DanNet-specifikke relationer i [afsnit 7](#dannet-specifikke-relationer).


## OPTIONAL: håndtering af manglende data

Ikke alle synsets har alle egenskaber. Hvis du vil have resultater selv når visse data mangler, kan du bruge [`OPTIONAL`](https://en.wikibooks.org/wiki/SPARQL/OPTIONAL):

```sparql
SELECT  ?synset ?definition ?example
WHERE
  { ?synset dns:ontologicalType/rdfs:member dnc:Comestible .
    ?synset  skos:definition  ?definition
    OPTIONAL
      { ?synset ontolex:lexicalizedSense/lexinfo:senseExample ?example }
  }
```

[Kør denne forespørgsel](/dannet/sparql?query=PREFIX++wn%3A+++%3Chttps%3A//globalwordnet.github.io/schemas/wn%23%3E%0APREFIX++dns%3A++%3Chttps%3A//wordnet.dk/dannet/schema/%3E%0APREFIX++dnc%3A++%3Chttps%3A//wordnet.dk/dannet/concepts/%3E%0APREFIX++skos%3A+%3Chttp%3A//www.w3.org/2004/02/skos/core%23%3E%0APREFIX++ontolex%3A+%3Chttp%3A//www.w3.org/ns/lemon/ontolex%23%3E%0APREFIX++rdfs%3A+%3Chttp%3A//www.w3.org/2000/01/rdf-schema%23%3E%0APREFIX++lexinfo%3A+%3Chttp%3A//www.lexinfo.net/ontology/3.0/lexinfo%23%3E%0A%0ASELECT++%3Fsynset+%3Fdefinition+%3Fexample%0AWHERE%0A++%7B+%3Fsynset+dns%3AontologicalType/rdfs%3Amember+dnc%3AComestible+.%0A++++%3Fsynset++skos%3Adefinition++%3Fdefinition%0A++++OPTIONAL%0A++++++%7B+%3Fsynset+ontolex%3AlexicalizedSense/lexinfo%3AsenseExample+%3Fexample+%7D%0A++%7D%0A&offset=0&limit=20&inference=auto&distinct=true&enrichment=true)

Denne forespørgsel finder alle **Comestible**-synsets (mad) og deres brugseksempler *hvis de findes*. Mønstret `dns:ontologicalType/rdfs:member` er det samme som bruges i [Ontologiske typer og RDF Bags](#ontologiske-typer-og-rdf-bags). Nogle synsets har eksempler, andre ikke. Uden `OPTIONAL` ville ethvert synset uden eksempel blive udeladt fra resultaterne. Rul igennem resultaterne og bemærk hvordan nogle rækker har en `?example`-værdi, mens andre er tomme.


## FILTER: indsnævring af resultater

[`FILTER`](https://en.wikibooks.org/wiki/SPARQL/FILTER) gør det muligt at tilføje betingelser ud over simpel mønstermatching. Almindelige anvendelser:

### Tekstsøgning med CONTAINS

Find alle synsets hvis definition nævner "lugtende":

```sparql
SELECT  ?synset ?definition
WHERE
  { ?synset  skos:definition  ?definition
    FILTER contains(?definition, "lugtende")
  }
```

[Kør denne forespørgsel](/dannet/sparql?query=PREFIX++skos%3A+%3Chttp%3A//www.w3.org/2004/02/skos/core%23%3E%0APREFIX++rdfs%3A+%3Chttp%3A//www.w3.org/2000/01/rdf-schema%23%3E%0A%0ASELECT++%3Fsynset+%3Fdefinition%0AWHERE%0A++%7B+%3Fsynset++skos%3Adefinition++%3Fdefinition%0A++++FILTER+contains%28%3Fdefinition%2C+%22lugtende%22%29%0A++%7D%0A&offset=0&limit=100&inference=auto&distinct=true&enrichment=true)

### Filtrering efter namespace

DanNets SPARQL-endpoint indeholder også det fulde [Open English WordNet](https://en-word.net/), så ufiltrerede forespørgsler kan returnere en blanding af danske og engelske synsets. Man kan begrænse resultaterne til enten danske eller engelske synsets ved at tjekke URI-namespacet med `STRSTARTS`, f.eks. for dansk:

```sparql
SELECT  ?synset
WHERE
  { ?synset  rdf:type  ontolex:LexicalConcept
    FILTER strstarts(str(?synset), str(dn:))
  }
```

[Kør denne forespørgsel](/dannet/sparql?query=PREFIX++rdf%3A++%3Chttp%3A//www.w3.org/1999/02/22-rdf-syntax-ns%23%3E%0APREFIX++ontolex%3A+%3Chttp%3A//www.w3.org/ns/lemon/ontolex%23%3E%0APREFIX++dn%3A+++%3Chttps%3A//wordnet.dk/dannet/data/%3E%0A%0ASELECT++%3Fsynset%0AWHERE%0A++%7B+%3Fsynset++rdf%3Atype++ontolex%3ALexicalConcept%0A++++FILTER+strstarts%28str%28%3Fsynset%29%2C+str%28dn%3A%29%29%0A++%7D%0A&offset=0&limit=100&inference=auto&distinct=true&enrichment=true)

Og for engelsk:

```sparql
SELECT  ?synset
WHERE
  { ?synset  rdf:type  ontolex:LexicalConcept
    FILTER strstarts(str(?synset), str(en:))
  }
```

[Kør denne forespørgsel](/dannet/sparql?query=PREFIX++rdf%3A++%3Chttp%3A//www.w3.org/1999/02/22-rdf-syntax-ns%23%3E%0APREFIX++en%3A+++%3Chttps%3A//en-word.net/id/%3E%0APREFIX++ontolex%3A+%3Chttp%3A//www.w3.org/ns/lemon/ontolex%23%3E%0A%0ASELECT++%3Fsynset%0AWHERE%0A++%7B+%3Fsynset++rdf%3Atype++ontolex%3ALexicalConcept%0A++++FILTER+strstarts%28str%28%3Fsynset%29%2C+str%28en%3A%29%29%0A++%7D%0A&offset=0&limit=100&inference=auto&distinct=true&enrichment=true)

`STR(?synset)` konverterer URI'en til en streng, og `STR(dn:)` udvider præfikset til dets fulde form (`https://wordnet.dk/dannet/data/`). Dette mønster er nyttigt når man skal begrænse resultater til RDF-ressourcer i et bestemt namespace.

### Sammenligningsoperatorer

```sparql
FILTER(?count > 5)
FILTER(?word != "kage"@da)
FILTER(STRSTARTS(?label, "{kat"))
```

For mere om de tilgængelige [udtryk og funktioner](https://en.wikibooks.org/wiki/SPARQL/Expressions_and_Functions) man kan bruge inde i `FILTER`, se Wikibook-kapitlet eller [W3C-specifikationen](https://www.w3.org/TR/sparql11-query/#expressions).

**Bemærkning om ydeevne:** `FILTER` med `CONTAINS` kan være langsom på store resultatsæt. Indsnævr om muligt resultaterne med tripelmønstre først, og filtrer derefter.


## Aggregering: optælling, gruppering og begrænsning

[Modifikatorerne](https://en.wikibooks.org/wiki/SPARQL/Modifiers) `GROUP BY`, `ORDER BY`, `LIMIT` og `OFFSET` gør det muligt at aggregere, sortere og paginere resultater. Se også Wikibook-kapitlet om [aggregeringsfunktioner](https://en.wikibooks.org/wiki/SPARQL/Aggregate_functions).

### Sentiment på tværs af semantiske domæner

Hvilke semantiske domæner (leksikograffiler) har flest synsets med positivt eller negativt sentiment?

```sparql
SELECT ?lexfile ?polarity (COUNT(?synset) AS ?count) WHERE {
  ?synset dns:sentiment ?opinion .
  ?opinion marl:hasPolarity ?polarity .
  ?synset wn:lexfile ?lexfile .
}
GROUP BY ?lexfile ?polarity
ORDER BY ?lexfile
```

[Kør denne forespørgsel](/dannet/sparql?query=SELECT%20%3Flexfile%20%3Fpolarity%20(COUNT(%3Fsynset)%20AS%20%3Fcount)%20WHERE%20%7B%20%3Fsynset%20dns%3Asentiment%20%3Fopinion%20.%20%3Fopinion%20marl%3AhasPolarity%20%3Fpolarity%20.%20%3Fsynset%20wn%3Alexfile%20%3Flexfile%20.%20%7D%20GROUP%20BY%20%3Flexfile%20%3Fpolarity%20ORDER%20BY%20%3Flexfile&distinct=true)

`GROUP BY` samler rækker efter grupperingsvariablerne, `COUNT` aggregerer dem, og `ORDER BY` sorterer output. Dette giver et overblik over hvordan sentiment er fordelt på tværs af substantiv-, verbal- og adjektivdomæner.

### Hvilke ord har flest betydninger?

```sparql
SELECT  ?word ?senses
WHERE
  { { SELECT  ?word (COUNT(DISTINCT ?synset) AS ?senses)
      WHERE
        { ?entry ontolex:canonicalForm/ontolex:writtenRep ?word .
          ?entry ontolex:sense/ontolex:isLexicalizedSenseOf ?synset
          FILTER strstarts(str(?entry), str(dn:))
        }
      GROUP BY ?word
      ORDER BY DESC(?senses)
      LIMIT   10
    }
  }
```

[Kør denne forespørgsel](/dannet/sparql?query=PREFIX++ontolex%3A+%3Chttp%3A//www.w3.org/ns/lemon/ontolex%23%3E%0APREFIX++dn%3A+++%3Chttps%3A//wordnet.dk/dannet/data/%3E%0A%0ASELECT++%3Fword+%3Fsenses%0AWHERE%0A++%7B+%7B+SELECT++%3Fword+%28COUNT%28DISTINCT+%3Fsynset%29+AS+%3Fsenses%29%0A++++++WHERE%0A++++++++%7B+%3Fentry+ontolex%3AcanonicalForm/ontolex%3AwrittenRep+%3Fword+.%0A++++++++++%3Fentry+ontolex%3Asense/ontolex%3AisLexicalizedSenseOf+%3Fsynset%0A++++++++++FILTER+strstarts%28str%28%3Fentry%29%2C+str%28dn%3A%29%29%0A++++++++%7D%0A++++++GROUP+BY+%3Fword%0A++++++ORDER+BY+DESC%28%3Fsenses%29%0A++++++LIMIT+++10%0A++++%7D%0A++%7D%0A&offset=0&limit=10&inference=true)

Denne forespørgsel bruger en [subquery](https://en.wikibooks.org/wiki/SPARQL/Subqueries) til at udføre aggregering og begrænsning i ét trin. `ORDER BY DESC(…)` sorterer faldende og `LIMIT` begrænser output. `FILTER STRSTARTS(…)` begrænser entries til DanNet-namespacet (se [Filtrering efter namespace](#filtrering-efter-namespace)), hvilket er vigtigt her, fordi triplestore'n også indeholder entries fra andre datasæt som COR og det engelske WordNet. Bemærk, at "Kør denne forespørgsel"-linket ovenfor sætter inferenstilstanden til *Afledt*; se [Rå, afledt og auto](#raa-afledt-og-auto) i appendikset for hvorfor det er vigtigt.

> **BEMÆRK:** `ORDER BY` kan være meget dyrt på store resultatsæt, da det kræver, at databasen sorterer alle matchende rækker, før den returnerer nogen. Brug det sparsomt og foretruk forespørgsler der indsnævrer resultater med tripelmønstre først. Når det kombineres med `GROUP BY`, er sortering af et mindre aggregeret resultat (som i subquery'en ovenfor) langt billigere end sortering af det fulde resultatsæt.

### Udforsk ontologiske typer

DanNet annoterer hvert synset med en eller flere ontologiske typer fra `dnc:`-namespacet (se [Ontologiske typer og RDF Bags](#ontologiske-typer-og-rdf-bags) for baggrund). Du kan få et overblik over alle tilgængelige typer og hvor mange synsets hver enkelt dækker:

```sparql
SELECT  ?type (COUNT(?synset) AS ?count)
WHERE
  { ?synset dns:ontologicalType/rdfs:member ?type }
GROUP BY ?type
ORDER BY DESC(?count)
LIMIT   100
```

[Kør denne forespørgsel](/dannet/sparql?query=PREFIX++dns%3A++%3Chttps%3A//wordnet.dk/dannet/schema/%3E%0APREFIX++rdfs%3A+%3Chttp%3A//www.w3.org/2000/01/rdf-schema%23%3E%0A%0ASELECT++%3Ftype+%28COUNT%28%3Fsynset%29+AS+%3Fcount%29%0AWHERE%0A++%7B+%3Fsynset+dns%3AontologicalType/rdfs%3Amember+%3Ftype+%7D%0AGROUP+BY+%3Ftype%0AORDER+BY+DESC%28%3Fcount%29%0ALIMIT+++100%0A&offset=0&limit=10&inference=auto&distinct=true)

Resultaterne viser `dnc:`-QNames direkte (f.eks. `dnc:Object`, `dnc:Covering`, `dnc:Animal`). Man kan vælge en hvilken som helst af disse og bruge den i en opfølgende forespørgsel. For eksempel, for at finde alle synsets tagget som `dnc:Covering`:

```sparql
SELECT  ?synset ?definition
WHERE
  { ?synset dns:ontologicalType/rdfs:member dnc:Covering .
    ?synset  skos:definition  ?definition
  }
```

[Kør denne forespørgsel](/dannet/sparql?query=PREFIX++dns%3A++%3Chttps%3A//wordnet.dk/dannet/schema/%3E%0APREFIX++dnc%3A++%3Chttps%3A//wordnet.dk/dannet/concepts/%3E%0APREFIX++skos%3A+%3Chttp%3A//www.w3.org/2004/02/skos/core%23%3E%0APREFIX++rdfs%3A+%3Chttp%3A//www.w3.org/2000/01/rdf-schema%23%3E%0A%0ASELECT++%3Fsynset+%3Fdefinition%0AWHERE%0A++%7B+%3Fsynset+dns%3AontologicalType/rdfs%3Amember+dnc%3ACovering+.%0A++++%3Fsynset++skos%3Adefinition++%3Fdefinition%0A++%7D%0A&offset=0&limit=10&inference=auto&distinct=true&enrichment=true)

Prøv at udskifte `dnc:Covering` med en anden type fra det forrige resultat!

### LIMIT, OFFSET og paginering

`LIMIT` begrænser antallet af returnerede resultater og `OFFSET` springer et antal resultater over fra starten:

```sparql
SELECT ?s ?p ?o WHERE { ?s ?p ?o } LIMIT 20 OFFSET 40
```

Dette ville returnere 20 resultater, startende fra det 41. match.

I DanNets SPARQL-editor behøver man generelt **ikke at skrive disse selv**. Editoren har en indbygget vælger til sidestørrelse (10, 20, 50 eller 100 resultater pr. side) og forrige/næste-knapper der håndterer paginering automatisk.

Bemærk, at der er en hård grænse på **100 resultater** pr. forespørgsel. Denne grænse gælder, uanset om man angiver sit eget `LIMIT`; at skrive `LIMIT 500` vil stadig returnere højst 100 rækker.

Inkluderer man *selv* `LIMIT` eller `OFFSET` i sin forespørgsel, respekterer editoren dem som de er og deaktiverer sine egne pagineringskontroller. Det er nyttigt, når man har brug for præcis kontrol over resultatvinduet, f.eks. i en subquery som den ovenfor der vælger de 10 mest polyseme ord. Her er `LIMIT 10` en integreret del af forespørgselslogikken, ikke blot en sikkerhedsforanstaltning.

For almindelige udforskende forespørgsler kan man blot udelade `LIMIT` og `OFFSET` og lade editoren håndtere pagineringen. Se også [Resultater, paginering og download](#resultater-paginering-og-download) i appendikset.


## DanNet-specifikke relationer

DanNet går ud over den standard WordNet-hypernym/hyponym-taksonomi. Med udgangspunkt i [datamodellen](#dannets-datamodel-forenklet) introduceret tidligere, koder den funktionelle og tematiske relationer der er værd at udforske.

### "Bruges til": funktionelt formål

Hvilke ting bruges til "transportere"?

```sparql
SELECT DISTINCT  ?thing
WHERE
  { ?thing  dns:usedFor  dn:synset-1997 }
```

[Kør denne forespørgsel](/dannet/sparql?query=PREFIX++dns%3A++%3Chttps%3A//wordnet.dk/dannet/schema/%3E%0APREFIX++dn%3A+++%3Chttps%3A//wordnet.dk/dannet/data/%3E%0APREFIX++rdfs%3A+%3Chttp%3A//www.w3.org/2000/01/rdf-schema%23%3E%0A%0ASELECT+DISTINCT++%3Fthing%0AWHERE%0A++%7B+%3Fthing++dns%3AusedFor++dn%3Asynset-1997+%7D%0A&offset=0&limit=100&inference=auto&distinct=true&enrichment=true)

### Register: at finde slangord

DanNet markerer visse betydninger med et sprogligt register. For eksempel kan man hurtigt finde ord der har en betydning markeret som slang:

```sparql
SELECT DISTINCT  ?slang
WHERE
  { ?sense  lexinfo:register  lexinfo:slangRegister .
    ?word   ontolex:sense     ?sense .
    ?word ontolex:canonicalForm/ontolex:writtenRep ?slang
  }
```

[Kør denne forespørgsel](/dannet/sparql?query=PREFIX++ontolex%3A+%3Chttp%3A//www.w3.org/ns/lemon/ontolex%23%3E%0APREFIX++lexinfo%3A+%3Chttp%3A//www.lexinfo.net/ontology/3.0/lexinfo%23%3E%0A%0ASELECT+DISTINCT++%3Fslang%0AWHERE%0A++%7B+%3Fsense++lexinfo%3Aregister++lexinfo%3AslangRegister+.%0A++++%3Fword+++ontolex%3Asense+++++%3Fsense+.%0A++++%3Fword+ontolex%3AcanonicalForm/ontolex%3AwrittenRep+%3Fslang%0A++%7D%0A&offset=0&limit=100&inference=auto&distinct=true&enrichment=true)

Dette er et godt eksempel på noget der er svært at opdage blot ved at browse webgrænsefladen.

### Krydslingvistiske links

DanNet-synsets er forbundet med [Open English WordNet](https://en-word.net/) via `wn:eq_synonym`:

```sparql
SELECT  ?synset ?enSynset
WHERE
  { ?entry ontolex:canonicalForm/ontolex:writtenRep "land"@da .
    ?entry ontolex:sense/ontolex:isLexicalizedSenseOf ?synset .
    ?synset  wn:eq_synonym  ?enSynset
  }
```

[Kør denne forespørgsel](/dannet/sparql?query=PREFIX++ontolex%3A+%3Chttp%3A//www.w3.org/ns/lemon/ontolex%23%3E%0APREFIX++wn%3A+++%3Chttps%3A//globalwordnet.github.io/schemas/wn%23%3E%0A%0ASELECT++%3Fsynset+%3FenSynset%0AWHERE%0A++%7B+%3Fentry+ontolex%3AcanonicalForm/ontolex%3AwrittenRep+%22land%22@da+.%0A++++%3Fentry+ontolex%3Asense/ontolex%3AisLexicalizedSenseOf+%3Fsynset+.%0A++++%3Fsynset++wn%3Aeq_synonym++%3FenSynset%0A++%7D%0A&offset=0&limit=100&inference=auto&distinct=true&enrichment=true)

Med berigelse aktiveret vil både DanNet-synsettet og det engelske synset vises som links med etiketter, så det er nemt at se hvilke danske betydninger der svarer til hvilke engelske. Hvis man har brug for at begrænse resultater til ét sprog, kan man bruge `STRSTARTS`-teknikken fra [Filtrering efter namespace](#filtrering-efter-namespace).

## Praktiske tips

**Udforsk en enkelt RDF-ressource først.** Før du skriver en generel forespørgsel, så vælg én ressource og se på alle dens egenskaber:

```sparql
SELECT  ?prop ?value
WHERE
  { dn:synset-52  ?prop  ?value }
```

[Kør denne forespørgsel](/dannet/sparql?query=PREFIX++dn%3A+++%3Chttps%3A//wordnet.dk/dannet/data/%3E%0A%0ASELECT++%3Fprop+%3Fvalue%0AWHERE%0A++%7B+dn%3Asynset-52++%3Fprop++%3Fvalue+%7D%0A&offset=0&limit=100&inference=auto&distinct=true&enrichment=true)

Dette er SPARQL-ækvivalenten til "vis mig alt om denne ting". Det er den bedste måde at opdage hvilke egenskaber der er tilgængelige. Du kan også bare [besøge ressourcesiden](/dannet/data/synset-52) og browse de samme data visuelt.

**Tænk i tripler.** Ethvert spørgsmål man vil stille oversættes til et mønster af tripler. "Hvilke ord er synonymer med X?" bliver til: "Find entries der har en betydning knyttet til det samme synset som X." Det [første forespørgselseksempel](#din-foerste-forespoergsel) demonstrerer denne tilgang trin for trin.

**Læs Turtle-formatet.** Besøg en hvilken som helst DanNet-ressourceside (f.eks. [synset-52](/dannet/data/synset-52)) og download [Turtle-repræsentationen](/dannet/data/synset-52?format=text/turtle). De prædikater man ser der, er de samme man bruger i sine SPARQL-forespørgsler.


### Almindelige præfikser

Man behøver ikke at huske fulde URI'er. DanNets [SPARQL-endpoint](/dannet/sparql) foruddefinerer disse [præfikser](https://en.wikibooks.org/wiki/SPARQL/Prefixes) (blandt andre):

| Præfiks | Namespace | Bruges til                               |
|---------|---|------------------------------------------|
| **dn:** | https://wordnet.dk/dannet/data/ | DanNet-synsets, -ord, -betydninger       |
| **dns:** | https://wordnet.dk/dannet/schema/ | DanNet-specifikke egenskaber             |
| **dnc:** | https://wordnet.dk/dannet/concepts/ | DanNet-ontologiske typer                 |
| **ontolex:** | http://www.w3.org/ns/lemon/ontolex# | Leksikalske entries, betydninger, former |
| **wn:**  | https://globalwordnet.github.io/schemas/wn# | WordNet-relationer (hypernym, osv.)      |
| **skos:** | http://www.w3.org/2004/02/skos/core# | Definitioner                             |
| **rdfs:** | http://www.w3.org/2000/01/rdf-schema# | Etiketter (labels)                       |
| **rdf:** | http://www.w3.org/1999/02/22-rdf-syntax-ns# | Typer                                    |


### Links og eksterne ressourcer

* DanNets SPARQL-endpoint er på: [`/dannet/sparql`](/dannet/sparql)*
* Udforsk data på: [`wordnet.dk`](/)*
* Den fulde SPARQL-specifikation: [W3C SPARQL 1.1 Query Language](https://www.w3.org/TR/sparql11-query/)*
* En bredere SPARQL-tutorial (med Wikidata-eksempler): [SPARQL on Wikibooks](https://en.wikibooks.org/wiki/SPARQL)*


## Appendiks A: SPARQL-editoren

DanNets [SPARQL-editor](/dannet/sparql) er en browserbaseret grænseflade til at skrive og køre forespørgsler mod den offentlige DanNet-triplestore. Denne database indeholder ikke blot de centrale DanNet-data og tilhørende skemaer, men også sentimentdata fra [DDS](https://github.com/dsldk/danish-sentiment-lexicon), morfologiske data fra [COR](http://ordregister.dk), links til det [Collaborative Interlingual Index](https://github.com/globalwordnet/cili), samt et helt ekstra WordNet: [Open English WordNet](https://en-word.net/).

[![SPARQL-editoren med en SELECT-forespørgsel](/images/sparql-editor.png)](/dannet/sparql)

### At skrive og køre forespørgsler

Editoren har et tekstfelt med SPARQL-syntaksfremhævning, linjenumre og matching af parenteser. Alle [almindelige præfikser](#almindelige-praefikser) er foruddefinerede, så man kan bruge korte former som `dn:`, `dns:`, `wn:`, osv. uden at skrive `PREFIX`-deklarationer. Præfiks-blokken er foldet sammen som standard for at holde editoren overskuelig.

To knapper sidder ved siden af editoren: **Eksekvér** kører forespørgslen, mens **Formatér** validerer og omformaterer den (uden at køre den). Hvis forespørgslen har en syntaksfejl, vises fejlmeddelelsen nedenunder; hvis den er gyldig, omformateres forespørgslen til et standardlayout. Det er en nem måde at tjekke syntaksen inden man kører forespørgslen.

### Kontroller

Under editoren er der flere kontroller:

- **Results** sætter sidestørrelsen (10, 20, 50 eller 100 resultater pr. side). Bemærk at der er en hård grænse på 100 resultater pr. forespørgsel (også uanset en eksplicit `LIMIT`-klausul i forespørgslen).
- **Kilde** vælger inferenstilstanden: *Auto* (standard) lader serveren bestemme, *Rå* forespørger kun den rå triplestore (hurtigere, men kan kræve mere komplekse forespørgsler), og *Afledt* gennemtvinger inferensmodellen (langsommere, men inkluderer tripler afledt via logisk inferens). Se [Rå, afledt og auto](#raa-afledt-og-auto) for detaljer.
- **Fjern dubletter** slår `DISTINCT` til/fra på resultaterne.
- **Beriget** slår etiketberigelse (label enrichment) til/fra. Når det er aktiveret, slår editoren menneskelæsbare etiketter op for alle ressource-URI'er i resultaterne og fletter dem med RDF-ressourcerne, så man ikke behøver manuelt at joine på `rdfs:label` i hver forespørgsel. Det er også sådan ressourcer normalt vises på hele DanNet-websitet.

### Resultater, paginering og download

Resultater vises som en tabel med klikbare ressourcelinks. Hvis resultatsættet overstiger sidestørrelsen, vises forrige/næste-knapper. Hvis forespørgslen indeholder et `LIMIT` eller `OFFSET`, deaktiverer editoren den automatiske pagineringsfunktion og gør det muligt at gøre det manuelt i stedet (se [LIMIT, OFFSET og paginering](#limit-offset-og-paginering) for vejledning om hvornår man selv bør skrive disse).

Under resultaterne viser statusindikatorer om resultatet blev serveret fra cache og om inferens blev brugt. Et **JSON download**-link gør det muligt at gemme det aktuelle resultatsæt i et [standardiseret format](https://www.w3.org/TR/sparql12-results-json/).

Den fulde forespørgselstilstand (forespørgselstekst, sidestørrelse, inferenstilstand, distinct, berigelse) er kodet i sidens URL, så man kan dele eller bogmærke enhver forespørgsel. "Kør denne forespørgsel"-linkene i denne guide fungerer præcis på denne måde.

### Rå, afledt og auto

Den offentlige DanNet-triplestore indeholder både eksplicit erklærede tripler ("rå") og tripler der kan afledes via logisk inferens. For eksempel gemmer DanNet `ontolex:sense`-links fra ord til betydninger og `ontolex:evokes`-links fra ord til synsets som eksplicitte tripler. Men det omvendte link `ontolex:isLexicalizedSenseOf` (fra en betydning tilbage til sit synset) findes kun i inferensmodellen, fordi det er afledt fra ontologidefinitionerne.

**Kilde**-kontrollen i editoren vælger hvilken model der forespørges:

- **Rå** forespørger kun de eksplicit erklærede tripler. Det er hurtigere, men tripelmønstre der kun virker med inferens, vil så ikke returnere nogen resultater.
- **Afledt** forespørger inferensmodellen, som inkluderer både rå tripler og logisk afledte. Det er langsommere, men mere komplet.
- **Auto** (standard) prøver først den rå model og gentager automatisk med inferensmodellen, hvis den rå forespørgsel ikke returnerer nogen resultater.

Auto fungerer godt til de fleste forespørgsler, men kan være misvisende, når en forespørgsel returnerer *nogle* resultater fra den rå model, som ikke er de resultater man forventede. For eksempel kan en forespørgsel der scanner entries på tværs af både DanNet og det engelske WordNet finde matches i de engelske data (som har eksplicitte `ontolex:isLexicalizedSenseOf`-tripler), mens den helt overser de danske data (hvor disse tripler er infererede). Da den rå model returnerede et ikke-tomt resultat, prøver Auto aldrig igen med inferens.

Når man ved, at ens forespørgsel afhænger af infererede tripler, bør man eksplicit sætte kilden til *Afledt*.


## Appendiks B: Hurtig oversigt

**[RDF](#hvad-er-rdf)** er dataformatet. Al data i DanNet er gemt som **tripler**, dvs. simple udsagn af formen `subjekt prædikat objekt`. For eksempel: *synset-52 har-definitionen "sødt bagværk…"*. En samling af tripler udgør en graf.

**[SPARQL](#hvad-er-sparql)** er forespørgselssproget for RDF-grafer. Man skriver et mønster af tripler med ubekendte **variabler** (markeret med `?`), og databasen finder så alle matchende tripler. Det svarer til hvordan SQL kan lave forespørgsler i tabeller, hvor man så her bare laver en mod en graf.

**DanNet** organiserer sine data omkring tre centrale byggesten ([ord, betydninger og synsets](#dannets-datamodel-forenklet)), der følger strukturen fra det oprindelige Princeton WordNet. Disse er formaliseret for RDF vha. [OntoLex-Lemon](https://www.w3.org/2016/05/ontolex/)-standarden. Kæden er: **Ord → Betydning → Synset**. Et ord kan have flere betydninger, og hver betydning peger på et synset. Synsets er forbundet med hinanden gennem [semantiske relationer](#udforsk-relationer).

Enhver **RDF-ressource** (ord, betydninger, synsets) er identificeret ved en **URI** (f.eks. `https://wordnet.dk/dannet/data/synset-52`), og man kan besøge dem alle i sin browser. Egenskaber som etiketter, definitioner og relationer er alle blot tripler der peger fra én URI til en anden (eller til en tekstværdi).

Med dette in mente gennemgår resten af denne guide RDF og SPARQL i detaljer, startende fra bunden.
