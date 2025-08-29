# DanNet MCP Server Setup Guide

## Overview

The DanNet MCP Server provides AI applications with seamless access to DanNet, the Danish semantic wordnet. This single-file Python server implements the Model Context Protocol (MCP) using FastMCP, enabling Claude and other AI systems to access rich Danish linguistic data including synsets, semantic relationships, word definitions, and examples.

## Key Features

### Tools Implemented

- **`search_dannet`** - Search for Danish words and synsets with comprehensive results
- **`get_synset_info`** - Get detailed information about specific synsets including associated words
- **`get_word_synonyms`** - Find synonyms for Danish words by analyzing synset relationships
- **`get_word_definitions`** - Get all definitions and senses for a Danish word
- **`autocomplete_danish_word`** - Get autocomplete suggestions for Danish word prefixes

### Resources Implemented

- **`dannet://synset/{synset_id}`** - Access synset data as a structured resource
- **`dannet://search/{query}`** - Access search results as a queryable resource

### Prompts Implemented

- **`analyze_danish_word`** - Generate comprehensive linguistic analysis prompts for Danish words
- **`compare_danish_words`** - Generate semantic comparison prompts between two Danish words

### Technical Highlights

#### MCP Best Practices
- ✅ Built with FastMCP framework for optimal performance
- ✅ Proper tool naming conventions (underscores, no colons)
- ✅ Structured return types using Pydantic models
- ✅ Comprehensive error handling with meaningful messages
- ✅ Full type hints throughout the codebase

#### DanNet Integration
- ✅ Always requests JSON format (`format=json`) for consistent responses
- ✅ Handles DanNet's complex RDF data structure
- ✅ Parses language-tagged values correctly
- ✅ Extracts clean resource IDs from DanNet URIs
- ✅ Implements rate limiting and retry logic
- ✅ Robust error handling for API failures

#### Structured Data Models
- **`SynsetInfo`** - Complete synset information with words, definitions, and metadata
- **`WordInfo`** - Word details with part-of-speech and synset associations
- **`SearchResult`** - Clean search result data with optional fields

## Installation & Setup

### Prerequisites

- **Python 3.10+** (required by FastMCP)
- **uv** - Fast Python package manager (recommended by Anthropic)

### Quick Start

```bash
# Visit MCP server directory
cd examples/dannet-mcp-server

# Initialize with uv
uv init

# Add required dependencies
uv add mcp httpx pydantic

# Add MCP CLI tools for development and installation commands
uv add "mcp[cli]"
```

### Development Setup

The MCP CLI tools provide useful development and deployment utilities:

- **`mcp dev`** - Runs the server with auto-reload during development (restarts automatically when you modify code)
- **`mcp install`** - Automatically configures the server in Claude Desktop
- **Enhanced debugging** - Better error reporting and development experience

```bash
# For development with auto-reload (recommended)
uv run mcp dev dannet_mcp_server.py

# Install additional development dependencies if needed
uv add --dev pytest requests-mock
```

## Usage

### Running the Server

#### Production Mode (Default)
```bash
# Uses wordnet.dk production API
uv run dannet_mcp_server.py
```

#### Development Mode
```bash
# Uses localhost:3456 for local DanNet development
uv run dannet_mcp_server.py --local

# Or with auto-reload (recommended for development)
uv run mcp dev dannet_mcp_server.py --local
```

#### Custom URL
```bash
# Use a custom DanNet server URL
uv run dannet_mcp_server.py --base-url http://custom-dannet-server.com
```

#### Debug Mode
```bash
# Enable detailed debug logging
uv run dannet_mcp_server.py --debug
```

### Command Line Options

| Option | Description | Default |
|--------|-------------|---------|
| `--local` | Use local development server (localhost:3456) | Production (wordnet.dk) |
| `--base-url <url>` | Custom base URL for DanNet API | https://wordnet.dk |
| `--debug` | Enable debug logging | INFO level |

## Claude Desktop Integration

### Automatic Installation
```bash
# Install with automatic Claude Desktop configuration
uv run mcp install dannet_mcp_server.py --name "DanNet Server"
```

### Manual Configuration

Edit your Claude Desktop configuration file:

**Configuration File Locations:**
- **macOS/Linux**: `~/Library/Application Support/Claude/claude_desktop_config.json`
- **Windows**: `%AppData%\Claude Desktop\claude_desktop_config.json`

**Configuration:**
```json
{
   "mcpServers": {
      "dannet": {
         "command": "uv",
         "args": [
            "--directory",
            "/absolute/path/to/dannet-mcp-server",
            "run",
            "dannet_mcp_server.py"
         ],
         "env": {
            "PYTHONPATH": "/absolute/path/to/dannet-mcp-server"
         }
      }
   }
}
```

### Local Development Configuration
```json
{
   "mcpServers": {
      "dannet-local": {
         "command": "uv",
         "args": [
            "--directory",
            "/absolute/path/to/dannet-mcp-server",
            "run",
            "dannet_mcp_server.py",
            "--local"
         ]
      }
   }
}
```

## Example Usage Scenarios

### Word Analysis
**User**: "Find all meanings and synonyms for the Danish word 'hund'"
**Claude**: Uses `search_dannet` and `get_word_synonyms` tools to provide comprehensive analysis

### Semantic Comparison
**User**: "Compare the words 'hus' and 'hjem' semantically"
**Claude**: Uses `compare_danish_words` prompt and multiple tools to analyze relationships

### Language Learning
**User**: "What does 'kærlighed' mean and how is it used?"
**Claude**: Uses `get_word_definitions` and `analyze_danish_word` for detailed explanation

### Autocomplete Assistance
**User**: "What Danish words start with 'hu'?"
**Claude**: Uses `autocomplete_danish_word` to provide suggestions

## Troubleshooting

### Common Issues

1. **Server won't start**
   - Ensure Python 3.10+ is installed
   - Verify `uv` is in your PATH
   - Check that all dependencies are installed with `uv sync`

2. **Connection errors**
   - Verify internet connection for production mode
   - For local mode, ensure DanNet is running on localhost:3456
   - Check firewall settings

3. **Rate limiting**
   - The server implements automatic retry logic
   - Wait a moment between requests if hitting rate limits
   - Consider caching for frequently accessed data

4. **Claude Desktop integration**
   - Ensure absolute paths in configuration
   - Restart Claude Desktop after configuration changes
   - Check server logs for startup errors

### Debug Mode

Enable debug logging to troubleshoot issues:
```bash
uv run dannet_mcp_server.py --debug
```

This will show detailed request/response information and help identify connection or parsing issues.

### Testing the Server

You can test the server functionality using the MCP Inspector or by making direct tool calls:

```bash
# Test with MCP Inspector (if available)
npx @anthropic-ai/mcp-inspector uv run dannet_mcp_server.py

# Or test individual functions in Python
python -c "
from dannet_mcp_server import search_dannet
results = search_dannet('hund')
print(results)
"
```

## Performance Considerations

- **Caching**: The server implements HTTP client connection pooling
- **Rate Limiting**: Built-in retry logic for rate-limited requests
- **Timeouts**: Configurable timeout settings (default 30 seconds)
- **Error Handling**: Graceful degradation when API calls fail

The DanNet MCP Server provides robust, production-ready access to Danish linguistic data while maintaining the simplicity and reliability expected from MCP implementations.