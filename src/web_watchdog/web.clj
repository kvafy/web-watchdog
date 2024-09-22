(ns web-watchdog.web
  (:require [compojure.core :refer [routes GET]]
            [compojure.route :as route]
            [integrant.core :as ig]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.util.response :as response]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
            [ring.middleware.json :refer [wrap-json-response]]
            [web-watchdog.utils :as utils]))

(defn build-routes [app-state]
  (routes
   ; Redirect to static html file.
   (GET "/" []
     (response/redirect "/index.html"))
   ; AJAX polling of the current application state
   (GET "/rest/current-state" []
     (response/response @app-state))
   ; Serve all static resources (HTML, CSS, JavaScript).
   (route/files "resources")
   (route/not-found "Not Found")))

(defn build-app [app-state]
  (-> (build-routes app-state)
      wrap-json-response
      (wrap-defaults site-defaults)))


;; The Ring handler component representing the web app.

(defmethod ig/init-key ::handler [_ {:keys [app-state]}]
  (build-app app-state))


;; The Jetty web server component.

(defmethod ig/init-key ::server [_ {:keys [port handler]}]
  (let [opts {:port port, :join? false}]
    (utils/log (format "Starting Jetty web server on port %d" port))
    (run-jetty handler opts)))

(defmethod ig/halt-key! ::server [_ server]
  (utils/log "Stopping Jetty web server")
  (.stop server))
