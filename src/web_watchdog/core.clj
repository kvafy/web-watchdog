(ns web-watchdog.core
  (:require [web-watchdog.persistence :as persistence]
            [web-watchdog.networking :as networking]
            [web-watchdog.utils :as utils])
  (:gen-class))


(def app-state (atom nil))

(def default-state
  {:sites []
   :config {:check-interval-ms (* 1000 60 60)}})


(defn sites-in-both-states [old-state new-state]
  (->> (concat (:sites old-state) (:sites new-state))
               (group-by #(:url %))
               vals
               (filter #(= 2 (count %)))))

(defn notify-if-sites-changed! [old-state new-state]
  (dorun
   (map (fn [[old-site new-site]]
          (let [old-hash (get-in old-site [:state :content-hash])
                new-hash (get-in new-site [:state :content-hash])]
            (when (and old-hash (not= old-hash new-hash))
              (utils/log (format "Change detected at [%s]" (:title new-site)))
              (networking/notify-site-changed! new-site))))
        (sites-in-both-states old-state new-state))))

(defn persist-new-state! [old-state new-state]
  (persistence/save-state! new-state))

(defn on-app-state-change [_ _ old-state new-state]
  (when (not= old-state new-state)
    (doseq [f [notify-if-sites-changed! persist-new-state!]]
      (f old-state new-state))))

(defn check-site [site]
  (utils/log (format "Checking site [%s]" (:url site)))
  (let [prev-hash (-> site :state :content-hash)
        cur-data  (-> site :url networking/download)]
    (if cur-data
      (assoc-in site
                [:state :content-hash]
                (->> cur-data (re-find (:re-pattern site)) (utils/md5)))
      ; in case of a download error, remember the last good :content-hash
      site)))

(defn check-sites [sites]
  (reduce (fn [res-sites site]
            (conj res-sites (check-site site)))
          []
          sites))

(defn initialize []
  (reset! app-state (or (persistence/load-state) default-state))
  (add-watch app-state :on-app-state-change on-app-state-change))

(defn run-checking-loop! []
  (loop []
    (swap! app-state update-in [:sites] check-sites)
    (Thread/sleep (get-in @app-state [:config :check-interval-ms]))
    (recur)))

(defn -main [& args]
  (initialize)
  (run-checking-loop!))

