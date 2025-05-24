(ns web-watchdog.conditions-test
  (:require [clojure.test :refer [deftest is testing]]
            [web-watchdog.conditions :refer [eval-expr]]
            [web-watchdog.test-utils :refer [build-site]]
            [web-watchdog.utils]))

(deftest eval-expr-test
  (let [now 123456789
        site-orig (build-site "site originally"
                              {:state {:last-check-time (- now (* 3600 1000))}})
        site-after-failure (build-site
                            "site with failed check"
                            {:state {:last-check-time  (- now 50)
                                     ;; Failed on the last check.
                                     :last-error-time  (- now 50)
                                     :fail-counter    1
                                     :last-error-msg  "download failed"}})
        site-after-change (build-site
                           "site with successful check"
                           {:state {:last-check-time  (- now 50)
                                    ;; Succeeded on the last check.
                                    :last-change-time (- now 50)
                                    :content-hash    (web-watchdog.utils/md5 "stale ok content")
                                    :content-snippet "stale ok content"}})]
    (testing "invalid expressions"
      (doseq [invalid-expr ["" "unknown-symbol" "(check-failed?)"]]
        (is (thrown? IllegalArgumentException (eval-expr invalid-expr [site-orig site-after-change])))))
    (testing "site-agnostic operators"
      (let [dummy-sites [site-after-change site-after-change]]
        (is (true?  (eval-expr "true" dummy-sites)))
        (is (false? (eval-expr "false" dummy-sites)))

        (is (true?  (eval-expr "(< 1 2)" dummy-sites)))
        (is (false? (eval-expr "(< 2 1)" dummy-sites)))

        (is (true?  (eval-expr "(<= 1 1)" dummy-sites)))
        (is (true?  (eval-expr "(<= 1 2)" dummy-sites)))
        (is (false? (eval-expr "(<= 2 1)" dummy-sites)))

        (is (true?  (eval-expr "(> 2 1)" dummy-sites)))
        (is (false? (eval-expr "(> 1 2)" dummy-sites)))

        (is (true?  (eval-expr "(>= 1 1)" dummy-sites)))
        (is (true?  (eval-expr "(>= 2 1)" dummy-sites)))
        (is (false? (eval-expr "(>= 1 2)" dummy-sites)))

        (is (true?  (eval-expr "(= 1 1)" dummy-sites)))
        (is (false? (eval-expr "(= 1 2)" dummy-sites)))

        (is (true?  (eval-expr "(not= 1 2)" dummy-sites)))
        (is (false? (eval-expr "(not= 1 1)" dummy-sites)))

        (is (true?  (eval-expr "(not false)" dummy-sites)))
        (is (false? (eval-expr "(not true)" dummy-sites)))

        (is (true?  (eval-expr "(and true)" dummy-sites)))
        (is (true?  (eval-expr "(and true true)" dummy-sites)))
        (is (true?  (eval-expr "(and true true true)" dummy-sites)))
        (is (false? (eval-expr "(and true true false)" dummy-sites)))
        (is (true?  (eval-expr "(and)" dummy-sites)))

        (is (true?  (eval-expr "(or true)" dummy-sites)))
        (is (true?  (eval-expr "(or true true)" dummy-sites)))
        (is (true?  (eval-expr "(or false false true)" dummy-sites)))
        (is (false? (eval-expr "(or false false false)" dummy-sites)))
        (is (false? (eval-expr "(or)" dummy-sites)))))
    (testing "check-failed?"
      (is (true?  (eval-expr "check-failed?" [site-orig site-after-failure])))
      (is (false? (eval-expr "check-failed?" [site-orig site-after-change]))))
    (testing "check-succeeded?"
      (is (true?  (eval-expr "check-succeeded?" [site-orig site-after-change])))
      (is (false? (eval-expr "check-succeeded?" [site-orig site-after-failure]))))
    (testing "content-matches?"
      (let [site-empty  (assoc-in site-after-change [:state :content-snippet] "")
            site-number (assoc-in site-after-change [:state :content-snippet] "42")]
        (is (true?  (eval-expr "(content-matches? \"^$\")" [site-orig site-empty])))
        (is (false? (eval-expr "(content-matches? \"^$\")" [site-orig site-number])))
        (is (true?  (eval-expr "(content-matches? \"[0-9]+\")" [site-orig site-number])))
        (is (false? (eval-expr "(content-matches? \"^$\")" [site-orig site-number])))))
    (testing "last-check-age-secs"
      (let [prev-check-time (get-in site-orig [:state :last-check-time])
            last-check-age-secs 3600
            site-new (-> site-after-change
                         (assoc-in [:state :last-check-time] (+ prev-check-time (* 1000 last-check-age-secs))))]
        (is (= last-check-age-secs
               (eval-expr "last-check-age-secs" [site-orig site-new])))))
    (testing "site-changed?"
      (is (true?  (eval-expr "site-changed?" [site-orig site-after-change])))
      (is (false? (eval-expr "site-changed?" [site-orig site-after-failure]))))))

