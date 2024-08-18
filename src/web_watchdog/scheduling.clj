(ns web-watchdog.scheduling
  (:import [java.time Instant ZoneId]
           [org.springframework.scheduling.support CronExpression]))

(defn next-cron-time
  "For a given CRON expression and a base time returns the next time when it should fire.
   
   `base-time-utc` a base time (epoch millis)
   `cron-str` is a CRON expression
   `tz-str` is a timezone, e.g. 'Europe/London'"
  [base-time-utc cron-str tz-str]
  (let [cron (CronExpression/parse cron-str)
        tz (ZoneId/of tz-str)
        base-time (-> base-time-utc (Instant/ofEpochMilli) (.atZone tz))]
    (.. cron (next base-time) (toInstant) (toEpochMilli))))
