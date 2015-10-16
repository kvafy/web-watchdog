(ns web-watchdog.networking
  (:require [clojure.java.io :as io]
            [postal.core]
            [web-watchdog.utils :as utils]))

(defn download [url]
  (let [cm (java.net.CookieManager.)]
    (java.net.CookieHandler/setDefault cm))
  (try
    (with-open [in (io/reader url)]
      (slurp in))
    (catch java.io.IOException ex
      (utils/log (format "Failed to download [%s] due to: %s" url (.toString ex)))
      nil)))

(defn notify-site-changed! [site change-type]
  (when (not-empty (:emails site))
    (let [subject (format "[Web-watchdog] %s" (:title site))
          body    (condp = change-type
                    :content-changed
                    (format "There seems to be something new on %s.\nCheck out %s." (:title site) (:url site)))]
      (postal.core/send-message {:from "mailer@webwatchdog.com"
                                 :to (:emails site)
                                 :subject subject
                                 :body body}))))
