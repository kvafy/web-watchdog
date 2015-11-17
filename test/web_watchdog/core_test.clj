(ns web-watchdog.core-test
  (:require [web-watchdog.core :refer :all]
            [clojure.test :refer :all]))

(defn reader-for-string! [string]
  (java.io.BufferedReader. (java.io.StringReader. string)))

(defn site-title [label]
  (format "Site %s" label))

(defn site-url [label]
  (format "http://site-%s.com" label))

(defn site-emails [label]
  [(format "%s@watcher.com" label)])

(defn site [label & args]
  (let [default-params {:last-check-utc nil
                        :content-hash   nil
                        :fail-counter   0
                        :last-error-msg nil}
        params (merge default-params (apply hash-map args))]
    {:title      (site-title label)
     :url        (site-url label)
     :re-pattern #"(?s).*"
     :emails     (site-emails label)
     :state      {:last-check-utc (:last-check-utc params)
                  :content-hash   (:content-hash params)
                  :fail-counter   (:fail-counter params)
                  :last-error-msg (:last-error-msg params)}}))

(defn set-sites [app-state sites]
  (assoc-in app-state [:sites] sites))


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
      (testing "successful download"
        (let [siteWithError (site "originally with error"
                                  :last-check-utc 0
                                  :content-hash   "old-hash"
                                  :fail-counter   5
                                  :last-error-msg "download failed")]
          (with-redefs [clojure.java.io/reader
                        (fn [_] (reader-for-string! "downloaded content"))]
            (let [siteOK (check-site siteWithError)]
              (testing "time of check is updated"
                (is (= check-time (get-in siteOK [:state :last-check-utc]))))
              (testing "hash of site content is updated"
                (is (not= "old-hash" (get-in siteOK [:state :content-hash]))))
              (testing "fail counter and error message is reset"
                (is (= 0 (get-in siteOK [:state :fail-counter])))
                (is (= nil (get-in siteOK [:state :last-error-msg]))))))))
      (testing "failed download"
        (let [siteOK (site "originally without any error"
                           :last-check-utc 0
                           :content-hash   "old-hash"
                           :fail-counter   0
                           :last-error-msg nil)]
          (with-redefs [clojure.java.io/reader
                        (fn [_] (throw (RuntimeException. "download failed")))]
            (let [siteWithError (check-site siteOK)]
              (testing "time of check is updated"
                (is (= check-time (get-in siteWithError [:state :last-check-utc]))))
              (testing "hash of site content is preserved"
                (is (= "old-hash" (get-in siteWithError [:state :content-hash]))))
              (testing "fail counter increased"
                (is (= 1 (get-in siteWithError [:state :fail-counter]))))
              (testing "error message is saved"
                (is (not= nil (get-in siteWithError [:state :last-error-msg])))
                (is (string? (get-in siteWithError [:state :last-error-msg])))))))))))

  (deftest handle-sites-changed-test
    (let [sent-emails (atom nil)
          setup!      (fn []
                        (reset! sent-emails #{}))]
      (with-redefs [postal.core/send-message
                    (fn [{:keys [to]}] (swap! sent-emails into to))]
        (testing "mail not sent if the content becomes available for the first time"
          (let [old-state (set-sites default-state [(site "a")])
                new-state (set-sites default-state [(site "a" :content-hash "a-hash")])]
            (setup!)
            (notify-if-sites-changed! old-state new-state)
            (is (= #{} @sent-emails))))
        (testing "mail sent if the content changes"
          (let [old-state (set-sites default-state [(site "a" :content-hash "old-hash")])
                new-state (set-sites default-state [(site "a" :content-hash "new-hash")])]
            (setup!)
            (notify-if-sites-changed! old-state new-state)
            (is (= (set (site-emails "a")) @sent-emails))))
        (testing "mail sent if site becomes unavailable"
          (let [old-state (set-sites default-state [(site "a" :content-hash "constant")])
                new-state (set-sites default-state [(site "a" :content-hash "constant" :fail-counter 1)])]
            (setup!)
            (notify-if-sites-changed! old-state new-state)
            (is (= (set (site-emails "a")) @sent-emails))))
        (testing "mail not sent if site stays unavailable"
          (let [old-state (set-sites default-state [(site "a" :content-hash "constant" :fail-counter 1)])
                new-state (set-sites default-state [(site "a" :content-hash "constant" :fail-counter 2)])]
            (setup!)
            (notify-if-sites-changed! old-state new-state)
            (is (= #{} @sent-emails))))
        (testing "mail not sent if site becomes available and content doesn't change"
          (let [old-state (set-sites default-state [(site "a" :content-hash "constant" :fail-counter 20)])
                new-state (set-sites default-state [(site "a" :content-hash "constant")])]
            (setup!)
            (notify-if-sites-changed! old-state new-state)
            (is (= #{} @sent-emails)))))))
