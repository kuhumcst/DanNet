# DanNet Project Summary

## Overview
DanNet is a comprehensive WordNet implementation for the Danish language, built as an RDF-based knowledge graph. The project provides a complete lexical semantic database that represents Danish words, their meanings (synsets), and relationships between them. It follows Ontolex-lemon and Global Wordnet Association standards, making it highly interoperable with other linguistic resources.

The system includes:
- A full RDF triplestore implementation using Apache Jena
- A web application (https://wordnet.dk) with both server-side and client-side rendering
- Multiple export formats (RDF/Turtle, CSV, WN-LMF XML)
- Bootstrap system for versioning and data migrations
- Rich query capabilities via SPARQL and Clojure DSL

## Key Architecture Components

### Database Layer (`dk.cst.dannet.db`)
- **Apache Jena** as the core RDF triplestore
- Support for both in-memory and persistent (TDB/TDB2) graph storage
- Automatic transaction management for persistent stores
- OWL inference support for deriving implicit relationships

### Query System (`dk.cst.dannet.query`)
- Multiple query methods: SPARQL, SPARQL Algebra, Aristotle DSL
- Entity expansion and blank node resolution
- Weighted synset algorithms for semantic similarity

### Web Application
- **Backend** (`dk.cst.dannet.web.service`): Pedestal-based HTTP service
- **Frontend** (`dk.cst.dannet.web.client`): ClojureScript SPA with Rum components
- Progressive enhancement: works with or without JavaScript
- Content negotiation for multiple data formats (HTML, JSON, RDF, etc.)
- Internationalization support (Danish/English)

### Bootstrap System (`dk.cst.dannet.db.bootstrap`)
- Loads previous RDF releases from `./bootstrap` directory
- Applies version migrations and schema updates
- Generates new releases with full data validation
- Exports to multiple formats (RDF, CSV, WN-LMF)

## File Structure

### Core Database & Query
```
src/main/dk/cst/dannet/
├── db.clj                     # Core database operations, model management
├── db/
│   ├── bootstrap.clj          # Release bootstrapping and migration
│   ├── bootstrap/supersenses.clj # Supersense taxonomy integration
│   ├── export/
│   │   ├── csv.clj           # CSV/CSVW export functionality
│   │   ├── rdf.clj           # RDF/Turtle export
│   │   └── wn_lmf.clj        # WN-LMF XML export
│   └── search.clj            # Text search and indexing
├── query.clj                  # Main query interface and navigation
├── query/
│   ├── operation.clj         # Query operations and transformations
│   └── operation/llm.clj     # LLM-specific query operations
├── transaction.clj            # Transaction management utilities
├── hash.clj                   # Content hashing and caching
└── prefix.cljc               # RDF namespace prefix management
```

### Web Application
```
src/main/dk/cst/dannet/web/
├── service.clj               # Pedestal HTTP service and routing
├── resources.clj             # Resource handlers and content negotiation
├── client.cljs              # ClojureScript SPA entry point
├── components.cljc          # Shared Rum UI components
├── components/
│   └── visualization.cljs   # Graph visualization components
├── section.cljc             # Page sections and layouts
├── i18n.cljc               # Internationalization strings
└── shared.cljc             # Shared utilities between CLJ/CLJS
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

### Data Processing
- **clj-yaml** (1.0.29): YAML configuration parsing
- **docjure** (1.21.0): Excel file processing
- **data.csv** (1.1.0): CSV parsing and generation
- **data.xml** (0.2.0-alpha9): XML processing for WN-LMF
- **tightly-packed-trie**: Efficient trie data structures

### Utilities
- **better-cond** (2.1.5): Enhanced conditional macros
- **ham-fisted** (2.030): High-performance collections
- **core.memoize** (1.1.266): Function memoization

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
- `dk.cst.dannet.query.*` - Query operations and transformations

### Transaction Handling
- Automatic transaction wrapping for TDB operations
- Use `dk.cst.dannet.transaction/transact` for explicit transactions
- Read operations are implicitly transactional

### Error Handling
- Database operations return nil on failure
- Web handlers use Pedestal interceptors for error handling
- Bootstrap validates data before committing changes

## Extension Points

### Adding New Export Formats
1. Create namespace in `dk.cst.dannet.db.export/`
2. Implement export function taking dataset and output path
3. Register in bootstrap process

### Custom Query Operations
1. Add operation in `dk.cst.dannet.query.operation/`
2. Implement transformation function
3. Register with query processor

### New Web Endpoints
1. Add handler in `dk.cst.dannet.web.resources`
2. Register route in `dk.cst.dannet.web.service/routes`
3. Implement content negotiation if needed

### Schema Extensions
1. Add definitions to `resources/schemas/internal/dannet-schema.ttl`
2. Update bootstrap to handle new properties
3. Extend query operations as needed

## Integration Examples

### Python Integration (via WN-LMF)
```python
import wn
wn.add("dannet-wn-lmf.xml.gz")

for synset in wn.synsets('kage'):
    print(f"{synset.lexfile()}: {synset.definition()}")
```

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
(require '[dk.cst.dannet.query :as q])

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
- Memoization used for expensive computations (synset weights)
- Client-side caching via Transit+JSON for SPA mode
- Batch operations preferred in bootstrap process

## Related Documentation

- [README.md](/README.md) - General project information
- [doc/web.md](/doc/web.md) - Detailed web application documentation
- [pages/queries-en.md](/pages/queries-en.md) - Query language documentation
- [pages/rationale-en.md](/pages/rationale-en.md) - Design rationale
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

