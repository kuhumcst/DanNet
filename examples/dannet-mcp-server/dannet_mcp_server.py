#!/usr/bin/env python3
"""
DanNet MCP Server - A Model Context Protocol server for accessing DanNet (Danish WordNet)

This server provides AI applications with access to DanNet's rich Danish linguistic data
including synsets, semantic relationships, word definitions, and examples.

Usage:
    python dannet_mcp_server.py                    # Uses wordnet.dk (production)
    python dannet_mcp_server.py --local           # Uses localhost:3456 (development)
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
    try:
        results = get_client().search(query, language)
        search_results = []
        
        # Handle DanNet's response structure - check for both single entity and multiple results
        if isinstance(results, dict):
            # Check if this is a single entity response (redirected from search)
            if ':entity' in results:
                # This is a single synset entity response
                entity = results[':entity']
                
                # Extract synset ID from the subject
                synset_id = None
                subject = results.get(':subject', '')
                if isinstance(subject, str) and subject.startswith(':dn/'):
                    synset_id = subject.replace(':dn/', '')
                
                # Extract definition and label
                definition = extract_language_string(entity.get(':skos/definition'))
                label = extract_language_string(entity.get(':rdfs/label'))
                
                if synset_id and definition:
                    search_results.append(SearchResult(
                        word=query,
                        synset_id=synset_id,
                        label=label,
                        definition=definition
                    ))
                    
            else:
                # Check for multiple search results in EDN format
                search_data = results.get(':search-results', results.get('search-results', {}))
                lemma = results.get(':lemma', results.get('lemma', query))
                
                if isinstance(search_data, dict):
                    for synset_key, synset_data in search_data.items():
                        if not isinstance(synset_data, dict):
                            continue
                        
                        # Extract synset information from EDN-style response
                        synset_id = None
                        if isinstance(synset_key, str) and synset_key.startswith(':dn/'):
                            synset_id = synset_key.replace(':dn/', '')
                        elif 'rdf/value' in synset_data or ':rdf/value' in synset_data:
                            rdf_value = synset_data.get(':rdf/value', synset_data.get('rdf/value', ''))
                            if isinstance(rdf_value, str) and rdf_value.startswith(':dn/'):
                                synset_id = rdf_value.replace(':dn/', '')
                        
                        # Extract definition from EDN structure
                        definition = extract_language_string(synset_data.get(':skos/definition', synset_data.get('skos/definition')))
                        
                        # Extract label if available
                        label = extract_language_string(synset_data.get(':rdfs/label', synset_data.get('rdfs/label')))
                        
                        if synset_id and definition:
                            search_results.append(SearchResult(
                                word=lemma,
                                synset_id=synset_id,
                                label=label,
                                definition=definition
                            ))
        
        return search_results
        
    except Exception as e:
        raise RuntimeError(f"Search failed: {e}")


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
    try:
        # Clean the synset_id
        clean_id = parse_resource_id(synset_id)
        if not clean_id.startswith('synset-'):
            clean_id = f"synset-{clean_id}" if clean_id.isdigit() else clean_id
            
        data = get_client().get_resource(clean_id)
        
        # Check for ':entity' (with colon) - this is the actual response structure
        if not data or ':entity' not in data:
            raise DanNetError(f"No data found for synset {clean_id}")
        
        # Start with entity data and add inferred metadata
        result = dict(data[':entity'])
        if ':inferred' in data:
            result[':inferred'] = data[':inferred']
        
        # Add the cleaned synset_id for convenience (without colon prefix)
        result['synset_id'] = clean_id
        
        # Extract and format ontological types for better usability
        if ':dns/ontologicalType' in result:
            extracted_types = extract_ontological_types(result[':dns/ontologicalType'])
            result[':dns/ontologicalType_extracted'] = extracted_types
        
        # Extract and format sentiment information for better usability
        if ':dns/sentiment' in result:
            extracted_sentiment = extract_sentiment_info(result[':dns/sentiment'])
            result[':dns/sentiment_extracted'] = extracted_sentiment
        
        return result
        
    except Exception as e:
        raise RuntimeError(f"Failed to get synset info: {e}")


@mcp.tool()
def get_word_synonyms(word: str) -> List[str]:
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
        List of synonymous words (aggregated across all word senses)

    Example:
        synonyms = get_word_synonyms("løbe")
        # Returns: ["rende", "spurte", "flyde", "strømme", ...]

    Note: Check synset definitions to understand which synonyms apply
    to which meaning (polysemy is common in Danish).
    """
    try:
        # Search for the word to find its synsets
        search_results = search_dannet(word)
        synonyms = set()
        
        for result in search_results:
            # Only process exact matches
            if not (result.synset_id and result.word.lower() == word.lower()):
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
                
                # Extract the lemma directly from :rdfs/label
                label_data = word_data[':entity'].get(':rdfs/label', {})
                
                # Handle the RDF label structure {"value": "\"word\"", "lang": "da"}
                lemma = None
                if isinstance(label_data, dict) and 'value' in label_data:
                    # Extract value and strip quotes
                    lemma = label_data['value'].strip('"')
                elif isinstance(label_data, str):
                    # Fallback for direct string values
                    lemma = label_data.strip('"')
                
                # Add to synonyms if it's not the input word
                if lemma and lemma.lower() != word.lower():
                    synonyms.add(lemma)
        
        return sorted(list(synonyms))
        
    except Exception as e:
        raise RuntimeError(f"Failed to find synonyms: {e}")


@mcp.tool()
def get_word_definitions(word: str) -> List[Dict[str, str]]:
    """
    Get all definitions for the different senses/meanings of a Danish word.
    
    Danish words often have multiple distinct meanings (polysemy). This function
    returns all definitions with their corresponding synset information, allowing
    you to understand the full semantic range of a word.

    Args:
        word: The Danish word to get definitions for
    
    Returns:
        List of definitions with:
        - word: The word form
        - definition: Semantic definition
        - synset_id: Unique synset identifier for this sense
        - label: Human-readable synset label (if available)

    Example:
        defs = get_word_definitions("bank")
        # Returns definitions for: financial institution, riverbank, bench, etc.
    """
    try:
        search_results = search_dannet(word)
        definitions = []
        
        for result in search_results:
            if result.word.lower() == word.lower() and result.definition:
                def_info = {
                    "word": result.word,
                    "definition": result.definition
                }
                if result.synset_id:
                    def_info["synset_id"] = result.synset_id
                if result.label:
                    def_info["label"] = result.label
                    
                definitions.append(def_info)
        
        return definitions
        
    except Exception as e:
        raise RuntimeError(f"Failed to get definitions: {e}")


@mcp.tool()
def autocomplete_danish_word(prefix: str, max_results: int = 10) -> List[str]:
    """
    Get autocomplete suggestions for Danish word prefixes.
    
    Useful for discovering Danish vocabulary or finding the correct spelling
    of words. Returns lemma forms (dictionary forms) of words.

    Args:
        prefix: The beginning of a Danish word (minimum 3 characters required)
    
        max_results: Maximum number of suggestions to return (default: 10)
    Returns:
        List of word completions in alphabetical order

    Note: DanNet's autocomplete requires at least 3 characters. Shorter prefixes
    will return empty results to avoid overwhelming amounts of data.

    Example:
        suggestions = autocomplete_danish_word("hyg", 5)
        # Returns: ["hygge", "hyggelig", "hygiejne", ...]
    """
    try:
        suggestions = get_client().autocomplete(prefix)
        return suggestions[:max_results]
        
    except Exception as e:
        raise RuntimeError(f"Autocomplete failed: {e}")


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
        description="DanNet MCP Server - Access Danish WordNet data via MCP"
    )
    parser.add_argument(
        "--local",
        action="store_true",
        help="Use local development server (localhost:3456)"
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
    is_local = args.local or env_local
    
    # Determine base URL
    if args.base_url:
        base_url = args.base_url
    elif is_local:
        base_url = LOCAL_URL
    else:
        base_url = REMOTE_URL
    
    # Initialize client with the chosen base URL
    dannet_client = DanNetClient(base_url)
    
    logger.info(f"Starting DanNet MCP Server with base URL: {base_url}")
    if is_local:
        if args.local and env_local:
            logger.info("Local mode enabled via both --local flag and DANNET_MCP_LOCAL environment variable")
        elif args.local:
            logger.info("Local mode enabled via --local command line flag")
        elif env_local:
            logger.info("Local mode enabled via DANNET_MCP_LOCAL environment variable")
    
    
    # Run the MCP server
    mcp.run()


if __name__ == "__main__":
    main()
