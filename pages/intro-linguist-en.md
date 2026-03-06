* [Layman](/dannet/page/intro-layman)
* [Developer](/dannet/page/intro-developer)
* [RDF Expert](/dannet/page/intro-rdf)
* **Linguist**

# What is DanNet?
DanNet is the Danish WordNet: a large-scale lexical-semantic resource grouping Danish words into synonym sets (synsets) connected through relations such as hypernymy, hyponymy, meronymy, and domain associations. It was created by the [Centre for Language Technology][CST] at the University of Copenhagen and [Dansk Sprog- og Litteraturselskab][DSL], and builds on the [EuroWordNet][EWN] tradition.

## Standards and interoperability
DanNet aligns with the [Global WordNet Association][GWA] interoperability standards and uses the [Ontolex-lemon][Ontolex] model, making it linkable to [Princeton WordNet][PWN], the [Open English WordNet][OEWN], and wordnets for other languages via the [Collaborative Interlingual Index][CILI]. It also integrates with Danish resources such as [COR][COR] (Det Centrale Ordregister) and [DDS][DDS] (Det Danske Sentimentleksikon).

## Exploring DanNet
You can browse the resource at [wordnet.dk][home] using the search field, which provides visual relation diagrams and detailed synset pages. The data is freely available under the [CC BY-SA 4.0][license] license and can be downloaded in RDF/Turtle, CSV, and WN-LMF XML from the [about page][about].

## Using DanNet in research
DanNet can be loaded directly via the [WN-LMF][wn-lmf] format into libraries such as Python's [`wn`][pywn]. A public [SPARQL endpoint][sparql] is also available for advanced querying, and an [MCP server][mcp] enables integration with AI-assisted research workflows.

[home]: https://wordnet.dk "DanNet"
[DSL]: https://dsl.dk/ "Dansk Sprog- og Litteraturselskab"
[CST]: https://cst.ku.dk/english "Centre for Language Technology (University of Copenhagen)"
[EWN]: http://www.illc.uva.nl/EuroWordNet/ "EuroWordNet"
[PWN]: https://wordnet.princeton.edu/ "Princeton WordNet"
[Ontolex]: https://www.w3.org/2016/05/ontolex/ "Lexicon Model for Ontologies"
[GWA]: http://globalwordnet.org/ "Global WordNet Association"
[COR]: http://ordregister.dk "Det Centrale Ordregister"
[DDS]: https://github.com/dsldk/danish-sentiment-lexicon "Det Danske Sentimentleksikon"
[OEWN]: https://en-word.net/ "Open English WordNet"
[CILI]: https://github.com/globalwordnet/cili "Collaborative Interlingual Index"
[license]: https://creativecommons.org/licenses/by-sa/4.0/ "CC BY-SA 4.0"
[about]: /dannet/page/about "About DanNet"
[sparql]: /dannet/sparql "SPARQL endpoint"
[mcp]: /dannet/page/mcp "MCP server"
[wn-lmf]: /export/wn-lmf "DanNet (WN-LMF)"
[pywn]: https://github.com/goodmami/wn "Python wn library"
[Github]: https://github.com/kuhumcst/DanNet "The Github project page"
