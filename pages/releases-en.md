# Releases
The newer DanNet releases use the release date as the version number, formatted as `YYYY-MM-DD`.

## **2023-09-28**: Fixing the domain topic relation
* The `wn:has_domain_topic` relation had been used in place of `wn:domain_topic` in the DanNet dataset. This has now been corrected.

## **2023-07-07**: Thousands of new links and schema updates
* DanNet now has around 10K new links to the [CILI](https://github.com/globalwordnet/cili) which is also linked with the OEWN and other WordNets.
* Some new relations (`dns:eqHypernym`, `dns:eqHyponym`, and `dns:eqSimilar`) have been added since `wn:ili` and `wn:eqSynonym` were not sufficient to cover the inter-WordNet links we now have.
* DanNet synsets now also have the raw DDO domain values from DSL that were present in the older versions of DanNet. These are represented with the `dc:subject` relation.
* Furthermore, the sex/gender data from the older versions of DanNet has also been included. It is available via the new `dns:gender` relation.
* To better facilitate graph navigation on the DanNet website, a new relation called `dns:linkedConcept` has been added to the DanNet schema. This relation is the inverse of `wn:ili` and is inferred in the large graph that can be queried on wordnet.dk/dannet.

## **2023-06-01**: ~5000 links to the Open English WordNet
* The schema translations have been updated.
* Around 5000 links have been added which link DanNet to the [Open English WordNet](https://github.com/globalwordnet/english-wordnet) or indirectly via the [CILI](https://github.com/globalwordnet/cili).
* The OEWN data set has received a companion data set containing generated labels for synsets, senses, and words.
* `dns:dslSense` and `dns:source` have been removed from the DanNet schema (`dns:source` has been replaced by `dc:source`)

## **2023-05-23**: DDS/COR improvements & links to DDO
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
