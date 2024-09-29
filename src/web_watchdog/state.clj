(ns web-watchdog.state
  (:require [integrant.core :as ig]
            [schema.core :as s]
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
                         :next-check-utc nil
                         :content-hash   nil
                         :content-snippet nil
                         :last-change-utc nil
                         :fail-counter   0
                         :last-error-utc nil
                         :last-error-msg nil
                         :ongoing-check :idle}}]
   ;; Global configuration.
   :config {:default-schedule "0 0 9 * * *"
            :timezone "Europe/London"}})

(def state-schema
  {:sites [{:id s/Str
            :title s/Str
            :url s/Str
            (s/optional-key :content-extractors) [(s/conditional
                                                   #(contains? #{:css :xpath :regexp} (first %))
                                                   [(s/one s/Keyword "extractor type") (s/one s/Str "param")]
                                                   #(contains? #{:html->text} (first %))
                                                   [(s/one s/Keyword "extractor type")])]
            :emails (s/conditional (every-pred vector? not-empty) [s/Str])
            (s/optional-key :schedule) s/Str
            :state {:last-check-utc (s/maybe s/Int)
                    :next-check-utc (s/maybe s/Int)
                    :content-hash (s/maybe s/Str)
                    :content-snippet (s/maybe s/Str)
                    :last-change-utc (s/maybe s/Int)
                    :fail-counter s/Int
                    :last-error-utc (s/maybe s/Int)
                    :last-error-msg (s/maybe s/Str)
                    :ongoing-check (s/enum :idle :pending :in-progress)}}]
   :config
     {:default-schedule s/Str
      (s/optional-key :timezone) s/Str}})

(defn validate [state]
  ;; Basic schema validation.
  (s/validate state-schema state)
  ;; Domain-specific check: All site IDs must be unique
  (let [dupe-ids (->> (:sites state)
                      (group-by :id)
                      (filter (fn [[_ v]] (< 1 (count v))))
                      (keys))]
    (when (not-empty dupe-ids)
      (throw (IllegalArgumentException.
              (format "Sites must have unique IDs, but the following are duplicate: %s" dupe-ids))))))

(defn sanitize-initial-state
  "Because state gets persisted on every change, it may be in an intermediate unexpected state.
   For example, the program was killed while performing a site check."
  [state]
  (let [set-ongoing-check-to-idle (fn [site] (assoc-in site [:state :ongoing-check] :idle))]
    (-> state
        (update-in [:sites] #(mapv set-ongoing-check-to-idle %)))))


;; The global application state component.

(defmethod ig/init-key ::app-state [_ {:keys [file-path validate? sanitize?]}]
  (let [state
        (if-let [loaded-state (persistence/load-state file-path)]
          (do (utils/log (format "Successfully loaded state from file '%s'." file-path))
              loaded-state)
          (do (utils/log (format "Failed to load state from file '%s', using empty state." file-path))
              default-state))]
    (when validate?
      (utils/log "Validating the initial app state.")
      (validate state))
    (atom
     (if sanitize?
       (do (utils/log "Sanitizing the initial app state.")
           (let [sanitized-state (sanitize-initial-state state)]
             (when (not= state sanitized-state)
               (utils/log "State sanitization did fix some issues."))
             sanitized-state))
       state))))
