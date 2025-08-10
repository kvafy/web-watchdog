(ns web-watchdog.networking
  (:require [cheshire.core :as cheshire]
            [clj-http.client :as clj-http]
            [clj-http.cookies]
            [clojure.string]
            [integrant.core :as ig]
            [web-watchdog.logging :as logging :refer [logd logw]]
            [web-watchdog.utils :as utils]))

(def default-user-agent
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36")

(defn site->clj-http-opts [site]
  (let [{:keys [method headers query-params form-params retries allow-insecure] :as request} (:request site)
        base-opts {:url (or (:url request) (:url site))
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
                                  (logd "Retrying download of site '%s', failed due to: %s" (:title site) (ex-message ex)))
                                retry?)))
                     allow-insecure
                     (assoc :insecure? true))]
    (merge-with merge base-opts extra-opts)))

(defn site->clj-http-opts-flaresolverr [site port]
  (let [request-cfg (:request site)
        post-data (when-let [form-params (:form-params request-cfg)]
                   ;; Leverage clj-http encoding.
                   (clj-http/generate-query-string form-params "application/x-www-form-urlencoded" nil))
        payload (cond-> {}
                  true (assoc "cmd" (case (get request-cfg :method "GET")
                                      "GET"  "request.get"
                                      "POST" "request.post"))
                  true (assoc "url" (or (:url request-cfg) (:url site)))
                  post-data (assoc "postData" post-data))
        unhandled-cfg (-> request-cfg (dissoc :client-impl :method :form-params :url))]
    (when (not-empty unhandled-cfg)
      (logw "FlareSolverr ignoring :request properties for site '%s': %s" (:title site) (keys unhandled-cfg)))
    {:url (format "http://localhost:%d/v1" port)
     :headers {"Content-Type" "application/json"}
     :method :post
     :body (cheshire/encode payload)}))

(defn download
  "Downloads contents of the given URL.
   Returns a 2-tuple `[body ex-info]` in which exactly one item is non-nil."
  [site opts]
  (try
    (case (get-in site [:request :client-impl] "clj-http")
      "clj-http"
      (let [_ (logd "Downloading '%s' using clj-http ..." (:title site))
            req (site->clj-http-opts site)
            ok-response (clj-http/request req)]
        [(:body ok-response) nil])

      "FlareSolverr"
      (let [_ (logd "Downloading '%s' using FlareSolverr ..." (:title site))
            req (site->clj-http-opts-flaresolverr site (:flaresolverr-port opts))
            json-response (-> req (clj-http/request) :body (cheshire/decode))]
        (cond
          (not= "ok" (get json-response "status"))
          [nil (ex-info (str ` "FlareSolverr error: " (get json-response "message")) {})]

          (not= 200 (get-in json-response ["solution" "status"]))
          [nil (ex-info (format "FlareSolverr returned HTTP %d" (get-in json-response ["solution" "status"])) {})]

          :else
          [(get-in json-response ["solution" "response"]) nil])))
    (catch java.lang.Exception thrown
      (let [ex-nfo (if (instance? clojure.lang.ExceptionInfo thrown)
                     thrown
                     (ex-info (. thrown getMessage) (Throwable->map thrown)))]
        (logw ex-nfo "Failed to download '%s'." (:title site))
        [nil ex-nfo]))))

;; The downloader function component.

(derive ::web-downloader :web-watchdog.system/download-fn)

(defmethod ig/init-key ::web-downloader [_ {:keys [cache-ttl-ms flaresolverr-port]}]
  (let [download-memoized (utils/memoize-with-ttl #'download cache-ttl-ms)]
    (fn [site]
      ;; Strip out irrelevant and volatile site fields to avoid cache missses.
      (let [pruned-site (dissoc site :state)
            opts {:flaresolverr-port flaresolverr-port}]
        (download-memoized pruned-site opts)))))
