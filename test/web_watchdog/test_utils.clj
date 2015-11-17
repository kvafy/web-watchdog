(ns web-watchdog.test-utils)


(defn reader-for-string! [string]
  (java.io.BufferedReader. (java.io.StringReader. string)))

(defn site-title [label]
  (format "Site %s" label))

(defn site-url [label]
  (format "http://site-%s.com" label))

(defn site-emails [label]
  [(format "%s@watcher.com" label)])

(defn site [label & args]
  (let [default-params {:last-check-utc nil
                        :content-hash   nil
                        :fail-counter   0
                        :last-error-msg nil}
        params (merge default-params (apply hash-map args))]
    {:title      (site-title label)
     :url        (site-url label)
     :re-pattern #"(?s).*"
     :emails     (site-emails label)
     :state      {:last-check-utc (:last-check-utc params)
                  :content-hash   (:content-hash params)
                  :fail-counter   (:fail-counter params)
                  :last-error-msg (:last-error-msg params)}}))

(defn set-sites [app-state sites]
  (assoc-in app-state [:sites] sites))
