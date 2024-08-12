(ns web-watchdog.state
  (:require [web-watchdog.core :as core]
            [web-watchdog.persistence :as persistence]
            [web-watchdog.networking :as networking]
            [web-watchdog.utils :as utils]))

(defonce app-state (atom nil))

(def default-state
  {:sites [#_
           ;; This is how a site is represented.
           {:title      "TfL: District line"
            :url        "https://tfl.gov.uk/tube-dlr-overground/status/"
            :content-extractors [[:css "#service-status-page-board"]
                                 [:xpath "//li[@data-line-id='lul-district']"]
                                 [:css "span.disruption-summary"]
                                 [:html->text]]
            :emails     ["my@email.com"]
            :state      {:last-check-utc nil
                         :content-hash   nil
                         :last-change-utc nil
                         :fail-counter   0
                         :last-error-utc nil
                         :last-error-msg nil}}]
   ;; Global configuration.
   :config {:check-interval-ms (* 1000 60 60)}})


;; state change listeners

(defn notify-by-email! [old-state new-state]
  (dorun
    (map (fn [[old-site new-site]]
           (when-let [change-type (core/site-change-type old-site new-site)]
             (utils/log (format "Change of type %s detected at [%s]" change-type (:title new-site)))
             (networking/notify-site-changed! new-site change-type)))
         ; filter out change of type "site added/removed from watched sites list"
         (core/common-sites old-state new-state))))

(defn persist-new-state! [old-state new-state]
  (persistence/save-state! new-state))

(def state-listeners
  [notify-by-email! persist-new-state!])

(defn on-app-state-change [_ _ old-state new-state]
 (when (not= old-state new-state)
   (doseq [f state-listeners]
     (f old-state new-state))))


;; other logic

(defn initialize! []
  (reset! app-state (or (persistence/load-state) default-state)))

(defn register-listeners! []
  (add-watch app-state :on-app-state-change on-app-state-change))
