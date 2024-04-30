![DanNet logo](/resources/public/images/dannet-logo-colour.svg)

[DanNet](https://cst.ku.dk/projekter/dannet/) is a [WordNet](https://en.wikipedia.org/wiki/WordNet) for the Danish language. The goal of this project is to represent DanNet in full using [RDF](https://www.w3.org/RDF/) as its native representation at both the database level, in the application space, and as its primary serialisation format.

Compatibility
-------------
Special care has been taken to maximise the compatibility of this iteration of DanNet. Like the DanNet of yore, the base dataset is published as both RDF (Turtle) and CSV. RDF is the native representation and can be loaded as-is inside a suitable RDF graph database, e.g. Apache Jena. The CSV files are now published along with column metadata as [CSVW](https://csvw.org/).

### Companion datasets
Apart from the base DanNet dataset, several **companion datasets** exist expanding the graph with additional data. The companion datasets collectively provide a broader view of the data with both implicit and explicit links to other data:

* The **COR** companion dataset links DanNet resources to IDs from the COR project.
* The **DDS** companion dataset decorates DanNet resources with sentiment data.
* The **OEWN extension** companion dataset provides DanNet-like labels for the [Open English WordNet](https://en-word.net/) to better facilitate browsing the connections between the two datasets.

The current version of the datasets can be downloaded on wordnet.dk/dannet. All of the releases from 2023 and onwards are [available as releases on this project page](https://github.com/kuhumcst/DanNet/releases).

### Inferred data
Additional data is also implicitly inferred from the base dataset, the aforementioned companion datasets, and any associated ontological metadata. These inferred data points can be browsed along with the rest of the data on the [official DanNet website](https://wordnet.dk/dannet).

Inferring data so can be both computationally expensive and mentally taxing for the consumer of the data, so we do not always publish the fully inferred graph in a DanNet release; when we do, those release will be specifically marked as containing this extra data.

### Standards-based
The old DanNet was modelled as tables inside a relational database. Two serialised representations also exist: [RDF/XML 1.0](https://www.w3.org/TR/2004/REC-rdf-syntax-grammar-20040210/) and a custom CSV format. The latter served as input for the new data model, remapping the relations described in these files onto a modern WordNet based on the [Ontolex-lemon](https://www.w3.org/2016/05/ontolex/) standard combined with various [relations](https://globalwordnet.github.io/gwadoc/) defined by the Global Wordnet Association as used in the official [GWA RDF standard](https://globalwordnet.github.io/schemas/#rdf).

In Ontolex-lemon...

* Synsets are analogous to `ontolex:LexicalConcept`.
* Word senses are analogous to `ontolex:LexicalSense`.
* Words are analogous to `ontolex:LexicalEntry`.
* Forms are analogous to `ontolex:Form`.

![alt text](doc/ontolex.png "The Ontolex-lemon representation of a WordNet")

By building DanNet according to these standards we maximise its ability to integrate with other lexical resources, in particular with other WordNets.

Significant changes
-------------------

### New schema, prefixes, URIs
DanNet uses a new schema, [available in this repository](resources/schemas/internal/dannet-schema.ttl) and also at https://wordnet.dk/dannet/schema. 

DanNet uses the following URI prefixes for the dataset instances, concepts (members of a `dns:ontologicalType`) and the schema itself:

* `dn` -> [https://wordnet.dk/dannet/data/](https://wordnet.dk/dannet/data)
* `dnc` -> [https://wordnet.dk/dannet/concepts/](https://wordnet.dk/dannet/concepts)
* `dns` -> [https://wordnet.dk/dannet/schema/](https://wordnet.dk/dannet/schema)

> NOTE: these new prefixes/URIs take over from the ones used for DanNet 2.2 (the last version before the 2023 re-release):
> * `dn` -> http://www.wordnet.dk/owl/instance/2009/03/instances/
> * `dn_schema` -> http://www.wordnet.dk/owl/instance/2009/03/schema/

All the new URIs resolve to HTTP resources, which is to say that accessing a resource with a GET request (e.g. through a web browser) returns data for the resource (or schema) in question.

Finally, the new DanNet schema is written in accordance with the [RDF conventions](http://www-sop.inria.fr/acacia/personnel/phmartin/RDF/conventions.html#reversingRelations) listed by Philippe Martin.

Implementation
--------------
The main database that the new tooling has been developed for is [Apache Jena](https://jena.apache.org/), which is a mature RDF triplestore that also supports [OWL](https://www.w3.org/OWL/) inferences. When represented inside Jena, the many relations of DanNet are turned into a queryable [knowledge graph](https://en.wikipedia.org/wiki/Knowledge_graph). The new DanNet is developed in the Clojure programming language (an alternative to Java on the JVM) which has multiple libraries for interacting with the Java-based Apache Jena, e.g. [Aristotle](https://github.com/arachne-framework/aristotle) and [igraph-jena](https://github.com/ont-app/igraph-jena).

However, standardising on the basic RDF triple abstraction does open up a world of alternative data stores, query languages, and graph algorithms. See [rationale.md](pages/rationale-en.md) for more.

### Clojure support
In its native Clojure representation, DanNet can be queried in a variety of ways (described in [queries.md](pages/queries-en.md)). It is especially convenient to query data from within a Clojure REPL.

Support for Apache Jena transactions is built-in and enabled automatically when needed. This ensures support for persistence on disk through the [TDB](https://jena.apache.org/documentation/tdb/) layer included with Apache Jena (mandatory for [TDB 2](https://jena.apache.org/documentation/tdb2/)). Both in-memory and persisted graphs can thus be queried using the same function calls. The [DanNet website](https://wordnet.dk/dannet) contains the complete dataset inside a TDB 2 graph.

Furthermore, DanNet query results are all decorated with support for the Clojure `Navigable` protocol. The entire RDF graph can therefore easily be navigated in tools such as [Morse](https://github.com/nubank/morse) or [Reveal](https://github.com/vlaaad/reveal) from a single query result.

### Web app
> Note: A more detailed explanation is available at [doc/web.md](doc/web.md).

The frontend is written in [ClojureScript](https://clojurescript.org/). It is rendered using [Rum](https://github.com/tonsky/rum) and is served by [Pedestal](https://github.com/pedestal/pedestal) in the backend. If JavaScript is turned on, the initial HTML page becomes the entrypoint of a single-page app. If JavaScript is unavailable, this web app converts to a regular HTML website.

The URIs of each of the resources in DanNet resolve to actual HTML pages with content relating to the resource at the IRI. However, every DanNet resource has both an HTML representation and several _other_ representations which can be accessed via HTTP content negotiation.

When JavaScript is disabled, usually only the HTML representation is used by the browser. However, when JavaScript _is_ available, a frontend router ([reitit](https://github.com/metosin/reitit)) reroutes all navigation requests (e.g. clicking a hyperlink or submitting a form) towards fetching the `application/transit+json` representation instead. This data is used to refresh the Rum components, allowing them to update in place, while "fake" browser history item is inserted by reitit. The very same Rum components are also used to render the static HTML webpages.

Language negotiation is used to select the most suitable RDF data when multiple languages are available in the dataset.

Bootstrap
---------
### Initial bootstrap
The initial dataset was [bootstrapped from the old DanNet 2.2 CSV files](src/main/dk/cst/dannet/old/bootstrap.clj) (technically: a slightly more recent, unpublished version) as well as several other input sources, e.g. the list of new adjectives produced by CST and DSL. This old CSV export mirrors the SQL tables of the old DanNet database.

### Current releases
New releases of DanNet are now bootstrapped from the RDF export of the [immediately preceding release](https://github.com/kuhumcst/DanNet/releases).

In [dk.cst.dannet.db.bootstrap](src/main/dk/cst/dannet/db/bootstrap.clj) the raw data from the previous version of DanNet is loaded into memory, cleaned up, and converted into triple data structures using the new RDF schema structure. These triples are imported into several Apache Jena graphs and the planned release changes to the dataset written in Clojure code are applied to these graphs. The union of these graphs is accessed through an `InfGraph` which also triggers inference of additional triples as defined in the associated [OWL/RDFS schemas](/resources/schemas/).

Finally, on the final run of this bootstrap process, the graph is exported into an RDF dataset. This dataset constitutes the new official version of DanNet. A smaller CSV dataset is also created, but this is not the full or canonical version of the data.

> NOTE: the data used for bootstrapping should be located inside the `./boostrap` subdirectory (relative to the execution directory).

Setup
-----
The code is all written in Clojure and it must be compiled to Java Bytecode and run inside a Java Virtual Machine (JVM). The primary means to do this is Clojure's [official CLI tools](https://clojure.org/guides/deps_and_cli) which can both fetch dependencies and build/run Clojure code. The project dependencies are specified in the [deps.edn file](deps.edn).

While developing, I typically launch a new local DanNet web service using the `restart` function in [dk.cst.dannet.web.service](src/main/dk/cst/dannet/web/service.clj). This makes the service available at `localhost:3456`. The Apache Jena database will be spun up as part of this process.

The frontend must be run concurrently using [shadow-cljs](https://github.com/thheller/shadow-cljs) by running the following command in the terminal:

```
npx shadow-cljs watch app
```

### Testing a release build
While developing, ideally you should be running code in a Clojure REPL.

However, when testing release you can either run the docker compose setup from inside the `./docker` directory using the following command:

```shell
docker compose up --build
```

Usually, the Caddy container can keep running in between restarts, i.e. only the DanNet container should be rebuilt:

```shell
docker compose up -d dannet --build
```

> NOTE: requires that the Docker daemon is installed and running!

Or you may build and run a new release manually from this directory:

```shell
shadow-cljs --aliases :frontend release app
clojure -T:build org.corfield.build/uber :lib dk.cst/dannet :main dk.cst.dannet.web.service :uber-file "\"dannet.jar\""
java -jar -Xmx4g dannet.jar
```

> NOTE: requires that Java, Clojure, and shadow-cljs are all installed.

By default, the web service is accessed on `localhost:3456`. The data is loaded into a TDB2 database located in the `./db/tdb2` directory.

### Regular operation of wordnet.dk/dannet
The system is registered as a systemd service which ensures smooth running between restarts:

```shell
cp system/dannet.service /etc/systemd/system/dannet.service
systemctl enable dannet
systemctl start dannet
```

This service merely delegates to the Docker daemon and attempts to ensure that both the Caddy reverse proxy and DanNet web service are available when the host OS is updated.

However, when doing a new release (NOTE: requires updating the database and various files on disk), it might be beneficial to shut down _only_ the DanNet web service, not the Caddy reverse proxy, by using docker compose commands directly (see next section).

### Making a release on wordnet.dk/dannet
The current release workflow assumes that the database and the export files are created on a development machine and the transferred to the production server. During the transfer, the DanNet web service will momentarily be down, so keep this in mind!

To build the database, load a Clojure REPL and load the `dk.cst.dannet.web.service` namespace. From here, execute `(restart)` to get a service up and running. When the service is up, go to the `dk.cst.dannet.db` namespace and execute either of the following:

```clojure
;; A standard RDF & CSV export
(export-rdf! @dk.cst.dannet.web.resources/db)
(export-csv! @dk.cst.dannet.web.resources/db)

;; The entire, realised dataset including inferences can also be written do disk.
;; Note: exporting the complete dataset (including inferences) usually takes ~40-45 minutes
(export-rdf! @dk.cst.dannet.web.resources/db :complete true)
```

Normally, the Caddy service can keep running, so only the DanNet service needs to be briefly stopped:

```shell
# from inside the docker/ directory on the production server
docker compose stop dannet
```

Once the service is down, the database and export files can be transferred using SFTP to the relevant directories on the server. The git commit on the production server should also match the uploaded data, of course!

After transferring the entire, zipped database as e.g. `tdb2.zip`, you may unzip it at the final destination using this command, which will overwrite the existing files:

```shell
unzip -o tdb2.zip -d /dannet/db/
```

The service is finally restarted with:

```shell
docker compose up -d dannet --build
```

When updating the database, you will likely also need to update the exported files. These are zip files which reside in either `/dannet/export/csv` or `/dannet/export/rdf`. I typically just move them to the server using Cyberduck and the run

```
mv cor.zip dannet.zip dds.zip oewn-extension.zip /dannet/export/rdf/
mv dannet-csv.zip /dannet/export/csv/
```

### Memory usage
Currently, the entire system, including the web service, uses ~1.4 GB when idle and ~3GB when rebuilding the Apache Jena database. A server should therefore have perhaps 4GB of available RAM to run the full version of DanNet.

### Frontend dependencies
DanNet depends on React 17 since the React wrapper Rum depends on this version of React:

```shell
npm init -y
npm install react@17 react-dom@17 create-react-class@17
```

Querying DanNet
---------------
The easiest way to query DanNet currently is by compiling and running the Clojure code, then navigating to the `dk.cst.dannet.db` namespace in the Clojure REPL. From there, you can use a variety of query methods as described in [queries.md](pages/queries-en.md).

For simple lemma searches, you can of course visit the official instance at wordnet.dk/dannet.
