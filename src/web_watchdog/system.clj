(ns web-watchdog.system
  (:require [integrant.core :as ig]))

(def system-cfg
  {;; Description: State of the watched websites (check results, schedule etc.).
   ;; Value: Atom<Map>
   ;; Parents: :web-watchdog.system/app-state
   :web-watchdog.state/file-based-app-state ;; ~ ::app-state
   {:file-path "state.edn",
    :fail-if-not-found? false,
    :validate? true,
    :sanitize? true,
    :save-on-change? true,
    :save-debounce-ms 1000}

   ;; Web server for the UI.
   ;; Value: org.eclipse.jetty.server.Server
   :web-watchdog.web/server
   {:port 8080, :handler (ig/ref :web-watchdog.web/handler)}

   ;; Description: Web server main Ring handler.
   ;; Value: Ring handler.
   :web-watchdog.web/handler
   {:app-state (ig/ref ::app-state),
    :download-fn (ig/ref ::download-fn),
    :sse-handler (ig/ref :web-watchdog.web-sse/state-change-broadcasting-handler)}

   ;; Description: Ring handler observing the app state and broadcasting
   ;;              a server-sent event (SSE) to all connected clients when
   ;;              the app state is updated.
   ;; Resolved value: <fn accepting a ring request, returning ring response>
   ;; Value: {:handler <fn accepting a ring request, returning ring response>,
   ;;         :watched-atom <app-state-atom>}
   ;; Parents: :web-watchdog.system/app-state-observer
   :web-watchdog.web-sse/state-change-broadcasting-handler ;; ~ ::app-state-observer
   {:app-state (ig/ref ::app-state),
    :debounce-ms 200}

   ;; Description: Backgronud process for checking websites according to their
   ;;              configured schedules, and immediately on demand.
   ;; Value: {:interrupt-channel <core.async channel>}
   :web-watchdog.scheduling/site-checker
   {;; Deps
    :app-state (ig/ref ::app-state),
    :download-fn (ig/ref ::download-fn),
    :blocking-threadpool (ig/ref :web-watchdog.scheduling/blocking-threadpool)
    ;; Forced dependencies to ensure the right starting order.
    :state-observers-initialized (ig/refset ::app-state-observer)}

   ;; Description: Threadpool for running blocking tasks (e.g. network I/O)
   ;;              that cannot be run on the core.async threads.
   ;; Value: java.util.concurrent.ExecutorService
   :web-watchdog.scheduling/blocking-threadpool
   {:thread-count 4}

   ;; Description: Notifies of state changes using the :email-sender.
   ;; Value: {:watched-atom <app-state-atom>}
   ;; Parents: :web-watchdog.system/app-state-observer
   :web-watchdog.email/notifier ;; ~ ::app-state-observer
   {:app-state (ig/ref ::app-state),
    :email-sender (ig/ref ::email-sender)}

   ;; Description: Gmail email sender.
   ;; Resolved value: web-watchdog.email/EmailSender
   ;; Value: {:impl <of web-watchdog.email/EmailSender>}
   ;; Parents: :web-watchdog.system/email-sender
   :web-watchdog.email/gmail-sender ;; ~ ::email-sender
   {}

   ;; Description: Downloader of websites (as a component for mocking).
   ;; Value: (fn [site] -> [[ok-body err]])
   ;; Parents: :web-watchdog.system/download-fn
   :web-watchdog.networking/web-downloader ;; ~::download-fn
   {:cache-ttl-ms (* 10 1000)}
   })
