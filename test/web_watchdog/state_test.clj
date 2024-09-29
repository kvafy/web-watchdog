(ns web-watchdog.state-test
  (:require [clojure.test :refer [deftest is testing]]
            [web-watchdog.test-utils :refer [build-site set-sites]]
            [web-watchdog.state :as s]))

(defn dissoc-nested-key [state ks]
  (let [ks-path (butlast ks)
        ks-leaf (last ks)]
    (update-in state ks-path #(dissoc % ks-leaf))))

(deftest validate-test
  (let [valid-site (build-site "test-site")
        valid-config (set-sites s/default-state [valid-site])
        passes-for-updated-config (fn [f] (s/validate (f valid-config)))
        throws-for-updated-config (fn [f] (is (thrown? Exception (s/validate (f valid-config)))))]
    (testing "valid site, passes"
      (s/validate valid-config))
    (testing "global config > missing :timezone, passes (will pick a default one)"
      (passes-for-updated-config #(dissoc-nested-key % [:config :timezone])))
    (testing "global config > missing :default-schedule, throws"
      (throws-for-updated-config #(dissoc-nested-key % [:config :default-schedule])))
    (testing "site > missing :id, throws"
      (throws-for-updated-config #(dissoc-nested-key % [:sites 0 :id])))
    (testing "site > duplicate :id, throws"
      (throws-for-updated-config #(set-sites % [valid-site valid-site])))
    (testing "site > missing :title, throws"
      (throws-for-updated-config #(dissoc-nested-key % [:sites 0 :title])))
    (testing "site > missing :url, throws"
      (throws-for-updated-config #(dissoc-nested-key % [:sites 0 :url])))
    (testing "site > missing :content-extractors, passes (by default checks whole site)"
      (passes-for-updated-config #(dissoc-nested-key % [:sites 0 :content-extractors])))
    (testing "site > :content-extractors > :css with value, passes"
      (passes-for-updated-config #(assoc-in % [:sites 0 :content-extractors] [[:css ".container .title"]])))
    (testing "site > :content-extractors > :css without value, throws"
      (throws-for-updated-config #(assoc-in % [:sites 0 :content-extractors] [[:css]])))
    (testing "site > :content-extractors > :xpath with value, passes"
      (passes-for-updated-config #(assoc-in % [:sites 0 :content-extractors] [[:xpath "//div[@id='service-status-page-board']"]])))
    (testing "site > :content-extractors > :xpath without value, throws"
      (throws-for-updated-config #(assoc-in % [:sites 0 :content-extractors] [[:xpath]])))
    (testing "site > :content-extractors > :regexp with value, passes"
      (passes-for-updated-config #(assoc-in % [:sites 0 :content-extractors] [[:regexp "Price: (.*)"]])))
    (testing "site > :content-extractors > :regexp without value, throws"
      (throws-for-updated-config #(assoc-in % [:sites 0 :content-extractors] [[:regexp]])))
    (testing "site > :content-extractors > :html->text with value, throws"
      (throws-for-updated-config #(assoc-in % [:sites 0 :content-extractors] [[:html->text "bogus"]])))
    (testing "site > :content-extractors > :html->text without value, passes"
      (passes-for-updated-config #(assoc-in % [:sites 0 :content-extractors] [[:html->text]])))
    (testing "site > :content-extractors > combined valid example, passes"
      (passes-for-updated-config #(assoc-in % [:sites 0 :content-extractors] [[:css ".crossroad-products"]
                                                                              [:xpath "//*[contains(@class, 'title')]"]
                                                                              [:html->text]
                                                                              [:regexp "(?:BESTSELLER)?(?:[^:]+:)?\\s*(.*)"]])))
    (testing "site > missing :emails, throws"
      (throws-for-updated-config #(dissoc-nested-key % [:sites 0 :emails])))
    (testing "site > no :emails, throws"
      (throws-for-updated-config #(assoc-in % [:sites 0 :emails] [])))
    (testing "site > missing :schedule, passes (will fall back to schedule from the global config)"
      (passes-for-updated-config #(dissoc-nested-key % [:sites 0 :schedule])))
    (testing "site > missing :state, throws"
      (throws-for-updated-config #(dissoc-nested-key % [:sites 0 :state])))
    (testing "site > :state > missing :ongoing-check, throws"
      (throws-for-updated-config #(dissoc-nested-key % [:sites 0 :state :ongoing-check])))
    (testing "site > :state > valid :ongoing-check values, passes"
      (doseq [val [:idle :pending :in-progress]]
        (passes-for-updated-config #(assoc-in % [:sites 0 :state :ongoing-check] val))))
    (testing "site > :state > invalid :ongoing-check values, throws"
      (doseq [val [:some :bogus nil]]
        (throws-for-updated-config #(assoc-in % [:sites 0 :state :ongoing-check] val))))))
