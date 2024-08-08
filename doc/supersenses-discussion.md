# Tagging ELEXIS-WSD 1.1 with supersenses
This document concerns itself with the process (and associated challenges of) adding supersenses to the Danish part of the [Parallel sense-annotated corpus ELEXIS-WSD 1.1](https://www.clarin.si/repository/xmlui/handle/11356/1842). As a part of this process supersenses were also added to the DanNet dataset (a total of 71055 synsets affected).

Many of the Danish tokens were annotated with sense IDs from the DanNet dataset, meaning that semantic information derived from DanNet could be used in part to further annotate these words; specifically in this case: the synsets/senses in DanNet are annotated with EuroWordNet ontological types.

> **RELEVANT ISSUES**: #138, #141, #144

## From ontological type to supersense
Bolette et al had already produced a partial mapping from EuroWordNet ontological types to a (slightly expanded) set of supersenses. This mapping was partial since it didn't map ontological types to every possible part-of-speech. Supersenses are discrete and partitioned according to part-of-speech. Nevertheless, many supersenses could be assigned based on this direct mapping.

### Using hypernyms to improve supersense tagging
One issue we rant into was the orthogonal nature of the two types of categorisations. Ontological types are precise and numerous where multiple semantic "tags" make up a composite type. Supersenses are broad categories with no clear demarcation, so words sometimes have no "obvious" home.

One case where this issue manifested had to with the set of synsets tagged with the supersense `verb.creation` which ended up containing many senses that were clearly `verb.consumption` or another supersense. We ended up reassigning supersenses for all words tagged as `verb.creation` (312 cases) in a semi-automatic way based on assigning supersenses to a closed set of ancestor hypernyms.

### Tagging remaining synsets
A full list of the remaining untagged synsets was produced programmatically and subsequently partitioned according to a combination of ontological type and part-of-speech tag (94 combinations in total). This list was then put in a spreadsheet and each of the 94 combinations manually annotated with supersenses to allow for supersense-tagging any synset with that particular combination of ontological type and PoS. Upon adding this data to the DanNet dataset, every synset was now finally tagged with a supersense.

## Producing an updated ELEXIS-WSD 1.1 [CoNLL-U](https://universaldependencies.org/format.html) file
Once the entirety of DanNet had been annotated with supersenses, this data could be used to tag every token with a DanNet sense ID in the ELEXIS dataset (3939 unique senses in total spread over 11085 instances in the dataset).

Unfortunately, the remaining tokens that should be tagged with supersenses still amounts to a total of 3141 unique IDs. These IDs are _not_ linked to DanNet in any way, so they will either have to be manually annotated with supersenses (a significant task) or some other way of (semi-)automatically assigning supersenses will have to be devised for them.
