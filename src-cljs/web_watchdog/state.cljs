(ns web-watchdog.state
  (:require [reagent.core :as reagent]
            [web-watchdog.common-utils :as c-utils]))

;; Application state.
;; The web UI rendered by Reagent is basically a mathematical
;; function projecting the application state to HTML. Each
;; time (part of the) application state changes, relevant parts
;; of the HTML representing the changed state are re-generated
;; and browser re-renders the affected portions of the page.
(defonce app-state (reagent/atom {}))

(defn post-process-site [site]
  (-> site
      c-utils/unkeywordize-maps-in-site-request-field))

(defn post-process-sites [state]
  (update state :sites #(mapv post-process-site %)))

(defn poll-current-state! []
  (letfn [(success-handler [json]
            (as-> json $
              (js->clj $ :keywordize-keys true)
              (post-process-sites $)
              (reset! app-state $)))]
    (.getJSON js/jQuery "/rest/current-state" success-handler)))
