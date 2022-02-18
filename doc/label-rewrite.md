Synset/sense label rewrite
==========================
> My comments on the format of the old DanNet labels based on my own induction. ~SG

The label actually contains a lot of identifying information tying DanNet
to ordnet.dk and its underlying database.

The basic ID format is as follows:

- ",N" denotes an entry ID while "_N" denotes a definition/subdefinition.
- word,3_2_1 means the 1st subdefinition of the 2nd definition of the 3rd
  entry for "word". The comma denotes that the first number is an entry ID.
- word_3_2 means the 2nd subdefinition of the 3rd definition for "word".
  There is no comma since the word does not have multiple entries.
- "word,1_9: word 'particle", refers to the MWE listed under the 1st
  definition of "word" at the 9th subdefinition.
- Some words have _0 as their definition ID. It seems likely that this is
  due to some words not having definitions at the time DanNet was created.

The multi-word expressions of a word are listed after the word's definitions.
The MWE IDs increment from the last definition ID, although in the ordnet.dk
user interface they each have their own set of definitions, each beginning at 1. and so on.
In the old DanNet labels they are not always consistent,
e.g. {skramme,1_1_1} refers to the specific subdefinition while
{bryde,1_16: bryde 'op} appears twice, referring to separate subdefinitions.

A single ' appears to mark the added words in a multi-word expression aside
from the root word, e.g. "bygge 'om". Sometimes two adjacent '' will appear.
This occurs because the ' is also used as a marker on ordnet.dk,
e.g. "holde ''fast" in the bootstrap data and "holde 'fast" on ordnet.
I am currently unsure what the meaning of the marker is. It has the CSS class
"diskret" however that just refers to the greyish styling. Sanni Nimb has
said that it is used to mark stress on the syllable, however that use appears
to be very inconsistent on ordnet.dk.

The new format will be as follows:

- A single _ is used to denote subscript and the full ID of the word.
- The § sign marks the section ID.
- An optional N before the § is used to denote an entry ID if available.
- 1a is used to denote the subdefinition previously defined by _1_1.
- In MWEs, the ID is moved to the relevant word in the MWE and the part
  before the : is removed entirely along with the :. 's are also removed.
  Thus {word,1_8_2: word 'particle} becomes {word_1§8b particle} and
  {word_1_5} becomes {word_§1e}
- Definition id _0 is removed entirely from the label.

This makes the connection to ordnet.dk a bit more apparent.
