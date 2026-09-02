#!/usr/bin/env python3
"""DanNet MCP server: Danish WordNet tools, resources and prompts backed by the
DanNet web service, either wordnet.dk or a local instance (see --help)."""

import argparse
import asyncio
import html
import json
import logging
import os
import re
from functools import lru_cache
from typing import Any

import httpx
from mcp.server.fastmcp import FastMCP
from mcp.server.transport_security import TransportSecuritySettings

log = logging.getLogger("dannet")

REMOTE_URL = "https://wordnet.dk"
LOCAL_URL = "http://localhost:3456"

INSTRUCTIONS = """\
DanNet is the Danish WordNet, modelled with OntoLex-Lemon and the Global
WordNet schema: words (ontolex:LexicalEntry) have senses (ontolex:LexicalSense)
that lexicalize synsets (ontolex:LexicalConcept). The triplestore also holds
the Open English WordNet (en:) and the COR inflection lexicon (cor:).

Workflow: get_word_overview(word) gives every sense of a word with synonyms,
hypernym and ontological types in one call; get_entity_info(id) gives the full
JSON-LD of any synset, word or sense; sparql_query covers everything else.
Synset definitions can be truncated (ending in "…"); fetch_ddo_definition
fetches the full text from DDO.

Key relations: wn:hypernym/wn:hyponym (taxonomy), dns:orthogonalHypernym,
wn:mero_*/wn:holo_* (part-whole), wn:similar, wn:antonym, dns:usedFor,
wn:agent/wn:instrument/wn:patient/wn:result (thematic roles), wn:causes,
wn:ili and wn:eq_synonym (English equivalents). dns:ontologicalType holds the
EuroWordNet-style semantic types (dnc:Animal, dnc:Human, dnc:Object, ...),
dns:sentiment the polarity (marl:hasPolarity, marl:polarityValue) and
dns:inherited marks properties inherited from hypernyms.

Sense labels follow DDO (Den Danske Ordbog): "hund_1§1" is the word "hund",
entry 1, definition 1. Synset labels list the member senses, e.g.
"{hund_1§1; køter_§1; vovhund_§1}".

Prefixes: dn: (data), dns: (schema), dnc: (ontological types), dnf: (similarity
functions dnf:path, dnf:lch, dnf:wup), wn:, ontolex:, lexinfo:, skos:, rdfs:,
rdf:, owl:, marl:, dc:, ili:, en:, enl:, cor:. Read dannet://schema/{prefix}
for the schema behind a prefix.
"""

mcp = FastMCP(
    "DanNet",
    instructions=INSTRUCTIONS,
    transport_security=TransportSecuritySettings(
        allowed_hosts=["localhost", "localhost:*", "127.0.0.1", "127.0.0.1:*",
                       "wordnet.dk", "www.wordnet.dk"]),
)


class DanNetError(Exception):
    """An error reported by the DanNet web service."""


def _error_message(r: httpx.Response) -> str:
    """The error message of a failed DanNet response `r`."""
    try:
        anomaly = r.json()["anomaly"]
        message = anomaly["message"]
        message = message.get("en", "") if isinstance(message, dict) else message
        return " ".join(filter(None, [message, anomaly.get("details")]))
    except (ValueError, KeyError, AttributeError, TypeError):
        return r.text.strip() or f"HTTP {r.status_code}"


class DanNet:
    """Async HTTP client for the DanNet web service at `base_url`."""

    def __init__(self, base_url: str):
        self.base_url = base_url.rstrip("/")
        self.http = httpx.AsyncClient(timeout=45.0, follow_redirects=True)
        self.entities: dict[str, dict] = {}

    async def get(self, path: str, **params) -> Any:
        """GET `path` as JSON, retrying on rate limiting and connection errors."""
        for attempt in range(3):
            try:
                r = await self.http.get(self.base_url + path,
                                        params={"format": "json", **params})
            except httpx.TooManyRedirects:
                raise DanNetError(f"Not found: {path}") from None
            except httpx.TransportError as e:
                if attempt == 2:
                    raise DanNetError(f"Request failed: {e}") from e
                await asyncio.sleep(0.5 * 2 ** attempt)
                continue
            if r.status_code == 429 and attempt < 2:
                await asyncio.sleep(0.5 * 2 ** attempt)
                continue
            if r.is_error or "json" not in r.headers.get("content-type", ""):
                raise DanNetError(_error_message(r))
            return r.json()
        raise DanNetError("Rate limit exceeded")

    async def entity(self, identifier: str) -> dict:
        """The JSON-LD of the resource `identifier` (see `_path`), cached."""
        path = _path(identifier)
        if path not in self.entities:
            if len(self.entities) >= 1024:
                del self.entities[next(iter(self.entities))]
            self.entities[path] = await self.get(path)
        return self.entities[path]


dannet: DanNet


def _path(identifier: str) -> str:
    """Web service path of `identifier`: "synset-3047", "dn:synset-3047", a full
    DanNet URI, or a prefixed external resource like "ili:i76470"."""
    if identifier.startswith("http"):
        return "/dannet/data/" + identifier.rsplit("/", 1)[-1]
    prefix, _, local = identifier.rpartition(":")
    if prefix in ("", "dn"):
        return f"/dannet/data/{local}"
    return f"/dannet/external/{prefix}/{local}"


def _local(uri: str) -> str:
    """Local name of `uri`, e.g. "synset-3047"."""
    return uri.rsplit("/", 1)[-1]


def _list(x: Any) -> list:
    """`x` as a list: JSON-LD gives single values bare and multiple as lists."""
    return x if isinstance(x, list) else [] if x is None else [x]


def _text(value: Any, language: str = "da") -> str:
    """The `language` string of a JSON-LD `value`: a plain string, a
    {"@value", "@language"} object, or a list of those."""
    values = _list(value)
    for v in values:
        if isinstance(v, dict) and v.get("@language") == language:
            return v["@value"]
    v = values[0] if values else ""
    return v.get("@value", "") if isinstance(v, dict) else v


def _literal(s: str) -> str:
    """`s` as a Danish SPARQL string literal (JSON escaping is SPARQL escaping)."""
    return json.dumps(s, ensure_ascii=False) + "@da"


@mcp.tool()
async def get_word_synsets(word: str) -> list[dict]:
    """The synsets (senses) of the Danish `word`, as JSON-LD entries with @id
    (e.g. "dn:synset-3047" for get_entity_info), skos:definition,
    dns:ontologicalType and wn:lexfile. A word with a single synset gives that
    synset's full JSON-LD as the only entry."""
    data = await dannet.get("/dannet/search", lemma=word)
    if "@type" in data:  # the service redirects a single synset to its page
        return [data]
    return data.get("@graph", [])


@mcp.tool()
async def get_entity_info(identifier: str) -> dict:
    """Full JSON-LD of a DanNet resource: a synset ("synset-3047"), word
    ("word-11021628") or sense ("sense-21033604"), given bare or prefixed
    ("dn:synset-3047"), or an external resource by prefix ("ili:i76470",
    "ontolex:LexicalConcept"). Properties use prefixed names (wn:hypernym,
    ontolex:isEvokedBy, ...); language-tagged values are
    {"@value": ..., "@language": ...}."""
    return await dannet.entity(identifier)


@mcp.tool()
async def get_word_overview(word: str) -> list[dict]:
    """Every sense of the Danish `word` in one call: a list with synset_id,
    label, definition, lexfile, ontological_types, synonyms (words sharing the
    synset) and hypernym ({synset_id, label} or null) per synset. Only synsets
    where the word itself has a sense count, not multi-word expressions
    containing it."""
    query = f"""
SELECT DISTINCT ?synset ?label ?definition ?lexfile ?ontType ?synonym ?hypernym ?hypernymLabel WHERE {{
  ?entry ontolex:canonicalForm/ontolex:writtenRep {_literal(word)} .
  ?sense ontolex:isSenseOf ?entry ;
         rdfs:label ?senseLabel ;
         ontolex:isLexicalizedSenseOf ?synset .
  FILTER(STRSTARTS(STR(?senseLabel), {json.dumps(word + "_", ensure_ascii=False)}))
  ?synset rdfs:label ?label .
  OPTIONAL {{ ?synset skos:definition ?definition }}
  OPTIONAL {{ ?synset wn:lexfile ?lexfile }}
  OPTIONAL {{
    ?synset dns:ontologicalType ?types . ?types ?pos ?ontType .
    FILTER(STRSTARTS(STR(?pos), STR(rdf:_)))
  }}
  OPTIONAL {{
    ?synset ontolex:isEvokedBy ?otherEntry .
    ?otherEntry ontolex:canonicalForm/ontolex:writtenRep ?synonym .
    FILTER(?synonym != {_literal(word)} && !CONTAINS(STR(?synonym), " "))
  }}
  OPTIONAL {{ ?synset wn:hypernym ?hypernym . ?hypernym rdfs:label ?hypernymLabel }}
}}"""
    result = await dannet.get("/dannet/sparql", query=query, lookahead="false")
    synsets: dict[str, dict] = {}
    for binding in result["results"]["bindings"]:
        row = {k: v["value"] for k, v in binding.items()}
        synset = synsets.setdefault(row["synset"], {
            "synset_id": _local(row["synset"]),
            "label": row["label"],
            "definition": row.get("definition", ""),
            "lexfile": row.get("lexfile"),
            "ontological_types": [],
            "synonyms": [],
            "hypernym": ({"synset_id": _local(row["hypernym"]),
                          "label": row["hypernymLabel"]}
                         if "hypernymLabel" in row else None),
        })
        if "ontType" in row:
            synset["ontological_types"].append("dnc:" + _local(row["ontType"]))
        if "synonym" in row:
            synset["synonyms"].append(row["synonym"])
    for synset in synsets.values():
        synset["ontological_types"] = sorted(set(synset["ontological_types"]))
        synset["synonyms"] = sorted(set(synset["synonyms"]))
    return list(synsets.values())


@mcp.tool()
async def autocomplete_danish_word(prefix: str, max_results: int = 10) -> dict:
    """Danish words starting with `prefix` (at least 3 characters), at most
    `max_results`, as {"autocompletions": [...]} where lemmas are plain strings
    and inflected forms of a matching lemma are [lemma, form] pairs."""
    data = await dannet.get("/dannet/autocomplete", s=prefix)
    return {"autocompletions": data["autocompletions"][:max_results]}


_DDO_DEFINITION = re.compile(
    r'class="[^"]*\bdefinitionBox\b[^"]*\bselected\b[^"]*".*?'
    r'<span[^>]+class="[^"]*\bdefinition\b[^"]*"[^>]*>(.*?)</span>', re.S)


async def _ddo_definition(sense_id: str) -> tuple[str | None, str | None]:
    """The DDO definition behind the dns:source of `sense_id`, as
    (definition, error) with one of them None."""
    source = _list((await dannet.entity(sense_id)).get("dns:source"))
    if not source:
        return None, f"{sense_id}: no dns:source"
    url = source[0].strip("<>")
    try:
        r = await dannet.http.get(url, timeout=10.0)
        r.raise_for_status()
    except httpx.HTTPError as e:
        return None, f"{url}: {e}"
    if m := _DDO_DEFINITION.search(r.text):
        text = html.unescape(re.sub(r"<[^>]+>", "", m.group(1)))
        return " ".join(text.split()), None
    return None, f"{url}: no selected definition found"


@mcp.tool()
async def fetch_ddo_definition(synset_id: str) -> dict:
    """The full definitions of `synset_id` from DDO (ordnet.dk), where DanNet's
    own skos:definition may be truncated. Follows the dns:source of each sense.
    Returns the DanNet definition, the DDO definitions found and any errors;
    DDO and DanNet have drifted apart, so some source URLs no longer resolve."""
    synset = await dannet.entity(synset_id)
    senses = _list(synset.get("ontolex:lexicalizedSense"))
    results = await asyncio.gather(*(_ddo_definition(s) for s in senses))
    return {"synset_id": synset["@id"],
            "definition": _text(synset.get("skos:definition")),
            "ddo_definitions": [d for d, _ in results if d],
            "errors": [e for _, e in results if e]}


@mcp.tool()
async def sparql_query(query: str, timeout: int = 8000, max_results: int = 100,
                       distinct: bool = True, inference: bool | None = None) -> dict:
    """Run a SPARQL SELECT `query` against DanNet and return standard SPARQL
    JSON results ({"head": ..., "results": {"bindings": [...]}}). The common
    prefixes (dn, dns, dnc, dnf, wn, ontolex, lexinfo, skos, rdfs, rdf, owl,
    marl, dc, ili, en, enl, cor) are declared automatically. `timeout` is in
    ms (max 15000), `max_results` at most 100, `distinct` adds DISTINCT, and
    `inference` selects the model: None tries the base model and retries with
    inference on an empty result; True forces inference, which inverse
    relations like wn:hyponym and wn:holo_* need; False forces the base model.

    Performance rules: anchor every query on a known URI or a word lookup
    (dn:synset-3047 wn:hypernym ?x, never ?x wn:hypernym ?y alone); never
    FILTER(CONTAINS(...)) over all labels, look the word up first; make every
    triple pattern share a variable with another; add LIMIT; prefer VALUES
    over FILTER for several known URIs; the store also holds the English
    WordNet (en:), so anchor on dn: or "..."@da to stay in Danish.

    Templates:
      Synsets of a word:
        SELECT DISTINCT ?synset ?label WHERE {
          ?entry ontolex:canonicalForm/ontolex:writtenRep "hund"@da .
          ?entry ontolex:sense/ontolex:isLexicalizedSenseOf ?synset .
          ?synset rdfs:label ?label }
      Taxonomic ancestors:
        SELECT DISTINCT ?ancestor ?label WHERE {
          dn:synset-3047 wn:hypernym+ ?ancestor . ?ancestor rdfs:label ?label }
      Hyponyms (needs inference=True, or query the inverse wn:hypernym):
        SELECT DISTINCT ?hyponym ?label WHERE {
          ?hyponym wn:hypernym dn:synset-3047 . ?hyponym rdfs:label ?label }
      Ontological types (an RDF bag):
        SELECT ?type WHERE {
          dn:synset-3047 dns:ontologicalType/?pos ?type .
          FILTER(STRSTARTS(STR(?pos), STR(rdf:_))) }
      Taxonomic similarity (dnf:path, dnf:lch, dnf:wup score two synsets of
      the same language and part of speech, 1.0 for identical):
        SELECT ?synset ?score WHERE {
          ?synset a ontolex:LexicalConcept .
          FILTER(STRSTARTS(STR(?synset), STR(dn:)))
          BIND(dnf:wup(dn:synset-3047, ?synset) AS ?score) }
        ORDER BY DESC(?score) LIMIT 20"""
    params = {"timeout": timeout, "limit": max_results, "lookahead": "false",
              "distinct": str(distinct).lower()}
    if inference is not None:
        params["inference"] = str(inference).lower()
    return await dannet.get("/dannet/sparql", query=query, **params)


@lru_cache
def _schema(prefix: str) -> str:
    """The Turtle schema behind `prefix`, fetched once per process."""
    r = httpx.get(f"{dannet.base_url}/schema/{prefix}", follow_redirects=True)
    if r.is_error:
        raise ValueError(f"No schema for the prefix {prefix!r}")
    return r.text


@mcp.resource("dannet://schema/{prefix}")
def schema(prefix: str) -> str:
    """The RDF schema (Turtle) behind a namespace `prefix`: dns (DanNet
    relations and properties), dnc (ontological types), wn (WordNet
    relations), ontolex, lexinfo, skos, marl, ..."""
    return _schema(prefix)


@mcp.resource("dannet://dannet-schema")
def dannet_schema() -> str:
    """The DanNet schema (dns:): DanNet's own relations and properties."""
    return _schema("dns")


@mcp.resource("dannet://ontological-types")
def ontological_types() -> str:
    """The ontological types (dnc:) used in dns:ontologicalType."""
    return _schema("dnc")


@mcp.resource("dannet://wordnet-schema")
def wordnet_schema() -> str:
    """The Global WordNet schema (wn:): the standard synset relations."""
    return _schema("wn")


@mcp.prompt()
def analyze_danish_word(word: str, include_examples: bool = True) -> str:
    """A full linguistic analysis of a Danish `word`."""
    examples = ("\n5. Usage examples and common collocations"
                if include_examples else "")
    return f"""Analyse the Danish word "{word}" with the DanNet tools:
1. Every synset (sense) of the word, with definitions
2. Synonyms and related words per sense
3. Part of speech and ontological types
4. Semantic relations: hypernyms, hyponyms, meronyms{examples}
Structure the result for a language learner or linguist."""


@mcp.prompt()
def compare_danish_words(word1: str, word2: str) -> str:
    """A semantic comparison of two Danish words."""
    return f"""Compare the Danish words "{word1}" and "{word2}" with the DanNet tools:
1. Shared and distinct synsets or semantic fields
2. Taxonomic relations between them, quantified with dnf:wup via sparql_query
3. Contexts where each word is preferred, and shared vs. unique synonyms
Cite synset ids and definitions."""


@mcp.prompt()
def explore_semantic_field(concept: str, depth: int = 2) -> str:
    """The semantic field around a Danish `concept`, `depth` hyponym levels deep."""
    return f"""Explore the semantic field of the Danish concept "{concept}" with DanNet:
1. Its synset(s) and their ontological types
2. Hyponyms {depth} levels deep, plus wn:similar and dns:orthogonalHypernym
3. Domain relations such as dns:usedFor
Present the field as a structured vocabulary overview."""


@mcp.prompt()
def trace_taxonomic_path(word1: str, word2: str) -> str:
    """The taxonomic relationship between two Danish words."""
    return f"""Trace the taxonomic relationship between "{word1}" and "{word2}" in DanNet:
1. A direct hypernym/hyponym relation, or else their nearest common hypernym
2. The hypernym chain from each word to the common ancestor
3. Their similarity via dnf:path, dnf:lch and dnf:wup (sparql_query)
Explain what distinguishes the two concepts."""


@mcp.prompt()
def map_part_whole_relations(entity: str, direction: str = "both") -> str:
    """The part-whole relations of a Danish `entity`: "parts", "wholes" or "both"."""
    relations = {"parts": "wn:mero_part, wn:mero_substance, wn:mero_member and wn:mero_location",
                 "wholes": "wn:holo_part, wn:holo_substance, wn:holo_member and wn:holo_location"}
    wanted = relations.get(direction) or " and ".join(relations.values())
    return f"""Map the part-whole relations of the Danish word "{entity}" in DanNet:
1. Its synsets
2. Per synset, {wanted}, noting direct vs. dns:inherited relations
Present the result as a structural decomposition."""


@mcp.prompt()
def find_translation_equivalents(word: str, target_lang: str = "en") -> str:
    """Cross-linguistic equivalents of a Danish `word`."""
    return f"""Find {target_lang} equivalents of the Danish word "{word}" in DanNet:
1. Its synsets and, per synset, wn:ili and wn:eq_synonym links
2. Which senses have exact vs. approximate equivalents, and the semantic gaps
Give a translation guide covering the word's whole range of senses."""


@mcp.prompt()
def analyze_verb_roles(verb: str) -> str:
    """The thematic roles and event structure of a Danish `verb`."""
    return f"""Analyse the event structure of the Danish verb "{verb}" in DanNet:
1. Its verbal synsets and ontological types
2. Thematic roles: wn:agent, wn:patient, wn:instrument, wn:result and the involved_* inverses
3. Co-occurrence (dns:co_*) and causal relations (wn:causes, wn:entails, wn:subevent)
Explain what roles the verb assigns."""


@mcp.prompt()
def explore_polysemy(word: str, include_etymology: bool = False) -> str:
    """The distinct senses of a polysemous Danish `word`."""
    etymology = ("\n4. Shared etymological origins and metaphorical extensions"
                 if include_etymology else "")
    return f"""Explore the polysemy of the Danish word "{word}" in DanNet:
1. Every synset with its full definition (fetch_ddo_definition if truncated), ontological type and register
2. Which senses are related (metaphor, specialisation) and which are distinct
3. The most prototypical sense and the synonyms unique to each sense{etymology}
Create a sense map."""


def _detect_server() -> str:
    """The local DanNet server when it responds, otherwise wordnet.dk."""
    try:
        httpx.get(LOCAL_URL, timeout=3.0)
        return LOCAL_URL
    except httpx.HTTPError:
        return REMOTE_URL


def main():
    parser = argparse.ArgumentParser(description="DanNet MCP server")
    parser.add_argument("--base-url", help="DanNet web service URL (default: a "
                        "local server when running, otherwise wordnet.dk)")
    parser.add_argument("--local", action="store_true", help=f"use {LOCAL_URL}")
    parser.add_argument("--http", action="store_true",
                        help="serve streamable HTTP instead of stdio")
    parser.add_argument("--host", default="127.0.0.1", help="HTTP bind address")
    parser.add_argument("--port", type=int, default=8000, help="HTTP port")
    parser.add_argument("--debug", action="store_true", help="debug logging")
    args = parser.parse_args()
    logging.basicConfig(level=logging.DEBUG if args.debug else logging.INFO)
    logging.getLogger("httpx").setLevel(logging.WARNING)

    local = args.local or os.getenv("DANNET_MCP_LOCAL", "").lower() == "true"
    global dannet
    dannet = DanNet(args.base_url or (LOCAL_URL if local else _detect_server()))
    log.info("Using DanNet at %s", dannet.base_url)

    if args.http:
        mcp.settings.host, mcp.settings.port = args.host, args.port
        mcp.run(transport="streamable-http")
    else:
        mcp.run()


if __name__ == "__main__":
    main()
