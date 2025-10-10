(ns dk.cst.dannet.web.components.markdown
  (:require [nextjournal.markdown :as md]
            [nextjournal.markdown.transform :as md.transform]))

(def md->hiccup
  (memoize
    (partial md/->hiccup
             (assoc md.transform/default-hiccup-renderers
               ;; Clerk likes to ignore alt text and produce <figure> tags,
               ;; so we need to intercept the regular image rendering to produce
               ;; accessible images.
               :image (fn [{:as ctx ::keys [parent]}
                           {:as node :keys [attrs content]}]
                        (let [alt (-> (filter (comp #{:text} :type) content)
                                      (first)
                                      (get :text))]
                          [:img (assoc attrs :alt alt)]))))))

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
  (hiccup->title* (md/->hiccup (slurp "pages/about-da.md")))
  (hiccup->title* nil)
  #_.)
