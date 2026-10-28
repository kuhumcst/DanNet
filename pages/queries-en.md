Queries
=======
DanNet can queried in a variety of ways. This plurality is intentional as explained in the [rationale](/dannet/page/rationale). The directly supported query methods include:

* [SPARQL](#sparql): the official RDF query language
* [Clojure](#clojure): SPARQL queries from Clojure, with the results as Clojure data
* [Graph traversal](#graph-traversal): various algorithms for traversing directed graphs

Furthermore, by importing DanNet into another RDF-supporting database, the query language of this database may also be used to query DanNet, e.g. Neo4j's Cypher query language.

> **NOTE:** when you query Apache Jena's persisted TDB2 database rather than an in-memory graph, every read and write must happen inside a transaction. The `q/run` function of `dk.cst.dannet.db.query` runs each query in a read transaction of its own. To group several calls in one transaction, use `txn/calculate` or `txn/calculate-read` from `dk.cst.theophrastus.txn`.

SPARQL
------
SPARQL is the official query language for querying RDF graphs. It is superficially similar to SQL, although queries take the form of dynamically joined sets of triples rather than the explicit table joins found in SQL.

### Relevant links
* [The W3C specification for SPAQRL](https://www.w3.org/TR/sparql11-query/)
* [Documentation on Jena's SPARQL processor](http://loopasam.github.io/jena-doc/documentation/query/index.html)

Clojure
-------
From Clojure, you query DanNet with SPARQL through [Theophrastus](https://github.com/kuhumcst/theophrastus), a Clojure library for Apache Jena that began as a fork of [Aristotle](https://github.com/arachne-framework/aristotle). Each query returns a vector of maps from variable to value, with IRIs as keywords such as `:dn/synset-3047`. You pass values as bindings rather than as SPARQL text:

```clojure
(require '[dk.cst.dannet.db.query :as q])

(q/run graph
       "SELECT ?label WHERE { ?synset rdfs:label ?label }"
       {'?synset :dn/synset-3047})
```

The query needs no PREFIX lines for the prefixes that DanNet uses.

### Relevant links
* [The Theophrastus README](https://github.com/kuhumcst/theophrastus#readme)

Graph traversal
---------------
Since RDF triples constitute a directed graph, this graph can be queried programmatically using graph traversal algorithms.

One way to do this is by invoking the [igraph](https://github.com/kuhumcst/DanNet/tree/feature/igraph) Clojure library which has [Apache Jena integration](https://github.com/ont-app/igraph-jena).

> Note that currently some functions available in igraph don't work well with models that perform triple inferencing, e.g. OWL-enabled graphs. They are too slow (in the realm of several minutes) for any production application to use them.

### Relevant links
* [The traversal section of the igraph README](https://github.com/ont-app/igraph#Traversal)
