# Vejledning: Gennemgang af hypernymer på tværs af ordklasser

## Hvad handler det om?

I DanNet skal et synsets hypernym ("overbegreb") normalt have samme ordklasse
som synsettet selv: substantiver hører under substantiver, verber under verber
osv. En automatisk kontrol har fundet **285 synset-par**, hvor det ikke er
tilfældet, f.eks. substantivet {filtrering}, der peger på verbet {fjerne} som
sit hypernym, eller verbet {tilsvine}, der peger på substantivet
{talehandling}.

Disse relationer kan ikke rettes automatisk, for det rigtige svar afhænger af
et fagligt skøn: Skal relationen flyttes til et andet synset? Skal den
erstattes af en anden relationstype? Eller er der i virkeligheden tale om en
forkert ordklasse på selve ordet?

## Filerne

Gennemgangen ligger nu i CSV-filer (tidligere ét regneark) i `doc/crosspos/`.
De kan åbnes direkte i Excel eller LibreOffice. To af dem skal udfyldes:

| fil | par | din opgave |
|---|---|---|
| `2d-cross-pos-taxonomy.csv` | 285 | udfyld *decision* (og *retarget to*) |
| `a4-deferred-crosspos.csv` | 104 | udfyld *decision* |
| `2a-verb-phrase-pos-flip.csv` | 110 | ingen: dokumentation af en allerede truffet beslutning |

Filerne gendannes maskinelt fra databasen. Kolonnerne *decision*, *retarget to*
og *comment* bevares ved gendannelse, så dit arbejde går ikke tabt.

## `2d-cross-pos-taxonomy.csv`

Hver **række** er ét problematisk par: et kilde-synset (*source*) og dets
nuværende hypernym (*target*). Rækkerne er **grupperet efter target**, så alle
synsets, der peger på det samme hypernym, står samlet. Grupperne står med de
største først, og pointen er, at man ofte kan træffe **én beslutning for hele
gruppen** ad gangen (kolonnen *n* viser gruppens størrelse).

| kolonne | betydning |
|---|---|
| *target* / *target URI* / *target PoS* | Det nuværende hypernym, link til wordnet.dk og dets ordklasse. |
| *n* | Antal rækker i gruppen, dvs. hvor mange synsets der peger på dette target. |
| *source* / *source URI* / *source PoS* | Synsettet med den problematiske relation, link og ordklasse. |
| *retarget candidates* | Automatisk fundne alternativer: synsets med samme lemma som target, men med kildens ordklasse. Kun et forslag, skal altid efterses. |
| *decision* | **Udfyldes af dig**, se nedenfor. |
| *retarget to* | **Udfyldes ved "retarget"**: indsæt URI'en på det nye hypernym. Er der præcis én oplagt kandidat, er feltet udfyldt på forhånd. Ret det, hvis forslaget er forkert. |
| *comment* | Fri tekst til forbehold, tvivl eller begrundelse. |

Klik på linkene i URI-kolonnerne for at se synsettets fulde opslag på
wordnet.dk, inkl. definition og relationer.

### Beslutningsmulighederne i *decision*

- **retarget**: relationen er reel nok, men skal pege på et andet synset med
  samme ordklasse som kilden. Skriv det nye synsets URI i *retarget to*.
  Eksempel: substantiver under adjektivet {sindssyg} flyttes til substantivet
  {sindslidende}.
- **attribute**: target er et egenskabssubstantiv, som kilden (et adjektiv)
  udtrykker en værdi af; relationen ændres til `wn:attribute`. Det er samme
  behandling som de ca. 5.400 tilfælde, der allerede er gennemført.
- **pos-fix**: relationen er korrekt, men kildens (eller targets) ordklasse er
  forkert angivet, typisk en verbalfrase mærket som substantiv. Ordklassen
  rettes, og relationen bevares. Skriv gerne i *comment*, hvilket ord det
  gælder.
- **exemplifies**: kilden er ikke en type af target, men et *eksempel på en
  brugsmarkering*, f.eks. {helvedes} under {bandeord}. Relationen ændres til
  `wn:exemplifies`.
- **delete**: relationen er forkert og har ingen brugbar erstatning på
  synset-niveau (typisk pertainym- eller participium-agtige forbindelser).
  Bemærk: kilden mister som regel sit eneste hypernym, så brug kun denne, når
  hverken retarget eller en anden relation giver mening.
- **keep**: relationen skal bevares som den er (undtagelsen bekræftes).

Er du i tvivl, så lad *decision* stå tom og skriv i *comment*. Tomme rækker
samles op i næste runde.

> **Bemærk:** `classified_by` var tidligere en mulighed her, men er trukket
> tilbage. Relationen betegner i GWA-standarden *numeralklassifikatorer*
> (f.eks. 'head' i 'head of cattle'), ikke substantiverede adjektiver, og
> passer derfor ikke på nogen af vores tilfælde.

## `a4-deferred-crosspos.csv`

104 par, der tidligere blev omsat automatisk til `wn:classified_by`. Da den
relation viste sig at være forkert, er de sat i bero og afventer nu en
beslutning. De står stadig som `dns:crossPoSHypernym` i datasættet.

Kolonnen *group* deler dem i fire mønstre, som formentlig skal behandles hver
for sig:

| group | par | eksempel | sandsynlig behandling |
|---|---|---|---|
| language | 62 | {koreansk} → {sprog} | pos-fix (substantiveret adjektiv) |
| register | 26 | {helvedes} → {bandeord} | exemplifies |
| person | 13 | {hjemløs} → {person} | pos-fix |
| nominal idiom | 3 | {en lille sort} → {kaffe} | pos-fix |

Samme *decision*-værdier som ovenfor. Bemærk at **pos-fix** her indebærer en
påstand om, at ordet har en veletableret substantivisk brug på dansk, hvilket
bør kunne bekræftes i DDO for hvert enkelt ord. Det er netop derfor, de ikke
blev rettet automatisk.

## `2a-verb-phrase-pos-flip.csv`

Denne fil kræver **ingen indsats**. Den dokumenterer 110 par, hvor
hypernym-relationen var korrekt, men kilde-synsettets ordklasse var forkert:
flerordsudtryk mærket som substantiver med et verbum som hypernym, f.eks.
{gøre kål på} → {spise; æde} og {slå mønt} → {fremstille}.

107 af dem er rettet automatisk (ordklassen ændret til verbum, relationen
bevaret). Kolonnen *ok?* viser udfaldet. De tre med **nej** blev udeladt, fordi
de ikke er verbalfraser:

- {over kors} og {i pleje}: præpositionsled, der er blevet hængende fra
  hypernymets idiom
- {skåret (bygget, ..) over samme læst}: participial/adjektivisk

De to første ligner i øvrigt fejlagtige synsets i sig selv og bør ses efter
uafhængigt af dette arbejde.

## Hvad sker der bagefter?

Hver udfyldt række omsættes til en automatisk rettelse i næste
DanNet-udgivelse, på samme måde som de ca. 5.600 relationer, der allerede er
behandlet. Det er derfor vigtigt, at *decision* kun indeholder værdier fra
listen ovenfor, og at *retarget to* indeholder en gyldig URI (kopiér den evt.
fra browserens adresselinje på wordnet.dk).
