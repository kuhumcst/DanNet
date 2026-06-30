(ns dk.cst.dannet.test-util
  "Shared helpers for DanNet test namespaces."
  (:import [java.io ByteArrayInputStream]
           [java.nio.charset StandardCharsets]
           [org.apache.jena.graph Factory]
           [org.apache.jena.riot RDFParser Lang]))

(defn ttl->graph
  "Parse a Turtle `ttl` string into an in-memory Jena Graph."
  [^String ttl]
  (let [g (Factory/createDefaultGraph)]
    (.. (RDFParser/source (ByteArrayInputStream. (.getBytes ttl StandardCharsets/UTF_8)))
        (lang Lang/TTL)
        (parse g))
    g))
