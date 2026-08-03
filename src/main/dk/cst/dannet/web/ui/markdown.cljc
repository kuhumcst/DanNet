(ns dk.cst.dannet.web.ui.markdown
  (:require [dk.cst.dannet.shared :as shared]
            [nextjournal.markdown :as md]
            [nextjournal.markdown.transform :as md.transform]))

(def renderers
  md.transform/default-hiccup-renderers)

(defn- md->hiccup*
  [markdown-text]
  (->> markdown-text
       (md/parse* (assoc md/empty-doc
                    :text->id+emoji-fn
                    (comp #(assoc {} :id %) shared/text->slug md/node->text)))
       (md/->hiccup renderers)))

(def md->hiccup
  (memoize md->hiccup*))

(defn hiccup->title*
  "Find the title string located in the first :h1 element in `hiccup`."
  [hiccup]
  (->> (tree-seq vector? rest hiccup)
       (reduce (fn [_ x]
                 (when (= :h1 (first x))
                   (let [node (last x)]
                     (reduced (if (= :img (first node))
                                (:alt (second node))
                                node)))))
               nil)))

(def hiccup->title
  (memoize hiccup->title*))

(comment
  (hiccup->title* (md/->hiccup (slurp "pages/downloads-da.md")))
  (hiccup->title* nil)
  #_.)
