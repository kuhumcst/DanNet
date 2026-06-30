# DanNet Project Summary

## Overview
DanNet is a comprehensive WordNet implementation for the Danish language, built as an RDF-based knowledge graph. The project provides a complete lexical semantic database that represents Danish words, their meanings (synsets), and relationships between them. It follows Ontolex-lemon and Global Wordnet Association standards, making it highly interoperable with other linguistic resources.

The system includes:
- A full RDF triplestore implementation using Apache Jena
- A web application (https://wordnet.dk) with both server-side and client-side rendering
- A full-featured SPARQL query editor with CodeMirror 6, pagination, caching, and result enrichment
- Multiple export formats (RDF/Turtle, CSV, WN-LMF XML, JSON-LD)
- Bootstrap system for versioning and data migrations, with on-demand dataset downloads
- Rich query capabilities via SPARQL and Clojure DSL
- Taxonomy-based synset similarity metrics exposed as custom `dnf:` SPARQL functions
- Interactive visualizations including radial relation diagrams, a zoomable hyponym sunburst, and word clouds
- An MCP (Model Context Protocol) server for AI application integration
- A ChainNet metaphor annotation pipeline producing annotation-ready Excel output

## Key Architecture Components

### Database Layer (`dk.cst.dannet.db`)
- **Apache Jena** as the core RDF triplestore
- Support for both in-memory and persistent (TDB/TDB2) graph storage
- Automatic transaction management for persistent stores
- OWL inference support for deriving implicit relationships

### Query System (`dk.cst.dannet.db.query`)
- Multiple query methods: SPARQL, SPARQL Algebra, Aristotle DSL
- Entity expansion and blank node resolution
- Weighted synset algorithms for semantic similarity

### Web Application
- **Backend** (`dk.cst.dannet.web.service`): Pedestal-based HTTP service with SPARQL endpoint
- **Frontend** (`dk.cst.dannet.web.client`): ClojureScript SPA with Rum components
- Progressive enhancement: works with or without JavaScript
- Content negotiation for multiple data formats (HTML, JSON, RDF, etc.)
- Internationalization support (Danish/English)
- Rate limiting for API endpoints
- Deferred loading of large semantic relations (truncate on server, fetch remainder on client)
- Full-screen visualization mode with persistent user preferences

### Synset Diagrams (`dk.cst.dannet.web.ui.visualization` + `dk.cst.dannet.web.d3` + `dk.cst.dannet.web.hyponymy`)
- The synset section can render as a table or a diagram; when in diagram mode, a mode toggle switches between two D3 visualizations sharing one toolbar/legend shell (`synset-diagram`, `diagram-legend`):
  - **Radial relation diagram** — the existing radial layout of a synset's relations (legend with relation filtering via `radial-tree-legend` / `radial-item--*` classes; built by `d3/build-radial!`)
  - **Hyponym sunburst** — a zoomable D3 partition (sunburst) of the synset's hyponym subtree, with click-to-zoom, a three-ring focus window, branch-coloured arcs, label truncation/fitting, a zoom-history breadcrumb (`hyponym-sunburst-history`), and `<title>` tooltips; built by `d3/build-sunburst!`
- Diagram mode lives in shared state at `shared/diagram-mode-path` (`[:section section/semantic-title :display :diagram-mode]`, `:radial` or `:sunburst`), reset to `:radial` on full page load. The sunburst falls back to the radial when a (non-synset) entity has no subtree.
- **Accessibility**: the sunburst SVG is `aria-hidden` (arc geometry isn't meaningfully navigable by screen readers); the table view and the zoom-history breadcrumb are the accessible alternatives. Reduced motion is honoured by checking `matchMedia("(prefers-reduced-motion: reduce)")` in JS, since the zoom is a D3-driven transition that CSS `prefers-reduced-motion` can't reach.
- **`dk.cst.dannet.web.hyponymy`**: pure, bounded hyponym-subtree construction. `hyponym-subtree` builds a nested `{:id :children}` skeleton from the inverted hyponym graph, ranking children by descendant count so the largest branches survive `:max-children`/`:max-depth`/`:max-nodes` caps; multiple inheritance is tree-ified and single-path cycles are broken. `hyponym-tree` labels and localises the skeleton (one batched label query, trimmed to a single clean lemma per arc). The inverted graph itself is assembled and cached in `web/resources` (see below).

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
- Read-only SPARQL endpoint with query validation (read-only check, length limit, timeout)
- Result caching using `core.cache` with TTL, whitespace-independent cache keys via Jena's query AST
- Coalescing of concurrent identical requests
- Configurable result limits, offsets, and DISTINCT enforcement
- Support for both inference and non-inference model selection

### Similarity Metrics (`dk.cst.dannet.similarity`)
- Taxonomy-based synset similarity measures over the DanNet hypernym graph, porting the `wn` Python tool's metrics: `path`, `lch` (Leacock-Chodorow), and `wup` (Wu-Palmer), plus information-content measures (`res`, `jcn`, `lin`)
- Runs over a precomputed `{child #{parents}}` hypernym graph built once from the base (asserted) graph; ancestor-distance maps are memoized so scoring one synset against many is cheap. Handles multiple-inheritance DAGs partitioned by language (no DanNet↔OEWN hypernym edges). `build-hyponym-graph` inverts this into a `{parent #{children}}` map (the hyponym direction is the un-asserted inverse of `:wn/hypernym`), which feeds the hyponym sunburst
- Exposed as `dnf:path` / `dnf:lch` / `dnf:wup` SPARQL functions via `register!`; the hypernym-graph context is held by `dk.cst.dannet.web.resources/hypernym-graph` (a lazy `delay`, initialised on server start) and derefed lazily on first call
- **`dk.cst.dannet.db.query.function`**: generic ARQ plumbing — `->function` lifts a plain `(ctx & args -> double|nil)` fn into a `FunctionFactory` (nil result ⇒ unbound), and `register-functions!` adds them to the global `FunctionRegistry` under a chosen RDF namespace, so any SPARQL query can call them regardless of inference mode

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

### Bootstrap System (`dk.cst.dannet.db.bootstrap` + submodules)
- Loads previous RDF releases from the `./bootstrap` directory
- Applies version migrations and schema updates
- Generates new releases with full data validation
- Exports to multiple formats (RDF, CSV, WN-LMF, JSON-LD)
- **`dk.cst.dannet.db.bootstrap.downloads`**: fetches bootstrap datasets on-demand — DanNet release zips via the GitHub releases API, the Open English WordNet (OEWN), and the CILI interlingual index (ILI). Missing files are downloaded automatically (`ensure-bootstrap-datasets!`, `ensure-english-datasets!`, `ensure-synset-indegrees!`), so manual placement is no longer required. The synset-indegree cache lands in `db/` (next to TDB2 data), the dataset zips in `bootstrap/latest`.
- **`dk.cst.dannet.db.bootstrap.metadata`**: holds the DanNet dataset metadata (DCAT/lime/foaf/dc triples), the `da`/`en` `LangStr` helpers, dataset RDF resource URIs (`<dn>`, `<dns>`, `<dnc>`, `<dds>`, `<cor>`), and the single `release` map (`:from`/`:to`) that drives release tracking. `update-metadata!` swaps old metadata for current during bootstrap.

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
│   │   ├── downloads.clj      # On-demand fetching of bootstrap datasets (DanNet zips, OEWN, ILI)
│   │   └── metadata.clj       # Dataset metadata triples, da/en helpers, release map (:from/:to)
│   ├── export/
│   │   ├── csv.clj           # CSV/CSVW export functionality
│   │   ├── rdf.clj           # RDF/Turtle export
│   │   ├── wn_lmf.clj        # WN-LMF XML export
│   │   └── json_ld.clj       # JSON-LD export
│   ├── query.clj             # Main query interface and navigation
│   ├── query/
│   │   ├── operation.clj     # Query operations and transformations
│   │   └── function.clj      # Generic ARQ custom SPARQL function registry plumbing
│   ├── search.clj            # Text search and indexing
│   └── transaction.clj       # Transaction management utilities
├── hash.clj                   # Content hashing and caching
├── prefix.cljc               # RDF namespace prefix management
└── shared.cljc               # Shared utilities between CLJ/CLJS
```

### Web Application
```
src/main/dk/cst/dannet/web/
├── service.clj               # Pedestal HTTP service and routing
├── resources.clj             # Resource handlers, content negotiation, entity truncation; caches the hypernym/hyponym graphs and attaches :hyponym-tree to synset entities
├── rate_limit.clj            # Rate limiting functionality
├── sparql.clj                # SPARQL endpoint: validation, execution, result caching
├── hyponymy.clj              # Pure bounded hyponym-subtree construction for the sunburst
├── anomaly.cljc              # Exception translation to cognitect.anomalies (bilingual error pages)
├── client.cljs               # ClojureScript SPA entry point
├── d3.cljs                   # D3 visualization components (radial relation diagram + hyponym sunburst)
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
│   ├── markdown.cljc         # Markdown rendering components
│   ├── rdf.cljc              # RDF display components
│   ├── entity.cljc           # Entity display, full-screen mode
│   ├── error.cljc            # Error boundaries and try-render macros
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
│   │   └── dannet-concepts.ttl    # EuroWordNet concepts
│   └── external/               # External ontologies (Ontolex, etc.)
└── public/                     # Static web assets
```

### Documentation Pages
All pages exist in both Danish (-da) and English (-en) variants. Intro pages are split across four personas targeting different audiences.
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
- **Apache Jena** (via `aristotle`, `igraph-jena`): RDF triplestore and SPARQL
- **ont-app/vocabulary**: RDF vocabulary management (incl. `lstr`/`->LangStr` for language-tagged strings)
- **donatello**: Graph algorithms and traversal

### Web Framework
- **Pedestal** (0.7.2): HTTP service and routing
- **Rum**: React-like UI components (custom fork)
- **Reitit** (0.9.1): Client-side routing for SPA
- **Shadow-cljs** (3.2.0): ClojureScript compilation (`:frontend` alias)
- **lambdaisland/fetch**: Client-side HTTP requests (custom fork)
- **CodeMirror 6**: SPARQL editor (via `codemirror`, `@codemirror/view`, `@codemirror/state`, `codemirror-lang-sparql`)

### Data Processing
- **clj-yaml** (1.0.29): YAML configuration parsing
- **docjure** (1.22.0): Excel file processing (ChainNet pipeline, POI styling)
- **clj-file-zip**: Release zip handling for bootstrap
- **data.csv** (1.1.0): CSV parsing and generation
- **data.json** (2.5.1): JSON parsing and generation (incl. GitHub releases API responses)
- **data.xml** (0.2.0-alpha9): XML processing for WN-LMF
- **nextjournal/markdown** (0.7.189): Markdown parsing and rendering
- **tightly-packed-trie**: Efficient trie data structures

### Caching
- **core.cache**: TTL-based SPARQL result caching with AST-normalized cache keys
- **core.memoize** (1.1.266): Function memoization

### Utilities
- **better-cond** (2.1.5): Enhanced conditional macros
- **ham-fisted** (2.031): High-performance collections
- **cognitect/anomalies** (0.1.12): Error categorization for exception translation
- **transito**: Transit serialization (CLJ/CLJS)
- **thi.ng/color** (1.5.1): Color manipulation for visualizations
- **Telemere** (1.1.0): Logging and error reporting

## Development Workflow

### Setup
1. Install JVM (Java 24+) and Clojure CLI tools
2. Clone the repository
3. Bootstrap datasets are downloaded on-demand from GitHub/OEWN/CILI when missing (see `dk.cst.dannet.db.bootstrap.downloads`); manual placement in `./bootstrap` is only needed for offline or custom data. The base release that gets fetched is determined by `:from` in the `release` map in `dk.cst.dannet.db.bootstrap.metadata`.

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

### Running Tests
```bash
# Run Clojure tests
clj -A:test

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
(rdf-export/save-rdf-files! dataset "release/")
```

## Conventions and Patterns

### RDF URI Structure
- Data instances: `https://wordnet.dk/dannet/data/` (prefix: `dn`)
- Concepts: `https://wordnet.dk/dannet/concepts/` (prefix: `dnc`)
- Schema: `https://wordnet.dk/dannet/schema/` (prefix: `dns`)
- Dataset RDF resource URIs (`<dn>`, `<dns>`, `<dnc>`, `<dds>`, `<cor>`) are defined centrally in `dk.cst.dannet.db.bootstrap.metadata`

### Release / Version Tracking
- A single `release` map in `dk.cst.dannet.db.bootstrap.metadata` is the source of truth for versions:
  - `:from` — the previous formal release bootstrapped on top of; the zip files in `bootstrap/latest` must match it, and it determines which release `downloads/fetch-bootstrap-datasets!` pulls from GitHub
  - `:to` — the version being produced; stays `"SNAPSHOT"` throughout development and is set to a real version only when a release is cut
- `bootstrap-base-release` and `new-release` are derived from this map and referenced by exporters (WN-LMF, JSON-LD) and dataset metadata

### Namespace Organization
- `dk.cst.dannet.*` - Core database and query functionality
- `dk.cst.dannet.db.*` - Database operations and exports
- `dk.cst.dannet.db.bootstrap.*` - Bootstrap submodules (downloads, metadata)
- `dk.cst.dannet.web.*` - Web application components
- `dk.cst.dannet.web.ui.*` - Modular UI components

### Transaction Handling
- Automatic transaction wrapping for TDB operations
- Use `dk.cst.dannet.transaction/transact` for explicit transactions
- Read operations are implicitly transactional

### Error Handling
- React error boundaries (CLJS) via `error-boundary-mixin` in `dk.cst.dannet.web.ui.error`
- `try-render` and `try-render-with` macros for wrapping component renders
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
3. Implement content negotiation if needed

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
3. Update the `release` map in `dk.cst.dannet.db.bootstrap.metadata` when cutting a new base/target version

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
(require '[dk.cst.dannet.db.query :as q])

;; Find all senses of "kage"
(q/run graph
  '[:bgp
    [?entry :ontolex/canonicalForm ?form]
    [?form :ontolex/writtenRep "kage"@da]
    [?entry :ontolex/sense ?sense]
    [?sense :ontolex/isLexicalizedSenseOf ?synset]])
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
- Bootstrap datasets are downloaded on-demand (`dk.cst.dannet.db.bootstrap.downloads`); the base release is set via the `release` map's `:from` in `dk.cst.dannet.db.bootstrap.metadata`
- A database gets automatically bootstrapped and/or initialised when the backend in `dk.cst.dannet.web.service` is launched (by calling the `restart` function during development)
- The SPARQL editor frontend is in `ui/sparql.cljc` with CodeMirror interop in `ui/codemirror.cljs`
- SPARQL backend logic (validation, execution, caching) is in `web/sparql.clj`
