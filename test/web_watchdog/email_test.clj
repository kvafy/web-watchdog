(ns web-watchdog.email-test
  (:require [clojure.string]
            [web-watchdog.state :refer [default-state]]
            [web-watchdog.email :refer [EmailSender mail-body-html notify-about-site-changes!]]
            [web-watchdog.test-utils :refer [build-site set-sites site-emails]]
            [clojure.test :refer [deftest is testing]]))

(deftest mail-body-html-test
  (let [old-site (build-site "a" {:state {:content-snippet "old-content", :last-change-time 0}})
        new-site (build-site "a" {:state {:content-snippet "new-content", :last-change-time 0}})
        valid-change-types [:content-changed :site-failing]
        valid-formats ["old-new" "new-only" "inline-diff"]]
    (testing "valid params, generates body"
      (doseq [change-type valid-change-types, fmt valid-formats]
        (let [body (mail-body-html old-site new-site change-type fmt)]
          (is (not-empty body)))))
    (testing "unknown change-type, throws"
      (let [valid-change-type (first valid-change-types)
            valid-fmt (first valid-formats)]
        (is (thrown? IllegalArgumentException
                     (mail-body-html old-site new-site valid-change-type "unknown-format")))
        (is (thrown? IllegalArgumentException
                     (mail-body-html old-site new-site :unknown-change-type valid-fmt)))))
    (testing "not XSS vulnerable"
      (let [xss-site (build-site "a" {:state {:content-snippet "<script>...</script>", :last-change-time 0
                                              :last-error-msg  "<script>...</script>", :last-error-time 0}})]
        (doseq [fmt valid-formats]
          (let [body-change (mail-body-html old-site xss-site :content-changed fmt)]
            (is (not (clojure.string/includes? body-change "<script>"))))
          (let [body-fail (mail-body-html old-site xss-site :site-failing fmt)]
            (is (not (clojure.string/includes? body-fail "<script>")))))))))

(deftest notify-about-site-changes!-test
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
    (testing "content changed"
      (let [old-state (set-sites default-state [(build-site "a" {:state {:content-hash "old-hash", :last-change-time 1000}})])
            new-state (set-sites default-state [(build-site "a" {:state {:content-hash "new-hash", :last-change-time 2000}})])]
        (testing "mail sent"
          (setup!)
          (notify-about-site-changes! fake-sender old-state new-state)
          (is (= (set (site-emails "a")) @sent-emails)))
        (testing "mail sent (condition true)"
          (let [new-state-true-condition (assoc-in new-state [:sites 0 :email-notification :condition] "true")]
            (setup!)
            (notify-about-site-changes! fake-sender old-state new-state-true-condition)
            (is (= (set (site-emails "a")) @sent-emails))))
        (testing "mail sent (condition invalid)"
          (let [new-state-invalid-condition (assoc-in new-state [:sites 0 :email-notification :condition] "@!xyz")]
            (setup!)
            (notify-about-site-changes! fake-sender old-state new-state-invalid-condition)
            (is (= (set (site-emails "a")) @sent-emails))))
        (testing "mail not sent (condition false)"
          (let [new-state-false-condition (assoc-in new-state [:sites 0 :email-notification :condition] "false")]
            (setup!)
            (notify-about-site-changes! fake-sender old-state new-state-false-condition)
            (is (empty? @sent-emails))))))
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
