DanNet
======
[DanNet](https://cst.ku.dk/projekter/dannet/) is a [WordNet](https://en.wikipedia.org/wiki/WordNet) for the Danish language. The goal of this project is to represent DanNet in full using [RDF](https://www.w3.org/RDF/) as its native representation at both the database level, in the application space, and as its primary serialisation format.

The initial dataset has been [bootstrapped from the old DanNet 2.2 CSV files](src/main/dk/cst/dannet/bootstrap.clj). The old CSV export mirrors the SQL tables of the old DanNet database. This process will eventually be made obsolete once the next version of DanNet has been published.

Compatibility
-------------
Special care has been taken to maximise the compatibility of this iteration of DanNet. Like the DanNet of yore, the base dataset of this iteration is published as both RDF and CSV; the CSV file now simply reflects the triples of the RDF representation.

### Alternative and companion datasets
Apart from the base DaNet dataset, several **alternative datasets** exist that contain additional data.

This additional data can be implicitly inferred from the base dataset and its associated ontological metadata, but doing so can be both computationally expensive and mentally taxing for the consumer of the data. The alternative datasets provide a broader view of the data where implicit links to other ontologies have already been reified as triples within the dataset.

Published alongside DanNet are also **companion datasets** exist which link DanNet resources directly to other resources, e.g. the COR companion dataset that links DanNet resources to IDs from the COR project.

### Standards-based
The previous version of DanNet was modelled as tables inside a relational database. Two serialised representations also exist: [RDF/XML 1.0](https://www.w3.org/TR/2004/REC-rdf-syntax-grammar-20040210/) and a custom CSV format. The latter now serves as input for the new data model, remapping the relations described in these files onto a modern WordNet based on the [Ontolex-lemon](https://www.w3.org/2016/05/ontolex/) standard combined with the various [relations](https://globalwordnet.github.io/gwadoc/) defined by the Global Wordnet Association as used in the official [GWA RDF standard](https://globalwordnet.github.io/schemas/#rdf).

In Ontolex-lemon...

* Synsets are analogous to `ontolex:LexicalConcept`.
* Wordsenses are analogous to `ontolex:LexicalSense`.
* Words are analogous to `ontolex:LexicalEntry`.
* Forms are analogous to `ontolex:Form`.

![alt text](doc/ontolex.png "The Ontolex-lemon representation of a WordNet")

By choosing these standards, we maximise DanNet's ability to integrate with other lexical resources, in particular with other WordNets.

### Clojure support
In its native Clojure representation, DanNet can be queried in a variety of ways (described in [queries.md](doc/queries.md)). It is especially convenient to query data from within a Clojure REPL.

Support for Apache Jena transactions is built-in and enabled automatically when needed. This ensures support for persistence on disk through the [TDB](https://jena.apache.org/documentation/tdb/) layer included with Apache Jena (mandatory for [TDB 2](https://jena.apache.org/documentation/tdb2/)). Both in-memory and persisted graphs can thus be queried using the same function calls.

Furthermore, DanNet query results are all decorated with support for the Clojure `Navigable` protocol. The entire RDF graph can therefore easily be navigated in tools such as [REBL](https://docs.datomic.com/cloud/other-tools/REBL.html) or [Reveal](https://github.com/vlaaad/reveal) from a single query result. 

Significant changes
-------------------

### New schema, prefixes, URIs
DanNet uses a new schema, [available in this repository](resources/schemas/dannet-schema-2022.ttl) and eventually at http://www.wordnet.dk/dannet/2022/schema/. 

DanNet uses the following URI prefixes for the dataset instances, concepts (the range of `dns:ontologicalFacet` and `dns:ontologicalType`) and the schema itself:

* `dn` -> http://www.wordnet.dk/dannet/2022/instances/
* `dnc` -> http://www.wordnet.dk/dannet/2022/concepts/
* `dns` -> http://www.wordnet.dk/dannet/2022/schema/

These new prefixes/URIs take over from the ones used for DanNet 2.2:

* `dn` -> http://www.wordnet.dk/owl/instance/2009/03/instances/
* `dn_schema` -> http://www.wordnet.dk/owl/instance/2009/03/schema/

Eventually, all of these new URIs should resolve to schema files, which is to say that accessing a resource with a GET request (e.g. through a web browser) should always return data for the resource (or schema) in question.

Implementation
--------------
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

- `resources/dannet/csv`: contains the old DanNet CSV file export. These are used to bootstrap the initial dataset. Eventually, this bootstrap code will be made obsolete by the release of new the DanNet dataset.
- `resources/dannet/rdf`: contains the old DanNet RDF/RDFS/OWL file export. These are only used for the initial prototypes.

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

### Memory usage
The total memory usage of the resulting graph can be estimated using [clj-memory-meter](https://github.com/clojure-goes-fast/clj-memory-meter) which is available using the `:mm` Clojure CLI alias (defined in the `deps.edn` file).

> Currently, the default OWL-enabled in-memory graph _without_ any forward-chaining inference in cache takes up **~500 MB**. After querying the graph for the triple `[:dn/word-11007846 ?p ?o]` to force more  triples to materialize, the total memory usage jumps to **~520 MB**.

To be able to evaluate `(mm/measure graph)`, the JVM must be started with the following JVM option:

```
-Djdk.attach.allowAttachSelf
```

### Frontend dependencies
```shell
npm init -y
npm install react react-dom create-react-class
```

Querying DanNet
---------------
The easiest way to query DanNet currently is by compiling and running the Clojure code, then navigating to the `dk.cst.dannet.db` namespace in the Clojure REPL. From there, you can use a variety of query methods as described in [queries.md](doc/queries.md).

At the moment, there is no graphical user interface available for querying DanNet - that is still to come! One option might be setting up [Apache Jena Fuseki](https://jena.apache.org/documentation/fuseki2/), which is a web-based application for querying Apache Jena using SPARQL. This requires either setting up DanNet as a persistent [TDB database](https://jena.apache.org/documentation/tdb/index.html) or creating as Fuseki instance from a published DanNet dataset.
