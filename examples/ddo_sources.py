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
