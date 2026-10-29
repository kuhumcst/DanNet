(ns dk.cst.dannet.web.sparql-test
  "The pages of the SPARQL editor: which queries it pages through, what it
  leaves to a query's own LIMIT and OFFSET, and how the result cache and the
  choice of model treat them."
  (:require [clojure.test :refer [deftest is]]
            [dk.cst.theophrastus :as theo]
            [dk.cst.theophrastus.endpoint :as endpoint]
            [dk.cst.dannet.web.sparql :as sparql])
  (:import [org.apache.jena.query Query]
           [org.apache.jena.sparql.resultset ResultSetMem]))

(defn- page
  "The LIMIT and OFFSET of the `query` on the page of the `params`, nil for
  none, and the :limit, :offset and :lookahead? of the page."
  [query params]
  (let [{:keys [^Query sparql] :as op} (sparql/page {:sparql (theo/query query)}
                                                    params)]
    [(when (.hasLimit sparql) (.getLimit sparql))
     (when (.hasOffset sparql) (.getOffset sparql))
     (select-keys op [:limit :offset :lookahead?])]))

(deftest paged-select
  (is (= [11 20 {:limit 10 :offset 20 :lookahead? true}]
         (page "SELECT * { ?s ?p ?o }" {:limit "10" :offset "20"}))
      "one row more than the page, to look ahead by")
  (is (= [10 nil {:limit 10 :offset 0 :lookahead? false}]
         (page "SELECT * { ?s ?p ?o }" {:limit "10" :lookahead "false"})))
  (is (= [101 nil {:limit 100 :offset 0 :lookahead? true}]
         (page "SELECT * { ?s ?p ?o }" {:limit "500"}))
      "never more than max-results")
  (is (= [2 nil {:limit 1 :offset 0 :lookahead? true}]
         (page "SELECT * { ?s ?p ?o }" {:limit "-3" :offset "x"}))
      "nonsense parameters"))

(deftest own-limit-and-offset
  (is (= [7 nil {:limit 7 :offset 0 :lookahead? false}]
         (page "SELECT * { ?s ?p ?o } LIMIT 7" {:limit "10"})))
  (is (= [100 nil {:limit 100 :offset 0 :lookahead? false}]
         (page "SELECT * { ?s ?p ?o } LIMIT 500" {})))
  (is (= [100 5 {:limit 100 :offset 20 :lookahead? false}]
         (page "SELECT * { ?s ?p ?o } OFFSET 5" {:offset "20"}))
      "the query's own OFFSET stays")
  (is (= [100 nil {:limit 100 :offset 0 :lookahead? false}]
         (page "CONSTRUCT WHERE { ?s ?p ?o }" {:limit "10"}))
      "only a SELECT is paged"))

(def prefixes
  {:ex "<http://example.org/>"})

(defn- ->db
  "A db of the `triples`."
  [triples]
  (doto (theo/db {:prefixes prefixes :transactional? false})
    (theo/transact! triples)))

(def dbs
  "A base model and an inference model, which alone knows what ex:c is."
  (delay
    (let [triples [[:ex/a :ex/p :ex/b] [:ex/a :ex/p :ex/c] [:ex/a :ex/p :ex/d]]]
      {:base      (->db triples)
       :inference (->db (conj triples [:ex/c :ex/p :ex/e]))})))

(defn- run
  "The number of rows, :has-more? and :inference? of the `query` on the page
  of the `params`, run through the cache, with the other keys of `op`."
  [query params & {:as op}]
  (let [{:keys [result] :as ran}
        (sparql/run-cached @dbs
                           (-> {:query             query
                                :params            params
                                :default-graph-uri []
                                :named-graph-uri   []}
                               (merge op)
                               (endpoint/prepare {:prefixes prefixes})
                               (sparql/page params))
                           {})]
    [(.size ^ResultSetMem result) (:has-more? ran) (:inference? ran)]))

(deftest result-cache
  (sparql/reset-cache!)
  (is (= [1 true false] (run "SELECT ?o { ex:a ex:p ?o }" {:limit "1"})))
  (is (= [2 nil false] (run "SELECT ?o { ex:a ex:p ?o } LIMIT 2" {}))
      "the query that the page of 1 ran, with the row it looked ahead by")
  (is (= [1 true false] (run "SELECT ?o { ex:a ex:p ?o }" {:limit "1"}))
      "the page of 1 once more, from the cache")
  (is (= [0 nil true] (run "SELECT ?o { ex:a ex:p ?o } LIMIT 2" {}
                           :default-graph-uri ["http://example.org/g"]))
      "a graph that the db doesn't have"))

(deftest auto-inference
  (sparql/reset-cache!)
  (is (= [1 nil true] (run "SELECT ?o { ex:c ex:p ?o }" {:limit "10"}))
      "the inference model when the base model has no results")
  (is (= [0 nil false] (run "SELECT ?o { ex:c ex:p ?o }"
                            {:limit "10" :offset "10"}))
      "only on the first page"))

(deftest normalize
  (is (= (str "PREFIX  ex:   <http://example.org/>\n\n"
              "SELECT  ?o\nWHERE\n  { ex:a  ex:p  ?o }")
         (sparql/normalize
           (theo/query "SELECT ?o { ex:a ex:p ?o }"
                       (assoc prefixes
                         :unused "<http://example.org/unused#>"))))
      "the PREFIX lines of the prefixes that the query uses"))
