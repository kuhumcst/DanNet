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
from typing import Dict, List, Optional, Any, Union
from urllib.parse import urljoin

import httpx
from pydantic import BaseModel, Field
from mcp.server.fastmcp import FastMCP

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# Global configuration
BASE_URL = "https://wordnet.dk"
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
    """HTTP client for DanNet API"""
    
    def __init__(self, base_url: str = BASE_URL):
        self.base_url = base_url.rstrip('/')
        self.client = httpx.Client(
            timeout=TIMEOUT
            # Note: DanNet doesn't support Accept: application/json header
            # Content format is determined by URL parameters or server defaults
        )
    
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


# Initialize the DanNet client
dannet_client = DanNetClient()

# Create FastMCP server
mcp = FastMCP("DanNet")


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
    try:
        results = dannet_client.search(query, language)
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
    Get detailed information about a specific DanNet synset.
    
    Args:
        synset_id: The synset identifier (e.g., "synset-1876")
    
    Returns:
        Raw RDF data for the synset including all properties and relationships
    """
    try:
        # Clean the synset_id
        clean_id = parse_resource_id(synset_id)
        if not clean_id.startswith('synset-'):
            clean_id = f"synset-{clean_id}" if clean_id.isdigit() else clean_id
            
        data = dannet_client.get_resource(clean_id)
        
        # Check for ':entity' (with colon) - this is the actual response structure
        if not data or ':entity' not in data:
            raise DanNetError(f"No data found for synset {clean_id}")
        
        # Start with entity data and add inferred metadata
        result = dict(data[':entity'])
        if ':inferred' in data:
            result[':inferred'] = data[':inferred']
        
        # Add the cleaned synset_id for convenience (without colon prefix)
        result['synset_id'] = clean_id
        
        return result
        
    except Exception as e:
        raise RuntimeError(f"Failed to get synset info: {e}")


@mcp.tool()
def get_word_synonyms(word: str) -> List[str]:
    """
    Find synonyms for a Danish word by looking up its synsets.
    
    Args:
        word: The Danish word to find synonyms for
    
    Returns:
        List of synonymous words
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
                word_data = dannet_client.get_resource(word_id)
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
    
    Args:
        word: The Danish word to get definitions for
    
    Returns:
        List of definitions with synset information
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
    
    Args:
        prefix: The beginning of a Danish word (minimum 3 characters required)
        max_results: Maximum number of suggestions to return
    
    Returns:
        List of word completions
    
    Note: DanNet's autocomplete requires at least 3 characters. Shorter prefixes 
    will return empty results to avoid overwhelming amounts of data.
    """
    try:
        suggestions = dannet_client.autocomplete(prefix)
        return suggestions[:max_results]
        
    except Exception as e:
        raise RuntimeError(f"Autocomplete failed: {e}")


@mcp.resource("dannet://synset/{synset_id}")
def get_synset_resource(synset_id: str) -> str:
    """
    Access DanNet synset data as a resource.
    
    Args:
        synset_id: The synset identifier
    
    Returns:
        JSON representation of the synset
    """
    try:
        synset_info = get_synset_info(synset_id)
        import json
        return json.dumps(synset_info, indent=2)
    except Exception as e:
        return f"Error accessing synset {synset_id}: {e}"


@mcp.resource("dannet://search/{query}")
def get_search_resource(query: str) -> str:
    """
    Access DanNet search results as a resource.
    
    Args:
        query: The search query
    
    Returns:
        JSON representation of search results
    """
    try:
        results = search_dannet(query)
        return "\n".join(result.model_dump_json() for result in results)
    except Exception as e:
        return f"Error searching for '{query}': {e}"


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
    
    # Determine base URL
    if args.base_url:
        base_url = args.base_url
    elif args.local:
        base_url = "http://localhost:3456"
    else:
        base_url = "https://wordnet.dk"
    
    # Initialize client with the chosen base URL
    dannet_client = DanNetClient(base_url)
    
    logger.info(f"Starting DanNet MCP Server with base URL: {base_url}")
    
    # Run the MCP server
    mcp.run()


if __name__ == "__main__":
    main()
