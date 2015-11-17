(ns web-watchdog.utils)

(defn log [msg]
  (printf "[%s] %s\n" (java.util.Date.) msg)
  (flush))

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

(defn update-map-keys
  "Walks given collection recursively and if encounters a map with
  given key, applies the given function to the associated value."
  [col k f]
  (cond
    (map? col)
    (loop [m        col
           all-keys (keys m)]
      (if (empty? all-keys)
        m
        (let [[k' & ks'] all-keys]
          (cond
            (= k' k)       (recur (assoc m k (f (k m))) ks')
            (coll? (k' m)) (recur (assoc m k' (update-map-keys (k' m) k f)) ks')
            :else          (recur m ks')))))
    (coll? col)
    (into (empty col) (map #(update-map-keys % k f) col))
    :else
    col))
