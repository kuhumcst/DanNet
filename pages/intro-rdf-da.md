* [Lægmand](/dannet/page/intro-layman)
* [Udvikler](/dannet/page/intro-developer)
* **Ontolog**
* [Lingvist](/dannet/page/intro-linguist)

# Hvad er DanNet?
DanNet er et dansk WordNet udgivet som Linked Open Data, modelleret med [Ontolex-lemon][Ontolex] og [GWA-ontologien][GWA RDF]. Det indeholder i øjeblikket ca. 70.000 synsets, ca. 70.000 senser og ca. 62.000 leksikalske opslag. Det er skabt af [Center for Sprogteknologi][CST] (Københavns Universitet) og [Dansk Sprog- og Litteraturselskab][DSL] med støtte fra [Carlsbergfondet][Carlsbergfondet] og er tilgængeligt på [wordnet.dk][home].

## RDF-arkitektur
WordNet-dataene i DanNet afbildes til følgende [Ontolex-lemon][Ontolex]-typer:

- ontolex:LexicalConcept (synsets)
- ontolex:LexicalSense (ordbetydninger)
- ontolex:LexicalEntry (ord)
- ontolex:Form (ordformer)

Kernen i DanNet består af tre namespaces:

| Præfiks | URI | Formål |
|---------|-----|--------|
| `dn:` | `https://wordnet.dk/dannet/data/` | [Dataset-instanser](/dannet/data) |
| `dnc:` | `https://wordnet.dk/dannet/concepts/` | EuroWordNet [ontologiske typer](/dannet/concepts) |
| `dns:` | `https://wordnet.dk/dannet/schema/` | DanNet [skema](/dannet/schema)-definitioner |

Semantiske relationer bruger standard `wn:`-relationer (f.eks. `wn:hypernym`, `wn:meronym`) suppleret af DanNet-specifikke relationer defineret i `dns:` (f.eks. `dns:usedFor`, `dns:involvedAgent`). Det fulde sæt af relationer er defineret i [DanNet-skemaet][dns-schema].

## Content negotiation
Alle RDF-ressourcer i DanNet-datasættet er dereferencerbare. Anmod om forskellige repræsentationer via `Accept`-headeren:

| Accept-header | Format |
|---------------|--------|
| `text/html` | Webside med indlejret [RDFa][rdfa] |
| `text/turtle` | RDF/Turtle |
| `application/ld+json` | JSON-LD |

Eksempel:

```bash
curl -H "Accept: text/turtle" https://wordnet.dk/dannet/data/synset-5028
```

## Sammenkoblinger
DanNet anvender [Collaborative Interlingual Index][CILI] (CILI) som fælles sammenkoblingsmekanisme på tværs af WordNets. Det integrerer desuden med [COR][COR] (Det Centrale Ordregister), [DDS][DDS] (Det Danske Sentimentleksikon) og [Open English WordNet][OEWN]. Ledsagende RDF-datasæt til disse integrationer er tilgængelige på [download-siden][downloads].

## Datasæt
Alle DanNet-datasæt er udgivet under [CC BY-SA 4.0][license]-licensen og kan downloades fra [download-siden][downloads] i RDF/Turtle, CSV og WN-LMF XML. Alle udgivelser er også tilgængelige på [GitHub-udgivelsessiden][releases].

## Offentlig adgang

### SPARQL-endpoint
Et offentligt [SPARQL-endpoint][sparql] er tilgængeligt til forespørgsler mod grafen. Det har begrænsninger på resultatsætstørrelse og forespørgselstid.

Eksempelforespørgsel:

```sparql
PREFIX wn: <https://globalwordnet.github.io/schemas/wn#>
PREFIX dn: <https://wordnet.dk/dannet/data/>

SELECT ?hypernym WHERE {
  dn:synset-5028 wn:hypernym ?hypernym .
}
```

> **BEMÆRK:** Almindelige præfikser som `wn` og `dn` tilføjes automatisk, når du bruger det offentlige SPARQL-endpoint, så de er ikke strengt nødvendige!

### MCP-server
En [MCP-server][mcp] muliggør integration med AI-værktøjer. Det offentlige API returnerer generelt data som [JSON-LD][JSON-LD].

[home]: https://wordnet.dk "DanNet"
[DSL]: https://dsl.dk/ "Dansk Sprog- og Litteraturselskab"
[CST]: https://cst.ku.dk/ "Center for Sprogteknologi (Københavns Universitet)"
[Carlsbergfondet]: https://www.carlsbergfondet.dk/da "Carlsbergfondet"
[Ontolex]: https://www.w3.org/2016/05/ontolex/ "Lexicon Model for Ontologies"
[GWA RDF]: https://globalwordnet.github.io/schemas/#rdf "GWA RDF schema"
[GWA]: http://globalwordnet.org/ "Global WordNet Association"
[COR]: http://ordregister.dk "Det Centrale Ordregister"
[DDS]: https://github.com/dsldk/danish-sentiment-lexicon "Det Danske Sentimentleksikon"
[OEWN]: https://en-word.net/ "Open English WordNet"
[CILI]: https://github.com/globalwordnet/cili "Collaborative Interlingual Index"
[license]: https://creativecommons.org/licenses/by-sa/4.0/ "CC BY-SA 4.0"
[downloads]: /dannet/page/downloads "Download datasæt"
[releases]: https://github.com/kuhumcst/DanNet/releases "Tidligere udgivelser"
[sparql]: /dannet/sparql "SPARQL-endpoint"
[mcp]: /dannet/page/mcp "MCP-server"
[jena]: https://jena.apache.org/ "Apache Jena"
[rdfa]: https://www.w3.org/TR/rdfa-primer/ "RDFa"
[Github]: https://github.com/kuhumcst/DanNet "GitHub-projektsiden"
[JSON-LD]: https://json-ld.org/ "JSON for Linking Data"
[dns-schema]: /schema/dns "DanNet-skema"
