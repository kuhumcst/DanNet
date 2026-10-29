(ns dk.cst.dannet.web.sparql-test
  "The pages of the SPARQL editor: which queries it pages through, and what
  it leaves to a query's own LIMIT and OFFSET."
  (:require [clojure.test :refer [deftest is]]
            [dk.cst.theophrastus :as theo]
            [dk.cst.dannet.web.sparql :as sparql])
  (:import [org.apache.jena.query Query]))

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
