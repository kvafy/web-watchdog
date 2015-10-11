(ns web-watchdog.core-test
  (:require [web-watchdog.core :refer :all]
            [clojure.test :refer :all]))

(defn site-title [label]
  (format "Site %s" label))

(defn site-url [label]
  (format "http://site-%s.com" label))

(defn site-emails [label]
  [(format "%s@watcher.com" label)])

(defn site [label content-hash]
  {:title      (site-title label)
   :url        (site-url label)
   :re-pattern #"(?s).*"
   :emails     (site-emails label)
   :state      {:content-hash content-hash}})

(defn set-sites [app-state sites]
  (assoc-in app-state [:sites] sites))


(let [siteA0 (site "a" nil)
      siteA1 (site "a" "hashA1")
      siteA2 (site "a" "hashA2")
      siteB0 (site "b" nil)
      siteB1 (site "b" "hashB1")]

  (deftest sites-in-both-states-test
    (testing "Set of sites remains the same"
      (is (= [[siteA0 siteA1]]
             (sites-in-both-states (set-sites default-state [siteA0])
                                   (set-sites default-state [siteA1])))))
    (testing "No common site"
      (is (= []
             (sites-in-both-states (set-sites default-state [siteA0])
                                   (set-sites default-state [siteB0])))))
    (testing "A site is removed"
      (is (= [[siteA0 siteA1]]
             (sites-in-both-states (set-sites default-state [siteB0 siteA0])
                                   (set-sites default-state [siteA1])))))
    (testing "A site is added"
      (is (= [[siteA0 siteA1]]
             (sites-in-both-states (set-sites default-state [siteA0])
                                   (set-sites default-state [siteB0 siteA1]))))))

  (deftest site-change-type-test
    (testing "No content change, consider as no change."
      (is (= nil (site-change-type siteA1 siteA1))))
    (testing "Site content becomes available, consider as no change."
      (is (= nil (site-change-type siteA0 siteA1))))
    (testing "Site content goes missing, consider as no change."
      (is (= nil (site-change-type siteA1 siteA0))))
    (testing "Content actually changes, change detected."
      (is (= :content-changed (site-change-type siteA1 siteA2))))))

(deftest handle-sites-changed-test
  (let [sent-emails (atom nil)
        setup!      (fn []
                      (reset! sent-emails #{}))]
    (with-redefs [postal.core/send-message
                  (fn [{:keys [to]}] (swap! sent-emails into to))]
      (testing "mail not sent if the content becomes available for the first time"
        (let [old-state (set-sites default-state [(site "a" nil)])
              new-state (set-sites default-state [(site "a" "a-hash")])]
          (setup!)
          (notify-if-sites-changed! old-state new-state)
          (is (= #{} @sent-emails))))
      (testing "mail sent if the content changes"
        (let [old-state (set-sites default-state [(site "a" "old-hash")])
              new-state (set-sites default-state [(site "a" "new-hash")])]
          (setup!)
          (notify-if-sites-changed! old-state new-state)
          (is (= (set (site-emails "a")) @sent-emails)))))))
