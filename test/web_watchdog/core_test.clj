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


(deftest sites-in-both-states-test
  (let [siteA0 (site "a" nil)
        siteA1 (site "a" "a-hash")
        siteB0 (site "b" nil)]
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
                                   (set-sites default-state [siteB0 siteA1])))))))

(deftest handle-sites-changed-test
  (let [emails-sent-to (atom nil)
        setup!         (fn []
                         (reset! emails-sent-to #{}))]
    (with-redefs [web-watchdog.networking/notify-site-changed!
                  (fn [site] (swap! emails-sent-to into (:emails site)))]
      (testing "mail not sent if the content becomes available for the first time"
        (let [old-state (set-sites default-state [(site "a" nil)])
              new-state (set-sites default-state [(site "a" "a-hash")])]
          (setup!)
          (notify-if-sites-changed! old-state new-state)
          (is (= #{} @emails-sent-to))))
      (testing "mail sent if the content changes"
        (let [old-state (set-sites default-state [(site "a" "old-hash")])
              new-state (set-sites default-state [(site "a" "new-hash")])]
          (setup!)
          (notify-if-sites-changed! old-state new-state)
          (is (= (set (site-emails "a")) @emails-sent-to)))))))
