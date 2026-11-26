(ns dk.cst.dannet.web.ui.dialog-test
  "Tests for the generic dialog and for the wizard, as the server draws them."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rum.core :as rum]
            [dk.cst.dannet.web.ui.dialog :as dialog]))

(defn- html
  "The wizard of the `flow` as the server draws it, for a Danish reader."
  [flow]
  (rum/render-static-markup (dialog/wizard ["da" "en"] flow {})))

(deftest dialogs
  (let [html (rum/render-static-markup
               (dialog/dialog ["da" "en"]
                              {:title "Find et ord" :class "search-dialog"}
                              [:p "indhold"]))]
    (is (str/includes? html "aria-label=\"Find et ord\"")
        "a dialog is named by its title")
    (is (str/includes? html "<h2>Find et ord</h2>"))
    (is (str/includes? html "class=\"dialog search-dialog\"")
        "and says which kind of dialog it is")
    (is (str/includes? html "Luk") "it has a button that closes it")
    (is (str/includes? html "<p>indhold</p>"))))

(deftest wizards
  (let [steps (fn [{:keys [more] :as answers}]
                (cond-> [{:heading "Valg"
                          :text    ["Vil du have et trin mere?"]
                          :choices [{:key :more :label "Et trin mere"}]}]
                  more (conj {:heading "Ekstra"})
                  true (conj {:heading "Bekræft" :warning "Pas på"})))
        flow  (fn [answers]
                {:title    "Guide"
                 :answers  answers
                 :steps-fn steps
                 :done     "Udfør"})
        first-step (html (flow {:more true}))]
    (testing "the first step of several"
      (is (str/includes? first-step "Trin 1 af 3")
          "says how far the wizard is, by the answers that it starts with")
      (is (str/includes? first-step "Vil du have et trin mere?"))
      (is (re-find #"<input[^>]*type=\"checkbox\"[^>]*checked" first-step)
          "a choice starts as its answer")
      (is (str/includes? first-step "Næste"))
      (is (not (str/includes? first-step "Tilbage"))
          "the first step has nothing to go back to")
      (is (not (str/includes? first-step "Udfør"))
          "and only the last step has the button that confirms")
      (is (re-find
            #"<section[^>]*data-focus[^>]*aria-labelledby=\"wizard-step\""
            first-step)
          "a step is where the focus starts, and its heading names it")
      (is (str/includes? first-step "<h3 id=\"wizard-step\"")
          "which is the heading that the step has")
      (is (re-find #"<section[^>]*aria-describedby=\"wizard-progress\""
                   first-step)
          "and it says how far the wizard is")
      (is (re-find #"<fieldset aria-labelledby=\"wizard-step\"><ul" first-step)
          "the choices of a step are a group with the name of the step"))
    (testing "the steps follow the answers"
      (is (str/includes? (html (flow {:more false})) "Trin 1 af 2")))
    (testing "a wizard of one step is a dialog that confirms"
      (let [one (html {:title       "Guide"
                       :about       :dn/word-1
                       :answers     {}
                       :steps-fn    (constantly
                                      [{:heading "Bekræft"
                                        :figures [{:value 2
                                                   :label "ting"
                                                   :title "Ting i alt"}]
                                        :text    ["I alt: 2"]
                                        :details [{:summary "Vis alt"
                                                   :items   [["2 ×" :wn/also]
                                                             :dn/word-2]}
                                                  {:summary "Vis mere"
                                                   :items   ["andet"]}]
                                        :warning "Pas på"}])
                       :resource-fn (fn [k] [:b (name k)])
                       :done        "Udfør"})]
        (is (not (str/includes? one "Trin")))
        (is (str/includes?
              one
              (str "<div title=\"Ting i alt\"><dt>"
                   "<span aria-hidden=\"true\">ting</span>"
                   "<span class=\"visually-hidden\">Ting i alt</span>"
                   "</dt><dd>2</dd></div>"))
            "a figure is a number with what it counts, and its title says
            that in full, also to a screen reader")
        (is (not (str/includes? one "aria-describedby=\"wizard-progress\""))
            "a single step has no progress to say")
        (is (re-find
              #"<button[^>]*aria-describedby=\"wizard-warning\"[^>]*>Udfør"
              one)
            "the warning is read out with the button that confirms")
        (is (str/includes? one "<p class=\"wizard__about\"><b>word-1</b></p>")
            "what the wizard is about is drawn by the function of the flow")
        (is (re-find #"<details[^>]*><summary>Vis alt</summary>" one)
            "the details are folded away under their summary")
        (is (re-find #"<details[^>]*><summary>Vis mere</summary>" one)
            "and a step can have several of them")
        (is (str/includes?
              one
              (str "<li><span class=\"wizard__lead\">2 ×</span>"
                   "<span><b>also</b></span></li>"))
            "an item with a lead is a row of two columns")
        (is (str/includes?
              one
              (str "<li><span class=\"wizard__lead\"></span>"
                   "<span><b>word-2</b></span></li>"))
            "and so is one without, so that they line up")
        (is (str/includes? one "Pas på"))
        (is (str/includes? one "Udfør"))
        (is (str/includes? one "Annullér"))
        (is (not (str/includes? one "Næste")))))))
