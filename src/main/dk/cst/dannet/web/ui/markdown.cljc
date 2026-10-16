(ns dk.cst.dannet.web.ui.markdown
  (:require #?(:clj [dk.cst.dannet.shared :as shared])
            #?(:clj [nextjournal.markdown :as md])
            #?(:clj [nextjournal.markdown.transform :as md.transform])))

;; Markdown is only parsed on the server. The browser's parser (markdown-it)
;; turns bare domains such as wordnet.dk into links and the server's parser
;; does not, so parsing on both sides breaks React hydration.
#?(:clj
   (do
     (def renderers
       (assoc md.transform/default-hiccup-renderers
         ;; A fragment, since React wants keys for the default seq.
         :plain (fn [ctx {:keys [content]}]
                  (into [:<>] (map (partial md.transform/->hiccup ctx)) content))))

     (defn- md->hiccup*
       [markdown-text]
       (->> markdown-text
            (md/parse* (assoc md/empty-doc
                         :text->id+emoji-fn
                         (comp #(assoc {} :id %) shared/text->slug md/node->text)))
            (md/->hiccup renderers)))

     (def md->hiccup
       (memoize md->hiccup*))))

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
