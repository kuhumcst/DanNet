# DanNet Project Summary

## Overview
DanNet is a comprehensive WordNet implementation for the Danish language, built as an RDF-based knowledge graph. The project provides a complete lexical semantic database that represents Danish words, their meanings (synsets), and relationships between them. It follows Ontolex-lemon and Global Wordnet Association standards, making it highly interoperable with other linguistic resources.

The system includes:
- A full RDF triplestore implementation using Apache Jena
- A web application (https://wordnet.dk) with both server-side and client-side rendering
- A full-featured SPARQL query editor with CodeMirror 6, pagination, caching, and result enrichment
- Multiple export formats (RDF/Turtle, CSV, WN-LMF XML, JSON-LD, DMLex XML/JSON in Danish and English variants)
- Bootstrap system for versioning and data migrations, with on-demand dataset downloads
- Rich query capabilities via SPARQL and Clojure DSL
- Taxonomy-based synset similarity metrics exposed as custom `dnf:` SPARQL functions
- Interactive visualizations including radial relation diagrams, a zoomable hyponym sunburst, and word clouds
- An MCP (Model Context Protocol) server for AI application integration
- A ChainNet metaphor annotation pipeline producing annotation-ready Excel output

## Key Architecture Components

### Database Layer (`dk.cst.dannet.db`)
- **Apache Jena** 6 as the core RDF triplestore, used through **Theophrastus** (kuhumcst's successor to Aristotle), which gives RDF terms as Clojure data
- Support for both in-memory and persistent (TDB2) graph storage
- Automatic transaction management for persistent stores
- OWL inference support for deriving implicit relationships
- `prefix/prefixes` is the one prefix map, shared by the queries, the writes, the RDF exports and the frontend (`prefix/kw->uri`); every Theophrastus db that DanNet creates has it
- `bootstrap/->dannet` returns a map of three Theophrastus dbs: the TDB2 `:dataset`, its asserted `:base` union graph and the `:inference` db. A named graph is a view of the dataset, e.g. `(theo/named-graph dataset prefix/dn-graph)`, and Jena code takes a db's Model as `(:model db)`

### Query System (`dk.cst.dannet.db.query`)
- SPARQL queries through `theo/q`, which takes the db first: rows are maps of `?var` symbols, IRIs are keywords, language-tagged strings are `LangString`, other typed literals `Literal`, and blank nodes `_label` symbols
- The queries in `dk.cst.dannet.db.query.operation` are parsed once with the DanNet prefixes; values go in as bindings, e.g. `{'?s :dn/synset-1}`, which become a VALUES block of RDF terms
- `q/triples` matches one [s p o] pattern, with `_` for any value, e.g. `(q/triples g ['_ :wn/hypernym '_])`, returning maps of `?s`, `?p` and `?o`
- Entity expansion and blank node resolution
- Weighted synset algorithms for semantic similarity

### Web Application
- **Backend** (`dk.cst.dannet.web.service`): Pedestal-based HTTP service with SPARQL endpoint
- **Frontend**: ClojureScript SPA with Rum components — `dk.cst.dannet.web.router` wires reitit routes to page fetching and mounting, `dk.cst.dannet.web.app` holds the session state and fetch layer, `dk.cst.dannet.web.ui.model/render-opts` derives the opts a page renders from, and `dk.cst.dannet.web.ui` (+ `web.ui.*`) are the components themselves
- **Page values**: each page is one map, `{:page :title :prefs :deferred? :data}`, built by `response/->page`. The Transit response is that map (an SPA redirect is `{:redirect :replace?}`), and the HTML embeds it in a `<script id="page-data">` element, so the first page hydrates from the data the server rendered without a second request. The client's `transit` query parameter carries a format version (`app/transit-param`), to be bumped whenever the Transit format changes, since responses are cached for a day
- **Navigation state**: the scroll position of each page is kept in `history.state`; in-flight fetches are in `app/fetches`, which sets a `data-fetching` attribute on `<html>` for the loading indicator
- Progressive enhancement: works with or without JavaScript
- Content negotiation of media type and language by conneg-clj (`resources/negotiation`, `negotiation-ic`), with `?format=`, `?lang=` and the `:languages` cookie as overrides, a Vary header per response and a 406 when no format is acceptable. Entity pages also get a Link header to their HTML, Turtle and JSON-LD representations and a Content-Location (`entity-negotiation-ic`, `entity-href`)
- Internationalization support (Danish/English)
- Rate limiting for API endpoints
- Deferred loading of large semantic relations (truncate on server, fetch remainder on client)
- Full-screen visualization mode with persistent user preferences
- COR inflected forms are valid search terms: autocomplete suggests them as "lemma (inflected form)" pairs, the synset search query resolves them via `owl:sameAs`, and DanNet word entities are supplemented with the `ontolex:otherForm` forms of their linked COR words
- DanNet synsets are supplemented (↪) with sense examples, DDO deep links (`dns:source` gathered from their senses), and the English definition of their linked ILI concept — displayed instead of the Danish definition when the UI language is English

### Synset Diagrams (`dk.cst.dannet.web.ui.visualization` + `dk.cst.dannet.web.d3` + `dk.cst.dannet.web.diagram` + `dk.cst.dannet.web.hyponymy`)
- The synset section can render as a table or a diagram; when in diagram mode, a mode toggle switches between the radial diagram and up to three sunbursts, all sharing one toolbar/legend shell (`synset-diagram`, `diagram-legend`):
  - **Radial relation diagram** (`:radial`) — the existing radial layout of a synset's relations (legend with relation filtering via `radial-tree-legend` / `radial-item--*` classes; built by `d3/build-radial!`)
  - **Sunbursts** (`:sunburst` hyponyms, `:sunburst-orthogonal` ortho-hyponyms, `:sunburst-meronym` parts) — zoomable D3 partitions of the corresponding subtree, with click-to-zoom, a three-ring focus window, branch-coloured arcs, label truncation/fitting, a zoom-history breadcrumb (`hyponym-sunburst-history`), and `<title>` tooltips; all built by `d3/build-sunburst!` over `hyponym-tree`, `orthogonal-hyponym-tree` or `meronym-tree` from `opts`
- Diagram mode lives in the session at `app/diagram-mode-path` (`[:section :semantic :display :diagram-mode]`), reset to `:radial` on full page load. The D3 builders get their click actions as callbacks (`:on-navigate`, `:on-toggle-full-screen`) from `visualization/with-actions` rather than calling `web.app`, and `d3/build-once!` draws a diagram again only when its key (the data and options it depends on) changes. The pure layout code (`prepare-synset-cloud`, `radial-tree`, `glyph-width`, ...) lives in the cljc namespace `web.diagram`, so it runs in the JVM REPL too. Each sunburst radio only appears when its tree has children, and a selected sunburst falls back to the radial when its subtree is empty.
- **Relation ordering** (`dk.cst.dannet.web.ui.relations`): `group-order` is the single source of truth for the canonical display order of relation groups (taxonomic, part–whole, similarity/opposition, roles, domain, cross-link, other) and the member relations within each group. Derived helpers (`relation-order`, `relation->group`, `relation->rank`, `relation-sort-key`, `relation->class`) drive the relations page `table`, the radial diagram's group ordering (`diagram/prepare-radial-children` via `insert-theme-spacers`), and `radial-tree-legend` — which lists relations in the same order within a single `<ul>`, tagging each `<li>` with a generic group class via `relation->class` (e.g. `taxonomic-rel`, falling back to `other-rel`) so the grouping can be expressed in CSS without splitting the list. Relations not covered by any group sort alphabetically at the end. The within-group label placement in the radial diagram (size/corner optimization) is independent and unchanged.
- **Accessibility**: the sunburst SVG is `aria-hidden` (arc geometry isn't meaningfully navigable by screen readers); the table view and the zoom-history breadcrumb are the accessible alternatives. Reduced motion is honoured by checking `matchMedia("(prefers-reduced-motion: reduce)")` in JS, since the zoom is a D3-driven transition that CSS `prefers-reduced-motion` can't reach.
- **`dk.cst.dannet.web.hyponymy`**: pure, bounded hyponym-subtree construction. `hyponym-subtree` builds a nested `{:id :children}` skeleton from the inverted hyponym graph, ranking children by descendant count so the largest branches survive `:max-children`/`:max-depth`/`:max-nodes` caps; multiple inheritance is tree-ified and single-path cycles are broken. `hyponym-tree` labels and localises the skeleton (one batched label query, trimmed to a single clean lemma per arc). The inverted graph itself is assembled and cached in `web.instance`.

### SPARQL Frontend (`dk.cst.dannet.web.ui.sparql`)
- Full-featured SPARQL query editor built on CodeMirror 6 with SPARQL syntax highlighting
- Query validation (client-side and server-side), normalization, and a "Format" button
- Pagination with configurable page sizes and N+1 lookahead
- DISTINCT checkbox, animated progress bar, cached result indicators
- Result enrichment: batch label lookup for URIs via `VALUES` clauses (controlled by `enrichment` URL param)
- Inline blank node expansion in result tables (works across SSR and SPA via Transit-serializable `:blank-nodes` maps)
- JSON download links for query results
- CodeMirror integration: bracket matching, line numbers, prefix block folding, error display

### SPARQL Backend (`dk.cst.dannet.web.sparql`)
- Read-only SPARQL endpoint with query validation (read-only check, length limit, timeout); SELECT and ASK results render as HTML, transit and SPARQL JSON
- Hard timeout through `txn/with-hard-timeout` on two thread pools without queues, so a full pool rejects a query at once
- Result caching using `core.cache` with TTL, whitespace-independent cache keys via Jena's query AST
- Coalescing of concurrent identical requests
- Configurable result limits, offsets, and DISTINCT enforcement
- Support for both inference and non-inference model selection

### Similarity Metrics (`dk.cst.dannet.similarity`)
- Taxonomy-based synset similarity measures over the DanNet hypernym graph, porting the `wn` Python tool's metrics: `path`, `lch` (Leacock-Chodorow), and `wup` (Wu-Palmer); the information-content measures (`res`, `jcn`, `lin`) are stubs that throw
- Runs over a precomputed `{child #{parents}}` hypernym graph built once from the base (asserted) graph; ancestor-distance maps are memoized so scoring one synset against many is cheap. Handles multiple-inheritance DAGs partitioned by language (no DanNet↔OEWN hypernym edges). `build-hyponym-graph` inverts this into a `{parent #{children}}` map (the hyponym direction is the un-asserted inverse of `:wn/hypernym`), which feeds the hyponym sunburst
- Exposed as `dnf:path` / `dnf:lch` / `dnf:wup` SPARQL functions via `register!`, which registers them with `dk.cst.theophrastus.function` in Jena's global function registry, so any SPARQL query can call them regardless of inference mode (a nil score leaves the result unbound); the hypernym-graph context is held by `dk.cst.dannet.web.instance/hypernym-graph` (a lazy `delay`, initialised on server start) and derefed lazily on first call

### MCP Server (`mcp/`)
- Python-based Model Context Protocol server for AI application integration
- Tools: word/synset lookup, synonyms, autocomplete, SPARQL queries, namespace info, server switching
- HTTP transport mode deployed at `https://wordnet.dk/mcp`
- Caching support for query results

### ChainNet Annotation Pipeline (`dk.cst.dannet.chainnet`)
- Builds a ChainNet metaphor annotation layer on top of DanNet
- Matches metaphorical senses from the METALLM input spreadsheet against DanNet sense data
- Produces annotation-ready Excel output (via docjure/POI) with clickable URI hyperlinks, color-coded status cells, dropdown validations, and frozen headers
- Flat one-row-per-sense output format with lemma grouping and status-based sorting

### Anomaly Handling (`dk.cst.dannet.web.anomaly`)
- Exception translation for user-facing error pages using `cognitect.anomalies`
- Classifies exceptions (e.g. Jena `QueryCancelledException`, `TimeoutException`) into anomaly categories at the boundary
- Bilingual (DA/EN) user-friendly messages with retry hints and HTTP status codes
- Anomaly maps are rendered directly by the UI across SSR and SPA

### SHACL Validation (`dk.cst.dannet.db.shapes`)
- Validates the `dn:` dataset against SHACL shapes in `resources/schemas/internal/shapes/` (`base.ttl` for the asserted graph, `inferred.ttl` for the inference model, `editorial.ttl` for future write gating); all shapes use SPARQL-based targets scoped to the `dn:` namespace
- Returns violations as plain Clojure data (via `dk.cst.theophrastus.shacl`); counts are compared to a known baseline (`resources/schemas/internal/shapes-baseline.edn`), so the enforced invariant is "no regressions" rather than "zero violations"
- `validate-db`: non-fatal check of the asserted graph, run async at boot; `validate-export!`: release gate aborting RDF exports of the `dn:` model on baseline regressions; `validate-node`: targeted single-node validation intended for the future RDF Patch write pipeline (#194)
- Fixture-based tests (`test/dk/cst/dannet/db/shapes_test.clj`) run via `clojure -X:validate:test`, also executed by the GitHub Actions workflow (`.github/workflows/test.yml`)

### Bootstrap System (`dk.cst.dannet.db.bootstrap` + submodules)
- Loads previous RDF releases from the `./bootstrap` directory
- Applies version migrations and schema updates, but only when cutting a release (`to` differs from `from`)
- Generates new releases with full data validation
- Exports to multiple formats (RDF, CSV, WN-LMF, JSON-LD, DMLex)
- **`dk.cst.dannet.db.bootstrap.downloads`**: fetches bootstrap datasets on-demand — DanNet release assets via the GitHub releases API, the Open English WordNet (OEWN), the CILI interlingual index (ILI), and the PreMOn FrameNet 1.7 dump. Missing files are downloaded automatically (`ensure-bootstrap-datasets!`, `ensure-english-datasets!`, `ensure-framenet-dataset!`), so manual placement is no longer required. The release assets (dataset zips plus the synset-indegree cache) land in a version-named directory under `bootstrap/from/`; the English datasets live in the shared `bootstrap/other/english` and the PreMOn dump in `bootstrap/other/framenet`, where several editions may coexist since the edition is part of the filename.
- **`dk.cst.dannet.db.bootstrap.metadata`**: holds the DanNet dataset metadata (DCAT/lime/foaf/dc triples), the `da`/`en` `LangString` helpers, and the dataset RDF resource URIs (`<dn>`, `<dns>`, `<dnc>`, `<dds>`, `<cor>`, `<cor-sem>`, `<framenet>`). `update-metadata!` swaps old metadata for current during bootstrap.
- **`dk.cst.dannet.db.bootstrap.cor`**: converts the COR source files (COR₁ + COR.EXT TSVs and the COR.EXT link file, downloaded from ordregister.dk into `bootstrap/other/cor`) into the triples of the `cor:` graph, and parses the official `.cordiff` changelogs into a lemma-ID remap (`id-remap`). The graph was last rebuilt from source for the 2026-08-21 release and is carried forward as data since; the conversion functions remain for future COR edition bumps.
- **`dk.cst.dannet.db.bootstrap.corsem`**: converts the COR.SEM sense inventory (`cor.sem.1.0.tsv` from ordregister.dk in `bootstrap/other/cor`) into the triples of the `cor-sem:` graph: one `ontolex:LexicalSense` per row in the `cor:` namespace, linked from `cor:` words via `ontolex:sense` and to `dn:` synsets via `dns:linkedSynset` (DanNet-link) and `dns:hypernymAnchor` (hypernym anchors), carrying FrameNet frame links (`dns:frame`, pointing at the `frame:` resources of the framenet graph; only the handful of post-1.7 frames outside that graph get a minimal `frame:` resource here), sentiment (`dns:sentiment` + marl, like DDS), simplified ontological types (`dns:simpleOntologicalType`, pointing at the shared named `dnt:` ontological types, e.g. `dnt:Human-Object` — `dns:OntologicalType` bags of 1..n `dnc:` concepts), systematic polysemy patterns (`dns:polysemyPattern`), topic domains (`dc:subject`) and restriction/centrality comments. The graph was built from source for the 2026-09-21 release, which also pruned/remapped its links, derived the sense-level `dns:eqSense`/`dns:eqNearSense` correspondences to `dn:` senses, and replaced the `dn:` graph's blank ontotype bags with the same named `dnt:` types (browsable through the inverse relations `dns:ontologicalTypeOf`/`dns:simpleOntologicalTypeOf`). It is carried forward as data since (`cor-sem.zip` is a bootstrap asset); the conversion functions remain for future COR.SEM edition bumps.
- **`dk.cst.dannet.db.bootstrap.premon`**: converts the PreMOn rendition of FrameNet 1.7 (`premon-2018a-fn17-noinf.nq` in `bootstrap/other/framenet`, CC BY-SA 4.0) into the triples of the framenet graph, its own dataset at `https://wordnet.dk/framenet` imported by `bootstrap/add-framenet!`: all 1,221 frames (label, `skos:definition`, Berkeley `rdfs:seeAlso`, `owl:sameAs` to PreMOn), their 11,428 frame elements as `frame:Frame@Element` resources (typed with the four `pmofn:` status classes, linked from frames via `pmo:semRole`, carrying definitions and `pmo:abbreviation`), the seven semantic `pmofn:` frame-to-frame relations (`inheritsFrom`, `uses`, `subframeOf`, `perspectiveOn`, `precedes`, `isCausativeOf`, `isInchoativeOf`) mirrored between roles by their FER counterparts plus the excludes/requires constraints, the FE core sets (`pmofn:feCoreSet` blank nodes grouping the core roles that fill the same slot), and the 109 `pmofn:SemType` resources (suffixed `_semType`, 14 sharing their name with a frame) linked from frames and roles via `pmofn:semType`; the editorial relations and English lexical units are left out. PreMOn's lowercased IRIs are renamed using the Berkeley spellings from their labels, which exactly match the COR.SEM frame names. The `pmo`/`pmofn` ontologies are bundled in `resources/schemas/external/`.
- **`dk.cst.dannet.release`**: the `from`/`to` release versions, the upstream COR editions (`cor-version`, `cor-ext-version`, `cor-sem-version`), the PreMOn release (`premon-version`) and the OEWN edition (`oewn-version`), plus the version-named bootstrap layout derived from them (`version-dir`). `to` is `"SNAPSHOT"` while the next release's changes are in development (which is what enables `make-release-changes!`) and is set to the real version at cut time. Kept free of DanNet dependencies since it is required from both ends of the namespace graph.

### DMLex Export (`dk.cst.dannet.db.export.dmlex`)
- Converts the raw DanNet graphs (DanNet + DDS + COR + COR.SEM + OEWN equivalents) into a single DMLex 1.0 (OASIS) lexicographic resource, serialized as both XML and JSON; conversion rules in `doc/dmlex/plan.md`. The COR.SEM payload rides on the exactly matched (`dns:eqSense`) DanNet senses as label inventories (official COR.SEM sense ids, FrameNet frames, polysemy patterns, differing simple ontological types, centrality) plus the synset-level alternation relations
- Exported per language (`->resource` takes `"da"` or `"en"`): the entries stay Danish in both variants, while inventory-tag descriptions (from bilingual literals in the code and `rdfs:comment`/`rdfs:label` in the graph), the presentation config (`resources/bundled/dmlex/presentation.json`, localized per language by the DMLex browser) and `dc:language` in metadata.json follow the language; zips are named `dannet-dmlex-{da,en}.zip`. The English variant additionally supplements each sense of an unambiguously ILI-linked synset with the English CILI definition, typed `"ili"`
- Each zip is self-contained: DMLex XML + JSON, metadata.json (Dublin Core), presentation.json (display config for the DMLex browser, incl. the Apple dictionary bundle identifier), LICENSE and README.txt
- `dk.cst.dannet.db.export.dmlex-validate` validates the two files of one language variant against the DMLex schemas in `doc/dmlex/spec/` (requires the `:validate` alias)
- Browsable with the generic DMLex browser (https://github.com/kuhumcst/DMLex-browser), which also builds the Apple Dictionary bundles

### Release Statistics (`dk.cst.dannet.db.stats`)
- Compares the current release with the legacy DanNet 2.2 CSV release in `bootstrap/dannet` (the 2.2 relation names mapped onto the relations they became in 2023): size, relation inventory, connectivity (asserted relations, owl:inverseOf closure, inference-model counts, degrees), the links beyond the synset graph and each dataset's lime/void statistics
- `(export-stats! @dk.cst.dannet.web.instance/db)` writes `export/stats/<version>/stats.{edn,md,tex}`, the tables as Markdown and booktabs LaTeX

## File Structure

### Core Database & Query
```
src/main/dk/cst/dannet/
├── chainnet.clj               # ChainNet metaphor annotation pipeline (Excel in/out)
├── db.clj                     # Core database operations, model management
├── similarity.clj             # Taxonomy-based synset similarity metrics + dnf: SPARQL functions
├── db/
│   ├── bootstrap.clj          # Release bootstrapping, migration, OEWN/ILI integration
│   ├── bootstrap/
│   │   ├── cor.clj            # COR source-file conversion + changelog remap
│   │   ├── corsem.clj         # COR.SEM source-file conversion (senses, frame links)
│   │   ├── downloads.clj      # On-demand fetching of bootstrap datasets (DanNet zips, OEWN, ILI, PreMOn)
│   │   ├── metadata.clj       # Dataset metadata triples, da/en helpers
│   │   └── premon.clj         # PreMOn FrameNet 1.7 dump conversion (framenet graph)
│   ├── export/
│   │   ├── csv.clj           # CSV/CSVW export functionality
│   │   ├── rdf.clj           # RDF/Turtle export
│   │   ├── wn_lmf.clj        # WN-LMF XML export
│   │   ├── json_ld.clj       # JSON-LD export
│   │   ├── dmlex.clj         # DMLex 1.0 (OASIS) export, XML + JSON, da/en variants
│   │   └── dmlex_validate.clj # DMLex schema validation (needs :validate alias)
│   ├── query.clj             # Main query interface and navigation
│   ├── query/
│   │   └── operation.clj     # SPARQL queries, parsed once with the DanNet prefixes
│   ├── search.clj            # Text search and indexing
│   ├── shapes.clj            # SHACL validation (boot check, export gate, node validation)
│   └── stats.clj             # Release statistics vs. DanNet 2.2 (paper tables as EDN/Markdown/LaTeX)
├── hash.clj                   # Content hashing and caching
├── prefix.cljc               # RDF namespace prefix management
└── shared.cljc               # Shared utilities between CLJ/CLJS
```

### Web Application
```
src/main/dk/cst/dannet/web/
├── service.clj               # Pedestal HTTP service and routing
├── resources.clj             # Resource handlers (interceptors), content negotiation, entity truncation; attaches :hyponym-tree to synset entities
├── instance.clj              # The live database and its cached derived structures (hypernym/hyponym graphs, search index)
├── response.clj              # Response bodies per media type, page values, request languages
├── rate_limit.clj            # Rate limiting functionality
├── sparql.clj                # SPARQL endpoint: validation, execution, result caching
├── hyponymy.clj              # Pure bounded hyponym-subtree construction for the sunburst
├── anomaly.cljc              # Exception translation to cognitect.anomalies (bilingual error pages)
├── router.cljs               # SPA entry point: routes, navigation callback, Rum mounting
├── app.cljc                  # Client-side session state, navigation, fetch layer
├── d3.cljs                   # D3 visualization components (radial relation diagram + hyponym sunburst)
├── diagram.cljc              # Pure diagram layout: word cloud sizes, label widths, radial order and spacing
├── ui.cljc                   # Core Rum UI components
├── ui/
│   ├── sparql.cljc           # SPARQL query editor UI, result table, pagination
│   ├── codemirror.cljs       # CodeMirror 6 interop (SPARQL syntax, folding, errors)
│   ├── form.cljc             # Form utilities (submit, validation, autofocus)
│   ├── visualization.cljc    # Synset diagrams (radial + sunburst, mode toggle, legend), word clouds, ancestry display
│   ├── search.cljc           # Search form components
│   ├── search/
│   │   └── aria.cljs         # ARIA-compliant combobox keyboard navigation
│   ├── catalog.cljc          # Catalog resources display (schemas and datasets)
│   ├── table.cljc            # Table components
│   ├── markdown.cljc         # Markdown pages to Hiccup (server only), page titles
│   ├── rdf.cljc              # RDF display components
│   ├── relations.cljc        # Synset relation groups (canonical group-order) + relations table
│   ├── entity.cljc           # Entity display, full-screen mode
│   ├── error.cljc            # Error boundaries and try-render macros
│   ├── model.cljc            # render-opts: page data + user state + derived values
│   └── page.cljc             # Page layout components
├── section.cljc              # Page sections and layouts
└── i18n.cljc                 # Internationalization strings
```

### MCP Server
```
mcp/
├── README.md                  # MCP server documentation
├── src/                       # Python MCP server source
└── ...                        # Configuration, deployment files
```

### Resources & Schemas
```
resources/
├── schemas/
│   ├── internal/
│   │   ├── dannet-schema.ttl      # DanNet RDF schema
│   │   ├── dannet-concepts.ttl    # EuroWordNet concepts
│   │   ├── shapes/                # SHACL shapes (base/inferred/editorial)
│   │   └── shapes-baseline.edn    # Accepted SHACL violation counts by shape
│   └── external/               # External ontologies (Ontolex, etc.)
└── public/                     # Static web assets
```

### Documentation Pages
All pages exist in both Danish (-da) and English (-en) variants. Intro pages are split across four personas targeting different audiences. The server parses each page into Hiccup (one per language) and sends that to the browser, which never parses Markdown: markdown-it in the browser and commonmark-java on the server disagree, so parsing on both sides breaks hydration.
```
pages/
├── frontpage-{da,en}.md       # Landing page content
├── intro-layman-{da,en}.md    # Introduction for general audience
├── intro-developer-{da,en}.md # Introduction for software developers
├── intro-linguist-{da,en}.md  # Introduction for linguists
├── intro-rdf-{da,en}.md       # Introduction for RDF/semantic web users
├── sparql-{da,en}.md          # Comprehensive SPARQL guide
├── mcp-{da,en}.md             # MCP server documentation
├── queries-en.md              # Query language documentation
├── downloads-{da,en}.md       # Download links and instructions
├── releases-{da,en}.md        # Release history
├── rationale-en.md            # Design rationale
├── label-rewrite-en.md        # Label rewrite documentation
└── privacy-{da,en}.md         # Privacy policy
```

## Dependencies

### Core RDF/Database
- **Theophrastus** (`dk.cst/theophrastus`, a Git dependency on kuhumcst/theophrastus): Apache Jena 6.2 (ARQ, TDB2, SHACL) with RDF terms as Clojure data; its `dk.cst.theophrastus.term` namespace (`LangString`, `Literal`) also runs in ClojureScript, so the frontend shares the server's terms
- **donatello** (1.3.6): Turtle writer for the RDF exports (`db.export.rdf`)

### Web Framework
- **Pedestal** (0.8.2): HTTP service and routing (still through `io.pedestal.http`, deprecated since 0.8.0)
- **conneg-clj** (0.1.0): proactive content negotiation of media type and language, with Vary and 406
- **Rum**: React-like UI components (custom fork)
- **reitit-frontend** (0.11.0): Client-side routing for SPA
- **Shadow-cljs** (3.4.11): ClojureScript compilation (`:frontend` alias)
- **lambdaisland/fetch** (1.5.83): Client-side HTTP requests
- **CodeMirror 6**: SPARQL editor (via `codemirror`, `@codemirror/view`, `@codemirror/state`, `codemirror-lang-sparql`)

### Data Processing
- **clj-yaml** (1.0.29): YAML configuration parsing
- **docjure** (1.22.0): Excel file processing (ChainNet pipeline, POI styling)
- **clj-file-zip**: Release zip handling for bootstrap
- **data.csv** (1.1.1): CSV parsing and generation
- **data.json** (2.5.2): JSON parsing and generation (incl. GitHub releases API responses)
- **data.xml** (0.2.0-alpha11): XML processing for WN-LMF
- **nextjournal/markdown** (0.7.225): Markdown parsing and rendering

### Caching
- **core.cache**: TTL-based SPARQL result caching with AST-normalized cache keys
- **core.memoize** (1.2.281): Function memoization

### Utilities
- **better-cond** (2.1.5): Enhanced conditional macros
- **ham-fisted** (3.030): High-performance collections
- **cognitect/anomalies** (0.1.12): Error categorization for exception translation
- **transit-clj** (1.1.357): Transit serialization on the server; the browser uses transit-cljs (via shadow-cljs)
- **Telemere** (1.2.1): Logging and error reporting

## Development Workflow

### Setup
1. Install JVM (Java 24+) and Clojure CLI tools
2. Clone the repository
3. Bootstrap datasets are downloaded on-demand from GitHub/OEWN/CILI when missing (see `dk.cst.dannet.db.bootstrap.downloads`); manual placement in `./bootstrap` is only needed for offline or custom data. The base release that gets fetched is determined by `from` in `dk.cst.dannet.release`.

### REPL Development
```clojure
;; Start REPL with all dependencies
clj -A:nrepl

;; In REPL, load user namespace
(require '[user :refer :all])

;; Start development server
(dk.cst.dannet.web.service/start-dev)
```

### Frontend Development
```bash
# Install Shadow-cljs dependencies
npm install

# Start Shadow-cljs watcher
npx shadow-cljs watch app

# Access dev server at http://localhost:7777
```

`dk.cst.dannet.web.snapshot` (in `src/dev`) writes the HTML and Transit of a fixed list of pages, in Danish and English, to a directory; `diff -r` on two snapshots shows what a UI change did to the output.

### Browser REPL Debugging (shadow-cljs)

Frontend behaviour can be debugged directly in a live browser tab by hooking
into the shadow-cljs nREPL. This is the tool of choice for bugs that only
manifest client-side — hydration errors invisible to SSR, CLJS/CLJ behavioural
differences (e.g. keyword hashing, `subs` on non-strings), inspecting transit
payloads as the client actually sees them, and pixel-level CSS work.

```clojure
;; 1. Connect to the shadow-cljs nREPL (port in .shadow-cljs/nrepl.port;
;;    it runs alongside the regular clj nREPL on 7888).

;; 2. Confirm a browser tab is connected, then switch the session to CLJS:
(shadow.cljs.devtools.api/repl-runtimes :app)
(shadow.cljs.devtools.api/repl :app)      ; :cljs/quit to leave CLJS mode

;; 3. Everything now evaluates inside the live browser tab:

;; Inspect client state, e.g. the current page data:
(:data @dk.cst.dannet.web.router/current-page)

;; Drive navigation to reproduce a bug on a specific page:
(dk.cst.dannet.web.app/navigate-to "/dannet/data/synset-52")

;; Query and measure the DOM, incl. glyph-level baseline comparisons:
(.querySelector js/document "tr[property=\"dns:inherited\"]")
;; (use Range/getClientRects on single characters to compare baselines)

;; Instrument a suspect function non-invasively; capture args/stacks in an
;; atom that can be deref'ed from the REPL, then restore the original:
(defonce orig-f some.ns/suspect-fn)
(defonce captured (atom []))
(set! some.ns/suspect-fn (fn [x]
                           (swap! captured conj [(pr-str x) (.-stack (js/Error.))])
                           (orig-f x)))

;; Try CSS candidates by injecting a temporary <style> and re-measuring,
;; before committing anything to main.css:
(let [style (.createElement js/document "style")]
  (set! (.-id style) "exp-style")
  (set! (.-textContent style) "span.inheritance sub { line-height: 0; }")
  (.appendChild (.-head js/document) style))

;; Force a stylesheet reload after editing main.css (cache busting):
(doseq [link (array-seq (.querySelectorAll js/document "link[rel=stylesheet]"))]
  (set! (.-href link) (str (first (clojure.string/split (.-href link) #"\?"))
                           "?v=" (js/Date.now))))
```

Notes:
- This drives the developer's actual browser tab — navigation and re-renders
  are visible, so coordinate before jumping around.
- Always clean up: restore `set!`-instrumented functions and remove injected
  `<style>` elements when done.
- The shadow watcher hot-reloads edited `.cljs`/`.cljc` files automatically
  (allow a few seconds before re-testing); backend `.clj` changes still need
  a `(require ... :reload)` in the *clj* REPL.

### Running Tests
```bash
# Run Clojure tests. The :validate alias is necessary for the DMLex tests.
clojure -X:validate:test

# Run ClojureScript tests
npx shadow-cljs compile test
```

### Building Releases
```clojure
;; In REPL, bootstrap from previous release (missing datasets are fetched automatically)
(require '[dk.cst.dannet.db.bootstrap :as bootstrap])
(bootstrap/bootstrap dataset)

;; Manually fetch bootstrap datasets if needed
(require '[dk.cst.dannet.db.bootstrap.downloads :as downloads])
(downloads/fetch-bootstrap-datasets!)                  ; latest release
(downloads/fetch-bootstrap-datasets! :version "v2024-08-09")
(downloads/ensure-english-datasets!)                   ; OEWN + ILI

;; Export to various formats
(require '[dk.cst.dannet.db.export.rdf :as rdf-export])
(rdf-export/export-rdf! @dk.cst.dannet.web.instance/db)              ; into export/rdf/
```

## Conventions and Patterns

### RDF URI Structure
- Data instances: `https://wordnet.dk/dannet/data/` (prefix: `dn`)
- Concepts: `https://wordnet.dk/dannet/concepts/` (prefix: `dnc`)
- Schema: `https://wordnet.dk/dannet/schema/` (prefix: `dns`)
- Dataset RDF resource URIs (`<dn>`, `<dns>`, `<dnc>`, `<dds>`, `<cor>`, `<cor-sem>`, `<framenet>`) are defined centrally in `dk.cst.dannet.db.bootstrap.metadata`

### Release / Version Tracking
- `dk.cst.dannet.release` is the source of truth for versions:
  - `from` — the previous formal release bootstrapped on top of; the files in `bootstrap/from/<from>` must match it, and it determines which release `downloads/fetch-bootstrap-datasets!` pulls from GitHub. Because `->dannet` hashes the input file paths, the version in the path is also what keeps each bootstrap target's database distinct, so several targets can coexist across branches without rebuilding
  - `to` — the version being produced; `"SNAPSHOT"` while the next release's changes are in development, and set to the real version when the release is cut
- Both are referenced directly by the exporters (WN-LMF, JSON-LD) and by the dataset metadata
- While `to` equals `from` the bootstrap reproduces the release it was bootstrapped from: `make-release-changes!` is skipped entirely, so a development database is faithful to its published version and can be shipped to production as-is. Setting `to` is what puts the bootstrap into release-producing mode

### Namespace Organization
- `dk.cst.dannet.*` - Core database and query functionality
- `dk.cst.dannet.db.*` - Database operations and exports
- `dk.cst.dannet.db.bootstrap.*` - Bootstrap submodules (downloads, metadata)
- `dk.cst.dannet.web.*` - Web application components
- `dk.cst.dannet.web.ui.*` - Modular UI components

### Transaction Handling
- Automatic transaction wrapping for TDB operations: each `theo/q` reads, and each Theophrastus write (`transact!`, `retract!`, `read!`) writes, in a transaction of its own
- Use `txn/with-transaction` or `txn/with-read-transaction` from `dk.cst.theophrastus.txn` to group several calls in one transaction; a transaction inside another on the same dataset joins it, and a write inside `txn/with-transaction` promotes it
- `theo/retract!` takes one [s p o] pattern or a coll of them and removes them all in one write transaction

### Error Handling
- `try-render` in `dk.cst.dannet.web.ui.error` (a React error boundary plus try/catch) wraps the page, each entity section and each diagram
- `try-render-with` is a plain try/catch for functions that build hiccup, e.g. `rdf/transform-val`
- `try-static-render` for imperative DOM error handling
- Exception translation at the boundary via `dk.cst.dannet.web.anomaly`: Java exceptions are mapped to `cognitect.anomalies` maps with bilingual messages, retry hints, and HTTP status codes
- Database operations return nil on failure
- Web handlers use Pedestal interceptors for error handling
- Bootstrap validates data before committing changes
- All caught errors logged via Telemere

### SPARQL Result Caching
- Backend uses `core.cache` with TTL expiry for SPARQL results
- Cache keys are normalized via Jena's query AST (whitespace-independent)
- Concurrent identical requests are coalesced (useful for classroom scenarios)
- Cache indicators shown in the frontend UI

## Extension Points

### Adding New Export Formats
1. Create namespace in `dk.cst.dannet.db.export/`
2. Implement export function taking dataset and output path
3. Register in bootstrap process

### Custom Query Operations
1. Add operation in `dk.cst.dannet.db.query.operation/`
2. Implement transformation function
3. Register with query processor

### New Web Endpoints
1. Add handler in `dk.cst.dannet.web.resources`
2. Register route in `dk.cst.dannet.web.service/routes`
3. Start the route's interceptors with `negotiation-ic`, or `media-type-negotiation-ic` when the content doesn't depend on the language

### Adding UI Components
1. Create namespace in `dk.cst.dannet.web.ui/`
2. Implement Rum components with shared utilities
3. Import in `dk.cst.dannet.web.ui` or relevant sections

### Schema Extensions
1. Add definitions to `resources/schemas/internal/dannet-schema.ttl`
2. Update bootstrap to handle new properties
3. Extend query operations as needed

### Adding MCP Server Tools
1. Add tool definition in `mcp/src/`
2. Implement handler with SPARQL queries or API calls
3. Register in the MCP server's tool list

### Adding/Updating Bootstrap Datasets
1. Add the dataset filename to `bootstrap-files` in `dk.cst.dannet.db.bootstrap.downloads` (if it ships as a DanNet release asset)
2. Or add a dedicated `ensure-*!` fetcher for externally hosted datasets (cf. `ensure-english-datasets!`)
3. Update `from`/`to` in `dk.cst.dannet.release` when cutting a new base/target version (set `to` first; release changes are skipped while the two are equal)

## Integration Examples

### Python Integration (via WN-LMF)
```python
import wn
wn.add("dannet-wn-lmf.xml.gz")

for synset in wn.synsets('kage'):
    print(f"{synset.lexfile()}: {synset.definition()}")
```

### MCP Integration
The MCP server at `https://wordnet.dk/mcp` can be used by AI applications (e.g. Claude) to query DanNet. Tools include word lookup, synset info, synonyms, autocomplete, SPARQL queries, and server switching.

### SPARQL Query Example
```sparql
PREFIX dn: <https://wordnet.dk/dannet/data/>
PREFIX ontolex: <http://www.w3.org/ns/lemon/ontolex#>

SELECT ?word ?sense ?synset
WHERE {
  ?entry ontolex:canonicalForm/ontolex:writtenRep "kage"@da .
  ?entry ontolex:sense ?sense .
  ?sense ontolex:isLexicalizedSenseOf ?synset .
}
```

### Clojure Query Example
```clojure
(require '[dk.cst.theophrastus :as theo]
         '[dk.cst.theophrastus.term :as term])

;; Find all senses of "kage"
(theo/q (:inference @dk.cst.dannet.web.instance/db)
        "SELECT ?entry ?sense ?synset
         WHERE {
           ?entry ontolex:canonicalForm/ontolex:writtenRep ?rep ;
                  ontolex:sense ?sense .
           ?sense ontolex:isLexicalizedSenseOf ?synset .
         }"
        {'?rep (term/->LangString "kage" "da")})
```

## Performance Considerations

- **TDB2** recommended for production (better performance, required transactions)
- Inference can be expensive - use `InfGraph` judiciously
- Memoization used for expensive computations (synset weights, sense labels)
- SPARQL result caching with TTL and AST-normalized keys reduces redundant query execution
- Client-side caching via Transit+JSON for SPA mode
- Batch operations preferred in bootstrap process
- Synset-indegree cache is fetched from the release rather than recomputed (regeneration is slow)
- Deferred loading of large semantic relations - synsets with many hyponyms/hypernyms are truncated server-side; clients fetch the remainder on demand
- Browser payloads (HTML/Transit) are display-trimmed: the `:entities` label map only covers displayed resources (deferred responses carry the overflow labels, merged client-side) and `:inferred` is reduced to its relation keys; negotiated RDF formats keep full data
- Entity labels are fetched in one batched VALUES query per entity rather than OPTIONAL-joined onto every triple
- The expanded-entity cache is pre-warmed at boot for the synsets with most hyponyms (`instance/warm-entity-cache!`)
- Caddy compresses `application/transit+json` explicitly (not part of its default gzip matcher)
- Component modularization improves rendering performance

## Related Documentation

- [README.md](/README.md) - General project information
- [doc/web.md](/doc/web.md) - Detailed web application documentation
- [pages/sparql-en.md](/pages/sparql-en.md) - Comprehensive SPARQL guide with examples
- [pages/queries-en.md](/pages/queries-en.md) - Query language documentation
- [pages/rationale-en.md](/pages/rationale-en.md) - Design rationale
- [mcp/README.md](/mcp/README.md) - MCP server documentation
- [DanNet Schema](/resources/schemas/internal/dannet-schema.ttl) - RDF schema definition

## Special Instructions for AI/LLM Assistants

### REPL Interaction Guidelines
- **Shadow-cljs**: Don't attempt to start shadow-cljs - the developer will handle this manually
- **Code Evaluation**: Keep REPL evaluations short and focused - test one specific aspect at a time rather than exhaustive testing
- **Code Style**: When writing or rewriting Clojure code, match the comment density of the surrounding code - observe the existing ratio of comments to code in nearby functions

### Working with This Codebase
- The project uses Apache Jena for RDF operations - transactions are automatic for TDB
- The web app works both as SPA and traditional server-rendered HTML
- Bootstrap is run once per version - subsequent work is query-only until the next version bootstrap
- Bootstrap datasets are downloaded on-demand (`dk.cst.dannet.db.bootstrap.downloads`); the base release is set via `from` in `dk.cst.dannet.release`
- A database gets automatically bootstrapped and/or initialised when the backend in `dk.cst.dannet.web.service` is launched (by calling the `restart` function during development)
- The SPARQL editor frontend is in `ui/sparql.cljc` with CodeMirror interop in `ui/codemirror.cljs`
- SPARQL backend logic (validation, execution, caching) is in `web/sparql.clj`
