(ns web-watchdog.utils-test
  (:require [clojure.test :refer [deftest is testing]]
            [web-watchdog.utils :refer [debounce memoize-with-ttl]]))

(deftest debounce-test
  (let [calls (atom [])
        f (fn [x] (swap! calls conj x))
        interval 20]
    (testing "single invocation, eventually executed"
      (let [f-debounced (debounce f interval)]
        (reset! calls [])
        (f-debounced 1)
        (Thread/sleep (* 2 interval))
        (is (= [1] @calls))))
    (testing "short burst, last invocation wins"
      (let [f-debounced (debounce f interval)]
        (reset! calls [])
        (dotimes [n 10]
          (f-debounced n))
        (Thread/sleep (* 2 interval))
        (is (= [9] @calls))))
    (testing "two unrelated invocations, both executed"
      (let [f-debounced (debounce f interval)]
        (reset! calls [])
        (f-debounced 1)
        (Thread/sleep (* 2 interval))
        (f-debounced 2)
        (Thread/sleep (* 2 interval))
        (is (= [1 2] @calls))))))

(deftest memoize-with-ttl-test
  (let [counter (atom 0)
        f (fn [behavior & args]
            (swap! counter inc)
            (case behavior
              :throw  (throw (IllegalArgumentException. "Crash as requested"))
              :return (first args)))]
    (testing "Memoized function returns the same values as the underlying function"
      (let [ttl 100, f-memoized (memoize-with-ttl f ttl)]
        (doseq [ret [nil 0 "potato" (fn [] nil)]]
          (is (= ret
                 (f :return ret)
                 (f-memoized :return ret))))))
    (testing "Non-expired cache entry, returns cached value"
      (let [ttl 100, f-memoized (memoize-with-ttl f ttl)]
        (reset! counter 0)
        (dotimes [_ 3]
          (let [ret (f-memoized :return "value")]
            (is (= ret "value"))))
        (is (= @counter 1))))
    (testing "Non-expired cache entry, throws cached exception"
      (let [ttl 100, f-memoized (memoize-with-ttl f ttl)]
        (reset! counter 0)
        (dotimes [_ 3]
          (is (thrown? IllegalArgumentException (f-memoized :throw))))
        (is (= @counter 1))))
    (testing "Expired cache entry, invokes the underlying function"
      (let [ttl 2, f-memoized (memoize-with-ttl f ttl)]
        (reset! counter 0)
        (dotimes [_ 3]
          (Thread/sleep (* 2 ttl))
          (let [ret (f-memoized :return "value")]
            (is (= ret "value"))))
        (is (= @counter 3))))
    (testing "Empty cache, invokes the underlying function"
      (let [ttl 100, f-memoized (memoize-with-ttl f ttl)]
        (reset! counter 0)
        (dotimes [n 3]
          (let [ret (f-memoized :return n)]
            (is (= ret n))))
        (is (= @counter 3))))))
