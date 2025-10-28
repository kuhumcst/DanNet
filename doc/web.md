# A hydrated SPA architecture using Rum

._..with special guests: Transit and Pedestal._

> *To anyone who is _not_ my future self: these are my notes on the architecture of a web application used to browse the next version of the Danish WordNet: an RDF graph and its associated ontologies.*

- [Overall architecture (component diagram)](architecture.svg)
- [Website flow (sequence diagram)](website-flow.svg)

At this point, I have several years of experience working with [reagent](https://github.com/reagent-project/reagent) which—I believe—is still the simplest way to create a "reactive" [single-page app](https://en.wikipedia.org/wiki/Single-page_application) (or "SPA" for short) in ClojureScript. However, I had still not created a properly ["hydrated"](https://reactjs.org/docs/react-dom.html#hydrate) SPA, which is the nomenclature used to describe single-page apps that are prerendered server-side and subsequently made to come alive in glorious, reactive dynamism on the client.

## Reagent's strengths
The promise of reagent is
simple: a SPA can be built nearly entirely from regular Clojure functions returning regular Clojure data structures taking the shape of the well-known Hiccup DSL, combined with a special, reactive version of the [Clojure atom](https://clojure.org/reference/atoms). In other words: if you're a Clojure developer you already know the individual parts making up reagent. Of course, you will need to learn the component lifecycle and a few other quirks of the underlying [React](https://github.com/facebook/react) library (e.g. [React keys](https://reactjs.org/docs/lists-and-keys.html#keys)) to fully grasp reagent, but it is still the fastest way to get going.

However, one deficiency of reagent is the fact that it doesn't make it easy to leverage server-side (pre-)rendering in JVM Clojure. It _does_ have the sparse [reagent.dom.server](https://github.com/reagent-project/reagent/blob/master/src/reagent/dom/server.cljs) namespace exposing the React server-side rendering functions. However, this is still in the realm of _ClojureScript_, whereas _most_ backend implementations in Clojure will—for good reasons—be running on the JVM, including mine. For this reason, reagent is _not_ ideal if your stack is Clojure+ClojureScript. This led me towards the other major React wrapper library in the Clojure ecosystem: [Rum](https://github.com/tonsky/rum).

## The way to Rum
> *To reiterate: I was trying to build a server-rendered, hydrated, single-page app. My intention was to make something that would be able to gracefully degrade when JavaScript was disabled and progressively upgrade when it _wasn't_. This is something I had never attempted before since I started building SPAs.*

The backend web service for the DanNet project was created using Cognitect's own [Pedestal](https://github.com/pedestal/pedestal) library which I actually consider to be quite underrated. To be honest, it's not perfect—and barely maintained at this point—but it's stable and offers an integrated, data-oriented approach to backend development that is still unrivaled, even by [Metosin's best efforts](https://github.com/metosin).

By the time I began researching Rum, I was just using the [original Hiccup](https://github.com/weavejester/hiccup) library as an HTML templating system. Initially, I thought that I might be able to leverage Lambda island's [alternative Hiccup](https://github.com/lambdaisland/hiccup) implementation which supports a subset of reagent's updated dialect of Hiccup. This would serve as a way to transform the server-rendered Hiccup into reusable components in a `.cljc` namespace. However, Lambda islands's implementation didn't correspond fully to the reagent implementation (e.g. no inlining of content from seqs) so I abandoned this approach and ultimately decided to abandon reagent entirely.

I already knew that Rum was the second most popular, actively developed ClojureScript React wrapper (going by Github stars). I was exalted to learn that—unlike reagent—Rum also came with its own JVM-based, server-side rendering implementation. After a quick survey of Rum's documentation, I could tell that its flavour of Hiccup was very similar to reagent's, making the switch quite painless.

## Content negotiation
The fact that Rum components have this dual purpose (HTML templates on the server and React components on the client) makes them a convenient tool for combining server-side and client-side rendering in a Clojure+ClojureScript stack.

However, being able to reuse the Rum components in both `.clj` and `cljs.` files is not _in itself_ enough to fully support a gracefully degrading, hydrated single-page app. To implement something like this, you need to think hard about the flow of data across your full stack and draw up an architecture that can adequately support this data flow.

The simplest solution I could think of was to make sure that every path served by the Pedestal web service could be resolved as _both_ a fully rendered HTML page _as well_ as the input data used to render that HTML page. This is key to making a SPA that can gracefully degrade. And of course nothing is stopping you from implementing alternative supported representations, e.g. eventually I will probably also implement [Turtle](https://en.wikipedia.org/wiki/Turtle_(syntax)). 

> _Let me briefly remind you that this is ultimately meant to support browsing an RDF graph._

This kind of architecture relies on [content negotiation](https://developer.mozilla.org/en-US/docs/Web/HTTP/Content_negotiation) of the content-type (and also language in my case). Every accessible resource served by the Pedestal web service has both a `text/html` representation—i.e. an HTML page—as well as a regular Clojure data representation by way of [Transit](https://github.com/cognitect/transit-format): `application/transit+json`. This makes Rum component reuse on the server/client a simple case of _when_ the component is rendered, since the data is easily accessible in both locations and the same components are used for the task.

I have used Transit several times before. It is indispensable in any Clojure+ClojureScript stack since it helps make the frontend/backend transition nearly seamless, provided you are diligent about being data-oriented.

The data transformation pipeline thus becomes:

```
Database query
  -> Apache Jena Triples
    -> Clojure data
      -> HTML source (server-side only)
       -> Rendered UI
```

## Hyperlink and form interception
Say a user visits some URL, e.g. the one with the path `/dannet/2022/instances/synset-9999`. Unless this user is specifically requesting a different content-type, `text/html` is what will be returned. The HTML page has already been rendered on the server using Rum, but it is now ready to be "hydrated", which means it will be seamlessly turned into a client-rendered SPA.

What happens in practice is that the Clojure data representation is fetched in the background and rendered as virtual HTML elements in the browser, silently assuming the role of the static HTML that was originally returned by the server. From the user's point of view nothing has changed, but from the system's point of view, the client application is now in full control. Any further manipulation of the [DOM](https://developer.mozilla.org/en-US/docs/Web/API/Document_Object_Model/Introduction) is done directly by the client application.

Once hydrated, frontend routing (by way of [reitit](https://github.com/metosin/reitit)) takes over. Reitit intercepts every single click on an inbound hyperlink. These clicks are turned into background fetches of the Clojure data representation of the requested pages at each path—this is where content negotiation comes into play. The Clojure data that is returned is the exact same data used to render the pages server-side, it just happens on the client instead. The same set of Rum components from `dk.cst.dannet.web.ui.*` are thus used in both the Clojure code (server) and the ClojureScript code (client).

The way the reitit frontend routing is set up is very basic. There is in fact just a single route which conveniently matches every path prefixed with `/dannet/`:

```clojure
(def routes
  [["/dannet/{*path}" :delegate]])
```

This catch-all route will delegate nearly _all_ requests to the Pedestal backend web service using [fetch](https://github.com/lambdaisland/fetch), sending matching background requests asking for the _Clojure data_ used to render the page (`application/transit+json`) rather than the rendered _HTML page_ itself (`text/html`). The only paths that bypass the frontend routing are download URLs prefixed with `/download/` (rather than `/dannet/`).

Form submissions are handled in the same way by keying a generic `on-submit` function to every form's `:on-submit` attribute. This function cancels the regular form behaviour and turns form submission events into background requests also returning `application/transit+json`. It doesn't do so directly, but rather uses the indirect route through the frontend router, first collecting the form's data into a URL and then "navigating" to that location:

```clojure
(defn on-submit
  "Generic function handling form submit events in Rum components."
  [e]
  #?(:cljs (let [action    (.. e -target -action)
                 query-str (-> (.. e -target -elements)
                               (form-elements->query-params)
                               (uri/map->query-string))
                 url       (str action (when query-str
                                         (str "?" query-str)))]
             (.preventDefault e)
             (navigate-to url))))
```

> _Note: for now, this generic function only supports GET requests!_

In case JavaScript is disabled in the browser, both hyperlinks _and_ forms on the HTML page will still keep working as the basic source code is essentially just plain HTML with no presumption of embedded JavaScript functionality. Hyperlinks and forms simply fetch whole HTML pages rendered server-side like a regular—non-SPA—website. However, any of the more dynamic features on the page will of course not work without JavaScript.

## The future
I am still working on the new version of DanNet, of which the web application is only a small part. In fact, most of my efforts have been spent on data modeling and conforming to RDF/WordNet standards. The primary purpose of this web app is to make all of the IRIs in the dataset properly resolve as RDF graph data. This is another reason why content negotation plays a big part in the design of the system.

For now, only a basic  resource browser and search functionality has been implemented (and nothing has been published online yet). It will be interesting to see how far this two-pronged approach can go and whether it will eventually break down. Eventually, I plan on expanding the web app to support more dynamic features as well as building an editor component which should replace our obsolete, internal tool previously used to build the Danish WordNet.

~ [Simon Gray](https://github.com/simongray)
