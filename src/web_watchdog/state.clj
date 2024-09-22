(ns web-watchdog.state
  (:require [integrant.core :as ig]
            [web-watchdog.persistence :as persistence]
            [web-watchdog.utils :as utils]))

(def default-state
  {:sites [#_
           ;; This is how a site is represented.
           {:id         "03ce4b54-408d-4e28-898a-aa030188d6e0"
            :title      "TfL: District line"
            :url        "https://tfl.gov.uk/tube-dlr-overground/status/"
            :content-extractors [[:css "#service-status-page-board"]
                                 [:xpath "//li[@data-line-id='lul-district']"]
                                 [:css "span.disruption-summary"]
                                 [:html->text]]
            :emails     ["my@email.com"]
            :schedule   "0 0 9 * * *"
            :state      {:last-check-utc nil
                         :content-hash   nil
                         :content-snippet nil
                         :last-change-utc nil
                         :fail-counter   0
                         :last-error-utc nil
                         :last-error-msg nil}}]
   ;; Global configuration.
   :config {:default-schedule "0 0 9 * * *"
            :timezone "Europe/London"}})


;; The global application state component.

(defmethod ig/init-key ::app-state [_ {:keys [file-path]}]
  (atom
   (if-let [loaded-state (persistence/load-state file-path)]
     (do (utils/log (format "Successfully loaded state from file '%s'." file-path))
         loaded-state)
     (do (utils/log (format "Failed to load state from file '%s', using empty state." file-path))
         default-state))))
