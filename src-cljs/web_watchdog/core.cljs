(ns web-watchdog.core
  (:require [reagent.dom]
            [web-watchdog.components :as components]
            [web-watchdog.state :as state]))

(defn init-reagent! []
  ; Reagent will render the 'content' component into
  ; #content HTML element
  (let [container (.getElementById js/document "content")]
    (reagent.dom/render (components/content) container)))

(defn init-bootstrap!
  "Initialize the Bootstrap Popover plug-in (opt-in)."
  []
  (let [body (js/$ "body")
        popover-selector "[data-bs-toggle='popover']"]
    ; Must exploit event delegation and pass in the selector,
    ; otherwise popups would work only for elements that are
    ; in the HTML at the time of calling this function, but
    ; not for elements added later by Reagent.
    (.popover body #js {:selector popover-selector})

    ; Custom function to hide a popover when clicked outside of it.
    ; Unfortunately Bootstrap supports this natively only for <a>
    ; elements.
    (.on body "click" (fn []
                        (-> (js/$ "[data-bs-toggle='popover']")
                            (.popover "hide"))))))

(defn subscribe-to-state-updates!
  "The server provides an SSE channel via which it informs about every state change.
   Reagent will observe the state changes and re-render the UI accordingly."
  []
  (let [event-source (js/EventSource. "/sse/state-changes")
        on-event #(state/poll-current-state!)]
    (.addEventListener event-source "connected" on-event)
    (.addEventListener event-source "app-state-changed" on-event)))

(defn on-document-ready []
  (init-reagent!)
  (init-bootstrap!)
  (subscribe-to-state-updates!))

; JavaScript start-up actions.
(js/$ on-document-ready) ; Equivalent of jQuery2 `$(document).on('ready', <fn>)`
