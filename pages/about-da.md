# DanNet
DanNet er et WordNet for det danske sprog skabt i fælleskab af [DSL][DSL] og [CST][CST].
Denne nye udgave af sprogressourcen blev første gang udgivet i 2023 og er blevet støttet med midler fra [Carlsbergfondet][Carlsbergfondet].

## Et WordNets opbygning
Et [WordNet][WordNet] er en lexico-semantisk netværksgraf, der viser hvordan betydninger i sproget relaterer til andre gennem navngivne forbindelser. Man kan også tænke på et WordNet som en slags maskinlæsbar ordbog. For at få en fornemmelse af hvad DanNet er, kan du prøve at søge efter et lemma ved at klikke på **luppen** i øverste venstre hjørne.

Til forskel fra en almindelig ordbog er det ikke definitionen af ordet, der står i centrum, men i højere grad ordets relationer til andre ord. I DanNet kan man f.eks. se at en [dværgpil][dværgpil] er en slags [busk][busk], at et [lysthus][lysthus] findes i en [have][have], at [fiberdrys][fiberdrys] bruges til at [spise][spise], og at [kager][kage] typisk fremstilles ved [bagning][bage] og typisk er lavet af [mel][mel] og [sukker][sukker].

## Hent vores data
DanNet er baseret på [Ontolex][Ontolex]-standarden med [tilføjelser][GWA RDF] fra [Global WordNet Association][GWA]. Du kan udforske DanNet direkte her på wordnet.dk, men du kan også downloade vores data som et [RDF-datasæt][DanNet RDF] eller i en lidt mere begrænset [CSV-udgave][DanNet CSV]. Alle vores datasæt udgives under [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/)-licensen.

DanNet er også integreret med [COR][COR] og [DDS][DDS]. Disse alternative RDF-datasæt kan ligeledes downloades her på siden ([COR-integration][COR-integration] og [DDS-integration][DDS-integration]).

Du kan også hente [DanNet+COR+DDS][DanNet+COR+DDS] som et samlet datasæt eller hente en [komplet kopi][complete] af al data der kan tilgås på wordnet.dk/dannet, inklusiv logisk udledt data og tilknyttede RDF-skemaer.

## Dokumentation
Følgende dokumenter er kun tilgængelige på engelsk og primært tiltænkt udviklere:

* [The original rationale][rationale]
* [Querying DanNet][queries]
* [Explaining the sense/synset labels][label-rewrite]
* [Github-projektet][Github]: DanNets kildekode er tilgængelig på Github + yderligere dokumentation.

[DSL]: https://dsl.dk/ "Dansk Sprog- og Litteraturselskab"
[CST]: https://cst.ku.dk/ "Center for Sprogteknologi (Københavns Universitet)"
[Carlsbergfondet]: https://www.carlsbergfondet.dk/da "Carlsbergfondet"
[WordNet]: https://wordnet.princeton.edu/ "What is WordNet?"
[Ontolex]: https://www.w3.org/2016/05/ontolex/ "Lexicon Model for Ontologies"
[GWA RDF]: https://globalwordnet.github.io/schemas/#rdf "GWA RDF schema"
[GWA]: http://globalwordnet.org/ "Global WordNet Association"
[COR]: http://ordregister.dk "Det Centrale Ordregister"
[DDS]: https://github.com/dsldk/danish-sentiment-lexicon "Det Danske Sentimentleksikon"
[DanNet RDF]: /export/rdf/dn "DanNet (RDF)"
[DanNet CSV]:  /export/csv/dn "DanNet (CSV)"
[COR-integration]: /export/rdf/cor "COR-integration (RDF)"
[DDS-integration]: /export/rdf/dds "DDS-integration (RDF)"
[DanNet+COR+DDS]: /export/rdf/dn?variant=merged "DanNet + COR + DDS (RDF)"
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
