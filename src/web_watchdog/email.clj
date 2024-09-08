(ns web-watchdog.email
  (:require [hiccup.util :refer [escape-html]]
            [postal.core]))

(defn mail-subject [site change-type]
  (condp = change-type
    :content-changed
    (format "[Web-watchdog change] %s" (:title site))
    :site-failing
    (format "[Web-watchdog site down] %s" (:title site))))

(defn mail-body-html [old-site new-site change-type]
  (condp = change-type
    :content-changed
    (str "<html>"
         "<head>"
         "  <style>"
         "    p:not(:first-of-type) {margin-top: 1em;}"
         "    .content {display: inline-block; font-family: roboto; background-color: #eee; padding: 4px; border: 1px solid #ccc; border-radius: 4px;}"
         "  </style>"
         "</head>"
         "<body>"
         "<p>" (format "There seems to be something new on <a href='%s'>%s</a>." (:url new-site) (-> new-site :title escape-html)) "</p>"
         (format "<p>Previous content: <span class='content'>%s</span></p>" (-> old-site :state :content-snippet escape-html))
         (format "<p>New content: <span class='content'>%s</span></p>" (-> new-site :state :content-snippet escape-html))
         "</body>"
         "</html>")
    :site-failing
    (str "<html>"
         "<body>"
         "<p>" (format "Site <a href='%s'>%s</a> is failing." (:url new-site) (-> new-site :title escape-html)) "</p>"
         "</body>"
         "</html>")))

(defprotocol EmailSender
  "Abstraction for sending emails"
  (send-email [this to subject body-html]))

(defrecord GmailEmailSender []
  EmailSender
  (send-email [this to subject body-html]
    (postal.core/send-message
     {:host "smtp.gmail.com"
      :user (System/getenv "MAILER_USER")
      :pass (System/getenv "MAILER_PASSWORD")
      :port 587
      :tls true}
     {:from "mailer@webwatchdog.com"
      :to to
      :subject subject
      :body [{:type "text/html; charset=utf-8"
              :content body-html}]})))

(defn notify-site-changed! [sender-impl old-site new-site change-type]
  (when (not-empty (:emails new-site))
    (send-email sender-impl
                (:emails new-site)
                (mail-subject new-site change-type)
                (mail-body-html old-site new-site change-type))))
