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

(defn truncate-at-max
  "Ensure that a string is no longer that the limit"
  [s limit]
  (let [size (count s)]
    (if (> size limit)
      (str (subs s 0 limit) " ... [truncated total size of " size "]")
      s)))
