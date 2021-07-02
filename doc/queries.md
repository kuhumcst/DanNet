Queries
=======
DanNet can queried in a variety of ways. This plurality is intentional as explained in the [rationale](rationale.md). The directly supported query methods include:

* [SPARQL](#sparql): the official RDF query language
* [SPARQL Algebra](#sparql-algebra): the underlying algebraic expressions that SPARQL compiles to
* [Aristotle queries](#aristotle-queries): a Clojure DSL based on the SPARQL Algebra
* [Graph traversal](#graph-traversal): various algorithms for traversing directed graphs

Furthermore, by importing DanNet into another RDF-supporting database, the query language of this database may also be used to query DanNet, e.g. Neo4j's Cypher query language.

> Note: when querying Apache Jena's persisted TDB rather than an in-memory graph, you will need to wrap the querying code inside transactions. The `dk.wordnet.db.query` namespace contains functionality to help with this aspect of using Apache Jena. Transactions are _always_ required for TDB 2, while they are only required for TDB 1 if at least one transaction has _already_ occurred!

SPARQL
------
SPARQL is the official query language for querying RDF graphs. It is superficially similar to SQL, although queries take the form of dynamically joined sets of triples rather than the explicit table joins found in SQL.

### Relevant links
* [The W3C specification for SPAQRL](https://www.w3.org/TR/sparql11-query/)
* [Documentation on Jena's SPARQL processor](http://loopasam.github.io/jena-doc/documentation/query/index.html)

SPARQL Algebra
--------------
SPARQL queries are compiled to an algebraic form before being run. This is similar to how SQL queries are also compiled to some form of relational algebra.

Although the algebraic form is mapped directly to Java classes in Apache Jena, this algebraic representation is usually illustrated using s-expressions - i.e. Lisp syntax - in the various documents on it. This makes it a suitable target for a DSL in a Lisp such as Clojure. 

### Important terms
* `bgp` - i.e. **B**asic **G**raph **P**attern, an expression enclosing a set of triples.

### Relevant links
* [Convert SPARQL to SPARQL Algebra](http://sparql.org/query-validator.html)
* [W3C document on SPARQL Algebra](https://www.w3.org/2011/09/SparqlAlgebra/ARQalgebra)
* [Jena tutorial on SPARQL Algebra](https://jena.apache.org/documentation/query/manipulating_sparql_using_arq.html)

Aristotle queries
-----------------
While [Aristotle](https://github.com/arachne-framework/aristotle) accepts regular SPARQL queries, its primary query language is based on Jena's SPARQL Algebra and superficially resembles [Datomic-style Datalog](https://docs.datomic.com/on-prem/query/query.html).

### Changes compared to SPARQL Algebra
* Square brackets - i.e. Clojure vectors - are used rather than parentheses.
* Triples are inferred from vectors, e.g. `[?s ?p ?o]` is equivalent to `(triple ?s ?p ?o)` in the SPARQL Algebra.
  - Note: in Jena's implementation of SPARQL Algebra, square brackets already auto-expand into triples. See: https://jena.apache.org/documentation/query/service.html#algebra
    
### Relevant links
* [The query section of the Aristotle README](https://github.com/arachne-framework/aristotle#query)

### Relevant namespaces
The query language of Aristotle isn't well-specified, but its close relationship to the SPARQL Algebra can be gauged from the following namespaces:

* `arachne.aristotle.query.compiler`
* `arachne.aristotle.query.spec`

Graph traversal
---------------
Since RDF triples constitute a directed graph, this graph can be queried programmatically using graph traversal algorithms.

One way to do this is by invoking the [igraph](https://github.com/kuhumcst/DanNet/tree/feature/igraph) Clojure library which has [Apache Jena integration](https://github.com/ont-app/igraph-jena). An example traversal of the transitive closure of hyponyms can be found in the `dk.wordnet.prototypes.igraph` namespace.

> Note that currently some functions available in igraph don't work well with models that perform triple inferencing, e.g. OWL-enabled graphs. They are too slow (in the realm of several minutes) for any production application to use them.

### Relevant links
* [The traversal section of the igraph README](https://github.com/ont-app/igraph#Traversal)
