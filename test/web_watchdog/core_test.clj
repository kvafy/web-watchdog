(ns web-watchdog.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [schema.core]
            [web-watchdog.core :as core]
            [web-watchdog.networking]
            [web-watchdog.state :refer [default-state] :as state]
            [web-watchdog.test-utils :refer [build-site failing-download not-thrown? set-sites succeeding-download]]
            [web-watchdog.utils :as utils]))

(defn assert-conforms-to-site-schema [site]
  (is (not-thrown? (schema.core/validate state/SiteSchema site))))

(deftest content-extraction-test
  (let [inner-span-content "World"
        inner-span-tag     (str "<span id=\"inner\"> " inner-span-content " </span>")
        outer-span-content (str "Hello" inner-span-tag)
        outer-span-tag     (str "<span id=\"outer\"> " outer-span-content " </span>")
        html-doc (str "<html><body>" outer-span-tag)]
    (testing "single extractor: input already failed (nil), propages error (return nil)"
      (is (= nil (core/apply-content-extractor nil [:regexp #"d"]))))

    (testing "single extractor: no match for regexp, returns nil"
      (is (= nil (core/apply-content-extractor "abc" [:regexp #"d"]))))
    (testing "single extractor: no match for css, returns nil"
      (is (= nil (core/apply-content-extractor html-doc [:css "#does-not-exist"]))))
    (testing "single extractor: no match for xpath, returns nil"
      (is (= nil (core/apply-content-extractor html-doc [:xpath "//*[@id='does-not-exist']"]))))

    (testing "single extractor: extract regexp without capturing group scans"
      (is (= "aha!" (core/apply-content-extractor "potato aha! potato" [:regexp #"aha!"]))))
    (testing "single extractor: extract regexp with capturing group"
      (is (= "42" (core/apply-content-extractor "Price is: $42" [:regexp #"Price is: \$(\d+)"]))))

    (testing "single extractor: extract normalized text from plain non-HTML string"
      (is (= "World"
             (core/apply-content-extractor " World " [:html->text]))))
    (testing "single extractor: extract normalized text from plain HTML string"
      (is (= "World"
             (core/apply-content-extractor "<br>\n\t World \n\t" [:html->text]))))
    (testing "single extractor: extract normalized text from leaf HTML element"
      (is (= inner-span-content
             (core/apply-content-extractor inner-span-tag [:html->text]))))
    (testing "single extractor: extract normalized text from element and its children"
      (is (= "Hello World"
             (core/apply-content-extractor outer-span-content [:html->text]))))

    (testing "single extractor: extract element from HTML fragment as HTML string"
      (is (= outer-span-content
             (core/apply-content-extractor outer-span-tag [:xpath "//span[@id='outer']"])
             (core/apply-content-extractor outer-span-tag [:css "#outer"]))))
    (testing "single extractor: extract element from HTML doc as HTML string"
      (is (= outer-span-content
             (core/apply-content-extractor html-doc [:xpath "//*[@id='outer']"])
             (core/apply-content-extractor html-doc [:xpath "//span[@id='outer']"])
             (core/apply-content-extractor html-doc [:css "#outer"])
             (core/apply-content-extractor html-doc [:css "span#outer"]))))

    (testing "extractor chain: empty chain returns the input"
      (is (= "data"
             (core/extract-content "data" []))))
    (testing "extractor chain: fails matching mid-way"
     (is (= nil
            (core/extract-content html-doc [[:css "#outer"] [:css "#does-not-exist"] [:css "#inner"]]))))
    (testing "extractor chain: succeeds"
      (is (= inner-span-content
             (core/extract-content html-doc [[:css "#outer"] [:css "#inner"]]))))))


(let [siteA0 (build-site "a")
      siteA1 (build-site "a" {:state {:content-hash "hashA1"}})
      siteA2 (build-site "a" {:state {:content-hash "hashA2"}})
      siteB0 (build-site "b")
      ; failing site
      siteF0 (build-site "f")
      siteF1 (build-site "f" {:state {:fail-counter 1}})
      siteF2 (build-site "f" {:state {:fail-counter 2}})]

  (deftest find-site-by-id-test
    (let [state (set-sites default-state [siteA0 siteB0])]
      (testing "Existing sites are found"
        (is (= [0 siteA0] (core/find-site-by-id state (:id siteA0))))
        (is (= [1 siteB0] (core/find-site-by-id state (:id siteB0)))))
      (testing "Non-existing site"
        (is (nil? (core/find-site-by-id state "non-existent-id"))))))

  (deftest common-sites-test
    (testing "Set of sites remains the same"
      (is (= [[siteA0 siteA1]]
             (core/common-sites (set-sites default-state [siteA0])
                                (set-sites default-state [siteA1])))))
    (testing "No common site"
      (is (= []
             (core/common-sites (set-sites default-state [siteA0])
                                (set-sites default-state [siteB0]))))
      (is (= []
             (core/common-sites (set-sites default-state nil)
                                (set-sites default-state [siteB0])))))
    (testing "A site is removed"
      (is (= [[siteA0 siteA1]]
             (core/common-sites (set-sites default-state [siteB0 siteA0])
                                (set-sites default-state [siteA1])))))
    (testing "A site is added"
      (is (= [[siteA0 siteA1]]
             (core/common-sites (set-sites default-state [siteA0])
                                (set-sites default-state [siteB0 siteA1]))))))

  (deftest site-change-type-test
    (testing "No content change, consider as no change."
      (is (= nil (core/site-change-type siteA1 siteA1))))
    (testing "Site content becomes available, consider as no change."
      (is (= nil (core/site-change-type siteA0 siteA1))))
    (testing "Site content goes missing, consider as no change."
      (is (= nil (core/site-change-type siteA1 siteA0))))
    (testing "Content actually changes, change detected."
      (is (= :content-changed (core/site-change-type siteA1 siteA2))))
    (testing "Site becomes unavailable, change detected."
      (is (= :site-failing (core/site-change-type siteF0 siteF1))))
    (testing "Site stays unavailable, consider as no change."
      (is (= nil (core/site-change-type siteF1 siteF2))))
    (testing "Site becomes available, consider as no change."
      (is (= nil (core/site-change-type siteF2 siteF0))))))

(deftest check-site-test
  (let [check-time 123456]
    (with-redefs [web-watchdog.utils/now-ms
                  (fn [] check-time)]
      (testing "successful download with content change"
        (let [site-data "downloaded-content"
              site-with-error (build-site
                               "originally with error"
                               {:state {:content-hash   "old-hash"
                                        :content-snippet "old-content-snipet"
                                        :fail-counter   5
                                        :last-error-msg "download failed"}})
              fake-downloader (succeeding-download site-data)]
          (let [site-ok (core/check-site site-with-error fake-downloader)]
            (testing "time of check is updated"
              (is (= check-time (get-in site-ok [:state :last-check-time]))))
            (testing "hash of site content is updated"
              (is (not= "old-hash" (get-in site-ok [:state :content-hash]))))
            (testing "content snippet of site content is updated"
              (is (= site-data (get-in site-ok [:state :content-snippet]))))
            (testing "last change timestamp is updated"
              (is (= check-time (get-in site-ok [:state :last-change-time]))))
            (testing "fail counter and error message is reset"
              (is (= 0 (get-in site-ok [:state :fail-counter])))
              (is (= nil (get-in site-ok [:state :last-error-msg]))))
            (testing "last error timestamp remains untouched"
              (is (= nil
                     (get-in site-with-error [:state :last-error-time])
                     (get-in site-ok [:state :last-error-time]))))
            (testing "produces valid state"
              (assert-conforms-to-site-schema site-ok)))))
      (testing "successful download without content change"
        (let [site-data "downloaded-content"
              fake-downloader (succeeding-download site-data)
              site-static (build-site
                           "originally with error"
                           {:state {:content-hash    (web-watchdog.utils/md5 site-data)
                                    :content-snippet site-data
                                    :fail-counter    5
                                    :last-error-msg  "download failed"}})]
          (let [site-ok (core/check-site site-static fake-downloader)]
            (testing "time of check is updated"
              (is (= check-time (get-in site-ok [:state :last-check-time]))))
            (testing "hash of site content remains untouched" ;; more of a precondition of the test
              (is (= (get-in site-static [:state :content-hash])
                     (get-in site-ok [:state :content-hash]))))
            (testing "site content snippet remains untouched" ;; more of a precondition of the test
              (is (= (get-in site-static [:state :content-snippet])
                     (get-in site-ok [:state :content-snippet]))))
            (testing "last change timestamp remains untouched"
              (is (= nil
                     (get-in site-static [:state :last-change-time])
                     (get-in site-ok [:state :last-change-time]))))
            (testing "fail counter and error message is reset"
              (is (= 0 (get-in site-ok [:state :fail-counter])))
              (is (= nil (get-in site-ok [:state :last-error-msg]))))
            (testing "last error timestamp remains untouched"
              (is (= nil
                     (get-in site-static [:state :last-error-time])
                     (get-in site-ok [:state :last-error-time]))))
            (testing "produces valid state"
              (assert-conforms-to-site-schema site-ok)))))
      (testing "failed download"
        (let [site-ok (build-site
                       "originally without any error"
                       {:state {:last-check-time 0
                                :content-hash    "old-hash"
                                :content-snippet "old-content-snipet"
                                :fail-counter    0
                                :last-error-msg  nil}})
              fake-downloader (failing-download (ex-info "download failed" {}))]
          (let [site-with-error (core/check-site site-ok fake-downloader)]
            (testing "time of check is updated"
              (is (= check-time (get-in site-with-error [:state :last-check-time]))))
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
                     (get-in site-ok [:state :last-change-time])
                     (get-in site-with-error [:state :last-change-time]))))
            (testing "fail counter increased"
              (is (= 1 (get-in site-with-error [:state :fail-counter]))))
            (testing "last error timestamp is updated"
              (is (= check-time (get-in site-with-error [:state :last-error-time]))))
            (testing "error message is saved"
              (is (not= nil (get-in site-with-error [:state :last-error-msg])))
              (is (= "download failed" (get-in site-with-error [:state :last-error-msg]))))
            (testing "produces valid state"
              (assert-conforms-to-site-schema site-with-error)))))
      (testing "all content extractors"
        (let [extracted-data "Extracted data"
              site-html-data (str "<html><body>"
                                  "<p>Ignore this"
                                  "  <span id='data'>" extracted-data "</span>"
                                  "</p>"
                                  "</body></html>")
              fake-downloader (succeeding-download site-html-data)]
          (testing "CSS selector"
            (let [site-before (build-site
                               "with CSS selector"
                               {:content-extractors [[:css "#data"]]})]
              (let [site-ok (core/check-site site-before fake-downloader)]
                (testing "content snippet of site content is updated"
                  (is (= extracted-data (get-in site-ok [:state :content-snippet]))))
                (testing "hash of site content is updated"
                  (is (= (utils/md5 extracted-data)
                         (get-in site-ok [:state :content-hash]))))
                (testing "produces valid state"
                  (assert-conforms-to-site-schema site-ok))))))))))
