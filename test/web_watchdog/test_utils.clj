(ns web-watchdog.test-utils)


(defn reader-for-string! [string]
  (java.io.BufferedReader. (java.io.StringReader. string)))

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
