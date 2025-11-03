(ns dk.cst.dannet.web.ui.page
  "The different page types rendered in the DanNet UI."
  (:require [rum.core :as rum]
            [dk.cst.dannet.shared :as shared]
            [dk.cst.dannet.prefix :as prefix]
            [dk.cst.dannet.web.i18n :as i18n]
            [ont-app.vocabulary.lstr :as lstr]
            [dk.cst.dannet.web.ui.entity :as entity]
            [dk.cst.dannet.web.ui.table :as table]
            [dk.cst.dannet.web.ui.markdown :as mdc]))

;; TODO: superfluous DN:A4-ark http://localhost:3456/dannet/data/synset-48300
;; TODO: empty synset http://localhost:3456/dannet/data/synset-47272
;; TODO: equivalent class empty http://localhost:3456/dannet/external/semowl/InformationEntity
;; TODO: empty definition http://0.0.0.0:3456/dannet/data/synset-42955
(rum/defc entity
  [{:keys [entity k->label]
    :as   opts}]
  ;; TODO: could this transformation be moved to the backend?
  (let [inherited (->> (shared/setify (:dns/inherited entity))
                       (map (comp prefix/qname->kw first k->label))
                       (set))
        opts'     (assoc opts :inherited inherited)
        {:keys [full-screen?]} @shared/state]
    (if full-screen?
      [:article
       (entity/full-screen-content opts')]
      [:article
       (entity/entity-header opts')
       (entity/entity-content opts')
       (entity/entity-notes opts')])))

(rum/defc search
  [{:keys [languages lemma search-results details?] :as opts}]
  [:article.search
   [:header
    [:h1 (str "\"" lemma "\"")]]
   (if (empty? search-results)
     [:p (i18n/da-en languages
           "Ingen resultater kunne findes for dette lemma."
           "No results could be found for this lemma.")]
     (for [[k entity] search-results]
       (let [{:keys [k->label short-label]} (meta entity)
             k->label' (if (and (not details?) short-label)
                         (assoc k->label
                           k short-label)
                         k->label)]
         (rum/with-key (table/attr-val-table {:languages languages
                                              :k->label  k->label'}
                                             entity)
                       k))))])

(rum/defc markdown
  [{:keys [languages content] :as opts}]
  (let [ls     (i18n/select-label languages content)
        lang   (lstr/lang ls)
        md     (str ls)
        hiccup (mdc/md->hiccup md)]
    #?(:cljs (when-let [title (mdc/hiccup->title hiccup)]
               (set! js/document.title title)))
    [:article.document {:lang lang}
     hiccup]))
