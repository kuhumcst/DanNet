# Contributing to DanNet

This document contains development setup details that are too specific for the main README.

## Frontend Dependencies

DanNet uses React 17 (required by Rum):

```shell
npm init -y
npm install react@17 react-dom@17 create-react-class@17
```

## AI-Assisted Development

The nREPL from the README's development steps (`clojure -M:nrepl`, port 7888) can be shared between your editor and an AI assistant. See [ai-stuff](https://github.com/simongray/ai-stuff) for AI/LLM setup documentation.
