Rationale
=========
A WordNet is a knowledge graph intended for [word-sense disambiguation](https://en.wikipedia.org/wiki/Word-sense_disambiguation).

The task of word-sense disambiguation - and related tasks - is separate from the _way_ the data itself is represented. While modelling word senses has an obvious linguistic dimension to it at the highest level, achieving an optimal data representation is fundamentally a computer science decision.


### Triplets
The fundamental unit of a WordNet is the triplet, i.e. a tuple containing 3 items:

```clojure
[?subject ?predicate ?object]
```

A collection of triplets is sufficient structure to represent an entire WordNet. However, in the context of a WordNet, the triplet must be interpreted as having a directionality from left to right, indicating a relationship between the first and third items as defined by the second item of the triplet.

This kind of triplet is fundamental to how the logic programming language Prolog works. In the  context of Prolog it is called a _fact_ and represented in this way:

```prolog
predicate(subject, object).
```

This exact representation has also been used in Datalog, a subset of Prolog used as a query language for databases since the 1970s.

Similarly, the language [SPARQL](https://en.wikipedia.org/wiki/SPARQL) - used to query [RDF triplestores](https://en.wikipedia.org/wiki/Triplestore) - also uses Datalog-like triplets.

Datalog also reappears as part of the modern, append-only database [Datomic](https://www.infoq.com/articles/Datomic-Information-Model/) (and a series of copy-cats), which internally models the triplets as quintuplets - called datoms - in its revival of Datalog. This adds 2 additional dimensions to the data: transaction ids and addition/deletion state, allowing for advanced features such as time travel and immutability.

In summary, triplets...

* can be seen as a way to represent facts.
* can be related to other triplets.
* can be used to query a collection of triplets.
* can be extended to perform advanced computational tasks that are harder to do using a relational data model.

### RDF
While the basic triplet can be represented in most programming or query languages, any data interchange across the wire and data integration will need a more formal standard. RDF, a W3C standard, both restricts the allowed contents of the triplets and defines several serialisation formats for its more restricted data model (RDF/XML, Turtle, JSON-LD, and others).

It is important to note that, in principle, the RDF standard is simply an abstraction over an information model designed to represent a [knowledge graph](https://en.wikipedia.org/wiki/Knowledge_graph). The formats themselves are interchangeable and simply different ways to serialise the RDF information model, e.g. for sending over the wire or for long term storage.

The Princeton WordNet has long been published as RDF, as has [DanNet](https://cst.ku.dk/english/projekter/dannet/). However, in DanNet's case, the RDF/XML serialisation has thus far _primarily_ been used as a method of distribution to third parties, while internally the network has been modelled as tables within a traditional SQL database. This leaves the [relational model](https://en.wikipedia.org/wiki/Relational_model) as the primary data model, rather than the more specific data model represented by RDF. The two models are only connected in a limited fashion, requiring adapter code.

While using a more generic data model such as the relational model can be beneficial due to its reach, combining it with RDF is not very ergonomic because of the different perspectives of the two different data models. Modelling RDF using SQL must thus be done in a very careful manner in order to preserve the restrictions and simplicity of the RDF data model.

### Graphs
The natural implication of modelling data using triplets is to represent these triplets in a [graph](https://en.wikipedia.org/wiki/Graph_theory). In a basic graph, nodes are connected by edges. In directed graphs, the edges have directions similar to the directionality implied by WordNet triplets.

One of the most popular graph databases is [Neo4j](https://en.wikipedia.org/wiki/Neo4j). Its information model represents data as [labeled property graphs](https://en.wikipedia.org/wiki/Graph_database#Labeled-property_graph), allowing for edges and nodes to contain properties. While this does add some complexity to the data model, it isn't fundamentally incompatible with the RDF model or triplets as a whole, as it simply collapses the information contained in certain triples, preferring to represent them as properties instead. Neo4j offers a plugin called [Neosemantics](https://github.com/neo4j-labs/neosemantics) that adds RDF support.

There is also an entire world of RDF triplestores - e.g. Apache Jena - as well as more generic triplestores that also model graphs using `s-p-o` or `e-a-v` triples.

The main advantage of composing WordNet triplets as RDF graphs and storing these graphs inside a generic graph database is access to a world of generic graph traversal algorithms and visualisation tools. Storing the full dataset inside a graph database also makes querying and graph mutation operations fast, as the data is automatically partitioned and indexed to take advantage of this kind of functionality.

### Towards an optimal data representation
In summary:

* WordNets are just graphs.
* Graphs can be decomposed into triplets.
* RDF is a graph abstraction using triplets as its fundamental data structure.

An optimal data representation for DanNet would allow it to be both compatible with the RDF standard, decomposable into basic triplets, and represented as a graph in any context, i.e. at the application or at the database level. In addition, the data can always be serialised and loaded into another RDF-compatible application or be integrated with other RDF knowledge graphs, e.g. the Princeton WordNet or its spiritual successor: the [English WordNet](https://github.com/globalwordnet/english-wordnet).

Treating DanNet in this way does not couple it too closely to a specific brand of database or type of serialisation format. Instead, each of these levels of abstraction are concerned only with data modelling and are mutually compatible. The database representation is then just another view of the data.
