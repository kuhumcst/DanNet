from rdflib import Graph
from rdflib.namespace import NamespaceManager

# create a graph, make sure dannet.ttl exists at this path
g = Graph()
g.parse('../export/rdf/dannet.ttl')

# define a SPARQL query to retrieve links to other synsets
q = """
SELECT ?synset ?linkType ?otherSynset
WHERE {
  ?synset ?linkType ?otherSynset .
  VALUES ?linkType { dns:eqHypernym dns:eqHyponym dns:eqSimilar wn:eq_synonym }
}
"""

# reuse input prefixes for output, mostly for aesthetics
nm = NamespaceManager(g)

# print results to links.csv
with open("links.csv", "w") as output:
    for row in g.query(q):
        output.write(f"{nm.qname(row.synset)}, {nm.qname(row.linkType)}, {nm.qname(row.otherSynset)}\n")
