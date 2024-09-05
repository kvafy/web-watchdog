(ns web-watchdog.networking
  (:require [clj-http.client :as client]
            [hiccup.util :refer [escape-html]]
            [postal.core]
            [web-watchdog.utils :as utils]))

(defn download
  "Downloads contents of the given URL.

   Returns a 2-tuple `[body ex-info]` in which exactly one item is non-nil."
  [url]
  (let [opts {:insecure? true  ;; accept self-signed SSL certs
              }
        cm (java.net.CookieManager.)]
    (java.net.CookieHandler/setDefault cm)
    (try
      (let [ok-response (client/get url opts)]
        [(:body ok-response) nil])
      (catch java.lang.Exception thrown
        (let [ex-nfo (if (instance? clojure.lang.ExceptionInfo thrown)
                       thrown
                       (ex-info (. thrown getMessage) (Throwable->map thrown)))]
          (utils/log (format "Failed to download [%s] due to: %s" url (ex-message ex-nfo)))
          [nil ex-nfo])))))

(def download-with-cache
  (utils/memoize-with-ttl #'download (* 10 1000)))

(defn mail-subject [site change-type]
  (condp = change-type
    :content-changed
      (format "[Web-watchdog change] %s" (:title site))
    :site-failing
      (format "[Web-watchdog site down] %s" (:title site))))

(defn mail-body [old-site new-site change-type]
  (condp = change-type
    :content-changed
      [{:type "text/html; charset=utf-8"
        :content (str "<html>"
                      "<head>"
                      "  <style>"
                      "    p:not(:first-of-type) {margin-top: 1em;}"
                      "    .content {display: inline-block; font-family: roboto; background-color: #eee; padding: 4px; border: 1px solid #ccc; border-radius: 4px;}"
                      "  </style>"
                      "</head>"
                      "<body>"
                      "<p>" (format "There seems to be something new on <a href='%s'>%s</a>." (:url new-site) (escape-html (:title new-site))) "</p>"
                      (format "<p>Previous content: <span class='content'>%s</span></p>" (escape-html (-> old-site :state :content-snippet)))
                      (format "<p>New content: <span class='content'>%s</span></p>" (escape-html (-> new-site :state :content-snippet)))
                      "</body>")}]
    :site-failing
      (format "Site %s at %s is failing." (:title new-site) (:url new-site))))

(defn notify-site-changed! [old-site new-site change-type]
  (when (not-empty (:emails new-site))
    (postal.core/send-message
     {:host "smtp.gmail.com"
      :user (System/getenv "MAILER_USER")
      :pass (System/getenv "MAILER_PASSWORD")
      :port 587
      :tls true}
     {:from "mailer@webwatchdog.com"
      :to (:emails new-site)
      :subject (mail-subject new-site change-type)
      :body (mail-body old-site new-site change-type)})))
