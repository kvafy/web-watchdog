(ns web-watchdog.core
  (:require [clojure.java.io :as io]
            [postal.core])
  (:gen-class))

(def sites
  [{:title      "European LISP Symposium"
    :url        "http://www.european-lisp-symposium.org/content-programme-full.html"
    :matcher-re #"(?s).*"
    :emails     ["chaloupka.david@gmail.com"]}
   {:title      "Mestske byty Brno-stred"
    :url        "http://www.brno-stred.cz/uredni-deska/pronajmy-bytu"
    :matcher-re #"(?s)<h2>Pron.*?</ul>"
    :emails     ["chaloupka.david@gmail.com" "m.svihalkova@gmail.com"]}])

(def check-interval-ms (* 1000 60 60))

;; map site -> string last matched (nil on start up)
(def check-results (ref {}))

(defn log [msg]
  (printf "[%s] %s\n" (java.util.Date.) msg)
  (flush))

(defn download [url]
  (let [cm (java.net.CookieManager.)]
    (java.net.CookieHandler/setDefault cm))
  (with-open [in (io/reader url)]
    (slurp in)))

(defn- notify-update! [site]
  (let [subject (format "[Web-watchdog] %s" (:title site))
        body    (format "There seems to be something new on %s.\nCheck out %s." (:title site) (:url site))]
    (postal.core/send-message {:from "mailer@webwatchdog.com"
                               :to (:emails site)
                               :subject subject
                               :body body})))

(defn check-site [site prev-result]
  (log (format "Checking site [%s]" (:url site)))
  (let [cur-result (->> (:url site)
                        download
                        (re-find (:matcher-re site)))]
    (when (and prev-result (not= prev-result cur-result))
      (log (format "Change detected at [%s]" (:title site)))
      (notify-update! site))
    cur-result))

(defn start-checking-loop! []
  (loop []
    (dosync
     (let [new-results
           (reduce (fn [res site]
                     (let [prev-result (res site)
                           cur-result  (check-site site prev-result)]
                       (assoc res site cur-result)))
                   @check-results
                   sites)]
       (ref-set check-results new-results)
       (Thread/sleep check-interval-ms)
       (recur)))))

(defn -main [& args]
  (start-checking-loop!))

