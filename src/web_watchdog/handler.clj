(ns web-watchdog.handler
  (:require [compojure.core :refer :all]
            [compojure.route :as route]
            [ring.util.response :as response]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults]]))

(defroutes app-routes
  (GET "/" [] (response/redirect "/index.html"))
  ; server all static HTML, CSS, JavaScript files
  (route/files "resources")
  (route/not-found "Not Found"))

(def app
  (wrap-defaults app-routes site-defaults))
