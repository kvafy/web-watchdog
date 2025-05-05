(ns web-watchdog.networking
  (:require [clj-http.client :as client]
            [clj-http.cookies]
            [clojure.string]
            [integrant.core :as ig]
            [web-watchdog.utils :as utils]))

(def default-user-agent
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36")

(defn site->clj-http-opts [site]
  (let [{:keys [method headers query-params form-params retries allow-insecure]} (:request site)
        base-opts {:url (:url site)
                   :method (-> method (or "GET") clojure.string/lower-case keyword)
                   :headers {"User-Agent" default-user-agent}
                   :cookie-store (clj-http.cookies/cookie-store)}
        extra-opts (cond-> {}
                     headers
                     (assoc :headers headers)
                     query-params
                     (assoc :query-params query-params)
                     form-params
                     (assoc :form-params form-params)
                     retries
                     (assoc :retry-handler
                            (fn [ex try-count _http-context]
                              (let [retry? (<= try-count retries)]
                                (when retry?
                                  (utils/log (format "Retrying download of site '%s', failed due to: %s"
                                                     (:url site) (ex-message ex))))
                                retry?)))
                     allow-insecure
                     (assoc :insecure? true))]
    (merge-with merge base-opts extra-opts)))

(defn download
  "Downloads contents of the given URL.

   Returns a 2-tuple `[body ex-info]` in which exactly one item is non-nil."
  [{:keys [url] :as site}]
  (try
    (let [req (site->clj-http-opts site)
          ok-response (client/request req)]
      [(:body ok-response) nil])
    (catch java.lang.Exception thrown
      (let [ex-nfo (if (instance? clojure.lang.ExceptionInfo thrown)
                     thrown
                     (ex-info (. thrown getMessage) (Throwable->map thrown)))]
        (utils/log (format "Failed to download [%s] due to: %s" url (ex-message ex-nfo)))
        [nil ex-nfo]))))

;; The downloader function component.

(derive ::web-downloader :web-watchdog.system/download-fn)

(defmethod ig/init-key ::web-downloader [_ {:keys [cache-ttl-ms]}]
  (let [download-memoized (utils/memoize-with-ttl #'download cache-ttl-ms)]
    (fn [site]
      ;; Strip out irrelevant and volatile site fields to avoid cache missses.
      (let [pruned-site (dissoc site :state)]
        (download-memoized pruned-site)))))
