(ns dk.cst.dannet.web.ui.markdown
  (:require [dk.cst.dannet.shared :as shared]
            [nextjournal.markdown :as md]
            [nextjournal.markdown.transform :as md.transform]))

(def renderers
  (assoc md.transform/default-hiccup-renderers
    ;; Clerk likes to ignore alt text and produce <figure> tags,
    ;; so we need to intercept the regular image rendering to produce
    ;; accessible images.
    :image (fn [{:as ctx ::keys [parent]}
                {:as node :keys [attrs content]}]
             (let [alt (-> (filter (comp #{:text} :type) content)
                           (first)
                           (get :text))]
               [:img (assoc attrs :alt alt)]))))

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
