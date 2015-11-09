(ns web-watchdog.core-test
  (:require [web-watchdog.core :refer :all]
            [clojure.test :refer :all]))

(defn site-title [label]
  (format "Site %s" label))

(defn site-url [label]
  (format "http://site-%s.com" label))

(defn site-emails [label]
  [(format "%s@watcher.com" label)])

(defn site [label content-hash fail-counter]
  {:title      (site-title label)
   :url        (site-url label)
   :re-pattern #"(?s).*"
   :emails     (site-emails label)
   :state      {:content-hash content-hash
                :fail-counter fail-counter}})

(defn set-sites [app-state sites]
  (assoc-in app-state [:sites] sites))


(let [siteA0 (site "a" nil 0)
      siteA1 (site "a" "hashA1" 0)
      siteA2 (site "a" "hashA2" 0)
      siteB0 (site "b" nil 0)
      siteB1 (site "b" "hashB1" 0)
      ; failing site
      siteF0 (site "f" nil 0)
      siteF1 (site "f" nil 1)
      siteF2 (site "f" nil 2)]

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
      (is (= :content-changed (site-change-type siteA1 siteA2))))
    (testing "Site becomes unavailable, change detected."
      (is (= :site-failing (site-change-type siteF0 siteF1))))
    (testing "Site stays unavailable, consider as no change."
      (is (= nil (site-change-type siteF1 siteF2))))
    (testing "Site becomes available, consider as no change."
      (is (= nil (site-change-type siteF2 siteF0))))))

(deftest handle-sites-changed-test
  (let [sent-emails (atom nil)
        setup!      (fn []
                      (reset! sent-emails #{}))]
    (with-redefs [postal.core/send-message
                  (fn [{:keys [to]}] (swap! sent-emails into to))]
      (testing "mail not sent if the content becomes available for the first time"
        (let [old-state (set-sites default-state [(site "a" nil 0)])
              new-state (set-sites default-state [(site "a" "a-hash" 0)])]
          (setup!)
          (notify-if-sites-changed! old-state new-state)
          (is (= #{} @sent-emails))))
      (testing "mail sent if the content changes"
        (let [old-state (set-sites default-state [(site "a" "old-hash" 0)])
              new-state (set-sites default-state [(site "a" "new-hash" 0)])]
          (setup!)
          (notify-if-sites-changed! old-state new-state)
          (is (= (set (site-emails "a")) @sent-emails))))
      (testing "mail sent if site becomes unavailable"
        (let [old-state (set-sites default-state [(site "a" "constant-hash" 0)])
              new-state (set-sites default-state [(site "a" "constant-hash" 1)])]
          (setup!)
          (notify-if-sites-changed! old-state new-state)
          (is (= (set (site-emails "a")) @sent-emails))))
      (testing "mail not sent if site stays unavailable"
        (let [old-state (set-sites default-state [(site "a" "constant-hash" 1)])
              new-state (set-sites default-state [(site "a" "constant-hash" 2)])]
          (setup!)
          (notify-if-sites-changed! old-state new-state)
          (is (= #{} @sent-emails))))
      (testing "mail not sent if site becomes available and content doesn't change"
        (let [old-state (set-sites default-state [(site "a" "constant-hash" 20)])
              new-state (set-sites default-state [(site "a" "constant-hash" 0)])]
          (setup!)
          (notify-if-sites-changed! old-state new-state)
          (is (= #{} @sent-emails)))))))
