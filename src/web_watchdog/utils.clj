(ns web-watchdog.utils
  (:require [clojure.core.async :as async :refer [<!, >!]])
  (:import [java.time Instant ZoneId]))

(defn log [msg]
  (printf "[%s] %s\n" (java.util.Date.) msg)
  (flush))

(defn millis-to-local-time [millis]
  (let [tz (ZoneId/systemDefault)]
    (.. (Instant/ofEpochMilli millis) (atZone tz) (toLocalDateTime))))

(defn now-utc []
  (System/currentTimeMillis))

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
   debounced. The function doesn't return anything and is executed only for
   side-effects.
   From a burst of invocations within the given interval, the last one wins."
  [f interval-ms]
  (let [proceed-ch-atom (atom (async/chan))
        restart-ch (async/chan)]
    ;; Where `f` actually gets eventually executed...
    (async/go-loop []
      (let [[args ch] (async/alts! [restart-ch @proceed-ch-atom] :priority true)]
        (when (= ch @proceed-ch-atom)
          (apply f args)))
      (recur))
    ;; Debounced decorator of `f`.
    (fn [& args]
      (let [new-proceed-ch (async/chan)]
        (reset! proceed-ch-atom new-proceed-ch)
        (async/go (>! restart-ch :restart)
                  (<! (async/timeout interval-ms))
                  (>! new-proceed-ch args))
        nil))))

(defn memoize-with-ttl [f ttl-ms]
  (let [cache (atom {})] ;; Format: {<f-args> {:result <f-return>, :timestamp <epoch-millis>}}
    (letfn [(not-expired? [entry]
              (< (now-utc) (+ (:timestamp entry) ttl-ms)))
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
              (let [timestamp (now-utc)]
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

(defn truncate-at-max
  "Ensure that a string is no longer that the limit"
  [s limit]
  (let [size (count s)]
    (if (> size limit)
      (str (subs s 0 limit) " ... [truncated total size of " size "]")
      s)))
