# Om DanNet
DanNet er et WordNet for det danske sprog skabt i fællesskab af [DSL][DSL] og [CST][CST]. Denne nye udgave af sprogressourcen blev første gang udgivet i 2023 og er blevet støttet med midler fra [Carlsbergfondet][Carlsbergfondet].

## Et WordNets opbygning
Et [WordNet][WordNet] er en lexico-semantisk netværksgraf, der viser hvordan betydninger i sproget relaterer til andre gennem navngivne forbindelser. Man kan også tænke på et WordNet som en slags maskinlæsbar ordbog. For at få en fornemmelse af hvad DanNet er, kan du prøve at søge efter et lemma ved at klikke på **luppen** i øverste venstre hjørne.

Til forskel fra en almindelig ordbog er det ikke definitionen af ordet, der står i centrum, men i højere grad ordets relationer til andre ord. I DanNet kan man f.eks. se at en [dværgpil][dværgpil] er en slags [busk][busk], at et [lysthus][lysthus] findes i en [have][have], at [fiberdrys][fiberdrys] bruges til at [spise][spise], og at [kager][kage] typisk fremstilles ved [bagning][bage] og typisk er lavet af [mel][mel] og [sukker][sukker].

## Hent vores data
DanNet er baseret på [Ontolex][Ontolex]-standarden med [tilføjelser][GWA RDF] fra [Global WordNet Association][GWA]. Du kan udforske DanNet direkte her på wordnet.dk, men du kan også downloade vores data som et `RDF`-datasæt eller i en lidt mere begrænset `CSV`-udgave. Vi tilbyder også DanNet som `WN-LMF`, klar til at blive brugt i software der understøtter dette format. Alle vores datasæt udgives under [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/)-licensen:

- [RDF-udgave][DanNet RDF] - fuldt datasæt (minus udledt data)
- [CSV-udgave][DanNet CSV] - alternativt datasæt
- [WN-LMF-udgave][DanNet WN-LMF] - alternativt datasæt, begrænset til WN-LMF relations

DanNet er også integreret med [COR][COR] og [DDS][DDS], samt det [engelske WordNet][OEWN] (du kan hente deres datasæt fra deres egen side) som vi har udvidet med RDF-etiketter, der minder om dem brugt i DanNet. Disse alternative RDF-datasæt kan ligeledes downloades her på siden:

- [COR-integration][COR-integration]
- [DDS-integration][DDS-integration]
- [OEWN-udvidelse][OEWN-extension]

Vi har inkluderer også [CILI][CILI]-data i vores database, da dette bruges som et fælles integrationspunkt for forskellige WordNets.

Alle udgaver af denne iteration af DanNet kan derudover downloades fra vores [releases][releases]-side på Github.

> NOTE: I tidligere versioner af DanNet, kunne du derudover hente en komplet kopi af al data der kunne tilgås på wordnet.dk/dannet, inklusiv logisk udledt data og tilknyttede RDF-skemaer. Desværre har dette vist sig at være for at ressourcekrævende at generere som en del af en almindelig DanNet-udgivelse. Vi vil forsøge at ændre på dette i fremtiden.

## Dokumentation
Følgende dokumenter er kun tilgængelige på engelsk og primært tiltænkt udviklere:

* [The original rationale][rationale]
* [Querying DanNet][queries]
* [Explaining the sense/synset labels][label-rewrite]
* [Github-projektet][Github]

[DSL]: https://dsl.dk/ "Dansk Sprog- og Litteraturselskab"
[CST]: https://cst.ku.dk/ "Center for Sprogteknologi (Københavns Universitet)"
[Carlsbergfondet]: https://www.carlsbergfondet.dk/da "Carlsbergfondet"
[WordNet]: https://wordnet.princeton.edu/ "What is WordNet?"
[Ontolex]: https://www.w3.org/2016/05/ontolex/ "Lexicon Model for Ontologies"
[GWA RDF]: https://globalwordnet.github.io/schemas/#rdf "GWA RDF schema"
[GWA]: http://globalwordnet.org/ "Global WordNet Association"
[COR]: http://ordregister.dk "Det Centrale Ordregister"
[DDS]: https://github.com/dsldk/danish-sentiment-lexicon "Det Danske Sentimentleksikon"
[OEWN]: https://en-word.net/ "Open English WordNet"
[CILI]: https://github.com/globalwordnet/cili "Collaborative Interlingual Index"
[DanNet RDF]: /export/rdf/dn "DanNet (RDF)"
[DanNet CSV]:  /export/csv/dn "DanNet (CSV)"
[DanNet WN-LMF]: /export/wn-lmf/dn "DanNet (WN-LMF)"
[COR-integration]: /export/rdf/cor "COR-integration (RDF)"
[DDS-integration]: /export/rdf/dds "DDS-integration (RDF)"
[OEWN-extension]: /export/rdf/oewn-extension "OEWN-udvidelse (RDF)"
[complete]: /export/rdf/dn?variant=complete "DanNet + COR + DDS + logisk udledt data (RDF)"
[dværgpil]: /dannet/data/synset-1304 "dværgpil"
[busk]: /dannet/data/synset-597 "busk"
[lysthus]: /dannet/data/synset-4733 "lysthus"
[have]: /dannet/data/synset-1876 "have"
[fiberdrys]: /dannet/data/synset-34989 "fiberdrys"
[spise]: /dannet/data/synset-124 "spise"
[kage]: /dannet/data/synset-52 "kage"
[bage]: /dannet/data/synset-145 "bage"
[mel]: /dannet/data/synset-131 "mel"
[sukker]: /dannet/data/synset-128 "sukker"
[label-rewrite]: /dannet/page/label-rewrite "Synset/sense label rewrite"
[rationale]: /dannet/page/rationale "Rationale"
[queries]: /dannet/page/queries "Queries"
[Github]: https://github.com/kuhumcst/DanNet "Github-projektet"
[releases]: https://github.com/kuhumcst/DanNet/releases "Tidligere releases"
