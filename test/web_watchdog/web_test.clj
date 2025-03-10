(ns web-watchdog.web-test
  (:require [cheshire.core :as cheshire]
            [clojure.string]
            [clojure.test :refer [deftest is testing]]
            [ring.mock.request :as mock]
            [web-watchdog.state :as state]
            [web-watchdog.test-utils :refer [build-site failing-download set-sites succeeding-download]]
            [web-watchdog.web :refer [build-app]]))

(deftest test-basic-routing
  (let [site (build-site "A")
        app-state (atom (set-sites state/default-state [site]))
        download-fn (succeeding-download "Fake site content")
        sse-handler nil
        app (build-app app-state download-fn sse-handler)]
    (testing "main route redirects to index"
      (let [response (app (mock/request :get "/"))]
        (is (= 302 (:status response)))))
    (testing "index as a static resource"
      (let [response (app (mock/request :get "/index.html"))]
        (is (= 200 (:status response)))))
    (testing "current state"
      (let [response (app (mock/request :get "/rest/current-state"))]
        (is (= 200 (:status response)))
        (is (= @app-state (cheshire/parse-string (:body response) true)))))
    (testing "create site (invalid request)"
      (let [request (-> (mock/request :put "/sites") (mock/json-body {}))
            response (app request)]
        (is (= 400 (:status response)))))
    (testing "update site (invalid request)"
      (let [request (-> (mock/request :put (str "/sites/" (:id site))) (mock/json-body {}))
            response (app request)]
        (is (= 400 (:status response)))))
    (testing "delete site (invalid request)"
      (let [request (-> (mock/request :delete "/sites/unknown-site-id"))
            response (app request)]
        (is (= 400 (:status response)))))
    (testing "test site (invalid request)"
      (let [request (-> (mock/request :post "/sites/test") (mock/json-body {}))
            response (app request)]
        (is (= 400 (:status response)))
        (is (clojure.string/starts-with? (:body response) "Request is invalid: "))))
    (testing "trigger a site refresh"
      (testing "of existing site"
        (let [response (app (mock/request :post (str "/sites/" (:id site) "/refresh")))]
          (is (= 200 (:status response)))))
      (testing "of non-existing site"
        (let [response (app (mock/request :post "/sites/unknown-site-id/refresh"))]
          (is (= 404 (:status response))))))
    (testing "not-found route"
      (let [response (app (mock/request :get "/invalid"))]
        (is (= 404 (:status response)))))))

(deftest test-delete-site-endpoint
  (let [site-A (build-site "A")
        site-B (build-site "B")
        site-C (build-site "C")
        initial-state (set-sites state/default-state [site-A site-B site-C])
        app-state (atom initial-state)
        download-fn (succeeding-download "Fake site content")
        sse-handler nil
        app (build-app app-state download-fn sse-handler)]
    (testing "unknown site"
      (let [request (-> (mock/request :delete "/sites/unknown-site-id"))
            response (app request)]
        (is (= 400 (:status response)))
        (is (= initial-state @app-state))))
    (testing "known site"
      (let [request (-> (mock/request :delete (str "/sites/" (:id site-B))))
            response (app request)]
        (is (= 200 (:status response)))
        (is (= (:sites @app-state) [site-A site-C]))))))

(deftest test-test-site-endpoint
  (let [app-state (atom state/default-state)
        sse-handler nil
        app-with-ok-download (build-app app-state (succeeding-download "Fake site content") sse-handler)
        app-with-failing-download (build-app app-state (failing-download (ex-info "Download error" {})) sse-handler)
        build-request (fn [body] (-> (mock/request :post "/sites/test") (mock/json-body body)))]
    (testing "malformed site request"
      (let [request (build-request {})
            response (app-with-ok-download request)]
        (is (= 400 (:status response)))
        (is (clojure.string/starts-with? (:body response) "Request is invalid: "))))
    (testing "well-formed site request and successful download"
      (let [request (build-request (build-site "Test site"))
            response (app-with-ok-download request)]
        (is (= 200 (:status response)))
        (is (clojure.string/starts-with? (:body response) "Extracted content: "))))
    (testing "well-formed site request and failing download"
      (let [request (build-request (build-site "Test site"))
            response (app-with-failing-download request)]
        (is (= 400 (:status response)))
        (is (clojure.string/starts-with? (:body response) "Download failed: "))))))
