(ns dk.cst.dannet.web.ui.codemirror
  "CodeMirror 6 interop for the SPARQL editor.

  Provides create/destroy/get/set for a CM6 EditorView configured with
  SPARQL syntax highlighting, line numbers, bracket matching, and other
  `basicSetup` defaults. Prefix blocks are folded by default on creation."
  (:require ["codemirror" :refer [basicSetup]]
            ["@codemirror/view" :refer [EditorView]]
            ["@codemirror/state" :refer [EditorState]]
            ["@codemirror/language" :refer [foldAll syntaxHighlighting LanguageSupport]]
            ["@lezer/highlight" :refer [classHighlighter styleTags tags]]
            ["codemirror-lang-sparql" :refer [SparqlLanguage]]))

(def ^:private sparql-ext
  "SPARQL language extension correcting/extending the upstream grammar's
  `styleTags`: assigns `tags.url` to `IriRef` (overriding the grammar's
  incorrect `tags.namespace`), and `tags.atom` to `PrefixedName`/
  `Pname_ln`/`Pname_ns` (QNames like wn:foo, left untagged upstream).
  `tags.atom` is used as the most semantically neutral available tag —
  none of the named tags fit QNames well, and the grammar emits them as
  single tokens so prefix and local name cannot be coloured separately."
  (let [extra-tags (styleTags #js {"IriRef"                         (.-url tags)
                                   "PrefixedName Pname_ln Pname_ns" (.-atom tags)})
        lang      (.configure SparqlLanguage #js {:props #js [extra-tags]})]
    (new LanguageSupport lang)))

(defn- ->change-listener
  "Return a CM6 update listener extension that calls `on-change-fn`
  with the new document string on every document change."
  [on-change-fn]
  (.of (.-updateListener EditorView)
       (fn [^js update]
         (when (.-docChanged update)
           (on-change-fn (.toString (.. update -state -doc)))))))

(defn create-editor!
  "Mount a CM6 editor on `parent-el` with `initial-doc`.

  `on-change-fn` is called with the new doc string on every change.
  Prefix declarations are folded by default after mount.
  Returns the EditorView instance."
  [parent-el initial-doc on-change-fn]
  (let [state (.create EditorState
                       #js {:doc        (or initial-doc "")
                            :extensions #js [basicSetup
                                             (syntaxHighlighting classHighlighter)
                                             sparql-ext
                                             (->change-listener on-change-fn)]})
        view  (EditorView. #js {:state state :parent parent-el})]
    (foldAll view)
    view))

(defn destroy-editor!
  "Destroy a CM6 `view`."
  [view]
  (when view
    (.destroy view)))

(defn get-doc
  "Return the current document content of `view` as a string."
  [view]
  (when view
    (.toString (.. view -state -doc))))

(defn set-doc!
  "Replace the entire document content of `view` with `text`."
  [view text]
  (when view
    (.dispatch view
               #js {:changes #js {:from   0
                                  :to     (.. view -state -doc -length)
                                  :insert (or text "")}})))

(defn fold-all!
  "Fold all foldable ranges in `view` (e.g. PREFIX declarations)."
  [view]
  (when view
    (foldAll view)))

(defn focus!
  "Focus `view`."
  [view]
  (when view
    (.focus view)))

(defn clear-editor-error!
  "Remove error styling and validation tooltip from `view`."
  [view]
  (when view
    (let [el (.-dom view)]
      (.remove (.-classList el) "cm-invalid")
      (some-> (.querySelector el ".cm-validation-error")
              (.remove)))))

(defn show-editor-error!
  "Add error styling and a validation tooltip to `view`,
  replacing any existing tooltip."
  [view msg]
  (when view
    (let [el  (.-dom view)
          tip (js/document.createElement "div")]
      (.add (.-classList el) "cm-invalid")
      (some-> (.querySelector el ".cm-validation-error")
              (.remove))
      (.add (.-classList tip) "cm-validation-error")
      (set! (.-textContent tip) msg)
      (.appendChild el tip))))
