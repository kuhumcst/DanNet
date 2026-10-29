(ns dk.cst.dannet.test-util
  "Shared helpers for DanNet test namespaces."
  (:require [dk.cst.theophrastus :as theo]
            [dk.cst.dannet.prefix :as prefix])
  (:import [java.io ByteArrayInputStream]
           [java.nio.charset StandardCharsets]
           [org.apache.jena.graph GraphMemFactory]
           [org.apache.jena.riot RDFParser Lang]))

(defn ttl->graph
  "Parse a Turtle `ttl` string into an in-memory Jena Graph."
  [^String ttl]
  (let [g (GraphMemFactory/createDefaultGraph)]
    (.. (RDFParser/source (ByteArrayInputStream. (.getBytes ttl StandardCharsets/UTF_8)))
        (lang Lang/TTL)
        (parse g))
    g))

(defn ttl->db
  "Parse a Turtle `ttl` string into an in-memory db with DanNet's prefixes."
  [ttl]
  (theo/db (ttl->graph ttl) {:prefixes prefix/prefixes}))
