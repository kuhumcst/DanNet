(ns dk.cst.dannet.db.bootstrap
  "Bootstrapping DanNet by mapping the old DanNet CSV export to Ontolex-lemon
  and Global WordNet Assocation relations.

  The following relations are excluded as part of the import:
    - eq_has_synonym:   mapping to an old version of Princeton Wordnet
    - eq_has_hyponym:   mapping to an old version of Princeton Wordnet
    - eq_has_hyperonym: mapping to an old version of Princeton Wordnet
    - used_for_qualby:  not in use, just 1 full triple + 3 broken ones

  Inverse relations are not explicitly created, but rather handled by way of
  inference using a Jena OWL reasoner.

  See: https://www.w3.org/2016/05/ontolex"
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.math.combinatorics :as combo]
            [ont-app.vocabulary.lstr :refer [->LangStr]]
            [dk.cst.dannet.hash :as h]
            [dk.cst.dannet.prefix :as prefix]))

(defn da
  [& s]
  (->LangStr (apply str s) "da"))

(defn en
  [& s]
  (->LangStr (apply str s) "en"))

(defn fix-ellipsis
  [definition]
  (str/replace definition " ..." "…"))

(def <simongray>
  (prefix/uri->rdf-resource "https://simongray.dk"))

(def <cst>
  (prefix/uri->rdf-resource "https://cst.dk"))

(def <dsl>
  (prefix/uri->rdf-resource "https://dsl.dk"))

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
  "<https://ordregister.dk>")

(def dn-zip-basic-uri
  (prefix/dataset-uri "rdf" 'dn))

(def dn-zip-merged-uri
  (prefix/dataset-uri "rdf" 'dn "merged"))

(def dn-zip-complete-uri
  (prefix/dataset-uri "rdf" 'dn "complete"))

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

(def release-date
  "2023-06-01")

(def dc-issued-old
  "2013-01-03")

(defn see-also
  "Generate rdfs:seeAlso backlink triples for `rdf-resources`."
  [& rdf-resources]
  (set (for [[k v] (combo/permuted-combinations rdf-resources 2)]
         [k :rdfs/seeAlso v])))

(h/def metadata-triples
  "Metadata for the different datasets is defined here.

  The Dublin Core Terms NS is used below which supersedes
  the older DC namespace (see: https://www.dublincore.org/schemas/rdfs/ )."
  (set/union
    #{[<dns> :rdf/type :owl/Ontology]
      [<dns> :vann/preferredNamespacePrefix "dns"]
      [<dns> :vann/preferredNamespaceUri (prefix/prefix->uri 'dns)]
      [<dns> :dc/title (en "DanNet schema")]
      [<dns> :dc/title (da "DanNet-skema")]
      [<dns> :dc/description (en "Schema for DanNet-specific relations.")]
      [<dns> :dc/description (da "Skema for DanNet-specifikke relationer.")]
      [<dns> :dc/issued release-date]
      [<dns> :dc/contributor <simongray>]
      [<dns> :dc/contributor <cst>]
      [<dns> :dc/contributor <dsl>]
      [<dns> :dc/publisher <cst>]
      [<dns> :foaf/homepage "<https://cst.ku.dk/projekter/dannet>"]
      [<dns> :dc/rights (en "Copyright © Centre for Language Technology (University of Copenhagen) & The Society for Danish Language and Literature.")]
      [<dns> :dc/rights (da "Copyright © Center for Sprogteknologi (Københavns Universitet) & Det Danske Sprog- og Litteraturselskab.")]
      [<dns> :dc/license "<https://creativecommons.org/licenses/by-sa/4.0/>"]

      [<dnc> :rdf/type :owl/Ontology]                       ;TODO: :skos/ConceptScheme instead?
      [<dnc> :vann/preferredNamespacePrefix "dnc"]
      [<dnc> :vann/preferredNamespaceUri (prefix/prefix->uri 'dnc)]
      [<dnc> :dc/title (en "DanNet concepts")]
      [<dnc> :dc/title (da "DanNet-koncepter")]
      [<dnc> :dc/description (en "Schema containing all DanNet/EuroWordNet concepts.")]
      [<dnc> :dc/description (da "Skema der indholder alle DanNet/EuroWordNet-koncepter.")]
      [<dnc> :dc/issued release-date]
      [<dnc> :dc/contributor <simongray>]
      [<dnc> :dc/contributor <cst>]
      [<dnc> :dc/contributor <dsl>]
      [<dnc> :dc/publisher <cst>]
      [<dnc> :foaf/homepage "<https://cst.ku.dk/projekter/dannet>"]
      [<dnc> :dc/rights (en "Copyright © Centre for Language Technology (University of Copenhagen) & The Society for Danish Language and Literature.")]
      [<dnc> :dc/rights (da "Copyright © Center for Sprogteknologi (Københavns Universitet) & Det Danske Sprog- og Litteraturselskab.")]
      [<dnc> :dc/license "<https://creativecommons.org/licenses/by-sa/4.0/>"]

      [<dn> :rdf/type :dcat/Dataset]
      [<dn> :rdf/type :lime/Lexicon]
      [<dn> :vann/preferredNamespacePrefix "dn"]
      [<dn> :vann/preferredNamespaceUri (prefix/prefix->uri 'dn)]
      [<dn> :rdfs/label "DanNet"]
      [<dn> :dc/title "DanNet"]
      [<dn> :dc/language "da"]
      [<dn> :dc/description (en "The Danish WordNet.")]
      [<dn> :dc/description (da "Det danske WordNet.")]
      [<dn> :dc/issued release-date]
      [<dn> :dc/contributor <simongray>]
      [<dn> :dc/contributor <cst>]
      [<dn> :dc/contributor <dsl>]
      [<dn> :dc/publisher <cst>]
      [<dn> :foaf/homepage "<https://cst.ku.dk/projekter/dannet>"]
      [<dn> :schema/email "simongray@hum.ku.dk"]
      [<dn> :owl/versionInfo release-date]
      [<dn> :dc/rights (en "Copyright © Centre for Language Technology (University of Copenhagen) & "
                           "The Society for Danish Language and Literature; "
                           "licensed under CC BY-SA 4.0 (https://creativecommons.org/licenses/by-sa/4.0/).")]
      [<dn> :dc/rights (da "Copyright © Center for Sprogteknologi (Københavns Universitet) & "
                           "Det Danske Sprog- og Litteraturselskab; "
                           "udgives under CC BY-SA 4.0 (https://creativecommons.org/licenses/by-sa/4.0/).")]
      [<dn> :dc/license "<https://creativecommons.org/licenses/by-sa/4.0/>"]
      ["<https://creativecommons.org/licenses/by-sa/4.0/>" :rdfs/label "CC BY-SA 4.0"]

      [<dds> :rdfs/label "DDS"]
      [<dds> :dc/title "DDS"]
      [<dds> :dc/description (en "The Danish Sentiment Lexicon")]
      [<dds> :dc/description (da "Det Danske Sentimentleksikon")]
      [<dds> :dc/contributor <cst>]
      [<dds> :dc/contributor <dsl>]
      [<dds> :rdfs/seeAlso (prefix/uri->rdf-resource "https://github.com/dsldk/danish-sentiment-lexicon")]

      [<cor> :rdfs/label "COR"]
      [<cor> :dc/title "COR"]
      [<cor> :dc/contributor <cst>]
      [<cor> :dc/contributor <dsl>]
      [<cor> :dc/contributor <dsn>]
      [<cor> :dc/description (en "The Central Word Registry.")]
      [<cor> :dc/description (da "Det Centrale Ordregister.")]
      [<cor> :rdfs/seeAlso (prefix/uri->rdf-resource "https://dsn.dk/sprogets-udvikling/sprogteknologi-og-fagsprog/cor/")]

      ;; Contributors/publishers
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
      [<dsl> :foaf/homepage <dsl>]
      [<dsn> :rdf/type :foaf/Group]
      [<dsn> :foaf/name (da "Dansk Sprognævn")]
      [<dsn> :foaf/name (en "The Danish Language Council")]
      [<dsn> :foaf/homepage <dsn>]}

    (see-also <dn> <dns> <dnc>)
    (see-also <dn> <dds>)
    (see-also <dn> <cor>)
    (see-also <cst> <dsl> <dsn>)

    ;; Downloads
    #{[<dn> :dcat/downloadURL (prefix/uri->rdf-resource dn-zip-basic-uri)]
      [<dn> :dcat/downloadURL (prefix/uri->rdf-resource dn-zip-merged-uri)]
      [<dn> :dcat/downloadURL (prefix/uri->rdf-resource dn-zip-complete-uri)]
      [<dn> :dcat/downloadURL (prefix/uri->rdf-resource dn-zip-csv-uri)]
      [<cor> :dcat/downloadURL (prefix/uri->rdf-resource cor-zip-uri)]
      [<dds> :dcat/downloadURL (prefix/uri->rdf-resource dds-zip-uri)]
      [<dns> :dcat/downloadURL (prefix/uri->rdf-resource dns-schema-uri)]
      [<dnc> :dcat/downloadURL (prefix/uri->rdf-resource dnc-schema-uri)]}))