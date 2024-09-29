(ns web-watchdog.scheduling-test
  (:require [clojure.test :refer [deftest is testing]]
            [web-watchdog.scheduling :refer [next-check-time next-cron-time] :as scheduling]
            [web-watchdog.test-utils :refer [build-site]])
  (:import [java.time Instant]
           [java.time.temporal ChronoUnit]))


(deftest cron-scheduling
  (let [hourly            "0 0 * * * *"
        daily-at-midnight "0 0 0 * * *"
        midnight     (Instant/parse "2024-01-01T00:00:00Z")
        midnight+1h  (. midnight (plus 1 ChronoUnit/HOURS))
        midnight+24h (. midnight (plus 24 ChronoUnit/HOURS))
        tz "UTC"
        site-hourly (build-site "hourly-schedule" {:schedule hourly, :state {:last-check-utc (. midnight toEpochMilli)}})
        site-daily (build-site "default-daily-schedule" {:state {:last-check-utc (. midnight toEpochMilli)}})
        global-config {:default-schedule daily-at-midnight, :timezone "UTC"}]
    ;; The `next-cron-time` function.
    (testing next-cron-time
      (testing "after 1h"
        (is (= (next-cron-time hourly (. midnight toEpochMilli) tz)
               (.. midnight (plus 1 ChronoUnit/HOURS) (toEpochMilli)))))
      (testing "after 24h"
        (is (= (next-cron-time daily-at-midnight (. midnight toEpochMilli) tz)
               (.. midnight (plus 24 ChronoUnit/HOURS) (toEpochMilli)))))
      (testing "complex expression"
        (let [base-time-str     "2024-08-18T09:24:30Z"
              expected-time-str "2024-08-19T07:00:00Z"]
          (is (= (next-cron-time "0 */10 7,8,17,18 * * Mon-Fri"
                                 (-> base-time-str (Instant/parse) (.toEpochMilli))
                                 tz)
                 (-> expected-time-str (Instant/parse) (.toEpochMilli)))))))
    ;; The `next-check-time` function.
    (testing next-check-time
      (is (= (. midnight+1h toEpochMilli)
             (next-check-time site-hourly global-config)))
      (is (= (. midnight+24h toEpochMilli)
             (next-check-time site-daily global-config))))))
