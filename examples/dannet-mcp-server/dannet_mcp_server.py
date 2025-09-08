#!/usr/bin/env python3
"""
DanNet MCP Server - A Model Context Protocol server for accessing DanNet (Danish WordNet)

This server provides AI applications with access to DanNet's rich Danish linguistic data
including synsets, semantic relationships, word definitions, and examples.

Server Selection (in order of precedence):
1. --base-url <url>                   # Explicit custom URL
2. --local or DANNET_MCP_LOCAL=true  # Force local server (localhost:3456)  
3. Auto-detect local server          # Default: try local, fallback to remote
4. Remote server fallback            # Uses wordnet.dk if local unavailable

Usage:
    python dannet_mcp_server.py                    # Auto-detect (local preferred, remote fallback)
    python dannet_mcp_server.py --local           # Force localhost:3456 (development)
    python dannet_mcp_server.py --base-url <url>  # Custom base URL
"""

import argparse
import logging
import json
import os
from typing import Dict, List, Optional, Any, Union
from urllib.parse import urljoin

import httpx
from pydantic import BaseModel, Field
from mcp.server.fastmcp import FastMCP

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# Global configuration
REMOTE_URL = "https://wordnet.dk"
LOCAL_URL = "http://localhost:3456"
TIMEOUT = 30.0
MAX_RETRIES = 3


class DanNetError(Exception):
    """Custom exception for DanNet API errors"""
    pass


class SearchResult(BaseModel):
    """Search result from DanNet"""
    word: str = Field(description="The word form")
    synset_id: Optional[str] = Field(description="Associated synset ID")
    label: Optional[str] = Field(description="Synset label")
    definition: Optional[str] = Field(description="Definition")


class DanNetClient:
    """HTTP client for DanNet API with format negotiation support"""

    def __init__(self, base_url: str = REMOTE_URL):
        """
        Initialize DanNet client.

        The DanNet service supports multiple response formats:
        - JSON: Convenient for Python processing, includes extracted fields
        - Turtle: Native RDF format, preserves full semantic structure
        - EDN: Clojure data format (internally used, converted to JSON)

        Args:
            base_url: DanNet service URL
        """
        self.base_url = base_url.rstrip('/')
        self.client = httpx.Client(timeout=TIMEOUT)

    def _make_request(self, endpoint: str, params: Optional[Dict] = None) -> Dict:
        """Make HTTP request to DanNet API"""
        url = urljoin(self.base_url + '/', endpoint.lstrip('/'))
        
        # Add format=json parameter since DanNet doesn't support Accept header
        request_params = params or {}
        request_params["format"] = "json"
        
        for attempt in range(MAX_RETRIES):
            try:
                logger.debug(f"Making request to {url} with params {request_params}")
                # Note: allow_redirects=True handles automatic redirects for single search results
                response = self.client.get(url, params=request_params, follow_redirects=True)
                response.raise_for_status()
                
                return response.json()
                
            except httpx.HTTPStatusError as e:
                if e.response.status_code == 404:
                    raise DanNetError(f"Resource not found: {endpoint}")
                elif e.response.status_code == 429:
                    if attempt < MAX_RETRIES - 1:
                        logger.warning(f"Rate limited, retrying... (attempt {attempt + 1})")
                        continue
                    raise DanNetError("Rate limit exceeded")
                else:
                    raise DanNetError(f"HTTP error {e.response.status_code}: {e.response.text}")
            except Exception as e:
                if attempt < MAX_RETRIES - 1:
                    logger.warning(f"Request failed, retrying... (attempt {attempt + 1}): {e}")
                    continue
                raise DanNetError(f"Request failed: {e}")
        
        raise DanNetError("Max retries exceeded")
    
    def search(self, query: str, language: str = "da") -> Dict:
        """Search DanNet for words and synsets"""
        return self._make_request("/dannet/search", {"lemma": query, "lang": language})
    
    def get_resource(self, resource_id: str) -> Dict:
        """Get a specific resource (synset, word, etc.) by ID"""
        return self._make_request(f"/dannet/data/{resource_id}")
    
    def autocomplete(self, prefix: str) -> List[str]:
        """Get autocomplete suggestions for a word prefix
        
        Note: DanNet's autocomplete endpoint requires at least 3 characters
        to avoid returning too much data. Shorter prefixes will return empty results.
        """
        try:
            # Use _make_request to automatically include format=json parameter
            data = self._make_request("/dannet/autocomplete", {"s": prefix})
            
            # Handle different possible response formats
            if isinstance(data, list):
                return data
            elif isinstance(data, dict) and 'suggestions' in data:
                return data['suggestions']
            else:
                return []
                
        except Exception as e:
            logger.error(f"Autocomplete failed for '{prefix}': {e}")
            return []


# Initialize the DanNet client (will be set in main())
dannet_client = None

# Create FastMCP server with helpful instructions
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
   - get_word_synsets("hund") → Find all meanings
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


def get_client():
    """Get the DanNet client, initializing with default URL if not already set"""
    global dannet_client
    if dannet_client is None:
        # Fallback initialization - check environment variable
        is_local = os.getenv('DANNET_MCP_LOCAL', '').lower() == 'true'
        base_url = LOCAL_URL if is_local else REMOTE_URL
        dannet_client = DanNetClient(base_url)
        logger.info(f"Lazy initialization of DanNet client with base URL: {base_url}")
    return dannet_client


def _process_synset_data(entity_data: Dict, inferred_data: Optional[Dict] = None, synset_id: Optional[str] = None) -> Dict[str, Any]:
    """
    Process raw synset entity data into enriched format with extracted fields.
    
    This helper function is used by both get_synset_info() and get_word_synsets() 
    to ensure consistent processing of synset data.
    
    Args:
        entity_data: Raw entity data from DanNet API
        inferred_data: Optional inferred properties from OWL reasoning
        synset_id: Optional synset ID to add for convenience
    
    Returns:
        Dict with processed synset data including extracted fields
    """
    # Start with entity data
    result = dict(entity_data)
    
    # Add inferred metadata if available
    if inferred_data:
        result[':inferred'] = inferred_data
    
    # Add the synset_id for convenience if provided
    if synset_id:
        result['synset_id'] = synset_id
    
    # Extract and format ontological types for better usability
    if ':dns/ontologicalType' in result:
        extracted_types = extract_ontological_types(result[':dns/ontologicalType'])
        result[':dns/ontologicalType_extracted'] = extracted_types
    
    # Extract and format sentiment information for better usability
    if ':dns/sentiment' in result:
        extracted_sentiment = extract_sentiment_info(result[':dns/sentiment'])
        result[':dns/sentiment_extracted'] = extracted_sentiment
    
    return result


def _process_entity_data(entity_data: Dict, inferred_data: Optional[Dict] = None, resource_id: Optional[str] = None) -> Dict[str, Any]:
    """
    Process raw entity data into enriched format.
    
    This is a more generic version of _process_synset_data that works for any entity type.
    
    Args:
        entity_data: Raw entity data from DanNet API
        inferred_data: Optional inferred properties from OWL reasoning
        resource_id: Optional resource ID to add for convenience
    
    Returns:
        Dict with processed entity data
    """
    # Start with entity data
    result = dict(entity_data)
    
    # Add inferred metadata if available
    if inferred_data:
        result[':inferred'] = inferred_data
    
    # Add the resource_id for convenience if provided
    if resource_id:
        result['resource_id'] = resource_id
    
    # Only process synset-specific fields if this is actually a synset
    entity_types = result.get(':rdf/type', [])
    if isinstance(entity_types, str):
        entity_types = [entity_types]
    
    if ':ontolex/LexicalConcept' in entity_types:
        # This is a synset, apply synset-specific processing
        if ':dns/ontologicalType' in result:
            extracted_types = extract_ontological_types(result[':dns/ontologicalType'])
            result[':dns/ontologicalType_extracted'] = extracted_types
        
        if ':dns/sentiment' in result:
            extracted_sentiment = extract_sentiment_info(result[':dns/sentiment'])
            result[':dns/sentiment_extracted'] = extracted_sentiment
    
    return result


def extract_language_string(value: Union[str, Dict]) -> Optional[str]:
    """Extract string value from DanNet language-tagged values"""
    if isinstance(value, str):
        return value
    elif isinstance(value, dict) and 'value' in value:
        return value['value']
    elif isinstance(value, list) and value:
        return extract_language_string(value[0])
    return None


def parse_resource_id(resource_uri: str) -> str:
    """Extract resource ID from a DanNet URI"""
    if isinstance(resource_uri, str):
        # Handle URIs like ":dn/synset-1876", ":dn/word-123", or full URIs
        if resource_uri.startswith(':dn/'):
            # Remove the :dn/ prefix - the dn namespace maps to /dannet/data/
            return resource_uri[4:]  # Strip ":dn/"
        elif ':' in resource_uri:
            return resource_uri.split(':')[-1]
        elif '/' in resource_uri:
            return resource_uri.split('/')[-1]
        return resource_uri
    return str(resource_uri)


def extract_ontological_types(ontotype_data):
    """
    Extract and format ontological types from DanNet RDF bag structure.
    
    DanNet uses ontological types from the EuroWordNet taxonomy to classify synsets
    semantically. These types indicate what kind of entity a synset represents
    (e.g., Animal, Human, Object, Event, etc.).

    Args:
        ontotype_data: List containing RDF bag structure with :rdf/_0, :rdf/_1, etc.
    
    Returns:
        List of dnc: type strings (e.g., ['dnc/Animal', 'dnc/Living']),
        or the original data if not in expected format
    """
    if not isinstance(ontotype_data, list) or not ontotype_data:
        return ontotype_data
    
    bag_data = ontotype_data[0]
    if not isinstance(bag_data, dict):
        return ontotype_data
    
    # Extract dnc: types from RDF bag structure
    concepts = []
    for key, value in bag_data.items():
        if key.startswith(':rdf/_') and isinstance(value, list) and value:
            concept = value[0]
            if isinstance(concept, str) and concept.startswith(':dnc/'):
                # Remove the leading colon to get clean dnc:Concept format
                concepts.append(concept[1:])
    
    # Sort for consistent ordering
    concepts.sort()
    return concepts if concepts else ontotype_data


def extract_sentiment_info(sentiment_data):
    """
    Extract and format sentiment information from DanNet sentiment structure.
    
    DanNet includes sentiment annotations for words that carry emotional polarity,
    using the MARL (Machine-Readable Language) vocabulary.

    Args:
        sentiment_data: List containing sentiment structure with :marl properties
    
    Returns:
        Dict with polarity ('positive', 'negative', 'neutral') and numerical value,
        or the original data if not in expected format
    """
    if not isinstance(sentiment_data, list) or not sentiment_data:
        return sentiment_data
    
    sentiment_obj = sentiment_data[0]
    if not isinstance(sentiment_obj, dict):
        return sentiment_data
    
    result = {}
    
    # Extract polarity
    polarity = sentiment_obj.get(':marl/hasPolarity')
    if isinstance(polarity, list) and polarity:
        polarity_value = polarity[0]
        if isinstance(polarity_value, str):
            # Remove prefix and colon to get clean value
            result['polarity'] = polarity_value.replace(':marl/', '')
    
    # Extract numerical value
    polarity_val = sentiment_obj.get(':marl/polarityValue')
    if isinstance(polarity_val, list) and polarity_val:
        if isinstance(polarity_val[0], (int, float)):
            result['value'] = polarity_val[0]
    
    return result if result else sentiment_data


@mcp.tool()
def get_word_synsets(query: str, language: str = "da") -> Union[List[SearchResult], Dict[str, Any]]:
    """
    Get synsets (word meanings) for a Danish word, returning a sorted list of lexical concepts.

    DanNet follows the OntoLex-Lemon model where:
    - Words (ontolex:LexicalEntry) evoke concepts through senses
    - Synsets (ontolex:LexicalConcept) represent units of meaning
    - Multiple words can share the same synset (synonyms)
    - One word can have multiple synsets (polysemy)

    This function returns all synsets associated with a word, effectively giving you
    all the different meanings/senses that word can have. Each synset represents
    a distinct semantic concept with its own definition and semantic relationships.

    Common patterns in Danish:
    - Nouns often have multiple senses (e.g., "kage" = cake/lump)
    - Verbs distinguish motion vs. state (e.g., "løbe" = run/flow)
    - Check synset's dns:ontologicalType for semantic classification

    RETURN BEHAVIOR:
    This function has two possible return modes depending on search results:
    
    1. MULTIPLE RESULTS: Returns List[SearchResult] with basic information for each synset
    2. SINGLE RESULT (redirect): Returns full synset data Dict when DanNet automatically 
       redirects to a single synset. This provides immediate access to all semantic 
       relationships, ontological types, sentiment data, and other rich information 
       without requiring a separate get_synset_info() call.

    The single-result case is equivalent to calling get_synset_info() on the synset,
    providing the same comprehensive RDF data structure with all semantic relations.

    Args:
        query: The Danish word or phrase to search for
    
        language: Language for results (default: "da" for Danish, "en" for English labels)
    Returns:
        MULTIPLE RESULTS: List of SearchResult objects with:
        - word: The lexical form
        - synset_id: Unique synset identifier (format: synset-NNNNN)
        - label: Human-readable synset label (e.g., "{kage_1§1}")
        - definition: Brief semantic definition (may be truncated with "...")
        
        SINGLE RESULT: Dict with complete synset data including:
        - All RDF properties with namespace prefixes (e.g., :wn/hypernym)
        - :dns/ontologicalType_extracted → human-readable semantic types
        - :dns/sentiment_extracted → parsed sentiment (if present)
        - synset_id → clean identifier for convenience
        - All semantic relationships and linguistic properties

    Examples:
        # Multiple results case
        results = get_word_synsets("hund")
        # Returns list of synsets for all meanings of "hund"
        # => [SearchResult, SearchResult, SearchResult, SearchResult]
        
        # Single result case (redirect)
        result = get_word_synsets("svinkeærinde")  
        # Returns complete synset data for unique word
        # => {':wn/hypernym': ':dn/synset-11677', ':dns/sentiment_extracted': {...}, ...}
    """
    try:
        results = get_client().search(query, language)
        search_results = []
        
        # Handle DanNet's response structure - check for both single entity and multiple results
        if isinstance(results, dict):
            # Check if this is a single entity response (redirected from search)
            if ':entity' in results:
                # This is a single synset entity response - return full synset data
                entity = results[':entity']
                
                # Extract synset ID from the subject
                synset_id = None
                subject = results.get(':subject', '')
                if isinstance(subject, str) and subject.startswith(':dn/'):
                    synset_id = subject.replace(':dn/', '')
                
                if synset_id:
                    # Use helper function to process synset data consistently
                    return _process_synset_data(
                        entity_data=entity,
                        inferred_data=results.get(':inferred'),
                        synset_id=synset_id
                    )
                    
            else:
                # Check for multiple search results in EDN format
                search_data = results.get(':search-results', results.get('search-results', {}))
                lemma = results.get(':lemma', results.get('lemma', query))
                
                if isinstance(search_data, dict):
                    for synset_key, synset_data in search_data.items():
                        if not isinstance(synset_data, dict):
                            continue
                        
                        # Extract synset ID from the key
                        synset_id = None
                        if isinstance(synset_key, str) and synset_key.startswith(':dn/'):
                            synset_id = synset_key.replace(':dn/', '')
                        
                        if synset_id:
                            # Get full synset data to extract definition and label
                            try:
                                synset_info = get_synset_info(synset_id)
                                definition = extract_language_string(synset_info.get(':skos/definition'))
                                label = extract_language_string(synset_info.get(':rdfs/label'))
                                
                                # Include synset even if no definition (some synsets only have labels)
                                search_results.append(SearchResult(
                                    word=lemma,
                                    synset_id=synset_id,
                                    label=label,
                                    definition=definition or ""
                                ))
                            except Exception as e:
                                logger.warning(f"Failed to fetch synset {synset_id}: {e}")
                                continue
        
        return search_results
        
    except Exception as e:
        raise RuntimeError(f"Search failed: {e}")


@mcp.tool()
def get_entity_info(identifier: str, namespace: str = "dn") -> Dict[str, Any]:
    """
    Get comprehensive RDF data for any entity in the DanNet database.
    
    Supports both DanNet entities and external vocabulary entities loaded
    into the triplestore from various schemas and datasets.

    UNDERSTANDING THE DATA MODEL:
    The DanNet database contains entities from multiple sources:
    - DanNet entities (namespace="dn"): synsets, words, senses, and other resources
    - External entities (other namespaces): OntoLex vocabulary, Inter-Lingual Index, etc.
    
    All entities follow RDF patterns with namespace prefixes for properties and relationships.

    NAVIGATION TIPS:
    - DanNet synsets have rich semantic relationships (wn:hypernym, wn:hyponym, etc.)
    - External entities provide vocabulary definitions and cross-references
    - Use parse_resource_id() on URI references to get clean IDs
    - Check :rdf/type to understand what kind of entity you're working with

    Args:
        identifier: Entity identifier (e.g., "synset-3047", "word-11021628", "LexicalConcept", "i76470")
        namespace: Namespace for the entity (default: "dn" for DanNet entities)
                  - "dn": DanNet entities via /dannet/data/ endpoint
                  - Other values: External entities via /dannet/external/{namespace}/ endpoint
                  - Common external namespaces: "ontolex", "ili", "wn", "lexinfo", etc.

    Returns:
        Dict containing:
        - All RDF properties with namespace prefixes (e.g., :wn/hypernym, :ontolex/evokes)
        - :inferred → properties derived through OWL reasoning (if available)
        - For DanNet synsets: extracted fields like :dns/ontologicalType_extracted
        - Entity-specific convenience fields (synset_id, resource_id, etc.)

    Examples:
        # DanNet entities
        get_entity_info("synset-3047")  # DanNet synset
        get_entity_info("word-11021628")  # DanNet word
        get_entity_info("sense-21033604")  # DanNet sense
        
        # External vocabulary entities  
        get_entity_info("LexicalConcept", namespace="ontolex")  # OntoLex class definition
        get_entity_info("i76470", namespace="ili")  # Inter-Lingual Index entry
        get_entity_info("noun", namespace="lexinfo")  # Lexinfo part-of-speech
    """
    try:
        if namespace == "dn":
            # DanNet entities use the standard data endpoint
            endpoint_path = f"dannet/data/{identifier}"
        else:
            # External entities use the external endpoint
            endpoint_path = f"dannet/external/{namespace}/{identifier}"
        
        # Make the request using the appropriate endpoint
        client = get_client()
        url = f"{client.base_url}/{endpoint_path}"
        
        # Use same request pattern as get_resource but with custom path
        request_params = {"format": "json"}
        
        for attempt in range(MAX_RETRIES):
            try:
                logger.debug(f"Making request to {url} with params {request_params}")
                response = client.client.get(url, params=request_params, follow_redirects=True)
                response.raise_for_status()
                data = response.json()
                break
                
            except httpx.HTTPStatusError as e:
                if e.response.status_code == 404:
                    raise DanNetError(f"Entity not found: {namespace}/{identifier}")
                elif e.response.status_code == 429:
                    if attempt < MAX_RETRIES - 1:
                        logger.warning(f"Rate limited, retrying... (attempt {attempt + 1})")
                        continue
                    raise DanNetError("Rate limit exceeded")
                else:
                    raise DanNetError(f"HTTP error {e.response.status_code}: {e.response.text}")
            except Exception as e:
                if attempt < MAX_RETRIES - 1:
                    logger.warning(f"Request failed, retrying... (attempt {attempt + 1}): {e}")
                    continue
                raise DanNetError(f"Request failed: {e}")
        
        # Check for entity data in response
        if not data or ':entity' not in data:
            raise DanNetError(f"No entity data found for {namespace}/{identifier}")
        
        # Process the entity data with appropriate handling
        if namespace == "dn":
            # For DanNet entities, determine if it's a synset for special processing
            entity_types = data[':entity'].get(':rdf/type', [])
            if isinstance(entity_types, str):
                entity_types = [entity_types]
            
            if ':ontolex/LexicalConcept' in entity_types:
                # This is a DanNet synset - use synset-specific processing
                return _process_synset_data(
                    entity_data=data[':entity'],
                    inferred_data=data.get(':inferred'),
                    synset_id=identifier
                )
            else:
                # Other DanNet entity - use generic processing
                return _process_entity_data(
                    entity_data=data[':entity'],
                    inferred_data=data.get(':inferred'),
                    resource_id=identifier
                )
        else:
            # External entity - use generic processing
            return _process_entity_data(
                entity_data=data[':entity'],
                inferred_data=data.get(':inferred'),
                resource_id=f"{namespace}/{identifier}"
            )
        
    except Exception as e:
        raise RuntimeError(f"Failed to get entity info: {e}")


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
    # Clean the synset_id and ensure proper prefix
    clean_id = parse_resource_id(synset_id)
    if not clean_id.startswith('synset-'):
        clean_id = f"synset-{clean_id}" if clean_id.isdigit() else clean_id
    
    return get_entity_info(clean_id, namespace="dn")


@mcp.tool()
def get_word_info(word_id: str) -> Dict[str, Any]:
    """
    Get comprehensive RDF data for a DanNet word (lexical entry).

    UNDERSTANDING THE DATA MODEL:
    Words are ontolex:LexicalEntry instances representing lexical forms.
    They connect to synsets via senses and have morphological information.

    KEY RELATIONSHIPS:

    1. LEXICAL CONNECTIONS:
       - ontolex:evokes → synsets this word can express
       - ontolex:sense → sense instances connecting word to synsets
       - ontolex:canonicalForm → canonical form with written representation

    2. MORPHOLOGICAL PROPERTIES:
       - lexinfo:partOfSpeech → part of speech classification
       - wn:partOfSpeech → WordNet part of speech
       - ontolex:canonicalForm/ontolex:writtenRep → written form

    3. CROSS-REFERENCES:
       - owl:sameAs → equivalent resources in other datasets
       - dns:source → source URL for this word entry

    NAVIGATION TIPS:
    - Follow ontolex:evokes to find synsets this word expresses
    - Check ontolex:sense for detailed sense information
    - Use parse_resource_id() on URI references to get clean IDs

    Args:
        word_id: Word identifier (e.g., "word-11021628" or just "11021628")

    Returns:
        Dict containing:
        - All RDF properties with namespace prefixes (e.g., :ontolex/evokes)
        - resource_id → clean identifier for convenience
        - All linguistic properties and relationships

    Example:
        info = get_word_info("word-11021628")  # "hund" word
        # Check info[':ontolex/evokes'] for synsets this word can express
        # Check info[':ontolex/sense'] for senses
    """
    # Clean the word_id and ensure proper prefix
    clean_id = parse_resource_id(word_id)
    if not clean_id.startswith('word-'):
        clean_id = f"word-{clean_id}" if clean_id.isdigit() else clean_id
    
    return get_entity_info(clean_id, namespace="dn")


@mcp.tool()
def get_sense_info(sense_id: str) -> Dict[str, Any]:
    """
    Get comprehensive RDF data for a DanNet sense (lexical sense).

    UNDERSTANDING THE DATA MODEL:
    Senses are ontolex:LexicalSense instances connecting words to synsets.
    They represent specific meanings of words with examples and definitions.

    KEY RELATIONSHIPS:

    1. LEXICAL CONNECTIONS:
       - ontolex:isSenseOf → word this sense belongs to
       - ontolex:isLexicalizedSenseOf → synset this sense represents

    2. SEMANTIC INFORMATION:
       - lexinfo:senseExample → usage examples in context
       - rdfs:label → sense label (e.g., "hund_1§1")

    3. SOURCE INFORMATION:
       - dns:source → source URL for this sense entry

    NAVIGATION TIPS:
    - Follow ontolex:isSenseOf to find the parent word
    - Follow ontolex:isLexicalizedSenseOf to find the synset
    - Check lexinfo:senseExample for usage examples
    - Use parse_resource_id() on URI references to get clean IDs

    Args:
        sense_id: Sense identifier (e.g., "sense-21033604" or just "21033604")

    Returns:
        Dict containing:
        - All RDF properties with namespace prefixes (e.g., :ontolex/isSenseOf)
        - resource_id → clean identifier for convenience
        - All sense properties and relationships

    Example:
        info = get_sense_info("sense-21033604")  # "hund_1§1" sense
        # Check info[':ontolex/isSenseOf'] for parent word
        # Check info[':ontolex/isLexicalizedSenseOf'] for synset
        # Check info[':lexinfo/senseExample'] for usage examples
    """
    # Clean the sense_id and ensure proper prefix
    clean_id = parse_resource_id(sense_id)
    if not clean_id.startswith('sense-'):
        clean_id = f"sense-{clean_id}" if clean_id.isdigit() else clean_id
    
    return get_entity_info(clean_id, namespace="dn")


@mcp.tool()
def get_word_synonyms(word: str) -> str:
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
    
    Returns:
        Comma-separated string of synonymous words (aggregated across all word senses)
        
        # TODO: Change back to List[str] if MCP framework serialization issue gets fixed.
        # Currently returns string to work around concatenation without separators.

    Example:
        synonyms = get_word_synonyms("løbe")
        # Returns: "rende, spurte, flyde, strømme"

    Note: Check synset definitions to understand which synonyms apply
    to which meaning (polysemy is common in Danish).
    """
    try:
        # Search for the word to find its synsets
        search_results = get_word_synsets(word)
        synonyms = set()
        
        # Handle both return types from get_word_synsets
        if isinstance(search_results, dict):
            # Single synset result - convert to list format
            synset_id = search_results.get('synset_id')
            if synset_id:
                search_results = [type('SearchResult', (), {
                    'synset_id': synset_id, 
                    'word': word
                })()]
            else:
                return ""
        
        for result in search_results:
            # Only process exact matches
            if not (hasattr(result, 'synset_id') and result.synset_id and 
                   hasattr(result, 'word') and result.word.lower() == word.lower()):
                continue
                
            # Get the full synset data
            synset_data = get_synset_info(result.synset_id)
            
            # Extract word IDs from :ontolex/isEvokedBy
            evoked_by = synset_data.get(':ontolex/isEvokedBy', [])
            
            # Normalize to list if single string
            if isinstance(evoked_by, str):
                evoked_by = [evoked_by]
            elif not isinstance(evoked_by, list):
                continue
            
            # Process each word reference
            for word_ref in evoked_by:
                # Extract the word ID from the reference
                word_id = parse_resource_id(word_ref)
                
                # Fetch the word entity
                word_data = get_client().get_resource(word_id)
                if not word_data or ':entity' not in word_data:
                    continue
                
                # Extract the lemma using the existing helper function
                label_data = word_data[':entity'].get(':rdfs/label')
                lemma = extract_language_string(label_data)
                
                # Strip quotes if present
                if lemma:
                    lemma = lemma.strip('"')
                
                # Add to synonyms if it's not the input word
                if lemma and lemma.lower() != word.lower():
                    synonyms.add(lemma)
        
        return ", ".join(sorted(list(synonyms)))
        
    except Exception as e:
        raise RuntimeError(f"Failed to find synonyms: {e}")


@mcp.tool()
def autocomplete_danish_word(prefix: str, max_results: int = 10) -> str:
    """
    Get autocomplete suggestions for Danish word prefixes.
    
    Useful for discovering Danish vocabulary or finding the correct spelling
    of words. Returns lemma forms (dictionary forms) of words.

    Args:
        prefix: The beginning of a Danish word (minimum 3 characters required)
    
        max_results: Maximum number of suggestions to return (default: 10)
    Returns:
        Comma-separated string of word completions in alphabetical order
        
        # TODO: Change back to List[str] if MCP framework serialization issue gets fixed.
        # Currently returns string to work around concatenation without separators.

    Note: DanNet's autocomplete requires at least 3 characters. Shorter prefixes
    will return empty results to avoid overwhelming amounts of data.

    Example:
        suggestions = autocomplete_danish_word("hyg", 5)
        # Returns: "hygge, hyggelig, hygiejne"
    """
    try:
        suggestions = get_client().autocomplete(prefix)
        limited_suggestions = suggestions[:max_results]
        return ", ".join(limited_suggestions)
        
    except Exception as e:
        raise RuntimeError(f"Autocomplete failed: {e}")


@mcp.tool()
def switch_dannet_server(server: str) -> Dict[str, str]:
    """
    Switch between local and remote DanNet servers on the fly.
    
    This tool allows you to change the DanNet server endpoint during runtime
    without restarting the MCP server. Useful for switching between development
    (local) and production (remote) servers.

    Args:
        server: Server to switch to. Options:
               - "local": Use localhost:3456 (development server)
               - "remote": Use wordnet.dk (production server)
               - Custom URL: Any valid URL starting with http:// or https://
    
    Returns:
        Dict with status information:
        - status: "success" or "error"
        - message: Description of the operation
        - previous_url: The URL that was previously active
        - current_url: The URL that is now active

    Example:
        # Switch to local development server
        result = switch_dannet_server("local")
        
        # Switch to production server
        result = switch_dannet_server("remote")
        
        # Switch to custom server
        result = switch_dannet_server("https://my-custom-dannet.example.com")
    """
    global dannet_client
    
    try:
        # Store the previous URL for response
        previous_url = dannet_client.base_url if dannet_client else "None"
        
        # Determine the target URL
        if server.lower() == "local":
            new_url = LOCAL_URL
        elif server.lower() == "remote":
            new_url = REMOTE_URL
        elif server.startswith(("http://", "https://")):
            new_url = server
        else:
            return {
                "status": "error",
                "message": f"Invalid server specification: '{server}'. Use 'local', 'remote', or a valid URL.",
                "previous_url": previous_url,
                "current_url": previous_url
            }
        
        # Create new client instance
        dannet_client = DanNetClient(new_url)
        
        # Test the connection with a simple request
        try:
            # Try to access the base endpoint to verify connectivity
            test_response = dannet_client.client.get(f"{new_url}/")
            if test_response.status_code not in [200, 404]:  # 404 is okay for root endpoint
                logger.warning(f"Server responded with status {test_response.status_code}, but continuing...")
        except Exception as conn_error:
            logger.warning(f"Could not verify connectivity to {new_url}: {conn_error}")
            # Continue anyway - the server might be accessible for API calls even if root isn't
        
        logger.info(f"DanNet client switched from {previous_url} to {new_url}")
        
        return {
            "status": "success",
            "message": f"Successfully switched DanNet server from {previous_url} to {new_url}",
            "previous_url": previous_url,
            "current_url": new_url
        }
        
    except Exception as e:
        error_msg = f"Failed to switch server: {e}"
        logger.error(error_msg)
        return {
            "status": "error",
            "message": error_msg,
            "previous_url": previous_url if 'previous_url' in locals() else "Unknown",
            "current_url": dannet_client.base_url if dannet_client else "Unknown"
        }


@mcp.tool()
def get_current_dannet_server() -> Dict[str, str]:
    """
    Get information about the currently active DanNet server.
    
    Returns:
        Dict with current server information:
        - server_url: The base URL of the current DanNet server
        - server_type: "local", "remote", or "custom"
        - status: Connection status information
    
    Example:
        info = get_current_dannet_server()
        # Returns: {"server_url": "https://wordnet.dk", "server_type": "remote", "status": "active"}
    """
    global dannet_client
    
    if dannet_client is None:
        return {
            "server_url": "None",
            "server_type": "uninitialized", 
            "status": "No client initialized"
        }
    
    current_url = dannet_client.base_url
    
    # Determine server type
    if current_url == LOCAL_URL:
        server_type = "local"
    elif current_url == REMOTE_URL:
        server_type = "remote"
    else:
        server_type = "custom"
    
    # Try to check server status
    try:
        # Simple connectivity test
        test_response = dannet_client.client.get(f"{current_url}/", timeout=5.0)
        status = f"Connected (HTTP {test_response.status_code})"
    except Exception as e:
        status = f"Connection issue: {str(e)[:100]}"
    
    return {
        "server_url": current_url,
        "server_type": server_type,
        "status": status
    }


@mcp.resource("dannet://ontological-types")
def get_ontological_types_schema() -> str:
    """
    Access the ontological types taxonomy (dnc: namespace) - DanNet's extended EuroWordNet classification.
    
    Returns:
        RDF schema defining ontological types like dnc:Animal, dnc:Human, dnc:Object, etc.
        
    This resource is essential for understanding the :dns/ontologicalType_extracted 
    values returned by DanNet synset tools. DanNet uses an EXTENDED version of the 
    EuroWordNet ontological type system, adding Danish-specific semantic categories 
    beyond the original EuroWordNet taxonomy.
    """
    return get_schema_resource("dnc")


@mcp.resource("dannet://dannet-schema")
def get_dannet_schema() -> str:
    """
    Access the DanNet-specific schema (dns: namespace).
    
    Returns:
        RDF schema defining DanNet properties like dns:ontologicalType, dns:sentiment, etc.
        
    This resource explains DanNet's custom semantic properties that extend 
    standard WordNet with Danish-specific linguistic annotations.
    """
    return get_schema_resource("dns")


@mcp.resource("dannet://wordnet-schema")
def get_wordnet_schema() -> str:
    """
    Access the Global WordNet schema (wn: namespace).
    
    Returns:
        RDF schema defining standard WordNet relations like wn:hypernym, wn:hyponym, etc.
        
    This resource explains the semantic relationships between synsets following 
    international WordNet standards.
    """
    return get_schema_resource("wn")


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
    try:
        client = get_client()
        response = client.client.get(f"{client.base_url}/schema/{prefix}")
        response.raise_for_status()
        return response.text
    except Exception as e:
        return f"Error accessing schema '{prefix}': {e}"


@mcp.resource("dannet://schemas")
def list_available_schemas() -> str:
    """
    List all available RDF schemas with descriptions and relevance to DanNet.
    
    Returns:
        JSON listing of all schemas with metadata and usage information
    """

    schemas = {
        "dannet_core": {
            "description": "Essential schemas for understanding DanNet data structure",
            "schemas": {
                "dns": {
                    "uri": "https://wordnet.dk/dannet/schema/",
                    "title": "DanNet Schema",
                    "description": "DanNet-specific relations, properties, and classes",
                    "key_properties": [
                        "dns:shortLabel", "dns:sentiment", "dns:ontologicalType",
                        "dns:usedFor", "dns:orthogonalHypernym", "dns:eqHypernym"
                    ],
                    "relevance": "essential"
                },
                "dnc": {
                    "uri": "https://wordnet.dk/dannet/concepts/",
                    "title": "DanNet Concepts",
                    "description": "All DanNet and EuroWordNet ontological types",
                    "key_concepts": [
                        "dnc:Animal", "dnc:Human", "dnc:Object", "dnc:Institution",
                        "dnc:BodyPart", "dnc:Plant", "dnc:Place"
                    ],
                    "relevance": "essential"
                }
            }
        },
        "linguistic_core": {
            "description": "Core linguistic vocabularies used in DanNet",
            "schemas": {
                "ontolex": {
                    "uri": "http://www.w3.org/ns/lemon/ontolex#",
                    "title": "OntoLex-Lemon",
                    "description": "W3C vocabulary for representing lexical data",
                    "key_classes": [
                        "ontolex:LexicalConcept", "ontolex:LexicalEntry",
                        "ontolex:Form", "ontolex:isEvokedBy"
                    ],
                    "relevance": "core"
                },
                "wn": {
                    "uri": "https://globalwordnet.github.io/schemas/wn#",
                    "title": "Global WordNet Schema",
                    "description": "Standard schema for WordNet synsets and relations",
                    "key_properties": [
                        "wn:hypernym", "wn:hyponym", "wn:similar", "wn:antonym",
                        "wn:lexfile", "wn:ili", "wn:eq_synonym"
                    ],
                    "relevance": "core"
                },
                "lexinfo": {
                    "uri": "http://www.lexinfo.net/ontology/3.0/lexinfo#",
                    "title": "LexInfo",
                    "description": "Ontology for lexical information and linguistic categories",
                    "usage": "Part-of-speech tags and morphological features",
                    "relevance": "supporting"
                }
            }
        },
        "semantic_web_standards": {
            "description": "Standard W3C vocabularies for RDF and semantic web",
            "schemas": {
                "rdf": {
                    "uri": "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
                    "title": "RDF",
                    "description": "Core RDF vocabulary",
                    "key_properties": ["rdf:type", "rdf:value"],
                    "relevance": "foundational"
                },
                "rdfs": {
                    "uri": "http://www.w3.org/2000/01/rdf-schema#",
                    "title": "RDF Schema",
                    "description": "Basic schema vocabulary for RDF",
                    "key_properties": ["rdfs:label", "rdfs:comment", "rdfs:subClassOf", "rdfs:domain", "rdfs:range"],
                    "relevance": "foundational"
                },
                "owl": {
                    "uri": "http://www.w3.org/2002/07/owl#",
                    "title": "Web Ontology Language",
                    "description": "Rich ontology vocabulary for complex relationships",
                    "key_classes": ["owl:Class", "owl:ObjectProperty", "owl:DatatypeProperty", "owl:inverseOf"],
                    "relevance": "foundational"
                },
                "skos": {
                    "uri": "http://www.w3.org/2004/02/skos/core#",
                    "title": "Simple Knowledge Organization System",
                    "description": "Vocabulary for thesauri and classification schemes",
                    "key_properties": ["skos:definition", "skos:altLabel", "skos:broader", "skos:narrower"],
                    "relevance": "supporting"
                }
            }
        },
        "metadata": {
            "description": "Metadata and annotation vocabularies",
            "schemas": {
                "dc": {
                    "uri": "http://purl.org/dc/terms/",
                    "title": "Dublin Core Terms",
                    "description": "Standard metadata vocabulary",
                    "key_properties": ["dc:subject", "dc:title", "dc:description", "dc:license", "dc:issued"],
                    "relevance": "metadata"
                },
                "foaf": {
                    "uri": "http://xmlns.com/foaf/0.1/",
                    "title": "Friend of a Friend",
                    "description": "Vocabulary for people and organizations",
                    "relevance": "metadata"
                },
                "marl": {
                    "uri": "http://www.gsi.upm.es/ontologies/marl/ns#",
                    "title": "MARL Sentiment",
                    "description": "Vocabulary for sentiment and emotion annotation",
                    "relevance": "specialized"
                }
            }
        }
    }
    
    return json.dumps(schemas, indent=2)


@mcp.resource("dannet://namespaces")
def get_namespace_documentation() -> str:
    """
    Comprehensive documentation of all namespaces used in DanNet RDF data.
    
    Returns:
        JSON documentation with namespace URIs, prefixes, and usage patterns
    """

    namespaces = {
        "usage_guide": {
            "understanding_prefixes": "Namespace prefixes map to full URIs in RDF data. For example, 'dns:sentiment' expands to 'https://wordnet.dk/dannet/schema/sentiment'",
            "prefix_resolution": "Use the schema resources to get full ontology definitions for each namespace",
            "data_interpretation": "Most DanNet synset properties use these prefixes. Core data is in 'dn:' namespace, relations defined in 'dns:' namespace"
        },
        "namespace_mappings": {
            "dn": {
                "uri": "https://wordnet.dk/dannet/data/",
                "description": "Core DanNet data namespace - all synsets, words, senses, and instances",
                "examples": ["dn:synset-3047", "dn:word-11021722", "dn:sense-21045953"],
                "usage": "Primary namespace for all DanNet entities"
            },
            "dns": {
                "uri": "https://wordnet.dk/dannet/schema/",
                "description": "DanNet-specific schema - properties and classes unique to DanNet",
                "examples": ["dns:sentiment", "dns:ontologicalType", "dns:usedFor", "dns:orthogonalHypernym"],
                "usage": "Custom relations and properties extending standard WordNet"
            },
            "dnc": {
                "uri": "https://wordnet.dk/dannet/concepts/",
                "description": "Ontological types from DanNet and EuroWordNet taxonomies",
                "examples": ["dnc:Animal", "dnc:Human", "dnc:Institution", "dnc:BodyPart"],
                "usage": "Semantic classification of synsets via dns:ontologicalType"
            },
            "ontolex": {
                "uri": "http://www.w3.org/ns/lemon/ontolex#",
                "description": "W3C OntoLex-Lemon vocabulary for lexical resources",
                "examples": ["ontolex:LexicalConcept", "ontolex:isEvokedBy", "ontolex:lexicalizedSense"],
                "usage": "Structural representation of lexical data - synsets are ontolex:LexicalConcept"
            },
            "wn": {
                "uri": "https://globalwordnet.github.io/schemas/wn#",
                "description": "Global WordNet Association schema for synset relations",
                "examples": ["wn:hypernym", "wn:hyponym", "wn:similar", "wn:lexfile", "wn:ili"],
                "usage": "Standard WordNet semantic relations between synsets"
            },
            "skos": {
                "uri": "http://www.w3.org/2004/02/skos/core#",
                "description": "W3C vocabulary for knowledge organization systems",
                "examples": ["skos:definition", "skos:altLabel", "skos:broader"],
                "usage": "Definitions and alternative labels for synsets"
            },
            "rdfs": {
                "uri": "http://www.w3.org/2000/01/rdf-schema#",
                "description": "RDF Schema vocabulary for basic ontology constructs",
                "examples": ["rdfs:label", "rdfs:comment", "rdfs:subClassOf"],
                "usage": "Basic labeling and classification of resources"
            },
            "dc": {
                "uri": "http://purl.org/dc/terms/",
                "description": "Dublin Core metadata vocabulary",
                "examples": ["dc:subject", "dc:title", "dc:issued"],
                "usage": "Metadata about synsets and other resources"
            }
        },
        "common_patterns": {
            "synset_structure": {
                "description": "Typical properties found on synset resources",
                "core_properties": [
                    "rdf:type → ontolex:LexicalConcept",
                    "rdfs:label → Human readable synset label",
                    "skos:definition → Definition text",
                    "dns:ontologicalType → Semantic classification (dnc: types)",
                    "ontolex:isEvokedBy → Words that evoke this synset",
                    "wn:hypernym/wn:hyponym → Taxonomic relations"
                ]
            },
            "word_structure": {
                "description": "Typical properties found on word resources",
                "core_properties": [
                    "rdf:type → ontolex:LexicalEntry",
                    "rdfs:label → The word form",
                    "ontolex:evokes → Synsets this word can evoke"
                ]
            }
        }
    }
    
    return json.dumps(namespaces, indent=2)


def _detect_available_server() -> str:
    """
    Detect if local DanNet server is available, fallback to remote.
    
    Returns:
        str: URL of available server (local preferred, remote fallback)
    """
    try:
        # Test local server connectivity with a quick timeout
        with httpx.Client(timeout=3.0) as test_client:
            response = test_client.get(LOCAL_URL)
            # Accept any response (including 404) as indication the server is running
            if response.status_code < 500:
                logger.info(f"Local DanNet server detected and available at {LOCAL_URL}")
                return LOCAL_URL
    except Exception as e:
        logger.debug(f"Local server not available at {LOCAL_URL}: {e}")
    
    logger.info(f"Using remote DanNet server at {REMOTE_URL} (local server not available)")
    return REMOTE_URL


@mcp.prompt()
def analyze_danish_word(word: str, include_examples: bool = True) -> str:
    """
    Generate a comprehensive analysis prompt for a Danish word using DanNet data.
    
    Args:
        word: The Danish word to analyze
        include_examples: Whether to request usage examples
    
    Returns:
        Analysis prompt for the word
    """
    example_section = """
    5. Usage examples and contexts where this word appears
    6. Collocations and common phrases""" if include_examples else ""
    
    return f"""Please provide a comprehensive linguistic analysis of the Danish word "{word}" using DanNet data.

Include the following information:
1. All synsets (word senses/meanings) for this word
2. Detailed definitions for each sense
3. Synonyms and semantically related words
4. Part of speech information and grammatical properties{example_section}
7. Semantic relationships (hypernyms, hyponyms, meronyms if applicable)

Use the available DanNet tools to gather this information and provide a structured analysis that would be useful for language learning or linguistic research."""


@mcp.prompt()
def compare_danish_words(word1: str, word2: str) -> str:
    """
    Generate a prompt for semantic comparison of two Danish words.
    
    Args:
        word1: First Danish word
        word2: Second Danish word
    
    Returns:
        Comparison prompt for the words
    """
    return f"""Please compare the Danish words "{word1}" and "{word2}" semantically using DanNet data.

Analyze and compare:
1. Overlapping synsets or semantic fields between the words
2. Distinct meanings unique to each word  
3. Hierarchical semantic relationships (if any exist between them)
4. Degree of semantic similarity or distance
5. Different contexts where each word would be preferred
6. Synonyms that are unique to each word vs. shared synonyms

Use DanNet tools to gather comprehensive data about both words, then provide a detailed comparison that highlights their semantic relationship and differences. Include specific synset IDs and definitions where relevant."""


def main():
    """Main entry point with command line argument parsing"""
    global dannet_client
    
    parser = argparse.ArgumentParser(
        description="DanNet MCP Server - Access Danish WordNet data via MCP. Defaults to local server if available, otherwise uses remote server."
    )
    parser.add_argument(
        "--local",
        action="store_true",
        help="Force local development server (localhost:3456)"
    )
    parser.add_argument(
        "--base-url",
        type=str,
        help="Custom base URL for DanNet API"
    )
    parser.add_argument(
        "--debug",
        action="store_true",
        help="Enable debug logging"
    )
    
    args = parser.parse_args()
    
    if args.debug:
        logging.getLogger().setLevel(logging.DEBUG)
    
    # Check environment variable for local mode
    env_local = os.getenv('DANNET_MCP_LOCAL', '').lower() == 'true'
    
    # Determine base URL with precedence: CLI args > env vars > auto-detect > remote fallback
    if args.base_url:
        # Explicit base URL argument takes highest precedence
        base_url = args.base_url
        logger.info(f"Using explicitly specified base URL: {base_url}")
    elif args.local or env_local:
        # Explicit local flag or environment variable
        base_url = LOCAL_URL
        if args.local and env_local:
            logger.info("Local mode enabled via both --local flag and DANNET_MCP_LOCAL environment variable")
        elif args.local:
            logger.info("Local mode enabled via --local command line flag")
        elif env_local:
            logger.info("Local mode enabled via DANNET_MCP_LOCAL environment variable")
    else:
        # Auto-detect: try local first, fallback to remote
        base_url = _detect_available_server()
    
    # Initialize client with the chosen base URL
    dannet_client = DanNetClient(base_url)
    
    logger.info(f"Starting DanNet MCP Server with base URL: {base_url}")
    
    
    # Run the MCP server
    mcp.run()


if __name__ == "__main__":
    main()
