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

Gennemgangen ligger i tre regneark i `doc/crosspos/`. De erstatter det
regneark, vi tidligere lavede i hånden. Åbn filerne i Excel eller
LibreOffice. To af dem skal du udfylde.

| fil | par | din opgave |
|---|---|---|
| `2d-cross-pos-taxonomy.xlsx` | 285 | udfyld de gule kolonner |
| `a4-deferred-crosspos.xlsx` | 104 | udfyld de gule kolonner |
| `2a-verb-phrase-pos-flip.xlsx` | 110 | ingen: filen dokumenterer en beslutning, vi allerede har truffet |

De fire kolonner, du skal udfylde, står ved siden af hinanden og har gul
baggrund: *nyt hypernym*, *ny relation*, *status* og *kommentar*. *status* og
*ny relation* har rullemenuer. Navnene i *synset* og *nuværende hypernym* er
klikbare links til wordnet.dk. De to URI-kolonner yderst til højre bruger
scriptet til at genkende rækkerne; dem skal du ikke røre.

Et script gendanner filerne fra databasen. Scriptet bevarer alt, du har
skrevet i de gule kolonner, så dit arbejde ikke går tabt.

## `2d-cross-pos-taxonomy.xlsx`

Hver **række** er ét par: et synset og det hypernym, det peger på i dag. Filen
grupperer rækkerne efter hypernym. Alle synsets, der peger på det samme
hypernym, står derfor samlet. De største grupper står først. Du kan ofte træffe
**én beslutning for en hel gruppe**. Kolonnen *antal i gruppen* viser gruppens
størrelse.

| kolonne | betydning |
|---|---|
| *synset* | Synsettet med den problematiske relation. Klik på navnet for at se opslaget på wordnet.dk. |
| *ordklasser* | Uoverensstemmelsen, for eksempel `vb. → sb.`: synsettets ordklasse til venstre, hypernymets til højre. |
| *nuværende hypernym* | Det hypernym, relationen peger på i dag. Klikbart. |
| *antal i gruppen* | Hvor mange synsets der peger på det samme hypernym. Filen er sorteret efter dette tal, så de store grupper står først. |
| *forslag* | Alternativer fundet af scriptet: synsets med samme lemma som hypernymet, men med synsettets ordklasse. Efterse altid forslaget. |
| *nyt hypernym* | Indsæt URI'en på det nye hypernym, hvis relationen skal pege et andet sted hen. Er der præcis én oplagt kandidat, står den der på forhånd. Er forslaget forkert, så ret det. |
| *ny relation* | Vælg en relation i rullemenuen, hvis relationen skal skifte type. Lad den stå tom, hvis den forbliver `wn:hypernym`. |
| *status* | Vælg en værdi i rullemenuen. Se listen nedenfor. |
| *kommentar* | Fri tekst til forbehold, tvivl eller begrundelse. |

Klik på navnet i *synset* eller *nuværende hypernym*. Så ser du synsettets
fulde opslag på wordnet.dk med definition og relationer.

### Sådan retter du en række

Der er to slags rettelser, og en række kan have brug for begge:

- **Skal relationen pege et andet sted hen?** Indsæt URI'en på det nye synset i
  *nyt hypernym*. Eksempel: substantiver under adjektivet {sindssyg} flytter
  til substantivet {sindslidende}.
- **Skal relationen skifte type?** Vælg relationen i *ny relation*. Vælg
  `wn:attribute`, når hypernymet er et egenskabssubstantiv og synsettet er et
  adjektiv, der udtrykker en værdi af det. Det er samme behandling som de
  omkring 5.400 tilfælde, vi allerede har rettet. Vælg `wn:exemplifies`, når
  synsettet er et eksempel på en brugsmarkering, for eksempel {helvedes} under
  {bandeord}.

Du behøver ikke skrive noget i *status*, når du har udfyldt en af de to
kolonner. At du har skrevet noget dér, betyder i sig selv, at rækken skal
rettes.

### Værdier i *status*

Brug *status* til de rækker, hvor der ikke skal rettes noget:

- **ordklassefejl**: relationen er korrekt, men ordklassen på synsettet eller på
  hypernymet er forkert. Det er typisk en verbalfrase mærket som substantiv.
  Ordklassen rettes, og relationen består. Skriv i *kommentar*, hvilket ord det
  gælder.
- **slettes**: relationen er forkert, og der findes ingen brugbar erstatning.
  Det er typisk pertainym-agtige eller participium-agtige forbindelser.
  Synsettet mister som regel sit eneste hypernym. Brug kun **slettes**, når
  hverken et nyt hypernym eller en anden relationstype giver mening.
- **beholdes**: relationen består uændret. Undtagelsen er dermed bekræftet.
- **i tvivl**: du har set på rækken, men kan ikke afgøre den. Skriv hvorfor i
  *kommentar*.

En tom *status* betyder, at ingen har set på rækken endnu. Både tomme rækker og
**i tvivl** kommer med i næste runde.

> **Bemærk:** `classified_by` var tidligere en mulighed. Vi har trukket den
> tilbage. I GWA-standarden betegner relationen *numeralklassifikatorer*, for
> eksempel 'head' i 'head of cattle'. Den dækker ikke substantiverede
> adjektiver og passer derfor ikke på vores tilfælde.

## `a4-deferred-crosspos.xlsx`

Filen indeholder 104 par. Et script omsatte dem tidligere til
`wn:classified_by`. Den relation viste sig at være forkert, og parrene afventer
nu en beslutning. De står stadig som `dns:crossPoSHypernym` i datasættet.

Kolonnen *gruppe* deler parrene i fire mønstre. Hvert mønster kræver sin egen
behandling, og kolonnen *forslag* viser den på forhånd.

| gruppe | par | eksempel | forslag |
|---|---|---|---|
| sprog | 62 | {koreansk} → {sprog} | ordklassefejl (substantiveret adjektiv) |
| brugsmarkering | 26 | {helvedes} → {bandeord} | ny relation: `wn:exemplifies` |
| person | 13 | {hjemløs} → {person} | ordklassefejl |
| fast udtryk | 3 | {en lille sort} → {kaffe} | ordklassefejl |

Kolonnerne er de samme som ovenfor. *forslag* er scriptets bud, ikke en
afgørelse: du skal stadig selv skrive i *status* eller *ny relation*.

For de 26 rækker i **brugsmarkering** er `wn:exemplifies` allerede sat i *ny
relation*. Bekræft den, eller ret den.

**ordklassefejl** betyder her, at ordet har en veletableret substantivisk brug
på dansk. Den påstand skal du bekræfte i DDO for hvert enkelt ord. Derfor
rettede vi ikke parrene automatisk, og derfor står *status* tom.

## `2a-verb-phrase-pos-flip.xlsx`

Denne fil kræver **ingen indsats**. Den dokumenterer 110 par, hvor
hypernym-relationen var korrekt, men synsettets ordklasse var forkert.
Det var flerordsudtryk mærket som substantiver med et verbum som hypernym, for
eksempel {gøre kål på} → {spise; æde} og {slå mønt} → {fremstille}.

Et script rettede 107 af parrene. Det ændrede ordklassen til verbum og bevarede
relationen. Kolonnen *rettet?* viser udfaldet. Tre par har værdien **nej**,
fordi de ikke er verbalfraser:

- {over kors} og {i pleje}: præpositionsled, der hænger ved fra hypernymets
  idiom
- {skåret (bygget, ..) over samme læst}: participial eller adjektivisk

De to første ligner selv fejlagtige synsets. De skal ses efter uafhængigt af
dette arbejde.

## Hvad sker der bagefter?

Hver udfyldt række bliver til en automatisk rettelse i næste DanNet-udgivelse.
Det er samme fremgangsmåde som for de omkring 5.600 relationer, vi allerede har
behandlet. *status* og *ny relation* må derfor kun indeholde værdier fra
rullemenuerne. *nyt hypernym* skal indeholde en gyldig URI. Du kan kopiere
URI'en fra browserens adresselinje på wordnet.dk.
