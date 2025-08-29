# DanNet API Specification for Python MCP Server

This document specifies how to interact with DanNet's public API endpoints for building an MCP server. All examples use the `requests` library and assume `BASE_URL = "https://wordnet.dk"`.

## Core API Endpoints

### 1. Search Synsets (`search_synsets`)

**Purpose:** Search for synsets by lemma/word  
**HTTP Call:** `GET /dannet/search?lemma={query}&transit=true`  
**Content Type:** `application/transit+json`

```python
import requests

def search_synsets(query: str, base_url: str = "https://wordnet.dk") -> dict:
    """
    MCP tool: search_synsets
    
    Search for synsets containing the given query word.
    
    Args:
        query: Danish word to search for (e.g., "kage", "hund")
        base_url: API base URL (default: https://wordnet.dk)
        
    Returns:
        Dictionary containing search results with synsets and metadata
        
    Example:
        search_synsets("kage")  # Search for "cake" synsets
    """
    response = requests.get(
        f"{base_url}/dannet/search",
        params={
            "lemma": query.strip().lower(),
            "transit": "true"
        },
        headers={
            "Accept": "application/transit+json",
            "User-Agent": "DanNet-MCP-Server/1.0"
        },
        timeout=10
    )
    
    if response.status_code == 200:
        # Parse transit+json response
        return response.json()
    elif response.status_code == 302:
        # Single result redirect - extract entity URI from Location header
        location = response.headers.get('Location')
        if location:
            return {"redirect": location, "single_result": True}
    elif response.status_code == 429:
        # Rate limited
        retry_after = response.headers.get('Retry-After', '60')
        raise Exception(f"Rate limited. Retry after {retry_after} seconds")
    else:
        response.raise_for_status()
```

### 2. Get Entity (`get_entity`)

**Purpose:** Retrieve detailed information about a specific entity (synset, word, sense)  
**HTTP Call:** `GET /{entity_path}?transit=true`  
**Content Type:** `application/transit+json`

```python
def get_entity(entity_uri: str, base_url: str = "https://wordnet.dk") -> dict:
    """
    MCP tool: get_entity
    
    Get detailed information about a specific DanNet entity.
    
    Args:
        entity_uri: Full URI or DanNet path (e.g., "https://wordnet.dk/dannet/data/synset-1234" 
                   or "/dannet/data/synset-1234")
        base_url: API base URL
        
    Returns:
        Dictionary with entity details, relationships, and labels
        
    Example:
        get_entity("https://wordnet.dk/dannet/data/synset-12345")
        get_entity("/dannet/data/word-6789")
    """
    # Extract path if full URI provided
    if entity_uri.startswith("http"):
        from urllib.parse import urlparse
        path = urlparse(entity_uri).path
    else:
        path = entity_uri
    
    response = requests.get(
        f"{base_url}{path}",
        params={"transit": "true"},
        headers={
            "Accept": "application/transit+json",
            "User-Agent": "DanNet-MCP-Server/1.0"
        },
        timeout=15
    )
    
    if response.status_code == 200:
        return response.json()
    elif response.status_code == 404:
        raise Exception(f"Entity not found: {entity_uri}")
    elif response.status_code == 429:
        retry_after = response.headers.get('Retry-After', '60')
        raise Exception(f"Rate limited. Retry after {retry_after} seconds")
    else:
        response.raise_for_status()
```

### 3. Autocomplete (`autocomplete_search`)

**Purpose:** Get autocomplete suggestions for partial queries  
**HTTP Call:** `GET /dannet/autocomplete?s={partial_query}`  
**Content Type:** `application/transit+json`

```python
def autocomplete_search(partial_query: str, base_url: str = "https://wordnet.dk") -> list:
    """
    MCP tool: autocomplete_search
    
    Get autocomplete suggestions for partial word queries.
    
    Args:
        partial_query: Partial word to autocomplete (minimum 3 characters)
        base_url: API base URL
        
    Returns:
        List of suggested completions
        
    Example:
        autocomplete_search("ka")  # Returns ["kage", "kaffe", "kald", ...]
    """
    if len(partial_query.strip()) < 3:
        return []
    
    response = requests.get(
        f"{base_url}/dannet/autocomplete",
        params={"s": partial_query.strip().lower()},
        headers={
            "Accept": "application/transit+json",
            "User-Agent": "DanNet-MCP-Server/1.0"
        },
        timeout=5
    )
    
    if response.status_code == 200:
        return response.json()
    elif response.status_code == 204:
        return []  # No completions available
    elif response.status_code == 429:
        retry_after = response.headers.get('Retry-After', '60')
        raise Exception(f"Rate limited. Retry after {retry_after} seconds")
    else:
        return []  # Fail silently for autocomplete
```

## Response Formats

### Search Results Structure
```python
{
    "languages": ["da", "en"],
    "lemma": "kage",
    "search-results": [
        ["dn:synset-12345", "dn:synset-67890"],  # List of matching synset URIs
        # ... more results
    ]
}
```

### Entity Details Structure
```python
{
    "entity": {
        # RDF properties as key-value pairs
        "rdfs:label": "cake; tart",
        "ontolex:sense": ["dn:sense-123", "dn:sense-456"],
        "dns:hypernym": ["dn:synset-999"],
        # ... more properties
    },
    "entities": {
        # Related entities with their labels
        "dn:synset-999": {"rdfs:label": "baked good"},
        # ... more entities
    },
    "languages": ["da", "en"],
    "subject": "dn:synset-12345"
}
```

## Content Negotiation

DanNet supports multiple content types. For MCP servers, use `application/transit+json` for structured data:

```python
headers = {
    "Accept": "application/transit+json",  # Structured JSON data
    "User-Agent": "DanNet-MCP-Server/1.0"
}

# Alternative content types (not recommended for MCP):
# "text/turtle"     - RDF Turtle format
# "application/rdf+xml" - RDF XML format  
# "text/html"       - Human-readable HTML
```

## Rate Limiting

DanNet implements sophisticated rate limiting:

- **Limit:** 400 requests per minute per composite key (IP + User-Agent + Accept-Language)
- **Response:** HTTP 429 with `Retry-After` header
- **Outer limit:** Caddy provides 2000 req/min DDoS protection

```python
import time
from typing import Optional

class RateLimitHandler:
    def __init__(self):
        self.retry_after: Optional[int] = None
        self.last_rate_limit: Optional[float] = None
    
    def handle_response(self, response: requests.Response) -> None:
        if response.status_code == 429:
            self.retry_after = int(response.headers.get('Retry-After', 60))
            self.last_rate_limit = time.time()
            raise Exception(f"Rate limited. Retry after {self.retry_after} seconds")
    
    def check_rate_limit(self) -> None:
        if self.last_rate_limit and self.retry_after:
            elapsed = time.time() - self.last_rate_limit
            if elapsed < self.retry_after:
                remaining = self.retry_after - elapsed
                time.sleep(remaining)
```

## Error Handling

```python
def safe_api_call(func, *args, **kwargs):
    """
    Wrapper for safe API calls with error handling.
    """
    try:
        return func(*args, **kwargs)
    except requests.exceptions.Timeout:
        raise Exception("DanNet API request timed out")
    except requests.exceptions.ConnectionError:
        raise Exception("Could not connect to DanNet API")
    except requests.exceptions.HTTPError as e:
        if e.response.status_code == 404:
            raise Exception("Resource not found in DanNet")
        elif e.response.status_code == 429:
            retry_after = e.response.headers.get('Retry-After', '60')
            raise Exception(f"Rate limited by DanNet. Retry after {retry_after} seconds")
        else:
            raise Exception(f"DanNet API error: {e.response.status_code}")
    except Exception as e:
        raise Exception(f"Unexpected error accessing DanNet: {str(e)}")
```

## Entity URI Patterns

DanNet uses consistent URI patterns for different entity types:

```python
# Synset URIs
"https://wordnet.dk/dannet/data/synset-{id}"      # e.g., synset-12345

# Word/Lexical Entry URIs  
"https://wordnet.dk/dannet/data/word-{id}"        # e.g., word-67890

# Sense URIs
"https://wordnet.dk/dannet/data/sense-{id}"       # e.g., sense-11111

# Concept URIs (from DanNet concepts namespace)
"https://wordnet.dk/dannet/concepts/{concept}"    # e.g., concepts/Supersense

# Schema URIs
"https://wordnet.dk/dannet/schema/{property}"     # e.g., schema/hypernym
```

## Language Support

DanNet supports Danish (primary) and English (secondary):

```python
def format_language_preference():
    """Format Accept-Language header for Danish preference."""
    return {
        "Accept-Language": "da, en;q=0.8, *;q=0.1"
    }

# Entity labels often include language tags:
# "kage"@da (Danish)  
# "cake"@en (English)
```

## MCP Tool Suggestions

Based on the API structure, implement these MCP tools:

1. **`search_synsets`** - Primary search functionality
2. **`get_synset`** - Get detailed synset information  
3. **`get_word`** - Get lexical entry details
4. **`get_sense`** - Get word sense information
5. **`autocomplete_search`** - Autocomplete assistance
6. **`explain_relation`** - Explain WordNet relationship types
7. **`browse_supersenses`** - Navigate semantic categories

## Caching Recommendations

Implement caching for better performance:

```python
import time
from typing import Dict, Tuple, Any

class SimpleCache:
    def __init__(self, ttl_seconds: int = 3600):  # 1 hour TTL
        self.cache: Dict[str, Tuple[float, Any]] = {}
        self.ttl = ttl_seconds
    
    def get(self, key: str) -> Any:
        if key in self.cache:
            timestamp, value = self.cache[key]
            if time.time() - timestamp < self.ttl:
                return value
            else:
                del self.cache[key]
        return None
    
    def set(self, key: str, value: Any) -> None:
        self.cache[key] = (time.time(), value)

# Cache synset lookups, autocomplete results, and entity details
cache = SimpleCache(ttl_seconds=1800)  # 30 minutes
```

This specification provides everything needed to implement a robust MCP server for DanNet's public API.