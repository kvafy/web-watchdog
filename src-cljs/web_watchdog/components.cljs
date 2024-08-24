(ns web-watchdog.components
  (:require [reagent.dom.server]
            [web-watchdog.state :as state]
            [web-watchdog.utils :as utils]))

(defn kv-pair [kv]
  (let [[k v] (utils/transform-kv-pair kv)]
    [:div.v-margin
     [:span k] ": "
     [:span.monospace v]]))

(defn configuration []
  [:div {:class "col-xs-12"}
   [:h1 "Global configuration"]
   [:dl
    (for [kv (-> @state/app-state :config)]
      ^{:key (first kv)} [kv-pair kv])]])

(defn site-tooltip [s]
  [:div
   [:div.tooltip-section
    [:div.tooltip-key "Notify emails"]
    [:div.tooltip-value (clojure.string/join ", " (:emails s))]]
   (let [schedule (get s :schedule "<default>")]
     [:div.tooltip-section
      [:div.tooltip-key "Schedule"]
      [:div.tooltip-value [:span.monospace schedule]]])
   (when-let [extractors (:content-extractors s)]
     [:div.tooltip-section
      [:div.tooltip-key "Content extractor chain"]
      [:div.tooltip-value
       (for [[extractor arg] extractors]
         ^{:key [extractor arg]}
         [:div.v-margin extractor
          (when arg [:span ": " [:span.monospace arg]])])]])
   (when-let [content-snippet (get-in s [:state :content-snippet])]
     [:div.tooltip-section
      [:div.tooltip-key "Last successful content"]
      [:div.tooltip-value [:span.monospace content-snippet]]])])

(defn site [s]
  (let [fails        (-> s :state :fail-counter)
        content-hash (-> s :state :content-hash)
        status (cond (< 0 fails)  {:color-css "text-danger" ; Bootstrap class
                                   :icon-css  "glyphicon glyphicon-exclamation-sign" ; Bootstrap class
                                   :text      (str "Last check failed with " (-> s :state :last-error-msg))}
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
          :data-content   (reagent.dom.server/render-to-string (site-tooltip s))
          ; Works together with `.popover {max-width: ...}` CSS.
          :container      "#sites-table"}
     [:td
      [:a {:href (:url s), :target "_blank"} (:title s)]]
     [:td (utils/utc->date-str (-> s :state :last-check-utc))]
     [:td (utils/utc->date-str (-> s :state :last-change-utc))]
     [:td (utils/utc->date-str (-> s :state :last-error-utc))]
     [:td {:class (:color-css status)}
      [:span {:class (:icon-css status)
              :title (:text status)}]]]))

(defn sites []
  [:div {:class "col-xs-12"}
   [:h1 "Checked sites"]
   [:table#sites-table {:class "table table-hover table-striped"}
    [:thead
     [:tr
      [:td "Name"]
      [:td "Last Check"]
      [:td "Last Change"]
      [:td "Last Error"]
      [:td "Status"]]]
    [:tbody
     (for [s (-> @state/app-state :sites)]
       ^{:key (:url s)} [site s])]]])

(defn content []
  [:div {:class "row"}
   [sites]
   [configuration]])
