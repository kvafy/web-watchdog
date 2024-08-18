(ns web-watchdog.server
  (:require [ring.adapter.jetty :refer [run-jetty]]
            [web-watchdog.handler :as handler]
            [web-watchdog.core :as core]
            [web-watchdog.state :as state]
            [web-watchdog.utils :as utils])
  (:gen-class))

(defn start-watcher-thread! []
  (let [runnable
          (fn []
            (loop []
              (let [{:keys [sites config]} @state/app-state
                    next-check-utc (->> sites (map #(core/next-check-time % config)) (apply min))
                    wait-ms (- next-check-utc (utils/now-utc))]
                (when (pos? wait-ms)
                  (utils/log (format "Sleeping until %s (for %dms) to perform the next check" (utils/millis-to-zoned-time next-check-utc) wait-ms))
                  (Thread/sleep wait-ms))
                (swap! state/app-state update-in [:sites] #(core/check-due-sites % config))
                (recur))))]
    (doto (java.lang.Thread. runnable)
          (.setName "watcher-thread")
          (.setDaemon true)
          (.start))))

(defn start-server! [opts]
  (run-jetty #'handler/app opts))

(defn -main [& args]
  (utils/log "Starting application on port 8080")
  (state/register-listeners!)
  (state/initialize!)
  (start-watcher-thread!)
  (start-server! {:port 8080 :join? false}))
