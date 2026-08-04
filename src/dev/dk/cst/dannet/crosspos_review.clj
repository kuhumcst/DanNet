(ns dk.cst.dannet.crosspos-review
  "Regenerate the cross-PoS review CSVs in doc/crosspos/ from the live graph.

  These replace a hand-assembled spreadsheet that had silently drifted from the
  data (it was missing two rows of the 2d set). Everything here is derived from
  the graph instead, with manually entered columns merged forward from the
  existing CSVs so review work is never lost.

  Usage from the REPL, with a built database:

    (regenerate! (:dataset @dk.cst.dannet.web.resources/db))"
  (:require [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [dk.cst.dannet.db :as db]
            [dk.cst.dannet.db.query :as q]
            [dk.cst.dannet.prefix :as prefix]))

(def files
  {:2d "doc/crosspos/2d-cross-pos-taxonomy.csv"
   :a4 "doc/crosspos/a4-deferred-crosspos.csv"})

;; Mirrors the exclusions in bootstrap/fix-verb-phrase-pos!, kept in step by
;; check-counts! rather than by a shared def: the pipeline hashes that
;; function's own form to decide when to rebuild, so a reference to a def
;; elsewhere would not trigger one.
(def verb-phrase-exclusions
  #{:dn/synset-27542                                        ; {over kors}
    :dn/synset-27572                                        ; {i pleje}
    :dn/synset-30501})                                      ; {skåret ... over samme læst}

(def second-order
  "Artificial top-ontology node whose only word has empty-IRI PoS values."
  :dn/synset-42970)

(def a4-groups
  {:dn/synset-8143   "language" :dn/synset-8091 "language" :dn/synset-8109 "language"
   :dn/synset-7878   "register" :dn/synset-8079 "register"
   :dn/synset-48279  "person" :dn/synset-2119 "person" :dn/synset-6217 "person"
   :dn/synset-48734  "nominal idiom" :dn/synset-1478 "nominal idiom"
   :dn/synset-116    "nominal idiom"})

(defn- index
  "Build {synset {:label .. :pos #{..} :lemmas #{..}}}.

  PoS and lemmas are queried separately on purpose: a word may carry a
  partOfSpeech without a canonicalForm/writtenRep, and joining the two would
  silently drop such synsets from the PoS comparison."
  [g]
  (let [labels (into {} (map (juxt '?s (comp str '?l)))
                     (q/run g '[:bgp [?s :rdfs/label ?l]]))
        pos    (q/run g '[:bgp
                          [?synset :ontolex/lexicalizedSense ?sense]
                          [?word :ontolex/sense ?sense]
                          [?word :wn/partOfSpeech ?pos]])
        lemmas (q/run g '[:bgp
                          [?synset :ontolex/lexicalizedSense ?sense]
                          [?word :ontolex/sense ?sense]
                          [?word :ontolex/canonicalForm ?form]
                          [?form :ontolex/writtenRep ?rep]])]
    (as-> (into {} (map (fn [[s l]] [s {:label l}])) labels) $
          (reduce (fn [m {:syms [?synset ?pos]}]
                    (update-in m [?synset :pos] (fnil conj #{}) ?pos))
                  $ pos)
          (reduce (fn [m {:syms [?synset ?rep]}]
                    (update-in m [?synset :lemmas] (fnil conj #{}) (str ?rep)))
                  $ lemmas))))

(defn- pairs
  "Every wn:hypernym pair the HypernymPOSShape flags, i.e. any pair whose
  synsets are lexicalized by words disagreeing in PoS."
  [g idx]
  (->> (q/run g '[:bgp [?s :wn/hypernym ?o]])
       (keep (fn [{:syms [?s ?o]}]
               (let [a (get-in idx [?s :pos]) b (get-in idx [?o :pos])]
                 (when (and (str/starts-with? (str ?o) ":dn/")
                            (seq a) (seq b) (not= 1 (count (into a b))))
                   {:source ?s :target ?o :spos a :tpos b}))))))

(defn partition-pairs
  "Split the flagged pairs into the four disjoint groups documented in
  doc/crosspos/crosspos-automatic-processing.md."
  [idx flagged]
  (let [disjoint? #(empty? (set/intersection (:spos %) (:tpos %)))
        phrase?   (fn [{:keys [source spos tpos]}]
                    (and (= spos #{:wn/noun}) (= tpos #{:wn/verb})
                         (let [ls (get-in idx [source :lemmas])]
                           (and (seq ls) (every? #(str/includes? % " ") ls)))))
        top?      #(or (= second-order (:source %)) (= second-order (:target %)))
        dis       (filter disjoint? flagged)]
    {:2a (filter phrase? dis)
     :2b (remove disjoint? flagged)
     :2c (filter top? dis)
     :2d (remove #(or (phrase? %) (top? %)) dis)}))

(defn deferred-crosspos
  "The dns:crossPoSHypernym triples deliberately retained for review."
  [g]
  (->> (q/run g '[:bgp [?s :dns/crossPoSHypernym ?o]])
       (map (fn [{:syms [?s ?o]}] {:source ?s :target ?o}))))

(defn- uri [k] (str (prefix/kw->uri k)))

(defn- read-csv
  "Existing CSV as {[source-uri target-uri] row-map}, for merging manual columns."
  [path]
  (when (.exists (io/file path))
    (with-open [r (io/reader path)]
      (let [[hdr & rows] (doall (csv/read-csv r))]
        (into {} (for [row rows
                       :let [m (zipmap hdr row)]]
                   [[(get m "source URI") (get m "target URI")] m]))))))

(defn- write-csv! [path hdr rows]
  (io/make-parents path)
  (with-open [w (io/writer path)]
    (csv/write-csv w (cons hdr (map (fn [m] (mapv #(get m % "") hdr)) rows)))))

(def ^:private manual-2d ["retarget candidates" "decision" "retarget to" "comment"])

(defn- row [idx {:keys [source target]} extra]
  (merge {"source"     (get-in idx [source :label])
          "source URI" (uri source)
          "source PoS" (str/join "+" (sort (map name (get-in idx [source :pos]))))
          "target"     (get-in idx [target :label])
          "target URI" (uri target)
          "target PoS" (str/join "+" (sort (map name (get-in idx [target :pos]))))}
         extra))

(defn- check-counts!
  "Fail loudly if the graph no longer matches the counts the pipeline asserts
  and the documentation cites.

  These are the counts of a *built* database, i.e. after make-release-changes!
  has run. 2a is 3 rather than 110 because the 107 corrected synsets are verbs
  by then and no longer match the noun-source criterion; the 3 that remain are
  exactly the hand-excluded ones."
  [groups deferred]
  (let [actual   (assoc (update-vals groups count) :deferred (count deferred))
        expected {:2a 3 :2b 149 :2c 52 :2d 285 :deferred 104}
        left     (set (map :source (:2a groups)))]
    (when (not= expected actual)
      (throw (ex-info "cross-PoS group counts have changed; update the docs"
                      {:expected expected :actual actual})))
    (when (not= left verb-phrase-exclusions)
      (throw (ex-info "unexpected 2a remainder; the exclusions no longer match"
                      {:expected verb-phrase-exclusions :actual left})))
    actual))

(defn regenerate!
  "Rewrite all three review CSVs from `dataset`, preserving manual columns."
  [dataset]
  (let [g        (db/get-graph dataset prefix/dn-uri)
        idx      (index g)
        flagged  (pairs g idx)
        deferred (deferred-crosspos g)
        groups   (partition-pairs idx flagged)
        counts   (check-counts! groups deferred)

        ;; --- 2d: merge the precomputed retarget columns forward ---
        prev   (read-csv (:2d files))
        n-by   (frequencies (map (comp uri :target) (:2d groups)))
        rows2d (->> (:2d groups)
                    (map (fn [p]
                           (let [k [(uri (:source p)) (uri (:target p))]
                                 m (get prev k)]
                             (row idx p (merge (select-keys m manual-2d)
                                               {"n" (str (n-by (uri (:target p))))})))))
                    (sort-by (juxt #(- (parse-long (get % "n"))) #(get % "target") #(get % "source"))))

        ;; --- 2a: NOT regenerated ---
        ;; Once fix-verb-phrase-pos! has run, the 107 corrected synsets are
        ;; verbs and no longer match the criterion, so the 110-row candidate
        ;; list cannot be reconstructed from a built database. The CSV is a
        ;; static record of a decision already applied; check-counts! verifies
        ;; that the 3 synsets still matching are exactly the excluded ones.

        ;; --- a4: the retained dns:crossPoSHypernym pairs ---
        prevA4 (read-csv (:a4 files))
        rowsA4 (->> deferred
                    (map (fn [{:keys [target] :as p}]
                           (let [m (get prevA4 [(uri (:source p)) (uri target)])]
                             (row idx p {"group"    (get a4-groups target "?")
                                         "decision" (get m "decision" "")
                                         "comment"  (get m "comment" "")}))))
                    (sort-by (juxt #(get % "group") #(get % "target") #(get % "source"))))]

    (write-csv! (:2d files)
                ["target" "target URI" "target PoS" "n" "source" "source URI" "source PoS"
                 "retarget candidates" "decision" "retarget to" "comment"]
                rows2d)
    (write-csv! (:a4 files)
                ["source" "source URI" "source PoS" "target" "target URI" "target PoS"
                 "group" "decision" "comment"]
                rowsA4)
    counts))

(comment
  (regenerate! (:dataset @dk.cst.dannet.web.resources/db))
  #_.)
