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
              (utils/log "Checking all sites")
              (swap! state/app-state update-in [:sites] core/check-sites)
              (Thread/sleep (get-in @state/app-state [:config :check-interval-ms]))
              (recur)))]
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
