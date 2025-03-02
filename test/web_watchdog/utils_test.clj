(ns web-watchdog.utils-test
  (:require [clojure.test :refer [deftest is testing]]
            [web-watchdog.utils :refer [debounce memoize-with-ttl]]))

(deftest debounce-test
  (let [calls (atom [])
        f (fn [x]
            (swap! calls conj x)
            (if (<= 0 x)
              x
              (throw (IllegalArgumentException. "mock throw"))))
        interval 20]
    (testing "single successful invocation, eventually executed and value returned"
      (reset! calls [])
      (let [f-debounced (debounce f interval)
            result (f-debounced 1)]
        (is (false? (realized? result)))
        (Thread/sleep (* 2 interval))
        (is (true? (realized? result)))
        (is (= 1 @result))
        (is (= [1] @calls))))
    (testing "single throwing invocation, eventually executed and exception returned"
      (reset! calls [])
      (let [f-debounced (debounce f interval)
            result (f-debounced -1)]
        (is (false? (realized? result)))
        (Thread/sleep (* 2 interval))
        (is (true? (realized? result)))
        (is (true? (contains? @result :exception)))
        (is (= [-1] @calls))))
    (testing "short burst, last invocation wins"
      (reset! calls [])
      (let [f-debounced (debounce f interval)
            results (for [n (range 10)] (f-debounced n))]
        (doall results)
        (is (= 1 (-> results set count)))  ;; Identical promise returned for the whole burst.
        (is (true? (not-any? realized? results)))
        (Thread/sleep (* 2 interval))
        (is (true? (every? realized? results)))
        (is (= #{9} (->> results (mapv deref) set)))
        (is (= [9] @calls))))
    (testing "two unrelated invocations, both executed"
      (reset! calls [])
      (let [f-debounced (debounce f interval)]
        (let [result-1 (f-debounced 1)
              _ (Thread/sleep (* 2 interval))
              result-2 (f-debounced 2)
              _ (Thread/sleep (* 2 interval))]
          (is (not= result-1 result-2))  ;; Two different bursts, two different promises.
          (is (true? (every? realized? [result-1 result-2])))
          (is (= 1 @result-1))
          (is (= 2 @result-2))
          (is (= [1 2] @calls)))))
    (testing "parameterless function"
      (reset! calls [])
      (let [f0 (fn [] (f "called"))
            f0-debounced (debounce f0 interval)]
        (f0-debounced)
        (is (= [] @calls))
        (Thread/sleep (* 2 interval))
        (is (= ["called"] @calls))))))

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
