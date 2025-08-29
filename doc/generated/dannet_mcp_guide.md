# DanNet MCP Server Implementation Guide

## Overview

This guide outlines the procedure for implementing a Model Context Protocol (MCP) server that interfaces with DanNet, a Danish semantic wordnet. DanNet provides semantic relationships, word definitions, synonyms, and other linguistic data through a structured RDF-based API.

## DanNet Architecture

DanNet exposes its data through multiple HTTP endpoints that return different content types based on content negotiation or explicit format parameters. The system is built on RDF principles and provides access to:

- **Synsets**: Groups of synonymous words/concepts
- **Lexical entries**: Individual words and their properties
- **Semantic relations**: Hypernymy, hyponymy, meronymy, etc.
- **Definitions and examples**: Human-readable descriptions
- **Sentiment data**: Emotional associations with words
- **Cross-language mappings**: Connections to other wordnets

## Base URLs and Environment

DanNet operates on two primary environments:
- **Production**: `https://wordnet.dk`
- **Development**: `http://localhost:3456`

All data resources follow the pattern: `{base_url}/dannet/data/{resource-id}`

## Core API Endpoints

### 1. Entity/Resource Lookup
**Pattern**: `/dannet/data/{resource-id}?format=json`

Access specific RDF resources (synsets, words, concepts) by their identifier.

**Examples**:
- `https://wordnet.dk/dannet/data/synset-1876?format=json` - Get synset data
- `https://wordnet.dk/dannet/data/word-12345?format=json` - Get word data
- `https://wordnet.dk/dannet/data/sense-98765?format=json` - Get sense data

**Response Format**: JSON-serialized RDF triples with language strings and typed values

### 2. Search Endpoint
**Pattern**: `/dannet/search?q={query}&format=json`

Search for words, synsets, and concepts across the database.

**Parameters**:
- `q`: Search query (lemma/word)
- `format`: Response format (json, edn, html, plain, turtle)
- `lang`: Language preference (da, en)

**Example**: `https://wordnet.dk/dannet/search?q=hund&format=json`

### 3. Autocomplete Endpoint
**Pattern**: `/dannet/autocomplete/{prefix}`

Get autocomplete suggestions for partial word inputs.

**Example**: `https://wordnet.dk/dannet/autocomplete/hu`

## Data Structure and Semantic Relations

### Core Namespaces
DanNet uses several RDF namespaces:
- `dn:` - DanNet data (`https://wordnet.dk/dannet/data/`)
- `dns:` - DanNet schema (`https://wordnet.dk/dannet/schema/`)
- `dnc:` - DanNet concepts (`https://wordnet.dk/dannet/concepts/`)
- `ontolex:` - OntoLex vocabulary for lexical data
- `skos:` - SKOS for definitions and relations
- `wn:` - WordNet schema compatibility

### Key Data Types

**Synsets** (Lexical Concepts):
- Type: `ontolex:LexicalConcept`
- Properties: `rdfs:label`, `skos:definition`, `dns:shortLabel`
- Relations: `wn:hypernym`, `wn:hyponym`, `wn:meronym`, etc.

**Words** (Lexical Entries):
- Type: `ontolex:LexicalEntry`
- Properties: `ontolex:canonicalForm`, `lexinfo:partOfSpeech`
- Relations: `ontolex:evokes` (connects to synsets)

**Forms**:
- Type: `ontolex:Form`
- Properties: `ontolex:writtenRep` (the actual word string)

## MCP Server Implementation Strategy

### Tools to Implement

#### 1. Word Lookup Tools
```python
@mcp.tool()
def search_word(word: str, language: str = "da") -> List[Dict]:
    """Search DanNet for words and their synsets"""
    # GET /dannet/search?q={word}&format=json&lang={language}
    
@mcp.tool()
def get_synset_details(synset_id: str) -> Dict:
    """Get detailed information about a specific synset"""
    # GET /dannet/data/{synset_id}?format=json
    
@mcp.tool()
def get_word_details(word_id: str) -> Dict:
    """Get detailed information about a specific word"""
    # GET /dannet/data/{word_id}?format=json
```

#### 2. Semantic Relation Tools
```python
@mcp.tool()
def find_synonyms(word: str) -> List[Dict]:
    """Find synonyms for a Danish word"""
    # Search word → get synsets → extract other words in same synsets
    
@mcp.tool()
def find_hypernyms(synset_id: str) -> List[Dict]:
    """Find hypernyms (broader concepts) for a synset"""
    # Query synset relations for wn:hypernym properties
    
@mcp.tool()
def find_hyponyms(synset_id: str) -> List[Dict]:
    """Find hyponyms (narrower concepts) for a synset"""
    # Query for synsets that have this synset as hypernym
```

#### 3. Definition and Example Tools
```python
@mcp.tool()
def get_definitions(word: str) -> List[Dict]:
    """Get definitions for all senses of a word"""
    # Extract skos:definition from synsets
    
@mcp.tool()
def get_examples(synset_id: str) -> List[str]:
    """Get usage examples for a synset"""
    # Extract example data from synset
```

#### 4. Autocomplete Tool
```python
@mcp.tool()
def autocomplete_word(prefix: str, max_results: int = 10) -> List[str]:
    """Get word completions for a prefix"""
    # GET /dannet/autocomplete/{prefix}
```

### Resources to Implement

#### 1. Synset Resources
```python
@mcp.resource("dannet://synset/{synset_id}")
def get_synset_resource(synset_id: str) -> Dict:
    """Access synset data as a resource"""
    # GET /dannet/data/{synset_id}?format=json
```

#### 2. Word Resources
```python
@mcp.resource("dannet://word/{word_id}")
def get_word_resource(word_id: str) -> Dict:
    """Access word data as a resource"""
    # GET /dannet/data/{word_id}?format=json
```

#### 3. Search Results Resources
```python
@mcp.resource("dannet://search/{query}")
def search_results_resource(query: str) -> Dict:
    """Access search results as a resource"""
    # GET /dannet/search?q={query}&format=json
```

### Prompts to Implement

#### 1. Linguistic Analysis Prompts
```python
@mcp.prompt()
def analyze_word_semantics(word: str, context: str = "") -> str:
    """Generate a prompt for semantic analysis of a Danish word"""
    return f"""
    Analyze the semantic properties of the Danish word "{word}".
    {f"Context: {context}" if context else ""}
    
    Please provide:
    1. All synsets (meanings) for this word
    2. Semantic relationships (hypernyms, hyponyms, meronyms)
    3. Synonyms and related words
    4. Definitions and usage examples
    5. Part of speech information
    
    Use DanNet data to provide comprehensive linguistic analysis.
    """

@mcp.prompt()
def compare_word_meanings(word1: str, word2: str) -> str:
    """Generate a prompt for comparing two Danish words semantically"""
    return f"""
    Compare the semantic relationships between "{word1}" and "{word2}".
    
    Analyze:
    1. Shared semantic fields or synsets
    2. Hierarchical relationships (if any)
    3. Degree of semantic similarity
    4. Contexts where they might be used differently
    5. Synonyms unique to each word
    
    Provide examples and cite specific DanNet synset relationships.
    """
```

## HTTP Request Implementation

### Content Negotiation
Always use the `format=json` query parameter for consistent JSON responses:

```python
def make_dannet_request(endpoint: str, params: Dict = None) -> Dict:
    """Make a request to DanNet API with JSON format"""
    base_params = {"format": "json"}
    if params:
        base_params.update(params)
    
    response = requests.get(
        f"{BASE_URL}{endpoint}", 
        params=base_params,
        headers={"Accept": "application/json"}
    )
    return response.json()
```

### JSON Response Structure
DanNet returns JSON with these patterns:

**Entity Data**:
```json
{
  "entity": {
    "subject": "dn:synset-1876",
    "properties": {
      "rdf:type": ["ontolex:LexicalConcept"],
      "rdfs:label": [{"value": "hund", "lang": "da"}],
      "skos:definition": [{"value": "firbentet husdyr af hundefamilien", "lang": "da"}]
    }
  }
}
```

**Search Results**:
```json
{
  "results": [
    {
      "form": "dn:form-12345",
      "word": "dn:word-67890", 
      "synset": "dn:synset-1876",
      "label": {"value": "hund", "lang": "da"}
    }
  ]
}
```

## Error Handling and Rate Limiting

DanNet implements rate limiting on the `/dannet/search` endpoint. Your MCP server should:

1. **Handle HTTP errors gracefully**:
   - 404: Resource not found
   - 429: Rate limit exceeded
   - 500: Server error

2. **Implement retry logic** for rate-limited requests

3. **Cache responses** where appropriate to reduce API calls

4. **Validate resource IDs** before making requests

## Testing and Validation

### Test Endpoints
Use these test cases to validate your MCP server:

1. **Search for common word**: `search_word("hund")`
2. **Get synset details**: `get_synset_details("synset-1876")`
3. **Find synonyms**: `find_synonyms("bil")`
4. **Autocomplete**: `autocomplete_word("hu")`

### Expected Data Patterns
- Synset IDs follow pattern: `synset-{number}`
- Word IDs follow pattern: `word-{number}`
- Form IDs follow pattern: `form-{number}`
- All Danish text should have `@da` language tags
- Definitions use SKOS vocabulary
- Lexical entries use OntoLex vocabulary

## Deployment Considerations

### Environment Configuration
```python
# Configuration for different environments
DANNET_CONFIGS = {
    "production": {
        "base_url": "https://wordnet.dk",
        "timeout": 30,
        "max_retries": 3
    },
    "development": {
        "base_url": "http://localhost:3456", 
        "timeout": 10,
        "max_retries": 1
    }
}
```

### Performance Optimization
- **Cache frequently accessed synsets** and definitions
- **Batch multiple lookups** when possible
- **Use connection pooling** for HTTP requests
- **Implement request deduplication** for identical queries

## Integration with Claude Desktop

Configure your MCP server for Claude Desktop integration:

```json
{
  "mcpServers": {
    "dannet-server": {
      "command": "uv",
      "args": [
        "--directory", 
        "/path/to/your/dannet-mcp-server",
        "run", 
        "server.py"
      ],
      "env": {
        "DANNET_BASE_URL": "https://wordnet.dk",
        "DANNET_LANGUAGE": "da"
      }
    }
  }
}
```

This guide provides the foundation for building an MCP server that effectively interfaces with DanNet's rich linguistic data, enabling AI applications to access Danish semantic wordnet information through standardized MCP protocols.