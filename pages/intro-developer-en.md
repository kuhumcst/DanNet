* [Layman](/dannet/page/intro-layman)
* **Developer**
* [RDF Expert](/dannet/page/intro-rdf)
* [Linguist](/dannet/page/intro-linguist)

# What is DanNet?
DanNet is an open Danish [WordNet][WordNet]: a structured lexical database organising Danish words into synonym sets (synsets) linked by semantic relations. It was created by the [Centre for Language Technology][CST] (University of Copenhagen) and [Dansk Sprog- og Litteraturselskab][DSL], and is available at [wordnet.dk][home].

## Data formats and access
DanNet is available as downloadable [RDF/Turtle][rdf], [CSV][csv], [WN-LMF XML][wn-lmf], and JSON-LD from the [about page][about]. All datasets are published under the [CC BY-SA 4.0][license] license.

There is a public [SPARQL endpoint][sparql] for programmatic querying. An [MCP server][mcp] is also available for integration with AI toolchains such as Claude and ChatGPT.

## Technical stack
The codebase is Clojure/ClojureScript, built on [Apache Jena][jena] as the RDF triplestore. The web app is a [Pedestal][pedestal]-based backend with a [Rum][rum]/React frontend and works both as a server-rendered site and a single-page application.

## Standards
DanNet is modelled using the [Ontolex-lemon][Ontolex] standard with [additions][GWA RDF] from the [Global WordNet Association][GWA], making it interoperable with other WordNets and linked data resources. It integrates with [COR][COR], [DDS][DDS], and the [Open English WordNet][OEWN].

## Source code
The project is open source and hosted on [Github][Github].

[home]: https://wordnet.dk "DanNet"
[WordNet]: https://wordnet.princeton.edu/ "What is WordNet?"
[DSL]: https://dsl.dk/ "Dansk Sprog- og Litteraturselskab"
[CST]: https://cst.ku.dk/english "Centre for Language Technology (University of Copenhagen)"
[Ontolex]: https://www.w3.org/2016/05/ontolex/ "Lexicon Model for Ontologies"
[GWA RDF]: https://globalwordnet.github.io/schemas/#rdf "GWA RDF schema"
[GWA]: http://globalwordnet.org/ "Global WordNet Association"
[COR]: http://ordregister.dk "Det Centrale Ordregister"
[DDS]: https://github.com/dsldk/danish-sentiment-lexicon "Det Danske Sentimentleksikon"
[OEWN]: https://en-word.net/ "Open English WordNet"
[license]: https://creativecommons.org/licenses/by-sa/4.0/ "CC BY-SA 4.0"
[about]: /dannet/page/about "About DanNet"
[sparql]: /dannet/sparql "SPARQL endpoint"
[mcp]: /dannet/page/mcp "MCP server"
[rdf]: /export/rdf/dn "DanNet (RDF)"
[csv]: /export/csv/dn "DanNet (CSV)"
[wn-lmf]: /export/wn-lmf/dn "DanNet (WN-LMF)"
[jena]: https://jena.apache.org/ "Apache Jena"
[pedestal]: https://github.com/pedestal/pedestal "Pedestal"
[rum]: https://github.com/tonsky/rum "Rum"
[Github]: https://github.com/kuhumcst/DanNet "The Github project page"
