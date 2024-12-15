(ns web-watchdog.web
  (:require [compojure.core :refer [routes GET POST PUT]]
            [compojure.route :as route]
            [integrant.core :as ig]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.util.response :as response]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
            [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
            [web-watchdog.scheduling :as scheduling]
            [web-watchdog.core :as core]
            [web-watchdog.state :as state]
            [web-watchdog.utils :as utils]))

(defn preprocess-site [site]
  (cond-> site
    ;; Content extractors types must be keywordized.
    (contains? site :content-extractors)
    (update :content-extractors
            (fn [cexs]
              (mapv (fn [[ce-type & ce-args]]
                      (apply vector (keyword ce-type) ce-args))
                    cexs)))))

(defn build-routes [app-state download-fn]
  (routes
   ; Redirect to static html file.
   (GET "/" []
     (response/redirect "/index.html"))
   ; AJAX polling of the current application state
   (GET "/rest/current-state" []
     (response/response @app-state))
   ; REST actions on sites
   (PUT "/sites" req
     (let [site-req (-> req :body preprocess-site)]
       (utils/log (str "Processing request to create site: " site-req))
       (try
         (swap! app-state (fn [cur-state]
                            (let [new-state (core/add-site cur-state site-req)]
                              (state/validate new-state)
                              new-state)))
         (response/status 200)
         (catch Exception e
           (utils/log (str "Create site failed: " (.getMessage e)))
           (response/bad-request (.getMessage e))))))
   (PUT "/sites/:site-id" req
     (let [site-req (-> req :body preprocess-site)]
       (utils/log (str "Processing request to update site: " site-req))
       (try
         (swap! app-state (fn [cur-state]
                            (let [new-state (core/update-site cur-state site-req)]
                              (state/validate new-state)
                              new-state)))
         (response/status 200)
         (catch Exception e
           (utils/log (str "Update site failed: " (.getMessage e)))
           (response/bad-request (.getMessage e))))))
   (POST "/sites/test" req
     (let [site-req (-> req :body preprocess-site)
           _ (utils/log (str "Processing request to test site: " site-req))
           [site-content error] (core/test-site site-req download-fn)]
       (if site-content
         (response/response (str "Extracted content: " site-content))
         (response/bad-request error))))
   (POST "/sites/:site-id/refresh" [site-id]
     (let [site-exists? (scheduling/make-site-due-now! app-state site-id)]
       (response/status (if site-exists? 200 404))))
   ; Serve all static resources (HTML, CSS, JavaScript).
   (route/files "resources")
   (route/not-found "Not Found")))

(defn build-app [app-state download-fn]
  (-> (build-routes app-state download-fn)
      (wrap-json-body {:keywords? true})
      wrap-json-response
      (wrap-defaults (assoc-in site-defaults [:security :anti-forgery] false))))


;; The Ring handler component representing the web app.

(defmethod ig/init-key ::handler [_ {:keys [app-state download-fn]}]
  (build-app app-state download-fn))


;; The Jetty web server component.

(defmethod ig/init-key ::server [_ {:keys [port handler]}]
  (let [opts {:port port, :join? false}]
    (utils/log (format "Starting Jetty web server on port %d" port))
    (run-jetty handler opts)))

(defmethod ig/halt-key! ::server [_ server]
  (utils/log "Stopping Jetty web server")
  (.stop server))
