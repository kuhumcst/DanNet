(ns dk.cst.dannet.web.ui.markdown
  "Markdown pages as hiccup: the server parses them and finds the title of each
  in its first <h1>."
  (:require [clojure.string :as str]
            #?(:clj [nextjournal.markdown :as md])
            #?(:clj [nextjournal.markdown.transform :as md.transform])))

(defn text->slug
  "Create a URL-safe slug from `text`, keeping only English alphanumerics
  separated by single dashes. Danish characters are transliterated first.

  NOTE: technically, HTML5 allows for most Unicode characters. However, the
        subsequent URL-encoding effort really isn't worth it."
  [text]
  (when (string? text)
    (-> text
        (str/trim)
        (str/lower-case)
        (str/replace "æ" "ae")
        (str/replace "ø" "oe")
        (str/replace "å" "aa")
        (str/replace #"[^a-z0-9]+" "-")
        (str/replace #"^-|-$" ""))))

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
                         (comp #(assoc {} :id %) text->slug md/node->text)))
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
