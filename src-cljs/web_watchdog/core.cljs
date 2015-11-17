(ns web-watchdog.core
  (:require [reagent.core :as reagent]
            [web-watchdog.components :as components]))

(defn init-reagent! []
  ; Reagent will render the 'content' component into
  ; #content HTML element
  (let [container (.getElementById js/document "content")]
    (reagent/render (components/content) container)))

(defn init-bootstrap! []
  ; Initialize Bootstrap Popover plug-in (opt-in).
  ; Must exploit event delegation and pass in the selector,
  ; otherwise popups would work only for elements that are
  ; in the HTML at the time of calling this function, but
  ; not for elements added later.
  (-> (js/$ "body")
      (.popover #js {:selector "[data-toggle='popover']"})))

(defn on-document-ready []
  (init-reagent!)
  (init-bootstrap!))

; JavaScript start-up actions
(-> (js/$ js/document)
    (.on "ready" on-document-ready))
