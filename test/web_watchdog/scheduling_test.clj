(ns web-watchdog.scheduling-test
  (:require [clojure.test :refer :all]
            [web-watchdog.scheduling :refer [next-cron-time]])
  (:import [java.time Instant]
           [java.time.temporal ChronoUnit]))


(deftest next-cron-time-test
  (let [hourly            "0 0 * * * *"
        daily-at-midnight "0 0 0 * * *"
        midnight     (Instant/parse "2024-01-01T00:00:00Z")
        tz "UTC"]
    (testing "after 1h"
      (is (= (next-cron-time (. midnight toEpochMilli) hourly tz)
             (.. midnight (plus 1 ChronoUnit/HOURS) (toEpochMilli)))))
    (testing "after 24h"
      (is (= (next-cron-time (. midnight toEpochMilli) daily-at-midnight tz)
             (.. midnight (plus 24 ChronoUnit/HOURS) (toEpochMilli)))))
    (testing "complex expression"
      (let [base-time-str     "2024-08-18T09:24:30Z"
            expected-time-str "2024-08-19T07:00:00Z"]
        (is (= (next-cron-time (-> base-time-str (Instant/parse) (.toEpochMilli))
                               "0 */10 7,8,17,18 * * Mon-Fri"
                               tz)
               (-> expected-time-str (Instant/parse) (.toEpochMilli))))))))
