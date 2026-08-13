---
name: browser-repl-debug
description: Debug DanNet frontend behaviour in the live browser via the shadow-cljs nREPL. Use for any frontend-only bug — hydration errors invisible to SSR, CLJS/CLJ behavioural differences (keyword hashing, subs on non-strings), inspecting transit payloads as the client sees them, client-state questions, and CSS/layout/pixel work — instead of guessing from source. Also use when asked to inspect or measure anything in the running browser tab.
---

# Browser REPL Debugging (shadow-cljs)

Frontend bugs are debugged in the live browser tab, not by reading source and
guessing. The shadow-cljs nREPL runs alongside the regular clj nREPL (7888);
its port is in `.shadow-cljs/nrepl.port` and `clj-nrepl-eval --discover-ports`
finds it. Sessions persist per port, so once switched to CLJS mode, later
evaluations on that port stay in the browser until `:cljs/quit`.

## Connect

```clojure
;; On the shadow-cljs nREPL port:
(shadow.cljs.devtools.api/repl-runtimes :app)  ; confirm a browser tab is connected
(shadow.cljs.devtools.api/repl :app)           ; switch session to CLJS (:cljs/quit to leave)
```

## Inspect and reproduce

```clojure
;; Client state, e.g. the current page data:
(:data @dk.cst.dannet.web.router/location)

;; Drive navigation to reproduce a bug on a specific page:
(dk.cst.dannet.web.app/navigate-to "/dannet/data/synset-52")
;; (this var has moved between namespaces before — if undefined, locate the
;;  current navigate-to rather than assuming)

;; Query and measure the DOM, incl. glyph-level baseline comparisons:
(.querySelector js/document "tr[property=\"dns:inherited\"]")
;; use Range/getClientRects on single characters to compare baselines
```

## Instrument non-invasively

Capture args/stacks in an atom deref-able from the REPL; keep the original so
it can be restored:

```clojure
(defonce orig-f some.ns/suspect-fn)
(defonce captured (atom []))
(set! some.ns/suspect-fn (fn [x]
                           (swap! captured conj [(pr-str x) (.-stack (js/Error.))])
                           (orig-f x)))
```

## Trial CSS before touching main.css

```clojure
;; Inject a temporary <style> and re-measure:
(let [style (.createElement js/document "style")]
  (set! (.-id style) "exp-style")
  (set! (.-textContent style) "span.inheritance sub { line-height: 0; }")
  (.appendChild (.-head js/document) style))

;; Cache-bust stylesheets after editing main.css:
(doseq [link (array-seq (.querySelectorAll js/document "link[rel=stylesheet]"))]
  (set! (.-href link) (str (first (clojure.string/split (.-href link) #"\?"))
                           "?v=" (js/Date.now))))
```

## Ground rules

- This drives Simon's actual browser tab: navigation and re-renders are
  visible to him. Coordinate before jumping around, and report anything
  unusual immediately rather than investigating solo.
- Always clean up before finishing: restore every `set!`-instrumented
  function and remove every injected `<style>` element.
- The shadow watcher hot-reloads edited `.cljs`/`.cljc` files (allow a few
  seconds before re-testing); backend `.clj` changes still need
  `(require ... :reload)` in the *clj* REPL (port 7888).
- Don't start or restart shadow-cljs yourself — Simon handles that.
