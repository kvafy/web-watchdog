(ns web-watchdog.networking
  (:require [clj-http.client :as client]
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

(defn mail-body [site change-type]
  (condp = change-type
    :content-changed
      (format "There seems to be something new on %s.\nCheck out %s." (:title site) (:url site))
    :site-failing
      (format "Site %s at %s is failing." (:title site) (:url site))))

(defn notify-site-changed! [site change-type]
  (when (not-empty (:emails site))
    (postal.core/send-message
     {:host "smtp.gmail.com"
      :user (System/getenv "MAILER_USER")
      :pass (System/getenv "MAILER_PASSWORD")
      :port 587
      :tls true}
     {:from "mailer@webwatchdog.com"
      :to (:emails site)
      :subject (mail-subject site change-type)
      :body (mail-body site change-type)})))
