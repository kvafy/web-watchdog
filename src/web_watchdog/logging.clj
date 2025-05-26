(ns web-watchdog.logging
  (:require [taoensso.timbre :as timbre]
            [taoensso.timbre.appenders.core :as timbre-appenders]))

(defn setup-logging! []
  (timbre/merge-config!
   {:appenders {:spit (timbre-appenders/spit-appender {:fname "./web-watchdog.log"})}
    :min-level [[#{"web-watchdog.*"} :debug]
                [#{"*"} :info]]
    :timestamp-opts {:pattern "yyyy-MM-dd' 'HH:mm:ss.SSSX"
                     :timezone (java.util.TimeZone/getTimeZone "Europe/London")}}))

;; All the logging macros accept the following argument formats:
;;   [msg]
;;   [msg fmt-args]
;;   [ex msg]
;;   [ex msg fmt-args]

(defmacro loge [& args]
  `(timbre/errorf ~@args))

(defmacro logw [& args]
  `(timbre/warnf ~@args))

(defmacro logi [& args]
  `(timbre/infof ~@args))

(defmacro logd [& args]
  `(timbre/debugf ~@args))
