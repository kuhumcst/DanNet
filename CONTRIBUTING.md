# Contributing to DanNet

This document contains development setup details that are too specific for the main README.

## Frontend Dependencies

DanNet uses React 17 (required by Rum):

```shell
npm init -y
npm install react@17 react-dom@17 create-react-class@17
```

## AI-Assisted Development with clojure-mcp

The project has experimental support for [clojure-mcp](https://github.com/bhauman/clojure-mcp), an MCP server for AI-assisted Clojure development.

### REPL Setup

When integrating with clojure-mcp, start an external nREPL on localhost:7888:

```shell
clojure -M:nrepl
```

You can then connect to this REPL from your editor (e.g., IntelliJ IDEA) and share it with the LLM.

### Configuration

See [mcp-stuff](https://github.com/simongray/mcp-stuff) for documentation, including `LLM_CODE_STYLE.md` and example `config.edn` files.
