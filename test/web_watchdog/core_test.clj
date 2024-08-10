(ns web-watchdog.core-test
  (:require [web-watchdog.core :refer :all]
            [web-watchdog.state :refer [default-state]]
            [web-watchdog.test-utils :refer :all]
            [clojure.test :refer :all]
            [web-watchdog.networking]))


(let [siteA0 (site "a")
      siteA1 (site "a" :content-hash "hashA1")
      siteA2 (site "a" :content-hash "hashA2")
      siteB0 (site "b")
      siteB1 (site "b" :content-hash "hashB1")
      ; failing site
      siteF0 (site "f")
      siteF1 (site "f" :fail-counter 1)
      siteF2 (site "f" :fail-counter 2)]

  (deftest common-sites-test
    (testing "Set of sites remains the same"
      (is (= [[siteA0 siteA1]]
             (common-sites (set-sites default-state [siteA0])
                           (set-sites default-state [siteA1])))))
    (testing "No common site"
      (is (= []
             (common-sites (set-sites default-state [siteA0])
                           (set-sites default-state [siteB0]))))
      (is (= []
             (common-sites (set-sites default-state nil)
                           (set-sites default-state [siteB0])))))
    (testing "A site is removed"
      (is (= [[siteA0 siteA1]]
             (common-sites (set-sites default-state [siteB0 siteA0])
                           (set-sites default-state [siteA1])))))
    (testing "A site is added"
      (is (= [[siteA0 siteA1]]
             (common-sites (set-sites default-state [siteA0])
                           (set-sites default-state [siteB0 siteA1]))))))

  (deftest site-change-type-test
    (testing "No content change, consider as no change."
      (is (= nil (site-change-type siteA1 siteA1))))
    (testing "Site content becomes available, consider as no change."
      (is (= nil (site-change-type siteA0 siteA1))))
    (testing "Site content goes missing, consider as no change."
      (is (= nil (site-change-type siteA1 siteA0))))
    (testing "Content actually changes, change detected."
      (is (= :content-changed (site-change-type siteA1 siteA2))))
    (testing "Site becomes unavailable, change detected."
      (is (= :site-failing (site-change-type siteF0 siteF1))))
    (testing "Site stays unavailable, consider as no change."
      (is (= nil (site-change-type siteF1 siteF2))))
    (testing "Site becomes available, consider as no change."
      (is (= nil (site-change-type siteF2 siteF0))))))

(deftest check-site-test
  (let [check-time 123456]
    (with-redefs [web-watchdog.utils/now-utc
                  (fn [] check-time)]
      (testing "successful download with content change"
        (let [siteWithError (site "originally with error"
                                  :content-hash   "old-hash"
                                  :fail-counter   5
                                  :last-error-msg "download failed")]
          (with-redefs [clojure.java.io/reader
                        (fn [_] (reader-for-string! "downloaded content"))
                        web-watchdog.networking/download
                        (fn [_] ["downloaded content" nil])]
            (let [siteOK (check-site siteWithError)]
              (testing "time of check is updated"
                (is (= check-time (get-in siteOK [:state :last-check-utc]))))
              (testing "hash of site content is updated"
                (is (not= "old-hash" (get-in siteOK [:state :content-hash]))))
              (testing "last change timestamp is updated"
                (is (= check-time (get-in siteOK [:state :last-change-utc]))))
              (testing "fail counter and error message is reset"
                (is (= 0 (get-in siteOK [:state :fail-counter])))
                (is (= nil (get-in siteOK [:state :last-error-msg]))))
              (testing "last error timestamp remains untouched"
                (is (= nil
                       (get-in siteWithError [:state :last-error-utc])
                       (get-in siteOK [:state :last-error-utc]))))))))
      (testing "successful download without content change"
        (let [siteStatic (site "originally with error"
                               :content-hash    (web-watchdog.utils/md5 "downloaded content")
                               :fail-counter    5
                               :last-error-msg  "download failed")]
          (with-redefs [web-watchdog.networking/download
                        (fn [_] ["downloaded content" nil])]
            (let [siteOK (check-site siteStatic)]
              (testing "time of check is updated"
                (is (= check-time (get-in siteOK [:state :last-check-utc]))))
              (testing "hash of site content remains untouched" ;; more of a precondition of the test
                (is (= (get-in siteStatic [:state :content-hash])
                       (get-in siteOK [:state :content-hash]))))
              (testing "last change timestamp remains untouched"
                (is (= nil
                       (get-in siteStatic [:state :last-change-utc])
                       (get-in siteOK [:state :last-change-utc]))))
              (testing "fail counter and error message is reset"
                (is (= 0 (get-in siteOK [:state :fail-counter])))
                (is (= nil (get-in siteOK [:state :last-error-msg]))))
              (testing "last error timestamp remains untouched"
                (is (= nil
                       (get-in siteStatic [:state :last-error-utc])
                       (get-in siteOK [:state :last-error-utc]))))))))
      (testing "failed download"
        (let [siteOK (site "originally without any error"
                           :last-check-utc 0
                           :content-hash   "old-hash"
                           :fail-counter   0
                           :last-error-msg nil)]
          (with-redefs [web-watchdog.networking/download
                        (fn [_] [nil "download failed"])]
            (let [siteWithError (check-site siteOK)]
              (testing "time of check is updated"
                (is (= check-time (get-in siteWithError [:state :last-check-utc]))))
              (testing "hash of site content remains untouched"
                (is (= "old-hash"
                       (get-in siteOK [:state :content-hash])
                       (get-in siteWithError [:state :content-hash]))))
              (testing "last change timestamp remains untouched"
                (is (= nil
                       (get-in siteOK [:state :last-change-utc])
                       (get-in siteWithError [:state :last-change-utc]))))
              (testing "fail counter increased"
                (is (= 1 (get-in siteWithError [:state :fail-counter]))))
              (testing "last error timestamp is updated"
                (is (= check-time (get-in siteWithError [:state :last-error-utc]))))
              (testing "error message is saved"
                (is (not= nil (get-in siteWithError [:state :last-error-msg])))
                (is (string? (get-in siteWithError [:state :last-error-msg])))))))))))
