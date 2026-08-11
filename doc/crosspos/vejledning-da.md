# Vejledning: hypernymer på tværs af ordklasser

## Hvad handler det om?

Et synsets hypernym (overbegreb) skal normalt have samme ordklasse som
synsettet selv. Substantiver hører under substantiver, og verber hører under
verber. En automatisk kontrol fandt **285 synset-par**, hvor ordklasserne ikke
passer sammen. Substantivet {filtrering} peger for eksempel på verbet {fjerne}
som sit hypernym. Verbet {tilsvine} peger på substantivet {talehandling}.

Vi kan ikke rette disse par automatisk. Det rigtige svar kræver et fagligt
skøn. Skal relationen pege på et andet synset? Skal den skifte til en anden
relationstype? Eller er ordklassen på selve ordet forkert?

## Filerne

Gennemgangen ligger i tre CSV-filer i `doc/crosspos/`. De erstatter det
tidligere regneark. Du kan åbne filerne i Excel eller LibreOffice. To af dem
skal du udfylde.

| fil | par | din opgave |
|---|---|---|
| `2d-cross-pos-taxonomy.csv` | 285 | udfyld *decision* og *retarget to* |
| `a4-deferred-crosspos.csv` | 104 | udfyld *decision* |
| `2a-verb-phrase-pos-flip.csv` | 110 | ingen: filen dokumenterer en beslutning, vi allerede har truffet |

Et script gendanner filerne fra databasen. Scriptet bevarer kolonnerne
*decision*, *retarget to* og *comment*, så dit arbejde ikke går tabt.

## `2d-cross-pos-taxonomy.csv`

Hver **række** er ét par: et kilde-synset (*source*) og dets nuværende hypernym
(*target*). Filen grupperer rækkerne efter *target*. Alle synsets, der peger på
det samme hypernym, står derfor samlet. De største grupper står først. Du kan
ofte træffe **én beslutning for en hel gruppe**. Kolonnen *n* viser gruppens
størrelse.

| kolonne | betydning |
|---|---|
| *target* / *target URI* / *target PoS* | Det nuværende hypernym, dets link til wordnet.dk og dets ordklasse. |
| *n* | Antal rækker i gruppen. Tallet viser, hvor mange synsets der peger på dette target. |
| *source* / *source URI* / *source PoS* | Synsettet med den problematiske relation, dets link og dets ordklasse. |
| *retarget candidates* | Alternativer fundet af scriptet: synsets med samme lemma som target, men med kildens ordklasse. Efterse altid forslaget. |
| *decision* | **Du udfylder den.** Se listen nedenfor. |
| *retarget to* | **Du udfylder den ved *retarget*.** Indsæt URI'en på det nye hypernym. Er der præcis én oplagt kandidat, står den der på forhånd. Er forslaget forkert, så ret det. |
| *comment* | Fri tekst til forbehold, tvivl eller begrundelse. |

Klik på linkene i URI-kolonnerne. Så ser du synsettets fulde opslag på
wordnet.dk med definition og relationer.

### Værdier i *decision*

- **retarget**: relationen er reel, men den skal pege på et andet synset med
  samme ordklasse som kilden. Skriv det nye synsets URI i *retarget to*.
  Eksempel: substantiver under adjektivet {sindssyg} flytter til substantivet
  {sindslidende}.
- **attribute**: target er et egenskabssubstantiv, og kilden er et adjektiv,
  der udtrykker en værdi af det. Relationen skifter til `wn:attribute`. Det er
  samme behandling som de omkring 5.400 tilfælde, vi allerede har rettet.
- **pos-fix**: relationen er korrekt, men ordklassen på kilden eller på target
  er forkert. Det er typisk en verbalfrase mærket som substantiv. Ordklassen
  rettes, og relationen består. Skriv i *comment*, hvilket ord det gælder.
- **exemplifies**: kilden er ikke en type af target. Kilden er et *eksempel på
  en brugsmarkering*, for eksempel {helvedes} under {bandeord}. Relationen
  skifter til `wn:exemplifies`.
- **delete**: relationen er forkert, og der findes ingen brugbar erstatning på
  synset-niveau. Det er typisk pertainym-agtige eller participium-agtige
  forbindelser. Kilden mister som regel sit eneste hypernym. Brug kun *delete*,
  når hverken *retarget* eller en anden relationstype giver mening.
- **keep**: relationen består uændret. Undtagelsen er dermed bekræftet.

Er du i tvivl, så lad *decision* stå tom og skriv i *comment*. Tomme rækker
kommer med i næste runde.

> **Bemærk:** `classified_by` var tidligere en mulighed. Vi har trukket den
> tilbage. I GWA-standarden betegner relationen *numeralklassifikatorer*, for
> eksempel 'head' i 'head of cattle'. Den dækker ikke substantiverede
> adjektiver og passer derfor ikke på vores tilfælde.

## `a4-deferred-crosspos.csv`

Filen indeholder 104 par. Et script omsatte dem tidligere til
`wn:classified_by`. Den relation viste sig at være forkert, og parrene afventer
nu en beslutning. De står stadig som `dns:crossPoSHypernym` i datasættet.

Kolonnen *group* deler parrene i fire mønstre. Hvert mønster kræver sin egen
behandling.

| group | par | eksempel | forslag til behandling |
|---|---|---|---|
| language | 62 | {koreansk} → {sprog} | pos-fix (substantiveret adjektiv) |
| register | 26 | {helvedes} → {bandeord} | exemplifies |
| person | 13 | {hjemløs} → {person} | pos-fix |
| nominal idiom | 3 | {en lille sort} → {kaffe} | pos-fix |

Brug samme værdier i *decision* som ovenfor. **pos-fix** betyder her, at ordet
har en veletableret substantivisk brug på dansk. Den påstand skal du bekræfte i
DDO for hvert enkelt ord. Derfor rettede vi ikke parrene automatisk.

## `2a-verb-phrase-pos-flip.csv`

Denne fil kræver **ingen indsats**. Den dokumenterer 110 par, hvor
hypernym-relationen var korrekt, men kilde-synsettets ordklasse var forkert.
Det var flerordsudtryk mærket som substantiver med et verbum som hypernym, for
eksempel {gøre kål på} → {spise; æde} og {slå mønt} → {fremstille}.

Et script rettede 107 af parrene. Det ændrede ordklassen til verbum og bevarede
relationen. Kolonnen *ok?* viser udfaldet. Tre par har værdien **nej**, fordi
de ikke er verbalfraser:

- {over kors} og {i pleje}: præpositionsled, der hænger ved fra hypernymets
  idiom
- {skåret (bygget, ..) over samme læst}: participial eller adjektivisk

De to første ligner selv fejlagtige synsets. De skal ses efter uafhængigt af
dette arbejde.

## Hvad sker der bagefter?

Hver udfyldt række bliver til en automatisk rettelse i næste DanNet-udgivelse.
Det er samme fremgangsmåde som for de omkring 5.600 relationer, vi allerede har
behandlet. *decision* må derfor kun indeholde værdier fra listen ovenfor.
*retarget to* skal indeholde en gyldig URI. Du kan kopiere URI'en fra browserens
adresselinje på wordnet.dk.
