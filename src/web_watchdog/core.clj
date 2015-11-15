(ns web-watchdog.core
  (:require [web-watchdog.persistence :as persistence]
            [web-watchdog.networking :as networking]
            [web-watchdog.utils :as utils])
  (:gen-class))


(def app-state (atom nil))

(def default-state
  {:sites [(comment
             ;; This is how a site is represented.
             {:title      "European LISP Symposium"
              :url        "http://www.european-lisp-symposium.org"
              :re-pattern #"(?s).*"
              :emails     ["happy@lisper.com"]
              :state      {:last-check-utc nil
                           :content-hash   nil
                           :fail-counter   0
                           :last-error-msg nil}})]
   ;; Global configuration.
   :config {:check-interval-ms (* 1000 60 60)}})


(defn sites-in-both-states [old-state new-state]
  (->> (concat (:sites old-state) (:sites new-state))
               (group-by #(:url %))
               vals
               (filter #(= 2 (count %)))))

(defn site-change-type [old-site new-site]
  (let [hash  (get-in old-site [:state :content-hash])
        hash' (get-in new-site [:state :content-hash])
        fails  (get-in old-site [:state :fail-counter])
        fails' (get-in new-site [:state :fail-counter])]
    (cond
      (and hash hash' (not= hash hash')) :content-changed
      (and (zero? fails) (pos? fails'))  :site-failing
      :else nil)))

(defn notify-if-sites-changed! [old-state new-state]
  (dorun
   (map (fn [[old-site new-site]]
          (when-let [change-type (site-change-type old-site new-site)]
            (utils/log (format "Change of type %s detected at [%s]" change-type (:title new-site)))
            (networking/notify-site-changed! new-site change-type)))
        (sites-in-both-states old-state new-state))))

(defn persist-new-state! [old-state new-state]
  (persistence/save-state! new-state))

(defn on-app-state-change [_ _ old-state new-state]
  (when (not= old-state new-state)
    (let [listeners [notify-if-sites-changed! persist-new-state!]]
      (doseq [f listeners]
        (f old-state new-state)))))

(defn check-site [site]
  (let [[data error]  (-> site :url networking/download)
        ; save the current time, right after the data is downloaded
        site (assoc-in site [:state :last-check-utc] (utils/now-utc))
        ; update the error message (may be nil)
        site (assoc-in site [:state :last-error-msg] error)]
    (if-not error
      (let [data-hash (->> data
                           (re-find (:re-pattern site))
                           utils/md5)]
        (-> site
            (assoc-in [:state :content-hash] data-hash)
            (assoc-in [:state :fail-counter] 0)))
      (-> site
          ; remember the last good :content-hash
          (update-in [:state :fail-counter] inc)))))

(defn check-sites [sites]
  (reduce (fn [res-sites site]
            (conj res-sites (check-site site)))
          []
          sites))

(defn initialize! []
  (reset! app-state (or (persistence/load-state) default-state))
  (add-watch app-state :on-app-state-change on-app-state-change))

(defn run-checking-loop! []
  (loop []
    (utils/log "Checking all sites")
    (swap! app-state update-in [:sites] check-sites)
    (Thread/sleep (get-in @app-state [:config :check-interval-ms]))
    (recur)))

(defn -main [& args]
  (utils/log "Application starting")
  (initialize!)
  (run-checking-loop!))
