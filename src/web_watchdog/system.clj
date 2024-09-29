(ns web-watchdog.system
  (:require [integrant.core :as ig]))

(def system-cfg
  {;; State of the watched websites (check results, schedule etc.).
   ;; Value: Atom<Map>
   :web-watchdog.state/app-state
   {:file-path "state.edn", :validate? true}

   ;; Web server for the UI.
   ;; Value: org.eclipse.jetty.server.Server
   :web-watchdog.web/server
   {:port 8080, :handler (ig/ref :web-watchdog.web/handler)}

   ;; Web server Ring handler.
   ;; Value: Ring handler.
   :web-watchdog.web/handler
   {:app-state (ig/ref :web-watchdog.state/app-state)}

   ;; Backgronud process for checking websites according to their configured
   ;; schedules, and immediately on demand.
   ;; Value: {:interrupt-channel <core.async channel>}
   :web-watchdog.scheduling/site-checker
   {;; Deps
    :app-state (ig/ref :web-watchdog.state/app-state),
    :download-fn (ig/ref :web-watchdog.networking/web-downloader),
    :blocking-threadpool (ig/ref :web-watchdog.scheduling/blocking-threadpool)
    ;; Forced dependency to ensure all observers are initialized.
    :app-state-observers (ig/refset ::app-state-observer)}

   ;; Threadpool for running blocking tasks (e.g. network I/O) that
   ;; cannot be run on the core.async threads.
   ;; Value: java.util.concurrent.ExecutorService
   :web-watchdog.scheduling/blocking-threadpool
   {:thread-count 4}

   ;; Notifies of state changes using the :email-sender.
   ;; Value: {:watched-atom <app-state-atom>}
   [::app-state-observer :web-watchdog.email/notifier]
   {:app-state (ig/ref :web-watchdog.state/app-state),
    :email-sender (ig/ref ::email-sender)}

   ;; Stores app state to a file when the state changes.
   ;; Value: {:watched-atom <app-state-atom>}
   ; TODO: Would be nice to share the file path between `::state-persister` and `::app-state`.
   [::app-state-observer :web-watchdog.persistence/state-persister]
   {:file-path "state.edn", :app-state (ig/ref :web-watchdog.state/app-state)}

   ;; Gmail email sender.
   ;; Value: {:impl <of web-watchdog.email/EmailSender>}.
   [::email-sender :web-watchdog.email/gmail-sender]
   {}

   ;; Downloader of websites (as a component for mocking).
   ;; Value: (fn [url] -> [[ok-body err]])
   :web-watchdog.networking/web-downloader
   {:cache-ttl-ms (* 10 1000)}
 })
