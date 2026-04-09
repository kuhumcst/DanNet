# /// script
# requires-python = ">=3.10"
# dependencies = ["rdflib"]
# ///
"""
Extract DDO source URLs from a DanNet RDF export.

Run with:
    uv run ddo_sources.py

Interactive REPL (for copy-pasting individual lines):
    uv run --with rdflib python

Requires ../export/rdf/dannet.ttl to exist.
"""

from rdflib import Graph
from rdflib.namespace import NamespaceManager

# create a graph, make sure dannet.ttl exists at this path
g = Graph()
g.parse('../export/rdf/dannet.ttl')

# define a SPARQL query to retrieve DDO source URLs
q = "SELECT * WHERE { ?resource dns:source ?source }"

# reuse input prefixes for output, mostly for aesthetics
nm = NamespaceManager(g)

# print results to sources.csv
with open("sources.csv", "w") as output:
    for row in g.query(q):
        output.write(f"{nm.qname(row.resource)}, {row.source}\n")
