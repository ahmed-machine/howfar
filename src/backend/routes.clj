(ns backend.routes
  (:require [compojure.core :refer [defroutes GET context]]
            [compojure.route :as route]
            [ring.util.response :as response]
            [backend.db :as db]
            [backend.otp :as otp]
            [cheshire.core :as json]))

(defn json-response
  "Create JSON response"
  [data & {:keys [status] :or {status 200}}]
  (-> (response/response (json/generate-string data))
      (response/status status)
      (response/content-type "application/json")))

(defn parse-double [s default]
  (try (Double/parseDouble s) (catch Exception _ default)))

(def ^:private default-viewport {:min-lat 40.4 :max-lat 41.0 :min-lng -74.3 :max-lng -73.7})
(def ^:private default-intersection-limit 500)
(def ^:private default-transit-stop-limit 2000)
(def ^:private default-nearby-radius 500)
(def ^:private default-departure-time "10:00:00")
(def ^:private default-day-type "weekday")
(def ^:private default-mode "transit")

(defn parse-int [s default]
  (try (Integer/parseInt s) (catch Exception _ default)))

;; Health check
(defn health-handler [_]
  (let [otp-ok (otp/health-check)]
    (json-response {:status "ok"
                    :otp otp-ok
                    :timestamp (System/currentTimeMillis)})))

;; Intersection endpoints
(defn intersections-viewport-handler [request]
  (let [params (:query-params request)
        min-lat (parse-double (get params "minLat") (:min-lat default-viewport))
        max-lat (parse-double (get params "maxLat") (:max-lat default-viewport))
        min-lng (parse-double (get params "minLng") (:min-lng default-viewport))
        max-lng (parse-double (get params "maxLng") (:max-lng default-viewport))
        limit (parse-int (get params "limit") default-intersection-limit)
        mode (get params "mode" default-mode)
        sample-group (when-let [sg (get params "sampleGroup")]
                       (parse-int sg nil))
        intersections (db/get-intersections-in-viewport min-lat max-lat min-lng max-lng
                                                         :limit limit
                                                         :mode mode
                                                         :sample-group sample-group)]
    (json-response {:intersections intersections
                    :count (count intersections)})))

(defn- isochrone-keys
  "Extract isochrone band keys from a result map"
  [result]
  {:isochrone_15m (:isochrone_15m result)
   :isochrone_30m (:isochrone_30m result)
   :isochrone_45m (:isochrone_45m result)
   :isochrone_60m (:isochrone_60m result)
   :isochrone_90m (:isochrone_90m result)
   :isochrone_120m (:isochrone_120m result)
   :isochrone_150m (:isochrone_150m result)
   :isochrone_180m (:isochrone_180m result)})

(defn- intersection-keys
  "Extract intersection keys from a result map"
  [result]
  {:id (:id result)
   :osm_node_id (:osm_node_id result)
   :name (:name result)
   :lat (:lat result)
   :lng (:lng result)
   :borough (:borough result)
   :distance_m (:distance_m result)})

;; Combined click endpoint (nearest intersection + isochrone in one call)
(defn click-handler [request]
  (let [params (:query-params request)
        lat (parse-double (get params "lat") nil)
        lng (parse-double (get params "lng") nil)
        mode (get params "mode" default-mode)
        departure-time (get params "time" default-departure-time)
        day-type (get params "dayType" default-day-type)]
    (if (and lat lng)
      (if (= mode "compare")
        ;; Comparison mode: fetch both transit and bike isochrones
        (if-let [result (db/get-nearest-with-both-modes lat lng
                                                         :departure-time departure-time
                                                         :day-type day-type)]
          (json-response {:intersection (:intersection result)
                          :isochrone {:transit (isochrone-keys (:transit result))
                                      :bike (isochrone-keys (:bike result))}
                          :source "cache"})
          (json-response {:error "No intersection with both transit and bike data found nearby"} :status 404))
        ;; Normal single-mode path
        (if-let [result (db/get-nearest-with-isochrone lat lng
                                                        :mode mode
                                                        :departure-time departure-time
                                                        :day-type day-type)]
          (json-response {:intersection (intersection-keys result)
                          :isochrone (merge {:origin_id (:id result)
                                            :mode mode
                                            :departure_time departure-time
                                            :day_type day-type}
                                           (isochrone-keys result))
                          :source "cache"})
          (json-response {:error "No cached isochrone found nearby"} :status 404)))
      (json-response {:error "lat and lng parameters required"} :status 400))))

;; Isochrone endpoints (cache-only, no OTP computation)
(defn isochrone-handler [request]
  (let [id (parse-int (get-in request [:params :id]) nil)
        params (:query-params request)
        mode (get params "mode" default-mode)
        departure-time (get params "time" default-departure-time)
        day-type (get params "dayType" default-day-type)]
    (if id
      (if (= mode "compare")
        ;; Comparison mode: fetch both transit and bike for this intersection
        (let [transit (db/get-cached-isochrone id :mode "transit"
                                                  :departure-time departure-time
                                                  :day-type day-type)
              bike (db/get-cached-isochrone id :mode "bike"
                                               :departure-time departure-time
                                               :day-type day-type)]
          (if (and transit bike)
            (json-response {:source "cache"
                            :isochrone {:transit (isochrone-keys transit)
                                        :bike (isochrone-keys bike)}})
            (json-response {:error "Both transit and bike data required for comparison"} :status 404)))
        ;; Normal single-mode path
        (if-let [cached (db/get-cached-isochrone id
                                                  :mode mode
                                                  :departure-time departure-time
                                                  :day-type day-type)]
          (json-response {:source "cache" :isochrone cached})
          (json-response {:error "No cached isochrone for this intersection"} :status 404)))
      (json-response {:error "Invalid intersection ID"} :status 400))))

;; Transit stop endpoints
(defn transit-stops-viewport-handler [request]
  (let [params (:query-params request)
        min-lat (parse-double (get params "minLat") (:min-lat default-viewport))
        max-lat (parse-double (get params "maxLat") (:max-lat default-viewport))
        min-lng (parse-double (get params "minLng") (:min-lng default-viewport))
        max-lng (parse-double (get params "maxLng") (:max-lng default-viewport))
        limit (parse-int (get params "limit") default-transit-stop-limit)
        stops (db/get-transit-stops-in-viewport min-lat max-lat min-lng max-lng
                                                 :limit limit)]
    (json-response {:stops stops
                    :count (count stops)})))

(defn nearby-stops-handler [request]
  (let [params (:query-params request)
        lat (parse-double (get params "lat") nil)
        lng (parse-double (get params "lng") nil)
        radius (parse-int (get params "radius") default-nearby-radius)]
    (if (and lat lng)
      (let [stops (db/get-nearby-transit-stops lat lng :radius-m radius)]
        (json-response {:stops stops
                        :count (count stops)}))
      (json-response {:error "lat and lng parameters required"} :status 400))))

;; Modes endpoint
(defn modes-handler [_]
  (json-response {:modes [{:id "transit" :name "Transit" :available true}
                          {:id "transit+bike" :name "Transit + Bike" :available true}
                          {:id "bike" :name "Bike" :available true}
                          {:id "walk" :name "Walk" :available false}]}))

;; Stats endpoint
(defn stats-handler [_]
  (try
    (let [intersection-count (db/get-intersection-count)
          cache-stats (db/get-cache-stats)
          progress (db/get-computation-progress)]
      (json-response {:intersections (or intersection-count 0)
                      :cache_stats (or cache-stats [])
                      :computation_progress (or progress [])}))
    (catch Exception _
      (json-response {:intersections 0
                      :cache_stats []
                      :computation_progress []
                      :error "Failed to load stats"}))))

;; Routes
(defroutes api-routes
  (GET "/health" [] health-handler)

  (context "/api" []
    (GET "/click" [] click-handler)

    (GET "/intersections/viewport" [] intersections-viewport-handler)

    (GET "/isochrone/:id" [] isochrone-handler)

    (GET "/transit/stops/viewport" [] transit-stops-viewport-handler)
    (GET "/transit/stops/nearby" [] nearby-stops-handler)

    (GET "/modes" [] modes-handler)
    (GET "/stats" [] stats-handler))

  (route/not-found (json-response {:error "Not found"} :status 404)))
