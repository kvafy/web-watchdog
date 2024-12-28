(ns web-watchdog.networking
  (:require [clj-http.client :as client]
            [clj-http.cookies]
            [integrant.core :as ig]
            [web-watchdog.utils :as utils]))

(defn authenticate-site [url opts]
  )

(defn download
  "Downloads contents of the given URL.

   Returns a 2-tuple `[body ex-info]` in which exactly one item is non-nil."
  [url]
  (let [cookie-store (clj-http.cookies/cookie-store)
        opts {:insecure? true  ;; accept self-signed SSL certs
              :cookie-store cookie-store
              :headers
              ;; Avoid 403 from certain sites.
              {"User-Agent" "Mozilla/5.0 (Windows NT 6.1;) Gecko/20100101 Firefox/13.0.1"}}]
    (try
      (authenticate-site url opts)
      (let [ok-response (client/get url opts)]
        [(:body ok-response) nil])
      (catch java.lang.Exception thrown
        (let [ex-nfo (if (instance? clojure.lang.ExceptionInfo thrown)
                       thrown
                       (ex-info (. thrown getMessage) (Throwable->map thrown)))]
          (utils/log (format "Failed to download [%s] due to: %s" url (ex-message ex-nfo)))
          [nil ex-nfo])))))


;; The downloader function component.

(derive ::web-downloader :web-watchdog.system/download-fn)

(defmethod ig/init-key ::web-downloader [_ {:keys [cache-ttl-ms]}]
  (utils/memoize-with-ttl #'download cache-ttl-ms))
