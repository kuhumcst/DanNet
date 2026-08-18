---
name: clojure-style
description: Simon's Clojure/ClojureScript code style. Use whenever writing, editing, refactoring, or reviewing Clojure code — any change to .clj, .cljs, .cljc, or .edn files, code proposed in chat, or REPL experiments that will end up in a file. Consult it before cleanups and "tidying" passes too; those have their own rules here.
---

# Clojure Style

## Guiding principle

Perfection is attained not when there is nothing more to add, but when there is
nothing left to take away (Saint-Exupéry). When improving code, look for
something to remove before adding anything. Prefer the smallest diff that does
the job: surgical edits, clean and lean git history, no drive-by refactors of
the surrounding namespace.

## Compose small functions

Model the code on the Clojure standard library: many small, single-purpose
functions with an obvious data-in/data-out contract, combined through
composition rather than accumulated into large bodies.

- Build features by composing existing vars — threading (`->`, `->>`),
  `comp`, `partial`, seq functions — before writing new ones.
- If a function needs internal section comments or does two describable
  things, split it. A helper with a good name beats a comment.
- Prefer `map`/`filter`/`reduce`/`into` over explicit `loop`/`recur`; reach
  for `loop` only when the seq library genuinely doesn't fit.
- Keep the core pure; push side effects to the edges and mark them with `!`.
- Use a transducer (`comp` of xforms with `into`) when a pipeline is reused
  or performance-critical; otherwise plain threading reads better.

```clojure
;; prefer
(->> documents
     (filter valid?)
     (map normalize)
     (group-by :category))
```

## Name like clojure.core

Consistency with the standard library and with the surrounding namespace beats
novelty. A reader who knows clojure.core should be able to guess what a name
means.

- Conventional parameter names: `f`, `g`, `pred`, `coll`, `xs`, `x`, `s`,
  `m`, `k`, `v`, `n`, `acc`, `opts` — and project conventions like `conn`,
  `ident`. Match the parameter names already used by sibling functions.
- Systematic affixes: `->tokenizer-xf` (constructor), `node->hiccup`
  (transform, arrow shows direction), `persist-results!` (side effect),
  `valid?` (predicate), `-xf` (transducer).
- Simple verbs for transforms (`keywordize`), plural nouns for extraction
  (`attribute-objects`).
- Namespaces: reverse-domain (`dk.cst.project-name`), kebab-case, a real
  docstring on the `ns` form, requires grouped logically (core libraries
  first), standard aliases (`str`, `walk`, `io`, `json`).

## Docstrings

Every public var gets a docstring; it is part of the API.

- Structure: a 1-2 line summary first; any further description goes in a
  separate paragraph after a blank line. Never one long run-on paragraph.
- Mention every parameter in the first line, wrapped in backticks:
  "Convert `xml` (String, File, or InputStream) into Hiccup data."
- Present tense, active voice ("Convert", not "Converts").
- Multi-arity functions get one docstring on the name, not per arity.
- Include a short example for non-obvious usage.
- Keep docstring lines ≤ 80 characters; aim for the same in code.

## Parameters and arities

- Prefer multi-arity over options maps; the simple arity delegates to the
  fuller one. An options map only when there are genuinely many optional
  parameters.
- Destructure vector parameters directly: `(defn process-pair [[a b]] ...)`.
- Never remove an `:as` binding just because the body doesn't reference it —
  it documents what the parameter IS. Treat an unused `:as` as deliberate in
  every review or cleanup.
- Use `declare` for mutual recursion rather than reordering a namespace.

## Private vars

Nothing with standalone meaning is private.

- Only `defn`s may be private (`defn-`), and only pure single-caller helpers
  with no standalone meaning.
- `def` and `defonce` are never made private.
- Do not introduce new private vars outside that rule, and do not privatize
  existing vars during cleanups.

## Comments

Comments state intent and non-obvious mechanism — nothing else.

- Match the comment density of the surrounding code.
- Derivations, magic-number arithmetic, and iteration history belong in git
  history, not comments.
- Document complex regexes; mark known limitations with `TODO`.
- When in doubt, the comment is too long.

## Constants

No blanket no-magic-numbers rule. Name a literal only when two call sites
must agree or something silently breaks; a single-use literal stays inline,
next to its only use, where the reader can see it.

## REPL workflow

Development is REPL-driven; artifacts should reflect that.

- New `def`s/`defn`s go directly in the relevant namespace — never inside a
  comment block, and not in a scratch/user namespace.
- Example and exploratory calls go in a rich `(comment ...)` block in the
  relevant namespace, ending with `#_.` so parinfer leaves the closing paren
  runnable:

```clojure
(comment
  (parse sample-xml {:file-meta {:path :absolute}})
  ;; => [:root {} ...]
  #_.)
```

- Use `delay` for expensive dev data so loading the namespace stays cheap.
- Verify one specific aspect per evaluation; short focused evals over
  exhaustive test sweeps.

## Use sparingly

Reader conditionals (`#?`) only in genuinely cross-platform code; complex
destructuring only for tree/Hiccup shapes; metadata only when something
actually consumes it; dynamic vars only for infrastructure (connections,
servers). Most code should use the simpler alternative.

## No better-cond inside rum/defc bodies

Never use better-cond's special keywords (`:let`, `:when-let`, `:when`)
inside a `rum/defc` body. The CLJ expansion is correct, but rum/daiquiri's
hiccup compilation macroexpands the CLJS body in an environment that loses
the better-cond refer, so `cond` silently degrades to `clojure.core/cond`:
the keyword becomes a truthy test and the binding vector is rendered as the
branch. SSR looks fine while the browser renders garbage, so the bug is
invisible from the backend. Hoist the bindings into a plain `let` above the
`cond` instead. Plain `defn`s are unaffected (e.g. `transform-val*` uses
better-cond in CLJC without issue).
