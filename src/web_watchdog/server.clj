(ns web-watchdog.server
  (:require [integrant.core :as ig]
            [web-watchdog.logging :as logging :refer [logi]]
            [web-watchdog.system :as system])
  (:gen-class))

(defn -main [& args]
  (logging/setup-logging!)
  (ig/load-namespaces system/system-cfg)
  (let [system (ig/init system/system-cfg)]
    (logi "The application fully started!")
    (.addShutdownHook
     (Runtime/getRuntime)
     (Thread. #(ig/halt! system)))))
