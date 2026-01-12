# DanNet MCP Server

A Model Context Protocol server providing AI applications with access to DanNet (Danish WordNet).

## Quick Start

```bash
cd mcp

# Install dependencies
uv sync

# Run locally (connects to wordnet.dk by default)
uv run dannet_mcp_server.py

# Run against local DanNet server (localhost:3456)
uv run dannet_mcp_server.py --local
```

## HTTP Server Mode

For remote access or deployment:

```bash
uv run dannet_mcp_server.py --http --host 0.0.0.0 --port 8000
```

Test with MCP Inspector:
```bash
npx @modelcontextprotocol/inspector
# Connect to http://localhost:8000/mcp
```

## Claude Desktop Integration

Edit `~/Library/Application\ Support/Claude/claude_desktop_config.json` (Mac example, YMMV):

```json
{
  "mcpServers": {
    "dannet": {
      "command": "uv",
      "args": ["--directory", "/path/to/DanNet/mcp", "run", "dannet_mcp_server.py"]
    }
  }
}
```

Add `"env": {"DANNET_MCP_LOCAL": "true"}` to use a local DanNet server.

## Features

**Tools:** `get_word_synsets`, `get_synset_info`, `get_word_info`, `get_sense_info`, `get_word_synonyms`, `autocomplete_danish_word`, `sparql_query`, `fetch_ddo_definition`

**Resources:** `dannet://schema/{prefix}`, `dannet://schemas`, `dannet://namespaces`, `dannet://ontological-types`

**Prompts:** `analyze_danish_word`, `compare_danish_words`, `explore_semantic_field`, `analyze_part_whole`, `find_translation_equivalents`, `analyze_verb_roles`, `explore_polysemy`

## CLI Options

| Option | Description |
|--------|-------------|
| `--local` | Use localhost:3456 |
| `--base-url <url>` | Custom DanNet server URL |
| `--http` | Run as HTTP server (streamable-http transport) |
| `--host <ip>` | HTTP bind address (default: 127.0.0.1) |
| `--port <n>` | HTTP port (default: 8000) |
| `--debug` | Enable detailed logging |

## MCP Registry

Published as `io.github.kuhumcst/dannet` at https://wordnet.dk/mcp

The MCP server can be added to a local LLM/AI setup by referencing this URL,
e.g. for Claude Desktop you can go to `Settings > Connectors > Browse Connectors`
and click `add a custom one` and then input a fitting name (such as "DanNet")
and the MCP server URL (https://wordnet.dk/mcp).

## Publishing Updates

```bash
# Edit server.json, bump version
mcp-publisher login github
mcp-publisher publish
```

### Namespace Migration (TODO)

HTTP verification for `dk.wordnet` namespace is configured but blocked by old registry versions. Once old versions can be deleted:

```bash
mcp-publisher login http --domain=wordnet.dk --private-key=$(cat mcp-key.pem | openssl pkey -outform DER | tail -c 32 | xxd -p -c 64)
```
