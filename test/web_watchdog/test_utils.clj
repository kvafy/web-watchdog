(ns web-watchdog.test-utils
  (:require [integrant.core :as ig]
            [web-watchdog.email]
            [web-watchdog.system :as system]
            [web-watchdog.utils :as utils]))

(defn site-emails [label]
  [(format "%s@watcher.com" label)])

(defn build-site
  ([label] (build-site label {}))
  ([label overrides]
   (let [default-site {:id     (format "site-id-%s" label)
                       :title  (format "Site %s" label)
                       :url    (format "http://site-%s.com" label)
                       :emails (site-emails label)
                       :state  {:last-check-utc  nil
                                :content-snippet nil
                                :content-hash    nil
                                :last-change-utc nil
                                :fail-counter    0
                                :last-error-utc  nil
                                :last-error-msg  nil}}]
     (merge-with merge default-site overrides))))

(defn set-sites [app-state sites]
  (assoc-in app-state [:sites] sites))


;; Mock download behaviors.

(defn succeeding-download [site-data]
  (constantly [site-data nil]))

(defn failing-download [ex-data]
  (constantly [nil ex-data]))


;; Helpers to manipulate the integrant system.

(defmethod ig/init-key ::fake-email-sender [_ {:keys [verbose]}]
  (let [history (atom [])
        impl (reify web-watchdog.email/EmailSender
               (send-email [_ to subject body-html]
                 (when verbose
                   (utils/log (format "Fake-sending email titled '%s' to [%s]" subject to)))
                 (swap! history conj {:to to, :subject subject, :body-html body-html})))]
    {:impl impl, :history history}))

(defn with-fake-email-sender
  ([system-cfg]
   (with-fake-email-sender system-cfg {:verbose false}))
  ([system-cfg opts]
   (-> system-cfg
       (dissoc [::system/email-sender :web-watchdog.email/gmail-sender])
       (assoc  [::system/email-sender ::fake-email-sender] opts))))

(defn without-site-checker [system-cfg]
  (dissoc system-cfg :web-watchdog.server/site-checker))
