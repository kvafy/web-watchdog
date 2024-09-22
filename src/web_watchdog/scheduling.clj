(ns web-watchdog.scheduling
  (:require [integrant.core :as ig]
            [web-watchdog.core :as core]
            [web-watchdog.utils :as utils])
  (:import [java.time Instant ZonedDateTime ZoneId]
           [java.util.concurrent Executors RejectedExecutionException TimeUnit]
           [com.cronutils.parser CronParser]
           [com.cronutils.model CronType]
           [com.cronutils.model.definition CronDefinitionBuilder]
           [com.cronutils.model.time ExecutionTime]))

;; Evaluating CRON expressions.

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


;; Scheduler for running tasks at specific times (possibly concurrently).

(defprotocol Scheduler
  (run-at [this f epoch-ms])
  (run-now [this f])
  (run-after-delay [this f delay-ms])
  (shutdown [this])
  (is-shutdown? [this]))

(defn create-java-scheduler [thread-count]
  (let [executor (Executors/newScheduledThreadPool thread-count)]
    (reify Scheduler
      (run-at [this f epoch-ms]
        (let [wait-ms (- epoch-ms (System/currentTimeMillis))]
          (run-after-delay this f wait-ms)))

      (run-now [this f]
        (run-after-delay this f 0))

      (run-after-delay [_ f delay-ms]
        (try
          (.schedule executor f (max 0 delay-ms) TimeUnit/MILLISECONDS)
          ; Happens if the executor is already shutdown.
          (catch RejectedExecutionException _ nil))
        nil)

      (shutdown [_]
        (.shutdownNow executor)
        nil)

      (is-shutdown? [_]
        (.isShutdown executor)))))

;; Scheduler as a component.

(defmethod ig/init-key ::scheduler [_ {:keys [thread-count]}]
  (utils/log (str "Starting task scheduler with " thread-count " threads."))
  (create-java-scheduler thread-count))

(defmethod ig/halt-key! ::scheduler [_ impl]
  (utils/log "Stopping task scheduler.")
  (shutdown impl))


;; Orchestrating site checks, both scheduled and immediate.

(defn next-check-time [site global-config]
  (let [last-check-utc (get-in site [:state :last-check-utc])]
    (if (nil? last-check-utc)
      (utils/now-utc)
      (let [schedule (or (get site :schedule) (get global-config :default-schedule))
            tz (get global-config :timezone "UTC")
            next-check-utc (next-cron-time schedule last-check-utc tz)]
        next-check-utc))))

(defn due-for-check? [site global-config]
  (let [next-check-utc (next-check-time site global-config)
        now-utc (utils/now-utc)]
    (<= next-check-utc now-utc)))

(defn oneshot-site-check-fn
  "Produces a Runnable, suitable to be sent off to a scheduler."
  [site-id app-state download-fn]
  #(core/check-site-with-lock! site-id app-state download-fn))

(defn scheduled-site-check-fn
  "Produces a Runnable, suitable to be sent off to a scheduler."
  [site-id app-state download-fn scheduler]
  (fn []
    (let [global-config (:config @app-state)
          [_ site-old] (core/find-site-by-id @app-state site-id)]
      (when (due-for-check? site-old global-config)
        ;; Skipped, if the schedule was editted after this task was scheduled.
        (core/check-site-with-lock! site-id app-state download-fn))
      (let [[_ site-new] (core/find-site-by-id @app-state site-id)
            next-check-epoch (next-check-time site-new global-config)
            next-check-fn (scheduled-site-check-fn site-id app-state download-fn scheduler)]
        (utils/log (format "Scheduling next check of '%s' at %s." (:title site-old) (utils/millis-to-local-time next-check-epoch)))
        (run-at scheduler next-check-fn next-check-epoch)))))

(defprotocol SiteChecker
  (check-site-one-shot [this site-id])
  (check-site-periodically [this site-id]))

;; Site checker as a component.

(defmethod ig/init-key ::site-checker [_ {:keys [start-cron-schedules? app-state download-fn scheduler]}]
  (let [impl (reify SiteChecker
               (check-site-one-shot [_ site-id]
                 (run-now scheduler (oneshot-site-check-fn site-id app-state download-fn)))

               (check-site-periodically [_ site-id]
                 (run-now scheduler (scheduled-site-check-fn site-id app-state download-fn scheduler))))]
    (when start-cron-schedules?
      ;; Register all sites for scheduled execution.
      (utils/log "Registering CRON schedules for all sites.")
      (dorun (->> @app-state
                  :sites
                  (map :id)
                  (map #(check-site-periodically impl %)))))
    impl))
