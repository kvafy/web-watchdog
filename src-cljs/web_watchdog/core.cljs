(ns web-watchdog.core
  (:require [clojure.core.async :as async]
            [reagent.dom]
            [web-watchdog.components :as components]
            [web-watchdog.state :as state]
            [web-watchdog.utils :as utils]))

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

(defn run-state-refresh-loop! []
  ; Periodically polls for the current state.
  ; Reagent will observe the state changes and re-render the UI accordingly.
  (async/go-loop []
    (let [wait-ms (if (utils/any-non-idle-site? @state/app-state) 250 (* 10 1000))
          wait-ch (async/timeout wait-ms)
          ;; Wait for a timeout, or skip it if state is refreshed by other means.
          [_ ch] (async/alts! [state/on-state-poll-finished-ch wait-ch])]
      (when (= ch wait-ch)
        (state/poll-current-state!)))
    (recur))
  ;; Kick off the state polling.
  (state/poll-current-state!))

(defn on-document-ready []
  (init-reagent!)
  (init-bootstrap!)
  (run-state-refresh-loop!))

; JavaScript start-up actions.
(js/$ on-document-ready) ; Equivalent of jQuery2 `$(document).on('ready', <fn>)`
