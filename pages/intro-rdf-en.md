* [Layman](/dannet/page/intro-layman)
* [Developer](/dannet/page/intro-developer)
* **Ontologist**
* [Linguist](/dannet/page/intro-linguist)

# What is DanNet?
DanNet is a Danish WordNet published as Linked Open Data, modelled with [Ontolex-lemon][Ontolex] and the [GWA ontology][GWA RDF]. It currently contains ~70K synsets, ~70K senses, and ~62K lexical entries. It was created by the [Centre for Language Technology][CST] (University of Copenhagen) and [Dansk Sprog- og Litteraturselskab][DSL], supported by the [Carlsberg Foundation][Carlsbergfondet], and is available at [wordnet.dk][home].

## RDF architecture
The WordNet data in DanNet maps to the [Ontolex-lemon][Ontolex] types:

- ontolex:LexicalConcept (synsets)
- ontolex:LexicalSense (word senses)
- ontolex:LexicalEntry (words)
- ontolex:Form (word forms)

The core of DanNet consists of three namespaces:

| Prefix | URI | Purpose |
|--------|-----|---------|
| `dn:` | `https://wordnet.dk/dannet/data/` | [Dataset instances](/dannet/data) |
| `dnc:` | `https://wordnet.dk/dannet/concepts/` | EuroWordNet [ontological types](/dannet/concepts) |
| `dns:` | `https://wordnet.dk/dannet/schema/` | DanNet [schema](/dannet/schema) definitions |

Semantic relations use the standard `wn:` relations (e.g. `wn:hypernym`, `wn:meronym`) supplemented by DanNet-specific relations defined in `dns:` (e.g. `dns:usedFor`, `dns:involvedAgent`). The full set of relations is defined in the [DanNet schema][dns-schema].

## Content negotiation
Every RDF resource in the DanNet dataset is dereferenceable. Request different representations via the `Accept` header:

| Accept header | Format |
|---------------|--------|
| `text/html` | Web page with embedded [RDFa][rdfa] |
| `text/turtle` | RDF/Turtle |
| `application/ld+json` | JSON-LD |

Example:

```bash
curl -H "Accept: text/turtle" https://wordnet.dk/dannet/data/synset-5028
```

## Interlinking
DanNet uses the [Collaborative Interlingual Index][CILI] (CILI) as a common interlinking mechanism across WordNets. It also integrates with [COR][COR] (Det Centrale Ordregister), [DDS][DDS] (Det Danske Sentimentleksikon), and the [Open English WordNet][OEWN]. Companion RDF datasets for these integrations are available on the [downloads page][downloads].

## Datasets
All DanNet datasets are published under the [CC BY-SA 4.0][license] license and can be downloaded from the [downloads page][downloads] in RDF/Turtle, CSV, and WN-LMF XML. All releases are also available on the [Github releases page][releases].

## Public access

### SPARQL endpoint
A public [SPARQL endpoint][sparql] is available for querying the graph. It has restrictions on result set size and query execution time.

Example query:

```sparql
PREFIX wn: <https://globalwordnet.github.io/schemas/wn#>
PREFIX dn: <https://wordnet.dk/dannet/data/>

SELECT ?hypernym WHERE {
  dn:synset-5028 wn:hypernym ?hypernym .
}
```

> **NOTE:** common prefixes such as `wn` and `dn` are added automatically when using the public SPARQL endpoint, so they aren't strictly necessary!

### MCP server
An [MCP server][mcp] enables integration with AI tools. The public API generally returns data as [JSON-LD][JSON-LD].

[home]: https://wordnet.dk "DanNet"
[DSL]: https://dsl.dk/ "Dansk Sprog- og Litteraturselskab"
[CST]: https://cst.ku.dk/english "Centre for Language Technology (University of Copenhagen)"
[Carlsbergfondet]: https://www.carlsbergfondet.dk/en "The Carlsberg Foundation"
[Ontolex]: https://www.w3.org/2016/05/ontolex/ "Lexicon Model for Ontologies"
[GWA RDF]: https://globalwordnet.github.io/schemas/#rdf "GWA RDF schema"
[GWA]: http://globalwordnet.org/ "Global WordNet Association"
[COR]: http://ordregister.dk "Det Centrale Ordregister"
[DDS]: https://github.com/dsldk/danish-sentiment-lexicon "Det Danske Sentimentleksikon"
[OEWN]: https://en-word.net/ "Open English WordNet"
[CILI]: https://github.com/globalwordnet/cili "Collaborative Interlingual Index"
[license]: https://creativecommons.org/licenses/by-sa/4.0/ "CC BY-SA 4.0"
[downloads]: /dannet/page/downloads "Dataset downloads"
[releases]: https://github.com/kuhumcst/DanNet/releases "Past releases"
[sparql]: /dannet/sparql "SPARQL endpoint"
[mcp]: /dannet/page/mcp "MCP server"
[jena]: https://jena.apache.org/ "Apache Jena"
[rdfa]: https://www.w3.org/TR/rdfa-primer/ "RDFa"
[Github]: https://github.com/kuhumcst/DanNet "The Github project page"
[JSON-LD]: https://json-ld.org/ "JSON for Linking Data"
[dns-schema]: /schema/dns "DanNet schema"
