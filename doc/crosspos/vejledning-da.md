# Vejledning: hypernymer på tværs af ordklasser

## Hvad handler det om?

Et synsets hypernym (overbegreb) skal normalt have samme ordklasse som
synsettet selv. Substantiver hører under substantiver, og verber hører under
verber. Parrene i denne gennemgang kommer fra to forskellige kilder:

- En automatisk kontrol fandt almindelige hypernym-relationer, hvor
  ordklasserne ikke passer sammen. **285 par** kræver et fagligt skøn.
  Substantivet {filtrering} peger for eksempel på verbet {fjerne} som sit
  hypernym. Verbet {tilsvine} peger på substantivet {talehandling}.
- DanNet brugte desuden en særlig relation til hypernymer på tværs af
  ordklasser: `dns:crossPoSHypernym`. Den havde 5.636 par. Et script har
  rettet eller slettet de fleste. **104 par** kræver også et fagligt skøn.

De to grupper overlapper ikke. Du skal derfor vurdere **389 par** i alt.
Hver gruppe har sit eget regneark.

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
klikbare links til wordnet.dk. Scriptet bruger de to URI-kolonner yderst til
højre til at genkende rækkerne. Dem skal du ikke røre.

Et script gendanner filerne fra databasen. Scriptet bevarer alt, du har
skrevet i de gule kolonner, så dit arbejde ikke går tabt.

## `2d-cross-pos-taxonomy.xlsx`

Filen indeholder de 285 par fra den automatiske kontrol.

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
| *forslag* | Alternativer, scriptet har fundet. Det er synsets med samme lemma som hypernymet, men med synsettets ordklasse. Det kan også være substantiver, der er afledt af hypernymets verbum, for eksempel {fjernelse} af {fjerne}. Efterse altid forslaget. |
| *nyt hypernym* | Indsæt URI'en på det nye hypernym, hvis relationen skal pege et andet sted hen. Er der præcis én oplagt kandidat, står den der på forhånd. Er forslaget forkert, så ret det. |
| *ny relation* | Vælg en relation i rullemenuen, hvis relationen skal skifte type. Lad den stå tom, hvis den forbliver `wn:hypernym`. |
| *status* | Vælg en værdi i rullemenuen. Se listen nedenfor. |
| *kommentar* | Fri tekst til forbehold, tvivl eller begrundelse. Scriptets egne udfyldninger har en kommentar, der begynder med "Automatisk forslag". Du kan overskrive den. |

### Sådan retter du en række

Der er to slags rettelser, og en række kan have brug for begge:

- **Hvis relationen skal pege et andet sted hen:** indsæt URI'en på det nye
  synset i *nyt hypernym*. Eksempel: substantiver under adjektivet {sindssyg}
  flytter til substantivet {sindslidende}.
- **Hvis relationen skal skifte type:** vælg relationen i *ny relation*. De to
  hyppigste er:
  - `wn:attribute`, når hypernymet er et egenskabssubstantiv og synsettet er et
    adjektiv, der udtrykker en værdi af det. Det er samme behandling som de
    omkring 5.400 tilfælde, vi allerede har rettet.
  - `wn:exemplifies`, når synsettet er et eksempel på en brugsmarkering, for
    eksempel {helvedes} under {bandeord}.

Lad *status* stå tom, når du har udfyldt en af de to kolonner. En udfyldt
kolonne betyder i sig selv, at rækken skal rettes.

### Værdier i *status*

Brug *status* til de rækker, hvor du ikke retter noget:

- **ordklassefejl**: relationen er korrekt, men ordklassen på synsettet eller på
  hypernymet er forkert. Det er typisk en verbalfrase mærket som substantiv.
  Vi retter ordklassen og beholder relationen. Skriv i *kommentar*, hvilket ord
  det gælder.
- **slettes**: relationen er forkert, og der findes ingen brugbar erstatning.
  Det er typisk pertainym-agtige eller participium-agtige forbindelser.
  Synsettet mister som regel sit eneste hypernym. Brug kun **slettes**, når
  hverken et nyt hypernym eller en anden relationstype giver mening.
- **beholdes**: relationen består uændret. Du bekræfter dermed undtagelsen.
- **i tvivl**: du har set på rækken, men kan ikke afgøre den. Skriv hvorfor i
  *kommentar*.

En tom *status* betyder, at ingen har set på rækken endnu. Både tomme rækker og
**i tvivl** kommer med i næste runde.

> **Bemærk:** `classified_by` var tidligere en mulighed. Vi har trukket den
> tilbage. I GWA-standarden betegner relationen *numeralklassifikatorer*, for
> eksempel 'head' i 'head of cattle'. Den dækker ikke substantiverede
> adjektiver og passer derfor ikke på vores tilfælde.

## `a4-deferred-crosspos.xlsx`

Filen indeholder de 104 par, der er tilbage fra den særlige relation
`dns:crossPoSHypernym`. Et script omsatte dem tidligere til
`wn:classified_by`. Den relation viste sig at være forkert, og parrene afventer
nu en beslutning. De står stadig som `dns:crossPoSHypernym` i datasættet.

Kolonnen *gruppe* deler parrene i fire mønstre. Hvert mønster kræver sin egen
behandling, og kolonnen *forslag* viser behandlingen på forhånd.

| gruppe | par | eksempel | forslag |
|---|---|---|---|
| sprog | 62 | {koreansk} → {sprog} | ordklassefejl (substantiveret adjektiv) |
| brugsmarkering | 26 | {helvedes} → {bandeord} | ny relation: `wn:exemplifies` |
| person | 13 | {hjemløs} → {person} | ordklassefejl |
| fast udtryk | 3 | {en lille sort} → {kaffe} | ordklassefejl |

Kolonnerne er de samme som ovenfor. *forslag* er scriptets bud, ikke en
afgørelse. Du skal stadig selv skrive i *status* eller *ny relation*.

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

De to første ligner selv fejlagtige synsets. Vi ser dem efter i et andet
forløb.

## Hvad sker der bagefter?

Hver udfyldt række bliver til en automatisk rettelse i næste DanNet-udgivelse.
Det er samme fremgangsmåde som for de omkring 5.600 relationer, vi allerede har
behandlet. Skriv derfor kun værdier fra rullemenuerne i *status* og *ny
relation*. Skriv en gyldig URI i *nyt hypernym*. Du kan kopiere URI'en fra
browserens adresselinje på wordnet.dk.
