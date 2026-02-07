(ns backend.core
  (:require [ring.adapter.jetty :as jetty]
            [ring.middleware.json :refer [wrap-json-response wrap-json-body]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.cors :refer [wrap-cors]]
            [backend.routes :as routes]
            [backend.config :as config]
            [clojure.tools.logging :as log])
  (:gen-class))

(defn wrap-logging
  "Middleware to log requests"
  [handler]
  (fn [request]
    (let [start (System/currentTimeMillis)
          response (handler request)
          elapsed (- (System/currentTimeMillis) start)]
      (log/info (:request-method request) (:uri request)
                "-" (:status response) (str elapsed "ms"))
      response)))

(def app
  (-> routes/api-routes
      wrap-params
      wrap-json-body
      wrap-json-response
      wrap-logging
      (wrap-cors :access-control-allow-origin [#".*"]
                 :access-control-allow-methods [:get :options])))

(defonce server (atom nil))

(defn start-server
  "Start the web server"
  []
  (let [{:keys [port host]} (config/server-config)]
    (log/info "Starting howfar.nyc server on" (str host ":" port))
    (reset! server
            (jetty/run-jetty app
                             {:port port
                              :host host
                              :join? false}))))

(defn stop-server
  "Stop the web server"
  []
  (when @server
    (log/info "Stopping howfar.nyc server")
    (.stop @server)
    (reset! server nil)))

(defn -main
  "Main entry point"
  [& _args]
  (config/load-config)
  (start-server)
  (log/info "howfar.nyc server started. Press Ctrl+C to stop.")
  ;; Keep main thread alive
  @(promise))
