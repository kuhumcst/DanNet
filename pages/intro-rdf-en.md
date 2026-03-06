* [Layman](/dannet/page/intro-layman)
* [Developer](/dannet/page/intro-developer)
* **RDF Expert**
* [Linguist](/dannet/page/intro-linguist)

# What is DanNet?
DanNet is a Danish WordNet published as Linked Open Data, modelled with [Ontolex-lemon][Ontolex] and the [GWA ontology][GWA RDF]. It was created by the [Centre for Language Technology][CST] (University of Copenhagen) and [Dansk Sprog- og Litteraturselskab][DSL], and is available at [wordnet.dk][home].

## RDF architecture
The triplestore is [Apache Jena][jena] (TDB2 in production) with optional OWL inference. Data is served with full content negotiation: HTML, JSON-LD, and Turtle. [RDFa][rdfa] is embedded in entity pages for machine-readable markup. Namespace prefixes follow a `dn:` / `dnc:` / `dns:` convention for data, concepts, and schema respectively.

## Endpoints
A public [SPARQL endpoint][sparql] is available for querying the graph. An [MCP server][mcp] enables integration with AI tools.

## Interoperability
DanNet integrates with [COR][COR] (Det Centrale Ordregister), [DDS][DDS] (Det Danske Sentimentleksikon), and the [Open English WordNet][OEWN]. It uses the [CILI][CILI] as a common interlinking index across WordNets.

## Datasets
All datasets are published under the [CC BY-SA 4.0][license] license and can be downloaded from the [downloads page][downloads] in RDF/Turtle, CSV, and WN-LMF XML.

[home]: https://wordnet.dk "DanNet"
[DSL]: https://dsl.dk/ "Dansk Sprog- og Litteraturselskab"
[CST]: https://cst.ku.dk/english "Centre for Language Technology (University of Copenhagen)"
[Ontolex]: https://www.w3.org/2016/05/ontolex/ "Lexicon Model for Ontologies"
[GWA RDF]: https://globalwordnet.github.io/schemas/#rdf "GWA RDF schema"
[GWA]: http://globalwordnet.org/ "Global WordNet Association"
[COR]: http://ordregister.dk "Det Centrale Ordregister"
[DDS]: https://github.com/dsldk/danish-sentiment-lexicon "Det Danske Sentimentleksikon"
[OEWN]: https://en-word.net/ "Open English WordNet"
[CILI]: https://github.com/globalwordnet/cili "Collaborative Interlingual Index"
[license]: https://creativecommons.org/licenses/by-sa/4.0/ "CC BY-SA 4.0"
[downloads]: /dannet/page/downloads "Dataset downloads"
[sparql]: /dannet/sparql "SPARQL endpoint"
[mcp]: /dannet/page/mcp "MCP server"
[jena]: https://jena.apache.org/ "Apache Jena"
[rdfa]: https://www.w3.org/TR/rdfa-primer/ "RDFa"
[Github]: https://github.com/kuhumcst/DanNet "The Github project page"
