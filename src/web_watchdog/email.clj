(ns web-watchdog.email
  (:require [clojure.string]
            [hiccup.util]
            [integrant.core :as ig]
            [plumula.diff :as pd]
            [postal.core]
            [web-watchdog.conditions :as conditions]
            [web-watchdog.core :as core]
            [web-watchdog.logging :as logging :refer [logd logw]]
            [web-watchdog.utils :as utils]))

;; Generating diff emails.

(defn trim-diffs
  "Trims text of the given diffs, so that first diff is trimmed to '...suffix',
   all middle diffs trimmed to 'prefix...suffix' and the last diff to 'prefix...'."
  [limit diffs]
  (let [last-idx (dec (count diffs))]
    (mapv (fn [idx diff]
            (if (not= ::pd/equal (::pd/operation diff))
              diff  ;; Don't trim insert/delete snippets.
              (let [mode (cond (= 0 idx) :left, (= last-idx idx) :right, :else :middle)]
                (update diff ::pd/text utils/trim mode limit))))
          (range)
          diffs)))


;; Generic crafting of email contents.

(defn mail-subject [site change-type]
  (condp = change-type
    :content-changed
    (format "[Web-watchdog change] %s" (:title site))
    :site-failing
    (format "[Web-watchdog site down] %s" (:title site))))

(defn js-unicode-to-html [s]
  (clojure.string/replace
   s
   #"\\u[0-9a-fA-F]{4}"
   (fn [match] (str "&#x" (subs match 2) ";"))))

(defn escape-html [s]
  (-> s hiccup.util/escape-html js-unicode-to-html))

(defn mail-body-html [old-site new-site change-type fmt]
  (condp = change-type
    :content-changed
    (str "<html>"
         "<head>"
         "  <style>"
         "    p:not(:first-of-type) {margin-top: 1em;}"
         "    .content {display: inline-block; font-family: roboto; background-color: #eee; padding: 4px; border: 1px solid #ccc; border-radius: 4px;}"
         "    .content .diff-insert {background-color: #aaf2aa}"
         "    .content .diff-delete {background-color: #ffcdd2}"
         "  </style>"
         "</head>"
         "<body>"
         (format "<p>The content at <a href='%s'>%s</a> has changed.</p>"
                 (:url new-site)
                 (-> new-site :title escape-html))
         (case fmt
           "new-only"
           (format "<p>New content: <span class='content'>%s</span></p>"
                   (-> new-site :state :content-snippet escape-html))
           "old-new"
           (str
            (format "<p>Previous content (from %s): <span class='content'>%s</span></p>"
                    (-> old-site :state :last-change-time utils/epoch->now-aware-str)
                    (-> old-site :state :content-snippet escape-html))
            (format "<p>New content: <span class='content'>%s</span></p>"
                    (-> new-site :state :content-snippet escape-html)))
           "inline-diff"
           (let [old-content (get-in old-site [:state :content-snippet])
                 new-content (get-in new-site [:state :content-snippet])
                 diff->html (fn [diff]
                              (let [attr (case (::pd/operation diff)
                                           ::pd/insert "class='diff-insert'"
                                           ::pd/delete "class='diff-delete'"
                                           "")]
                                (str "<span " attr "'>" (-> diff ::pd/text escape-html) "</span>")))]
             (as-> (pd/diff old-content new-content ::pd/cleanup ::pd/cleanup-semantic) $
               (trim-diffs 50 $)
               (mapv diff->html $)
               (clojure.string/join "\n" $)
               (str "<span class='content'>" $ "</span>"))))
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

(defrecord SmtpEmailSender [cfg]
  EmailSender
  (send-email [this to subject body-html]
    (postal.core/send-message
     {:host (:host cfg), :user (:user cfg), :pass (:password cfg), :port 465, :tls true}
     {:from (:from cfg)
      :to to
      :subject subject
      :body [{:type "text/html; charset=utf-8", :content body-html}]})))

(derive ::smtp-sender :web-watchdog.system/email-sender)

(defmethod ig/init-key ::smtp-sender [_ deps]
  (let [cfg (select-keys deps [:user :password :host :from])]
    {:impl (->SmtpEmailSender cfg)}))

(defmethod ig/resolve-key ::smtp-sender [_ {:keys [impl]}]
  impl)


;; Notifying about app state changes and notifying  through email.

(defn site-condition-satisfied [old-site new-site]
  (if-let [condition (get-in new-site [:email-notification :condition])]
    (try
      (conditions/eval-expr condition [old-site new-site])
      (catch Exception e
        (logw e "Exception while evaluating condition '%s' of site '%s'." condition (:title new-site))
        ;; On failure, err on the side of sending a change notification.
        true))
    ;; No condition is trivially satisfied.
    true))

(defn notify-site-changed! [sender-impl old-site new-site change-type]
  (let [email-to (get-in new-site [:email-notification :to])
        fmt (get-in new-site [:email-notification :format])]
    (when (not-empty email-to)
      (send-email sender-impl
                  email-to
                  (mail-subject new-site change-type)
                  (mail-body-html old-site new-site change-type fmt)))))

(defn notify-about-site-changes! [sender-impl old-state new-state]
  (dorun
   (map (fn [[old-site new-site]]
          (when-let [change-type (core/site-change-type old-site new-site)]
            (logd "Change of type %s detected at '%s'" change-type (:title new-site))
            (if-not (site-condition-satisfied old-site new-site)
              (logd "Skipping email notification for '%s' due to its condition" (:title new-site))
              (notify-site-changed! sender-impl old-site new-site change-type))))
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
