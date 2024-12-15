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
        app (build-app app-state download-fn)]
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

(deftest test-test-site-endpoint
  (let [app-state (atom state/default-state)
        app-with-ok-download (build-app app-state (succeeding-download "Fake site content"))
        app-with-failing-download (build-app app-state (failing-download (ex-info "Download error" {})))
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
