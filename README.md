DanNet
======
[DanNet](https://cst.ku.dk/projekter/dannet/) is a [WordNet](https://en.wikipedia.org/wiki/WordNet) for the Danish language. Currently, DanNet is modelled as tables in a relational database along with two serialised representations: [RDF/XML 1.0](https://www.w3.org/TR/2004/REC-rdf-syntax-grammar-20040210/) and a custom CSV format. This project is an attempt at representing DanNet fully as RDF, including its database representation, as well as allowing for representing any part of the network as an RDF-compatible graph in the application space.

Currently, this means modelling DanNet as [RDF](https://en.wikipedia.org/wiki/Resource_Description_Framework) inside [Neo4j](https://neo4j.com/), while allowing for query results to be represented as [Ubergraph](https://github.com/Engelberg/ubergraph) data structures. Example code also exists for loading DanNet and Princeton WordNet into an in-memory [Apache Jena](https://jena.apache.org/) triplestore.

When subgraphs of the knowledge graph in the database can be represented as graph data structures in the application space, these subgraphs can be easily manipulated at both the database level and by conventional graph algorithms at the application level. See [rationale.md](rationale.md) for more.

Prototypes
----------
Currently, this project contains multiple prototypes of a new DanNet RDF database, albeit using the old RDF export:

* A Neo4j implementation
* Two Apache Jena implementations
  - ... using [aristotle](https://github.com/arachne-framework/aristotle)
  - ... using [igraph-jena](https://github.com/ont-app/igraph-jena)

Setup
-----
The code is all written in Clojure and it must be compiled to Java Bytecode and run inside a Java Virtual Machine (JVM). The primary means to do this is Clojure's [official CLI tools](https://clojure.org/guides/deps_and_cli) which can both fetch dependencies and build/run Clojure code.

### JAXP00010001 error
When running both the Neo4j code or the updated Jena code in a more recent JVM, you will probably encounter this XML-related error message:

```
JAXP00010001: The parser has encountered more than "64000" entity expansions in this document; this is the limit imposed by the JDK.
```

To avoid this error, the JVM process should be run with the following JVM arg:

```
-Djdk.xml.entityExpansionLimit=0
```

Roadmap
-------
_(subject to change)_

* Export the full DanNet dataset as RDF/XML
  - ... with some help from [DSL](https://dsl.dk/).
* Convert the dataset to [RDF 1.1](https://www.w3.org/TR/rdf11-concepts/)
  - Possibly using: https://github.com/jmccrae/gwn-scala-api
* Remap the dataset to adhere to [lemon-based RDF](https://globalwordnet.github.io/schemas/)
* Fully represent the dataset within a Neo4j database.
    - Work out kinks with [Neosemantics](https://github.com/neo4j-labs/neosemantics) and add lots of tests.
* Conversion between various compatible graph formats
    - Neo4j graphs
    - Ubergraph
      * The primary data representation in the application space
      * Other representations convert to/from this format
    - Plain triplets?
      * Datalog
      * RDF (export)
* API for updating Neo4j with an Ubergraph graph
  - ... relies on the conversion code
* Develop a GUI for viewing and editing RDF graph data
  - Generic reagent component for editing ubergraph data
    - Perhaps using https://github.com/wbkd/react-flow or a similar library
      * Need temporary conversion to/from JS objects
  - Specialised reagent components for DanNet-specific tasks
  - A custom SPA allowing...
    * Plain Cypher queries
    * Editing graphs locally and submitting changes
    * Specialised DanNet tasks, e.g. linking or extending the graph
    * Tracking of database history
    * Online access for authenticated users
