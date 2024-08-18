(ns web-watchdog.core-test
  (:require [clojure.test :refer :all]
            [web-watchdog.core :refer :all]
            [web-watchdog.state :refer [default-state]]
            [web-watchdog.test-utils :refer [build-site set-sites]]
            [web-watchdog.networking]
            [web-watchdog.utils :as utils])
  (:import [java.time Instant]
           [java.time.temporal ChronoUnit]))


(deftest content-extraction-test
  (let [inner-span-content "World"
        inner-span-tag     (str "<span id=\"inner\"> " inner-span-content " </span>")
        outer-span-content (str "Hello" inner-span-tag)
        outer-span-tag     (str "<span id=\"outer\"> " outer-span-content " </span>")
        html-doc (str "<html><body>" outer-span-tag)]
    (testing "single extractor: input already failed (nil), propages error (return nil)"
      (is (= nil (apply-content-extractor nil [:regexp #"d"]))))

    (testing "single extractor: no match for regexp, returns nil"
      (is (= nil (apply-content-extractor "abc" [:regexp #"d"]))))
    (testing "single extractor: no match for css, returns nil"
      (is (= nil (apply-content-extractor html-doc [:css "#does-not-exist"]))))
    (testing "single extractor: no match for xpath, returns nil"
      (is (= nil (apply-content-extractor html-doc [:xpath "//*[@id='does-not-exist']"]))))

    (testing "single extractor: extract regexp without capturing group scans"
      (is (= "aha!" (apply-content-extractor "potato aha! potato" [:regexp #"aha!"]))))
    (testing "single extractor: extract regexp with capturing group"
      (is (= "42" (apply-content-extractor "Price is: $42" [:regexp #"Price is: \$(\d+)"]))))

    (testing "single extractor: extract normalized text from plain non-HTML string"
      (is (= "World"
             (apply-content-extractor " World " [:html->text]))))
    (testing "single extractor: extract normalized text from plain HTML string"
      (is (= "World"
             (apply-content-extractor "<br>\n\t World \n\t" [:html->text]))))
    (testing "single extractor: extract normalized text from leaf HTML element"
      (is (= inner-span-content
             (apply-content-extractor inner-span-tag [:html->text]))))
    (testing "single extractor: extract normalized text from element and its children"
      (is (= "Hello World"
             (apply-content-extractor outer-span-content [:html->text]))))

    (testing "single extractor: extract element from HTML fragment as HTML string"
      (is (= outer-span-content
             (apply-content-extractor outer-span-tag [:xpath "//span[@id='outer']"])
             (apply-content-extractor outer-span-tag [:css "#outer"]))))
    (testing "single extractor: extract element from HTML doc as HTML string"
      (is (= outer-span-content
             (apply-content-extractor html-doc [:xpath "//*[@id='outer']"])
             (apply-content-extractor html-doc [:xpath "//span[@id='outer']"])
             (apply-content-extractor html-doc [:css "#outer"])
             (apply-content-extractor html-doc [:css "span#outer"]))))

    (testing "extractor chain: empty chain returns the input"
      (is (= "data"
             (extract-content "data" []))))
    (testing "extractor chain: fails matching mid-way"
     (is (= nil
            (extract-content html-doc [[:css "#outer"] [:css "#does-not-exist"] [:css "#inner"]]))))
    (testing "extractor chain: succeeds"
      (is (= inner-span-content
             (extract-content html-doc [[:css "#outer"] [:css "#inner"]]))))))


(let [siteA0 (build-site "a")
      siteA1 (build-site "a" {:state {:content-hash "hashA1"}})
      siteA2 (build-site "a" {:state {:content-hash "hashA2"}})
      siteB0 (build-site "b")
      siteB1 (build-site "b" {:state {:content-hash "hashB1"}})
      ; failing site
      siteF0 (build-site "f")
      siteF1 (build-site "f" {:state {:fail-counter 1}})
      siteF2 (build-site "f" {:state {:fail-counter 2}})]

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


(deftest cron-scheduling
  (let [hourly            "0 0 * * * *"
        daily-at-midnight "0 0 0 * * *"
        midnight     (Instant/parse "2024-01-01T00:00:00Z")
        midnight+1h  (. midnight (plus 1 ChronoUnit/HOURS))
        midnight+24h (. midnight (plus 24 ChronoUnit/HOURS))
        midnight+48h (. midnight (plus 48 ChronoUnit/HOURS))
        site-hourly (build-site "hourly-schedule" {:schedule hourly, :state {:last-check-utc (. midnight toEpochMilli)}})
        site-daily (build-site "default-daily-schedule" {:state {:last-check-utc (. midnight toEpochMilli)}})
        global-config {:default-schedule daily-at-midnight, :timezone "UTC"}]
    (testing next-check-time
      (is (= (. midnight+1h toEpochMilli)
             (next-check-time site-hourly global-config)))
      (is (= (. midnight+24h toEpochMilli)
             (next-check-time site-daily global-config))))
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


(deftest check-site-test
  (let [check-time 123456]
    (with-redefs [web-watchdog.utils/now-utc
                  (fn [] check-time)]
      (testing "successful download with content change"
        (let [site-data "downloaded-content"
              site-with-error (build-site
                               "originally with error"
                               {:state {:content-hash   "old-hash"
                                        :content-snippet "old-content-snipet"
                                        :fail-counter   5
                                        :last-error-msg "download failed"}})]
          (with-redefs [web-watchdog.networking/download
                        (fn [_] [site-data nil])]
            (let [siteOK (check-site site-with-error)]
              (testing "time of check is updated"
                (is (= check-time (get-in siteOK [:state :last-check-utc]))))
              (testing "hash of site content is updated"
                (is (not= "old-hash" (get-in siteOK [:state :content-hash]))))
              (testing "content snippet of site content is updated"
                (is (= site-data (get-in siteOK [:state :content-snippet]))))
              (testing "last change timestamp is updated"
                (is (= check-time (get-in siteOK [:state :last-change-utc]))))
              (testing "fail counter and error message is reset"
                (is (= 0 (get-in siteOK [:state :fail-counter])))
                (is (= nil (get-in siteOK [:state :last-error-msg]))))
              (testing "last error timestamp remains untouched"
                (is (= nil
                       (get-in site-with-error [:state :last-error-utc])
                       (get-in siteOK [:state :last-error-utc]))))))))
      (testing "successful download without content change"
        (let [site-data "downloaded-content"
              site-static (build-site
                           "originally with error"
                           {:state {:content-hash    (web-watchdog.utils/md5 site-data)
                                    :content-snippet site-data
                                    :fail-counter    5
                                    :last-error-msg  "download failed"}})]
          (with-redefs [web-watchdog.networking/download
                        (fn [_] [site-data nil])]
            (let [site-ok (check-site site-static)]
              (testing "time of check is updated"
                (is (= check-time (get-in site-ok [:state :last-check-utc]))))
              (testing "hash of site content remains untouched" ;; more of a precondition of the test
                (is (= (get-in site-static [:state :content-hash])
                       (get-in site-ok [:state :content-hash]))))
              (testing "site content snippet remains untouched" ;; more of a precondition of the test
                (is (= (get-in site-static [:state :content-snippet])
                       (get-in site-ok [:state :content-snippet]))))
              (testing "last change timestamp remains untouched"
                (is (= nil
                       (get-in site-static [:state :last-change-utc])
                       (get-in site-ok [:state :last-change-utc]))))
              (testing "fail counter and error message is reset"
                (is (= 0 (get-in site-ok [:state :fail-counter])))
                (is (= nil (get-in site-ok [:state :last-error-msg]))))
              (testing "last error timestamp remains untouched"
                (is (= nil
                       (get-in site-static [:state :last-error-utc])
                       (get-in site-ok [:state :last-error-utc]))))))))
      (testing "failed download"
        (let [site-ok (build-site
                       "originally without any error"
                       {:state {:last-check-utc  0
                                :content-hash    "old-hash"
                                :content-snippet "old-content-snipet"
                                :fail-counter    0
                                :last-error-msg  nil}})]
          (with-redefs [web-watchdog.networking/download
                        (fn [_] [nil "download failed"])]
            (let [site-with-error (check-site site-ok)]
              (testing "time of check is updated"
                (is (= check-time (get-in site-with-error [:state :last-check-utc]))))
              (testing "hash of site content remains untouched"
                (is (= "old-hash"
                       (get-in site-ok [:state :content-hash])
                       (get-in site-with-error [:state :content-hash]))))
              (testing "content snippet remains untouched"
                (is (= "old-content-snipet"
                       (get-in site-ok [:state :content-snippet])
                       (get-in site-with-error [:state :content-snippet]))))
              (testing "last change timestamp remains untouched"
                (is (= nil
                       (get-in site-ok [:state :last-change-utc])
                       (get-in site-with-error [:state :last-change-utc]))))
              (testing "fail counter increased"
                (is (= 1 (get-in site-with-error [:state :fail-counter]))))
              (testing "last error timestamp is updated"
                (is (= check-time (get-in site-with-error [:state :last-error-utc]))))
              (testing "error message is saved"
                (is (not= nil (get-in site-with-error [:state :last-error-msg])))
                (is (string? (get-in site-with-error [:state :last-error-msg]))))))))
      (testing "all content extractors"
        (let [extracted-data "Extracted data"
              site-html-data (str "<html><body>"
                                  "<p>Ignore this"
                                  "  <span id='data'>" extracted-data "</span>"
                                  "</p>"
                                  "</body></html>")]
          (with-redefs [web-watchdog.networking/download
                        (fn [_] [site-html-data nil])]
            (testing "CSS selector"
              (let [site-before (build-site
                                 "with CSS selector"
                                 {:content-extractors [[:css "#data"]]})]
                (let [site-ok (check-site site-before)]
                  (testing "content snippet of site content is updated"
                    (is (= extracted-data (get-in site-ok [:state :content-snippet]))))
                  (testing "hash of site content is updated"
                    (is (= (utils/md5 extracted-data)
                           (get-in site-ok [:state :content-hash])))))))
            (testing "XPath selector"
              (let [site-before (build-site
                                 "with XPath selector"
                                 {:content-extractors [[:xpath "//span[@id='data']"]]})]
                (let [site-ok (check-site site-before)]
                  (testing "content snippet of site content is updated"
                    (is (= extracted-data (get-in site-ok [:state :content-snippet]))))
                  (testing "hash of site content is updated"
                    (is (= (utils/md5 extracted-data)
                           (get-in site-ok [:state :content-hash])))))))
            (testing "regexp"
              (let [site-before (build-site
                                 "with regexp selector"
                                 {:content-extractors [[:regexp "<span id='data'>(.*)</span>"]]})]
                (let [site-ok (check-site site-before)]
                  (testing "content snippet of site content is updated"
                    (is (= extracted-data (get-in site-ok [:state :content-snippet]))))
                  (testing "hash of site content is updated"
                    (is (= (utils/md5 extracted-data)
                           (get-in site-ok [:state :content-hash])))))))))))))
