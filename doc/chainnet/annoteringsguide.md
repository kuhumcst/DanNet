# Guide til ChainNet-annotering

Rækkerne er samlet i grupper, en gruppe per ord. En tynd streg viser, hvor
gruppen slutter. En af rækkerne er grundbetydningen. Den har "-" i kolonnen
"derived from". De andre rækker er betydninger, som bygger på
grundbetydningen.

Farverne betyder:

- Grøn, gul eller rød i kolonnen "lemma": grønne grupper er klar til
  annotering. Gule og røde mangler data.
- Lyseblå række: rækken er ikke annoteret endnu. Farven forsvinder, når du
  udfylder "qualia role".
- Turkise felter: her mangler der noget.

Alle ID'er i arket er webadresser. Klik på et ID, så åbner siden på
wordnet.dk. Der kan du se betydningen og klikke dig videre rundt i DanNet, fx
til synonymer og overbegreber. Det giver god kontekst, før du annoterer.

Tag kolonnerne fra venstre mod højre:

1. **task**: Læs denne først. Den nævner særlige problemer. Fx betyder
   "assign roles", at du selv skal afgøre, hvilken række der er
   grundbetydningen.
2. **sense ID**: Klik på linket og tjek betydningen. Læs også "description"
   og "example" længere ude i rækken. Står der "unknown", findes betydningen
   ikke i DanNet endnu.
3. **derived from**: Betydningen, som denne betydning bygger på. Feltet er
   som regel udfyldt. Er det tomt, så indsæt grundbetydningens sense ID.
4. **qualia role**: Her beskriver du, hvad den nye betydning bygger på i
   grundbetydningen. Vælg den rolle, der passer bedst:
   - FORMAL: den bygger på, hvad tingen er, eller hvad den ligner. En
     "bølgedal" (dårlig periode) bygger fx på, at en bølgedal er en
     fordybning.
   - CONSTITUTIVE: den bygger på tingens dele, eller hvad den består af.
   - TELIC: den bygger på, hvad tingen bruges til. En "nøgle" (løsning)
     bygger fx på, at en nøgle bruges til at åbne med.
   - AGENTIVE: den bygger på, hvordan tingen opstår eller bliver lavet.
5. **relation**: Her gør du svaret fra qualia role konkret. Vælg den
   relation, der binder de to betydninger sammen, fx wn:hypernym (er en
   slags) eller wn:mero_part (er en del af).
6. **relation to**: Skriv det ord, som relationen peger på, fx "fordybning".
7. **relation to synset ID**: Find ordets synset på wordnet.dk og kopier
   adressen fra browseren ind i feltet.
8. **annotator**: Skriv dine initialer.
9. **comment**: Skriv en kommentar, hvis noget er svært eller ser forkert ud.

Kan du ikke annotere rækken, så skriv en streg i "annotator" og forklar
hvorfor i "comment". Så kan vi se, at du har kigget på rækken.

Kolonnerne "lemma", "id", "description", "example" og "in DAMETA selection"
skal du ikke ændre.

Arbejdet bygger på ChainNet, som er beskrevet i denne artikel:
Hall Maudslay, Teufel, Bond & Pustejovsky (2024): "ChainNet: Structured
Metaphor and Metonymy in WordNet".
<https://aclanthology.org/2024.lrec-main.266/>

Qualia-rollerne er beskrevet her:
Pustejovsky & Jezek (2016): "A Guide to Generative Lexicon Theory", kapitlet
om qualia-struktur.
<https://gl-tutorials.org/wp-content/uploads/2015/12/GL-QualiaStructure.pdf>
