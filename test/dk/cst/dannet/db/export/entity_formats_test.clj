(ns dk.cst.dannet.db.export.entity-formats-test
  "Tests for the Turtle and JSON-LD of an entity page, read back with Jena.

  The fixture holds the values that both formats used to get wrong, and each
  format has to say exactly the triples of the expected Turtle."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dk.cst.theophrastus.term :refer [->LangString]]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.db.export.json-ld :as json-ld]
            [dk.cst.dannet.db.export.rdf :as export.rdf]
            [dk.cst.dannet.test-util :as util])
  (:import [java.io ByteArrayInputStream]
           [java.nio.charset StandardCharsets]
           [org.apache.jena.graph Graph GraphMemFactory]
           [org.apache.jena.riot Lang RDFParser]
           [org.apache.jena.sparql.core DatasetGraphFactory]))

(def entity
  "A synset as q/expanded-entity returns it, with a relation sorted for
  display, IRIs with no prefix, a dc:subject, a number in a blank node and
  the labels of two resources."
  (with-meta
    {:rdf/type      :ontolex/LexicalConcept
     :dc/subject    (->LangString "zoologi" "da")
     :wn/hyponym    '(:dn/synset-3 :dn/synset-2)
     :dns/source    "<https://example.com/entry?id=1>"
     :skos/inScheme "<https://wordnet.dk/dannet/data>"
     :dns/sentiment {:rdf/about '_b1 :marl/polarityValue -1}}
    {:subject  :dn/synset-1
     :entities {:rdf/type                          {:rdfs/label "type"}
                "<https://wordnet.dk/dannet/data>" {:rdfs/label "DanNet"}}}))

(def expected
  (str (str/join (for [[k v] prefix/prefixes]
                   (str "@prefix " (name k) ": " v " .\n")))
       "
dn:synset-1 a ontolex:LexicalConcept ;
  dc:subject \"zoologi\"@da ;
  wn:hyponym dn:synset-2, dn:synset-3 ;
  dns:source <https://example.com/entry?id=1> ;
  skos:inScheme <https://wordnet.dk/dannet/data> ;
  dns:sentiment [ marl:polarityValue -1 ] .
rdf:type rdfs:label \"type\" .
<https://wordnet.dk/dannet/data> rdfs:label \"DanNet\" .
"))

(defn- json-ld->graph
  "Parse the JSON-LD `s` into a Graph of the triples of all its graphs."
  ^Graph [^String s]
  (let [dsg (DatasetGraphFactory/create)
        g   (GraphMemFactory/createDefaultGraph)]
    (.. (RDFParser/source (ByteArrayInputStream. (.getBytes s StandardCharsets/UTF_8)))
        (lang Lang/JSONLD11)
        (parse dsg))
    (doseq [quad (iterator-seq (.find dsg))]
      (.add g (.asTriple quad)))
    g))

(deftest turtle-test
  (is (.isIsomorphicWith (util/ttl->graph (export.rdf/ttl-entity entity))
                         (util/ttl->graph expected))))

(deftest json-ld-test
  (let [entities (for [[subject m] (:entities (meta entity))]
                   (assoc m :rdf/about subject))]
    (is (.isIsomorphicWith (json-ld->graph
                             (json/write-str (json-ld/json-ld-ify entity
                                                                  entities)))
                           (util/ttl->graph expected)))))
