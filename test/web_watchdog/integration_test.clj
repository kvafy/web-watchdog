(ns web-watchdog.integration-test
  (:require [cheshire.core :as cheshire]
            [clj-http.client :as http]
            [clojure.java.io :as io]
            [clojure.string]
            [clojure.test :refer [deftest is testing]]
            [web-watchdog.core :as core]
            [web-watchdog.persistence :as persistence]
            [web-watchdog.state :refer [default-state] :as state]
            [web-watchdog.system :as system]
            [web-watchdog.test-utils :refer [build-site not-thrown? set-sites with-system] :as test-utils]
            [web-watchdog.utils :as utils]
            [web-watchdog.web-sse])
  (:import [java.net ServerSocket]))

(def download-delay-ms 500)  ;; For actual network I/O with a real-world server.
(def settling-delay-ms 50)   ;; To allow for async processes to finish.

;; Helper functions.

(defn is-approx-now [time]
  (let [delta (- time (utils/now-ms))]
    (is (<= (abs delta) 5000))))

(defn assert-app-state-conforms-to-schema [app-state]
  (is (not-thrown? (state/validate app-state))))

(defn assert-downloads-attempted-for-sites [site-history expected-sites]
  (let [actual-urls (mapv :url site-history)
        expected-urls (mapv :url expected-sites)]
    (is (= actual-urls expected-urls))))

(defn assert-emails-sent-for-sites
  "Checks just the `:to` fields, since each test site should have a unique set of emails."
  [email-history sites]
  (let [actual-history (mapv #(select-keys % [:to]) email-history)
        expected-history (mapv (fn [site] {:to (get-in site [:email-notification :to])}) sites)]
    (is (= (set actual-history) (set expected-history)))))

(defn assert-site-updated-with-success
  ([new-site]
   (is-approx-now (get-in new-site [:state :last-check-time])))
  ([new-site new-content]
   (assert-site-updated-with-success new-site)
   (is (= new-content (get-in new-site [:state :content-snippet])))
   (is (= (utils/md5 new-content)
          (get-in new-site [:state :content-hash])))))

(defn update-site-with-successful-download-result [site content]
  (core/update-site-with-download-result site [content nil]))

(defn make-site-due-for-check [site]
  (-> site
      (assoc-in [:state :last-check-time] 0)
      (assoc-in [:state :next-check-time] 1)))

(defn http-put-json [path port data]
  (http/put (str "http://localhost:" port path)
            {:body (cheshire/encode data)
             :headers {:content-type "application/json"}
             :throw-exceptions false}))

(defn http-connect-sse [url events-coll-atom]
  (let [parse-line (fn [line]
                     (let [m (re-matcher #"([^:]*): (.*)" (clojure.string/trim-newline line))]
                       (when-not (.matches m)
                         (throw (IllegalArgumentException. (format "No SSE match found for line '%s'" line))))
                       (let [[_ k v] (re-groups m)]
                         [(keyword k) v])))
        input-lines (->> (http/get url {:as :stream}) :body io/reader line-seq)]
    (future
      (loop [event-builder {}, lines input-lines]
        (let [line (first lines)]
          (if (clojure.string/blank? line)
            (do (swap! events-coll-atom conj event-builder)
                (recur {} (rest lines)))
            (let [[k v] (parse-line line)]
              (recur (assoc event-builder k v) (rest lines)))))))))

(defn find-free-port []
  (with-open [socket (ServerSocket. 0)]
    (.getLocalPort socket)))


;; Actual tests.

(deftest validate-checked-in-system-configs
  (testing "validate 'test/resources/test-google.edn'"
    (let [file-path "test/resources/test-google.edn"
          loaded-state (persistence/load-state file-path)]
      (is (some? loaded-state))  ;; File exists.
      (assert-app-state-conforms-to-schema loaded-state))))


(deftest e2e-smoke-test
  (test-utils/with-temp-file-copy [tmp-state-file "test/resources/test-google.edn"]
    ;; Bring up the full system whose config is as close as possible to typical
    ;; production run, mocking/reconfiguring only the bare minimum.
    (let [test-system-cfg (-> system/system-cfg
                              ;; Read app state from a test file.
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

(deftest add-site-integration-test
  (let [initial-app-state default-state
        server-port (find-free-port)
        test-system-cfg (-> system/system-cfg
                            (test-utils/with-in-memory-app-state initial-app-state)
                            test-utils/with-fake-downloader
                            (assoc-in [:web-watchdog.web/server :port] server-port))]
    (testing "invalid request, fails and no change"
      (with-system [sut test-system-cfg :only-keys [:web-watchdog.web/server]]
        (let [app-state-atom (-> sut ::test-utils/in-memory-app-state)]
          (let [app-state-before @app-state-atom
                req {}
                resp (http-put-json "/sites" server-port req)
                app-state-after @app-state-atom]
            (is (= 400 (:status resp)))
            (is (= app-state-before app-state-after))))))
    (testing "valid request, site is added"
      (with-system [sut test-system-cfg :only-keys [:web-watchdog.web/server]]
        (let [app-state-atom (-> sut ::test-utils/in-memory-app-state)]
          (let [req {:title "New site", :url "https://site.io", :email-notification {:to ["me@gmail.com"] :format "old-new"}}
                resp (http-put-json "/sites" server-port req)
                app-state-after @app-state-atom]
            (is (= 200 (:status resp)))
            (assert-app-state-conforms-to-schema app-state-after)
            ;; Verify the site has been added.
            (is (= 1 (-> app-state-after :sites count)))
            (let [added-site (get-in app-state-after [:sites 0])]
              (is (= req (select-keys added-site (keys req)))))))))))

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

(deftest forced-site-check-integration-test
  (let [initial-site (-> (build-site "A") (core/update-site-with-download-result ["initial content" nil]))
        initial-app-state (-> default-state (set-sites [initial-site]))
        server-port (find-free-port)
        test-system-cfg (-> system/system-cfg
                            (test-utils/with-in-memory-app-state initial-app-state)
                            test-utils/with-fake-downloader
                            test-utils/with-fake-email-sender
                            (assoc-in [:web-watchdog.web/server :port] server-port))
        http-post (fn [path] (http/post (str "http://localhost:" server-port path) {:throw-exceptions false}))]
    (testing "trigger check of a non-existing site, fails"
      (with-system [sut test-system-cfg :only-keys [:web-watchdog.web/server :web-watchdog.scheduling/site-checker]]
        (let [app-state-atom (-> sut ::test-utils/in-memory-app-state)
              download-arg-history-atom (-> sut ::test-utils/fake-download-fn :arg-history)]
          ;; Request an immediate site check.
          (let [resp (http-post (str "/sites/unknown-site-id/refresh"))]
            (is (= 404 (:status resp))))
          (Thread/sleep settling-delay-ms)
          ;; Verify that no site check was attempted.
          (is (= initial-app-state @app-state-atom))
          (is (empty? @download-arg-history-atom)))))
    (testing "trigger check of an existing site, succeeds"
      (with-system [sut test-system-cfg :only-keys [:web-watchdog.web/server :web-watchdog.scheduling/site-checker]]
        (let [app-state-atom (-> sut ::test-utils/in-memory-app-state)
              mock-download-result-atom (-> sut ::test-utils/fake-download-fn :mock-result)
              download-arg-history-atom (-> sut ::test-utils/fake-download-fn :arg-history)]
          (reset! mock-download-result-atom {:success "updated content"})
          ;; Request an immediate site check.
          (let [resp (http-post (str "/sites/" (:id initial-site) "/refresh"))]
            (is (= 200 (:status resp))))
          (Thread/sleep settling-delay-ms)
          ;; Verify that a site check was attempted and recorded.
          (assert-site-updated-with-success (get-in @app-state-atom [:sites 0]) "updated content")
          (assert-downloads-attempted-for-sites @download-arg-history-atom [initial-site])
          (assert-app-state-conforms-to-schema @app-state-atom))))))

(deftest server-sent-events-integration-test
  (let [initial-site (-> (build-site "A") (core/update-site-with-download-result ["initial content" nil]))
        initial-app-state (-> default-state (set-sites [initial-site]))
        server-port (find-free-port)
        test-system-cfg (-> system/system-cfg
                            (test-utils/with-in-memory-app-state initial-app-state)
                            (assoc-in [:web-watchdog.web/server :port] server-port)
                            (assoc-in [:web-watchdog.web-sse/state-change-broadcasting-handler :debounce-ms] 0))
        sse-events-atom (atom [])]
    (with-system [sut test-system-cfg :only-keys [:web-watchdog.web/server]]
      (let [app-state-atom (-> sut ::test-utils/in-memory-app-state)]
        (http-connect-sse (str "http://localhost:" server-port "/sse/state-changes") sse-events-atom)
        (testing "hello message received on connect"
          (Thread/sleep settling-delay-ms)
          (is (= [{:event "connected" :data "dummy"}] @sse-events-atom)))
        (testing "notification received when app state changes"
          ;; Simulate a site content change.
          (swap! app-state-atom update-in [:sites 0] update-site-with-successful-download-result "new content")
          (Thread/sleep settling-delay-ms)
          (is (= [{:event "connected" :data "dummy"} {:event "app-state-changed" :data "dummy"}] @sse-events-atom)))))))
