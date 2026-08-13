# DMLex export of DanNet, COR and the sentiment data

Scope: the `feature/dmlex` branch, `git diff master...feature/dmlex` at `145c56d`, three commits. A fix to `->relations`, described under Loose ends, was applied after the first version of this document.

## The short version

DanNet gains a DMLex 1.0 export. One intermediate Clojure structure is built from the raw RDF graphs and serialized to both XML and JSON, merging DanNet entries, senses and relations with COR inflected forms and sentiment labels from Det Danske Sentimentleksikon. A second new namespace validates the output against the official OASIS schemas, which are vendored under `doc/dmlex/spec/`. The branch is pure addition: two source namespaces, a `deps.edn` alias for the validators, the vendored schemas, and two long documents holding the conversion rules and what the spec work uncovered. Nothing outside `db/export` calls the new code; like the WN-LMF export, it is driven from a comment block in its own namespace.

The commits tell the story in order: the plan and the vendored schemas first (`52f1378`), an MVP export of DanNet alone (`cd7f943`), then COR inflections and sentiment on top (`145c56d`).

## Reading order

The sections below follow the call flow: entry point, extraction, assembly, serialization, validation, then the data and documents. Within `dmlex.clj` that runs bottom-up, since the file puts the tag inventories and serializers at the top, extraction in the middle, and assembly plus the entry point at the bottom. Validation lives in `dmlex_validate.clj`, with its dependencies in `deps.edn`.

## Entry point: a comment block

There is no pipeline hook or CLI flag. The export runs from the REPL, `src/main/dk/cst/dannet/db/export/dmlex.clj:786-799`:

```clojure
(comment
  (def query-results
    (time (run-queries @dk.cst.dannet.web.instance/db)))

  (def resource
    (time (->resource query-results)))

  (count (:entries resource))
  (count (:labelTags resource))
  (count (:relations resource))
  (first (:entries resource))

  (time (export-dmlex! "export/dmlex/" resource))
  #_.)
```

Three calls make up the whole pipeline: `run-queries` fetches, `->resource` assembles, and `export-dmlex!` (`dmlex.clj:775-784`) writes `dannet-dmlex.xml` and `dannet-dmlex.json` into a directory.

## Extraction: run-queries

`run-queries` (`dmlex.clj:454-486`) runs a batch of small SPARQL queries and returns their results in one map. The important decision sits in the section banner, `dmlex.clj:246-251`:

```clojure
;; -----------------------------------------------------------------------------
;; Extraction
;;
;; Every query runs on the raw DanNet graph. The inference graph materialises
;; both directions of every inverse relation as well as the transitive closure
;; of e.g. wn:hypernym, none of which was stated by a lexicographer.
```

Three raw graphs are queried: DanNet for words, senses, definitions, examples, labels, genders and ILI links; COR for the `owl:sameAs` links and the inflected forms; DDS (and DanNet itself) for sentiment. Only descriptions and `owl:inverseOf` statements come from the full graph, since those are schema statements.

Relations get the most machinery. `exported-relations` (`dmlex.clj:313-339`) is a whitelist of nineteen relations in the direction the export keeps, with a docstring explaining the two policy exceptions (meronymy stays consistent rather than following the lexicographers) and one deliberate omission (`dns:subsumed`, whose 188 objects are senses that do not exist). `relation-pairs` (`dmlex.clj:385-398`) flips statements of the obverse relation into the kept direction using the `owl:inverseOf` map, normalises symmetric pairs, and drops pairs reaching outside DanNet via `dannet-pair?`, because a DMLex relation member must live in the same file (findings, section 2).

## Assembly: ->resource

`->resource` (`dmlex.clj:675-773`) indexes the query results and builds the intermediate structure, a plain map using DMLex property names as keys. Entries come from grouping senses by word, so a word without senses never becomes an entry, and `filterv :headword` drops words without a written form, since DMLex requires a headword. `homograph-numbers` (`dmlex.clj:499-508`) numbers only the words that collide on headword plus part of speech, which is the combination DMLex requires to be unique.

DMLex has no synset object (findings, section 1), and the branch works around it in two moves. First, every synset becomes a `labelTag` that senses point at, carrying the synset's identity outward via `sameAs`, `dmlex.clj:586-594`:

```clojure
(defn ->synset-label-tag
  "The labelTag that gives a `synset` its identity, with its `description` and
  its `ilis` as extra sameAs URIs."
  [synset description ilis]
  (cond-> {:tag     (name synset)
           :typeTag "synset"
           :for     "sense"
           :sameAs  (into [(str prefix/dn-uri (name synset))] ilis)}
    description (assoc :description description)))
```

The ILI URIs ride along only when unambiguous: `unambiguous-ilis` (`dmlex.clj:510-519`) keeps a link only when one synset claims one ILI and vice versa, since an ILI identifier is an identity claim and the contradictory rows stay out until DanNet corrects them. Second, synset membership becomes a `synonym` relation over the synset's senses, covered under relations below.

Each sense then collects its labels in one thread, `dmlex.clj:707-719`:

```clojure
        ->sense        (fn [{:syms [?sense ?synset]}]
                         (cond-> {:id     (name ?sense)
                                  :labels (-> [(name ?synset)]
                                              (cond->
                                                (ontotype-of ?synset)
                                                (conj (ontotype-of ?synset))

                                                (gender-of ?synset)
                                                (conj (name (gender-of ?synset))))
                                              (into (marks-of ?sense))
                                              (into (notes-of ?sense))
                                              (into (or (sentiment-of ?sense)
                                                        (sentiment-of ?synset))))}
                           ;; ...
```

The label vector starts with the synset tag, then the composite ontological type (rendered `{LanguageRepresentation; Artifact; Object}` by `ontological-type`, which reorders the `rdf:Bag` members by their `rdf:_N` index), gender, register/dating/frequency marks, usage notes, and sentiment, where a sense falls back to its synset's sentiment. Word-level sentiment becomes entry labels instead. `sentiment-labels` (`dmlex.clj:565-584`) applies the fault rules of plan section 14.2: conflicting polarities drop all sentiment labels, and a value that disagrees with its polarity keeps the polarity alone.

The COR inflections need a guard before they attach to an entry, `dmlex.clj:536-547`:

```clojure
(defn matching-cor-words
  "The COR words of `cor-word->lemmas` whose lemma is the `headword`. When the
  linked COR words hold different lemmas, a merge of their paradigms would put
  the forms of another lemma on the entry, so only the matching COR words keep
  their forms. When no lemma matches, all of them do: COR is the authority for
  the spelling, so a differing lemma alone is not a fault."
  [headword cor-word->lemmas]
  (let [lemmas   (into #{} (mapcat val) cor-word->lemmas)
        matching (filter (fn [[_ ls]] (ls headword)) cor-word->lemmas)]
    (if (and (> (count lemmas) 1) (seq matching))
      (map key matching)
      (keys cor-word->lemmas))))
```

The forms themselves are tagged with the inflection code, the last dot-segment of the COR form identifier, and the `inflectedFormTags` inventory gets human-readable descriptions parsed out of the COR `rdfs:label`s by `code-descriptions` (`dmlex.clj:549-563`), which discards a code whose labels disagree.

Relations close the assembly. `->relations` (`dmlex.clj:639-654`) first emits one `synonym` relation per synset of two or more senses, then walks the whitelisted pairs:

```clojure
    (for [[rel pairs] (sort-by (comp name key) relations)
          :let [roles (relation-roles rel (obverse-of rel))]
          pair (sort-by (partial mapv name) pairs)
          :when (every? (comp seq senses-of) pair)]
      {:type    (name rel)
       :members (->members senses-of pair roles)})))
```

`relation-roles` (`dmlex.clj:621-630`) names the two ends after the relation pair itself, one name for both ends of a symmetric relation, and `source`/`target` when no obverse is declared. `->relation-types` (`dmlex.clj:656-673`) declares each relation type with a `sameAs` link back into the RDF vocabulary.

## Serialization: one structure, two writers

The intermediate map feeds both serializers. `xml-str` (`dmlex.clj:202-207`) goes through `->lexicographic-resource` (`dmlex.clj:186-200`), whose child order deliberately follows the sequences of the XSD, then `xml/sexp-as-element` and `indent-str`. The JSON side needs two adjustments: `prune` (`dmlex.clj:209-221`) strips nil values and empty collections, and `json-safe` papers over a disagreement between the two official schemas, `dmlex.clj:223-236`:

```clojure
;; TODO: report the homographNumber mismatch to the LEXIDMA committee
;; dmlex_no-crosslingual.xsd types the attribute as xs:integer while
;; dmlex_no-crosslingual.schema.json types the property as a string, so no
;; single value serializes into both. Drop this coercion once they agree.

(defn json-safe
  "The two schemas disagree on one property: `homographNumber` is an integer in
  the XSD and a string in the JSON schema."
  [resource]
  (update resource :entries
          (fn [entries]
            (mapv #(cond-> %
                     (:homographNumber %) (update :homographNumber str))
                  entries))))
```

## Validation: dmlex_validate.clj and the :validate alias

Neither required validator ships with the JDK, so `deps.edn:58-60` adds a `:validate` alias with three deps, and the comment above it (`deps.edn:53-57`) explains the oddity: XSD 1.1 support never merged into the Xerces trunk, the only build on Maven Central is a repackaging of the `xml-schema-1.1-dev` branch, and its XPath 2.0 processor needs ICU4J without declaring it. JSON validation uses networknt's json-schema-validator for draft 2020-12. Findings section 3 ("About two validators in the world can read the XML schema") is the long version.

`validate-xml` (`dmlex_validate.clj:53-71`) collects SAX errors through an `error-collector` that throws once a limit is reached, then splits off errors the schema itself causes, `dmlex_validate.clj:42-47`:

```clojure
(def schema-defect-codes
  "Xerces error codes that a defect in the DMLex XSD causes, not our document.
  Every identity constraint of the schema keys on a mixed content element, e.g.
  entryUnique on headword, and XSD needs a simple type there. A field then
  gives cvc-id.3, or a null value that looks like a duplicate."
  #{"cvc-id.3" "cvc-identity-constraint.4.1"})
```

`validate-json` (`dmlex_validate.clj:73-86`) checks the JSON file, and `validate-dmlex!` (`dmlex_validate.clj:88-106`) prints both outcomes, reporting the schema-caused errors as an ignored count. Like the export, it runs from a comment block.

## Data and documents

Four vendored spec files sit under `doc/dmlex/spec/`, about 3,000 lines: the XSD and the JSON schema, each in a full and a `no-crosslingual` variant; validation targets the `no-crosslingual` pair. `doc/dmlex/plan.md` (796 lines) holds the decisions and conversion rules; the commit notes it came out of a back and forth with Claude Opus 5. `doc/dmlex/findings.md` (437 lines) is a critique of the spec written during implementation, ending in feedback for the LEXIDMA committee. `wn_lmf.clj` changes by four lines: a TODO comment (`wn_lmf.clj:93-95`) noting that `excluded-synsets` is a hand-maintained list that must be deleted manually once DanNet is fixed, unlike the condition-based exclusions around it.

## Loose ends

Nothing requires either new namespace, which matches the house convention: `export-wn-lmf!` also has no caller outside its own comment block, so the DMLex export is wired in exactly as much as the WN-LMF export is. There are no unit tests; schema validation is the test story, per plan section 9.5, so the fault rules in functions like `sentiment-labels` and `matching-cor-words` are enforced only by their own code.

One flaw turned up on rereading and has since been fixed. The guard in `->relations` was `:when (next members)`, two members in total, while the docstring promised that a pair with a senseless synset at one end produces nothing. The data hits the gap: three synsets, {Republikken Congo}, {Den Demokratiske Republik Congo} and {Nordkorea}, hold relation statements but no lexicalized senses, and their `wn:mero_member` links to {beboer_§1a; indbygger_§1} passed the guard on the strength of the object's two senses alone, emitting three relations with members in one role only. No schema validator can catch this, since the `min` of a `memberType` is declarative data. The guard is now `:when (every? (comp seq senses-of) pair)` (`dmlex.clj:652`), which enforces the docstring, and plan section 14.2 records the fault.

Three TODOs are left on purpose: report the `homographNumber` type mismatch to the LEXIDMA committee (`dmlex.clj:223`), reconsider the composite ontological type tag against one-tag-per-concept (`dmlex.clj:596-599`), and replace the `excluded-synsets` list in the WN-LMF export with a validation query (`wn_lmf.clj:93`).
