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
        """Get autocomplete suggestions for a word prefix"""
        try:
            # Use _make_request to automatically include format=json parameter
            data = self._make_request("/dannet/autocomplete", {"s": prefix})

            # Extract autocompletions from the JSON response
            if isinstance(data, dict) and 'autocompletions' in data:
                return data['autocompletions']
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
- Words (LexicalEntry) → Senses → Synsets (LexicalConcept)
- Synsets represent units of meaning shared by synonymous words
- Rich semantic network with 10+ major relation categories, 70+ specific types

RDF PRESENTATION GUIDELINES:
When presenting DanNet data, use standard Turtle/SPARQL namespace notation (ns:identifier) 
rather than internal :ns/identifier format. Present relations with human-readable labels 
when available from schema vocabularies. Use Danish labels in Danish contexts, English 
labels in English contexts. For relations without defined labels, convert identifiers to 
readable strings (e.g., "mero_part" → "meronym part", "holo_member" → "holonym member").

QUICK START WORKFLOW:
1. Check resources for context:
   - dannet://wordnet-schema → core WordNet RDF relations
   - dannet://dannet-schema → DanNet-specific WordNet relation extensions
   - dannet://ontological-types → Semantic categories (Animal, Human, Object, etc.)
   - dannet://namespaces → Understanding prefixes in the data
   
2. Search for words to find synsets:
   - get_word_synsets("hund") → Find all meanings
   - Note: Danish has high polysemy (words with multiple meanings)
   
3. Explore synset details:
   - get_synset_info() → Full RDF data with relationships
   - Check dns:ontologicalType for semantic class
   - Follow wn:hypernym for categories, wn:hyponym for specifics
   
4. Navigate semantic relationships:
   - Taxonomic: hypernym (broader) / hyponym (narrower)
   - Similarity: similar, near_synonym, antonym
   - Part-whole: meronym/holonym (part/substance/member)
   - Functional: used_for, causes, instrument (DanNet-specific)

CORE RELATION CATEGORIES:
- Taxonomic: hypernym/hyponym chains + orthogonalHyponym for cross-cutting categories
- Part-Whole: mero_part/holo_part (components), mero_member/holo_member (collections), 
  mero_substance/holo_substance (materials), mero_location/holo_location (spatial)
- Thematic Roles: agent/involved_agent (who), instrument/involved_instrument (with what),
  patient/involved_patient (to what), result/involved_result (outcome)
- Functional: usedFor/usedForObject (purpose), domain_topic/has_domain_topic (fields)
- Causal-Temporal: causes/is_caused_by, entails/is_entailed_by, subevent/is_subevent_of
- Similarity-Opposition: similar, eq_synonym, antonym (+ gradable/simple/converse variants)
- Co-occurrence: co_agent_instrument, co_patient_agent (systematic co-occurrence patterns)

SEMANTIC PATTERNS BY DOMAIN:
- Animals: taxonomic hierarchies + agent roles + instrument co-occurrence
- Artifacts: extensive part-whole decomposition + functional domains
- Actions: thematic role chains (agent-instrument-patient-result)
- Body parts: anatomical part-whole hierarchies + location relations
- Emotions: similarity networks + sentiment annotations
- Locations: spatial containment + domain classifications

ONTOLOGICAL TYPES (dns:ontologicalType):
Core: Animal, Human, Object, Physical, Mental, Property
Events: BoundedEvent, UnboundedEvent, Agentive, Cause
Artifacts: Vehicle, Instrument, Artifact, Natural, BodyPart
Domains: Place, Location, Comestible, Occupation

DANNET EXTENSIONS:
- Sentiment polarity (Positive/Negative) with intensity values
- Inheritance system (dns:inherited) reduces redundancy
- DDO integration via synset labels {word_entry§definition}
- Cross-linguistic via wn:ili (Inter-Lingual Index) + the Open English WordNet

JSON-LD FORMAT GUIDE:
- All responses use standard JSON-LD with @context, @id, @type
- Namespace prefixes: dns: (schema), wn: (WordNet), ontolex: (vocabulary)
- Semantic data directly accessible: dns:ontologicalType["@set"], dns:sentiment["marl:hasPolarity"]
- Property names use colon format: "dns:sentiment" not ":dns/sentiment"
- Multi-value properties use arrays: ["dn:synset-1", "dn:synset-2"]
- Language-tagged literals: {"@value": "text", "@language": "da"}

KEY SEMANTIC PATTERNS:
- Hypernym chains reveal conceptual hierarchies
- Multiple hyponyms indicate important category nodes
- dns:inherited shows properties from parent concepts
- Cross-linguistic via wn:ili (or wn:eq_synonym to the Open English WordNet)

DATA FORMATS:
- JSON-LD responses with semantic data directly accessible
- Clean namespace prefixes (dns: for schema, dn: for data)
- Raw RDF available via Turtle format for graph operations
- All properties use standard JSON-LD format with @context

TIPS FOR LLM USAGE:
- Start broad with word search, then narrow to specific synsets
- Use ontological types to understand what kind of entity something is
- Follow relation chains: taxonomic for classification, functional for purpose,
  part-whole for composition, thematic roles for event structure
- Check sentiment annotations for emotional concepts
"""
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


# REMOVED: _process_synset_data() function - no longer needed with JSON-LD format
# The new JSON-LD format provides clean data directly without custom processing


# REMOVED: _process_entity_data() function - no longer needed with JSON-LD format  
# The new JSON-LD format provides clean data directly without custom processing


# REMOVED: extract_language_string() function - JSON-LD format handles this properly
# Values are now directly accessible as strings or objects with @value/@language structure


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


# ====================================================================================
# PHASE 4 ENHANCEMENTS: JSON-LD Utilities and Enhanced Error Handling
# ====================================================================================

def validate_jsonld_structure(data: Dict[str, Any]) -> bool:
    """
    Validate that a response has proper JSON-LD structure.
    
    Args:
        data: Response data to validate
        
    Returns:
        True if valid JSON-LD, False otherwise
    """
    if not isinstance(data, dict):
        return False
    
    # Check for essential JSON-LD properties
    required_props = ['@context', '@id', '@type']
    return all(prop in data for prop in required_props)


def get_namespace_prefixes(data: Dict[str, Any]) -> Dict[str, str]:
    """
    Extract namespace prefixes from JSON-LD @context.
    
    Args:
        data: JSON-LD data with @context
        
    Returns:
        Dict mapping prefixes to full namespace URLs
    """
    context = data.get('@context', {})
    if isinstance(context, dict):
        return {k: v for k, v in context.items() if isinstance(v, str)}
    return {}


def resolve_prefixed_uri(uri: str, context: Dict[str, str]) -> str:
    """
    Resolve a prefixed URI using JSON-LD context.
    
    Args:
        uri: Prefixed URI like "dns:sentiment" or full URI
        context: Namespace context mapping
        
    Returns:
        Resolved full URI or original if not prefixed
    """
    if ':' in uri and not uri.startswith(('http://', 'https://')):
        prefix, local = uri.split(':', 1)
        if prefix in context:
            return context[prefix] + local
    return uri


def extract_ontological_types(data: Dict[str, Any]) -> List[str]:
    """
    Extract ontological types from JSON-LD synset data.
    
    Args:
        data: Synset JSON-LD data
        
    Returns:
        List of ontological type URIs (e.g., ["dnc:Animal", "dnc:Object"])
    """
    ont_type = data.get('dns:ontologicalType', {})
    if isinstance(ont_type, dict) and '@set' in ont_type:
        return ont_type['@set']
    elif isinstance(ont_type, list):
        return ont_type
    return []


def extract_sentiment_data(data: Dict[str, Any]) -> Optional[Dict[str, str]]:
    """
    Extract sentiment information from JSON-LD synset data.
    
    Args:
        data: Synset JSON-LD data
        
    Returns:
        Dict with polarity and value, or None if no sentiment
    """
    sentiment = data.get('dns:sentiment')
    if isinstance(sentiment, dict):
        return {
            'polarity': sentiment.get('marl:hasPolarity', ''),
            'value': sentiment.get('marl:polarityValue', '')
        }
    return None


def get_language_value(data: Union[str, Dict[str, Any]], preferred_lang: str = 'da') -> str:
    """
    Extract language-specific value from JSON-LD language object.
    
    Args:
        data: Either a string or language object with @value/@language
        preferred_lang: Preferred language code (default: 'da')
        
    Returns:
        String value, preferring the specified language
    """
    if isinstance(data, str):
        return data
    elif isinstance(data, dict):
        if '@value' in data:
            return data['@value']
        elif isinstance(data, list):
            # Handle multiple language variants
            for item in data:
                if isinstance(item, dict) and item.get('@language') == preferred_lang:
                    return item.get('@value', '')
            # Fall back to first available
            if data and isinstance(data[0], dict):
                return data[0].get('@value', '')
    return str(data) if data else ''


def enhanced_error_message(error: Exception, context: str = '') -> str:
    """
    Generate enhanced error messages with JSON-LD context.
    
    Args:
        error: Original exception
        context: Additional context about the operation
        
    Returns:
        Enhanced error message with debugging guidance
    """
    base_msg = str(error)
    
    # Add JSON-LD specific guidance for common issues
    if 'KeyError' in str(type(error)) or 'key' in base_msg.lower():
        guidance = "Tip: Check property names use JSON-LD format (dns:sentiment not :dns/sentiment)"
    elif 'TypeError' in str(type(error)) or 'type' in base_msg.lower():
        guidance = "Tip: Verify JSON-LD structure with @context, @id, @type properties"
    elif 'ConnectionError' in str(type(error)) or 'connection' in base_msg.lower():
        guidance = "Tip: Check DanNet server availability and network connectivity"
    else:
        guidance = "Tip: Verify data format matches expected JSON-LD structure"
    
    if context:
        return f"{context}: {base_msg}. {guidance}"
    return f"{base_msg}. {guidance}"


# ====================================================================================
# END PHASE 4 ENHANCEMENTS
# ====================================================================================


# REMOVED: extract_ontological_types() function - no longer needed with JSON-LD format
# Ontological types are now directly available as dns:ontologicalType["@set"] array


# REMOVED: extract_sentiment_info() function - no longer needed with JSON-LD format  
# Sentiment is now directly available as dns:sentiment object with marl: properties


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

    DDO CONNECTION AND SYNSET LABELS:
    Synset labels are compositions of DDO-derived sense labels, showing all words that 
    express the same meaning. For example:
    - "{hund_1§1; køter_§1; vovhund_§1; vovse_§1}" = all words meaning "domestic dog"
    - "{forlygte_§2; babs_§1; bryst_§2; patte_1§1a}" = all words meaning "female breast"
    
    Each individual sense label follows DDO structure:
    - "hund_1§1" = word "hund", entry 1, definition 1 in DDO (ordnet.dk)
    - "patte_1§1a" = word "patte", entry 1, definition 1, subdefinition a
    - The § notation connects directly to DDO's definition numbering system

    This composition reveals the semantic relationships between Danish words and their
    shared meanings, all traceable back to authoritative DDO lexicographic data.

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
    
        language: Language for labels and definitions in results (default: "da" for Danish, "en" for English when available)
        Note: Only Danish words can be searched regardless of this parameter
        
    Returns:
        MULTIPLE RESULTS: List of SearchResult objects with:
        - word: The lexical form
        - synset_id: Unique synset identifier (format: synset-NNNNN)
        - label: Human-readable synset label (e.g., "{kage_1§1}")
        - definition: Brief semantic definition (may be truncated with "...")
        
        SINGLE RESULT: Dict with complete synset data including:
        - All RDF properties with namespace prefixes (e.g., wn:hypernym)
        - dns:ontologicalType → semantic types with @set array
        - dns:sentiment → parsed sentiment (if present)
        - synset_id → clean identifier for convenience
        - All semantic relationships and linguistic properties

    Examples:
        # Multiple results case
        results = get_word_synsets("hund")
        # Returns list of search result dictionaries for all meanings of "hund"
        # => [{"word": "hund", "synset_id": "synset-3047", ...}, ...]
        
        # Single result case (redirect)
        result = get_word_synsets("svinkeærinde")  
        # Returns complete synset data for unique word
        # => {'wn:hypernym': 'dn:synset-11677', 'dns:sentiment': {...}, ...}
    """
    try:
        results = get_client().search(query, language)
        search_results = []

        # Handle DanNet's JSON-LD response structure
        if isinstance(results, dict):
            # Check if this is a single entity response (redirected from search)
            if '@id' in results and '@type' in results:
                # This is a single synset entity response - return full synset data
                
                # Extract synset ID from @id (e.g., "dn:synset-68420" -> "synset-68420")
                synset_id = None
                entity_id = results.get('@id', '')
                if entity_id.startswith('dn:'):
                    synset_id = entity_id[3:]  # Remove "dn:" prefix
                
                if synset_id:
                    # JSON-LD format is already clean - just add convenience field
                    result = dict(results)
                    result['synset_id'] = synset_id
                    return result

            # Check for multiple search results in @graph structure
            graph_results = results.get('@graph', [])
            if isinstance(graph_results, list) and graph_results:
                search_results = []
                for synset_data in graph_results:
                    if not isinstance(synset_data, dict):
                        continue

                    # Extract synset ID from @id
                    synset_id = None
                    entity_id = synset_data.get('@id', '')
                    if entity_id.startswith('dn:'):
                        synset_id = entity_id[3:]  # Remove "dn:" prefix

                    if synset_id:
                        # Extract definition - handle both JSON-LD formats
                        definition = ""
                        skos_def = synset_data.get('skos:definition', {})
                        if isinstance(skos_def, dict) and '@value' in skos_def:
                            definition = skos_def['@value']
                        elif isinstance(skos_def, str):
                            definition = skos_def

                        # Extract proper label from rdfs:label if available
                        label = f"{{{query}_§1}}"  # Default fallback
                        rdfs_label = synset_data.get('rdfs:label', {})
                        if isinstance(rdfs_label, dict) and '@value' in rdfs_label:
                            label = rdfs_label['@value']
                        elif isinstance(rdfs_label, str):
                            label = rdfs_label

                        search_results.append(SearchResult(
                            word=query,
                            synset_id=synset_id,
                            label=label,
                            definition=definition or ""
                        ))

                return search_results

        # If no results found, return empty list
        return []

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
    - Check @type to understand what kind of entity you're working with

    Args:
        identifier: Entity identifier (e.g., "synset-3047", "word-11021628", "LexicalConcept", "i76470")
        namespace: Namespace for the entity (default: "dn" for DanNet entities)
                  - "dn": DanNet entities via /dannet/data/ endpoint
                  - Other values: External entities via /dannet/external/{namespace}/ endpoint
                  - Common external namespaces: "ontolex", "ili", "wn", "lexinfo", etc.

    Returns:
        Dict containing JSON-LD format with:
        - @context → namespace mappings (if applicable)
        - @id → entity identifier
        - @type → entity type
        - All RDF properties with namespace prefixes (e.g., wn:hypernym, ontolex:evokes)
        - For DanNet synsets: dns:ontologicalType and dns:sentiment (if applicable)
        - Entity-specific convenience fields (synset_id, resource_id, etc.)
    
    Note: The new JSON-LD format provides clean, directly accessible data.
    Use human-readable labels where available from schemas.

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

        # Check for valid JSON-LD response
        if not data:
            raise DanNetError(f"No data found for {namespace}/{identifier}")

        # JSON-LD format is already clean - just add convenience field
        result = dict(data)
        if namespace == "dn":
            result['resource_id'] = identifier
        else:
            result['resource_id'] = f"{namespace}/{identifier}"
        
        return result

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
       - dns:orthogonalHypernym → cross-cutting categories [Danish: ortogonalt hyperonym]

    2. LEXICAL CONNECTIONS:
       - ontolex:isEvokedBy → words expressing this concept [Danish: fremkaldes af]
       - ontolex:lexicalizedSense → sense instances [Danish: leksikaliseret betydning]
       - wn:similar → related but distinct concepts

    3. PART-WHOLE RELATIONS:
       - wn:mero_part/wn:holo_part → component relationships [English: meronym/holonym part]
       - wn:mero_substance/wn:holo_substance → material composition
       - wn:mero_member/wn:holo_member → membership relations

    4. SEMANTIC PROPERTIES:
       - dns:ontologicalType → semantic classification with @set array of dnc: types
         Common types: dnc:Animal, dnc:Human, dnc:Object, dnc:Physical,
         dnc:Dynamic (events/actions), dnc:Static (states)
       - dns:sentiment → emotional polarity with marl:hasPolarity and marl:polarityValue
       - wn:lexfile → semantic domain (e.g., "noun.food", "verb.motion")
       - skos:definition → synset definition (may be truncated for length)

    5. CROSS-LINGUISTIC:
       - wn:ili → Interlingual Index for cross-language mapping
       - wn:eq_synonym → Open English WordNet equivalent

    DDO CONNECTION FOR FULLER DEFINITIONS:
    DanNet synset definitions (skos:definition) may be truncated (ending with "…").
    For complete definitions, use the fetch_ddo_definition() tool which automatically
    retrieves full DDO text, or manually examine sense source URLs via get_sense_info().

    NAVIGATION TIPS:
    - Follow wn:hypernym chains to find semantic categories
    - Check dns:inherited for properties from parent synsets
    - Use parse_resource_id() on URI references to get clean IDs
    - For fuller definitions, examine individual sense source URLs via get_sense_info()

    Args:
        synset_id: Synset identifier (e.g., "synset-1876" or just "1876")

    Returns:
        Dict containing JSON-LD format with:
        - @context → namespace mappings
        - @id → entity identifier (e.g., "dn:synset-1876")
        - @type → "ontolex:LexicalConcept"
        - All RDF properties with namespace prefixes (e.g., wn:hypernym)
        - dns:ontologicalType → {"@set": ["dnc:Animal", ...]} (if applicable)
        - dns:sentiment → {"marl:hasPolarity": "marl:Positive", "marl:polarityValue": "3"} (if applicable)
        - synset_id → clean identifier for convenience
    
    Note: The new JSON-LD format provides clean, directly accessible data without
    requiring custom extraction functions.

    Example:
        info = get_synset_info("synset-52")  # cake synset
        # Check info['wn:hypernym'] for parent concepts
        # Check info['dns:ontologicalType']['@set'] for semantic types
        # Check info['dns:sentiment']['marl:hasPolarity'] for sentiment
    """
    try:
        # Clean the synset_id and ensure proper prefix
        clean_id = parse_resource_id(synset_id)
        if not clean_id.startswith('synset-'):
            clean_id = f"synset-{clean_id}" if clean_id.isdigit() else clean_id

        # Get the JSON-LD data directly from DanNet
        data = get_client().get_resource(clean_id)
        if not data:
            raise DanNetError(f"Synset not found: {clean_id}")
            
        # JSON-LD format is already clean - just add convenience field
        result = dict(data)
        result['synset_id'] = clean_id
        return result
        
    except Exception as e:
        raise RuntimeError(f"Failed to get synset info: {e}")


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
        - All RDF properties with namespace prefixes (e.g., ontolex:evokes)
        - resource_id → clean identifier for convenience
        - All linguistic properties and relationships
    
    Note: Present results using RDF notation (ns:identifier), not internal format.
    Use human-readable labels where available from schemas.

    Example:
        info = get_word_info("word-11021628")  # "hund" word
        # Check info['ontolex:evokes'] for synsets this word can express
        # Check info['ontolex:sense'] for senses
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

    3. REGISTER AND STYLISTIC INFORMATION:
       - lexinfo:register → formal register classification (e.g., ":lexinfo/slangRegister")
       - lexinfo:usageNote → human-readable usage notes (e.g., "slang", "formal")

    4. SOURCE INFORMATION:
       - dns:source → source URL for this sense entry

    DDO CONNECTION (Den Danske Ordbog):
    DanNet senses are derived from DDO (ordnet.dk), the authoritative modern Danish dictionary.
    
    SENSE LABELS: The format "word_entry§definition" connects to DDO structure:
    - "hund_1§1" = word "hund", entry 1, definition 1 in DDO
    - "forlygte_§2" = word "forlygte", definition 2 in DDO
    - The § notation directly corresponds to DDO's definition numbering

    SOURCE TRACEABILITY: The dns:source URLs link back to specific DDO entries:
    - Format: https://ordnet.dk/ddo/ordbog?entry_id=X&def_id=Y&query=word
    - Note: Some DDO URLs may not resolve correctly if IDs have changed since import
    - If the DDO page loads correctly, the relevant definition has CSS class "selected"

    METADATA ORIGINS: Usage examples, register information, and definitions flow from DDO's
    corpus-based lexicographic data, providing authoritative linguistic information.

    NAVIGATION TIPS:
    - Follow ontolex:isSenseOf to find the parent word
    - Follow ontolex:isLexicalizedSenseOf to find the synset
    - Check lexinfo:senseExample for usage examples from DDO corpus
    - Check lexinfo:register and lexinfo:usageNote for stylistic information
    - Use dns:source to attempt tracing back to original DDO definition (with caveats)
    - Use parse_resource_id() on URI references to get clean IDs

    Args:
        sense_id: Sense identifier (e.g., "sense-21033604" or just "21033604")

    Returns:
        Dict containing:
        - All RDF properties with namespace prefixes (e.g., ontolex:isSenseOf)
        - resource_id → clean identifier for convenience
        - All sense properties and relationships
    
    Note: Present results using RDF notation (ns:identifier), not internal format.
    Use human-readable labels where available from schemas.

    Example:
        info = get_sense_info("sense-21033604")  # "hund_1§1" sense
        # Check info['ontolex:isSenseOf'] for parent word
        # Check info['ontolex:isLexicalizedSenseOf'] for synset
        # Check info['lexinfo:senseExample'] for usage examples from DDO
        # Check info['lexinfo:register'] for register classification
        # Check info['lexinfo:usageNote'] for usage notes like "slang"
        # Check info['dns:source'] for DDO source URL (may not always work)
    """
    # Clean the sense_id and ensure proper prefix
    clean_id = parse_resource_id(sense_id)
    if not clean_id.startswith('sense-'):
        clean_id = f"sense-{clean_id}" if clean_id.isdigit() else clean_id

    return get_entity_info(clean_id, namespace="dn")



# TODO: Change back to List[str] if MCP framework serialization issue gets fixed.
# Currently returns string to work around concatenation without separators.
@mcp.tool()
def get_word_synonyms(word: str) -> str:
    """
    Find synonyms for a Danish word through shared synsets (word senses).

    SYNONYM TYPES IN DANNET:
    - True synonyms: Words sharing the exact same synset
    - Context-specific: Different synonyms for different word senses
    Note: Near-synonyms via wn:similar relations are not currently included

    The function returns all words that share synsets with the input word,
    effectively finding lexical alternatives that express the same concepts.

    Args:
        word: The Danish word to find synonyms for
    
    Returns:
        Comma-separated string of synonymous words (aggregated across all word senses)

    Example:
        synonyms = get_word_synonyms("hund")
        # Returns: "køter, vovhund, vovse"

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

            # Extract word IDs from ontolex:isEvokedBy (JSON-LD format)
            evoked_by = synset_data.get('ontolex:isEvokedBy', [])

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
                if not word_data:
                    continue

                # Extract the lemma from JSON-LD format
                label_data = word_data.get('rdfs:label', {})
                lemma = ""
                
                if isinstance(label_data, dict) and '@value' in label_data:
                    lemma = label_data['@value']
                elif isinstance(label_data, str):
                    lemma = label_data

                # Strip quotes if present
                if lemma:
                    lemma = lemma.strip('"')

                # Add to synonyms if it's not the input word
                if lemma and lemma.lower() != word.lower():
                    synonyms.add(lemma)

        return ", ".join(sorted(list(synonyms)))

    except Exception as e:
        raise RuntimeError(f"Failed to find synonyms: {e}")



# TODO: Change back to List[str] if MCP framework serialization issue gets fixed.
# Currently returns string to work around concatenation without separators.
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

    Note: Autocomplete requires at least 3 characters to prevent excessive results.

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


# TODO: replace with official DDO API if ever available
@mcp.tool()
def fetch_ddo_definition(synset_id: str) -> Dict[str, Any]:
    """
    Fetch the full, untruncated definition from DDO (Den Danske Ordbog) for a synset.
    
    This tool addresses the issue that DanNet synset definitions (:skos/definition)
    may be capped at a certain length. It retrieves the complete definition from
    the authoritative DDO source by following sense source URLs.
    
    WORKFLOW:
    1. Get synset information to find associated senses
    2. Extract DDO source URLs from sense data (dns:source)
    3. Fetch DDO HTML pages and parse for definitions
    4. Find elements with class "definitionBox selected" and extract span.definition content
    
    IMPORTANT NOTES:
    - Looks for CSS classes "definitionBox selected" and child span.definition
    - DDO and DanNet have diverged over time, so source URLs may not always work
    - This implementation uses httpx for web requests and regex-based HTML parsing
    
    Args:
        synset_id: Synset identifier (e.g., "synset-1876" or just "1876")
    
    Returns:
        Dict containing:
        - synset_id: The queried synset ID
        - ddo_definitions: List of definitions found from DDO pages
        - source_urls: List of DDO URLs that were attempted
        - success_urls: List of URLs that successfully returned definitions
        - errors: List of any errors encountered
        - truncated_definition: The original DanNet definition for comparison
    
    Example:
        result = fetch_ddo_definition("synset-3047")
        # Check result['ddo_definitions'] for full DDO definitions
        # Compare with result['truncated_definition'] from DanNet
    """
    try:
        # Clean the synset_id
        clean_id = parse_resource_id(synset_id)
        if not clean_id.startswith('synset-'):
            clean_id = f"synset-{clean_id}" if clean_id.isdigit() else clean_id

        # Get synset information
        synset_info = get_synset_info(clean_id)

        # Extract the original (possibly truncated) definition from JSON-LD format
        truncated_def = ""
        skos_def = synset_info.get('skos:definition', {})
        if isinstance(skos_def, dict) and '@value' in skos_def:
            truncated_def = skos_def['@value']
        elif isinstance(skos_def, str):
            truncated_def = skos_def

        # Get associated senses from JSON-LD format
        senses = synset_info.get('ontolex:lexicalizedSense', [])
        if isinstance(senses, str):
            senses = [senses]

        # Extract sense IDs and get their source URLs
        source_urls = []
        ddo_definitions = []
        success_urls = []
        errors = []

        for sense_uri in senses:
            try:
                # Extract sense ID from URI
                sense_id = parse_resource_id(sense_uri)
                if not sense_id.startswith('sense-'):
                    sense_id = f"sense-{sense_id}" if sense_id.replace('sense-', '').isdigit() else sense_id

                # Get sense information
                sense_info = get_sense_info(sense_id)

                # Extract DDO source URL from JSON-LD format
                source = sense_info.get('dns:source')
                if source:
                    if isinstance(source, list):
                        source = source[0]

                    # Clean up the URL (remove < > brackets if present)
                    source_url = str(source).strip('<>')
                    source_urls.append(source_url)

                    try:
                        # Fetch the DDO page
                        response = get_client().client.get(source_url, timeout=10.0)
                        response.raise_for_status()
                        html_content = response.text

                        import re

                        # Look for elements with class="definitionBox selected" and extract span.definition content
                        # The classes are space-separated, so we need to match "definitionBox selected" or "selected definitionBox"
                        definition_box_pattern = r'<div[^>]+class="[^"]*(?:definitionBox\s+selected|selected\s+definitionBox)[^"]*"[^>]*>(.*?)</div>'
                        box_matches = re.findall(definition_box_pattern, html_content, re.IGNORECASE | re.DOTALL)

                        for box_content in box_matches:
                            # Within the box, find span with class="definition"
                            span_pattern = r'<span[^>]+class="[^"]*definition[^"]*"[^>]*>(.*?)</span>'
                            span_matches = re.findall(span_pattern, box_content, re.IGNORECASE | re.DOTALL)

                            for span_content in span_matches:
                                # Clean up the definition text
                                clean_text = re.sub(r'<[^>]+>', '', span_content)
                                # Decode HTML entities
                                clean_text = clean_text.replace('&nbsp;', ' ').replace('&amp;', '&').replace('&lt;',
                                                                                                             '<').replace(
                                    '&gt;', '>')
                                # Normalize whitespace
                                clean_text = re.sub(r'\s+', ' ', clean_text).strip()

                                if clean_text and len(clean_text) > 5:  # Filter out very short matches
                                    ddo_definitions.append(clean_text)
                                    success_urls.append(source_url)
                                    break  # Only take the first good match per URL

                            if ddo_definitions:  # Found definition, stop looking
                                break

                        if not ddo_definitions:
                            errors.append(
                                f"No definition found with pattern 'definitionBox selected' > 'span.definition' at {source_url}")

                    except Exception as e:
                        errors.append(f"Failed to fetch/parse {source_url}: {str(e)}")

            except Exception as e:
                errors.append(f"Failed to process sense {sense_uri}: {str(e)}")

        return {
            'synset_id': clean_id,
            'ddo_definitions': ddo_definitions,
            'source_urls': source_urls,
            'success_urls': success_urls,
            'errors': errors,
            'truncated_definition': truncated_def
        }

    except Exception as e:
        return {
            'synset_id': synset_id,
            'error': f"Failed to fetch DDO definition: {str(e)}",
            'ddo_definitions': [],
            'source_urls': [],
            'success_urls': [],
            'errors': [str(e)],
            'truncated_definition': ""
        }


# ====================================================================================
# PHASE 4 ENHANCED MCP TOOLS: Advanced JSON-LD Processing
# ====================================================================================

@mcp.tool()
def validate_synset_structure(synset_data: Dict[str, Any]) -> Dict[str, Any]:
    """
    Validate and analyze the structure of synset JSON-LD data.
    
    This enhanced tool helps debug and understand synset data structure,
    providing validation and insights into the JSON-LD format.
    
    Args:
        synset_data: Synset data returned from get_synset_info()
        
    Returns:
        Dict with validation results and structural analysis
    """
    try:
        result = {
            'is_valid_jsonld': validate_jsonld_structure(synset_data),
            'has_ontological_types': bool(extract_ontological_types(synset_data)),
            'has_sentiment': extract_sentiment_data(synset_data) is not None,
            'namespace_prefixes': get_namespace_prefixes(synset_data),
            'synset_id': synset_data.get('synset_id', 'Not found'),
            'entity_type': synset_data.get('@type', 'Unknown')
        }
        
        # Add ontological types if present
        ont_types = extract_ontological_types(synset_data)
        if ont_types:
            result['ontological_types'] = ont_types
            result['ontological_count'] = len(ont_types)
        
        # Add sentiment details if present
        sentiment = extract_sentiment_data(synset_data)
        if sentiment:
            result['sentiment_details'] = sentiment
            
        # Validate key properties
        key_properties = ['rdfs:label', 'skos:definition', 'wn:hypernym', 'ontolex:isEvokedBy']
        result['available_properties'] = [prop for prop in key_properties if prop in synset_data]
        result['missing_properties'] = [prop for prop in key_properties if prop not in synset_data]
        
        return result
        
    except Exception as e:
        return {
            'error': enhanced_error_message(e, 'Synset structure validation'),
            'is_valid': False
        }


@mcp.tool()
def extract_semantic_data(entity_data: Dict[str, Any]) -> Dict[str, Any]:
    """
    Extract and normalize semantic data from any DanNet JSON-LD entity.
    
    This tool provides a unified way to extract semantic information from
    synsets, words, or senses, handling different JSON-LD structures consistently.
    
    Args:
        entity_data: Any DanNet entity JSON-LD data
        
    Returns:
        Dict with normalized semantic information
    """
    try:
        result = {
            'entity_id': entity_data.get('@id', 'Unknown'),
            'entity_type': entity_data.get('@type', 'Unknown'),
            'valid_jsonld': validate_jsonld_structure(entity_data)
        }
        
        # Extract labels (handling language variants)
        if 'rdfs:label' in entity_data:
            result['label'] = get_language_value(entity_data['rdfs:label'])
        
        # Extract definitions
        if 'skos:definition' in entity_data:
            result['definition'] = get_language_value(entity_data['skos:definition'])
            
        # Extract ontological types (for synsets)
        ont_types = extract_ontological_types(entity_data)
        if ont_types:
            result['ontological_types'] = ont_types
            
        # Extract sentiment (for synsets)
        sentiment = extract_sentiment_data(entity_data)
        if sentiment:
            result['sentiment'] = sentiment
            
        # Extract key relationships based on entity type
        entity_type = entity_data.get('@type', '')
        
        if 'LexicalConcept' in entity_type:  # Synset
            for rel in ['wn:hypernym', 'wn:hyponym', 'ontolex:isEvokedBy']:
                if rel in entity_data:
                    result[rel.replace(':', '_')] = entity_data[rel]
                    
        elif 'Word' in entity_type:  # Word
            for rel in ['ontolex:evokes', 'ontolex:sense']:
                if rel in entity_data:
                    result[rel.replace(':', '_')] = entity_data[rel]
                    
        elif 'LexicalSense' in entity_type:  # Sense
            for rel in ['ontolex:isSenseOf', 'ontolex:isLexicalizedSenseOf']:
                if rel in entity_data:
                    result[rel.replace(':', '_')] = entity_data[rel]
        
        return result
        
    except Exception as e:
        return {
            'error': enhanced_error_message(e, 'Semantic data extraction'),
            'entity_id': entity_data.get('@id', 'Unknown'),
            'valid': False
        }


@mcp.tool()
def analyze_namespace_usage(entity_data: Dict[str, Any]) -> Dict[str, Any]:
    """
    Analyze namespace usage and provide resolution for prefixed properties.
    
    This debugging tool helps understand how namespaces are used in
    DanNet JSON-LD data and resolves prefixed URIs to full forms.
    
    Args:
        entity_data: Any DanNet JSON-LD entity data
        
    Returns:
        Dict with namespace analysis and URI resolution
    """
    try:
        context = get_namespace_prefixes(entity_data)
        
        result = {
            'available_prefixes': list(context.keys()),
            'namespace_mappings': context,
            'prefixed_properties': [],
            'resolved_uris': {},
            'entity_namespace': 'Unknown'
        }
        
        # Analyze entity ID namespace
        entity_id = entity_data.get('@id', '')
        if ':' in entity_id and not entity_id.startswith('http'):
            prefix = entity_id.split(':')[0]
            result['entity_namespace'] = prefix
            result['entity_resolved'] = resolve_prefixed_uri(entity_id, context)
        
        # Find all prefixed properties
        for key in entity_data.keys():
            if ':' in key and not key.startswith('@'):
                result['prefixed_properties'].append(key)
                result['resolved_uris'][key] = resolve_prefixed_uri(key, context)
        
        # Count usage by namespace
        namespace_counts = {}
        for prop in result['prefixed_properties']:
            if ':' in prop:
                prefix = prop.split(':')[0]
                namespace_counts[prefix] = namespace_counts.get(prefix, 0) + 1
        
        result['namespace_usage_counts'] = namespace_counts
        
        return result
        
    except Exception as e:
        return {
            'error': enhanced_error_message(e, 'Namespace analysis'),
            'valid': False
        }


# ====================================================================================
# END PHASE 4 ENHANCED MCP TOOLS
# ====================================================================================


@mcp.resource("dannet://ontological-types")
def get_ontological_types_schema() -> str:
    """
    Access the ontological types taxonomy (dnc: namespace) - DanNet's extended EuroWordNet classification.
    
    Returns:
        RDF schema defining ontological types like dnc:Animal, dnc:Human, dnc:Object, etc.
        
    This resource is essential for understanding the dns:ontologicalType 
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

    Essential schemas:
    - 'dns': DanNet-specific properties and relations
    - 'dnc': Ontological types (semantic categories)
    - 'wn': Standard WordNet relations
    - 'ontolex': Word-sense-synset model
    
    Supporting schemas:
    - 'skos': Definitions and labels
    - 'lexinfo': Part-of-speech and morphology
    - 'marl': Sentiment annotations

    Args:
        prefix: Namespace prefix (e.g., 'dns', 'wn', 'ontolex')

    Returns:
        RDF schema in Turtle format

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


@mcp.prompt()
def explore_semantic_field(concept: str, depth: int = 2) -> str:
    """
    Generate a prompt to explore a semantic field or domain in Danish.
    
    Args:
        concept: The central concept or domain (e.g., "mad" for food, "dyr" for animals)
        depth: How many levels of hyponyms to explore (default: 2)
    
    Returns:
        Exploration prompt for the semantic field
    """
    return f"""Please explore the semantic field around the Danish concept "{concept}" using DanNet.

Perform the following analysis:
1. Find the primary synset(s) for "{concept}"
2. Map out all hyponyms (subcategories) up to {depth} levels deep
3. Identify the ontological types (dns:ontologicalType) for this semantic field
4. Find related concepts via wn:similar and dns:orthogonalHypernym
5. List key vocabulary items in this semantic domain
6. Identify any domain-specific relationships (e.g., dns:usedFor for tools)

Create a structured overview of this semantic field that would be useful for vocabulary learning or domain analysis."""


@mcp.prompt()
def trace_taxonomic_path(word1: str, word2: str) -> str:
    """
    Generate a prompt to find the taxonomic relationship between two words.
    
    Args:
        word1: Starting word
        word2: Target word
    
    Returns:
        Prompt for tracing taxonomic relationships
    """
    return f"""Please trace the taxonomic relationship between "{word1}" and "{word2}" in Danish using DanNet.

Investigate:
1. Is there a direct hypernym/hyponym relationship between these words?
2. If not directly related, find their common hypernym (shared parent concept)
3. Trace the full hypernym chain from each word to their common ancestor
4. Calculate the semantic distance (number of steps) between them
5. Identify any cross-cutting relationships via dns:orthogonalHypernym
6. Explain what semantic features differentiate these concepts

Provide a clear visualization of the taxonomic paths and explain the semantic relationship."""


@mcp.prompt()
def map_part_whole_relations(entity: str, direction: str = "both") -> str:
    """
    Generate a prompt to explore part-whole relationships for an entity.
    
    Args:
        entity: The Danish word for the entity to analyze
        direction: "parts" (meronyms), "wholes" (holonyms), or "both"
    
    Returns:
        Prompt for part-whole relationship analysis
    """
    directions_text = {
        "parts": "all component parts (meronyms)",
        "wholes": "all containing wholes (holonyms)", 
        "both": "both parts (meronyms) and wholes (holonyms)"
    }
    
    return f"""Please map {directions_text.get(direction, "part-whole relationships")} for the Danish word "{entity}" using DanNet.

Analyze:
1. Find all synsets for "{entity}"
2. For each synset, identify:
   - wn:mero_part / wn:holo_part (physical components)
   - wn:mero_substance / wn:holo_substance (material composition)
   - wn:mero_member / wn:holo_member (membership relations)
   - wn:mero_location / wn:holo_location (spatial containment)
3. Create a structured hierarchy showing these relationships
4. Note which relationships are inherited vs. direct
5. Identify the ontological types of related entities

Present this as a clear structural decomposition that shows how "{entity}" fits into larger systems or breaks down into components."""


@mcp.prompt()
def find_translation_equivalents(word: str, target_lang: str = "en") -> str:
    """
    Generate a prompt to find cross-linguistic equivalents.
    
    Args:
        word: Danish word to translate
        target_lang: Target language code (default: "en" for English)
    
    Returns:
        Prompt for finding translation equivalents
    """
    return f"""Please find translation equivalents for the Danish word "{word}" using DanNet's cross-linguistic connections.

Perform this analysis:
1. Find all synsets for "{word}"
2. For each synset:
   - Check wn:ili (Inter-Lingual Index) mappings
   - Look for wn:eq_synonym (Open English WordNet equivalents)
   - Note the specific sense that each translation corresponds to
3. Identify which senses have direct equivalents vs. approximate matches
4. Explain any semantic differences or gaps between languages
5. If multiple English words map to the Danish word, explain the distinctions
6. Provide example contexts where each translation would be appropriate

Give a nuanced translation guide that captures the semantic range of "{word}" across languages."""


@mcp.prompt()
def analyze_verb_roles(verb: str) -> str:
    """
    Generate a prompt to analyze thematic roles for Danish verbs.
    
    Args:
        verb: Danish verb to analyze
    
    Returns:
        Prompt for thematic role analysis
    """
    return f"""Please analyze the thematic roles and argument structure of the Danish verb "{verb}" using DanNet.

Investigate:
1. Find all verbal synsets for "{verb}"
2. For each verbal sense, identify:
   - dns:agent / dns:involved_agent (who performs the action)
   - dns:patient / dns:involved_patient (what is affected)
   - dns:instrument / dns:involved_instrument (tools/means used)
   - dns:result / dns:involved_result (outcomes produced)
3. Look for co-occurrence patterns:
   - dns:co_agent_instrument (typical agent-instrument pairs)
   - dns:co_patient_agent (typical patient-agent pairs)
4. Identify causal relationships:
   - wn:causes / dns:is_caused_by
   - wn:entails / dns:is_entailed_by
5. Find related actions via wn:subevent
6. Note the ontological type (Agentive, Cause, etc.)

Provide a comprehensive analysis of how "{verb}" structures events and what semantic roles it assigns."""


@mcp.prompt()
def explore_polysemy(word: str, include_etymology: bool = False) -> str:
    """
    Generate a prompt to explore polysemous meanings of a word.
    
    Args:
        word: Danish word to analyze for multiple meanings
        include_etymology: Whether to request etymological connections
    
    Returns:
        Prompt for polysemy analysis
    """
    etymology_section = """
7. If possible, identify whether different senses share etymological origins
8. Note any metaphorical extensions from concrete to abstract meanings""" if include_etymology else ""
    
    return f"""Please explore the polysemy (multiple meanings) of the Danish word "{word}" using DanNet.

Analyze:
1. List all distinct synsets (senses) for "{word}"
2. For each sense:
   - Provide the full definition (use fetch_ddo_definition if truncated)
   - Identify the ontological type and semantic domain
   - Note the register/formality level if available
3. Group related senses vs. completely distinct meanings
4. Identify the most common/prototypical sense
5. Show how senses relate through:
   - Metaphorical extension
   - Specialization/generalization
   - Domain-specific usage
6. Find synonyms unique to each sense{etymology_section}

Create a sense map that helps learners understand how one word form carries multiple meanings in Danish."""


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
