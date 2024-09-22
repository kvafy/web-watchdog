(ns web-watchdog.server
  (:require [integrant.core :as ig]
            [web-watchdog.core :as core]
            [web-watchdog.system :as system]
            [web-watchdog.utils :as utils]
            )
  (:gen-class))

;; The site checker component.

(defmethod ig/init-key ::site-checker [_ {:keys [app-state downloader]}]
  (let [latch (atom true)
        runnable (fn []
                   (loop []
                     (if-not @latch
                       (utils/log "The 'watcher-thread' stopped.")
                       (do
                         (try
                           (let [{:keys [sites config]} @app-state
                                 next-check-utc (->> sites (map #(core/next-check-time % config)) (apply min))
                                 wait-ms (- next-check-utc (utils/now-utc))]
                             (when (pos? wait-ms)
                               (utils/log (format "watcher-thread sleeping until %s (for %dms) to perform the next check"
                                                  (utils/millis-to-zoned-time next-check-utc)
                                                  wait-ms))
                               (Thread/sleep wait-ms))
                             (swap! app-state update-in [:sites] #(core/check-due-sites % downloader config)))
                           (catch InterruptedException e
                             (when @latch (utils/log (str "The watcher-thread was unexpectedly interrupted: " e)))))
                         (recur)))))
        thread (doto (java.lang.Thread. runnable)
                 (.setName "watcher-thread")
                 (.setDaemon true))]
    (.start thread)
    {:thread thread, :thread-latch latch}))

(defmethod ig/halt-key! ::site-checker [_ {:keys [thread thread-latch]}]
  (reset! thread-latch false)
  (.interrupt thread))


(defn -main [& args]
  (ig/load-namespaces system/system-cfg)
  (let [system (ig/init system/system-cfg)]
    (utils/log "The application fully started!")
    (.addShutdownHook
     (Runtime/getRuntime)
     (Thread. #(ig/halt! system)))))
