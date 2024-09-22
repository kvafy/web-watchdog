(ns web-watchdog.scheduling-test
  (:require [clojure.test :refer [deftest is testing]]
            [web-watchdog.scheduling :refer [create-java-scheduler due-for-check? next-check-time next-cron-time] :as scheduling]
            [web-watchdog.utils :as utils]
            [web-watchdog.test-utils :refer [build-site]])
  (:import [java.time Instant]
           [java.time.temporal ChronoUnit]))

(deftest java-scheduler-test
  (let [step-ms 10]
    (testing "Single immediate execution"
      (let [done (atom #{})
            scheduler (create-java-scheduler 4)]
        (try
          (scheduling/run-now scheduler (fn [] (swap! done conj 1)))
          (Thread/sleep step-ms)
          (is (= #{1} @done))
          (finally (scheduling/shutdown scheduler)))))
    (testing "Single delayed execution"
      (let [done (atom #{})
            scheduler (create-java-scheduler 4)]
        (try
          (scheduling/run-after-delay scheduler (fn [] (swap! done conj 1)) (* 2 step-ms))
          (Thread/sleep step-ms)
          (is (= #{} @done))
          (Thread/sleep (* 2 step-ms))
          (is (= #{1} @done))
          (finally (scheduling/shutdown scheduler)))))
    (testing "Delayed tasks executed in sequence (with enough time buffer in-between)"
      (let [done (atom [])
            scheduler (create-java-scheduler 4)]
        (try
          (dotimes [i 5]
            (let [base-delay (* 2 step-ms)
                  ith-delay (+ base-delay (* i step-ms))]
              (scheduling/run-after-delay scheduler (fn [] (swap! done conj i)) ith-delay)))
          (Thread/sleep step-ms)
          (is (= [] @done))
          (Thread/sleep (* 10 step-ms))
          (is (= [0 1 2 3 4] @done))
          (finally (scheduling/shutdown scheduler)))))
    (testing "Delayed tasks executed in parallel (scheduled for the same time)"
      (let [cnt 15  ;; One in 15! chance that the test wrongfully fails. Bet lotto if this happens :).
            done (atom #{})
            scheduler (create-java-scheduler 4)]
        (try
          (dotimes [i cnt]
            (scheduling/run-after-delay scheduler (fn [] (swap! done conj i)) 0))
          (Thread/sleep step-ms)
          (is (= (set (range cnt)) @done))    ;; All tasks done.
          (is (not= (vec (range cnt)) @done)) ;; But not sequentially.
          (finally (scheduling/shutdown scheduler)))))
    (testing "Shutdown interrupts currently running tasks"
      (let [done (atom #{})
            scheduler (create-java-scheduler 4)]
        (try
          (scheduling/run-now scheduler
                              (fn []
                                (Thread/sleep (* 2 step-ms))
                                (swap! done conj 1)))
          (Thread/sleep step-ms)
          (scheduling/shutdown scheduler)
          (Thread/sleep (* 2 step-ms))
          (is (= #{} @done))
          (finally (scheduling/shutdown scheduler)))))
    (testing "After shutdown doesn't accept new tasks"
      (let [done (atom #{})
            scheduler (create-java-scheduler 4)]
        (try
          (scheduling/shutdown scheduler)
          (scheduling/run-now scheduler (fn [] (swap! done conj 1)))
          (Thread/sleep step-ms)
          (is (= #{} @done))
          (finally (scheduling/shutdown scheduler)))))
    (testing "After shutdown doesn't run tasks that were scheduled to the future"
      (let [done (atom #{})
            scheduler (create-java-scheduler 4)]
        (try
          (scheduling/run-after-delay scheduler (fn [] (swap! done conj 1)) step-ms)
          (scheduling/shutdown scheduler)
          (Thread/sleep (* 2 step-ms))
          (is (= #{} @done))
          (finally (scheduling/shutdown scheduler)))))))


(deftest cron-scheduling
  (let [hourly            "0 0 * * * *"
        daily-at-midnight "0 0 0 * * *"
        midnight     (Instant/parse "2024-01-01T00:00:00Z")
        midnight+1h  (. midnight (plus 1 ChronoUnit/HOURS))
        midnight+24h (. midnight (plus 24 ChronoUnit/HOURS))
        midnight+48h (. midnight (plus 48 ChronoUnit/HOURS))
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
             (next-check-time site-daily global-config))))
    ;; The `due-for-check?` function.
    (testing due-for-check?
      (testing "no - check just happened"
        (with-redefs [utils/now-utc (fn [] (. midnight toEpochMilli))]
          (is (false? (due-for-check? site-hourly global-config)))
          (is (false? (due-for-check? site-daily global-config)))))
      (testing "no - due in the future"
        (with-redefs [utils/now-utc (fn [] (. midnight+1h toEpochMilli))]
          (is (false? (due-for-check? site-daily global-config)))))
      (testing "yes - due this exact millisecond"
        (with-redefs [utils/now-utc (fn [] (. midnight+24h toEpochMilli))]
          (is (true? (due-for-check? site-daily global-config)))))
      (testing "yes - overdue"
        (with-redefs [utils/now-utc (fn [] (. midnight+48h toEpochMilli))]
          (is (true? (due-for-check? site-daily global-config))))))))
