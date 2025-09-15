![DanNet logo](/resources/public/images/dannet-logo-colour.svg)

[DanNet](https://cst.ku.dk/projekter/dannet/) is a [WordNet](https://en.wikipedia.org/wiki/WordNet) for the Danish language. The goal of this project is to represent DanNet in full using [RDF](https://www.w3.org/RDF/) as its native representation at both the database level, in the application space, and as its primary serialisation format.

- The full version of DanNet can always be browsed at [wordnet.dk](https://wordnet.dk); the official DanNet web app. The current release of the datasets can be found there too.
- In addition, the current datasets along with all releases since 2023 are available on the [releases page of this repository](https://github.com/kuhumcst/DanNet/releases).

Compatibility
-------------
Special care has been taken to maximise the compatibility of this iteration of DanNet, including multiple variants of the core DanNet dataset:

- RDF (Turtle) is the native representation of DanNet and can be loaded as-is inside a suitable RDF graph database, e.g. Apache Jena, and queried using [SPARQL](https://en.wikipedia.org/wiki/SPARQL).
- The CSV variant is published together with column metadata as [CSVW](https://csvw.org/).
- There is also a [WN-LMF](https://globalwordnet.github.io/schemas/#xml) variant which can be loaded and queried e.g. using Python libraries such as the NLTK-derived [wn](https://github.com/goodmami/wn):

```python
import wn

wn.add("dannet-wn-lmf.xml.gz")

for synset in wn.synsets('kage'):
    print((synset.lexfile() or "?") + ": " + (synset.definition() or "?"))
```

### Differences between dataset variants
While every dataset variant includes every synset/sense/word, the CSV and WN-LMF variants do *not* include every single data point. In the case of CSV, this is a question of reduced ergonomics when converting from an open graph to fixed tables, while in the case of WN-LMF only the official GWA relations are allowed as per the standard (i.e. the proprietary DanNet relations described in the [DanNet schema](/resources/schemas/internal/dannet-schema.ttl) are not included).

To access the full and comprehensive version of DanNet, you can either load the RDF dataset in an RDF database as a graph _or_ access this graph via your browser at [wordnet.dk](https://wordnet.dk).

### Companion datasets
Apart from the base DanNet dataset, several **companion datasets** exist for the RDF variant expanding the graph with additional data. These companion datasets collectively provide a broader view of the data with both implicit and explicit links to other data:

* The **COR** companion dataset links DanNet resources to IDs from the COR project.
* The **DDS** companion dataset decorates DanNet resources with sentiment data.
* The **OEWN extension** companion dataset provides DanNet-like labels for the [Open English WordNet](https://en-word.net/) to better facilitate browsing the connections between the two datasets.

### Standards-based
DanNet is based on the [Ontolex-lemon](https://www.w3.org/2016/05/ontolex/) standard combined with various [relations](https://globalwordnet.github.io/gwadoc/) defined by the Global Wordnet Association as used in the official [GWA RDF standard](https://globalwordnet.github.io/schemas/#rdf).

In Ontolex-lemon...

* Synsets are represented by `ontolex:LexicalConcept`.
* Word senses are represented by `ontolex:LexicalSense`.
* Words are represented by`ontolex:LexicalEntry`.
* Forms are represented by `ontolex:Form`.

![alt text](doc/ontolex.png "The Ontolex-lemon representation of a WordNet")

DanNet also has a few proprietary relations which defined in the official [DanNet schema](/resources/schemas/internal/dannet-schema.ttl) in an Ontolex-compatible way. There is also a schema for [EuroWordNet concepts](/resources/schemas/internal/dannet-concepts.ttl). Just like the DanNet RDF dataset itself, the DanNet schemas are also written in Turtle. The new DanNet schema is also written in accordance with the [RDF conventions](http://www-sop.inria.fr/acacia/personnel/phmartin/RDF/conventions.html#reversingRelations) listed by Philippe Martin.

DanNet uses the following URI prefixes for the dataset instances, concepts (members of a `dns:ontologicalType`) and the schema itself:

* `dn` -> [https://wordnet.dk/dannet/data/](https://wordnet.dk/dannet/data)
* `dnc` -> [https://wordnet.dk/dannet/concepts/](https://wordnet.dk/dannet/concepts)
* `dns` -> [https://wordnet.dk/dannet/schema/](https://wordnet.dk/dannet/schema)

All DanNet URIs resolve to HTTP resources, which is to say that accessing a resource with a GET request (e.g. through a web browser) returns data for the resource (or schema) in question.

By building DanNet according to these standards we maximise its ability to integrate with other lexical resources, in particular with other WordNets.

### Inferred data
Additional data is also implicitly inferred from the base dataset, the aforementioned companion datasets, and any associated ontological metadata. These inferred data points can be browsed along with the rest of the data on the [official DanNet website](https://wordnet.dk).

Inferring data so can be both computationally expensive and mentally taxing for the consumer of the data, so we do not always publish the fully inferred graph in a DanNet release; when we do, those release will be specifically marked as containing this extra data.

Implementation
--------------
The main database that the new tooling has been developed for is [Apache Jena](https://jena.apache.org/), which is a mature RDF triplestore that also supports [OWL](https://www.w3.org/OWL/) inferences. When represented inside Jena, the many relations of DanNet are turned into a queryable [knowledge graph](https://en.wikipedia.org/wiki/Knowledge_graph). The new DanNet is developed in the Clojure programming language (an alternative to Java on the JVM) which has multiple libraries for interacting with the Java-based Apache Jena, e.g. [Aristotle](https://github.com/arachne-framework/aristotle) and [igraph-jena](https://github.com/ont-app/igraph-jena).

However, standardising on the basic RDF triple abstraction does open up a world of alternative data stores, query languages, and graph algorithms. See [rationale.md](pages/rationale-en.md) for more.

### Clojure support
In its native Clojure representation, DanNet can be queried in a variety of ways (described in [queries.md](pages/queries-en.md)). It is especially convenient to query data from within a Clojure REPL.

Support for Apache Jena transactions is built-in and enabled automatically when needed. This ensures support for persistence on disk through the [TDB](https://jena.apache.org/documentation/tdb/) layer included with Apache Jena (mandatory for [TDB 2](https://jena.apache.org/documentation/tdb2/)). Both in-memory and persisted graphs can thus be queried using the same function calls. The [DanNet website](https://wordnet.dk) contains the complete dataset inside a TDB 2 graph.

### Web app
> Note: A more detailed explanation is available at [doc/web.md](doc/web.md).

The frontend is written in [ClojureScript](https://clojurescript.org/). It is rendered using [Rum](https://github.com/tonsky/rum) and is served by [Pedestal](https://github.com/pedestal/pedestal) in the backend. If JavaScript is turned on, the initial HTML page becomes the entrypoint of a single-page app. If JavaScript is unavailable, this web app converts to a regular HTML website.

The URIs of each of the resources in DanNet resolve to actual HTML pages with content relating to the resource at the IRI. However, every DanNet resource has both an HTML representation and several _other_ representations which can be accessed via HTTP content negotiation.

When JavaScript is disabled, usually only the HTML representation is used by the browser. However, when JavaScript _is_ available, a frontend router ([reitit](https://github.com/metosin/reitit)) reroutes all navigation requests (e.g. clicking a hyperlink or submitting a form) towards fetching the `application/transit+json` representation instead. This data is used to refresh the Rum components, allowing them to update in place, while "fake" browser history item is inserted by reitit. The very same Rum components are also used to render the static HTML webpages.

Language negotiation is used to select the most suitable RDF data when multiple languages are available in the dataset.

Bootstrap
---------
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

### Regular operation of wordnet.dk
The system is registered as a systemd service which ensures smooth running between restarts:

```shell
cp system/dannet.service /etc/systemd/system/dannet.service
systemctl enable dannet
systemctl start dannet
```

This service merely delegates to the Docker daemon and attempts to ensure that both the Caddy reverse proxy and DanNet web service are available when the host OS is updated.

However, when doing a new release (NOTE: requires updating the database and various files on disk), it might be beneficial to shut down _only_ the DanNet web service, not the Caddy reverse proxy, by using docker compose commands directly (see next section).

### Making a release on wordnet.dk
The current release workflow assumes that the database and the export files are created on a development machine and the transferred to the production server. During the transfer, the DanNet web service will momentarily be down, so keep this in mind!

To build the database, load a Clojure REPL and load the `dk.cst.dannet.web.service` namespace. From here, execute `(restart)` to get a service up and running. When the service is up, go to the `dk.cst.dannet.db` namespace and execute either of the following:

```clojure
;; A standard RDF, CSV & WN-LMF export
(export-rdf! @dk.cst.dannet.web.resources/db)
(export-csv! @dk.cst.dannet.web.resources/db)
(export-wn-lmf! "export/wn-lmf/")

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

```shell
mv cor.zip dannet.zip dds.zip oewn-extension.zip /dannet/export/rdf/
mv dannet-csv.zip /dannet/export/csv/
mv dannet-wn-lmf.xml.gz /dannet/export/wn-lmf/
```

### Memory usage
Currently, the entire system, including the web service, uses ~1.4 GB when idle and ~3GB when rebuilding the Apache Jena database. A server should therefore have perhaps 4GB of available RAM to run the full version of DanNet.

### Frontend dependencies
DanNet depends on React 17 since the React wrapper Rum depends on this version of React:

```shell
npm init -y
npm install react@17 react-dom@17 create-react-class@17
```

Validating DanNet
-----------------
> NOTE: this only validates the WN-LMF file, not the core RDF dataset!

The structure of DanNet can currently be validated by using the validator in the `wn` Python package:

```shell
# install wn in a virtulenv
python3 -m venv examples/venv
source examples/venv/bin/activate
python3 -m pip install wn

# validate the current state using the CLI
python -m wn validate --output-file examples/wn-lmf-validation.json export/wn-lmf/dannet-wn-lmf.xml
```

This will create a map of validation errors with lists of the offending entities.

> NOTE: subsequent runs must also first execute `source examples/venv/bin/activate` in the terminal window before validation can commence.

Clojure-mcp
-----------
I've added experimental support for [clojure-mcp](https://github.com/bhauman/clojure-mcp) through Claude, which is an MCP server for AI-assisted Clojure development. The project hasn't been developed with AI at all, but future changes may be AI-assisted.

See my personal [mcp-stuff repo](https://github.com/simongray/mcp-stuff) for documentation. The current versions of `LLM_CODE_STYLE.md` should also be located in that repo as well as my most recent personal `config.edn` for clojure-mcp projects.

### REPL
When integrating with clojure-mcp, an external nREPL on localhost:7888 should be used:

```shell
clojure -M:nrepl
```

You can then use e.g. IntelliJ IDEA to connect to this REPL and share it with the LLM.
