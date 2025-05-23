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
              :url "https://tfl.gov.uk/tube-dlr-overground/status/"
              :request {:method "GET"
                        :query-params {}
                        :form-params {}
                        :headers {"Accept" "text/xml"}
                        :retries 3
                        :allow-insecure true}
              :content-extractors [[:css "#service-status-page-board"]
                                   [:xpath "//li[@data-line-id='lul-district']"]
                                   [:css "span.disruption-summary"]
                                   [:html->text]]
              :email-notification {:to ["my@email.com"]
                                   :format "old-new"}
              :schedule   "0 0 9 * * *"
              :state      {:last-check-time  nil
                           :next-check-time  0
                           :content-hash     nil
                           :content-snippet  nil
                           :last-change-time nil
                           :fail-counter     0
                           :last-error-time  nil
                           :last-error-msg   nil
                           :ongoing-check "idle"}}]
   ;; Global configuration.
   :config {:default-schedule "0 0 9 * * *"
            :timezone "Europe/London"}})

(s/defschema SiteSchema
  {:id s/Str
   :title s/Str
   :url s/Str
   (s/optional-key :request) {(s/optional-key :method)         (s/enum "GET" "POST")
                              (s/optional-key :query-params)   {s/Str s/Str}
                              (s/optional-key :form-params)    {s/Str s/Str}
                              (s/optional-key :headers)        {s/Str s/Str}
                              (s/optional-key :retries)        s/Int
                              (s/optional-key :allow-insecure) s/Bool}
   (s/optional-key :content-extractors) [(s/conditional
                                          #(contains? #{:css :xpath :regexp} (first %))
                                          [(s/one s/Keyword "extractor type") (s/one s/Str "param")]
                                          #(contains? #{:html->text :sort-elements-by-text} (first %))
                                          [(s/one s/Keyword "extractor type")])]
   :email-notification {:to (s/conditional (every-pred vector? not-empty) [s/Str])
                        :format (s/enum "old-new" "inline-diff")}
   (s/optional-key :schedule) s/Str
   :state {:last-check-time (s/maybe s/Int)
           :next-check-time (s/maybe s/Int)
           :content-hash (s/maybe s/Str)
           :content-snippet (s/maybe s/Str)
           :last-change-time (s/maybe s/Int)
           :fail-counter s/Int
           :last-error-time (s/maybe s/Int)
           :last-error-msg (s/maybe s/Str)
           :ongoing-check (s/enum "idle" "pending" "in-progress")}})

(s/defschema AppStateSchema
  {:sites [SiteSchema]
   :config
     {:default-schedule s/Str
      (s/optional-key :timezone) s/Str}})

(defn validate-site [site]
  (s/validate SiteSchema site))

(defn validate [state]
  ;; Basic schema validation.
  (s/validate AppStateSchema state)
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
  (let [set-ongoing-check-to-idle (fn [site] (assoc-in site [:state :ongoing-check] "idle"))]
    (-> state
        (update-in [:sites] #(mapv set-ongoing-check-to-idle %)))))


;; The global application state component, sourced from a file.

(derive ::file-based-app-state :web-watchdog.system/app-state)

(defmethod ig/init-key ::file-based-app-state
  [_ {:keys [file-path fail-if-not-found? validate? sanitize? save-on-change? save-debounce-ms]}]
  (let [state (let [loaded-state (persistence/load-state file-path)]
                (cond
                  (some? loaded-state)
                  (do (utils/log (format "Successfully loaded state from file '%s'." file-path))
                      loaded-state)
                  fail-if-not-found?
                  (throw (IllegalStateException. (format "State file '%s' not found." file-path)))
                  :else
                  (do (utils/log (format "Failed to load state from file '%s', using empty state." file-path))
                      default-state)))
        _  (when validate?
             (utils/log "Validating the initial app state.")
             (validate state))
        state-sanitized (if sanitize?
                          (do (utils/log "Sanitizing the initial app state.")
                              (let [sanitized-state (sanitize-initial-state state)]
                                (when (not= state sanitized-state)
                                  (utils/log "State sanitization did fix some issues."))
                                sanitized-state))
                          state)
        state-atom (atom state-sanitized)]
    (when save-on-change?
      (utils/log (format "State changes will be saved to the config file '%s'." file-path))
      (let [save-state!-debounced (utils/debounce #'persistence/save-state! save-debounce-ms)]
        (add-watch state-atom
                   ::file-based-app-state--state-persister
                   (fn [_ _ old-state new-state]
                     (when (not= old-state new-state)
                       (save-state!-debounced new-state file-path))))))
    state-atom))

(defmethod ig/halt-key! ::file-based-app-state [_ state-atom]
  (remove-watch state-atom ::file-based-app-state--state-persister))
