Web architecture
================
The backend web service was created with [Pedestal](https://github.com/pedestal/pedestal). The HTML is rendered using [Rum](https://github.com/tonsky/rum) which is both a [React](https://github.com/facebook/react) wrapper for ClojureScript, but also doubles as a server-side HTML template engine in Clojure. The fact that Rum components have this dual purpose makes them a convenient tool for combining server-side and client-side rendering in a Clojure/ClojureScript stack.

The architecture of the web service and the frontend [single-page app](https://en.wikipedia.org/wiki/Single-page_application) (= SPA) relies on [content negotiation](https://developer.mozilla.org/en-US/docs/Web/HTTP/Content_negotiation) of both content-type and language. Every single accessible resource served by the web service will have both a `text/html` representation (= an HTML page) as well as a `application/transit+json` representation (= Clojure data). This Clojure data representation is in fact the input used to render every HTML page using Rum, whether it happens on the server or on the client.

The data transformation process can be imagined as a pipeline:

```
Database query --> Apache Jena Triples --> Clojure data --> HTML source
```

Say a user visits some URL, e.g. the one with the path `/dannet/2022/instances/synset-9999`. Unless this user is specifically requesting a different content-type, `text/html` is what will be returned. The HTML page has already been rendered on the server using Rum, but it is now ready to be "hydrated", which means it will be turned into a client-rendered SPA. What happens in practice is that the Clojure data representation is fetched in the background and rendered as HTML elements in the browser, silently assuming the role of the static HTML that was originally returned.

Once hydrated, frontend routing (by way of [reitit](https://github.com/metosin/reitit)) takes over and converts every single click on a hyperlink into another background fetch of the Clojure data representation of the requested page. This is where content negotiation comes into play. The Clojure data that is returned is the exact same data used to render the pages server-side, it just happens on the client instead. The same set of Rum components from `dk.cst.dannet.web.components` are thus used in both the Clojure code (server) and the ClojureScript code (client).

The way the reitit frontend routing is set up is very simple. There is in fact just a single route which matches every path:

```clojure
(def routes
  [["{*path}" :delegate]])
```

This catch-all route delegates _all_ requests to the Pedestal backend web service using [fetch](https://github.com/lambdaisland/fetch), sending matching background navigation requests asking for the _Clojure data_ used to render the page (`application/transit+json`) rather than the rendered _HTML page_ itself (`text/html`). Form submissions are handled in the same way by attaching the same generic `on-submit` function to every form. This function cancels the regular form behaviour and turns form submission events into background requests returning `application/transit+json`.

In case JavaScript is disabled in the browser, both hyperlinks _and_ forms on the HTML page will still keep working. They simply fetch whole HTML pages rendered server-side like a regular, i.e. non-SPA, website. However, any of the more dynamic features on the page will of course not work without JavaScript.

