(ns web-watchdog.networking-test
  (:require [clojure.string]
            [clojure.test :refer [deftest is testing]]
            [web-watchdog.networking :as networking]
            [web-watchdog.test-utils :refer [build-site]]))

(deftest site->clj-http-opts-test
  (let [min-site (build-site "a")
        min-site-req (networking/site->clj-http-opts min-site)]
    (testing "url"
      (is (= (:url min-site) (:url min-site-req))))
    (testing "http method"
      (testing "defaults to GET"
        (is (nil? (get-in min-site [:request :method])))  ;; sanity-check
        (is (= :get (get min-site-req :method))))
      (testing "value propagated"
        (doseq [[method-str method-kw] [["GET" :get] ["POST" :post]]]
          (let [site (build-site "a" {:request {:method method-str}})
                req (networking/site->clj-http-opts site)]
            (is (= method-kw (:method req)))))))
    (testing "headers"
      (let [default-headers (:headers min-site-req)
            extra-headers {"Accept" "text/xml"}
            site (build-site "a" {:request {:headers extra-headers}})
            req (networking/site->clj-http-opts site)]
        (testing "contain User-Agent"
          (is (contains? default-headers "User-Agent")))
        (testing "merged with default headers"
          (is (= (:headers req) (merge extra-headers default-headers))))))
    (testing "query-params"
      (testing "absent by default"
        (is (nil? (get-in min-site [:request :query-params])))  ;; sanity-check
        (is (nil? (get min-site-req :query-params))))
      (testing "value propagated"
        (let [params {"q" "my query"}
              site (build-site "a" {:request {:query-params params}})
              req (networking/site->clj-http-opts site)]
          (is (= params (:query-params req))))))
    (testing "form-params"
      (testing "absent by default"
        (is (nil? (get-in min-site [:request :form-params])))  ;; sanity-check
        (is (nil? (get min-site-req :form-params))))
      (testing "value propagated"
        (let [params {"q" "my query"}
              site (build-site "a" {:request {:form-params params}})
              req (networking/site->clj-http-opts site)]
          (is (= params (:form-params req))))))
    (testing "retries"
      (testing "absent by default"
        (is (nil? (get-in min-site [:request :retries])))  ;; sanity-check
        (is (nil? (get min-site-req :retry-handler))))
      (testing "value propagated"
        (let [site (build-site "a" {:request {:retries 1}})
              req (networking/site->clj-http-opts site)]
          (is (some? (get req :retry-handler)))
          (let [retry-handler (:retry-handler req)
                mock-ex (RuntimeException. "Sample download failure")]
            ;; 2nd param ~ how many requests have failed (starts at 1)
            (is (true? (retry-handler mock-ex 1 nil)))
            (is (false? (retry-handler mock-ex 2 nil)))))))
    (testing "allow-insecure"
      (testing "absent by default"
        (is (nil? (get-in min-site [:request :allow-insecure])))  ;; sanity-check
        (is (nil? (get min-site-req :insecure?))))
      (testing "value propagated"
        (let [site (build-site "a" {:request {:allow-insecure true}})
              req (networking/site->clj-http-opts site)]
          (is (= true (:insecure? req))))))))
