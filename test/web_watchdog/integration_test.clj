(ns web-watchdog.integration-test
  (:require [clojure.test :refer [deftest is testing]]
            [web-watchdog.core :as core]
            [web-watchdog.persistence :as persistence]
            [web-watchdog.state :refer [default-state] :as state]
            [web-watchdog.system :as system]
            [web-watchdog.test-utils :refer [build-site not-thrown? set-sites with-system] :as test-utils]
            [web-watchdog.utils :as utils])
  (:import [java.net ServerSocket]))

(def download-delay-ms 500)  ;; For actual network I/O with a real-world server.
(def settling-delay-ms 50)   ;; To allow for async processes to finish.

;; Helper functions.

(defn is-approx-now [time]
  (let [delta (- time (utils/now-utc))]
    (is (<= (abs delta) 5000))))

(defn assert-app-state-conforms-to-schema [app-state]
  (is (not-thrown? (state/validate app-state))))

(defn assert-downloads-attempted-for-sites [url-history sites]
  (let [expected-history (mapv :url sites)]
    (is (= (set url-history) (set expected-history)))))

(defn assert-emails-sent-for-sites
  "Checks just the `:to` fields, since each test site should have a unique set of emails."
  [email-history sites]
  (let [actual-history (mapv #(select-keys % [:to]) email-history)
        expected-history (mapv (fn [site] {:to (get site :emails)}) sites)]
    (is (= (set actual-history) (set expected-history)))))

(defn assert-site-updated-with-success
  ([new-site]
   (is-approx-now (get-in new-site [:state :last-check-utc])))
  ([new-site new-content]
   (assert-site-updated-with-success new-site)
   (is (= new-content (get-in new-site [:state :content-snippet])))))

(defn update-site-with-successful-download-result [site content]
  (core/update-site-with-download-result site [content nil]))

(defn make-site-due-for-check [site]
  (-> site
      (assoc-in [:state :last-check-utc] 0)
      (assoc-in [:state :next-check-utc] 1)))

(defn find-free-port []
  (with-open [socket (ServerSocket. 0)]
    (.getLocalPort socket)))


;; Actual tests.

(deftest validate-checked-in-system-configs
  (testing "validate 'test/resources/test-google.edn'"
    (let [file-path "test/resources/test-google.edn"
          loaded-state (persistence/load-state file-path)]
      (is (some? loaded-state))  ;; File exists.
      (is (not-thrown? (state/validate loaded-state))))))


(deftest e2e-smoke-test
  (test-utils/with-temp-file-copy [tmp-state-file "test/resources/test-google.edn"]
    ;; Bring up the full system whose config is as close as possible to typical
    ;; production run, mocking/reconfiguring only the bare minimum.
    (let [test-system-cfg (-> system/system-cfg
                              ;; Read app state from a test file, and don't modify the file.
                              (assoc-in [:web-watchdog.state/file-based-app-state :file-path] tmp-state-file)
                              (assoc-in [:web-watchdog.state/file-based-app-state :fail-if-not-found?] true)
                              (assoc-in [:web-watchdog.state/file-based-app-state :save-debounce-ms] 0)
                              ;; Dynamically pick a port that is not used.
                              (assoc-in [:web-watchdog.web/server :port] (find-free-port))
                              test-utils/with-fake-email-sender)]
      (testing "full system checking google.com"
        (with-system [sut test-system-cfg]
          (let [app-state-atom (-> sut :web-watchdog.state/file-based-app-state)
                email-history-atom (-> sut ::test-utils/fake-email-sender :history)]
            (Thread/sleep (+ download-delay-ms settling-delay-ms))
            (let [updated-site (-> @app-state-atom (get-in [:sites 0]))]
              (testing "site state updated with successful download"
                (assert-site-updated-with-success updated-site))
              (testing "email notification sent"
                (assert-emails-sent-for-sites @email-history-atom [updated-site]))
              (testing "updated state is valid"
                (assert-app-state-conforms-to-schema @app-state-atom)))
            (testing "state file updated"
              (let [orig-state (persistence/load-state "test/resources/test-google.edn")
                    new-state  (persistence/load-state tmp-state-file)]
                (is (not= orig-state new-state))
                (is (= new-state @app-state-atom))))))))))


(deftest content-change-integration-test
  (let [initial-site (-> (build-site "A") (update-site-with-successful-download-result "initial content"))
        initial-app-state (-> default-state (set-sites [initial-site]))
        test-system-cfg (-> system/system-cfg
                            (test-utils/with-in-memory-app-state initial-app-state)
                            test-utils/with-fake-email-sender)]
    (testing "site content changes, email sent"
      (with-system [sut test-system-cfg :only-keys [:web-watchdog.email/notifier]]
        (let [app-state-atom (-> sut ::test-utils/in-memory-app-state)
              email-history-atom (-> sut ::test-utils/fake-email-sender :history)]
          (is (empty? @email-history-atom))
          ;; Simulate a site content change.
          (swap! app-state-atom update-in [:sites 0] update-site-with-successful-download-result "new content")
          ;; Verify that email an was sent.
          (assert-emails-sent-for-sites @email-history-atom [initial-site]))))))

(deftest scheduled-site-check-integration-test
  (let [initial-site (-> (build-site "A") (core/update-site-with-download-result ["initial content" nil]))
        initial-app-state (-> default-state (set-sites [initial-site]))
        test-system-cfg (-> system/system-cfg
                            (test-utils/with-in-memory-app-state initial-app-state)
                            test-utils/with-fake-downloader
                            test-utils/with-fake-email-sender)]
    (testing "site becomes due, site is checked"
      (with-system [sut test-system-cfg :only-keys [:web-watchdog.scheduling/site-checker]]
        (let [app-state-atom (-> sut ::test-utils/in-memory-app-state)
              mock-download-result-atom (-> sut ::test-utils/fake-download-fn :mock-result)
              download-arg-history-atom (-> sut ::test-utils/fake-download-fn :arg-history)]
          (is (empty? @download-arg-history-atom))
          ;; Force a site to become due.
          (reset! mock-download-result-atom {:success "updated content"})
          (swap! app-state-atom update-in [:sites 0] make-site-due-for-check)
          (Thread/sleep settling-delay-ms)
          ;; Verify that a site check was attempted and recorded.
          (assert-site-updated-with-success (get-in @app-state-atom [:sites 0]) "updated content")
          (assert-downloads-attempted-for-sites @download-arg-history-atom [initial-site])
          (assert-app-state-conforms-to-schema @app-state-atom))))))
