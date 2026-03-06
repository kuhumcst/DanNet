* [Layman](/dannet/page/intro-layman)
* **Developer**
* [ontologist](/dannet/page/intro-rdf)
* [Linguist](/dannet/page/intro-linguist)

# What is DanNet?
DanNet is an open Danish [WordNet][WordNet]: a structured lexical database organising Danish words into synonym sets (synsets) linked by semantic relations. It currently contains ~70K synsets covering ~62K words. It was created by the [Centre for Language Technology][CST] (University of Copenhagen) and [Dansk Sprog- og Litteraturselskab][DSL], and is available in an interactive version at [wordnet.dk][home]. DanNet is designed to be easy to integrate with other lexical data sources.

The DanNet source code is MIT-licensed and available (along with current and past releases of the DanNet datasets) [at our GitHub repo][Github].

## Accessing the data

### Content negotiation
Every RDF resource in DanNet resolves as a dereferenceable URI. You can request different representations using the HTTP `Accept` header: `text/html` for the web page, `application/ld+json` for JSON-LD, and `text/turtle` for RDF/Turtle. For example:

```bash
curl -H "Accept: application/ld+json" https://wordnet.dk/dannet/data/synset-5028
```

### SPARQL endpoint
A public [SPARQL endpoint][SPARQL endpoint] is available for programmatic querying. It has restrictions on result set size and query execution time.

### WN-LMF + Python
The [WN-LMF][wn-lmf] format can be used with the [wn][wn] Python library:

```python
import wn

wn.add("dannet-wn-lmf.xml.gz")

for synset in wn.synsets('kage'):
    print((synset.lexfile() or "?") + ": " + (synset.definition() or "?"))
```

> **NOTE:** WN-LMF only includes official GWA relations; DanNet-specific relations (such as `used_for`) are only available in the RDF format.

### AI integration
An [MCP server][mcp] is available for integration with AI/LLM tools such as Claude and ChatGPT. The MCP server URL is `https://wordnet.dk/mcp`. It provides direct access to the DanNet API and returns results as [JSON-LD][JSON-LD] which the LLM can interpret using the provided schemas.

## Datasets
DanNet is available in a handful of different datasets formatted as either RDF/Turtle, CSV, or WN-LMF XML. All datasets are published under the [CC BY-SA 4.0][license] license. You can [download them here][downloads]. All releases are also available on the [Github releases page][releases].

The RDF dataset is the canonical version. It can be imported into any modern RDF triplestore and queried with [SPARQL][SPARQL].

## Standards and integrations
DanNet is modelled using the [Ontolex-lemon][Ontolex] standard with [additions][GWA RDF] from the [Global WordNet Association][GWA], making it interoperable with other WordNets and linked data resources. It integrates directly with [COR][COR] (Det Centrale Ordregister, a Danish word registry), [DDS][DDS] (Det Danske Sentimentleksikon, a Danish sentiment lexicon), and the [Open English WordNet][OEWN].

## Tech stack
The DanNet codebase is an MIT-licensed Clojure/ClojureScript project hosted on [GitHub][Github], built on top of the [Apache Jena][jena] RDF triplestore. The web app is a [Pedestal][pedestal]-based backend with a [Rum][rum] (React) frontend and works both as a server-rendered site and a single-page application.

## Documentation
Additional developer-oriented documentation:

* [Querying DanNet][queries] — SPARQL, Aristotle DSL, and graph traversal
* [Sense/synset label format][label-rewrite]
* [Design rationale][rationale]

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
[SPARQL]: https://www.w3.org/TR/sparql11-query/ "SPARQL 1.1 Query Language specification"
[SPARQL endpoint]: /dannet/sparql "SPARQL endpoint"
[mcp]: /dannet/page/mcp "MCP server"
[JSON-LD]: https://json-ld.org/ "JSON for Linking Data"
[jena]: https://jena.apache.org/ "Apache Jena"
[pedestal]: https://github.com/pedestal/pedestal "Pedestal"
[rum]: https://github.com/tonsky/rum "Rum"
[Github]: https://github.com/kuhumcst/DanNet "The Github project page"
[wn]: https://github.com/goodmami/wn "A Python library for exploring WordNets"
[wn-lmf]: /export/wn-lmf/dn "DanNet (WN-LMF)"
[downloads]: /dannet/page/downloads "Dataset downloads"
[releases]: https://github.com/kuhumcst/DanNet/releases "Past releases"
[queries]: /dannet/page/queries "Querying DanNet"
[label-rewrite]: /dannet/page/label-rewrite "Synset/sense label format"
[rationale]: /dannet/page/rationale "Design rationale"
