(ns web-watchdog.components
  (:require [clojure.string]
            [reagent.core]
            [reagent.dom.server]
            [web-watchdog.state :as state]
            [web-watchdog.utils :as utils]))

(defn kv-pair [kv]
  (let [[k v] (utils/transform-kv-pair kv)]
    [:div.v-margin
     [:span k] ": "
     [:span.monospace v]]))

(defn request-site-create! [site]
  (js/jQuery.ajax
   (clj->js {:url "sites"
             :method "PUT"
             :data (-> site clj->js js/JSON.stringify)
             :contentType "application/json; charset=UTF-8"
             :success (fn [res] (prn "Create success!" res))})))

(defn request-site-refresh! [site-id]
  (let [url (str "sites/" site-id "/refresh")
        data ""
        on-success #(state/poll-current-state!)]
    (js/jQuery.post url data on-success)))

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
    [:div.tooltip-value (clojure.string/join ", " (get-in s [:email-notification :to]))]]
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
  (let [fails         (-> s :state :fail-counter)
        content-hash  (-> s :state :content-hash)
        ongoing-check (-> s :state :ongoing-check)
        status (cond (= ongoing-check "pending") {:color-css ""
                                                  :icon-css  "bi bi-hourglass spinning"
                                                  :text      "Check pending"}
                     (= ongoing-check "in-progress") {:color-css ""
                                                      :icon-css  "bi bi-arrow-repeat spinning"
                                                      :text      "Check in progress"}
                     (< 0 fails)  {:color-css "text-danger" ; Bootstrap class
                                   :icon-css  "bi bi-exclamation-circle" ; Bootstrap class
                                   :text      (str "Last check failed with " (-> s :state :last-error-msg))}
                     content-hash {:color-css "text-success"
                                   :icon-css  "bi bi-check-circle"
                                   :text      "Last check succeeded"}
                     :else        {:color-css "text-muted"
                                   :icon-css  "bi bi-question-circle"
                                   :text      "No check performed yet"})
        popover-props {; Bootstrap Popover properties
                       :data-bs-toggle   "popover"
                       :data-bs-title    (:title s)
                       :data-bs-content  (reagent.dom.server/render-to-string (site-tooltip s))
                       :data-bs-html     "true"
                       :data-placement   "bottom"
                       ; Works together with `.popover {max-width: ...}` CSS.
                       :data-container   "#sites-table"}]
    [:tr {:class (:tr-class status)}
     [:td popover-props
      [:a {:class "link-light" :href (:url s), :target "_blank"} (:title s)]]
     [:td popover-props (utils/utc->date-str (-> s :state :last-check-time))]
     [:td popover-props (utils/utc->date-str (-> s :state :last-change-time))]
     [:td popover-props (utils/utc->date-str (-> s :state :last-error-time))]
     [:td (merge {:class (:color-css status)} popover-props)
      [:span {:class (:icon-css status)
              :title (:text status)}]]
     [:td
      [:div.dropleft
       [:button {:class "btn btn-sm dropdown-toggle", :type "button", :data-bs-toggle "dropdown"}]
       [:ul.dropdown-menu
        [:li [:a {:class (str "dropdown-item" (if (not= ongoing-check "idle") " disabled" "")),
                  :on-click #(request-site-refresh! (:id s))}
              "Refresh now"]]]]]]))

(defn add-site-button [dialog-model-atom]
  [:span {:class "bi bi-plus-circle highlight-on-hover"
          :title "Add a site"
          ;; Bootstrap properties that toggle the dialog visibility.
          :data-bs-toggle "modal"
          :data-bs-target "#add-or-edit-site-dialog"
          :on-click #(reset! dialog-model-atom {})}])

(defn add-or-edit-site-dialog--content-extractor-row [model-atom idx]
  (let [[type value] (get-in @model-atom [:content-extractors idx])]
    [:div {:class "row content-extractor"}
     [:div.col-md-3
      [:select {:class "form-select"
                :value type
                :on-change (fn [e]
                             (let [type (utils/target-value e)
                                   cex (if (= type "html->text") [type] [type ""])]
                               (swap! model-atom assoc-in [:content-extractors idx] cex)))}
       [:option {:value "css"} "CSS"]
       [:option {:value "xpath"} "XPath"]
       [:option {:value "regexp"} "Regexp"]
       [:option {:value "sort-elements-by-text"} "sort-elements-by-text"]
       [:option {:value "html->text"} "html->text"]]]
     [:div.col-md-8
      [:input {:type "text" :class "form-control"
               :style {:visibility (when (contains? #{"html->text" "sort-elements-by-text"} type) "hidden")}
               :placeholder (case type
                              "css"     "#content item:first-of-type"
                              "xpath"   "//span[@id='outer']"
                              "regexp"  "Price:\\s*(\\d+).*"
                              "Select extractor type")
               :value value
               :on-change (fn [e] (swap! model-atom assoc-in [:content-extractors idx 1] (utils/target-value e)))}]]
     [:div.col-md-1
      [:span {:class "bi bi-x-circle col-form-label highlight-on-hover"
              :title "Remove"
              :on-click #(swap!
                          model-atom
                          (fn [model]
                            (let [new-cexs (-> model :content-extractors (utils/dissoc-idx idx))]
                              (if (empty? new-cexs)
                                (dissoc model :content-extractors)
                                (assoc  model :content-extractors new-cexs)))))}]]]))

(defn add-or-edit-site-dialog
  "`model-atom` holds the currently edited site state.
   If the model has an `:id` key, it's an 'Edit' dialog, otherwise a 'Create' dialog."
  [model-atom]
  [:div {:id "add-or-edit-site-dialog" :class "modal fade" :tab-index "-1"}
   [:div.modal-dialog.modal-dialog-centered
    [:div.modal-content
     [:div.modal-header
      [:h1.modal-title (if (some? (:id @model-atom)) "Edit site" "Add a new site")]
      [:button {:type "button" :class "btn-close" :data-bs-dismiss "modal"}]]
     [:div.modal-body
      [:form.row.g-3
       [:div.col-12
        [:label {:for "aoesd-title" :class "col-form-label"} "Title:"]
        [:input {:id  "aoesd-title" :type "text" :class "form-control"
                 :value (:title @model-atom)
                 :on-change (fn [e] (swap! model-atom assoc :title (utils/target-value e)))}]]
       [:div.col-12
        [:label {:for "aoesd-url" :class "col-form-label"} "URL:"]
        [:input {:id  "aoesd-url" :type "text" :class "form-control"
                 :value (:url @model-atom)
                 :on-change (fn [e] (swap! model-atom assoc :url (utils/target-value e)))}]]
       [:div.col-12.content-extractors
        [:label {:class "col-form-label"}
         [:span "Content extractor chain:"]
         [:span " "]
         [:span {:class "bi bi-plus-circle highlight-on-hover" :title "Add content extractor"
                 :on-click (fn [_]
                             (swap! model-atom update :content-extractors
                                    (fn [cexs] (conj (or cexs []) ["css" ""]))))}]]
        (for [idx (-> @model-atom :content-extractors count range)]
          ^{:key idx} [add-or-edit-site-dialog--content-extractor-row model-atom idx])]
       [:div.col-12
        [:label {:for "aoesd-emails-to" :class "col-form-label"} "Email addresses (comma-separated):"]
        [:input {:id  "aoesd-emails-to" :type "email" :class "form-control" :placeholder "first@example.com, second@example.com"
                 :value (->> @model-atom :email-notification :to (clojure.string/join ","))
                 :on-change (fn [e] (let [parsed (utils/split-with-trailing (utils/target-value e) ",")]
                                      (swap! model-atom assoc-in [:email-notification :to] parsed)))}]]
       [:div.col-12
        [:label {:for "aoesd-emails-format" :class "col-form-label"} "Notification format:"]
        [:select {:id "aoesd-emails-format" :class "form-select"
                  :value (get-in @model-atom [:email-notification :format])
                  :on-change (fn [e] (swap! model-atom assoc-in [:email-notification :format] (utils/target-value e)))}
         [:option {:value "old-new"} "old-new"]
         [:option {:value "inline-diff"} "inline-diff"]]]
       [:div.col-12
        [:label {:for "aoesd-schedule" :class "col-form-label"} "Schedule (leave empty to use default):"]
        [:input {:id  "aoesd-schedule" :type "text" :class "form-control" :placeholder "CRON expression (e.g. \"0 0 9 * * *\")"
                 :value (:schedule @model-atom)
                 :on-change (fn [e] (let [new-val (utils/target-value e)]
                                      (if (empty? new-val)
                                        (swap! model-atom dissoc :schedule)
                                        (swap! model-atom assoc  :schedule new-val))))}]]]]
     [:div.modal-footer
      [:button {:type "button" :class "btn btn-secondary" :data-bs-dismiss "modal"} "Close"]
      [:button {:type "button" :class "btn btn-primary"
                :on-click #(request-site-create! @model-atom)}
       "Save"]]]]])

(defn sites [add-or-edit-site-dialog-model-atom]
  [:div {:class "col-xs-12"}
   [:h1 "Checked sites"
    [:span " "]
    [add-site-button add-or-edit-site-dialog-model-atom]]
   [:table#sites-table {:class "table table-hover table-striped"}
    [:thead
     [:tr
      [:th "Name"]
      [:th "Last Check"]
      [:th "Last Change"]
      [:th "Last Error"]
      [:th "Status"]
      [:th]]]
    [:tbody
     (for [s (-> @state/app-state :sites)]
       ^{:key (:id s)} [site s])]]])

(defn content []
  (let [add-or-edit-site-dialog-model (reagent.core/atom {})]
    (fn []
      [:div {:class "row"}
       [sites add-or-edit-site-dialog-model]
       [configuration]
       [add-or-edit-site-dialog add-or-edit-site-dialog-model]])))
