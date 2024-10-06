(ns web-watchdog.web-test
  (:require [clojure.test :refer [deftest is testing]]
            [ring.mock.request :as mock]
            [web-watchdog.state :as state]
            [web-watchdog.test-utils :refer [build-site set-sites]]
            [web-watchdog.web :refer [build-app]]))

(deftest test-basic-routing
  (let [site (build-site "A")
        app-state (atom (set-sites state/default-state [site]))
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
        ;; TODO: Verify the `(:body response)`
        ))
    (testing "trigger site check"
      (testing "of existing site"
        (let [response (app (mock/request :post (str "/sites/" (:id site) "/refresh")))]
          (is (= 200 (:status response)))))
      (testing "of non-existing site"
        (let [response (app (mock/request :post "/sites/unknown-site-id/refresh"))]
          (is (= 404 (:status response))))))
    (testing "not-found route"
      (let [response (app (mock/request :get "/invalid"))]
        (is (= 404 (:status response)))))))
