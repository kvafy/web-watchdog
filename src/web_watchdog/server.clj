(ns web-watchdog.server
  (:require [integrant.core :as ig]
            [web-watchdog.system :as system]
            [web-watchdog.utils :as utils])
  (:gen-class))

(defn -main [& args]
  (ig/load-namespaces system/system-cfg)
  (let [system (ig/init system/system-cfg)]
    (utils/log "The application fully started!")
    (.addShutdownHook
     (Runtime/getRuntime)
     (Thread. #(ig/halt! system)))))
