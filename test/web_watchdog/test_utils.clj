(ns web-watchdog.test-utils
  (:require [integrant.core :as ig]
            [web-watchdog.email]
            [web-watchdog.state :as state]
            [web-watchdog.utils :as utils]))

;; Generic helpers.

(defmacro not-thrown? [& body]
  `(try
     ~@body
     true
     (catch Exception _# false)))


;; Constructing sites and app state.

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
                                :next-check-utc  nil
                                :content-snippet nil
                                :content-hash    nil
                                :last-change-utc nil
                                :fail-counter    0
                                :last-error-utc  nil
                                :last-error-msg  nil
                                :ongoing-check   :idle}}]
     (merge-with merge default-site overrides))))

(defn set-sites [app-state sites]
  (assoc-in app-state [:sites] sites))


;; Mock download behaviors.

(defn succeeding-download [site-data]
  (constantly [site-data nil]))

(defn failing-download [ex-data]
  (constantly [nil ex-data]))


;; ===============================================
;; Fake Integrant components for integration tests
;; ===============================================

(defmacro with-system
  [[bound-var binding-cfg-expr & {keys-to-start# :only-keys}] & body]
  `(let [keys-to-start# (if (some? ~keys-to-start#) ~keys-to-start# (keys ~binding-cfg-expr))
         ~bound-var (ig/init ~binding-cfg-expr keys-to-start#)]
     (try
       ~@body
       (finally (ig/halt! ~bound-var)))))

(defn assert-system-modified
  "Used to validate that a system modification was successful.
   Helps to catch typos in keywords when removing a component from the system."
  [new-system-cfg orig-system-cfg]
  (when (= new-system-cfg orig-system-cfg)
    (throw (IllegalStateException. "The system state was expected to change, but it didn't.")))
  new-system-cfg)

;; In-memory app state component.

(derive ::in-memory-app-state :web-watchdog.system/app-state)

(defmethod ig/init-key ::in-memory-app-state [_ {:keys [state validate?]}]
  (when validate?
    (state/validate state))
  (atom state))

(defn with-in-memory-app-state [system-cfg state]
  (-> system-cfg
      (dissoc :web-watchdog.state/file-based-app-state)
      (assert-system-modified system-cfg)
      (assoc  ::in-memory-app-state {:state state, :validate? true})))

;; Mockable download-fn.

(derive ::fake-download-fn :web-watchdog.system/download-fn)

(defmethod ig/init-key ::fake-download-fn [_ _]
  (let [mock-result (atom {:success "downloaded content"})
        arg-history (atom [])
        f (fn [url]
            (swap! arg-history conj url)
            (let [{:keys [success failure]} @mock-result]
              (cond
                (some? success) [success nil]
                (some? failure) [nil failure]
                :else (throw (IllegalStateException. (str "Invalid config of the ::fake-download-fn: " @mock-result))))))]
    {:impl f, :arg-history arg-history, :mock-result mock-result}))

(defmethod ig/resolve-key ::fake-download-fn [_ {:keys [impl]}]
  impl)

(defn with-fake-downloader [system-cfg]
  (-> system-cfg
      (dissoc :web-watchdog.networking/web-downloader)
      (assert-system-modified system-cfg)
      (assoc  ::fake-download-fn {})))

;; Fake email sender.

(derive ::fake-email-sender :web-watchdog.system/email-sender)

(defmethod ig/init-key ::fake-email-sender [_ {:keys [verbose]}]
  (let [history (atom [])
        impl (reify web-watchdog.email/EmailSender
               (send-email [_ to subject body-html]
                 (when verbose
                   (utils/log (format "Fake-sending email titled '%s' to [%s]" subject to)))
                 (swap! history conj {:to to, :subject subject, :body-html body-html})))]
    {:impl impl, :history history}))

(defmethod ig/resolve-key ::fake-email-sender [_ {:keys [impl]}]
  impl)

(defn with-fake-email-sender
  ([system-cfg]
   (with-fake-email-sender system-cfg {:verbose false}))
  ([system-cfg opts]
   (-> system-cfg
       (dissoc :web-watchdog.email/gmail-sender)
       (assert-system-modified system-cfg)
       (assoc  ::fake-email-sender opts))))
