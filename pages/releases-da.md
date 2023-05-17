# Versioner
De nye DanNet-versioner bruger udgivelsesdatoen som versionsnummer, formatteret som `YYYY-MM-DD`.

## SNAPSHOT
* Mange DanNet-ord og -betydninger er blevet forbundet med [DDO](https://ordnet.dk/ddo) via den nye `dns:source`-relation.

## **2023-05-11**: Det nye DanNet
Der er for mange ændringer i denne første version til at opremse dem alle på en kortfattet måde:

* Omkring 5000 nye betydninger er blevet tilføjet, primært adjektiver.
* Der er blevet renset ud i mange uoverensstemmelser i datasættet og andre uønskede ting.
* Hele DanNet er nu konverteret over til Ontolex-standarden og bruger relationerne fra Global WordNet Association.
* DanNet er nu "RDF-native"; RDF-skemaer er også tilgængelige og dækker bl.a. ontologiske typer.
* De DSL-afledte Dannet-ID'er oversættes nu til egentlige RDF-ressourcer der kan ses i en browser.
* Diverse alternative datasæt kan også downloades og disse er også sammenføjet med det data der er på wordnet.dk.
* Yderligere datapunkter er også blevet udledt fra vores "bootstrap"-data, f.eks. omvendte relationer.
* Vores CSV-download er nu CSVW og inkludere metadatafiler, der beskriver indholdet af kolonnerne.
* DanNet-datasættene er nu tilgængelige under en CC BY-SA 4.0-licens og projektets kildekode under en MIT-licens.
* ... og selvfølgelig er wordnet.dk/dannet nu DanNets nye hjem.

Udover arbejdet med selve DanNet, har vi også lavet nogle tilføjelser til Global WordNet Association's nye RDF-skema.
