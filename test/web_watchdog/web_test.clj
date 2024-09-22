(ns web-watchdog.web-test
  (:require [clojure.test :refer [deftest is testing]]
            [ring.mock.request :as mock]
            [web-watchdog.web :refer [build-app]]))

(deftest test-basic-routing
  (let [app-state (atom {})
        app (build-app app-state)]
    (testing "main route redirects to index"
      (let [response (app (mock/request :get "/"))]
        (is (= 302 (:status response)))))
    (testing "index as a static resource"
      (let [response (app (mock/request :get "/index.html"))]
        (is (= 200 (:status response)))))
    (testing "current state"
      (let [response (app (mock/request :get "/rest/current-state"))]
        (is (= 200 (:status response)))
        (is (= "{}" (:body response)))))
    (testing "not-found route"
      (let [response (app (mock/request :get "/invalid"))]
        (is (= 404 (:status response)))))))
