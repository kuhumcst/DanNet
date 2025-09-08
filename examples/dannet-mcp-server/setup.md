# DanNet MCP Server Setup Guide

## Overview

The DanNet MCP Server provides AI applications with access to DanNet, the Danish semantic wordnet. This Python server implements the Model Context Protocol (MCP) using FastMCP, enabling Claude and other AI systems to access Danish linguistic data including synsets, semantic relationships, word definitions, and examples.

## Quick Setup

### Prerequisites
- Python 3.10+
- `uv` package manager (recommended)
- Cloned DanNet Git repository

### Why uv?

This guide uses `uv` following Anthropic's MCP recommendations: it's faster than pip, handles dependencies more reliably, and provides integrated virtual environment management. Other package managers work but require different commands.

### Installation
```bash
# Navigate to the MCP server directory within the cloned DanNet repo
cd examples/dannet-mcp-server

# Initialize a new uv project (if starting fresh)
uv init --name dannet-mcp-server

# Add core dependencies
uv add mcp httpx pydantic

# Add MCP CLI tools for development (provides 'mcp dev' command)
uv add "mcp[cli]"
```

## Usage

### Basic Commands
```bash
# Production mode (wordnet.dk)
uv run dannet_mcp_server.py

# Local development (localhost:3456)
uv run dannet_mcp_server.py --local
DANNET_MCP_LOCAL=true uv run dannet_mcp_server.py

# Interactive browser interface (production mode)
uv run mcp dev dannet_mcp_server.py

# Interactive browser interface (local development mode)
DANNET_MCP_LOCAL=true uv run mcp dev dannet_mcp_server.py

# Debug mode
uv run dannet_mcp_server.py --debug
```

## Claude Desktop Integration

Edit `~/Library/Application Support/Claude Desktop/claude_desktop_config.json` (macOS) or `%AppData%\Claude Desktop\claude_desktop_config.json` (Windows):

```json
{
   "mcpServers": {
      "dannet": {
         "command": "uv",
         "args": [
            "--directory", "/absolute/path/to/dannet-mcp-server",
            "run", "dannet_mcp_server.py"
         ],
         "env": {
            "DANNET_MCP_LOCAL": "true"
         }
      }
   }
}
```

## Features

### Tools
- `get_word_synsets` - Get synsets (word meanings) for Danish words
- `get_synset_info` - Get detailed synset information with RDF properties
- `get_word_synonyms` - Find synonyms by analyzing synset relationships
- `autocomplete_danish_word` - Get word completion suggestions (3+ characters)

### Resources
- `dannet://schema/{prefix}` - Access RDF schemas (dns, dnc, ontolex, etc.)
- `dannet://schemas` - List all available schemas with descriptions
- `dannet://namespaces` - Namespace documentation and usage patterns

### Prompts
- `analyze_danish_word` - Generate comprehensive word analysis
- `compare_danish_words` - Generate semantic comparison between words

## Key DanNet Namespaces

- `dn:` - Core data (synsets, words, senses)
- `dns:` - DanNet-specific properties (sentiment, ontologicalType)
- `skos:` - Definitions and labels
- `ontolex:` - W3C lexical vocabulary (LexicalConcept, isEvokedBy)

## Troubleshooting

### Options Reference
| Option | Type | Description |
|--------|------|-------------|
| `--local` | Flag | Use localhost:3456 instead of wordnet.dk |
| `--base-url <url>` | Flag | Custom DanNet server URL |
| `--debug` | Flag | Enable detailed logging |
| `DANNET_MCP_LOCAL=true` | Environment | Enable local mode (works with `mcp dev`) |

### Common Issues
1. **Server won't start** - Check Python 3.10+, `uv` in PATH, dependencies installed
2. **Connection errors** - Verify internet (production) or localhost:3456 running (local)
3. **Environment variable ignored** - Use exact syntax: `DANNET_MCP_LOCAL=true`
4. **mcp dev argument issues** - Use environment variables, not command flags

### Testing
```bash
# Test with browser interface (recommended)
DANNET_MCP_LOCAL=true uv run mcp dev dannet_mcp_server.py

# Test environment detection
DANNET_MCP_LOCAL=true uv run python -c "
from dannet_mcp_server import get_client
print(f'Using: {get_client().base_url}')
"
```

### Debug Mode
```bash
uv run dannet_mcp_server.py --debug
```
Shows initialization messages, HTTP requests, and configuration details.

The server provides robust access to Danish linguistic data while maintaining MCP implementation simplicity.
