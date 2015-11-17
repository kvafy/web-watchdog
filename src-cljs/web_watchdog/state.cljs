(ns web-watchdog.state
  (:require [reagent.core :as reagent]
            [ajax.core :as ajax]))

;; Application state.
;; The web UI rendered by Reagent is basically a mathematical
;; function projecting the application state to HTML. Each
;; time (part of the) application state changes, relevant parts
;; of the HTML representing the changed state are re-generated
;; and browser re-renders the affected portions of the page.
(defonce app-state (reagent/atom {}))

(defn poll-current-state! []
  (ajax/GET "rest/current-state"
            {:response-format :json ; must be there to enable keywords? option
             :keywords? true        ; convert keywords to strings
             :handler (fn [data]
                        (reset! app-state data))}))
