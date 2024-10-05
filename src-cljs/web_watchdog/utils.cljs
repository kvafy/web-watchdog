(ns web-watchdog.utils
  (:require [clojure.string]
            [goog.string :as gstring]
            [goog.string.format]))

(defn today? [date]
  (let [now  (js/Date.)]
    (and (= (.getDate date) (.getDate now))
         (= (.getMonth date) (.getMonth now))
         (= (.getFullYear date) (.getFullYear now)))))

(defn utc->date-str [millis]
  (when millis
    (let [date (js/Date. millis)
          Y    (.getFullYear date)
          M    (inc (.getMonth date))
          D    (.getDate date)
          H    (.getHours date)
          m    (.getMinutes date)]
      (if (today? date)
        (gstring/format "%d:%02d" H m)
        (gstring/format "%d-%02d-%02d %d:%02d" Y M D H m)))))

(defn duration-pprint [millis]
  (let [conversions ["second(s)" 1000
                     "minute(s)" 60
                     "hour(s)"   60
                     "day(s)"    24]]
    (loop [unit        "millisecond(s)"
           value       millis
           conversions conversions]
      (if (empty? conversions)
        (gstring/format "%.1f %s" value unit)
        (let [[next-unit divider & next-conversions] conversions
              next-value (/ value divider)]
          (if (< (.abs js/Math next-value) 1)
            (gstring/format "%.1f %s" value unit)
            (recur next-unit next-value next-conversions)))))))

(defn keyword-pprint [kw]
  (-> kw
      name
      (clojure.string/replace ":" "")
      (clojure.string/replace "-" " ")
      (clojure.string/capitalize)))

(defn transform-kv-pair [[k v]]
  (let [transforms {:check-interval-ms
                    [(constantly "Check interval") duration-pprint]}
        default-transform [keyword-pprint identity]]
    (let [[k-trans v-trans] (get transforms k default-transform)]
      [(k-trans k) (v-trans v)])))
