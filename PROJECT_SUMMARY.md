# DanNet Project Summary

## Overview
DanNet is a comprehensive WordNet implementation for the Danish language, built as an RDF-based knowledge graph. The project provides a complete lexical semantic database that represents Danish words, their meanings (synsets), and relationships between them. It follows Ontolex-lemon and Global Wordnet Association standards, making it highly interoperable with other linguistic resources.

The system includes:
- A full RDF triplestore implementation using Apache Jena
- A web application (https://wordnet.dk) with both server-side and client-side rendering
- A full-featured SPARQL query editor with CodeMirror 6, pagination, caching, and result enrichment
- Multiple export formats (RDF/Turtle, CSV, WN-LMF XML, JSON-LD)
- Bootstrap system for versioning and data migrations
- Rich query capabilities via SPARQL and Clojure DSL
- Interactive visualizations including radial tree diagrams and word clouds
- An MCP (Model Context Protocol) server for AI application integration

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

### MCP Server (`mcp/`)
- Python-based Model Context Protocol server for AI application integration
- Tools: word/synset lookup, synonyms, autocomplete, SPARQL queries, namespace info, server switching
- HTTP transport mode deployed at `https://wordnet.dk/mcp`
- Caching support for query results

### Bootstrap System (`dk.cst.dannet.db.bootstrap`)
- Loads previous RDF releases from `./bootstrap` directory
- Applies version migrations and schema updates
- Generates new releases with full data validation
- Exports to multiple formats (RDF, CSV, WN-LMF, JSON-LD)

## File Structure

### Core Database & Query
```
src/main/dk/cst/dannet/
├── db.clj                     # Core database operations, model management
├── db/
│   ├── bootstrap.clj          # Release bootstrapping and migration
│   ├── export/
│   │   ├── csv.clj           # CSV/CSVW export functionality
│   │   ├── rdf.clj           # RDF/Turtle export
│   │   ├── wn_lmf.clj        # WN-LMF XML export
│   │   └── json_ld.clj       # JSON-LD export
│   ├── query.clj             # Main query interface and navigation
│   ├── query/
│   │   └── operation.clj     # Query operations and transformations
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
├── resources.clj             # Resource handlers, content negotiation, entity truncation
├── rate_limit.clj            # Rate limiting functionality
├── sparql.clj                # SPARQL endpoint: validation, execution, result caching
├── client.cljs               # ClojureScript SPA entry point
├── d3.cljs                   # D3 visualization components (radial trees)
├── ui.cljc                   # Core Rum UI components
├── ui/
│   ├── sparql.cljc           # SPARQL query editor UI, result table, pagination
│   ├── codemirror.cljs       # CodeMirror 6 interop (SPARQL syntax, folding, errors)
│   ├── form.cljc             # Form utilities (submit, validation, autofocus)
│   ├── visualization.cljc    # Radial tree diagrams, word clouds, ancestry display
│   ├── search.cljc           # Search form components
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
- **ont-app/vocabulary**: RDF vocabulary management
- **donatello**: Graph algorithms and traversal

### Web Framework
- **Pedestal** (0.7.2): HTTP service and routing
- **Rum**: React-like UI components (custom fork)
- **Reitit** (0.9.1): Client-side routing for SPA
- **Shadow-cljs**: ClojureScript compilation
- **CodeMirror 6**: SPARQL editor (via `codemirror`, `@codemirror/view`, `@codemirror/state`, `codemirror-lang-sparql`)

### Data Processing
- **clj-yaml** (1.0.29): YAML configuration parsing
- **docjure** (1.21.0): Excel file processing
- **data.csv** (1.1.0): CSV parsing and generation
- **data.xml** (0.2.0-alpha9): XML processing for WN-LMF
- **tightly-packed-trie**: Efficient trie data structures

### Caching
- **core.cache**: TTL-based SPARQL result caching with AST-normalized cache keys
- **core.memoize** (1.1.266): Function memoization

### Utilities
- **better-cond** (2.1.5): Enhanced conditional macros
- **ham-fisted** (2.031): High-performance collections
- **Telemere** (1.1.0): Logging and error reporting

## Development Workflow

### Setup
1. Install JVM (Java 24+) and Clojure CLI tools
2. Clone the repository
3. Place bootstrap data in `./bootstrap` directory (for releases)

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
;; In REPL, bootstrap from previous release
(require '[dk.cst.dannet.db.bootstrap :as bootstrap])
(bootstrap/bootstrap dataset)

;; Export to various formats
(require '[dk.cst.dannet.db.export.rdf :as rdf-export])
(rdf-export/save-rdf-files! dataset "release/")
```

## Conventions and Patterns

### RDF URI Structure
- Data instances: `https://wordnet.dk/dannet/data/` (prefix: `dn`)
- Concepts: `https://wordnet.dk/dannet/concepts/` (prefix: `dnc`)
- Schema: `https://wordnet.dk/dannet/schema/` (prefix: `dns`)

### Namespace Organization
- `dk.cst.dannet.*` - Core database and query functionality
- `dk.cst.dannet.db.*` - Database operations and exports
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
- A database gets automatically bootstrapped and/or initialised when the backend in `dk.cst.dannet.web.service` is launched (by calling the `restart` function during development)
- The SPARQL editor frontend is in `ui/sparql.cljc` with CodeMirror interop in `ui/codemirror.cljs`
- SPARQL backend logic (validation, execution, caching) is in `web/sparql.clj`
