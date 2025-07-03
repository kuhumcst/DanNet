# Versioner
De nye DanNet-versioner bruger udgivelsesdatoen som versionsnummer, formateret som `YYYY-MM-DD`.

## **2025-07-03**: Forbedret validitet af WN-LMF
* WN-LMF-eksporten valideres nu korrekt i henhold til outputtet fra `wn` kommandolinjeprogrammet.
  * De fundne problemer og eliminationsprocessen er dokumenteret i [Github issue #146](https://github.com/kuhumcst/DanNet/issues/146).
* Dublerede betydninger er blevet flettet. Deres synsets er blevet ommærket og relinket til COR.
* Dublerede former er blevet fjernet.
* Synset-selvreferencer er blevet fjernet.
* For adjektiver, hvor `wn:hypernym`-relationen krydser ordklassegrænser, er denne relation blevet erstattet med den specialdesignede `dns:crossPoSHypernym`, som kan bruges til at skabe noget, der minder om hypernymiske relationer på tværs af forskellige ordklasser.
  * BEMÆRK: Andre cross-PoS-hyperonymer er *ikke* blevet ændret i denne udgivelse, men de er udelukket fra WN-LMF-formatet indtil videre, da disse relationer teknisk set er ugyldige, når de er markeret som `wn:hypernym`.
* Dublerede ILI-links er blevet udelukket fra WN-LMF-formatet.
  * 1194 synsets i DanNet er linket til de samme ressourcer i `CILI`, hvilket ikke er en gyldig brug af relationen `wn:ili`!
* Afledte `wn:hyponym`-links er nu *inkluderet* i WN-LMF-formatet.
* `dns:supersense` er nu `wn:lexfile`, den tilsvarende relation i GWA-skemaet.
  * Den engelske WordNet brugte faktisk ikke denne relation tidligere, men vil nu gøre det i fremtidige udgivelser (efter vores forespørgsel).
* Forskellige mindre manuelle udelukkelser af synsets i WN-LMF-formatet for at opfylde valideringskrav.
* Synset indegrees er blevet genberegnet.

## **2024-08-09**: Supersenses + OEWN-opdatering
* Tilføjede 71055 Supersenses til DanNet, primært baseret på en mapping skabt til SemDaX-korporaet.
  * Omkring 300 af disse er "rettet" efter hypernym til forskel fra mappingen.
  * Yderligere 3793 af disse er blevet til på baggrund af en ny mapping af ikke-mappede synsets.
* Tilføjede valør/sentiment til 183 synsets taget fra det gamle DanNet 2 connotation-data.
* De eksisterende links til 2022-udgaven af Open English WordNet er blevet erstattet af links til 2023-udgaven (nye synset-ID'er)
* OEWN-udvidelsesdatasættet er blevet opdateret sådan at det bruger de new OEWN-synset-ID'er.

## **2024-06-12**: WN-LMF som alternativt format
* På baggrund af en forespørgsel via Github er WN-LMF nu blevet tilføjet som et alternativt format. Den nye fil, `dannet-wn-lmf.xml.gz`, kan bruges direkte i software som [goodmami/wn](https://github.com/goodmami/wn) (se også: [eksempel på Github](https://github.com/kuhumcst/DanNet/blob/master/examples/wn_lmf_query.py)). Desværre understøtter WN-LMF ikke det fulde sortiment af data som findes i DanNet; eksempelvis er vores ontologiske typer ikke at finde i dette format og det samme gælder DanNet-specifikke relationer som `bruges til`.
* I alt 1906 dårlige kildehenvisninger til DDO er blevet fjernet fra datasættet. Disse `dns:source`-relationer var blevet oprettet automatisk baseret på ID'er der udelukkende eksister i DanNet, så derfor kan der ikke kildehenvises til DDO.
* 88 Synset-definitioner er rettet således at opdelingen mellem titler og embeder er korrekt.
* Synset indegrees er blevet genberegnet.

## **2024-04-30**: Forbedret CSV-eksport + andre små rettelser
* CSV-eksporten er blevet forbedret ved...
    1. ... at fjerne tilstedeværelsen af interne ID'er i `synsets.csv` (der henviser til ontologityper) og i stedet erstatte dem med den konkrete sammensætning af ontologityper.
    2. .. at inkludere leksikale opslag i `words.csv`, som tidligere fejlagtigt blev udeladt.
* Nogle leksikale opslag, der tidligere var af den generiske type `ontolex:LexicalEntry`, har nu mere specifikke typer, f.eks. `ontolex:Word`, `ontolex:MultiWordExpression` eller `ontolex:Affix`.
* Nogle af ordklasserne for adjektiver tilføjet i udgivelsen `2023-05-11` manglede en ordklasse-relation og/eller forvekslede to separate relationstyper; dette er nu blevet rettet.

## **2023-11-28**: Korte etiketter
* `dns:shortLabel`-varianter af de eksisterende synset-labels (udledt fra bl.a. ordfrekvenser fra [DDO](https://ordnet.dk/ddo)) er blevet tilføjet til DanNet-datasættet.
* `dns:source` bruges nu igen til at linke til oprindelige opslagskilder som f.eks. DDO. Brugen af `dc:source` var både problematisk ift. skemadefinitionen samt det irritationsmoment at `dc` i visse tilfælde skaber forvirring når det bruges som RDF-præfiks, da det kan være hardcoded til en bestemt IRI.
* Nogle sense-etiketter havde ved en fejl mistet deres sprog (@da) og dette er nu udbedret.

## **2023-09-28**: Rettelse af domain topic-relationen
* `wn:has_domain_topic`-relationen har været brugt i stedet for `wn:domain_topic` i DanNet-datasættet. Dette er nu blevet rettet.

## **2023-07-07**: Tusinder af nye links samt skemaopdateringer
* DanNet har nu omkring 10K nye links til [CILI](https://github.com/globalwordnet/cili), som også linker til OEWN og andre WordNets.
* Flere nye relationer ( `dns:eqHypernym`, `dns:eqHyponym` og `dns:eqSimilar` ) er blevet tilføjet, da `wn:ili` og `wn:eqSynonym` ikke var nok til at beskrive de relationer vi har mellem forskellige WordNets nu.
* DanNet-synsets har nu også de rå DDO-domæneværdier fra DSL, der fandtes i gamle versioner af DanNet. Disse repræsenteres med  `dc:subject`-relationen.
* Derudover er kønsdata fra de gamle versioner af DanNet også nu inkluderet. Det kan findes via den nye `dns:gender`-relation.
* For bedre at kunne facilitere navigation af grafen på DanNet-hjemmesiden er en ny relation, `dns:linkedConcept`, blevet tilføjet til DanNet-skemaet. Denne relation er den omvendte relation af `wn:ili` og kan udledes i den store graf der kan udforskes på wordnet.dk/dannet.

## **2023-06-01**: ~5000 links til Open English WordNet
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
