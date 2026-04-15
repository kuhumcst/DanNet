* [Lægmand](/dannet/page/intro-layman)
* **Udvikler**
* [Ontolog](/dannet/page/intro-rdf)
* [Lingvist](/dannet/page/intro-linguist)

# Hvad er DanNet?
DanNet er et åbent dansk [WordNet][WordNet]: en struktureret leksikalsk database, der organiserer danske ord i synonymgrupper (synsets) forbundet via semantiske relationer. Det indeholder i øjeblikket ca. 70.000 synsets, der dækker ca. 62.000 ord. Det er skabt af [Center for Sprogteknologi][CST] (Københavns Universitet) og [Dansk Sprog- og Litteraturselskab][DSL] og er tilgængeligt i en interaktiv version på [wordnet.dk][home]. DanNet er designet til nemt at kunne integreres med andre leksikalske datakilder.

DanNet-kildekoden er MIT-licenseret og tilgængelig (sammen med aktuelle og tidligere udgivelser af DanNet-datasættene) [på vores GitHub-repo][Github].

## Adgang til data

### Content negotiation
Alle RDF-ressourcer i DanNet fungerer som dereferencerbare URI'er. Du kan anmode om forskellige repræsentationer via HTTP-headeren `Accept`: `text/html` for websiden, `application/ld+json` for JSON-LD og `text/turtle` for RDF/Turtle. For eksempel:

```bash
curl -H "Accept: application/ld+json" https://wordnet.dk/dannet/data/synset-5028
```

### SPARQL-endpoint
Et offentligt [SPARQL-endpoint][SPARQL endpoint] er tilgængeligt til forespørgsler. I en browser viser det en interaktiv forespørgselseditor. Se [SPARQL-guiden][SPARQL guide] for en introduktion til at forespørge DanNet.

SPARQL-endpointet accepterer også programmatiske forespørgsler via GET (med en `query`-parameter) eller POST (med forespørgslen som request body), og understøtter content negotiation (herunder `application/sparql-results+json`). Yderligere forespørgselsparametre: `limit`, `offset`, `timeout`, `inference` og `distinct`. Der er dog begrænsninger på resultatsætstørrelse og forespørgselstid, så hvis du har brug for mere ressourcekrævende forespørgsler, er det bedre at forespørge datasættet i en lokal RDF-graf.

### WN-LMF + Python
[WN-LMF][wn-lmf]-formatet kan bruges med Python-biblioteket [wn][wn]. Et [tutorialscript][tutorial] er tilgængeligt i GitHub-repoet og dækker polysemi, relationer, taksonomi, lighed, ILI m.m.:

```python
import wn
from wn import taxonomy, similarity

wn.add("dannet-wn-lmf.xml.gz")
dn = wn.Wordnet("dn")

# Polysemi: flere veje til samme data
for ss in dn.synsets("land"):
    print(ss.definition())

# Semantisk lighed
ss1 = dn.synsets("hund", pos="n")[0]
ss2 = dn.synsets("kat", pos="n")[0]
print(similarity.path(ss1, ss2))
print(similarity.wup(ss1, ss2))
```

Vi anbefaler [uv][uv] til at køre Python-scripts. Det håndterer afhængigheder automatisk. Eksemplerne i repoet er sat op som uv-scripts og kan køres direkte med f.eks. `uv run dannet_tutorial.py`.

> **BEMÆRK:** WN-LMF indeholder kun officielle GWA-relationer; DanNet-specifikke relationer (såsom `used_for`) er kun tilgængelige i RDF-formatet.

### AI-integration
En [MCP-server][mcp] er tilgængelig til integration med AI/LLM-værktøjer som Claude og ChatGPT. MCP-serverens URL er `https://wordnet.dk/mcp`. Den giver direkte adgang til DanNet-API'et og returnerer resultater som [JSON-LD][JSON-LD], som sprogmodellen kan fortolke ved hjælp af de medfølgende skemaer.

## Datasæt
DanNet er tilgængeligt i en håndfuld forskellige datasæt formateret som enten RDF/Turtle, CSV eller WN-LMF XML. Alle datasæt er udgivet under [CC BY-SA 4.0][license]-licensen. Du kan [downloade dem her][downloads]. Alle udgivelser er også tilgængelige på [GitHub-udgivelsessiden][releases].

RDF-datasættet er den kanoniske version. Det kan importeres i enhver moderne RDF-triplestore og forespørges med [SPARQL][SPARQL].

## Standarder og integrationer
DanNet er modelleret ved hjælp af [Ontolex-lemon][Ontolex]-standarden med [tilføjelser][GWA RDF] fra [Global WordNet Association][GWA], hvilket gør det interoperabelt med andre WordNets og linked data-ressourcer. Det integrerer direkte med [COR][COR] (Det Centrale Ordregister), [DDS][DDS] (Det Danske Sentimentleksikon) og [Open English WordNet][OEWN].

## Teknologistak
DanNet-kodebasen er et MIT-licenseret Clojure/ClojureScript-projekt på [GitHub][Github], bygget oven på [Apache Jena][jena] RDF-triplestore. Web-appen er et [Pedestal][pedestal]-baseret backend med et [Rum][rum] (React) frontend og fungerer både som server-renderet site og single-page application.

## Dokumentation
Yderligere udviklerorienteret dokumentation:

* [Python-eksempler][examples] — tutorial og eksempler til at arbejde med DanNet i Python
* [SPARQL-guide][SPARQL guide] — en praktisk introduktion til at forespørge DanNet med SPARQL
* [Forespørgsler i DanNet][queries] (en) — SPARQL, Aristotle DSL og graftraversering
* [Sense/synset-labelformat][label-rewrite] (en)
* [Rationale][rationale] (en)

[home]: https://wordnet.dk "DanNet"
[WordNet]: https://wordnet.princeton.edu/ "What is WordNet?"
[DSL]: https://dsl.dk/ "Dansk Sprog- og Litteraturselskab"
[CST]: https://cst.ku.dk/ "Center for Sprogteknologi (Københavns Universitet)"
[Ontolex]: https://www.w3.org/2016/05/ontolex/ "Lexicon Model for Ontologies"
[GWA RDF]: https://globalwordnet.github.io/schemas/#rdf "GWA RDF schema"
[GWA]: http://globalwordnet.org/ "Global WordNet Association"
[COR]: http://ordregister.dk "Det Centrale Ordregister"
[DDS]: https://github.com/dsldk/danish-sentiment-lexicon "Det Danske Sentimentleksikon"
[OEWN]: https://en-word.net/ "Open English WordNet"
[license]: https://creativecommons.org/licenses/by-sa/4.0/ "CC BY-SA 4.0"
[about]: /dannet/page/about "Om DanNet"
[SPARQL]: https://www.w3.org/TR/sparql11-query/ "SPARQL 1.1 Query Language specification"
[SPARQL endpoint]: /dannet/sparql "SPARQL-endpoint"
[SPARQL guide]: /dannet/page/sparql "SPARQL-guide"
[mcp]: /dannet/page/mcp "MCP-server"
[JSON-LD]: https://json-ld.org/ "JSON for Linking Data"
[jena]: https://jena.apache.org/ "Apache Jena"
[pedestal]: https://github.com/pedestal/pedestal "Pedestal"
[rum]: https://github.com/tonsky/rum "Rum"
[Github]: https://github.com/kuhumcst/DanNet "GitHub-projektsiden"
[wn]: https://github.com/goodmami/wn "Et Python-bibliotek til udforskning af WordNets"
[wn-lmf]: /export/wn-lmf/dn "DanNet (WN-LMF)"
[uv]: https://docs.astral.sh/uv/ "uv: en hurtig Python-pakke- og projekthåndtering"
[tutorial]: https://github.com/kuhumcst/DanNet/blob/master/examples/dannet_tutorial.py "Python-tutorialscript"
[examples]: https://github.com/kuhumcst/DanNet/tree/master/examples "Python-eksempler"
[downloads]: /dannet/page/downloads "Download datasæt"
[releases]: https://github.com/kuhumcst/DanNet/releases "Tidligere udgivelser"
[queries]: /dannet/page/queries "Forespørgsler i DanNet"
[label-rewrite]: /dannet/page/label-rewrite "Synset/sense-labelformat"
[rationale]: /dannet/page/rationale "Rationale"
