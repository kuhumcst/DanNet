DanNet
======
[DanNet](https://cst.ku.dk/projekter/dannet/) is a [WordNet](https://en.wikipedia.org/wiki/WordNet) for the Danish language. The goal of this project is to represent DanNet in full using [RDF](https://www.w3.org/RDF/) as its native representation at both the database level, in the application space, and as its primary serialisation format.

Standards-based
---------------
The previous version of DanNet was modelled as tables inside a relational database. Two serialised representations also exist: [RDF/XML 1.0](https://www.w3.org/TR/2004/REC-rdf-syntax-grammar-20040210/) and a custom CSV format. The latter now serves as input for the new data model, remapping the relations described in these files onto a modern WordNet based on the [Ontolex-lemon](https://www.w3.org/2016/05/ontolex/) standard combined with the various [relations](https://globalwordnet.github.io/gwadoc/) defined by the Global Wordnet Association as used in the official [GWA RDF standard](https://globalwordnet.github.io/schemas/#rdf).

In Ontolex-lemon...

* Synsets are analogous to `ontolex:LexicalConcept`.
* Wordsenses are analogous to `ontolex:LexicalSense`.
* Words are analogous to `ontolex:LexicalEntry`.
* Forms are analogous to `ontolex:Form`.

![alt text](doc/ontolex.png "The Ontolex-lemon representation of a WordNet")

By choosing these standards, we maximise DanNet's ability to integrate with other lexical resources, in particular with other WordNets. 

Knowledge graphs
----------------
The main database that the new tooling has been developed for is [Apache Jena](https://jena.apache.org/), which is a mature RDF triplestore that also supports [OWL](https://www.w3.org/OWL/). When represented inside Jena, the many relations of DanNet are turned into a queryable [knowledge graph](https://en.wikipedia.org/wiki/Knowledge_graph). The new DanNet is developed in the Clojure programming language (an alternative to Java on the JVM) which has multiple libraries for interacting with the Java-based Apache Jena, e.g. [Aristotle](https://github.com/arachne-framework/aristotle) and [igraph-jena](https://github.com/ont-app/igraph-jena).

However, standardising on the basic RDF triple abstraction does open up a world of alternative data stores, query languages, and graph algorithms. See [rationale.md](doc/rationale.md) for more.

### Earlier prototypes
For this project we have created a couple of prototypes demonstrating DanNet's viability as a queryable RDF graph. These proof-of-concept knowledge graphs demonstrate alternative ways to query the data while using the old RDF/XML export as the source:

* **A Neo4j implementation**
  - Modelling DanNet as [RDF](https://en.wikipedia.org/wiki/Resource_Description_Framework) inside [Neo4j](https://neo4j.com/), while allowing for query results to be represented as [Ubergraph](https://github.com/Engelberg/ubergraph) data structures.
* **Two Apache Jena implementations**
  - Example code exists for loading DanNet and Princeton WordNet into an in-memory [Apache Jena](https://jena.apache.org/) triplestore.
    * ... using [aristotle](https://github.com/arachne-framework/aristotle)
    * ... using [igraph-jena](https://github.com/ont-app/igraph-jena)

Setup
-----
The code is all written in Clojure and it must be compiled to Java Bytecode and run inside a Java Virtual Machine (JVM). The primary means to do this is Clojure's [official CLI tools](https://clojure.org/guides/deps_and_cli) which can both fetch dependencies and build/run Clojure code. The project dependencies are specified in the [deps.edn file](deps.edn).

### Resource dependencies
This project assumes that the [ZIP-files containing DanNet 2.2](https://cst.ku.dk/english/projects/dannet/) have been downloaded in advance and extracted into a subdirectory called "resources":

- `resources/dannet/csv`: contains the CSV files.
- `resources/dannet/rdf`: contains the RDF, RDFS, OWL files.

In addition, for some of the prototype code, [version 2.0 of the Princeton WordNet](https://wordnet.princeton.edu/download/old-versions) is also needed:

- `resources/wordnet/rdf`: contains the Princeton WordNet files.

### JAXP00010001 error
When running both the Neo4j code or the updated Jena code in a more recent JVM, you will probably encounter this XML-related error message:

```
JAXP00010001: The parser has encountered more than "64000" entity expansions in this document; this is the limit imposed by the JDK.
```

To avoid this error, the JVM process should be run with the following JVM arg:

```
-Djdk.xml.entityExpansionLimit=0
```

Querying DanNet
---------------
Currently, there is no graphical user interface available for querying DanNet - that is still to come! The easiest way to query DanNet currently is by compiling and running the Clojure code, then navigating to the `dk.wordnet.dk` namespace in the Clojure REPL. From there, you can use a variety of query methods as described in [queries.md](doc/queries.md).

Roadmap
-------
_(subject to change)_

* [x] Remap the dataset to adhere to [lemon-based RDF](https://globalwordnet.github.io/schemas/).
* [ ] Export the full DanNet dataset as **CSV** ~~RDF/XML~~
  - ... with some help from [DSL](https://dsl.dk/).
* [ ] Fully represent the dataset within **Apache Jena** ~~a Neo4j database~~.
    - Work out kinks with [Neosemantics](https://github.com/neo4j-labs/neosemantics) and add lots of tests.
* [ ] Develop a GUI for viewing and editing RDF graph data
  - Generic reagent component for editing triple-based graphs ~~ubergraph data~~
    - Perhaps using https://github.com/wbkd/react-flow or a similar library
      * Need temporary conversion to/from JS objects
  - Specialised reagent components for DanNet-specific tasks
  - A custom SPA allowing...
    * Queries
    * Editing graphs locally and submitting changes
    * Specialised DanNet tasks, e.g. linking or extending the graph
    * Tracking of database history
    * Online access for authenticated users
