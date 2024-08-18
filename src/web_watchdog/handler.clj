(ns web-watchdog.handler
  (:require [compojure.core :refer [defroutes GET]]
            [compojure.route :as route]
            [ring.util.response :as response]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
            [ring.middleware.json :refer [wrap-json-response]]
            [web-watchdog.state :as state]))


(defroutes app-routes
  ; redirect to static html file
  (GET "/" []
    (response/redirect "/index.html"))
  ; AJAX polling of current application state
  (GET "/rest/current-state" []
    (response/response @state/app-state))
  ; serve all static resources (HTML, CSS, JavaScript)
  (route/files "resources")
  (route/not-found "Not Found"))

(def app
  (-> app-routes
      wrap-json-response
      (wrap-defaults site-defaults)))
