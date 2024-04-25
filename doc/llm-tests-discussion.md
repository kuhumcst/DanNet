# Feelings
## Metaphorical/figurative
Several of the synsets classified as feeling hyponyms in DanNet are entirely figurative/metaphorical uses of other words, e.g. "tidevandsbølge" ("tidal wave"), "flod" ("river") or "stød" ("electric shock"). These metaphors would likely not be recognised as feelings by a human observer, however removing them automatically is a difficult task as they are not clearly differentiated in DanNet from other feelings.

## Different parts-of-speech
Some hyponyms of feeling in DanNet are nouns while a great deal are classified as adjectives. Without limiting to a specific word class, it makes generating gramatically correct sentences quite hard.

> COMMENT: another consideration is whether it even ideal to have a hyponymy tree where different parts-of-speech appear...? it seems a bit unstructured to me).

# Comestible liquids
## Inconsistent applications of ontological types
This section of the dataset has been queried via DanNet's concept of ontological types. Unfortunately, many drinkable liquids have not been marked as "Comestible", e.g. various types of wine or "soja" ("soy sauce").

## Polysemy and ontological types
Some words, like "juice", have both a `Liquid` form as well as an object form (juice = juicekarton) which can cause when generating sentences that assume the wrong ontological type.

# Point in time vs. period of time
Time words are a messy subject, including in DanNet, and the differences between the two senses is not necessarily as hard as the DanNet definitions make out.

# Sports
Sports is unfortunately not a clearly defined thing in DanNet, e.g. certain types of (e.g. "kampsport") do not appear in the hyponomy tree of "sport".

...

# Other random thoughts
## Problems with slang/uncommon words
'The synsets do not have a prototypical word, so each lemma must be considered when generating examples. This results in uncommon or slang words appearing with equal frequency, unless special measures are taken to remove these before generating the sentences, e.g. "en krop kan have en forlygte" ("a body can have a headlight") which comes about as a result of "forlygte" ("headlight"), a slang term for "bryst" ("breast"), being co-located in the same synset. Removing slang terms entirely and very infrequent words somewhat reduces this issue.

## Differences from common sense
Another issue has to do with the ontological reality of the DanNet dataset viz-a-viz the common expectations of the human reader. For example, a human would expect parts of a body to be either human body parts or -- in the case of animal body parts -- a contextually consistent set of animal body parts. However, as an example the word "dyrefilet" (a "fillet" from an animal) is also registered as part of an animal. This does make sense in a certain context (gastronomy), but it is out of place in a more mixed context. For this reason, special care has to be taken to remove certain classes of part-whole relationships (e.g. comestible parts of non-comestible wholes), but there is no exhaustive way to ensure that every generated sentence is sound.

## Strong vs. weak statements
Furthermore, any has-a relationship must be qualified ("can ...") as we generally cannot state in the absolute that "X has Y".

## Confusing class and instance
Another weakness found while generating part-whole relationships has to do with specific instances of the part vs. the part as a generic word. For example, "et sprog kan have et bandeord" ("a language can have a swear word") is a perfectly acceptable sentence while "et sprog kan have pokker" ("a language can have pokker", with "pokker" being an instance of a swear word) is quite strange-sounding.
