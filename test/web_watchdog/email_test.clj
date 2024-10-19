(ns web-watchdog.email-test
  (:require [web-watchdog.state :refer [default-state]]
            [web-watchdog.email :refer [EmailSender notify-about-site-changes!]]
            [web-watchdog.test-utils :refer [build-site set-sites site-emails]]
            [clojure.test :refer [deftest is testing]]))

(deftest notify-by-email!-test
  (let [sent-emails (atom #{})
        fake-sender (reify EmailSender
                      (send-email [_ to _ _]
                        (swap! sent-emails into to)))
        setup!      (fn [] (reset! sent-emails #{}))]
    (testing "mail not sent if the content becomes available for the first time"
      (let [old-state (set-sites default-state [(build-site "a")])
            new-state (set-sites default-state [(build-site "a" {:state {:content-hash "a-hash"}})])]
        (setup!)
        (notify-about-site-changes! fake-sender old-state new-state)
        (is (empty? @sent-emails))))
    (testing "mail sent if the content changes"
      (let [old-state (set-sites default-state [(build-site "a" {:state {:content-hash "old-hash", :last-change-time 1000}})])
            new-state (set-sites default-state [(build-site "a" {:state {:content-hash "new-hash", :last-change-time 2000}})])]
        (setup!)
        (notify-about-site-changes! fake-sender old-state new-state)
        (is (= (set (site-emails "a")) @sent-emails))))
    (testing "mail sent if site becomes unavailable"
      (let [old-state (set-sites default-state [(build-site "a" {:state {:content-hash "constant", :last-change-time 1000}})])
            new-state (set-sites default-state [(build-site "a" {:state {:content-hash "constant" :fail-counter 1}})])]
        (setup!)
        (notify-about-site-changes! fake-sender old-state new-state)
        (is (= (set (site-emails "a")) @sent-emails))))
    (testing "mail not sent if site stays unavailable"
      (let [old-state (set-sites default-state [(build-site "a" {:state {:content-hash "constant" :fail-counter 1}})])
            new-state (set-sites default-state [(build-site "a" {:state {:content-hash "constant" :fail-counter 2}})])]
        (setup!)
        (notify-about-site-changes! fake-sender old-state new-state)
        (is (empty? @sent-emails))))
    (testing "mail not sent if site becomes available and content doesn't change"
      (let [old-state (set-sites default-state [(build-site "a" {:state {:content-hash "constant" :fail-counter 20}})])
            new-state (set-sites default-state [(build-site "a" {:state {:content-hash "constant"}})])]
        (setup!)
        (notify-about-site-changes! fake-sender old-state new-state)
        (is (empty? @sent-emails))))))
