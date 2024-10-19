(ns web-watchdog.state
  (:require [clojure.core.async :as async]
            [reagent.core :as reagent]))

;; Application state.
;; The web UI rendered by Reagent is basically a mathematical
;; function projecting the application state to HTML. Each
;; time (part of the) application state changes, relevant parts
;; of the HTML representing the changed state are re-generated
;; and browser re-renders the affected portions of the page.
(defonce app-state (reagent/atom {}))

;; Channel to signal when the app state has been refreshed.
(defonce on-state-poll-finished-ch (async/chan))

(defn poll-current-state! []
  (letfn [(success-handler [json]
            (as-> json $
              (js->clj $ :keywordize-keys true)
              (reset! app-state $)
              (async/put! on-state-poll-finished-ch :state-refreshed)))]
    (.getJSON js/jQuery "rest/current-state" success-handler)))
