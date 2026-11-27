(ns dk.cst.dannet.web.edit
  "The routes of the editor: what edit mode asks the server for besides the
  pages of the site (see dk.cst.dannet.web.resources). The editor is a
  preview, so the service only has these routes in development.

  The pending changes are in the browser. Nothing here writes to the
  database."
  (:require [io.pedestal.http.route :refer [decode-query-part]]
            [dk.cst.theophrastus :as theo]
            [dk.cst.dannet.change :as change]
            [dk.cst.dannet.db.change :as db.change]
            [dk.cst.dannet.db.query :as q]
            [dk.cst.dannet.db.query.operation :as op]
            [dk.cst.dannet.db.search :as search]
            [dk.cst.dannet.db.shapes :as shapes]
            [dk.cst.dannet.editable :as editable]
            [dk.cst.dannet.entry :as entry]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.web.i18n :as i18n]
            [dk.cst.dannet.web.instance :as instance]
            [dk.cst.dannet.web.resources :as res]
            [dk.cst.dannet.web.response :as resp]))

(defn query-param
  "The query parameter `k` of the request in `ctx`, decoded."
  [ctx k]
  (some-> (get-in ctx [:request :query-params k]) (decode-query-part)))

(def ontological-types-ic
  "Every ontological type with its label, sorted by label, for the editor to
  search among in the browser."
  {:name  ::ontological-types
   :enter (fn [ctx]
            (assoc ctx
              :content
              {:ontological-types
               (->> (theo/q (:inference @instance/db) op/ontological-types)
                    (map (fn [{:syms [?t ?label]}]
                           {:rdf/about ?t :rdfs/label ?label}))
                    (sort-by (comp str :rdfs/label))
                    (vec))}))})

(defn prefix-lemmas
  "The string `s` and the first few words that the autocompletion has for
  it, i.e. words that start with `s`."
  [s]
  (->> (some-> (not-empty (shared/search-string s))
               (instance/autocomplete))
       (map #(if (vector? %) (first %) %))
       (take 10)
       (cons s)
       (distinct)))

(defn- prefix-synsets
  "The synsets in `g` of the words that start with the string `s`, as the
  search finds them: those of the word `s` itself first, then those of the
  first few words that the autocompletion has for `s`, 30 at most."
  [g s]
  (when-let [synsets (some->> (prefix-lemmas s)
                              (keep #(res/look-up-any-case g %))
                              (seq)
                              (reduce into))]
    (into (empty synsets) (take 30) synsets)))

(def synsets-ic
  "The synsets of the words that start with the lemma in the request, for
  the editor to choose between. A lemma with a single synset gives that
  synset, where the search page would go to its page."
  {:name  ::synsets
   :enter (fn [ctx]
            (let [lemma (query-param ctx :lemma)]
              (assoc ctx
                :content {:synsets (when (not-empty lemma)
                                     (prefix-synsets (:inference @instance/db)
                                                     lemma))})))})

(def words-ic
  "The words of DanNet that start with the lemma in the request, for the
  editor to choose between: the word that is the lemma itself first, then
  the first few words that the autocompletion has for it, 30 at most."
  {:name  ::words
   :enter (fn [ctx]
            (let [lemma (query-param ctx :lemma)]
              (assoc ctx
                :content
                {:words (when (not-empty lemma)
                          (let [{:keys [dataset inference]} @instance/db]
                            (->> (prefix-lemmas lemma)
                                 (search/words
                                   (theo/named-graph dataset prefix/dn-graph)
                                   inference)
                                 (take 30))))})))})

(def entry-ic
  "What the editor needs to show a new entry of the class in the request,
  given as a prefixed name: what it reads from the shapes and the schemas
  for the class (see resources/editor-content), with the label of the class
  among the :entities."
  {:name  ::entry
   :enter (fn [ctx]
            (let [class (some-> (query-param ctx :class) (prefix/qname->kw))]
              (assoc ctx
                :content
                (when class
                  (update (res/editor-content #{class})
                          :entities merge
                          (q/resource-labels (:inference @instance/db)
                                             #{class}))))))})

(def removal-rules
  "What the rules of the editor say goes with an entry that is removed (see
  db.change/removal-parts): what a template links it to, e.g. the senses of
  a word, and the values that the build writes."
  (let [{:keys [entries derived derived-by-class]} editable/rules]
    {:links   (entry/links entries)
     :derived (into derived (mapcat val) derived-by-class)}))

(def removal-ic
  "What the editor needs to remove the entry in the request, given as a
  prefixed name: the parts that take it out of the dn: graph (see
  db.change/removal-parts), with the labels of the resources in them as
  :entities."
  {:name  ::removal
   :enter (fn [ctx]
            (let [id (some-> (query-param ctx :id) (prefix/qname->kw))
                  {:keys [dataset inference]} @instance/db
                  {:keys [orphans] :as removal}
                  (when id
                    (db.change/removal-parts
                      (theo/named-graph dataset prefix/dn-graph)
                      id
                      removal-rules))
                  parts   (cons removal (vals orphans))
                  triples (mapcat :triples parts)]
              (assoc ctx
                :content
                (when removal
                  (assoc removal
                    :entities (q/resource-labels
                                inference
                                (into (change/resources [{:retract triples}])
                                      (mapcat (comp keys :references))
                                      parts)))))))})

(def references-ic
  "How many triples of other entries point to each of the entries in the
  request, given as prefixed names with commas between them (see
  db.change/references), with the labels of the resources as :entities."
  {:name  ::references
   :enter (fn [ctx]
            (let [ids (some->> (query-param ctx :ids)
                               (re-seq #"[^,]+")
                               (map prefix/qname->kw))
                  {:keys [dataset inference]} @instance/db
                  counts (db.change/references
                           (theo/named-graph dataset prefix/dn-graph)
                           ids
                           removal-rules)]
              (assoc ctx
                :content
                {:references counts
                 :entities   (q/resource-labels
                               inference
                               (into (set (keys counts))
                                     (mapcat keys)
                                     (vals counts)))})))})

(def no-store-ic
  "Tell the browser not to keep the answer. An edit that is made from an
  answer has to be made from the data as it is now."
  {:name  ::no-store
   :leave (fn [ctx]
            (assoc-in ctx [:response :headers "Cache-Control"] "no-store"))})

(def changes-ic
  "The page that lists the pending changes of an editor. The changes are in
  the browser, so the page has no data of its own."
  {:name  ::changes
   :enter (fn [{:keys [request] :as ctx}]
            (let [languages (resp/request->languages request)]
              (assoc ctx
                :content {:languages languages}
                :page-meta {:title (i18n/da-en languages "Ændringer" "Changes")
                            :page  :changes})))})

(def check-ic
  "What the editorial shapes say about the changes in the body of the
  request, an EDN text as a change file has it (see shapes/check-changes).

  A change can break a rule of a resource that it doesn't name, e.g. one
  that refers to a removed resource. So the labels of the first hundred
  resources that the results are about come with them, as :entities."
  {:name  ::check
   :enter (fn [{:keys [request] :as ctx}]
            (let [{:keys [dataset inference]} @instance/db
                  check (shapes/check-changes
                          dataset
                          (db.change/read-changes (:body request)))
                  about (into #{}
                              (comp (keep :focus-node)
                                    (filter keyword?)
                                    (distinct)
                                    (take 100))
                              (:results check))]
              (assoc ctx
                :content {:check    check
                          :entities (q/resource-labels inference about)})))})

(def routes
  "The routes of the editor, by the paths that the browser asks for (see
  dk.cst.dannet.prefix)."
  #{[prefix/ontological-types-path
     :get [res/media-type-negotiation-ic
           ontological-types-ic
           res/response-body-ic]
     :route-name ::ontological-types]

    [prefix/synsets-path
     :get [res/media-type-negotiation-ic
           synsets-ic
           res/response-body-ic]
     :route-name ::synsets]

    [prefix/words-path
     :get [res/media-type-negotiation-ic
           words-ic
           res/response-body-ic]
     :route-name ::words]

    [prefix/entry-path
     :get [res/media-type-negotiation-ic
           entry-ic
           res/response-body-ic]
     :route-name ::entry]

    [prefix/removal-path
     :get [no-store-ic
           res/media-type-negotiation-ic
           removal-ic
           res/response-body-ic]
     :route-name ::removal]

    [prefix/references-path
     :get [no-store-ic
           res/media-type-negotiation-ic
           references-ic
           res/response-body-ic]
     :route-name ::references]

    [prefix/changes-path
     :get [res/negotiation-ic
           changes-ic
           res/response-body-ic]
     :route-name ::changes]

    [prefix/check-path
     :post [res/media-type-negotiation-ic
            check-ic
            res/response-body-ic]
     :route-name ::check]})
