(ns web-watchdog.web
  (:require [compojure.core :refer [routes GET DELETE POST PATCH PUT]]
            [compojure.route :as route]
            [integrant.core :as ig]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.util.response :as response]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
            [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
            [web-watchdog.core :as core]
            [web-watchdog.logging :as logging :refer [logd logi logw]]
            [web-watchdog.scheduling :as scheduling]
            [web-watchdog.state :as state]
            [web-watchdog.common-utils :as c-utils])
  (:import (java.util.concurrent Executors)
           (org.eclipse.jetty.util.thread QueuedThreadPool)))

(defn keywordize-site-content-extractors [site]
  (if (contains? site :content-extractors)
    (update site
            :content-extractors
            (fn [cexs]
              (mapv (fn [[ce-type & ce-args]]
                      (apply vector (keyword ce-type) ce-args))
                    cexs)))
    site))

(defn preprocess-site [site]
  (-> site
      keywordize-site-content-extractors
      c-utils/unkeywordize-maps-in-site-request-field))

(defn build-routes [app-state download-fn sse-handler]
  (routes
   ; Redirect to static html file.
   (GET "/" []
     (response/redirect "/index.html"))
   ; AJAX polling of the current application state
   (GET "/rest/current-state" []
     (response/response @app-state))
   ; Server-sent events notifying about app state changes
   (GET "/sse/state-changes" []
     sse-handler)
   ; REST actions on sites
   (POST "/sites" req
     (let [site-req (-> req :body preprocess-site)]
       (logd "Processing request to create site: %s" site-req)
       (try
         (swap! app-state (fn [cur-state]
                            (let [new-state (core/add-site cur-state site-req)]
                              (state/validate new-state)
                              new-state)))
         (response/status 200)
         (catch Exception e
           (logw e "Create site failed, request: %s" site-req)
           (response/bad-request (.getMessage e))))))
   (PUT "/sites/:site-id" req
     (let [site-req (-> req :body preprocess-site)]
       (logd "Processing request to update site: %s" site-req)
       (try
         (swap! app-state (fn [cur-state]
                            (let [new-state (core/update-site cur-state site-req)]
                              (state/validate new-state)
                              new-state)))
         (response/status 200)
         (catch Exception e
           (logw e "Update site failed, request: %s" site-req)
           (response/bad-request (.getMessage e))))))
   (DELETE "/sites/:site-id" [site-id]
     (logd "Processing request to delete site: %s" site-id)
     (try
       (swap! app-state (fn [cur-state]
                          (let [new-state (core/delete-site cur-state site-id)]
                            (state/validate new-state)
                            new-state)))
       (response/status 200)
       (catch Exception e
         (logw e "Delete site failed, request: %s" site-id)
         (response/bad-request (.getMessage e)))))
   (POST "/sites/test" req
     (let [site-req (-> req :body preprocess-site)
           _ (logd "Processing request to test site: %s" site-req)
           [site-content error] (core/test-site site-req download-fn)]
       (if site-content
         (response/response site-content)
         (response/bad-request error))))
   (POST "/sites/:site-id/refresh" [site-id]
     (let [site-exists? (scheduling/make-site-due-now! app-state site-id)]
       (response/status (if site-exists? 200 404))))
   (PATCH "/sites/drag-and-drop-reorder" req
     (let [reorder-req (-> req :body)
           {:keys [src-id dst-id]} reorder-req]
       (logd "Processing request to reorder sites: %s" reorder-req)
       (try
         (swap! app-state (fn [cur-state]
                            (let [new-state (core/reorder-site cur-state src-id dst-id)]
                              (state/validate new-state)
                              new-state)))
         (response/status 200)
         (catch Exception e
           (logw e "Reorder site failed, request: %s" reorder-req)
           (response/bad-request (.getMessage e))))))
   ; Serve all static resources (HTML, CSS, JavaScript).
   (route/files "resources")
   (route/not-found "Not Found")))

(defn build-app [app-state download-fn sse-handler]
  (-> (build-routes app-state download-fn sse-handler)
      (wrap-json-body {:keywords? true})
      wrap-json-response
      (wrap-defaults (assoc-in site-defaults [:security :anti-forgery] false))))


;; The Ring handler component representing the web app.

(defmethod ig/init-key ::handler [_ {:keys [app-state download-fn sse-handler]}]
  (build-app app-state download-fn sse-handler))


;; The Jetty web server component.

(defmethod ig/init-key ::server [_ {:keys [port handler]}]
  (let [thread-pool (doto (QueuedThreadPool.)
                          (.setVirtualThreadsExecutor (Executors/newVirtualThreadPerTaskExecutor)))
        opts {:port port, :join? false, :thread-pool thread-pool}]
    (logi "Starting Jetty web server on port %d" port)
    (run-jetty handler opts)))

(defmethod ig/halt-key! ::server [_ server]
  (logi "Stopping Jetty web server")
  (.stop server))
