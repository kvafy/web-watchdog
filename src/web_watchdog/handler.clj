(ns web-watchdog.handler
  (:require [compojure.core :refer [defroutes GET]]
            [compojure.route :as route]
            [ring.util.response :as response]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
            [ring.middleware.json :refer [wrap-json-response]]
            [web-watchdog.state :as state]
            [web-watchdog.utils :as utils]))

(defn process-state [state]
  (-> state
      ; Java regular expression instance cannot be converted to JSON
      (utils/update-map-keys :re-pattern #(.pattern %))))

(defroutes app-routes
  ; redirect to static html file
  (GET "/" []
    (response/redirect "/index.html"))
  ; AJAX polling of current application state
  (GET "/rest/current-state" []
    (response/response (process-state @state/app-state)))
  ; serve all static resources (HTML, CSS, JavaScript)
  (route/files "resources")
  (route/not-found "Not Found"))

(def app
  (-> app-routes
      wrap-json-response
      (wrap-defaults site-defaults)))
