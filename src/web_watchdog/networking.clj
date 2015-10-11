(ns web-watchdog.networking
  (:require [clojure.java.io :as io]
            [postal.core]))

(defn download [url]
  (let [cm (java.net.CookieManager.)]
    (java.net.CookieHandler/setDefault cm))
  (try
    (with-open [in (io/reader url)]
      (slurp in))
    (catch java.io.IOException ex nil)))

(defn notify-site-changed! [site]
  (when (not-empty (:emails site))
    (let [subject (format "[Web-watchdog] %s" (:title site))
          body    (format "There seems to be something new on %s.\nCheck out %s." (:title site) (:url site))]
      (postal.core/send-message {:from "mailer@webwatchdog.com"
                                 :to (:emails site)
                                 :subject subject
                                 :body body}))))
