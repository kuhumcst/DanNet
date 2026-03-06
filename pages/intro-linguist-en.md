* [Layman](/dannet/page/intro-layman)
* [Developer](/dannet/page/intro-developer)
* [ontologist](/dannet/page/intro-rdf)
* **Linguist**

# What is DanNet?
DanNet is the Danish WordNet: a large-scale lexical-semantic resource grouping Danish words into synonym sets (synsets) connected through a rich set of semantic relations. It currently contains ~70K synsets covering ~62K words.

Beyond the standard WordNet relations such as hypernymy, hyponymy, and meronymy, DanNet includes a wide range of relations inherited from the [EuroWordNet][EWN] tradition, such as `involved_agent`, `used_for`, `has_mero_part`, `is_caused_by`, and domain associations. It was created by the [Centre for Language Technology][CST] at the University of Copenhagen and [Dansk Sprog- og Litteraturselskab][DSL], and builds on the [EuroWordNet][EWN] tradition. This new edition was first published in 2023, supported by funding from the [Carlsberg Foundation][Carlsbergfondet].

## What changed in this edition?
This edition (2023–) is a major rewrite. The data has been converted to an RDF-native representation using the [Ontolex-lemon][Ontolex] standard with [GWA relations][GWA], around 5000 new senses (mostly adjectives) have been added, and many inconsistencies from the older DanNet have been cleaned up. So-called "supersenses" have been added based on the SemDaX corpora mapping. A detailed list of changes is available on the [releases page][releases].

## Standards and interoperability
DanNet aligns with the [Global WordNet Association][GWA] interoperability standards and uses the [Ontolex-lemon][Ontolex] model, making it linkable to [Princeton WordNet][PWN], the [Open English WordNet][OEWN], and wordnets for other languages via the [Collaborative Interlingual Index][CILI]. It also integrates with Danish resources such as [COR][COR] (Det Centrale Ordregister) and [DDS][DDS] (Det Danske Sentimentleksikon, a sentiment lexicon). The [sense/synset label format][label-rewrite] is documented separately.

## Exploring DanNet
You can browse the resource at [wordnet.dk][home] using the search field. Each synset page shows the word's different senses with definitions, usage examples, and a radial diagram visualising the semantic relations emanating from the synset. You can click on any related concept to explore further.

[![The 'kage' synset in DanNet](/images/kage_dannet.png)](/dannet/data/synset-52)

The data is freely available under the [CC BY-SA 4.0][license] license and can be downloaded in RDF/Turtle, CSV, and WN-LMF XML from the [downloads page][downloads].

## Using DanNet in research
DanNet can be loaded directly via the [WN-LMF][wn-lmf] format into libraries such as Python's [`wn`][pywn]. A public [SPARQL endpoint][sparql] is also available for advanced querying, and an [MCP server][mcp] enables integration with AI-assisted research workflows.

For citing DanNet and finding related published works, see the university [project page][projectpage].

[home]: https://wordnet.dk "DanNet"
[DSL]: https://dsl.dk/ "Dansk Sprog- og Litteraturselskab"
[CST]: https://cst.ku.dk/english "Centre for Language Technology (University of Copenhagen)"
[Carlsbergfondet]: https://www.carlsbergfondet.dk/en "The Carlsberg Foundation"
[EWN]: http://www.illc.uva.nl/EuroWordNet/ "EuroWordNet"
[PWN]: https://wordnet.princeton.edu/ "Princeton WordNet"
[Ontolex]: https://www.w3.org/2016/05/ontolex/ "Lexicon Model for Ontologies"
[GWA]: http://globalwordnet.org/ "Global WordNet Association"
[COR]: http://ordregister.dk "Det Centrale Ordregister"
[DDS]: https://github.com/dsldk/danish-sentiment-lexicon "Det Danske Sentimentleksikon"
[OEWN]: https://en-word.net/ "Open English WordNet"
[CILI]: https://github.com/globalwordnet/cili "Collaborative Interlingual Index"
[license]: https://creativecommons.org/licenses/by-sa/4.0/ "CC BY-SA 4.0"
[sparql]: /dannet/sparql "SPARQL endpoint"
[mcp]: /dannet/page/mcp "MCP server"
[wn-lmf]: /export/wn-lmf/dn "DanNet (WN-LMF)"
[pywn]: https://github.com/goodmami/wn "Python wn library"
[Github]: https://github.com/kuhumcst/DanNet "The Github project page"
[downloads]: /dannet/page/downloads "Dataset downloads"
[releases]: https://wordnet.dk/dannet/page/releases "Past releases"
[label-rewrite]: /dannet/page/label-rewrite "Sense/synset label format"
[projectpage]: https://cst.ku.dk/projekter/dannet "DanNet project page"
