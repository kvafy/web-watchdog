(ns web-watchdog.state
  (:require [reagent.core :as reagent]))

;; Application state.
;; The web UI rendered by Reagent is basically a mathematical
;; function projecting the application state to HTML. Each
;; time (part of the) application state changes, relevant parts
;; of the HTML representing the changed state are re-generated
;; and browser re-renders the affected portions of the page.
(defonce app-state (reagent/atom {}))

(defn poll-current-state! [post-poll-callback]
  (letfn [(success-handler [json]
            (as-> json $
              (js->clj $ :keywordize-keys true)
              (reset! app-state $)
              (post-poll-callback $)))]
    (.getJSON js/$ "rest/current-state" success-handler)))
