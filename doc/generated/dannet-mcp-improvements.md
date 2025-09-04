# DanNet MCP Server Enhancement Plan

## Executive Summary

After analyzing the DanNet system, its RDF schemas, and the current Python MCP server implementation, I've identified several key improvements that will make the MCP server more intelligent and effective for LLM clients. The main focus areas are:

1. **Enhanced semantic awareness** through better docstring documentation
2. **Improved data format handling** with native RDF support options
3. **Better relationship navigation** with priority on common patterns
4. **Clearer resource organization** with contextual guidance

## Current State Analysis

### Strengths
- Clean separation between client and server logic
- Good error handling with retry mechanisms
- Resource endpoints for schema access
- Basic extraction helpers for ontological types and sentiment

### Weaknesses
- Limited semantic context in tool documentation
- No guidance on which relationships are most important
- Lack of RDF-native querying options
- Insufficient explanation of the data model structure
- Missing navigation patterns for complex queries

## Proposed Improvements

### 1. Enhanced Tool Documentation with Semantic Context

#### BEFORE: `search_dannet` function
```python
@mcp.tool()
def search_dannet(query: str, language: str = "da") -> List[SearchResult]:
    """
    Search DanNet for Danish words, synsets, and their meanings.
    
    Args:
        query: The Danish word or phrase to search for
        language: Language for results (default: "da" for Danish)
    
    Returns:
        List of search results with words, synsets, and definitions
    """
```

#### AFTER: Enhanced with semantic guidance
```python
@mcp.tool()
def search_dannet(query: str, language: str = "da") -> List[SearchResult]:
    """
    Search DanNet for Danish words, synsets, and their lexical meanings.
    
    DanNet follows the OntoLex-Lemon model where:
    - Words (ontolex:LexicalEntry) evoke concepts through senses
    - Synsets (ontolex:LexicalConcept) represent units of meaning
    - Multiple words can share the same synset (synonyms)
    - One word can have multiple synsets (polysemy)
    
    Common search patterns:
    - Nouns often have multiple senses (e.g., "kage" = cake/lump)
    - Verbs distinguish motion vs. state (e.g., "løbe" = run/flow)
    - Check synset's dns:ontologicalType for semantic classification
    
    Args:
        query: The Danish word or phrase to search for
        language: Language for results (default: "da" for Danish, "en" for English labels)
    
    Returns:
        List of search results with:
        - word: The lexical form
        - synset_id: Unique synset identifier (format: synset-NNNNN)
        - label: Human-readable synset label (e.g., "{kage_1§1}")
        - definition: Brief semantic definition (may be truncated with "...")
    
    Example:
        results = search_dannet("hund")
        # Returns synsets for dog (animal), person (slang), etc.
    """
```

### 2. Improved Synset Information Retrieval

#### BEFORE: `get_synset_info` function
```python
@mcp.tool()
def get_synset_info(synset_id: str) -> Dict[str, Any]:
    """
    Get detailed information about a specific DanNet synset.
    
    Args:
        synset_id: The synset identifier (e.g., "synset-1876")
    
    Returns:
        Raw RDF data for the synset including all properties and relationships.
        Includes extracted fields :dns/ontologicalType_extracted and :dns/sentiment_extracted
        for easier interpretation.
        
    NOTE: To understand ontological types (dnc: namespace), check dannet://ontological-types first.
    For DanNet properties, see dannet://dannet-schema resource.
    """
```

#### AFTER: With relationship priority and navigation hints
```python
@mcp.tool()
def get_synset_info(synset_id: str) -> Dict[str, Any]:
    """
    Get comprehensive RDF data for a DanNet synset (lexical concept).
    
    UNDERSTANDING THE DATA MODEL:
    Synsets are ontolex:LexicalConcept instances representing word meanings.
    They connect to words via ontolex:isEvokedBy and have rich semantic relations.
    
    KEY RELATIONSHIPS (by importance):
    
    1. TAXONOMIC (most fundamental):
       - wn:hypernym → broader concept (e.g., "hund" → "pattedyr")
       - wn:hyponym → narrower concepts (e.g., "hund" → "puddel", "schæfer")
       - dns:orthogonalHypernym → cross-cutting categories
    
    2. LEXICAL CONNECTIONS:
       - ontolex:isEvokedBy → words expressing this concept
       - ontolex:lexicalizedSense → sense instances
       - wn:similar → related but distinct concepts
    
    3. PART-WHOLE RELATIONS:
       - wn:mero_part/wn:holo_part → component relationships
       - wn:mero_substance/wn:holo_substance → material composition
       - wn:mero_member/wn:holo_member → membership relations
    
    4. SEMANTIC PROPERTIES:
       - dns:ontologicalType → semantic classification (see _extracted field)
         Common types: dnc:Animal, dnc:Human, dnc:Object, dnc:Physical, 
         dnc:Dynamic (events/actions), dnc:Static (states)
       - dns:sentiment → emotional polarity (if applicable)
       - wn:lexfile → semantic domain (e.g., "noun.food", "verb.motion")
    
    5. CROSS-LINGUISTIC:
       - wn:ili → Interlingual Index for cross-language mapping
       - wn:eq_synonym → Princeton WordNet equivalent
    
    NAVIGATION TIPS:
    - Follow wn:hypernym chains to find semantic categories
    - Check dns:inherited for properties from parent synsets
    - Use parse_resource_id() on URI references to get clean IDs
    
    Args:
        synset_id: Synset identifier (e.g., "synset-1876" or just "1876")
        
    Returns:
        Dict containing:
        - All RDF properties with namespace prefixes (e.g., :wn/hypernym)
        - :inferred → properties derived through OWL reasoning
        - synset_id → clean identifier for convenience
        - :dns/ontologicalType_extracted → human-readable semantic types
        - :dns/sentiment_extracted → parsed sentiment (if present)
        
    Example:
        info = get_synset_info("synset-52")  # cake synset
        # Check info[':wn/hypernym'] for parent concepts
        # Check info[':dns/ontologicalType_extracted'] for semantic class
    """
```

### 3. Enhanced Synonym Finding with Semantic Awareness

#### BEFORE: `get_word_synonyms` function
```python
@mcp.tool()
def get_word_synonyms(word: str) -> List[str]:
    """
    Find synonyms for a Danish word by looking up its synsets.
    
    Args:
        word: The Danish word to find synonyms for
    
    Returns:
        List of synonymous words
    """
```

#### AFTER: With synset-aware synonym detection
```python
@mcp.tool()
def get_word_synonyms(word: str, synset_filter: Optional[str] = None) -> Union[List[str], Dict[str, List[str]]]:
    """
    Find synonyms for a Danish word through shared synsets (word senses).
    
    SYNONYM TYPES IN DANNET:
    - True synonyms: Words sharing the exact same synset
    - Near-synonyms: Words in synsets marked with wn:similar
    - Context-specific: Different synonyms for different word senses
    
    The function returns all words that share synsets with the input word,
    effectively finding lexical alternatives that express the same concepts.
    
    Args:
        word: The Danish word to find synonyms for
        synset_filter: Optional synset_id to get synonyms for specific sense only
        
    Returns:
        If synset_filter provided: List of synonyms for that specific sense
        Otherwise: Dict mapping synset_ids to their synonym lists
        
    Example:
        # Get all synonyms grouped by meaning:
        all_synonyms = get_word_synonyms("løbe")
        # Returns: {"synset-30094": ["rende", "spurte"], 
        #           "synset-44527": ["flyde", "strømme"], ...}
        
        # Get synonyms for specific sense (running):
        run_synonyms = get_word_synonyms("løbe", "synset-30094")
        # Returns: ["rende", "spurte", "jage"]
        
    Note: Check synset definitions to understand which synonyms apply
    to which meaning (polysemy is common in Danish).
    """
```

### 4. New Helper Function for Relationship Navigation

#### NEW ADDITION: Relationship explorer
```python
@mcp.tool()
def explore_semantic_relations(
    synset_id: str, 
    relation_types: List[str] = ["hypernym", "hyponym"],
    max_depth: int = 2
) -> Dict[str, Any]:
    """
    Explore semantic relationships from a synset with controlled traversal.
    
    COMMON NAVIGATION PATTERNS:
    
    1. Taxonomic exploration (hypernym/hyponym):
       - Navigate UP (hypernym) to find categories
       - Navigate DOWN (hyponym) to find specific instances
       - Most synsets have 1 hypernym but many hyponyms
       
    2. Part-whole exploration (meronym/holonym):
       - mero_part: components (e.g., "hjul" → "bil")
       - holo_part: things this is part of
       - Common for physical objects and body parts
       
    3. Semantic similarity (similar, also):
       - Find related but distinct concepts
       - Useful for expanding search or finding alternatives
       
    4. Cross-domain relations:
       - domain_topic: technical/specialized usage
       - used_for: functional relationships (DanNet-specific)
       
    Args:
        synset_id: Starting synset identifier
        relation_types: List of relation types to explore
            Common: ["hypernym", "hyponym", "similar", "mero_part"]
            Format: Remove "wn:" or "dns:" prefix
        max_depth: How many steps to traverse (default 2)
        
    Returns:
        Dict with:
        - synset: Starting synset info
        - relations: Dict of relation_type → list of related synsets
        - paths: Traversal paths for multi-hop relations
        
    Example:
        # Find what kind of thing a "hund" (dog) is:
        explore_semantic_relations("synset-2084", ["hypernym"], 3)
        # Returns chain: hund → pattedyr → hvirveldyr → dyr
        
        # Find all types of "kage" (cake):
        explore_semantic_relations("synset-52", ["hyponym"], 1)
        # Returns: lagkage, æblekage, chokoladekage, etc.
    """
```

### 5. Enhanced Resource Documentation

#### BEFORE: Generic schema resource
```python
@mcp.resource("dannet://schema/{prefix}")
def get_schema_resource(prefix: str) -> str:
    """
    Access RDF schemas used by DanNet by their namespace prefix.
    
    Args:
        prefix: Schema namespace prefix (e.g., 'dns', 'dnc', 'rdfs', 'owl', 'ontolex')
    
    Returns:
        RDF schema in Turtle format
    """
```

#### AFTER: With comprehensive usage guidance
```python
@mcp.resource("dannet://schema/{prefix}")
def get_schema_resource(prefix: str) -> str:
    """
    Access RDF schemas defining DanNet's semantic structure.
    
    SCHEMA HIERARCHY AND USAGE:
    
    Essential DanNet schemas (START HERE):
    ----------------------------------------
    'dns' - DanNet Schema (https://wordnet.dk/dannet/schema/)
        Defines Danish-specific relations and properties:
        - dns:ontologicalType → links to semantic categories
        - dns:sentiment → emotional polarity annotations  
        - dns:usedFor → functional relationships
        - dns:orthogonalHypernym → cross-cutting hierarchies
        - dns:inherited → properties from parent synsets
        USE: Understanding DanNet-specific features
        
    'dnc' - DanNet Concepts (https://wordnet.dk/dannet/concepts/)
        Taxonomy of ontological types (semantic categories):
        - EuroWordNet concepts: FirstOrderEntity (physical things),
          SecondOrderEntity (events/processes), ThirdOrderEntity (abstract)
        - Semantic primitives: Animal, Human, Object, Place, Time
        - Properties: Physical/Mental, Static/Dynamic, Natural/Artifact
        USE: Interpreting dns:ontologicalType values
        
    Core linguistic schemas:
    ------------------------
    'ontolex' - OntoLex-Lemon (W3C standard for lexical resources)
        - LexicalEntry → words and multi-word expressions
        - LexicalConcept → synsets (word meanings)
        - LexicalSense → connection between words and concepts
        - Form → inflected word forms
        USE: Understanding the word-sense-synset model
        
    'wn' - Global WordNet (standard WordNet relations)
        - Taxonomic: hypernym, hyponym, instance_of
        - Part-whole: meronym, holonym (part/member/substance)
        - Lexical: antonym, similar, also, pertainym
        - Cross-lingual: ili (Interlingual Index)
        USE: Navigating semantic relationships
        
    Supporting vocabularies:
    -----------------------
    'skos' - Simple Knowledge Organization System
        Used for: definitions, alternative labels
        
    'lexinfo' - Linguistic categories
        Used for: part-of-speech, morphological features
        
    'marl' - Sentiment annotation
        Used for: polarity values in dns:sentiment
    
    Args:
        prefix: Namespace prefix (case-sensitive)
        
    Returns:
        RDF schema in Turtle format with full ontology definitions
        
    Example:
        # First understand the semantic categories:
        dnc_schema = get_schema_resource("dnc")
        
        # Then explore DanNet's custom properties:
        dns_schema = get_schema_resource("dns")
    """
```

### 6. Add Data Format Options

#### NEW: Alternative data format support
```python
class DanNetClient:
    """HTTP client for DanNet API with format negotiation"""
    
    def __init__(self, base_url: str = BASE_URL, preferred_format: str = "json"):
        """
        Initialize DanNet client with format preference.
        
        Args:
            base_url: DanNet service URL
            preferred_format: Data format - "json" (default) or "turtle" (RDF)
        """
        self.base_url = base_url.rstrip('/')
        self.preferred_format = preferred_format
        self.client = httpx.Client(timeout=TIMEOUT)
    
    def _make_request(self, endpoint: str, params: Optional[Dict] = None, 
                     format_override: Optional[str] = None) -> Union[Dict, str]:
        """
        Make HTTP request with format negotiation.
        
        The DanNet service supports multiple response formats:
        - JSON: Convenient for Python processing, includes extracted fields
        - Turtle: Native RDF format, preserves full semantic structure
        - EDN: Clojure data format (internally used, converted to JSON)
        
        For RDF-native operations (SPARQL-like queries), Turtle format
        preserves namespace prefixes and allows standard RDF processing.
        """
        url = urljoin(self.base_url + '/', endpoint.lstrip('/'))
        request_params = params or {}
        
        format_type = format_override or self.preferred_format
        request_params["format"] = format_type
        
        response = self.client.get(url, params=request_params, follow_redirects=True)
        response.raise_for_status()
        
        if format_type == "turtle":
            return response.text  # Return raw Turtle RDF
        else:
            return response.json()  # Parse JSON response
```

#### NEW: SPARQL-like query builder
```python
@mcp.tool()
def query_semantic_pattern(
    pattern: str,
    start_word: Optional[str] = None,
    start_synset: Optional[str] = None
) -> List[Dict[str, Any]]:
    """
    Execute semantic pattern queries inspired by SPARQL graph patterns.
    
    PATTERN LANGUAGE:
    Patterns describe paths through the semantic graph using a simple syntax:
    
    Basic patterns:
    - "hypernym" → Find direct hypernym (parent concept)
    - "hypernym+" → Find all hypernyms (transitive closure) 
    - "hypernym/hyponym" → Find siblings (up then down)
    - "similar|antonym" → Find similar OR antonym relations
    
    Common query patterns:
    
    1. Find semantic category (what kind of thing):
       pattern="hypernym+", start_word="hund"
       → Returns: hund → pattedyr → dyr → living_thing
       
    2. Find all specific instances:
       pattern="hyponym+", start_synset="synset-52"  # cake
       → Returns all types of cakes
       
    3. Find related concepts at same level:
       pattern="hypernym/hyponym", start_word="hund"
       → Returns: kat, hest, ko (sibling concepts)
       
    4. Find part-whole relationships:
       pattern="mero_part+", start_word="bil"
       → Returns: hjul, motor, rat (car components)
    
    Args:
        pattern: Relationship pattern using "/" for sequence, 
                "|" for alternatives, "+" for transitive
        start_word: Danish word to start from (finds all its synsets)
        start_synset: Specific synset ID to start from
        
    Returns:
        List of matching paths with synset information
        
    Example:
        # What kind of food is "kage"?
        query_semantic_pattern("hypernym+", start_word="kage")
        
        # Find all animals in DanNet:
        query_semantic_pattern("hyponym+", start_synset="synset-123")  # animal synset
    """
```

### 7. Improved Main Server Instructions

#### BEFORE: Basic instructions
```python
mcp = FastMCP(
    "DanNet",
    instructions="""DanNet MCP Server - Danish WordNet semantic database access

Key features:
- Search Danish words and get semantic synsets with definitions
- Access ontological classifications (what type of thing a word represents)
- Find semantic relationships: synonyms, hypernyms, hyponyms
- Sentiment analysis for Danish words
- Rich RDF-based linguistic data with extracted user-friendly formats
"""
)
```

#### AFTER: Comprehensive semantic guidance
```python
mcp = FastMCP(
    "DanNet",
    instructions="""DanNet MCP Server - Danish WordNet with rich semantic relationships

SEMANTIC DATA MODEL:
DanNet follows OntoLex-Lemon + Global WordNet standards where:
• Words (LexicalEntry) → Senses → Synsets (LexicalConcept)
• Synsets represent units of meaning shared by synonymous words
• Rich semantic network with 70+ relation types

QUICK START WORKFLOW:
1. Check resources for context:
   - dannet://ontological-types → Semantic categories (Animal, Human, Object, etc.)
   - dannet://namespaces → Understanding prefixes in the data
   
2. Search for words to find synsets:
   - search_dannet("hund") → Find all meanings
   - Note: Danish has high polysemy (words with multiple meanings)
   
3. Explore synset details:
   - get_synset_info() → Full RDF data with relationships
   - Check dns:ontologicalType_extracted for semantic class
   - Follow wn:hypernym for categories, wn:hyponym for specifics
   
4. Navigate semantic relationships:
   - Taxonomic: hypernym (broader) / hyponym (narrower)
   - Similarity: similar, near_synonym, antonym
   - Part-whole: meronym/holonym (part/substance/member)
   - Functional: used_for, causes, instrument (DanNet-specific)

KEY SEMANTIC PATTERNS:
• Hypernym chains reveal conceptual hierarchies
• Multiple hyponyms indicate important category nodes
• dns:inherited shows properties from parent concepts
• Cross-linguistic via wn:ili and wn:eq_synonym to Princeton WordNet

DATA FORMATS:
• JSON responses include _extracted fields for easier parsing
• Raw RDF available via Turtle format for graph operations
• All entities use namespace prefixes (dn: for data, dns: for schema)

TIPS FOR LLM USAGE:
- Start broad with word search, then narrow to specific synsets
- Use ontological types to understand what kind of entity something is
- Check relationships to build semantic context
- Danish-specific: Watch for compound words and derivations"""
)
```

## Implementation Priorities

### Phase 1: Documentation Enhancement (Immediate)
- Update all tool docstrings with semantic context
- Add navigation hints and common patterns
- Include concrete examples with expected outputs

### Phase 2: Data Format Improvements (Short-term)
- Add Turtle format option for RDF-native processing
- Implement format negotiation in client
- Create pattern-based query builder

### Phase 3: Semantic Navigation (Medium-term)
- Add relationship explorer tool
- Implement transitive relationship following
- Create semantic similarity calculator

### Phase 4: Advanced Features (Long-term)
- SPARQL endpoint integration
- Semantic path finding algorithms
- Cross-linguistic mapping tools

## Benefits of These Changes

1. **Better LLM Understanding**: The enhanced documentation provides semantic context that helps LLMs understand not just what the tools do, but how to use them effectively for linguistic tasks.

2. **Improved Navigation**: By highlighting the most important relationships and providing navigation patterns, LLMs can explore the semantic graph more efficiently.

3. **Format Flexibility**: Supporting both JSON and Turtle formats allows for both convenient programming and semantically-rich RDF processing.

4. **Reduced Learning Curve**: The examples and patterns make it easier for LLMs to start using DanNet effectively without extensive trial and error.

5. **Semantic Awareness**: By explaining the OntoLex-Lemon model and WordNet standards, LLMs can leverage their training on these widespread standards.

## Conclusion

These improvements transform the DanNet MCP server from a simple API wrapper into an intelligent semantic gateway. The enhanced documentation acts as built-in expertise, guiding LLMs through the rich semantic landscape of Danish language data. The focus on relationships and navigation patterns enables more sophisticated linguistic analysis while maintaining ease of use.

The changes prioritize practical improvements that can be implemented immediately (documentation) while laying groundwork for more advanced features (RDF processing, pattern queries). This approach ensures the MCP server becomes progressively more capable while remaining accessible to LLM clients with varying levels of sophistication.