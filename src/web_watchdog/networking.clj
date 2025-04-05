(ns web-watchdog.networking
  (:require [clj-http.client :as client]
            [clj-http.cookies]
            [clojure.string]
            [integrant.core :as ig]
            [web-watchdog.utils :as utils]))

(defn opts-for-site
  "Hacky programmatic solution for tweaking requests (headers, cookies etc.) until not supported
   declaratively in the config.
   Must return (modified) `opts`."
  [url opts]
  (cond
    (clojure.string/starts-with? url "https://api.tfl.gov.uk/")
    (assoc-in opts [:headers "Accept"] "application/xml")
  )

(defn download
  "Downloads contents of the given URL.

   Returns a 2-tuple `[body ex-info]` in which exactly one item is non-nil."
  [url]
  (let [cookie-store (clj-http.cookies/cookie-store)
        default-opts {:headers
                      ;; Avoid 403 from certain sites.
                      {"User-Agent" "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36"}
                      :cookie-store cookie-store}]
    (try
      (let [opts (opts-for-site url default-opts)
            ok-response (client/get url opts)]
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
