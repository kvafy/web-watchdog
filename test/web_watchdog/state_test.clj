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
    (testing "site > missing :request, passes"
      (passes-for-updated-config #(dissoc-nested-key % [:sites 0 :request])))
    (testing "site > empty :request, passes"
      (passes-for-updated-config #(assoc-in % [:sites 0 :request] {})))
    (testing "site > :request > valid :method values, passes"
      (doseq [val ["GET" "POST"]]
        (passes-for-updated-config #(assoc-in % [:sites 0 :request] {:method val}))))
    (testing "site > :request > invalid :method values, throws"
      (doseq [val ["PUT" "DELETE" "get" "post"]]
        (throws-for-updated-config #(assoc-in % [:sites 0 :request] {:method val}))))
    (testing "site > :request > valid :query-params, passes"
      (passes-for-updated-config #(assoc-in % [:sites 0 :request] {:query-params {}}))
      (passes-for-updated-config #(assoc-in % [:sites 0 :request] {:query-params {"q" "search"}})))
    (testing "site > :request > invalid :query-params, throws"
      (doseq [invalid [:only-strings 1]]
        (throws-for-updated-config #(assoc-in % [:sites 0 :request] {:query-params {"ok" invalid}}))
        (throws-for-updated-config #(assoc-in % [:sites 0 :request] {:query-params {invalid "ok"}}))))
    (testing "site > :request > valid :form-params, passes"
      (passes-for-updated-config #(assoc-in % [:sites 0 :request] {:form-params {}}))
      (passes-for-updated-config #(assoc-in % [:sites 0 :request] {:form-params {"q" "search"}})))
    (testing "site > :request > invalid :form-params, throws"
      (doseq [invalid [:only-strings 1]]
        (throws-for-updated-config #(assoc-in % [:sites 0 :request] {:form-params {"ok" invalid}}))
        (throws-for-updated-config #(assoc-in % [:sites 0 :request] {:form-params {invalid "ok"}}))))
    (testing "site > :request > valid :headers, passes"
      (passes-for-updated-config #(assoc-in % [:sites 0 :request] {:headers {}}))
      (passes-for-updated-config #(assoc-in % [:sites 0 :request] {:headers {"Accept" "text/xml"}})))
    (testing "site > :request > invalid :headers, throws"
      (doseq [invalid [:only-strings 1]]
        (throws-for-updated-config #(assoc-in % [:sites 0 :request] {:headers {"ok" invalid}}))
        (throws-for-updated-config #(assoc-in % [:sites 0 :request] {:headers {invalid "ok"}}))))
    (testing "site > :request > valid :retries, passes"
      (passes-for-updated-config #(assoc-in % [:sites 0 :request] {:retries 0}))
      (passes-for-updated-config #(assoc-in % [:sites 0 :request] {:retries 3})))
    (testing "site > :request > invalid :retries, throws"
      (doseq [invalid [:only-ints "1"]]
        (throws-for-updated-config #(assoc-in % [:sites 0 :request] {:retries invalid}))))
    (testing "site > :request > valid :allow-insecure, passes"
      (passes-for-updated-config #(assoc-in % [:sites 0 :request] {:allow-insecure false}))
      (passes-for-updated-config #(assoc-in % [:sites 0 :request] {:allow-insecure true})))
    (testing "site > :request > invalid :allow-insecure, throws"
      (doseq [invalid [:only-bools 1 "true"]]
        (throws-for-updated-config #(assoc-in % [:sites 0 :request] {:allow-insecure invalid}))))
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
    (testing "site > missing :email-notification, throws"
      (throws-for-updated-config #(dissoc-nested-key % [:sites 0 :email-notification])))
    (testing "site > :email-notification > empty :to, throws"
      (throws-for-updated-config #(assoc-in % [:sites 0 :email-notification :to] [])))
    (testing "site > :email-notification > missing :format, throws"
      (throws-for-updated-config #(dissoc-nested-key % [:sites 0 :email-notification :format])))
    (testing "site > :email-notification > valid :format values, passes"
      (doseq [val ["old-new" "inline-diff"]]
        (passes-for-updated-config #(assoc-in % [:sites 0 :email-notification :format] val))))
    (testing "site > :email-notification > invalid :format values, throws"
      (doseq [val [:old-new :inline-diff nil "bogus"]]
        (throws-for-updated-config #(assoc-in % [:sites 0 :email-notification :format] val)))
      (throws-for-updated-config #(dissoc-nested-key % [:sites 0 :email-notification :format])))
    (testing "site > missing :schedule, passes (will fall back to schedule from the global config)"
      (passes-for-updated-config #(dissoc-nested-key % [:sites 0 :schedule])))
    (testing "site > missing :state, throws"
      (throws-for-updated-config #(dissoc-nested-key % [:sites 0 :state])))
    (testing "site > :state > missing :ongoing-check, throws"
      (throws-for-updated-config #(dissoc-nested-key % [:sites 0 :state :ongoing-check])))
    (testing "site > :state > valid :ongoing-check values, passes"
      (doseq [val ["idle" "pending" "in-progress"]]
        (passes-for-updated-config #(assoc-in % [:sites 0 :state :ongoing-check] val))))
    (testing "site > :state > invalid :ongoing-check values, throws"
      (doseq [val [:some :bogus nil :idle :pending :in-progress]]
        (throws-for-updated-config #(assoc-in % [:sites 0 :state :ongoing-check] val))))))
