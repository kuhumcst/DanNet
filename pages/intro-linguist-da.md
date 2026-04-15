* [Lægmand](/dannet/page/intro-layman)
* [Udvikler](/dannet/page/intro-developer)
* [Ontolog](/dannet/page/intro-rdf)
* **Lingvist**

# Hvad er DanNet?
DanNet er det danske WordNet: en omfattende leksikalsk-semantisk ressource, der grupperer danske ord i synonymgrupper (synsets) forbundet via et rigt sæt af semantiske relationer. Det indeholder i øjeblikket ca. 70.000 synsets, der dækker ca. 62.000 ord.

Ud over de standardiserede WordNet-relationer såsom hyperonymi, hyponymi og meronymi indeholder DanNet en bred vifte af relationer arvet fra [EuroWordNet][EWN]-traditionen, herunder `involved_agent`, `used_for`, `has_mero_part`, `is_caused_by` og domæneassociationer. Det er skabt af [Center for Sprogteknologi][CST] ved Københavns Universitet og [Dansk Sprog- og Litteraturselskab][DSL] og bygger på [EuroWordNet][EWN]-traditionen. Denne nye udgave blev første gang udgivet i 2023 med støtte fra [Carlsbergfondet][Carlsbergfondet].

## Hvad er nyt i denne udgave?
Denne udgave (2023–) er en større omskrivning. Dataene er konverteret til en RDF-native repræsentation ved hjælp af [Ontolex-lemon][Ontolex]-standarden med [GWA-relationer][GWA], ca. 5.000 nye senser (primært adjektiver) er tilføjet, og mange uregelmæssigheder fra det gamle DanNet er rettet. Såkaldte "supersenses" er tilføjet på baggrund af SemDaX-korpussets mapping. En detaljeret ændringsliste er tilgængelig på [udgivelsessiden][releases].

## Standarder og interoperabilitet
DanNet er i overensstemmelse med [Global WordNet Association][GWA]s interoperabilitetsstandarder og bruger [Ontolex-lemon][Ontolex]-modellen, hvilket gør det muligt at koble det til [Princeton WordNet][PWN], [Open English WordNet][OEWN] og WordNets for andre sprog via [Collaborative Interlingual Index][CILI]. Det integrerer også med danske ressourcer som [COR][COR] (Det Centrale Ordregister) og [DDS][DDS] (Det Danske Sentimentleksikon). [Sense/synset-labelformatet][label-rewrite] er dokumenteret separat.

## Udforskning af DanNet
Du kan gennemse ressourcen på [wordnet.dk][home] via søgefeltet. Hver synset-side viser ordets forskellige senser med definitioner, brugseksempler og et radialdiagram, der visualiserer de semantiske relationer, der udgår fra synsettet. Du kan klikke på et hvilket som helst relateret begreb for at udforske videre.

[![Synsettet 'kage' i DanNet](/images/kage_dannet.png)](/dannet/data/synset-52)

Dataene er frit tilgængelige under [CC BY-SA 4.0][license]-licensen og kan downloades i RDF/Turtle, CSV og WN-LMF XML fra [download-siden][downloads].

## Brug af DanNet i forskning
* DanNet kan indlæses direkte via [WN-LMF][wn-lmf]-formatet i software-biblioteker som f.eks. [`wn`][pywn] (Python).
* Et offentligt [SPARQL-endpoint][sparql] med en interaktiv forespørgselseditor er også tilgængeligt til avancerede forespørgsler. Vores [guide][SPARQL guide] giver en praktisk introduktion til at komme i gang.
* En [MCP-server][mcp] muliggør integration med AI-assisterede forskningsworkflows.

For citationer af DanNet og relevante publikationer, se universitetets [projektside][projectpage].

[home]: https://wordnet.dk "DanNet"
[DSL]: https://dsl.dk/ "Dansk Sprog- og Litteraturselskab"
[CST]: https://cst.ku.dk/ "Center for Sprogteknologi (Københavns Universitet)"
[Carlsbergfondet]: https://www.carlsbergfondet.dk/da "Carlsbergfondet"
[EWN]: http://www.illc.uva.nl/EuroWordNet/ "EuroWordNet"
[PWN]: https://wordnet.princeton.edu/ "Princeton WordNet"
[Ontolex]: https://www.w3.org/2016/05/ontolex/ "Lexicon Model for Ontologies"
[GWA]: http://globalwordnet.org/ "Global WordNet Association"
[COR]: http://ordregister.dk "Det Centrale Ordregister"
[DDS]: https://github.com/dsldk/danish-sentiment-lexicon "Det Danske Sentimentleksikon"
[OEWN]: https://en-word.net/ "Open English WordNet"
[CILI]: https://github.com/globalwordnet/cili "Collaborative Interlingual Index"
[license]: https://creativecommons.org/licenses/by-sa/4.0/ "CC BY-SA 4.0"
[sparql]: /dannet/sparql "SPARQL-endpoint"
[SPARQL guide]: /dannet/page/sparql "SPARQL-guide"
[mcp]: /dannet/page/mcp "MCP-server"
[wn-lmf]: /export/wn-lmf/dn "DanNet (WN-LMF)"
[pywn]: https://github.com/goodmami/wn "Python wn-biblioteket"
[Github]: https://github.com/kuhumcst/DanNet "GitHub-projektsiden"
[downloads]: /dannet/page/downloads "Download datasæt"
[releases]: https://wordnet.dk/dannet/page/releases "Tidligere udgivelser"
[label-rewrite]: /dannet/page/label-rewrite "Sense/synset-labelformat"
[projectpage]: https://cst.ku.dk/projekter/dannet "DanNet-projektsiden"
