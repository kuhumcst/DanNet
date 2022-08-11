Label synthesis
===============
> My comments on different cases of label synthesis during the bootstrap. ~SG
> See also: [label-rewrite.md](/doc/label-rewrite.md).

The old DanNet data was mostly composed of data from DSL's database, however,
in some cases, senses were inserted that didn't exist in DSL's data at the time.
These senses all refer to the same dummy word, "TOP", rather than a real word.
In the new DanNet, the references to this dummy word have been removed.

However, the senses _have_ actually been labeled inside the synset labels.
Every "Inserted by DanNet" sense is prefixed with "DN:" inside the synset label.
The issue then becomes extracting these labels properly and generating new words
as well as sense labels for them.

In this new dataset, the prefixes are removed, senses are labeled based on the
synset labels, and new words are synthesized from the senses, using the sense ID
as a template, e.g. `dn/sense-24000051` results in `:dn/word-s24000051` where
the "s" is used to mark the synthesized property.
