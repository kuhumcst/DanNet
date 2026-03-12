# SPARQL guide

This is a hands-on introduction for people with no prior knowledge of RDF or SPARQL.
By reading this guide you will learn how to use [SPARQL](https://www.w3.org/TR/sparql11-query/) queries to fetch data from the DanNet database.

> **NOTE:** all examples in the guide run against the [DanNet SPARQL endpoint](/dannet/sparql). Keep in mind that this public version of DanNet has limits in place for both the size of the result sets _and_ the query run time. We also limit the number of concurrent requests that require inferencing to complete.

## 1. What is RDF?

RDF (Resource Description Framework) is a way of representing knowledge as a graph. The DanNet database is an RDF graph. Where relational databases (e.g. using SQL) store data in rows and columns, RDF stores data as **triples**, i.e. statements of the form:

```
subject  predicate  object .
```

Each triple is a single fact. A subject is connected to an object via a predicate (also called a *property*). That's it. Every piece of information in the database is expressed this way.

> **NOTE:** if you want to know why DanNet stores its data as triples, you can check out the original [rationale](/dannet/page/rationale).

Here's a real triple from DanNet, written in the **Turtle** syntax (the most common human-readable RDF format):

```turtle
dn:synset-52  skos:definition  "sødt bagværk der især serveres til kaffe el. te el…"@da .
```

This says: *"the synset `synset-52` has the definition 'sødt bagværk der især serveres til kaffe…' in Danish."*

A few things to notice:

- `dn:synset-52` is a **URI**, a globally unique identifier for this concept. The `dn:` part is a *prefix*, short for `https://wordnet.dk/dannet/data/`. So the full URI is `https://wordnet.dk/dannet/data/synset-52`. You can actually [visit that page](/dannet/data/synset-52) in your browser and see everything about it.
- `skos:definition` is also a URI (from the [SKOS vocabulary](https://www.w3.org/TR/skos-reference/)). It's the predicate.
- `"…"@da` is a **literal**, i.e. a plain text value. The `@da` tag marks it as Danish.
- The triple ends with a **period**.

An RDF dataset is just a huge collection of these triples. DanNet contains around ~70K Danish synsets covering ~62K words, all described as triples.

### Turtle shorthand

Turtle lets you abbreviate. When multiple [triples](https://en.wikibooks.org/wiki/SPARQL/Triples) share the same subject, you can use a **semicolon** to continue with the next predicate:

```turtle
dn:synset-52 rdf:type ontolex:LexicalConcept ;
             rdfs:label "{kage_1§1}"@da ;
             skos:definition "sødt bagværk der især serveres til kaffe el. te el…"@da ;
             wn:hypernym dn:synset-135 .
```

This describes four facts about the same subject. You can see this exact Turtle output on any DanNet resource page. For instance, download the [Turtle file for synset-52](/dannet/data/synset-52?format=text/turtle).

### The DanNet data model (simplified)

DanNet follows the [OntoLex-Lemon](https://www.w3.org/2016/05/ontolex/) standard. The key building blocks are:

- **LexicalEntry** (a word, e.g. "kage"): has a written form and a part of speech
- **LexicalSense** (a word-meaning pairing): connects a word to a concept
- **LexicalConcept / Synset** (a meaning): has a definition, and relates to other synsets

The chain looks like this:

```
LexicalEntry (word)  →  LexicalSense  →  LexicalConcept (synset)
                                                ↕
                                other Synsets (hypernyms, hyponyms, etc.)
```

## 2. What is SPARQL?

SPARQL is the query language for RDF data. If SQL is how you query tables, SPARQL is how you query graphs of triples. It's not just a DanNet thing either: SPARQL is used across the linked data world. Most famously, [Wikidata](https://www.wikidata.org/) exposes its entire knowledge base (over 100 million items) through a [SPARQL endpoint](https://query.wikidata.org/). The same query concepts you learn here apply directly there. In fact, the [SPARQL Wikibook](https://en.wikibooks.org/wiki/SPARQL) uses Wikidata as its running example and is a great companion to this tutorial.

The key insight: **a SPARQL query is a pattern of triples with variables in it.** The database finds all triples that match your pattern and returns the variable bindings.

### Your first query

Let's find all the meanings (synsets) of the Danish word "kage":

```sparql
SELECT DISTINCT ?synset ?label ?definition WHERE {
  ?entry ontolex:canonicalForm/ontolex:writtenRep "kage"@da .
  ?entry ontolex:sense/ontolex:isLexicalizedSenseOf ?synset .
  ?synset rdfs:label ?label .
  ?synset skos:definition ?definition .
}
```

[Run this query](/dannet/sparql?query=SELECT%20DISTINCT%20%3Fsynset%20%3Flabel%20%3Fdefinition%20WHERE%20%7B%20%3Fentry%20ontolex%3AcanonicalForm%2Fontolex%3AwrittenRep%20%22kage%22%40da%20.%20%3Fentry%20ontolex%3Asense%2Fontolex%3AisLexicalizedSenseOf%20%3Fsynset%20.%20%3Fsynset%20rdfs%3Alabel%20%3Flabel%20.%20%3Fsynset%20skos%3Adefinition%20%3Fdefinition%20.%20%7D)

Let's break this down:

- [`SELECT`](https://en.wikibooks.org/wiki/SPARQL/SELECT) `DISTINCT ?synset ?label ?definition` tells the database which variables to return. `DISTINCT` removes duplicate rows.
- `WHERE { … }` contains the graph pattern to match.
- `?entry`, `?synset`, `?label`, `?definition` are [**variables**](https://en.wikibooks.org/wiki/SPARQL/Variables) (always start with `?`).
- `"kage"@da` is a concrete value: the Danish string "kage".
- Each line inside the `WHERE` block is a **triple pattern**, ending with a period.

The query reads: *"Find any entry whose written form is 'kage', follow its senses to their synsets, and return each synset's label and definition."*

**Results (3 synsets):**

| ?synset | ?label | ?definition |
|---|---|---|
| [dn:synset-52](/dannet/data/synset-52) | {kage_1§1} | sødt bagværk der især serveres til kaffe el. te… |
| [dn:synset-21205](/dannet/data/synset-21205) | {kage_1§2} | mere el. mindre fast (størknet el. sammenpresset) … |
| [dn:synset-40950](/dannet/data/synset-40950) | {en del/bid af kagen_1§6} | en andel af noget fordelagtigt, især et økonomisk … |

The word "kage" has three meanings: cake (the pastry), a compacted mass or lump, and the idiomatic "en del af kagen" (a piece of the pie, i.e. a share of something desirable).

### Reading the synset labels

The labels like `{kage_1§1}` deserve explanation. They list all the words that share this meaning, i.e. the **synonyms**. When a synset has several, you'll see them separated by semicolons: `{bag_2§1; bagværk_§1; brød_1§1b}`. The `§1` notation comes from [Den Danske Ordbog (DDO)](https://ordnet.dk/ddo) and points to a specific definition number in that dictionary.

### Property paths: the `/` shorthand

Notice the slash syntax:

```sparql
?entry ontolex:canonicalForm/ontolex:writtenRep "kage"@da .
```

This is a [**property path**](https://en.wikibooks.org/wiki/SPARQL/Property_paths). It's shorthand for two triple patterns chained together:

```sparql
?entry ontolex:canonicalForm ?form .
?form  ontolex:writtenRep    "kage"@da .
```

The `/` means "follow this property, then follow that property". It saves you from having to introduce intermediate variables you don't care about.


## 3. Exploring relationships

### Hypernyms: "is a kind of"

In a WordNet, every concept sits in a hierarchy. A *hypernym* is the broader concept above. Let's find what "kage" (cake, [synset-52](/dannet/data/synset-52)) is a kind of:

```sparql
SELECT ?hypernym ?label WHERE {
  dn:synset-52 wn:hypernym ?hypernym .
  ?hypernym rdfs:label ?label .
}
```

[Run this query](/dannet/sparql?query=SELECT+%3Fhypernym+%3Flabel+WHERE+{+dn%3Asynset-52+wn%3Ahypernym+%3Fhypernym+.+%3Fhypernym+rdfs%3Alabel+%3Flabel+.+})

We learn that cake is a kind of *bagværk* (baked goods). One triple pattern gives us the parent concept.

### Hyponyms: "what kinds are there?"

The reverse question: what are the *kinds of* cake? We flip the pattern and look for synsets whose hypernym *is* our synset:

```sparql
SELECT ?hyponym ?label WHERE {
  ?hyponym wn:hypernym dn:synset-52 .
  ?hyponym rdfs:label ?label .
} LIMIT 10
```

[Run this query](/dannet/sparql?query=SELECT%20%3Fhyponym%20%3Flabel%20WHERE%20%7B%20%3Fhyponym%20wn%3Ahypernym%20dn%3Asynset-52%20.%20%3Fhyponym%20rdfs%3Alabel%20%3Flabel%20.%20%7D%20LIMIT%2010)

Note the [`LIMIT 10`](https://en.wikibooks.org/wiki/SPARQL/Modifiers). Always good practice when exploring, so you don't accidentally fetch thousands of results.

### Climbing the hierarchy with property paths

You can use `+` in a [property path](https://en.wikibooks.org/wiki/SPARQL/Property_paths) to mean "one or more steps". This query finds all ancestors of cake, all the way up:

```sparql
SELECT ?ancestor ?label WHERE {
  dn:synset-52 wn:hypernym+ ?ancestor .
  ?ancestor rdfs:label ?label .
}
```

[Run this query](/dannet/sparql?query=SELECT+%3Fancestor+%3Flabel+WHERE+{%0A++dn%3Asynset-52+wn%3Ahypernym%2B+%3Fancestor+.%0A++%3Fancestor+rdfs%3Alabel+%3Flabel+.%0A})

This traces the full chain: kage → bagværk → fødevare (food).

### More than just hypernyms

DanNet encodes much richer relationships than just "is a kind of". The cake synset ([synset-52](/dannet/data/synset-52)) also knows that cake *contains* flour and sugar (substance meronyms), that cake is the *result of* baking, and that cake is *used for* eating:

```sparql
SELECT ?relation ?targetLabel WHERE {
  VALUES ?relation { wn:mero_substance wn:result dns:usedFor }
  dn:synset-52 ?relation ?target .
  ?target rdfs:label ?targetLabel .
}
```

[Run this query](/dannet/sparql?query=SELECT+%3Frelation+%3FtargetLabel+WHERE+{%0A++VALUES+%3Frelation+{+wn%3Amero_substance+wn%3Aresult+dns%3AusedFor+}%0A++dn%3Asynset-52+%3Frelation+%3Ftarget+.%0A++%3Ftarget+rdfs%3Alabel+%3FtargetLabel+.%0A})

We'll look at more of these DanNet-specific relations in [section 7](#7-dannet-specific-relations).


## 4. OPTIONAL: handling missing data

Not every synset has every property. If you want results even when some data is absent, use [`OPTIONAL`](https://en.wikibooks.org/wiki/SPARQL/OPTIONAL):

```sparql
SELECT DISTINCT ?synset ?label ?hyperLabel WHERE {
  ?entry ontolex:canonicalForm/ontolex:writtenRep "fisk"@da .
  ?entry ontolex:sense/ontolex:isLexicalizedSenseOf ?synset .
  ?synset rdfs:label ?label .
  OPTIONAL { ?synset wn:hypernym/rdfs:label ?hyperLabel . }
}
```

[Run this query](/dannet/sparql?query=SELECT%20DISTINCT%20%3Fsynset%20%3Flabel%20%3FhyperLabel%20WHERE%20%7B%20%3Fentry%20ontolex%3AcanonicalForm%2Fontolex%3AwrittenRep%20%22fisk%22%40da%20.%20%3Fentry%20ontolex%3Asense%2Fontolex%3AisLexicalizedSenseOf%20%3Fsynset%20.%20%3Fsynset%20rdfs%3Alabel%20%3Flabel%20.%20OPTIONAL%20%7B%20%3Fsynset%20wn%3Ahypernym%2Frdfs%3Alabel%20%3FhyperLabel%20.%20%7D%20%7D)

This finds all senses of "fisk" (fish) and their hypernyms *where they exist*. Without `OPTIONAL`, any synset lacking a hypernym would be silently dropped from the results.

The word "fisk" has six meanings, from the animal to a zodiac sign, a card game, slang for a criminal, and an idiom for a strange person. Two of those senses lack hypernyms; without `OPTIONAL`, they'd silently disappear from the results.


## 5. FILTER: narrowing results

[`FILTER`](https://en.wikibooks.org/wiki/SPARQL/FILTER) lets you add conditions beyond simple pattern matching. Common uses:

### Text matching with CONTAINS

Find all synsets whose definition mentions "pattedyr" (mammal):

```sparql
SELECT ?synset ?label ?definition WHERE {
  ?synset skos:definition ?definition .
  ?synset rdfs:label ?label .
  FILTER(CONTAINS(?definition, "pattedyr"))
} LIMIT 10
```

[Run this query](/dannet/sparql?query=SELECT%20%3Fsynset%20%3Flabel%20%3Fdefinition%20WHERE%20%7B%20%3Fsynset%20skos%3Adefinition%20%3Fdefinition%20.%20%3Fsynset%20rdfs%3Alabel%20%3Flabel%20.%20FILTER(CONTAINS(%3Fdefinition%2C%20%22pattedyr%22))%20%7D%20LIMIT%2010)

### Language filtering

If a property has values in multiple languages, you can filter by language tag:

```sparql
SELECT ?synset ?label WHERE {
  ?synset rdfs:label ?label .
  ?synset wn:hypernym dn:synset-52 .
  FILTER(LANG(?label) = "da")
}
```

[Run this query](/dannet/sparql?query=SELECT+%3Fsynset+%3Flabel+WHERE+{%0A++%3Fsynset+rdfs%3Alabel+%3Flabel+.%0A++%3Fsynset+wn%3Ahypernym+dn%3Asynset-52+.%0A++FILTER(LANG(%3Flabel)+%3D+"da")%0A})

### Comparison operators

```sparql
FILTER(?count > 5)
FILTER(?word != "kage"@da)
FILTER(STRSTARTS(?label, "{kat"))
```

For more on the available [expressions and functions](https://en.wikibooks.org/wiki/SPARQL/Expressions_and_Functions) you can use inside `FILTER`, see the Wikibook chapter or the [W3C specification](https://www.w3.org/TR/sparql11-query/#expressions).

**Performance note:** `FILTER` with `CONTAINS` on large result sets can be slow. When possible, constrain results with triple patterns first, then filter.


## 6. Aggregation: counting and grouping

The [modifiers](https://en.wikibooks.org/wiki/SPARQL/Modifiers) `GROUP BY`, `ORDER BY`, and `LIMIT` let you aggregate and sort results. See also the Wikibook chapter on [aggregate functions](https://en.wikibooks.org/wiki/SPARQL/Aggregate_functions).

### How many hyponyms does a concept have?

```sparql
SELECT ?label (COUNT(?hyponym) AS ?numHyponyms) WHERE {
  ?hyponym wn:hypernym dn:synset-52 .
  dn:synset-52 rdfs:label ?label .
}
GROUP BY ?label
```

[Run this query](/dannet/sparql?query=SELECT+%3Flabel+(COUNT(%3Fhyponym)+AS+%3FnumHyponyms)+WHERE+{%0A++%3Fhyponym+wn%3Ahypernym+dn%3Asynset-52+.%0A++dn%3Asynset-52+rdfs%3Alabel+%3Flabel+.%0A}%0AGROUP+BY+%3Flabel)

### Which words have the most senses?

```sparql
SELECT ?word ?senses WHERE {
  {
    SELECT ?word (COUNT(DISTINCT ?synset) AS ?senses) WHERE {
      ?entry ontolex:canonicalForm/ontolex:writtenRep ?word .
      ?entry ontolex:sense/ontolex:isLexicalizedSenseOf ?synset .
    }
    GROUP BY ?word
    ORDER BY DESC(?senses)
    LIMIT 10
  }
}
```

[Run this query](/dannet/sparql?query=SELECT%20%3Fword%20%3Fsenses%20WHERE%20%7B%20%7B%20SELECT%20%3Fword%20(COUNT(DISTINCT%20%3Fsynset)%20AS%20%3Fsenses)%20WHERE%20%7B%20%3Fentry%20ontolex%3AcanonicalForm%2Fontolex%3AwrittenRep%20%3Fword%20.%20%3Fentry%20ontolex%3Asense%2Fontolex%3AisLexicalizedSenseOf%20%3Fsynset%20.%20%7D%20GROUP%20BY%20%3Fword%20ORDER%20BY%20DESC(%3Fsenses)%20LIMIT%2010%20%7D%20%7D)

`GROUP BY` collects rows, `COUNT` aggregates them, `ORDER BY DESC(…)` sorts descending, and `LIMIT` caps the output. This query uses a [subquery](https://en.wikibooks.org/wiki/SPARQL/Subqueries) to perform the aggregation and limiting in one step, which is more efficient for large datasets.


## 7. DanNet-specific relations

DanNet goes beyond the standard WordNet hypernym/hyponym taxonomy. It encodes functional and thematic relationships that are worth exploring.

### "Used for": functional purpose

What things are used for sport?

```sparql
SELECT DISTINCT ?thingLabel ?purposeLabel WHERE {
  ?thing dns:usedFor ?purpose .
  ?thing rdfs:label ?thingLabel .
  ?purpose rdfs:label ?purposeLabel .
  FILTER(CONTAINS(?purposeLabel, "sport"))
} LIMIT 10
```

[Run this query](/dannet/sparql?query=SELECT%20DISTINCT%20%3FthingLabel%20%3FpurposeLabel%20WHERE%20%7B%20%3Fthing%20dns%3AusedFor%20%3Fpurpose%20.%20%3Fthing%20rdfs%3Alabel%20%3FthingLabel%20.%20%3Fpurpose%20rdfs%3Alabel%20%3FpurposeLabel%20.%20FILTER(CONTAINS(%3FpurposeLabel%2C%20%22sport%22))%20%7D%20LIMIT%2010)

### Thematic roles: who does what?

```sparql
SELECT ?actionLabel ?agentLabel WHERE {
  ?action wn:involved_agent ?agent .
  ?action rdfs:label ?actionLabel .
  ?agent rdfs:label ?agentLabel .
} LIMIT 10
```

[Run this query](/dannet/sparql?query=SELECT%20%3FactionLabel%20%3FagentLabel%20WHERE%20%7B%20%3Faction%20wn%3Ainvolved_agent%20%3Fagent%20.%20%3Faction%20rdfs%3Alabel%20%3FactionLabel%20.%20%3Fagent%20rdfs%3Alabel%20%3FagentLabel%20.%20%7D%20LIMIT%2010)

### Cross-lingual links

DanNet synsets are linked to the [Open English WordNet](https://en-word.net/) via `wn:eq_synonym`:

```sparql
SELECT ?label ?enSynset WHERE {
  ?entry ontolex:canonicalForm/ontolex:writtenRep "kage"@da .
  ?entry ontolex:sense/ontolex:isLexicalizedSenseOf ?synset .
  ?synset rdfs:label ?label .
  ?synset wn:eq_synonym ?enSynset .
}
```

[Run this query](/dannet/sparql?query=SELECT%20%3Flabel%20%3FenSynset%20WHERE%20%7B%20%3Fentry%20ontolex%3AcanonicalForm%2Fontolex%3AwrittenRep%20%22kage%22%40da%20.%20%3Fentry%20ontolex%3Asense%2Fontolex%3AisLexicalizedSenseOf%20%3Fsynset%20.%20%3Fsynset%20rdfs%3Alabel%20%3Flabel%20.%20%3Fsynset%20wn%3Aeq_synonym%20%3FenSynset%20.%20%7D)

The result URI `https://en-word.net/id/oewn-07644479-n` is a synset in the English WordNet. You can [look it up](https://en-word.net/id/oewn-07644479-n) to find the English equivalents.

## 8. Practical tips

**Start small.** When exploring, always use `LIMIT`. A query without `LIMIT` that matches thousands of results will be slow or time out.

**Use `DISTINCT`.** Because a word can have multiple senses leading to the same synset (via different paths), you'll often get duplicate rows. `DISTINCT` cleans that up.

**Explore a single entity first.** Before writing a general query, pick one entity and look at all its properties:

```sparql
SELECT ?prop ?value WHERE {
  dn:synset-52 ?prop ?value .
} LIMIT 30
```

[Run this query](/dannet/sparql?query=SELECT+%3Fprop+%3Fvalue+WHERE+{%0A++dn%3Asynset-52+%3Fprop+%3Fvalue+.%0A}+LIMIT+30)

This is the SPARQL equivalent of "show me everything about this thing". It's the best way to discover what properties are available. You can also just [visit the resource page](/dannet/data/synset-52) and browse the same data visually.

**Think in triples.** Every question you want to ask translates to a pattern of triples. "What words are synonyms of X?" becomes: "Find entries that have a sense linked to the same synset as X."

**Read the Turtle.** Visit any DanNet resource page (e.g. [synset-52](/dannet/data/synset-52)) and download the [Turtle representation](/dannet/data/synset-52?format=text/turtle). The predicates you see there are the same ones you use in your SPARQL queries.


## 9. Common prefixes

You don't need to memorize full URIs. The DanNet [SPARQL endpoint](/dannet/sparql) pre-declares these [prefixes](https://en.wikibooks.org/wiki/SPARQL/Prefixes) (among others):

| Prefix | Namespace | Used for |
|--------|---|---|
| **dn:** | https://wordnet.dk/dannet/data/ | DanNet synsets, words, senses |
| **dns:** | https://wordnet.dk/dannet/schema/ | DanNet-specific properties |
| **dnc:** | https://wordnet.dk/dannet/concepts/ | DanNet ontological types |
| **ontolex:** | http://www.w3.org/ns/lemon/ontolex# | Lexical entries, senses, forms |
| **wn:**  | https://globalwordnet.github.io/schemas/wn# | WordNet relations (hypernym, etc.) |
| **skos:** | http://www.w3.org/2004/02/skos/core# | Definitions |
| **rdfs:** | http://www.w3.org/2000/01/rdf-schema# | Labels |
| **rdf:** | http://www.w3.org/1999/02/22-rdf-syntax-ns# | Types |


## 10. Exercises

Try these on the [DanNet SPARQL endpoint](/dannet/sparql):

1. Find all the meanings of the word "hund" (dog). How many distinct synsets are there?
2. What are the kinds of (hyponyms of) "kat" (cat, [synset-3264](/dannet/data/synset-3264))?
3. Find all synsets that are used for "mad" (food). *(Hint: use `dns:usedFor` and filter by the label.)*
4. Which word in DanNet has the most distinct synsets?
5. Pick any synset and trace its hypernym chain all the way to the top using `wn:hypernym+`. How many levels are there?

---

* The DanNet SPARQL endpoint is at: [`/dannet/sparql`](/dannet/sparql)*
* Browse the data at: [`wordnet.dk`](/)*
* For the full SPARQL specification, see: [W3C SPARQL 1.1 Query Language](https://www.w3.org/TR/sparql11-query/)*
* For a broader SPARQL tutorial (with Wikidata examples), see: [SPARQL on Wikibooks](https://en.wikibooks.org/wiki/SPARQL)*
