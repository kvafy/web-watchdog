(ns web-watchdog.state-test
  (:require [web-watchdog.state :refer :all]
            [web-watchdog.test-utils :refer :all]
            [clojure.test :refer :all]))


(deftest notify-by-email!-test
  (let [sent-emails (atom nil)
        setup!      (fn []
                      (reset! sent-emails #{}))]
    (with-redefs [postal.core/send-message
                  (fn [_ {:keys [to]}] (swap! sent-emails into to))]
      (testing "mail not sent if the content becomes available for the first time"
        (let [old-state (set-sites default-state [(build-site "a")])
              new-state (set-sites default-state [(build-site "a" {:state {:content-hash "a-hash"}})])]
          (setup!)
          (notify-by-email! old-state new-state)
          (is (= #{} @sent-emails))))
      (testing "mail sent if the content changes"
        (let [old-state (set-sites default-state [(build-site "a" {:state {:content-hash "old-hash"}})])
              new-state (set-sites default-state [(build-site "a" {:state {:content-hash "new-hash"}})])]
          (setup!)
          (notify-by-email! old-state new-state)
          (is (= (set (site-emails "a")) @sent-emails))))
      (testing "mail sent if site becomes unavailable"
        (let [old-state (set-sites default-state [(build-site "a" {:state {:content-hash "constant"}})])
              new-state (set-sites default-state [(build-site "a" {:state {:content-hash "constant" :fail-counter 1}})])]
          (setup!)
          (notify-by-email! old-state new-state)
          (is (= (set (site-emails "a")) @sent-emails))))
      (testing "mail not sent if site stays unavailable"
        (let [old-state (set-sites default-state [(build-site "a" {:state {:content-hash "constant" :fail-counter 1}})])
              new-state (set-sites default-state [(build-site "a" {:state {:content-hash "constant" :fail-counter 2}})])]
          (setup!)
          (notify-by-email! old-state new-state)
          (is (= #{} @sent-emails))))
      (testing "mail not sent if site becomes available and content doesn't change"
        (let [old-state (set-sites default-state [(build-site "a" {:state {:content-hash "constant" :fail-counter 20}})])
              new-state (set-sites default-state [(build-site "a" {:state {:content-hash "constant"}})])]
          (setup!)
          (notify-by-email! old-state new-state)
          (is (= #{} @sent-emails)))))))
