# Vejledning: Gennemgang af hypernymer på tværs af ordklasser

## Hvad handler det om?

I DanNet skal et synsets hypernym ("overbegreb") normalt have samme ordklasse
som synsettet selv: substantiver hører under substantiver, verber under verber
osv. En automatisk kontrol har fundet **283 synset-par**, hvor det ikke er
tilfældet — f.eks. substantivet {filtrering}, der peger på verbet {fjerne}
som sit hypernym, eller verbet {tilsvine}, der peger på substantivet
{talehandling}.

Disse relationer kan ikke rettes automatisk, for det rigtige svar afhænger af
et fagligt skøn: Skal relationen flyttes til et andet synset? Skal den
erstattes af en anden relationstype? Eller er der i virkeligheden tale om en
forkert ordklasse på selve ordet? Det er dét, regnearket skal hjælpe med at
afgøre.

## Sådan er regnearket bygget op

Hver **række** er ét problematisk par: et kilde-synset (*source*) og dets
nuværende hypernym (*target*). Rækkerne er **grupperet efter target** — alle
synsets, der peger på det samme hypernym, står samlet, adskilt af en tynd
streg. Grupperne står med de største først, og pointen er, at man ofte kan
træffe **én beslutning for hele gruppen** ad gangen (kolonnen *n* viser
gruppens størrelse).

Kolonnerne:

| kolonne | betydning |
|---|---|
| *target* / *target URI* / *target PoS* | Det nuværende hypernym, link til wordnet.dk og dets ordklasse. |
| *n* | Antal rækker i gruppen — dvs. hvor mange synsets der peger på dette target. |
| *source* / *source URI* / *source PoS* | Synsettet med den problematiske relation, link og ordklasse. |
| *retarget candidates* | Automatisk fundne alternativer: synsets med samme lemma som target, men med kildens ordklasse. Kun et forslag — skal altid efterses. |
| *decision* | **Udfyldes af dig** — vælg fra rullelisten (se nedenfor). |
| *retarget to* | **Udfyldes ved "retarget"**: indsæt URI'en på det nye hypernym. Er der præcis én oplagt kandidat, er feltet udfyldt på forhånd — ret det, hvis forslaget er forkert. |
| *comment* | Fri tekst til forbehold, tvivl eller begrundelse. |

De gule felter er dem, der skal udfyldes. Klik på linkene i URI-kolonnerne for
at se synsettets fulde opslag på wordnet.dk, inkl. definition og relationer.

## Beslutningsmulighederne (rullelisten i *decision*)

- **retarget** — relationen er reel nok, men skal pege på et andet synset med
  samme ordklasse som kilden. Skriv det nye synsets URI i *retarget to*.
  Eksempel: substantiver under adjektivet {sindssyg} flyttes til substantivet
  {sindslidende}.
- **classified_by** — target er et substantiveret adjektiv, der klassificerer
  kilden; relationen ændres til `wn:classified_by`.
- **attribute** — target er et egenskabssubstantiv, som kilden (et adjektiv)
  udtrykker en værdi af; relationen ændres til `wn:attribute`.
- **pos-fix** — relationen er korrekt, men kildens (eller targets) ordklasse
  er forkert angivet, typisk en verbalfrase mærket som substantiv. Ordklassen
  rettes, og relationen bevares. Skriv gerne i *comment*, hvilket ord det gælder.
- **delete** — relationen er forkert og har ingen brugbar erstatning på
  synset-niveau (typisk pertainym-/participium-agtige forbindelser).
  Bemærk: kilden mister som regel sit eneste hypernym, så brug kun denne,
  når hverken retarget eller en anden relation giver mening.
- **keep** — relationen skal bevares som den er (undtagelsen bekræftes).

Er du i tvivl, så lad *decision* stå tom og skriv i *comment* — tomme rækker
samles op i næste runde.

## Hvad sker der bagefter?

Regnearket læses maskinelt: hver udfyldt række omsættes til en automatisk
rettelse i næste DanNet-udgivelse, på samme måde som de ca. 5.600 lignende
relationer der allerede er blevet behandlet. Det er derfor vigtigt, at
*decision* kun indeholder værdier fra rullelisten, og at *retarget to*
indeholder en gyldig URI (kopiér den evt. fra browserens adresselinje på
wordnet.dk).

## Fane 2: "PoS-flip candidates (phrasal)"

Regnearkets anden fane indeholder 110 par af en anden slags: Her er selve
hypernym-relationen efter alt at dømme **korrekt**, men kilde-synsettets
ordklasse er formentlig forkert. Alle kilderne er flerordsudtryk mærket som
substantiver, der peger på et verbum som hypernym — f.eks. {gøre kål på} →
{spise; æde} og {drikke en skål} → {skåle}. Mønsteret tyder på verbalfraser,
der fejlagtigt er blevet mærket som substantiver.

Rettelsen (ordklassen ændres til verbum, relationen bevares) sker automatisk —
din opgave er kun at **bekræfte mønsteret** for hver række:

- **ja** i *ok?*-kolonnen: kilden er ganske rigtigt en verbalfrase; ordklassen
  rettes automatisk.
- **nej**: kilden er reelt et substantiv (eller andet er galt); parret ryger
  i stedet over til manuel behandling som på fane 1. Skriv gerne hvorfor i
  *comment*.

Det er altså en ren ja/nej-gennemgang — væsentligt hurtigere end fane 1 —
og de gule felter viser igen, hvad der skal udfyldes.
