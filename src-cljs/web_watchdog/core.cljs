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

(defn init-state-refresh! []
  ; Periodically poll for current state.
  ; Reagent will re-render UI based on the current state.
  (state/poll-current-state!)
  (js/setInterval state/poll-current-state! (* 10 1000)))

(defn on-document-ready []
  (init-reagent!)
  (init-bootstrap!)
  (init-state-refresh!))

; JavaScript start-up actions.
(js/$ on-document-ready) ; Equivalent of jQuery2 `$(document).on('ready', <fn>)`
