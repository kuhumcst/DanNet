Refactoring the DanNet UI layer
===============================

Research done on 27 October 2026, at commit 16a61c3.

This is a plan for an agent to follow. It ranks changes that make the UI code
easier to read and its data flow easier to follow. The HTTP API stays as it
is: the same URLs, query parameters, media types, and the same JSON, JSON-LD,
Turtle and EDN output. Only the Transit format may change, since only the SPA
reads it.

It builds on [library-report.md](library-report.md) (13 October 2026), which
covers the move from the Rum fork to Replicant. This plan doesn't depend on
the renderer. Every step works with Rum, and most of them make a later
switch smaller.


Summary
-------

- **The main problem is that a page has no single shape.** Its data travels
  in the response body, four custom headers, cookies and the client session,
  and `page-shell` merges all of it into one `opts` map that every component
  gets. You can't tell what a component needs from its arguments.
- **First, make each page one value and embed it in the HTML** (step 1). This
  removes the second request on every full page load and the header plumbing,
  and the client then hydrates from the data the server rendered.
- **Then name things by id** (step 2), **split the navigation callback**
  (step 3) and **split the entity interceptor** (step 4). After these, the
  request-to-render flow can be read top to bottom in two places.
- **The largest single gain is narrowing `opts`** (step 8). It comes later
  because steps 1 to 7 remove many of its keys first.
- **Don't change the renderer during this work.** Replicant hydration is
  still unreleased. Step 11 makes the switch mostly mechanical when it comes.
- **Build a safety net before step 1.** The UI has no tests. HTML and
  Transit snapshots of a fixed list of URLs, plus a hydration check in the
  browser, catch nearly every regression these steps can cause.


How the UI works today
----------------------

A full page load:

```
GET /dannet/data/synset-52 (Accept: text/html)
  -> entity-negotiation-ic              resources.clj:267
  -> ->entity-ic: :content, :page-meta  resources.clj:287-457
  -> redirect-ic                        resources.clj:74-134
  -> response-body-ic                   resources.clj:137-170
       -> html-page + ui/page-shell     response.clj:37-73, 141-146
          (content + cookies + the JVM copy of app/session)
browser loads main.js
  -> router/init! -> reitit start       router.cljs:252-259
  -> on-navigate for the current URL    router.cljs:131-250
       -> fetch the same URL as Transit (?transit=true)
       -> read X-Page, X-Title, X-Has-deferred headers
       -> ui/page-shell -> rum/hydrate
       -> if X-Has-deferred: fetch ?deferred=true, merge, re-mount
```

A link click or form submit after that goes through the same `on-navigate`
and ends in `rum/mount` instead of `rum/hydrate`. Forms are turned into URLs
by `form/submit-form` and navigated to.

State lives in many places:

| State | Where | Written by |
|---|---|---|
| Preferences (languages, detail level, full-screen) | `app/session` | cookies at start-up (`app.cljc:15-30`), selects in `ui.cljc`, diagram buttons |
| Search input and completions | `app/session` `:search` | `search/update-suggestions` |
| Section display and diagram mode | `app/session` `:section`, keyed by a set of LangStr | `entity/display-mode-selector`, `viz/synset-diagram`, `d3.cljs:1272` |
| In-flight fetch controllers | `app/session` `:fetch` | `app/fetch!` |
| React root, current page, scroll history | `root`, `current-page`, `visited` | `router.cljs` |
| "Keep the scroll position" flag | `app/post-navigate` | `d3.cljs:837`, `1273` |
| Hydration done | `ui/*hydrated*` | `hydration-mixin` |
| Local UI state | 6 `rum/local` | search box, details, table display options, legend, sunburst trail, word cloud |
| DOM-held state | SVG classes, a key on the sunburst element, the CodeMirror view atom | `d3.cljs`, `visualization.cljc`, `sparql.cljc:30` |


Where the complexity comes from
-------------------------------

1. **A page has no single shape.** The body, four custom headers (`X-Page`,
   `X-Title`, `X-Has-deferred`, `X-Redirect` with `X-Replace`), the cookies
   merged in by `with-cookies` (`response.clj:233-238`) and the session
   together describe a page. The router reads them back one by one
   (`router.cljs:31-41`, `156-170`).
2. **One `opts` map carries everything.** `page-shell` merges backend data,
   cookies, the session and derived values (`ui.cljc:199-241`). Components
   then add about 15 more keys on the way down (`:attr-key`,
   `:table-component`, `:display-opt`, `:link-href`, `:link-title`, `:class`,
   `:badge`, `:independent-prefix`, `:subject-label`, `:cloud-limit`,
   `:sunburst-nav`, `:sunburst-caption`, `:folded`, `:inherited`, and
   `:k->label` overrides). Derived values are computed in three places: the
   entity interceptor, `page-shell` and `page/entity` (`page.cljc:22-39`, with
   a TODO asking to move it to the backend).
3. **Display text doubles as identity.** Section titles are sets of LangStr
   (`section.cljc:41-65`). The same sets are keys in the session
   (`app.cljc:39-41`, `47-53`), dispatch values (`entity.cljc:300`) and React
   keys (`entity.cljc:315`). Page types are strings in a header, looked up in
   a map in `page-shell` (`ui.cljc:189-198`, whose TODO says the same) and
   again in a `cond` for the nav colour (`ui.cljc:249-257`).
4. **One callback does all the navigation.** `on-navigate` is 120 lines
   (`router.cljs:131-250`). It aborts fetches, fetches, checks staleness twice,
   handles two kinds of redirect, sets the title, scrolls, moves focus,
   mounts, scrolls to the fragment, fetches and merges deferred data, and
   shows the error page. Navigation state is spread over four atoms in two
   namespaces, and the fetch controllers sit in the user's session.
5. **The entity interceptor does too much.** `->entity-ic`
   (`resources.clj:287-457`) decodes the subject, looks it up, prunes and
   truncates it, builds three sunburst trees, trims the payload, sets the page
   meta and handles redirects, all in one `let`. `resources.clj` changed in
   69 commits since October 2025, more than any other web namespace.
6. **Predicate special cases are scattered.** How a value renders depends on
   `:attr-key` tests in `rdf.cljc:39`, `75-85`, `171-172`, `201-206`,
   `276-290`, `298-313` and `339-394`, plus `table.cljc:22-28` and
   `visualization.cljc:269-280`.
7. **Error handling wraps pure functions in components.** `try-render` and
   `try-render-with` put an error-boundary component around their child on
   CLJS (`error.cljc:118-160`), even when the child is plain hiccup. That
   includes `transform-val` (`rdf.cljc:228-238`), which runs for almost every
   value, and every list item (`rdf.cljc:412-415`).
8. **The D3 code reaches back into the app.** `d3.cljs` requires `web.app`
   to navigate, toggle full-screen and write the session (`d3.cljs:8`, `197`,
   `837-838`, `948`, `1268-1274`). Its three diagrams use three different ways
   to decide when to rebuild.
9. **Hydration workarounds pile up.** The `*hydrated*` flag
   (`ui.cljc:13-24`, `276-280`), the native change listener in
   `detail-level-select` (`ui.cljc:140-150`), the `%2F` replace
   (`resources.clj:331-336`), and the server reading the client's session
   atom (`ui.cljc:201-205`).
10. **Text is written inline, sometimes twice.** There are 122
    `i18n/da-en` calls in `web/`, 104 of them in the components. The footer (`ui.cljc:26-92`), the error and not-found
    pages (`page.cljc:160-211`) and the display-options select
    (`table.cljc:135-161`) are each written out once per language.
11. **There are no UI tests.** All 930 test lines cover the database side.

Found along the way: markdown pages have an empty `<title></title>`. Their
page meta has no `:title` (`resources.clj:796-807`), and the client sets
`document.title` from inside a render function instead (`page.cljc:74-75`).
Checked on https://wordnet.dk/dannet/page/frontpage on 27 October 2026.
Step 1 fixes it.


Before you start
----------------

### The safety net

Do this first. Every step below is checked against it.

1. **Pick a fixed list of URLs** and write it down in the harness. It should
   cover: a large synset with deferred relations (e.g. `dn:synset-52`), a
   synset with sentiment, one with inherited relations, an OEWN synset, a
   DanNet word, a COR word, a sense, a FrameNet frame and frame element, an
   ontological type, a polysemy pattern, a schema page, `/dannet/metadata`,
   the relations page, a search with several results, the SPARQL editor with
   and without results, a markdown page, an unknown entity and an error.
2. **Snapshot each URL** as `text/html` and as `application/transit+json`,
   with `?lang=da` and `?lang=en`, from the running service on port 3456.
   Strip the cache-busting `?hash=` values and write the files to a scratch
   directory. A `comment` block or a small dev namespace is enough. After
   each commit, take new snapshots and diff them. Explain any expected
   difference in the commit message.
3. **Check hydration in the browser** with the `browser-repl-debug` skill.
   Open each URL, check the console for hydration errors, follow a few links,
   and use back and forward.

### Ground rules

- Ask Simon which branch the work goes on before the first commit.
- One recommendation per commit series, in small commits. Simon prefers lean
  diffs over large rewrites of a namespace.
- Work in the REPL. Reload changed namespaces with `(require '... :reload)`.
  The nREPL on port 7888 loads the main checkout, so in a worktree, use
  `load-file` with an absolute path instead.
- Follow the `clojure-style` skill for code and the docstring layout it
  describes.
- If something unusual happens (odd output, unexpected errors), stop and
  report it to Simon instead of digging into it.
- Update `doc/web.md` and `PROJECT_SUMMARY.md` (with the `project-summary`
  skill) after steps 1, 3 and 8, which change the flow they describe.
- An Aristotle fork is being prepared in parallel. The UI reads entities via
  `q/entity` and `q/expanded-entity` and relies on Aristotle's data shapes:
  keyword IRIs, LangStr, and blank nodes as symbols carrying metadata. Don't
  change those shapes here. That belongs to the data layer work.


Recommendations
---------------

Ranked by how much complexity a step removes for its risk. Steps that make
later steps smaller come first. The rank is also the suggested order, except
that step 5 is a good first commit for trying out the safety net.

| Rank | Step | Size | Main files |
|---|---|---|---|
| 1 | Make each page one value and embed it in the HTML | medium | `response.clj`, `resources.clj`, `router.cljs`, `app.cljc` |
| 2 | Name pages, sections and display modes with keywords | small | `section.cljc`, `ui.cljc`, `entity.cljc`, `app.cljc` |
| 3 | Split `on-navigate` and keep navigation state in one place | medium | `router.cljs`, `app.cljc`, `main.css` |
| 4 | Split the entity interceptor | medium | `resources.clj` |
| 5 | Merge the duplicated grouped-table pages | small | `page.cljc`, `catalog.cljc`, `relations.cljc` |
| 6 | Use fewer, coarser error boundaries | small | `error.cljc`, `rdf.cljc`, `entity.cljc` |
| 7 | Put predicate-specific rendering in one table | medium | `rdf.cljc`, `table.cljc` |
| 8 | Narrow `opts` and build each page model in one function | large | all of `web.ui.*` |
| 9 | Give the D3 code a clear boundary | medium | `d3.cljs`, `visualization.cljc` |
| 10 | Write duplicated text structures once | small | `ui.cljc`, `page.cljc`, `table.cljc` |
| 11 | Turn stateless components into plain functions | medium | all of `web.ui.*` |
| 12 | Small cleanups | small | various |


### 1. Make each page one value and embed it in the HTML

**What.** The server builds one map per page. The Transit response is that
map. The HTML response embeds the same map as Transit, and the client
hydrates from it instead of fetching the page a second time.

```clojure
;; The Transit body and the embedded page data have the same shape.
{:page      :entity                 ; a keyword, see step 2
 :title     "dn:synset-52"
 :deferred? true                    ; more relations can be fetched
 :prefs     {:languages    ["da" "en" nil]
             :detail-level :normal
             :full-screen  false}
 :data      {...}}                  ; today's :content

;; A redirect for the SPA.
{:redirect "/dannet/data/synset-52" :replace? true}
```

**Why.**

- The router no longer has to put a page together from headers, and
  `with-cookies` no longer mixes preferences into the data.
- Every full page load fetches the page twice today: as HTML, then as
  Transit before hydrating. reitit calls `on-navigate` for the current URL
  when it starts (`router.cljs:252-259`), and that fetches (`router.cljs:145`).
  Both requests count against the rate limit.
- The hydration render then uses data from the second request, not the data
  the server rendered. The `%2F` replace (`resources.clj:331-336`) exists only
  to make the two requests agree. The client also re-derives its preferences
  from cookies and data attributes (`app.cljc:15-30`), when the server has
  already worked them out.
- Markdown pages get a title.

**Where.** `response.clj:37-73` (`html-page`), `141-146` (HTML body),
`190-197` (Transit body), `233-246` (`with-cookies`, `x-headers`);
`resources.clj:74-134` (`redirect-ic`), `137-170` (`response-body-ic`),
`172-214` (`error-ic`) and the `:page-meta` of each page interceptor;
`router.cljs:31-41`, `131-267`; `app.cljc:15-30`.

**Steps.**

1. Add a function in `response.clj` that builds the page map from `content`,
   `page-meta` and the request's preferences. Use it for the Transit body and
   for the SPA redirect in `redirect-ic`.
2. In `html-page`, write the same map as Transit into a
   `<script type="application/transit+json" id="page-data">` element after
   `#app`. Escape every `<` as `<`, so that a label containing
   `</script>` can't end the element.
3. In the client, read the element once at start-up. When reitit makes its
   first `on-navigate` call, use the embedded page instead of fetching, then
   hydrate. If the page has `:deferred? true`, fetch the rest as today.
4. Seed `app/session` from `:prefs`. Remove the cookie and data-attribute
   reads in `app.cljc:15-30` unless something else still needs them (the
   `data-development` flag is still needed).
5. Compute the title of markdown pages on the server with
   `mdc/hiccup->title` (it is already in a cljc namespace) and remove the
   `set!` in `page/markdown`.
6. Remove what is now unused: `router/x-header`, `resp/x-headers` and the
   headers they set. Check a URL with an encoded slash, then remove the `%2F`
   replace if hydration stays clean.

**Check.** The snapshot diff shows the new script element and the markdown
titles, and nothing else in the HTML. In the browser's network panel, a full
page load makes one request for the page. Hydration is clean on every test
URL. The deferred relations still arrive on `dn:synset-52`.

**Size and risk.** Medium. The HTML grows by about the size of the Transit
data. For `dn:synset-52` that is 28 kB of HTML plus 23 kB of Transit,
uncompressed, measured on wordnet.dk. The escaping in step 2 must be right,
since labels come from external data.


### 2. Name pages, sections and display modes with keywords

**What.** Give each page type, entity section and display mode a keyword id,
and keep the display text as data next to it.

- **Pages.** Replace the string map in `page-shell` (`ui.cljc:189-198`) and
  the nav-colour `cond` (`ui.cljc:249-257`) with one registry:

  ```clojure
  (def pages
    {:entity    {:render page/entity}
     :search    {:render page/search}
     :markdown  {:render page/markdown  :nav-class "dannet"}
     :sparql    {:render page/sparql    :nav-class "w3c"}
     :metadata  {:render page/metadata  :nav-class "meta"}
     :relations {:render page/relations :nav-class "wordnet"}
     :error     {:render page/error}})
  ```

- **Sections.** Turn each `[title ks]` pair in `section.cljc` into
  `{:id :semantic :title #{...} :ks [...]}`. Dispatch on `:id` in
  `entity-section-content` (`entity.cljc:297-309`) and use it as the React
  key. Keep the DOM ids (`section-title-...`, `entity.cljc:314`) exactly as
  they are, since links with fragments may point at them.
  `cross-link-section` doubles as the relation probe in
  `web.instance/find-synset-relations` (`section.cljc:166-169`), so keep that
  working.
- **Display modes.** Store the session state as
  `{:semantic-display "diagram" :diagram-mode :radial}` or similar, not under
  a set of LangStr (`app.cljc:39-41`, `47-53`). Update `diagram-mode-path`
  users (`visualization.cljc:199`, `224`, `d3.cljs:1272`). Move the option
  labels from `ui-descriptions` (`entity.cljc:116-123`, whose TODO already
  asks if it is needed) into `display-mode-selector`.

**Check.** Snapshots unchanged.

**Size and risk.** Small to medium, low risk.


### 3. Split `on-navigate` and keep navigation state in one place

**What.** Rewrite `on-navigate` (`router.cljs:131-250`) as a few named steps,
and keep all navigation state in the router, apart from the user's session.

```clojure
(defn on-navigate
  [{:keys [path query-params]}]
  (-> (load-page! path query-params)
      (.then #(when (current? path) (show-page! path %)))
      (.catch #(when (current? path) (show-error! path %)))))
```

`show-page!` follows a redirect or sets the title, mounts, and handles
scrolling and focus. It calls `load-deferred!` when the page has
`:deferred? true`. `current?` is the one staleness check, used in both places
where today's code compares paths.

**Changes inside the step.**

- **Scroll.** Replace the `visited` stack (`router.cljs:24-26`, `78-129`) with
  the browser's own history entries. Set `history.scrollRestoration` to
  `"manual"`. Store the scroll position of `#content` in `history.state` with
  a debounced `replaceState` from a scroll listener. On each navigation,
  scroll to the stored position, or to the top when there is none. New
  entries have no stored state, so they start at the top, and back and
  forward restore the position.
- **Keeping the scroll position.** `app/post-navigate` (`app.cljc:44-45`)
  exists only so that a click in a diagram doesn't scroll
  (`d3.cljs:837`, `1273`; `router.cljs:164`, `178`). Make it an option:
  `(app/navigate-to href {:keep-scroll? true})`.
- **Fetch controllers.** Move them out of `app/session` into a router-owned
  atom.
- **The loading indicator.** Set a `data-fetching` attribute on `<html>`
  from the router instead of rendering a `fetching` class in `page-shell`
  (`ui.cljc:274-280`), and change the selectors in `main.css:921-940` and
  `2227` to match. The class has to agree with the server render during
  hydration, which is the reason for `*hydrated*` and `hydration-mixin`: on a
  large synset, the deferred fetch starts while React is still hydrating.
  With the attribute set outside React, both can go.

**Check.** On a long page, back and forward restore the scroll position, a
link click scrolls to the top, a label click in the radial diagram keeps the
position, and a link with a `#fragment` scrolls to its element. Two quick
link clicks in a row show the second page. A failing fetch shows the error
page. The title letters pulse while a page loads. `PROJECT_SUMMARY.md` uses
`(:data @dk.cst.dannet.web.router/current-page)` for debugging; keep that
working or update it.

**Size and risk.** Medium, contained in `router.cljs` and `app.cljc`. Do it
after step 1, which changes the first call.


### 4. Split the entity interceptor

**What.** Break `->entity-ic` (`resources.clj:287-457`) into named functions
that each do one thing, for example:

- `request->subject`: decode the path and query parameters into a subject
  (`resources.clj:298-328`);
- `entity-content`: look up, prune, truncate and trim the entity for the
  page;
- `sunburst-trees`: the three trees, only for synsets on the first request;
- `missing-entity`: the redirect, replace or 404 decision
  (`resources.clj:436-457`).

The interceptor then reads as a short `cond` over their results.

**Why.** This function decides what the entity page receives. It is the
server half of the flow that steps 1 and 3 make readable on the client.
Keeping the `q/entity` and `q/expanded-entity` calls in one small function
also gives the Aristotle work one place to change.

**Check.** Snapshots unchanged, in both HTML and Transit, including the
deferred response of `dn:synset-52` (`?deferred=true`).

**Size and risk.** Medium, server only, well covered by the snapshots.


### 5. Merge the duplicated grouped-table pages

**What.** `page/metadata` and `page/relations` (`page.cljc:79-133`) have the
same structure: a header, a subheading, then groups of
`[group-key title desc entries]`, each shown as a three-column table.
`catalog/table` (`catalog.cljc:70-102`) and `relations/table`
(`relations.cljc:126-143`) share the column group and the row layout.

Write one page component that takes a title, an intro and the groups, and
one table component that takes a row function. Keep each namespace's
`group-order` data and its `prepare-groups` where they are.

**Check.** Snapshots of `/dannet/metadata` and the relations page unchanged.

**Size and risk.** Small. A good first commit for trying out the safety net.


### 6. Use fewer, coarser error boundaries

**What.** Use two tools with clear roles:

- a plain `try`/`catch` around functions that return hiccup, with no
  component around it;
- an error boundary only around component subtrees that React renders
  later: the page (`ui.cljc:282`), each entity section, and each diagram.

`try-static-render` stays as it is for ref callbacks (D3) and mounting.

**Why.** `try-render` and `try-render-with` (`error.cljc:118-160`) wrap their
child in an error-boundary component on CLJS, whatever the child is.
`transform-val` uses `try-render-with` (`rdf.cljc:234`), and
`render-list-items` uses `try-render` for each item (`rdf.cljc:415`), so a
large synset page gets hundreds of extra React components. The boundary does
nothing for plain hiccup, which is already built when the boundary sees it.
Readers also have to check what each call wraps to know whether a failure
is caught.

**Check.** Snapshots unchanged, since boundaries render no DOM. In the
browser REPL, make `transform-val*` throw for one value and check that only
that value falls back. Make a section component throw and check that only
that section falls back.

**Size and risk.** Small. This is also the direction Replicant takes, where
a plain `try`/`catch` catches every render error.


### 7. Put predicate-specific rendering in one table

**What.** Collect the special cases for particular predicates into one map,
looked up in one place, and keep `transform-val` for the generic cases.

```clojure
(def predicate-display
  "How values of particular predicates are shown, by predicate."
  {:vann/preferredNamespacePrefix {:render prefix-value}
   :dns/ontologicalType           {:hide-prefix? true}
   :dns/simpleOntologicalType     {:hide-prefix? true}
   :foaf/homepage                 {:label? false}
   ...})
```

Blank nodes with a known shape (sentiment, inheritance, bags;
`rdf.cljc:339-394`) can dispatch on the keys of the blank node map in the
same spirit.

**Why.** Today, to learn how one predicate is shown, you read `rdf.cljc:39`,
`75-85`, `171-172`, `201-206`, `276-290`, `298-313`, `339-394`, plus
`table.cljc:22-28` and `visualization.cljc:269-280`.

**First, check issue #182.** Several branches are marked for removal once
#182 has shipped: `page.cljc:28-37`, `rdf.cljc:82-84`, `276-290`, and
`db/export/rdf.clj:261`. Check whether the database in use still has named
`inherit-*` resources. If it doesn't, delete those branches before this step.

**Check.** Snapshots unchanged. The URL list from the safety net covers each
special case. Add a URL if one is missing.

**Size and risk.** Medium. Easier after step 6.


### 8. Narrow `opts` and build each page model in one function

**What.** This is the largest change and the largest gain. Make a
component's inputs visible from its arguments.

- **A page model per page type.** Write a pure function per page type, in a
  cljc namespace such as `web.ui.model`, that takes the page value from
  step 1 and returns what the page renders. Move the derivations there from
  `page-shell` (`ui.cljc:223-245`: `k->label`, `synset?`, the subject's
  prefix), from `page/entity` (`page.cljc:22-39`: `inherited`) and from
  `viz-opts` (`visualization.cljc:13-21`). The function runs on both server
  and client, so hydration still matches, and you can call it in the JVM
  REPL.
- **A small render context.** Pass components a context that is the same for
  the whole page (`:languages`, `:detail-level`, `:k->label`, `:full-screen`)
  and pass the data they render as arguments.
- **No session merge.** The components that need session state (search box,
  display selectors, diagram mode) read it themselves. On the server, use a
  plain default map instead of dereferencing the client's atom
  (`ui.cljc:201-205`).
- **Arguments, not flags.** A flag that only travels one or two levels
  (`:badge`, `:subject-label`, `:cloud-limit`, `:sunburst-caption`,
  `:link-href`, `:link-title`) becomes an argument.

**Steps.** Each stage is its own commit series.

1. List every `opts` key: where it is set and where it is read. Put the
   table in the docstring of `page-shell` or in `doc/web.md`. This alone
   helps anyone who works on the UI.
2. Write the page model functions and call them from `page-shell`.
3. Replace the session merge.
4. Turn the short-lived flags into arguments, one namespace at a time.

**Check.** Snapshots unchanged and hydration clean after each stage.

**Size and risk.** Large. Steps 1, 2, 6 and 7 remove keys first, which makes
this one smaller.


### 9. Give the D3 code a clear boundary

**What.**

- **Callbacks instead of `app`.** Pass `:on-navigate` and
  `:on-toggle-full-screen` to the builders instead of requiring `web.app`
  (`d3.cljs:8`, `197`, `837-838`, `948`, `1268-1274`). `d3.cljs` then depends
  only on its arguments.
- **One rebuild rule.** Today the word cloud tracks what it drew in an atom
  (`d3.cljs:219-231`), the radial diagram removes and rebuilds its SVG on
  every call (`d3.cljs:868-875`) from a ref function that is new on every
  render (`visualization.cljc:37-40`), and the sunburst stores a key on its
  DOM element (`d3.cljs:1375-1391`). Write one helper that runs a builder in
  a ref callback only when its key (the data and the options that affect
  the drawing) changes, and use it for all three.
- **Pure layout code in a cljc namespace.** Move the data preparation
  functions that don't touch the DOM (e.g. `prepare-synset-cloud`,
  `insert-balanced-spacers`, `optimize-radial-structure`,
  `prepare-radial-children`) to a cljc namespace, so they can be called in
  the JVM REPL. Don't change the algorithms.
- **Legend filtering scoped to its figure.** The radial legend finds the
  diagram with `document.querySelector` and edits its classes
  (`visualization.cljc:114-165`). Pass the selected relation to the builder
  as data, or at least look the diagram up from the legend's own figure.

**Check.** Screenshots before and after of the radial diagram, the sunburst
(including zoom and the history trail), the word cloud, and full-screen
mode. Resize the window and check that the sunburst keeps its zoom.

**Size and risk.** Medium. Visual checks are needed, since the snapshots
don't contain the SVG.


### 10. Write duplicated text structures once

**What.** Write each structure once and look up its text by language: the
footer (`ui.cljc:26-92`), the error and not-found pages (`page.cljc:160-211`)
and the display-options select (`table.cljc:135-161`). Move text that is used
in several places into one small table in `web.i18n`. Examples are the
column labels shared by three tables, "download data as" (`entity.cljc:99-101`
and `sparql.cljc:427-429`), and "Minimize"/"Maximize" (`visualization.cljc:70-72`
and `d3.cljs`). The `comments` map that `page-shell` rebuilds on every render
(`ui.cljc:207-222`) moves there too.

Don't move every `da-en` call into the table. A short pair inline is easy to
read where it is used. Keep the `lang` attributes correct.

**Check.** Snapshots unchanged in both languages.

**Size and risk.** Small, low risk.


### 11. Turn stateless components into plain functions

**What.** There are 63 Rum components (`rum/defc` and `rum/defcs`). None use
`rum/static`, so they re-render whenever their parent does, the same as a
plain function would. Turn the stateless ones into `defn`, putting `:key` on
the root element instead of using `rum/with-key`. Rum then remains only
where there is state or a life-cycle:

- `page-shell` (reactive);
- `attr-val-table` and `attr-val-row` (local and reactive display options);
- `reactive-details`, the search form, `radial-tree-legend`,
  `synset-diagram`, `word-cloud` (local state);
- `sunburst-legend` (reactive);
- `expanded-diagram` (resize mixin), the SPARQL `editor` (CodeMirror mixin),
  `error-boundary`.

Then write down each piece of remaining state: where it lives, what writes
it and what reads it. The display options of `attr-val-table`
(`table.cljc:239-243`) are already keyed by subject and could move to the
session.

**Why.** Plain functions return hiccup you can inspect in the JVM REPL. They
also shrink the part of the code that depends on Rum, which is the main
preparation for a renderer switch.

**Trade-off.** Rum compiles literal hiccup inside a `defc` body at compile
time. Hiccup returned from a plain function is interpreted at run time.
Measure the mount time of `dn:synset-52` before and after.

**Size and risk.** Medium, mostly mechanical. Do it last, after the
structure has settled.


### 12. Small cleanups

Each is one commit.

- **Hydration hacks in the selects.** `language-select` has a TODO saying its
  `:on-change` may not fire after hydration (`ui.cljc:96-99`), and
  `detail-level-select` adds a native listener as a fallback
  (`ui.cljc:140-150`). Commit b5188b7 fixed when hydration counts as done.
  Check in the browser whether React's `onChange` now fires after hydration,
  and remove the fallback if it does.
- **SPARQL editor helpers.** `set-submit-disabled!` exists twice
  (`form.cljc:40-46`, `sparql.cljc:50-55`). Query strings are built by hand
  twice (`sparql.cljc:32-41`, `413-424`). `validate-query!` calls
  `fetch/request` with its own options (`sparql.cljc:74-78`) instead of
  `app/fetch!`.
- **Global DOM lookups.** Use a ref or the event target where the code now
  looks up elements by id or selector: `search.cljc:44-45`, `72`;
  `sparql.cljc:99`; `visualization.cljc:119-121`; `aria.cljs:64`.
- **`shared.cljc`.** At 747 lines it mixes cookies, URLs, labels, sorting,
  word cloud weights and colours. Move the parts only the UI uses next to
  their users. Check the users with grep first.
- **`?transit=true`.** The parameter keeps the browser cache from showing
  Transit on back and forward (`app.cljc:106-111`). Responses now send
  `Vary: Accept` (commit 8c5d451). Without the parameter, test back and
  forward in Chrome, Firefox and Safari. Remove it, and its handling in
  `remove-internal-params` (`resources.clj:238-241`), only if all three
  behave. Low priority.


The renderer
------------

DanNet renders with a fork of Rum on React 18. Rum's last release was in
2023, and React 19 removed `findDOMNode`, which `rum/dom-node` uses
(`sparql.cljc:134`, `visualization.cljc:315`). See the library report for
details. The options as of 27 October 2026:

- **Replicant.** No React; components are plain functions that return
  hiccup. DMLex-browser already uses it. Hydration was implemented on a
  branch in July 2026. On 7 October the maintainer asked for testers, and on
  16 October a tester reported that the normal case works, but that a
  mismatch between server and client hiccup gives no warning and later
  renders go wrong. It isn't released. Whether life-cycle hooks run during
  hydration isn't stated, and D3 and CodeMirror need them.
- **UIx.** A maintained React wrapper. `uix.dom.server` renders cljc
  components to HTML on the JVM for React to hydrate. It keeps React, and
  components use hooks.
- **No SPA.** Render every page on the server only, and attach small CLJS
  widgets (search box, SPARQL editor, diagrams, display toggles) to their
  elements. This removes the router, the Transit fetch layer, hydration and
  the deferred merge. In exchange, every link is a full page load, and
  changing the language or detail level needs a reload or a widget of its
  own.

**Recommendation.** Don't change the renderer during steps 1 to 12. After
step 11 the switch is mostly mechanical. Move to Replicant once its
hydration is released and runs life-cycle hooks, for the reasons in the
library report. If that takes too long, UIx is the lower-risk path, since it
supports server rendering with hydration today. Consider the no-SPA route
only if the SPA behaviour is no longer wanted.


What not to do
--------------

- Don't add a state library such as re-frame. One session atom and one
  navigation atom are enough.
- Don't change the D3 layout algorithms or how anything looks.
- Don't restructure `main.css` in the same commits. At 2,598 lines it is the
  most-changed UI file (89 commits since October 2025) and deserves a pass
  of its own.
- Don't reformat or reorder namespaces as a side effect.
- Don't switch the renderer inside these steps.


Appendix: the UI namespaces
---------------------------

Line counts at 16a61c3. The UI namespaces add up to 5,460 lines, with 63
components, 6 `rum/local`, 3 reactive components and 4 mixins (hydration,
error boundary, CodeMirror, resize).

| Namespace | Lines | Role | Components | State |
|---|---|---|---|---|
| `web.router` (cljs) | 267 | routes, navigation, mounting | | `root`, `current-page`, `visited` |
| `web.app` | 157 | session, fetch, cookies | | `session`, `post-navigate` |
| `web.ui` | 283 | page shell, nav bar, footer | 5 | reads session, `*hydrated*` |
| `web.ui.page` | 211 | one component per page type | 8 | |
| `web.ui.entity` | 338 | entity page parts | 13 | writes session |
| `web.ui.rdf` | 522 | RDF values, lists, blank nodes | 10 | 1 local |
| `web.ui.table` | 264 | attribute-value table | 7 | 1 local, 1 reactive |
| `web.ui.visualization` | 342 | diagram shell, legends | 11 | 3 local, 1 reactive, resize mixin, writes session |
| `web.ui.sparql` | 443 | SPARQL editor and results | 4 | CodeMirror mixin, editor atom |
| `web.ui.search` | 157 | search box and results | 3 | 1 local, writes session |
| `web.ui.error` | 183 | boundaries and macros | 2 | |
| `web.ui.catalog` | 102 | metadata tables | | |
| `web.ui.relations` | 143 | relation groups and table | | |
| `web.ui.form` | 65 | form submission | | |
| `web.ui.markdown` | 46 | markdown to hiccup (server) | | |
| `web.ui.codemirror` (cljs) | 107 | CodeMirror interop | | |
| `web.ui.search.aria` (cljs) | 83 | combobox keys | | |
| `web.section` | 280 | entity page sections | | |
| `web.i18n` | 76 | label selection, `da-en` | | |
| `web.d3` (cljs) | 1,391 | D3 diagrams | | DOM-held |

On the server, `web.response` (284 lines) renders HTML and Transit, and the
page interceptors in `web.resources` (957 lines) build the page data.


Sources
-------

- [library-report.md](library-report.md), Part 2, item 3 (Replicant)
- Replicant hydration: https://github.com/cjohansen/replicant/issues/53
- UIx server-side rendering: https://cljdoc.org/d/com.pitch/uix.dom/1.4.0/doc/server-side-rendering
- Rum and React 18: https://github.com/tonsky/rum/issues/250
