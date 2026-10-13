Libraries in and around DanNet
==============================

Research done on 13 October 2026, on master at ae865fe.

This report answers two questions:

1. Which parts of DanNet could become libraries of their own, in the style
   of whatwg-url, langtag-clj or drop-in-search?
2. Which Clojure libraries, existing or not yet written, could DanNet use to
   improve its features or its code?

Both lists are ranked, with the reasons for each rank. DanNet has about
20,100 lines of Clojure in `src/` and 930 lines of tests. The line counts
below are estimates from reading the code. Versions and dates of other
libraries were checked on Clojars, Maven Central and GitHub on the same day.

A few bugs turned up during the research, two of them security problems.
They are listed at the end, under [Problems found along the way](#problems-found-along-the-way).

Since then, both security problems are fixed, and DanNet no longer uses
tightly-packed-trie. Those items are marked as done.


Summary
-------

- **Best spin-out: a DMLex library.** It moves the most code out of DanNet
  (about 900 lines plus 360 lines of tests), DMLex-browser would use it too,
  and no library for DMLex exists on the JVM.
- **Next: content negotiation, then WordNet similarity and WN-LMF.** Content
  negotiation completes the family of whatwg-mime and langtag-clj and fixes
  real bugs in DanNet. Similarity and WN-LMF could become one library in the
  spirit of the Python `wn` package.
- **Best library to adopt: langtag-clj.** It is a small change that fixes
  language negotiation for every browser set to da-DK.
- **Most important upkeep: Jena.** DanNet runs Jena 4.5.0 from 2022 through
  the Aristotle fork. Jena is at 6.2.0.
- **Largest change worth planning: Replicant in place of the Rum fork and
  React.** It waits on Replicant's hydration, which is not released yet.


Part 1: Code to spin out of DanNet
----------------------------------

A candidate ranks higher when:

- it is built on a standard, a spec or a published algorithm, so it stands
  on its own;
- many lines leave DanNet;
- there is room to build it out past what DanNet needs today;
- no maintained Clojure library covers it already;
- someone other than DanNet would use it.

| Rank | Library | Built on | Lines leaving DanNet | Existing Clojure work |
|---|---|---|---|---|
| 1 | dmlex | OASIS DMLex 1.0 | ~900, plus 360 test lines | none |
| 2 | Content negotiation | RFC 9110 section 12, RFC 4647, Cool URIs | ~180, plus bug fixes | juxt/pick (quiet since 2024), liberator (2019) |
| 3 | WordNet similarity | Rada 1989, Wu & Palmer 1994, Resnik 1995, Jiang & Conrath 1997, Leacock & Chodorow 1998, Lin 1998 | ~290 | none |
| 4 | wn-lmf | GWA WN-LMF 1.0-1.4 | ~130 | none |
| 5 | RDF prefixes and terms | CURIE 1.0, Turtle and SPARQL prefixed names, RDFa 1.1, JSON-LD | ~210, plus the ont-app/vocabulary pin | ont-app/vocabulary (buggy), kuhumcst/qname (archived) |
| 6 | Linked Data pages | Cool URIs, RDFa 1.1 | up to ~1,300 | none |
| 7 | SPARQL endpoint | SPARQL 1.1 Protocol and result formats | ~400 | none outside apps |
| 8 | cor | COR and COR.SEM specs, `.cordiff` | ~170 | none |
| 9 | Rate limiting | RFC 6585, IETF RateLimit draft 11 | ~120 | ring-congestion (old headers) |
| 10 | Problem details | RFC 9457 | ~100 | none released |

### 1. dmlex

DanNet's DMLex export builds one map in the shape of DMLex JSON, then writes
it as XML and as JSON. That map is a clean seam. On one side all the code is
about DMLex, and on the other side it is about DanNet's graphs.

**What leaves DanNet:**

- the element builders, the conversion of stand-off markers into inline
  `headwordMarker` elements, the indentation of element-only XML content,
  the streaming XML and JSON writers and the `homographNumber` coercion
  (`dmlex.clj:226-440`, about 215 lines);
- almost all of `dmlex_validate.clj` (about 690 of 718 lines). It strips the
  XSD asserts and checks them in a streaming pass, checks ids and references
  that the XSD can't, and validates the JSON one item at a time;
- its test (360 lines, fixtures only, no database), the schemas in
  `doc/dmlex/spec/` and most of `doc/dmlex/findings.md`, which records
  defects in the spec.

The tag inventories and the mapping from the RDF graphs stay (about 1,300
lines).

**Room to grow:**

- reading as well as writing, for both XML and JSON;
- the 15 elements DanNet never writes (pronunciations, translations, notes,
  placeholder and collocate markers) and the whole Etymology module;
- the RDF serialisation, which the standard requires. OASIS ships it with a
  SHACL shape, so Jena can check the output;
- validation rules that work on plain data, so they run in the browser too;
- round-trip tests with the examples in the spec.

**Second user.** DMLex-browser reads the same JSON shape and has DMLex
helpers of its own: inventory indexing, stand-off markers to text runs (the
inverse of DanNet's conversion) and relation member lookup. One .cljc
library could serve both the writer and the reader.

**Existing work.** No DMLex library exists on the JVM. The only one in any
language is [jmccrae/dmlex-converter](https://github.com/jmccrae/dmlex-converter),
in Rust.

**Why first.** It moves the most code, it has a second user, and DMLex is a
new OASIS standard (2025) with no Clojure or Java support.

**Caveats.** The current validator needs Xerces XSD 1.1 and a JSON Schema
validator, which only run on the JVM. A .cljc validator would have to state
the schema rules as code. That is work, but it is also what would make the
library more than a copy of DanNet's code. qname-hiccup would be the natural
XML layer if it could stream to a Writer and write a leading comment and a
DOCTYPE. Today it builds the whole document in a StringBuilder, and DanNet's
DMLex XML file is 200 MB.

**Side note.** DMLex-browser's `uniquify-sense-ids` works around duplicate
sense ids from DanNet. The export of 5 October has 77,288 sense ids and none
are duplicated, so the workaround may no longer be needed.

### 2. Content negotiation

This means choosing the format and the language of a response from the
Accept and Accept-Language headers
([RFC 9110, section 12](https://www.rfc-editor.org/rfc/rfc9110#section-12)).
On top come the Linked Data conventions of
[Cool URIs for the Semantic Web](https://www.w3.org/TR/cooluris/): 303
redirects for things that aren't documents, plus Vary, Content-Location and
`Link: rel="alternate"` headers.

**DanNet today.** `resources.clj:31-218` and parts of `response.clj` use
Pedestal's Accept parser for both headers. That parser is made for media
types, so language matching goes wrong. Tested in the REPL with the
languages `["da" "en"]`:

| Accept-Language | DanNet picks | Should pick |
|---|---|---|
| `da-DK` | nothing (falls back to English) | da |
| `da-DK, en;q=0.8` | en | da |
| `EN` | nothing | en |
| `da;q=0` | da | not da |

A malformed q value such as `q=abc` throws a NumberFormatException. An
unknown `?format=xml` ends in a 500 rather than a 406.

**What leaves DanNet:** about 180 lines. About 120 lines stay: DanNet's body
functions, cookies and the redirect header of the single-page app.

**Room to grow:** Accept-Encoding and Accept-Charset, the specificity rules,
q=0 meaning "not acceptable", computing Vary, 406 responses that list the
alternatives, an opt-in query parameter override, the 303 pattern and Link
headers, and adapters for Ring and Pedestal around a pure .cljc core. The
examples in RFC 9110 make test cases, and
[jshttp/negotiator](https://github.com/jshttp/negotiator) has a large test
corpus.

**Fit.** It would sit on top of whatwg-mime for media types and langtag-clj
for language ranges. indieblog, micropub-clj and your other web projects
could use it too.

**Existing work.** [juxt/pick](https://github.com/juxt/pick) is the most
complete, but it is a git-only dependency written for RFC 7231 and nobody
has touched it since January 2024. liberator's negotiation (2019) is tied to
liberator. muuntaja only picks a format and ignores q=0, wildcards and
Accept-Language.

**Why second.** A clear standard, an open gap, real bugs fixed, and the
widest audience on this list. Fewer lines leave DanNet than with dmlex.

### 3. WordNet similarity

`similarity.clj` computes path, Leacock-Chodorow and Wu-Palmer similarity
over a hypernym graph. `web/hyponymy.clj` builds the bounded hyponym
subtrees for the sunburst.

**What leaves DanNet:** about 290 lines, which already take and return plain
maps (`{child #{parents}}`). The graph building from DanNet's RDF (with its
`dns:orthogonalHypernym` fallback), the `dnf:` SPARQL functions and the
labelling stay. `hyponym-subtree` reads the global `@q/synset-indegrees`
(`hyponymy.clj:68`), which would have to become an argument.

**Room to grow:**

- Resnik, Jiang-Conrath and Lin, which are stubs that throw today
  (`similarity.clj:312-334`), and intrinsic information content (Seco et al.
  2004), which is a stub too;
- information content computed from a corpus;
- the `simulate_root` option of `wn`, hypernym paths and closures;
- a faster `max-depth`, which follows every path from a synset today;
- .cljc, so the measures run in the browser too.

There are no tests today. The Python [wn](https://github.com/goodmami/wn)
package and NLTK give numbers to test against on OEWN, which DanNet already
loads.

**Existing work.** None in Clojure.
[WS4J](https://github.com/dmeoli/WS4J) (Java) is tied to the Princeton
WordNet 3.0 files. Graph libraries don't help: JGraphT's lowest common
ancestor isn't the one that `wn` uses, and DanNet's hypernym data has cycles
that DAG algorithms don't expect.

**Why third.** It fits your style best (published algorithms, plain data and
an obvious test oracle) and fills a gap. Fewer lines leave DanNet, and the
audience is smaller.

### 4. wn-lmf

This covers the Global WordNet Association's
[WN-LMF formats](https://github.com/globalwordnet/schemas): an XML DTD in
versions 1.0 to 1.4, JSON and RDF.

**DanNet today.** `wn_lmf.clj` writes WN-LMF 1.1 XML. It builds the whole
document as one string with data.xml's `indent-str`, which data.xml itself
describes as slow and meant for debugging, then splices in the DOCTYPE by
splitting that string. The builders take raw SPARQL rows, so a plain data
model has to come first.

**What leaves DanNet:** about 130 lines, the GWA relation list and the
builders. More would go if bootstrap read OEWN from its WN-LMF release
instead of from Turtle.

**Room to grow:** reading and writing 1.0 to 1.4, the parts DanNet skips
(SenseRelation, Form, Pronunciation, Tag, Count, SyntacticBehaviour,
ILIDefinition, examples), DTD validation and the checks of `wn`'s validator,
tested on OEWN and Open Multilingual Wordnet data.

**Existing work.** None in Clojure. Python `wn` 1.1.1 reads, writes and
validates every version. jmccrae/gwn-scala-api reads and writes on the JVM.

**Why fourth.** An open gap with room to grow, but little code leaves
DanNet. Consider merging 3 and 4 into one library, as Python's `wn` does:
read and write WN-LMF, then measure similarity in what you read. That is a
coherent library of two things, and it gives the similarity code real data
to test on.

### 5. RDF prefixes and terms

This would be one prefix map as plain data, with conversions between
keywords, prefixed names and IRIs. It would write the map as a SPARQL PREFIX
block, Turtle `@prefix` lines, an RDFa `prefix` attribute and a JSON-LD
`@context`.

**DanNet today.** `prefix.cljc` (508 lines) and `json_ld.clj` build each of
these by hand in different places (`json_ld.clj:32`, `response.clj:49`,
`export/rdf.clj:74`). DanNet keeps two global prefix registries in step,
Aristotle's and ont-app/vocabulary's. The parsing is loose:

- `qname?` rejects `/` in local names;
- `qname->kw` drops everything after a second colon;
- `kw->qname` doesn't escape local names;
- `uri-parts` only handles http and https.

**What leaves DanNet:** about 120 lines from `prefix.cljc` and about 90 from
`json_ld.clj`. The prefix data, routes, CSS classes and download helpers
stay.

**Room to grow:** the CURIE 1.0 grammar including safe `[...]` CURIEs,
Turtle's escaping of local names, longest-namespace matching, and loading
the RDFa initial context or prefix.cc.

**Existing work.** ont-app/vocabulary 0.5 has the problems described under
Part 2, item 4. kuhumcst/qname was archived in 2023 after three commits. The
Python [curies](https://github.com/biopragmatics/curies) package is a good
design to learn from.

**Why fifth.** About 30 DanNet namespaces use `prefix.cljc`, so the gain is
spread thin. But it removes a pinned dependency and settles how RDF terms
look in DanNet, which candidate 6 depends on.

### 6. Linked Data pages

This is the part of the website that renders any RDF resource as HTML:
prefixed names, typed and language-tagged literals, blank nodes and
containers, the attribute-value table with RDFa, a section per `rdf:type`,
and the protocol of server-rendered HTML followed by Transit.

About 1,300 of the 5,300 lines in the web UI are generic in this sense. No
Clojure library does this. The nearest are
[Trifid](https://github.com/zazuko/trifid) (TypeScript) and LodView (Java,
quiet since 2023).

**Why sixth.** It has the most code, but that code is tied to Rum, to
Aristotle's keyword IRIs, to LangStr and to blank nodes stored as symbols
with metadata. It is a second step, after the move to Replicant (Part 2,
item 3) and after candidates 2 and 5. A first slice could be a library that
turns RDF terms into Hiccup with RDFa (about 400-500 lines), tested against
the RDFa test suite.

### 7. SPARQL endpoint

This is the server side of the
[SPARQL 1.1 Protocol](https://www.w3.org/TR/sparql11-protocol/): query
validation, limits, timeouts, result formats, caching and merging identical
requests that arrive at the same time. About 400 lines in `sparql.clj` and
`resources.clj` are generic.

**Room to grow.** Several gaps are bugs today:

- ASK queries call `.materialise` on a boolean (`sparql.clj:134`);
- CONSTRUCT and DESCRIBE results have no serialiser;
- POST queries never reach the handler;
- SELECT results only come as JSON, although Jena's `ResultSetLang` writes
  XML, CSV and TSV in a few lines;
- there is no service description;
- CORS is only on in development, so browser-based SPARQL clients can't use
  the public endpoint.

The W3C has 34 protocol tests to run against a live endpoint.

**Why seventh.** It runs on the JVM and Jena only, so it can't be .cljc like
your other libraries, and Fuseki already serves the machine-facing side of
this. The bugs are worth fixing in place either way.

### 8. cor

These would be readers for the COR and COR.SEM files from
[ordregister.dk](https://ordregister.dk/files/) and for the `.cordiff`
changelogs.

About 170 lines in `bootstrap/cor.clj` and `corsem.clj` are format code: the
COR id grammar, grammar labels, norm codes, column layouts and changelog
parsing. The conversion only runs when a new COR edition comes out, since
DanNet carries the graph forward as data. `dmlex.clj:667-709` also works out
COR facts again from RDF strings.

**Room to grow:** all 11 `.cordiff` operations (DanNet parses MRG, MOV and
REP), applying a changelog to an edition, decoding grammar labels such as
`sb.fk.pl.best.gen` into features, and paradigms per lemma.

**Why eighth.** A published format with no reader in any language, but the
audience is Danish NLP, and the code sits idle in DanNet.

### 9. Rate limiting

All 119 lines of `rate_limit.clj` are generic, and they have bugs (see
[Problems](#problems-found-along-the-way)). A library could use a token
bucket and send the IETF `RateLimit` and `RateLimit-Policy` fields. The
[draft](https://datatracker.ietf.org/doc/draft-ietf-httpapi-ratelimit-headers/)
is at version 11 (May 2026), and its syntax has changed between versions.
The code is small and the bugs are easier to fix in place.

### 10. Problem details

These are [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) error responses
(`application/problem+json`) built from cognitect anomalies. The table from
anomaly category to HTTP status in `anomaly.cljc` is generic. No released
Clojure library does this, but it would only be about 100 lines. It could
live inside the content negotiation library.

### Not recommended

- **`export/csv.clj` as a CSVW library.** CSVW is a W3C standard with a test
  suite, but only about 20 lines would leave DanNet.
  [Swirrl/csv2rdf](https://github.com/Swirrl/csv2rdf) already covers parsing
  and RDF output on the JVM.
- **`hash.clj`.** Neat, but there is no standard behind it. It would need
  hashes that follow dependencies to replace the 55 hashes that
  `bootstrap.clj` lists by hand.
- **The ARIA combobox** (83 lines). Too small. It also departs from the
  [APG pattern](https://www.w3.org/WAI/ARIA/apg/patterns/combobox/): focus
  moves into the options, and `aria-activedescendant` points at an id that
  no element has. It belongs in a UI kit such as stucco, perhaps as the
  missing UI for drop-in-search.
- **The D3 diagrams, the CodeMirror wrapper and the section config.** These
  are DanNet's own. The `styleTags` fix in `codemirror.cljs:14-25` would do
  more good as a pull request to codemirror-lang-sparql.
- **`operation.clj`, `search.clj`, `catalog.clj`, `stats.clj`,
  `chainnet.clj`, `metadata.clj`, `premon.clj` and `bootstrap.clj`.** These
  hold DanNet's own data and queries.
- **DanNet's generic Jena code** (transactions, hard timeouts, ARQ
  functions, SHACL reports as data; about 450 lines). Its home is Aristotle,
  not a new library. See Part 2, item 2.


Part 2: Libraries DanNet could adopt
------------------------------------

A library ranks higher when it fixes bugs, adds features or removes code or
dependencies, and lower when the change is large or the library is risky.

| Rank | Library | Replaces or adds | Size of change |
|---|---|---|---|
| 1 | langtag-clj | language negotiation | small |
| 2 | A newer Jena through Aristotle | Jena 4.5.0 from 2022 | medium |
| 3 | Replicant | the Rum fork and React 18 | large |
| 4 | A PREFIX block of DanNet's own | the ont-app/vocabulary 0.2.1 pin | small |
| 5 | drop-in-search | new: full-text search and "did you mean" | medium |
| 6 | babashka/fs and babashka/http-client | clj-file-zip, hand-written file helpers, downloads with no timeout | small |
| 7 | A sorted map (done) | tightly-packed-trie | small |
| 8 | Dependency upkeep | Pedestal, reitit, donatello, transito, reagent-utils, npm | small |
| 9 | An MCP library on the JVM | the separate Python MCP server | medium |

### 1. langtag-clj

- It replaces the language half of content negotiation
  (`resources.clj:31-67`).
- Tested in the REPL, `langtag/lookup` picks the right language in every
  case of the table under Part 1, item 2, and a malformed q value doesn't
  throw.
- The choice of labels (`i18n/select-label` and `select-str`) stays as it
  is. It matches the tags of RDF literals exactly against a fixed list such
  as `["da" "en" nil]`, which `lang-prefs` builds from the negotiated
  language, and the client's header never reaches it. Exact matching suits
  the data: the bundled schemas have 1,345 literals tagged `en` and 546
  tagged `da`, and only 6 with a region (`en-US`, `en-GB`).
- It was released today, but you maintain it, so that is less of a risk
  than usual.
- Going straight to the content negotiation library (Part 1, item 2) is
  just as good, since the library would call langtag-clj for this part
  anyway.

**Why first.** A small change that fixes a bug users hit, and the first step
toward Part 1, item 2.

### 2. A newer Jena, through Aristotle

- The kuhumcst/aristotle fork pins Jena 4.5.0 (2022). DanNet declares no
  Jena version of its own, so the public SPARQL endpoint runs a four-year-old
  engine. Jena is at 6.2.0 (July 2026), which needs Java 21. The JavaScript
  function CVEs in Jena up to 4.8 need a script engine, and DanNet has none
  on its classpath, so they don't seem to apply.
- Upstream aristotle's master branch now has every commit of the fork, plus
  Jena 5.3 and Clojure 1.12 (January 2025). Otherwise it is quiet.
- **Option A:** point the git dependency at upstream (one line) and port
  DanNet's own Jena code (TDB2, the reasoner, SHACL, ARQ functions) to Jena 5.
- **Option B:** take over Aristotle (about 1,560 lines, of which DanNet uses
  the core, graph, query and registry namespaces) and go straight to Jena 6.
  Then move DanNet's generic Jena code (about 450 lines) into it, and fix the
  `:union` that silently drops every clause after the second
  (`operation.clj:74-87` works around it). No maintained Jena wrapper exists
  in Clojure, so this fills a gap. It is a wrapper for a platform, though,
  not a library around a standard.
- [flint](https://github.com/yetanalytics/flint) writes SPARQL as Clojure
  data and is active. DanNet has 9 `q/build` forms and about 47 queries built
  as strings. Queries built as data, or with bound parameters, would rule
  out the kind of bug behind the search injection (see
  [Problems](#problems-found-along-the-way)). flint-jena targets Jena 4.8,
  and I couldn't check it against Jena 5 or 6.

**Why second.** It is upkeep rather than a feature, but it concerns the only
query engine DanNet has.

### 3. Replicant

- It replaces the Rum fork, React 18, react-dom and create-react-class.
- It removes the hydration workarounds (`ui.cljc:16`, `88-91`, `132-142`;
  `router.cljs:47-67`; the `%2F` fix at `resources.clj:340-344`), the
  react-dom/server workaround in `ui/error.cljc` and the error boundaries.
  Replicant calls components right away, so a plain try/catch catches errors
  while rendering.
- The pressure to move: Rum's last release was in 2023, its React 18 issue
  is still open, and React 19 removed `findDOMNode`, which `rum/dom-node`
  calls (`sparql.cljc:135`, `visualization.cljc:315`).
- The size: 63 components (55 `defc`, 8 `defcs`) in about 3,300 lines, with
  6 `rum/local`, 3 reactive components and 3 mixins (CodeMirror,
  ResizeObserver and the error boundary). Most components are already pure
  Hiccup. The work is moving local state into the session atom and turning
  the mixins into life-cycle hooks.
- DMLex-browser already renders through `replicant.dom` and
  `replicant.string`.
- The risk: hydration isn't released. It lives on a branch, and on 7 October
  the maintainer asked for testers
  ([cjohansen/replicant#53](https://github.com/cjohansen/replicant/issues/53)).
  It doesn't seem to run life-cycle hooks while hydrating yet, and D3 and
  CodeMirror need them. You are well placed to be that tester.

**Why third.** The biggest gain in code and dependencies in this part, but
also the biggest change, and it waits on a feature that isn't released.

### 4. A PREFIX block of DanNet's own

- The bug behind the 0.2.1 pin of ont-app/vocabulary: in `put-ns-meta!`, the
  call `(clear-caches!)` sits as a stray fifth form inside a
  `#?(:cljs ... :clj ...)` reader conditional, so it never runs. A prefix
  registered after the cache has filled is left out of the PREFIX block.
  An agent reproduced this in a scratch project. It isn't reported upstream.
- Version 0.5.x breaks more in DanNet: prefixes that both DanNet and the
  library claim (such as `dc`) throw "Ambiguous collection",
  `prefix-to-ns` returns sets, and `uri-for` and `keyword-for` are
  deprecated (11 call sites).
- The fix: build the PREFIX block from DanNet's own prefix map (about 10
  lines). That removes the reason for the pin. LangStr can stay on 0.2.1;
  quoll/rudolf is the only alternative in CLJ and CLJS, and switching gains
  little.

**Why fourth.** Small and safe, and it leads into Part 1, item 5.

### 5. drop-in-search, for a new feature

- It adds full-text search over definitions and examples, which DanNet
  lacks, with field boosts and snippets. Its typo matching could suggest
  "did you mean ..." when a lemma lookup finds nothing.
- The library doesn't stem words, but DanNet's COR data maps inflected forms
  to lemmas and could serve as its `:term-fn`.
- It is .cljc, so a small index could run in the browser too.
- The risk: nobody has measured its memory use at DanNet's size. Its own
  benchmark is 45 MB of EDN for 20,000 documents of 120 words. Keep the trie
  for lemma completion, since it also holds the COR inflected forms.

### 6. babashka/fs and babashka/http-client

- clj-file-zip 0.1.0 is from 2018 and is LGPL-3.0, while DanNet is MIT.
  [babashka/fs](https://github.com/babashka/fs) zips and unzips, and also
  replaces the hand-written gzip (`wn_lmf.clj:402`), `atomically!`
  (`downloads.clj:16`) and the staging in a temp dir (`rdf.clj:116-135`).
- `downloads.clj` slurps the GitHub API with no timeout and no token.
  babashka/http-client adds both in about 20-30 lines. wary-fetch would work
  too, but it is built for URLs from strangers, which these aren't.
- The risk: `rdf.clj` relies on how clj-file-zip names zip entries.

### 7. A sorted map in place of tightly-packed-trie

Done in 27db4c1. Autocomplete now searches a sorted vector of the search
strings.

- tightly-packed-trie has had no release since 2022. It brings timbre 4.10.0
  and tufte 2.2.0 onto the classpath next to Telemere, and DanNet has to lock
  around its shared ByteBuffer.
- A `java.util.TreeMap` or a sorted array searched by prefix does the same
  job with no dependency. It may use more memory, which nobody has measured.

### 8. Dependency upkeep

- **Pedestal 0.8.2** (October 2026) brings Jetty 12 and a new connector API.
  It deprecates `io.pedestal.http` but keeps it, so the bump itself is
  small. Its content negotiation code hasn't changed, so it doesn't fix the
  bugs above.
- **reitit.** DanNet calls the internal `reitit.impl` namespace for URL
  encoding (`prefix.cljc:429`, `481` and 7 places in `sparql.cljc`).
  Depending on reitit-core and reitit-frontend instead of the full bundle
  should remove malli, muuntaja, jsonista and swagger-ui from the classpath.
  I haven't checked the exact split of artifacts.
- **donatello.** Use `org.clojars.quoll/donatello` 1.3.6 from Clojars instead
  of the git tag.
- **Small wrappers.** transito wraps transit-clj and has one call site.
  reagent-utils is used once, for cookies. thi.ng/color is only used by a
  generator at development time and could move to a dev alias.
- **npm.** wordcloud seems unused, nothing in `src/main` uses katex, and
  markdown-it and texmath are pinned below what nextjournal/markdown asks for
  now.

### 9. An MCP library on the JVM

- `mcp/dannet_mcp_server.py` (486 lines, 18 tools) talks to DanNet over
  HTTP. [roklenarcic/mcp-server](https://github.com/roklenarcic/mcp-server),
  whose HTTP transport is a Ring handler, or
  [PluMCP](https://github.com/plumce/plumcp) could serve MCP from the DanNet
  process. That removes the Python deployment and the extra HTTP hop.
- The risk: both libraries are young, Pedestal needs an adapter for Ring
  handlers, and streaming with server-sent events needs care.

### Not worth adopting now

- **loom, ubergraph, cljgrapht.** See Part 1, item 3. The core of
  `similarity.clj` is about 60 lines.
- **integrant, donut.system, mount, aero.** Good libraries, but a broad
  refactor for little gain. DanNet reads no configuration from the
  environment.
- **Clerk and Clay.** They wouldn't replace the 30 lines in `stats.clj` that
  write the LaTeX tables.
- **tablecloth, tech.ml.dataset, charred.** data.csv and data.json are
  current, and the tables are small.
- **Datalevin, Asami, Fluree, grafter, igraph-jena.** They don't fit RDF and
  OWL, or nobody has maintained them since 2023.
- **whatwg-mime on its own.** It parses media types but doesn't negotiate.
  It belongs inside Part 1, item 2.
- **html-pieces, and qname-hiccup for reading.** DanNet renders no untrusted
  HTML and reads no XML.
- **kaocha.** It adds nothing over cognitect's test-runner here.


Suggested order
---------------

1. Fix the two security problems below (done in 5a48408 and ab732a7).
2. Build the content negotiation library on top of langtag-clj, and use it
   in DanNet once the XSS fix and the Pedestal upgrade have landed.
3. Start the dmlex library from the validator and the writers, with
   DMLex-browser as its second user.
4. Write one WordNet library for WN-LMF and similarity, tested against
   Python's `wn`.
5. Upgrade Jena before moving to Replicant. Move to Replicant once its
   hydration is released.


Problems found along the way
----------------------------

"Read" means the agent or I found it by reading the code and didn't run it.

| # | Problem | Where | How sure |
|---|---|---|---|
| 1 | The search pastes the user's text into a SPARQL string with no escaping, and runs it outside the timeout of the SPARQL endpoint. A `"` in a search breaks the query. A crafted search could run a slow query with no time limit. | `operation.clj:39`, `search.clj:31`, `resources.clj:547` | Fixed in 5a48408. |
| 2 | Possible reflected XSS. The raw `?lang=` value becomes the request's languages, and the page prints the languages with `pr-str` inside a single-quoted inline script. The production CSP allows inline scripts. | `resources.clj:106-116`, `response.clj:73`, `service.clj:91` | Fixed in ab732a7. |
| 3 | `Retry-After` holds a Unix time in seconds, not the number of seconds to wait. | `rate_limit.clj:102-104` | Read and confirmed. |
| 4 | Every counted request pushes the expiry of the rate limit forward, so the count only resets after a full minute with no requests. A client that sends 1 request per second is blocked after about 400 seconds. Entries are only removed when the same client comes back. | `rate_limit.clj:27-35` | Read and confirmed. |
| 5 | Language negotiation picks the wrong language (see Part 1, item 2), and a malformed q value throws. | `resources.clj:41`, `63` | Tested in the REPL. |
| 6 | An unknown `?format` gives a 500, not a 406. | `resources.clj:105` | Read. |
| 7 | The SPARQL endpoint: ASK throws, CONSTRUCT and DESCRIBE have no output, POST doesn't work, CORS is on in development only. | `sparql.clj:134`, `response.clj:147-181`, `resources.clj:605-607` and `750-758`, `service.clj:119-122` | Read. |
| 8 | An unknown entity returns a 404 with an empty body, so no page loads. | `resources.clj:202-204` | Read. |
| 9 | A full page load fetches each page twice, first as HTML and then as Transit before hydrating. Both count against the rate limit. | `router.cljs:145`, `257-264` | Read. |
| 10 | PROJECT_SUMMARY.md says Resnik, Jiang-Conrath and Lin are implemented, but they are stubs. It also lists igraph-jena, which isn't a dependency. | `PROJECT_SUMMARY.md:70`, `234` | Confirmed. |
| 11 | `label-lookup` memoizes on the graph object with no bound. | `search.clj:16` | Read. |
| 12 | The similarity, prefix, entity, query, hash and transaction namespaces have no tests. | `test/` | Reported by an agent. |
| 13 | DMLex-browser works around duplicate sense ids that the current export doesn't have. | DMLex-browser `build.clj:290` | Checked the export of 5 October. |


Sources
-------

Standards and specs:

- OASIS DMLex 1.0: https://docs.oasis-open.org/lexidma/dmlex/v1.0/
- GWA WN-LMF schemas: https://github.com/globalwordnet/schemas
- RFC 9110 (HTTP semantics): https://www.rfc-editor.org/rfc/rfc9110
- RFC 4647 (matching language tags): https://www.rfc-editor.org/rfc/rfc4647
- RFC 9457 (problem details): https://www.rfc-editor.org/rfc/rfc9457
- Cool URIs for the Semantic Web: https://www.w3.org/TR/cooluris/
- SPARQL 1.1 Protocol: https://www.w3.org/TR/sparql11-protocol/ and tests
  at https://github.com/w3c/rdf-tests/tree/main/sparql/sparql11/protocol
- IETF RateLimit header fields: https://datatracker.ietf.org/doc/draft-ietf-httpapi-ratelimit-headers/
- CURIE Syntax 1.0: https://www.w3.org/TR/curie/
- COR files: https://ordregister.dk/files/

Libraries and tools:

- Python wn: https://github.com/goodmami/wn
- dmlex-converter (Rust): https://github.com/jmccrae/dmlex-converter
- juxt/pick: https://github.com/juxt/pick
- jshttp/negotiator: https://github.com/jshttp/negotiator
- Aristotle: https://github.com/arachne-framework/aristotle
- Apache Jena downloads and security advisories:
  https://jena.apache.org/download/ and
  https://jena.apache.org/security/advisories.html
- ont-app/vocabulary (`put-ns-meta!`):
  https://github.com/ont-app/vocabulary/blob/master/src/ont_app/vocabulary/core.cljc
- flint: https://github.com/yetanalytics/flint
- Replicant: https://github.com/cjohansen/replicant
- Rum and React 18: https://github.com/tonsky/rum/issues/250
- React 19 upgrade guide: https://react.dev/blog/2024/04/25/react-19-upgrade-guide
- babashka/fs: https://github.com/babashka/fs
- babashka/http-client: https://github.com/babashka/http-client
- Pedestal changelog: https://github.com/pedestal/pedestal/blob/master/CHANGELOG.md
- Python curies: https://github.com/biopragmatics/curies
- Swirrl/csv2rdf: https://github.com/Swirrl/csv2rdf
- Trifid: https://github.com/zazuko/trifid
