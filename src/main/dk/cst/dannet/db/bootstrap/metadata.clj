(ns dk.cst.dannet.db.bootstrap.metadata
  "The DanNet dataset metadata applied during bootstrapping."
  (:require [clojure.set :as set]
            [ont-app.vocabulary.lstr :refer [->LangStr]]
            [taoensso.telemere :as t]
            [dk.cst.dannet.db :as db]
            [dk.cst.dannet.db.transaction :as txn]
            [dk.cst.dannet.hash :as h]
            [dk.cst.dannet.prefix :as prefix])
  (:import [org.apache.jena.rdf.model Model]
           [org.apache.jena.vocabulary RDF]))

(defn da
  [& s]
  (->LangStr (apply str s) "da"))

(defn en
  [& s]
  (->LangStr (apply str s) "en"))

(def <simongray>
  "<https://simongray.dk>")

(def <cst>
  "<https://cst.dk>")

(def <dsl>
  "<https://dsl.dk>")

(def <dsn>
  "<https://dsn.dk>")

(def <dn>
  "The RDF resource URI for the DanNet dataset."
  (prefix/prefix->rdf-resource 'dn))

(def <dns>
  "The RDF resource URI for the DanNet schema."
  (prefix/prefix->rdf-resource 'dns))

(def <dnc>
  "The RDF resource URI for the DanNet/EuroWordNet concepts."
  (prefix/prefix->rdf-resource 'dnc))

(def <dds>
  "The RDF resource URI for the sentiment dataset."
  (prefix/prefix->rdf-resource 'dds))

(def <cor>
  "The RDF resource URI for the COR dataset."
  (prefix/prefix->rdf-resource 'cor))

(def dn-zip-uri
  (prefix/dataset-uri "rdf" 'dn))

(def dn-zip-csv-uri
  (prefix/dataset-uri "csv" 'dn))

(def cor-zip-uri
  (prefix/dataset-uri "rdf" 'cor))

(def dds-zip-uri
  (prefix/dataset-uri "rdf" 'dds))

(def dns-schema-uri
  (prefix/schema-uri 'dns))

(def dnc-schema-uri
  (prefix/schema-uri 'dnc))

;; :from - the previous formal release we bootstrap on top of. The zip files
;;         placed in /bootstrap/latest must match it precisely. It determines
;;         which release downloads/fetch-bootstrap-datasets! pulls from GitHub.
;;
;; :to   - the version we produce. It stays "SNAPSHOT" throughout development
;;         (the target date isn't known up front) and is set to a real version
;;         only at the moment a release is cut.
(def release
  {:from "2025-07-03"
   :to   "SNAPSHOT"})

(def bootstrap-base-release
  "The previous formal release the database is bootstrapped from."
  (:from release))

(def new-release
  "The version being produced; stays \"SNAPSHOT\" until a release is cut."
  (:to release))

(defn see-also
  [source rdf-resources]
  (set (for [v rdf-resources]
         [source :rdfs/seeAlso v])))

(h/defn update-metadata!
  "Remove old dataset metadata from `model` and add current `dataset-metadata`.

  The resources to remove are derived from the subjects of the incoming
  `dataset-metadata` rather than kept in a hand-maintained list, ensuring that
  the removals can never go out of date as metadata is added or changed from
  release to release."
  [dataset-metadata model]
  (t/log! {:level :debug :id :dannet.bootstrap/update-metadata}
          "Updating dataset metadata")
  (doseq [rdf-resource (set (map first dataset-metadata))]
    (db/remove! model [rdf-resource '_ '_]))
  (db/safe-add! (.getGraph ^Model model) dataset-metadata))

(h/def metadata
  {'dn  (set/union
          (see-also <dn> [<dns> <dnc> <dds> <cor>])
          (see-also <cst> [<dn> <dsl> <dsn>])
          #{[<dn> :rdf/type :dcat/Dataset]
            [<dn> :rdf/type :lime/Lexicon]
            [<dn> :vann/preferredNamespacePrefix "dn"]
            [<dn> :vann/preferredNamespaceUri (prefix/prefix->uri 'dn)]
            [<dn> :rdfs/label "DanNet"]
            [<dn> :dc/title "DanNet"]
            [<dn> :dc/language "da"]
            [<dn> :dc/description (en "The Danish WordNet.")]
            [<dn> :dc/description (da "Det danske WordNet.")]
            [<dn> :dc/issued new-release]
            [<dn> :dc/contributor <simongray>]
            [<dn> :dc/contributor <cst>]
            [<dn> :dc/contributor <dsl>]
            [<dn> :dc/publisher <cst>]
            [<dn> :foaf/homepage "<https://cst.ku.dk/projekter/dannet>"]
            [<dn> :schema/email "simongray@hum.ku.dk"]
            [<dn> :owl/versionInfo new-release]
            [<dn> :dc/rights (en "Copyright © Centre for Language Technology (University of Copenhagen) & "
                                 "The Society for Danish Language and Literature; "
                                 "licensed under CC BY-SA 4.0 (https://creativecommons.org/licenses/by-sa/4.0/).")]
            [<dn> :dc/rights (da "Copyright © Center for Sprogteknologi (Københavns Universitet) & "
                                 "Det Danske Sprog- og Litteraturselskab; "
                                 "udgives under CC BY-SA 4.0 (https://creativecommons.org/licenses/by-sa/4.0/).")]
            [<dn> :dc/license "<https://creativecommons.org/licenses/by-sa/4.0/>"]
            ["<https://creativecommons.org/licenses/by-sa/4.0/>" :rdfs/label "CC BY-SA 4.0"]
            [<dn> :dcat/downloadURL (prefix/uri->rdf-resource dn-zip-uri)]
            [<dn> :dcat/downloadURL (prefix/uri->rdf-resource dn-zip-csv-uri)]
            [<dns> :dcat/downloadURL (prefix/uri->rdf-resource dns-schema-uri)]
            [<dnc> :dcat/downloadURL (prefix/uri->rdf-resource dnc-schema-uri)]

            ;; Contributors
            [<simongray> :rdf/type :foaf/Person]
            [<simongray> :foaf/name "Simon Gray"]
            [<simongray> :foaf/workplaceHomepage "<https://nors.ku.dk/ansatte/?id=428973&vis=medarbejder>"]
            [<simongray> :foaf/homepage <simongray>]
            [<simongray> :foaf/weblog "<https://simon.grays.blog>"]
            [<cst> :rdf/type :foaf/Group]
            [<cst> :foaf/name (da "Center for Sprogteknologi")]
            [<cst> :foaf/name (en "Centre for Language Technology")]
            [<cst> :rdfs/comment (da "Centret er en del af Københavns universitet.")]
            [<cst> :rdfs/comment (en "The centre is part of the University of Copenhagen.")]
            [<cst> :foaf/homepage <cst>]
            [<cst> :foaf/homepage "<https://cst.ku.dk>"]
            [<cst> :foaf/member <simongray>]
            [<dsl> :rdf/type :foaf/Group]
            [<dsl> :foaf/name (da "Det Danske Sprog- og Litteraturselskab")]
            [<dsl> :foaf/name (en "The Society for Danish Language and Literature")]
            [<dsl> :foaf/homepage <dsl>]})
   ;; NOTE: the dcat:Dataset typing of DDS and COR ensures that they are listed
   ;; on the metadata page along with the DanNet dataset (GitHub issue #178).
   'dds #{[<dds> :rdf/type :dcat/Dataset]
          [<dds> :rdfs/label "DDS"]
          [<dds> :dc/title "DDS"]
          ;; The DDS and COR RDF datasets are regenerated with every DanNet
          ;; release, so their issued/version metadata tracks the release.
          [<dds> :dc/issued new-release]
          [<dds> :owl/versionInfo new-release]
          [<dds> :dc/description (en "The Danish Sentiment Lexicon")]
          [<dds> :dc/description (da "Det Danske Sentimentleksikon")]
          [<dds> :dc/contributor <cst>]
          [<dds> :dc/contributor <dsl>]
          ;; The CC BY-SA 4.0 rdfs:label is asserted in the 'dn map above; DDS
          ;; reuses the same licence resource, so only the links are added here.
          [<dds> :dc/license "<https://creativecommons.org/licenses/by-sa/4.0/>"]
          [<dds> :dc/rights (en "Copyright © Det Danske Sprog- og Litteraturselskab & "
                                "Centre for Language Technology (University of Copenhagen); "
                                "licensed under CC BY-SA 4.0 (https://creativecommons.org/licenses/by-sa/4.0/).")]
          [<dds> :dc/rights (da "Copyright © Det Danske Sprog- og Litteraturselskab & "
                                "Center for Sprogteknologi (Københavns Universitet); "
                                "udgives under CC BY-SA 4.0 (https://creativecommons.org/licenses/by-sa/4.0/).")]
          [<dds> :rdfs/seeAlso (prefix/uri->rdf-resource "https://github.com/dsldk/danish-sentiment-lexicon")]
          [<dds> :dcat/downloadURL (prefix/uri->rdf-resource dds-zip-uri)]}
   'cor #{[<cor> :rdf/type :dcat/Dataset]
          [<cor> :rdfs/label "COR"]
          [<cor> :dc/title "COR"]
          [<cor> :dc/issued new-release]
          [<cor> :owl/versionInfo new-release]
          [<cor> :dc/contributor <cst>]
          [<cor> :dc/contributor <dsl>]
          [<cor> :dc/contributor <dsn>]
          ;; COR upstream is only partly CC0: we consume ONLY the CC0 resources
          ;; (COR₁ 1.02 and COR.EXT 1.0). COR.SEM.EXT is CC BY-NC-ND and must NOT
          ;; be added here without revisiting the licence (issue #96).
          [<cor> :dc/license "<https://creativecommons.org/publicdomain/zero/1.0/>"]
          ["<https://creativecommons.org/publicdomain/zero/1.0/>" :rdfs/label "CC0 1.0"]
          [<cor> :dc/description (en "The Central Word Registry.")]
          [<cor> :dc/description (da "Det Centrale Ordregister.")]
          [<cor> :rdfs/seeAlso (prefix/uri->rdf-resource "https://dsn.dk/sprogets-udvikling/sprogteknologi-og-fagsprog/cor/")]
          [<dsn> :rdf/type :foaf/Group]
          [<dsn> :foaf/name (da "Dansk Sprognævn")]
          [<dsn> :foaf/name (en "The Danish Language Council")]
          [<dsn> :foaf/homepage <dsn>]
          [<cor> :dcat/downloadURL (prefix/uri->rdf-resource cor-zip-uri)]}})

(defn- count-type
  "Count the number of instances of `rdf-type` in `model`."
  [^Model model rdf-type]
  (txn/transact model
    (count (iterator-seq
             (.listStatements model nil RDF/type
                              (.createResource model (prefix/kw->uri rdf-type)))))))

(defn- triple-count
  "Count the total number of triples in `model`."
  [^Model model]
  (txn/transact model
    (.size model)))

(defn- avg
  "The ratio of `n` to `d` rounded to two decimal places; nil when undefined."
  [n d]
  (when (pos? d)
    (/ (Math/round (* 100.0 (/ n d))) 100.0)))

(h/defn add-dataset-statistics!
  "Compute and add statistics for the DanNet, DDS, and COR dataset resources
  in `dataset`: LIME lexicon metadata mirroring what the OEWN provides for
  <https://en-word.net/> (GitHub issue #178) plus VoID triple counts.

  This is a permanent part of the bootstrap process: it must run AFTER the
  release changes so that the statistics reflect the data actually being
  exported. Stale statistics never survive a rebuild since update-metadata!
  removes every dataset resource triple during the initial import."
  [dataset]
  (let [dn-model  (db/get-model dataset prefix/dn-uri)
        dds-model (db/get-model dataset prefix/dds-uri)
        cor-model (db/get-model dataset prefix/cor-uri)
        ;; dn: words are typed ontolex:Word or ontolex:MultiwordExpression --
        ;; never ontolex:LexicalEntry directly; COR additionally has affixes.
        entries   (+ (count-type dn-model :ontolex/Word)
                     (count-type dn-model :ontolex/MultiwordExpression))
        senses    (count-type dn-model :ontolex/LexicalSense)
        concepts  (count-type dn-model :ontolex/LexicalConcept)]
    (doseq [[^Model model triples]
            [[dn-model [[<dn> :lime/language "da"]
                        [<dn> :lime/lexicalEntries entries]
                        [<dn> :lime/lexicalizations senses]
                        [<dn> :lime/concepts concepts]
                        (when-let [x (avg senses entries)]
                          [<dn> :lime/avgAmbiguity x])
                        (when-let [x (avg senses concepts)]
                          [<dn> :lime/avgSynonymy x])
                        [<dn> :void/triples (triple-count dn-model)]]]
             [dds-model [[<dds> :void/triples (triple-count dds-model)]]]
             [cor-model [[<cor> :lime/language "da"]
                         [<cor> :lime/lexicalEntries
                          (+ (count-type cor-model :ontolex/Word)
                             (count-type cor-model :ontolex/MultiwordExpression)
                             (count-type cor-model :ontolex/Affix))]
                         [<cor> :void/triples (triple-count cor-model)]]]]
            :let [triples' (remove nil? triples)]]
      (txn/transact-exec model
        (t/log! {:level :info
                 :id    :dannet.bootstrap/dataset-statistics
                 :data  {:statistics (vec triples')}}
                "Adding dataset statistics")
        (db/safe-add! (.getGraph model) triples')))))
