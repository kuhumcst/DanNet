# DanNet
DanNet is a WordNet for the Danish language created as a collaboration between [DSL][DSL] and [CST][CST]. This new edition of the language resource was first published in 2023 and has been supported by funding from the [Carlsberg Foundation][Carlsbergfondet].

## The structure of a WordNet
A [WordNet][WordNet] is a lexico-semantic network graph that shows how senses of a language relate to others through named relations. One can also think of a WordNet as a kind of machine-readable dictionary. To get a sense of what DanNet is, you can try searching for a lemma by clicking on the **looking glas** in the upper left corner.

Unlike a normal dictionary, the definitions of words aren't central; instead, relations to other words are the important part. For example, in DanNet you can see that a [Swiss willow][dværgpil] is a kind of [bush][busk], that a [gazebo][lysthus] is located in a [garden][have], that "[fiberdrys][fiberdrys]" is for [eating][spise], and that [cakes][kage] are typically produced by [baking][bage] and usually made from [flour][mel] and [sugar][sukker].

## Download our data
DanNet is based on the [Ontolex][Ontolex] standard with [additions][GWA RDF] from the [Global WordNet Association][GWA]. You can explore DanNet directly on wordnet.dk, but you may also download our data as an [RDF data set][DanNet RDF] or in a slightly more limited [CSV edition][DanNet CSV]. All our data sets are published under the [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) license.

DanNet is integrated with [COR][COR] and [DDS][DDS] too. These alternative RDF data sets can also be downloaded on this page ([COR integration][COR-integration] og [DDS integration][DDS-integration]).

You may also download [DanNet+COR+DDS][DanNet+COR+DDS] as a combined data set or download a [complete copy][complete] of all the data that can be found on wordnet.dk/dannet, including logically inferred data and associated RDF schemas.

## More documentation
The following documents are only available in English and mostly for developers:

* [The original rationale][rationale]
* [Querying DanNet][queries]
* [Explaining the sense/synset labels][label-rewrite]

[DSL]: https://dsl.dk/ "Dansk Sprog- og Litteraturselskab"
[CST]: https://cst.ku.dk/english "Centre for Language Technology (University of Copenhagen)"
[Carlsbergfondet]: https://www.carlsbergfondet.dk/en "The Carlsberg Foundation"
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
[complete]: /export/rdf/dn?variant=complete "DanNet + COR + DDS + logically inferred data (RDF)"
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
