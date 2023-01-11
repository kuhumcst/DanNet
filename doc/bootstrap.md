Bootstrapping the new DanNet
============================
The [dk.cst.dannet.bootstrap](/src/main/dk/cst/dannet/bootstrap.clj) namespace contains most of the code used to bootstrap this new version of DanNet. Once the final version of this new dataset has been published, most of this code will essentially be irrelevant, as the new dataset becomes the canonical one.

The main job of the bootstrap code is to convert the old version of DanNet from its relational table origins (represented in the CSV export) to a graph-native RDF representation, normalising all database rows as triples. This new RDF data model standardises DanNet to use Ontolex and the Global WordNet Schema as its primary ontological sources. However, we also extend these common WordNet ontologies with [our own schema](/resources/schemas/internal), such that every facet of DanNet is optimally represented in the most compatible way. Only a few experimental (and mostly unused) relations have not been passed down in this new version of DanNet.

Two-step bootstrapping
----------------------
The bootstrap process itself is divided into two steps:

1. Generation of triples based CSV input files.
2. Modification of (and based on) the partial state of the RDF graph.

The reason for having two steps has to do with the fact that we are merging datasets from a variety of sources. In order to be able to generate some of the triples that we desire, we will at times need to query the partial state of the RDF graph. The majority of the triples are nevertheless created in the first step, but a lot of clean-up and interlinking happens as part of the second step.
