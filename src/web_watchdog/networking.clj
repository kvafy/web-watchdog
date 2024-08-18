(ns web-watchdog.networking
  (:require [clj-http.client :as client]
            [hiccup.util :refer [escape-html]]
            [postal.core]
            [web-watchdog.utils :as utils]))

(defn download [url]
  (let [cm (java.net.CookieManager.)]
    (java.net.CookieHandler/setDefault cm))
  (try
    [(-> url (client/get {:insecure? true}) :body) nil]
    (catch java.lang.Exception ex
      (let [err-msg (.toString ex)]
        (utils/log (format "Failed to download [%s] due to: %s" url err-msg))
        [nil err-msg]))))

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
      [{:type "text/html"
        :content (str "<html>"
                      "<head>"
                      "  <style>"
                      "    p:not(:first-of-type) {margin-top: 1em;}"
                      "    .content {font-family: roboto; background-color: #eee; padding: 4px; border-radius: 4px;}"
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
