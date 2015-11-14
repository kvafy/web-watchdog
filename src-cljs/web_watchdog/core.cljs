(ns web-watchdog.core
  (:require [reagent.core :as reagent]
            [goog.string.format]))

;; Application state. The web UI rendered by Reagent is just
;; a mathematical function projecting the state to HTML. Each
;; time application state changes, relevant parts of the HTML
;; representing the changed state are re-generated.
;; This is just a sample state. Later it will be fetched from
;; the server and updated periodically.
(defonce app-state (reagent/atom 
                    {:sites [{:title      "European LISP Symposium"
                              :url        "http://www.european-lisp-symposium.org"
                              :re-pattern "(?s).*"
                              :emails     ["happy@lisper.com"]
                              :state      {:content-hash nil
                                           :fail-counter 0}}]
                     :config {:check-interval-ms (* 1000 60 60)}}))

(defn duration-pprint [millis]
  (let [conversions ["second(s)" 1000
                     "minute(s)" 60
                     "hour(s)"   60
                     "day(s)"    24]]
    (loop [unit        "millisecond(s)"
           value       millis
           conversions conversions]
      (if (empty? conversions)
        (goog.string/format "%.1f %s" value unit)
        (let [[next-unit divider & next-conversions] conversions
              next-value (/ value divider)]
          (if (< (.abs js/Math next-value) 1)
            (goog.string/format "%.1f %s" value unit)
            (recur next-unit next-value next-conversions)))))))

(defn keyword-pprint [kw]
  (-> kw
      str
      (clojure.string/replace ":" "")
      (clojure.string/replace "-" " ")
      (clojure.string/capitalize)))

(defn transform-kv-pair [[k v]]
  (let [transforms {:check-interval-ms
                    [(constantly "Check interval") duration-pprint]}
        default-transform [keyword-pprint identity]]
    (let [[k-trans v-trans] (get transforms k default-transform)]
      [(k-trans k) (v-trans v)])))


;; Reagent components

(defn kv-pair [kv]
  (let [[k v] (transform-kv-pair kv)]
    [:div
     [:dt k]
     [:dd v]]))

(defn configuration []
  [:div {:class "col-xs-12"}
   [:h1 "Global configuration"]
   [:dl
    (for [kv (-> @app-state :config)]
      [kv-pair kv])]])

(defn site-tooltip-html [s]
  (str "<dl>"
       "  <dt>Notifications sent to</dt>"
       "  <dd>" (clojure.string/join "," (:emails s)) "</dd>"
       "  <dt>Regexp (Java)</dt>"
       "  <dd>" (:re-pattern s) "</dd>"
       "</dl>"))

(defn site [s]
  (let [fails        (-> s :state :fail-counter)
        content-hash (-> s :state :content-hash)
        status (cond (< 0 fails)  {:color-css "text-danger"
                                   :icon-css  "glyphicon glyphicon-exclamation-sign"
                                   :text      "Last check failed"}
                     content-hash {:color-css "text-success"
                                   :icon-css  "glyphicon glyphicon-ok-sign"
                                   :text      "Last check succeeded"}
                     :else        {:color-css "text-muted"
                                   :icon-css  "glyphicon glyphicon-question-sign"
                                   :text      "No check performed yet"})]
    [:tr {:class (:tr-class status)
          ; Bootstrap Popover properties
          :data-toggle    "popover"
          :data-placement "bottom"
          :data-html      "true"
          :title          (:title s)
          :data-content   (site-tooltip-html s)}
     [:td
      [:a {:href (:url s)} (:title s)]]
     [:td "???"]
     [:td {:class (:color-css status)}
      [:span {:class (:icon-css status)
              :title (:text status)}]]]))

(defn sites []
  [:div {:class "col-xs-12"}
   [:h1 "Checked sites"]
   [:table {:class "table table-hover table-striped"}
    [:thead
     [:tr
      [:td "Name"]
      [:td "Last Change"]
      [:td "Status"]]]
    [:tbody
     (for [s (-> @app-state :sites)]
       [site s])]]])

(defn content []
  [:div {:class "row"}
   [sites]
   [configuration]])


(defn on-document-ready []
  (let [container (.getElementById js/document "content")]
    ; Reagent will render the 'content' component into #content HTML element
    (reagent/render (content) container))
  ; Initialize Bootstrap Popover plug-in
  (-> (js/$ "[data-toggle='popover']") .popover))

; JavaScript start-up actions
(-> (js/$ js/document) (.ready on-document-ready))

