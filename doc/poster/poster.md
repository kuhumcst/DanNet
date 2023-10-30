# The new DanNet
DanNet, the Danish WordNet, is a semantical language resource which links the many senses of the Danish language in various ways. DanNet is a collaborative effort of Det Danske Sprog- og Litteraturselskab and the Center for Language Technology at the University of Copenhagen.

In 2023, an entirely new version of DanNet was released which contains major changes to the language resource, along with an entirely new presence on wordnet.dk/dannet.

## Web presence
- DanNet is now completely standards-based and RDF-native. Our data uses the Ontolex standard with WordNet relations from the Global WordDet Association.
- In addition to the work done on DanNet itself, we have also contributed to the Global WordNet Association's RDF schema.
- There is a new bilingual home on wordnet.dk/dannet, where the RDF graph can be browsed along with several linked datasets.
- The DanNet IDs all dereference as actual RDF resources which can be accessed in a browser in accordance with the best practices for linked data.

## More data included
- ~5000 new senses, mostly adjectives.
- ~5000 new links between DanNet and the Open English WordNet (OEWN), or indirectly via the Collaborative Interlingual Index (CILI).
- Most words and senses have been linked to "Den Danske Ordbog" (DDO) via our new dns:source relation and many word frequencies from DDO are also included.
- Dataset inconsistencies and other undesirable properties have been cleaned up.

## Dataset changes
- Several companion RDF datasets are available for download on wordnet.dk/dannet, e.g. Det Centrale Ordregister (COR), Det Danske Sentimentleksikon (DDS), and DanNet-style labels for the Open English WordNet (OEWN).
- The CSV download is now CSVW and includes metadata files describing contents of the columns.
- The dataset has been relicensed as CC BY-SA 4.0 and the source code of the project is available under the MIT licence.

----

## Continued relevance in an age of LLMs
...
