(ns web-watchdog.utils
  (:require [clojure.core.async :as async :refer [<!, >!]])
  (:import [java.time Instant ZoneId]
           [java.time.format DateTimeFormatter]
           [java.time.temporal ChronoField]))

(defn log [msg]
  (printf "[%s] %s\n" (java.util.Date.) msg)
  (flush))

(defn now-ms []
  (System/currentTimeMillis))

(defn millis-to-local-time [millis]
  (let [tz (ZoneId/systemDefault)]
    (.. (Instant/ofEpochMilli millis) (atZone tz) (toLocalDateTime))))

(defn epoch->now-aware-str [millis]
  (let [local-now (millis-to-local-time (now-ms))
        local-arg (millis-to-local-time millis)
        same-field? (fn [^ChronoField field]
                      (= (.get local-now field) (.get local-arg field)))
        day-fmt (case (mod (.get local-arg ChronoField/DAY_OF_MONTH) 10)
                  1 "d'st'"
                  2 "d'nd'"
                  3 "d'rd'"
                  "d'th'")
        fmt (cond (not (same-field? ChronoField/YEAR))
                  (str "H:mm, " day-fmt " LLL u")
                  (not (same-field? ChronoField/DAY_OF_YEAR))
                  (str "H:mm, " day-fmt " LLL")
                  :else
                  "H:mm 'today'")]
    (.format local-arg (DateTimeFormatter/ofPattern fmt))))

(defn md5
  "Generate a md5 checksum for the given string"
  [token]
  (let [hash-bytes
         (doto (java.security.MessageDigest/getInstance "MD5")
               (.reset)
               (.update (.getBytes token)))]
       (.toString
         (new java.math.BigInteger 1 (.digest hash-bytes)) ; Positive and the size of the number
         16))) ; Use base16 i.e. hex

(defn debounce
  "Returns a new function that accepts the same parameters as `f` but is
   debounced. The decorated function returns a promise that will eventually be
   realized with the value of the `f` execution, or with `{:exception ex}`.
   From a burst of invocations within the given interval, the last one wins."
  [f interval-ms]
  (let [proceed-ch-atom (atom (async/chan))
        restart-ch (async/chan)
        pending-result-atom (atom (promise))]
    ;; Where `f` actually gets eventually executed...
    (async/go-loop []
      (let [[args ch] (async/alts! [restart-ch @proceed-ch-atom] :priority true)]
        (when (= ch @proceed-ch-atom)
          (try
            (deliver @pending-result-atom (apply f args))
            (catch Exception e (deliver @pending-result-atom {:exception e}))
            (finally (reset! pending-result-atom (promise))))))
      (recur))
    ;; Debounced decorator of `f`.
    (fn [& args]
      (let [new-proceed-ch (async/chan)
            args (if (nil? args) [] args)]  ;; Sanitize - `nil` cannot be put on a channel
        (reset! proceed-ch-atom new-proceed-ch)
        (async/go (>! restart-ch :restart)
                  (<! (async/timeout interval-ms))
                  (>! new-proceed-ch args))
        @pending-result-atom))))

(defn memoize-with-ttl [f ttl-ms]
  (let [cache (atom {})] ;; Format: {<f-args> {:result <f-return>, :timestamp <epoch-millis>}}
    (letfn [(not-expired? [entry]
              (< (now-ms) (+ (:timestamp entry) ttl-ms)))
            (lookup-non-expired [args]
              (when-let [entry (get @cache args)]
                (when (not-expired? entry)
                  entry)))
            (insert-cache! [args entry]
              (swap! cache #(assoc % args entry)))
            (filter-map-values [pred m]
              (into {} (filter (fn [[k v]] (pred v)) m)))
            (remove-expired! []
              (swap! cache #(filter-map-values not-expired? %)))
            (call-uncached [args]
              (let [timestamp (now-ms)]
                (try
                  (let [result (apply f args)]
                    {:result result, :timestamp timestamp})
                  (catch Throwable e
                    {:throw e, :timestamp timestamp}))))
            (unwrap-entry [entry]
              (if (:throw entry)
                (throw (:throw entry))
                (:result entry)))]
      (fn [& args]
        (if-let [cached-entry (lookup-non-expired args)]
          (unwrap-entry cached-entry)
          (let [new-entry (call-uncached args)]
            (remove-expired!)
            (insert-cache! args new-entry)
            (unwrap-entry new-entry)))))))

(defn trim
  "Trims the 'text' to be at most 'limit' characters long, inserting '...'
   placeholder for the omitted portion. 'mode' is one of :left, :middle ,:right."
  [text mode limit]
  (let [text-len (count text)]
    (cond
      ;; Text already within the lenght limit, nothing to do.
      (<= text-len limit)
      text
      ;; Limit too small, nothing would be preserved from the original text.
      (<= limit (count "..."))
      (apply str (repeat limit \.))
      ;; There is a meaningful shortening to be done.
      :else
      (let [len-to-drop (+ (- text-len limit) (count "..."))
            [trim-start trim-end] (case mode
                                    :left   [0 len-to-drop]
                                    :middle (let [start (/ (- text-len len-to-drop) 2)]
                                              [start (+ start len-to-drop)])
                                    :right  [(- text-len len-to-drop) text-len])]
        (str (subs text 0 trim-start) "..." (subs text trim-end text-len))))))

(defn truncate-at-max
  "Ensure that a string is no longer that the limit"
  [s limit]
  (let [size (count s)]
    (if (> size limit)
      (str (subs s 0 limit) " ... [truncated total size of " size "]")
      s)))
