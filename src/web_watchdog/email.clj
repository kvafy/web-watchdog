(ns web-watchdog.email
  (:require [hiccup.util :refer [escape-html]]
            [integrant.core :as ig]
            [postal.core]
            [web-watchdog.core :as core]
            [web-watchdog.utils :as utils]))

;; Generic crafting of email contents.

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
         (format "<p>There seems to be something new on <a href='%s'>%s</a>.</p>"
                 (:url new-site)
                 (-> new-site :title escape-html))
         (format "<p>Previous content (from %s): <span class='content'>%s</span></p>"
                 (-> old-site :state :last-change-utc utils/epoch->now-aware-str)
                 (-> old-site :state :content-snippet escape-html))
         (format "<p>New content: <span class='content'>%s</span></p>"
                 (-> new-site :state :content-snippet escape-html))
         "</body>"
         "</html>")
    :site-failing
    (str "<html>"
         "<body>"
         "<p>" (format "Site <a href='%s'>%s</a> is failing." (:url new-site) (-> new-site :title escape-html)) "</p>"
         "</body>"
         "</html>")))


;; Sending emails through GMail.

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

(derive ::gmail-sender :web-watchdog.system/email-sender)

(defmethod ig/init-key ::gmail-sender [_ _]
  {:impl (->GmailEmailSender)})

(defmethod ig/resolve-key ::gmail-sender [_ {:keys [impl]}]
  impl)


;; Notifying about app state changes and notifying  through email.

(defn notify-site-changed! [sender-impl old-site new-site change-type]
  (when (not-empty (:emails new-site))
    (send-email sender-impl
                (:emails new-site)
                (mail-subject new-site change-type)
                (mail-body-html old-site new-site change-type))))

(defn notify-about-site-changes! [sender-impl old-state new-state]
  (dorun
   (map (fn [[old-site new-site]]
          (when-let [change-type (core/site-change-type old-site new-site)]
            (utils/log (format "Change of type %s detected at [%s]" change-type (:title new-site)))
            (notify-site-changed! sender-impl old-site new-site change-type)))
         ; filter out change of type "site added/removed from watched sites list"
        (core/common-sites old-state new-state))))


;; The email notifier component.

(derive ::notifier :web-watchdog.system/app-state-observer)

(defmethod ig/init-key ::notifier [_ {:keys [app-state email-sender]}]
  (add-watch app-state
             ::email-notifier
             (fn [_ _ old-state new-state]
               (when (not= old-state new-state)
                 (notify-about-site-changes! email-sender old-state new-state))))
  {:watched-atom app-state})

(defmethod ig/halt-key! ::notifier [_ {:keys [watched-atom]}]
  (remove-watch watched-atom ::email-notifier))
