# Releases
The newer DanNet releases use the release date as the version number, formatted as `YYYY-MM-DD`.

## SNAPSHOT
The following changes to our data sets will be available in the next version:

* Many DanNet words and senses have been linked to [DDO](https://ordnet.dk/ddo) via the new `dns:source` relation.
* Unofficial conjugations present in the COR companion data set have been marked as such in their `rdfs:label`.
* Various other smaller tweaks to the COR dataset.
* The DDS data set now uses 32-bit `float` as opposed to `double`, which results in a smaller RDF export as this data type doesn't require any special encoding in .ttl-files.

## **2023-05-11**: The new DanNet
There are too many changes in this initial release to list all of them in a succinct way:

* Around 5000 new senses have been added, mostly adjectives.
* Many data set inconsistencies and other undesirable properties have been cleaned up.
* The entirety of DanNet has been converted to the Ontolex standard and uses the relations from the Global WordDet Association.
* DanNet is now RDF-native; RDF schemas are also available covering e.g. the ontological types.
* The DSL-derived DanNet IDs all resolve to actual RDF resources which can be viewed in a browser.
* Several companion data sets are available for download and are also merged with the data on wordnet.dk.
* Additional data points have also been inferred from the bootstrap data, e.g. inverse relations.
* The CSV download is now CSVW and includes metadata files describing contents of the columns.
* The DanNet data is now licensed as CC BY-SA 4.0 and the source code of the project is available under the MIT licence.
* ... and of course wordnet.dk/dannet is the new home of DanNet.

In addition to the work done on DanNet itself, we have also contributed to the Global WordNet Association's RDF schema.
