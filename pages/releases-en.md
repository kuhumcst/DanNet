# Releases
The newer DanNet releases use the release date as the version number, formatted as `YYYY-MM-DD`.

## **2025-07-03**: Improved validity of WN-LMF
* The WN-LMF export now passes validation as per the output of the `wn` command line program.
  * The issues found and the process of elimination has been documented in [Github issue #146](https://github.com/kuhumcst/DanNet/issues/146).
* Duplicate senses have been merged. Their synsets have been relabeled and relinked to COR.
* Duplicate forms have been removed.
* Synset self-references have been removed.
* The `wn:hypernym` relation for adjectives that go across part-of-speech boundaries have been replaced with the purpose-made `dns:crossPoSHypernym` which can be used to make something akin to hypernymic relations across different parts-of-speech.
  * NOTE: other cross-PoS hypernyms have *not* been modified for this release, though they have been excluded from the WN-LMF format for now as these relations are technically invalid when marked as `wn:hypernym`.
* Duplicate ILI links have been excluded from the WN-LMF format.
  * 1194 of the synsets in DanNet are linked to the same resources in the `CILI` which is not a valid use of the `wn:ili`  relation!
* Inferred `wn:hyponym` links are now *included* in the WN-LMF format.
* `dns:supersense` is now `wn:lexfile`, the equivalent relation in the GWA schema.
  * The English WordNet actually didn't use this relation itself, but will now do so in future releases (per our request).
* Various smaller manual synset exclusions in the WN-LMF format to satisfy validation requirements.
* The OEWN labels are now created based on the 2024 version of the Open English WordNet.
* Synset indegrees have been recalculated.

## **2024-08-09**: Supersenses + OEWN update
* Added 71055 Supersenses to DanNet, mostly based on a mapping devised for the SemDaX corpora.
  * Around 300 of these were "fixed" according to hypernym, differing from the mapping.
  * Another 3793 of these were created based on a new mapping of unmapped synsets.
* Added sentiment to 183 synsets taken from the old DanNet 2 connotation data.
* The existing links to the 2022 edition of the Open English WordNet were replaced with links to the 2023 edition (new synset IDs).
* The OEWN extension dataset has been updated such that it uses the new OEWN synset IDs.

## **2024-06-12**: WN-LMF as an alternative format
* WN-LMF has been added as an alternative format following a request on Github. The new file, `dannet-wn-lmf.xml.gz`, can even be used directly in software such as  [goodmami/wn](https://github.com/goodmami/wn) (see also:  [example on Github](https://github.com/kuhumcst/DanNet/blob/master/examples/wn_lmf_query.py)). Unfortunately, WN-LMF currently does not support the full set of data found in DanNet; for instance, our ontological types are not present in this format and the same applies to DanNet-specific relations such as `used for`.
* A total of 1906 bad source references to DDO have been removed from the dataset. These `dns:source`-relations had been created automatically based on IDs that exclusively exist within DanNet and for this reason they couldn't reference DDO.
* 88 Synset definitions have been fixed such that the split between titles and occupations is correct.
* Synset indegrees have been recalculated.

## **2024-04-30**: Improved CSV export + other small fixes
* The CSV export has been improved by...
  1. ... removing the presence of internal IDs in `synsets.csv` (referring to ontological types) replacing them instead with the concrete mix of ontological types.
  2. ... including lexical entries in `words.csv` which were previously erroneously excluded. 
* Some Lexical entries which were formerly of the generic `ontolex:LexicalEntry` type now have more specific types, e.g. `ontolex:Word`, `ontolex:MultiWordExpression`, or `ontolex:Affix`.
* Some of the parts-of-speech for the adjectives added in release `2023-05-11` were missing a PoS relation and/or mixed up two separate relation types; this has now been fixed.

## **2023-11-28**: Short labels
* `dns:shortLabel` variants of synset labels (derived from, amongst other things, word frequencies from [DDO](https://ordnet.dk/ddo)) have been added to the DanNet dataset.
* `dns:source` is now used once again to link to the original dictionary entry sources such as DDO. The usage of `dc:source` was both problematic wrt. its definition in the schema, as well the annoying fact that `dc` in some cases results in confusion when used as an RDF prefix as it may be hardcoded to a specific IRI.
* Some sense labels had lost their language (@da) by mistake and this has now been fixed.

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
* The OEWN dataset has received a companion dataset containing generated labels for synsets, senses, and words.
* `dns:dslSense` and `dns:source` have been removed from the DanNet schema (`dns:source` has been replaced by `dc:source`)

## **2023-05-23**: DDS/COR improvements & links to DDO
The following changes to our datasets will be available in the next version:

* Many DanNet words and senses have been linked to [DDO](https://ordnet.dk/ddo) via the new `dns:source` relation.
* Unofficial conjugations present in the COR companion dataset have been marked as such in their `rdfs:label`.
* Various other smaller tweaks to the COR dataset.
* The DDS dataset now uses 32-bit `float` as opposed to `double`, which results in a smaller RDF export as this data type doesn't require any special encoding in .ttl-files.

## **2023-05-11**: The new DanNet
There are too many changes in this initial release to list all of them in a succinct way:

* Around 5000 new senses have been added, mostly adjectives.
* Many dataset inconsistencies and other undesirable properties have been cleaned up.
* The entirety of DanNet has been converted to the Ontolex standard and uses the relations from the Global WordDet Association.
* DanNet is now RDF-native; RDF schemas are also available covering e.g. the ontological types.
* The DSL-derived DanNet IDs all resolve to actual RDF resources which can be viewed in a browser.
* Several companion datasets are available for download and are also merged with the data on wordnet.dk.
* Additional data points have also been inferred from the bootstrap data, e.g. inverse relations.
* The CSV download is now CSVW and includes metadata files describing contents of the columns.
* The DanNet data is now licensed as CC BY-SA 4.0 and the source code of the project is available under the MIT licence.
* ... and of course wordnet.dk/dannet is the new home of DanNet.

In addition to the work done on DanNet itself, we have also contributed to the Global WordNet Association's RDF schema.
