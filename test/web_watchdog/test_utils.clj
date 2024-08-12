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
  (let [default-state {:last-check-utc  nil
                       :content-hash    nil
                       :last-change-utc nil
                       :fail-counter    0
                       :last-error-utc  nil
                       :last-error-msg  nil}
        state (merge default-state (apply hash-map args))]
    {:title               (site-title label)
     :url                 (site-url label)
     :content-extractors  [[:regexp #"(?s).*"]]
     :emails              (site-emails label)
     :state               state}))

(defn set-sites [app-state sites]
  (assoc-in app-state [:sites] sites))
