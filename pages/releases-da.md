# Versioner
De nye DanNet-versioner bruger udgivelsesdatoen som versionsnummer, formatteret som `YYYY-MM-DD`.

## **SNAPSHOT**: Ordfrekvens
* Ordfrekvens fra [DDO](https://ordnet.dk/ddo) (delt af DSL) er blevet tilføjet til DanNet-datasættet.

## **2023-09-28**: Rettelse af domain topic-relationen
* `wn:has_domain_topic`-relationen har været brugt i stedet for `wn:domain_topic` i DanNet-datasættet. Dette er nu blevet rettet.

## **2023-07-07**: Tusinder af nye links samt skemaopdateringer
* DanNet har nu omkring 10K nye links til [CILI](https://github.com/globalwordnet/cili), som også linker til OEWN og andre WordNets.
* Flere nye relationer ( `dns:eqHypernym`, `dns:eqHyponym` og `dns:eqSimilar` ) er blevet tilføjet, da `wn:ili` og `wn:eqSynonym` ikke var nok til at beskrive de relationer vi har mellem forskellige WordNets nu.
* DanNet-synsets har nu også de rå DDO-domæneværdier fra DSL, der fandtes i gamle versioner af DanNet. Disse repræsenteres med  `dc:subject`-relationen.
* Derudover er kønsdata fra de gamle versioner af DanNet også nu inkluderet. Det kan findes via den nye `dns:gender`-relation.
* For bedre at kunne facilitere navigation af grafen på DanNet-hjemmesiden er en ny relation, `dns:linkedConcept`, blevet tilføjet til DanNet-skemaet. Denne relation er den omvendte relation af `wn:ili` og kan udledes i den store graf der kan udforskes på wordnet.dk/dannet.

## **2023-06-01**:  ~5000 links til Open English WordNet
* Skemaoversættelserne er blevet opdateret.
* Omtrent 5000 links er blevet tilføjet, som linker DanNet med [Open English WordNet](https://github.com/globalwordnet/english-wordnet) eller indirekte via [CILI](https://github.com/globalwordnet/cili).
* OEWN-datasættet har fået et medfølgende datasæt der indeholder genererede etiketter for synsets, betydninger og ord.
* `dns:dslSense` og `dns:source` er fjernet fra DanNet-skemaet (`dns:source` erstattes med `dc:source`)

## **2023-05-23**: DDS/COR-forbedringer & links til DDO
Følgende ændringer af vores datasæt vil være tilgængelige i næste version:

* Mange DanNet-ord og -betydninger er blevet forbundet med [DDO](https://ordnet.dk/ddo) via den nye `dns:source`-relation.
* Uofficielle bøjningsformer der er til stede i COR-datasættet er blevet markeret således i deres `rdfs:label`.
* Diverse mindre ændringer af COR-datasættet.
* DDS-datasættet bruger nu 32-bit `float` i stedet for `double` hvilket resulterer i en mindre RDF-eksport, da denne datatype ikke kræver særlig kodning i .ttl-filer.

## **2023-05-11**: Det nye DanNet
Der er for mange ændringer i denne første version til at opremse dem alle på en kortfattet måde:

* Omkring 5000 nye betydninger er blevet tilføjet, primært adjektiver.
* Der er blevet renset ud i mange uoverensstemmelser i datasættet og andre uønskede ting.
* Hele DanNet er nu konverteret over til Ontolex-standarden og bruger relationerne fra Global WordNet Association.
* DanNet er nu "RDF-native"; RDF-skemaer er også tilgængelige og dækker bl.a. ontologiske typer.
* De DSL-afledte Dannet-ID'er oversættes nu til egentlige RDF-ressourcer der kan ses i en browser.
* Diverse alternative datasæt kan også downloades og disse er også sammenføjet med det data der er på wordnet.dk.
* Yderligere datapunkter er også blevet udledt fra vores "bootstrap"-data, f.eks. omvendte relationer.
* Vores CSV-download er nu CSVW og inkluderer metadatafiler, der beskriver indholdet af kolonnerne.
* DanNet-datasættene er nu tilgængelige under en CC BY-SA 4.0-licens og projektets kildekode under en MIT-licens.
* ... og selvfølgelig er wordnet.dk/dannet nu DanNets nye hjem.

Udover arbejdet med selve DanNet, har vi også lavet nogle tilføjelser til Global WordNet Association's nye RDF-skema.
