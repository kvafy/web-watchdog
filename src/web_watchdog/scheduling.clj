(ns web-watchdog.scheduling
  (:require [clojure.core.async :as async :refer [<!, >!]]
            [integrant.core :as ig]
            [web-watchdog.core :as core]
            [web-watchdog.utils :as utils])
  (:import [java.time Instant ZonedDateTime ZoneId]
           [java.util.concurrent Executors ExecutorService]
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


;; Blocking threadpool component.

(defmethod ig/init-key ::blocking-threadpool [_ {:keys [thread-count]}]
  (Executors/newFixedThreadPool thread-count))

(defmethod ig/halt-key! ::blocking-threadpool [_ executor]
  (.shutdownNow executor))


(defn run-async-on-threadpool
  "Runs `(apply f args)` on the given threadpool, returning a core.async channel
   that will close once the operation finishes or throws."
  [^ExecutorService threadpool f & args]
  (let [result-ch (async/chan)]
    (.submit threadpool
             ^Runnable (fn []
                         (try
                           (apply f args)
                           (finally
                             (async/close! result-ch)))))
    result-ch))


;; Orchestrating site checks, both scheduled and immediate.

(defn next-scheduled-check-time [site global-config]
  (let [last-check-time (get-in site [:state :last-check-time])]
    (if (nil? last-check-time)
      (utils/now-ms)
      (let [schedule (or (get site :schedule) (get global-config :default-schedule))
            tz (get global-config :timezone "UTC")
            next-check-time (next-cron-time schedule last-check-time tz)]
        next-check-time))))

(defn site-idle?-pred [site]
  (let [state (get-in site [:state :ongoing-check])]
    (= state "idle")))

(defn site-next-check-due?-pred [now]
  (fn [site]
    (let [next-check-time (get-in site [:state :next-check-time])]
      (or (nil? next-check-time)
          (<= next-check-time now)))))

(defn min-next-check-time [app-state]
  (->> (:sites app-state)
        (filter site-idle?-pred)
        (map #(get-in % [:state :next-check-time] 0))
        sort first))

(defn due-idle-sites [app-state]
  (let [now (utils/now-ms)
        due-and-idle (every-pred site-idle?-pred (site-next-check-due?-pred now))]
    (filter due-and-idle (:sites app-state))))

(defn timeout-at
  "Creates a core.async channel that will be delivered `msg` at the specified time and closed,
   or immediately if that time already passed."
  [time-ms msg]
  (let [ch (async/chan)]
    (async/go
      (let [now (utils/now-ms)
            wait-ms (- time-ms now)]
        (when (pos? wait-ms)
          (<! (async/timeout wait-ms)))
        (>! ch msg)
        (async/close! ch)))
    ch))

(defn make-site-due-now!
  "Makes the site immediately due for a check.
   Returns true if the `site-id` exists, false otherwise."
  [app-state-atom site-id]
  (if-let [[site-idx _] (core/find-site-by-id @app-state-atom site-id)]
    (do
      (utils/log (format "Making site '%s' due now" site-id))
      (swap! app-state-atom assoc-in [:sites site-idx :state :next-check-time] (utils/now-ms))
      true)
    false))


;; Site checker as a component.

(defmethod ig/init-key ::site-checker [_ {:keys [app-state blocking-threadpool download-fn]}]
  (let [interrupt-ch (async/chan 1)] ;; Expected events: :stop, :scheduling-changed
    ;; A single core.async process that is reponsible for orchestrating the site checks.
    ;; It takes the `:next-check-time` site property as the source of truth. This property is either
    ;; set and moved forward by the scheduled checks, or it can be artifically set to "now" by the
    ;; user to trigger an immediate check of a site. For the latter, the go process will be woken up
    ;; via the `interrupt-ch`.
    (async/go-loop []
      (let [next-check-time (min-next-check-time @app-state)
            _ (when (some? next-check-time) (utils/log (format "Next scheduled check is at %s (excluding sites with an ongoing check)."
                                                               (utils/millis-to-local-time next-check-time))))
            chans (cond-> []
                    true (conj interrupt-ch)
                    (some? next-check-time) (conj (timeout-at next-check-time :scheduled-run)))
            [event _] (async/alts! chans :priotity true)]
        (utils/log (format "Site checker woke up with '%s' event." event))
        (if (= event :stop)
          (utils/log "Site checker stopped.")
          (do
            (doseq [site-to-check (due-idle-sites @app-state)]
              (let [[site-idx _] (core/find-site-by-id @app-state (:id site-to-check))
                    set-site-state-prop! (fn [prop val]
                                           (swap! app-state assoc-in (concat [:sites site-idx :state] [prop]) val))]
                ;; Mark the site as pending a check. This also prevents it from being picked up
                ;; by `due-idle-sites` in the next iteration of this "go" process.
                (set-site-state-prop! :ongoing-check "pending")
                (async/go
                  ;; Handle the check itself on a blocking threadpool (slow I/O).
                  (<! (run-async-on-threadpool
                       blocking-threadpool
                       (fn []
                         (utils/log (format "Checking site '%s' ..." (:title site-to-check)))
                         (set-site-state-prop! :ongoing-check "in-progress")
                         (swap! app-state update-in [:sites site-idx] #(core/check-site % download-fn))
                         (let [updated-site (get-in @app-state [:sites site-idx])
                               next-check-time (next-scheduled-check-time updated-site (:config @app-state))]
                           (set-site-state-prop! :next-check-time next-check-time)))))
                  ;; Finish by marking the site check as completed.
                  (set-site-state-prop! :ongoing-check "idle"))))
            (recur)))))
    ;; Observer of the app state that will wake up the go process above if some site should now be checked sooner
    ;; than originally planned. This also covers the edge cases when the set of sites is originally empty and
    ;; becomes non-empty and vice versa.
    (add-watch app-state
               ::site-checker--next-check-time--monitor
               (fn [_ _ old-state new-state]
                 ;; Handle nil (~ no sites in the state).
                 (let [old-next-check (min-next-check-time old-state)
                       new-next-check (min-next-check-time new-state)]
                   (when (and (not= old-next-check new-next-check)
                              (some? new-next-check)
                              (or (nil? old-next-check)
                                  (< new-next-check old-next-check)))
                     (async/go (>! interrupt-ch :scheduling-changed))))))
    {:interrupt-channel interrupt-ch}))

(defmethod ig/halt-key! ::site-checker [_ {:keys [interrupt-channel]}]
  (async/>!! interrupt-channel :stop)
  (async/close! interrupt-channel))
