(ns web-watchdog.core
  (:require [reagent.dom]
            [web-watchdog.components :as components]
            [web-watchdog.state :as state]))

(defn init-reagent! []
  ; Reagent will render the 'content' component into
  ; #content HTML element
  (let [container (.getElementById js/document "content")]
    (reagent.dom/render (components/content) container)))

(defn init-bootstrap! []
  ; Initialize Bootstrap Popover plug-in (opt-in).
  ; Must exploit event delegation and pass in the selector,
  ; otherwise popups would work only for elements that are
  ; in the HTML at the time of calling this function, but
  ; not for elements added later.
  (-> (js/$ "body")
      (.popover #js {:selector "[data-bs-toggle='popover']"})))

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
