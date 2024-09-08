(ns web-watchdog.scheduling
  (:import [java.time Instant ZonedDateTime ZoneId]
           [com.cronutils.parser CronParser]
           [com.cronutils.model CronType]
           [com.cronutils.model.definition CronDefinitionBuilder]
           [com.cronutils.model.time ExecutionTime]))

(let [spring-impl (CronDefinitionBuilder/instanceDefinitionFor CronType/SPRING)
      parser (CronParser. spring-impl)]
  (defn next-cron-time
    "For a given CRON expression and a base time returns the next time when it should fire.
   
   `from-ms` a base time (epoch millis)
   `cron-str` is a CRON expression
   `tz-str` is a timezone, e.g. 'Europe/London'"
    [cron-str from-ms tz-str]
    (let [cron (. parser (parse cron-str))
          execution (ExecutionTime/forCron cron)
          from-time (ZonedDateTime/ofInstant (Instant/ofEpochMilli from-ms) (ZoneId/of tz-str))
          next-time (.. execution (nextExecution from-time) (get))]
      (.. next-time (toInstant) (toEpochMilli)))))
