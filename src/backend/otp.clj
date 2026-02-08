(ns backend.otp
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [backend.config :as config]
            [clojure.tools.logging :as log]))

(def ^:private health-check-timeout-ms 5000)
(def ^:private default-date "2026-01-30")
(def ^:private default-time "10:00:00")
(def ^:private default-timeout-ms 60000)
(def ^:private tz-offset "-05:00")
(def ^:private default-cutoff-minutes [15 30 45 60 90 120 150 180])
(def ^:private weekend-dates {:saturday "2026-01-24" :sunday "2026-01-25"})

(defn- otp-url
  "Build OTP API URL. Optionally accepts a base-url override."
  ([path]
   (str (:base-url (config/otp-config)) path))
  ([base-url path]
   (str base-url path)))

;; List of direct OTP instance URLs for batch processing
(def otp-instances
  ["http://localhost:8081"
   "http://localhost:8082"
   "http://localhost:8083"
   "http://localhost:8084"
   "http://localhost:8085"
   "http://localhost:8086"
   "http://localhost:8087"
   "http://localhost:8088"
   "http://localhost:8089"
   "http://localhost:8090"
   "http://localhost:8091"
   "http://localhost:8092"
   "http://localhost:8093"
   "http://localhost:8094"
   "http://localhost:8095"])

(defn health-check
  "Check if OTP is running. Checks first direct instance (8081) for batch mode."
  []
  (try
    (let [;; Use first direct instance for health check (more reliable than load balancer)
          url (str (first otp-instances) "/otp/")
          response (http/get url
                             {:socket-timeout health-check-timeout-ms
                              :connection-timeout health-check-timeout-ms})]
      (= 200 (:status response)))
    (catch Exception e
      (log/warn "OTP health check failed:" (.getMessage e))
      false)))

(defn- get-single-isochrone
  "Compute a single isochrone cutoff from OTP 2.x TravelTime API.
   mode-params is a map of OTP query params (e.g. {:modes \"TRANSIT,WALK\"} or
   {:modes \"TRANSIT\" :accessModes \"BIKE\" :egressModes \"BIKE\"})."
  [lat lng cutoff-min & {:keys [mode-params date time timeout-ms base-url]
                          :or {mode-params {:modes "TRANSIT,WALK"}
                               date default-date
                               time default-time
                               timeout-ms default-timeout-ms}}]
  (let [url (if base-url
              (otp-url base-url "/otp/traveltime/isochrone")
              (otp-url "/otp/traveltime/isochrone"))
        datetime (str date "T" time tz-offset)
        cutoff (str "PT" cutoff-min "M")
        params (merge {:batch true
                       :location (str lat "," lng)
                       :time datetime
                       :cutoff cutoff}
                      mode-params)]
    (try
      (let [response (http/get url
                               {:query-params params
                                :socket-timeout timeout-ms
                                :connection-timeout timeout-ms
                                :accept :json})]
        (when (= 200 (:status response))
          (let [data (json/parse-string (:body response) true)
                feature (first (get data :features))]
            (when feature
              {:cutoff cutoff-min
               :geometry (:geometry feature)}))))
      (catch Exception e
        (log/warn "OTP isochrone request failed for cutoff" cutoff-min ":" (.getMessage e))
        nil))))

(defn- get-multi-cutoff-isochrone
  "Compute all isochrone cutoffs in a single OTP request.
   OTP TravelTime API accepts multiple cutoff query params and computes the SPT
   once, extracting all isochrone boundaries from it.
   mode-params is a map of OTP query params (e.g. {:modes \"TRANSIT,WALK\"})."
  [lat lng cutoff-minutes & {:keys [mode-params date time timeout-ms base-url]
                              :or {mode-params {:modes "TRANSIT,WALK"}
                                   date default-date
                                   time default-time
                                   timeout-ms default-timeout-ms}}]
  (let [url (if base-url
              (otp-url base-url "/otp/traveltime/isochrone")
              (otp-url "/otp/traveltime/isochrone"))
        datetime (str date "T" time tz-offset)
        cutoffs (mapv #(str "PT" % "M") cutoff-minutes)
        params (merge {:batch true
                       :location (str lat "," lng)
                       :time datetime
                       :cutoff cutoffs}
                      mode-params)]
    (try
      (let [response (http/get url
                               {:query-params params
                                :socket-timeout timeout-ms
                                :connection-timeout timeout-ms
                                :accept :json})
            data (when (= 200 (:status response))
                   (json/parse-string (:body response) true))
            features (:features data)]
        (when (seq features)
          (mapv (fn [feature]
                  (let [time-seconds (some-> (get-in feature [:properties :time])
                                            (Integer/parseInt))
                        cutoff-min (when time-seconds (quot time-seconds 60))]
                    {:cutoff cutoff-min
                     :geometry (:geometry feature)}))
                features)))
      (catch Exception e
        (log/warn "Multi-cutoff isochrone request failed:" (.getMessage e))
        nil))))

(defn- multi-cutoff-results-valid?
  "Check that multi-cutoff results contain distinct geometries.
   Returns false if all geometries are identical (known OTP bug)."
  [results]
  (and (seq results)
       (> (count (distinct (map :geometry results))) 1)))

(defn- get-isochrone
  "Compute isochrones from OTP 2.x TravelTime API.
   Tries a single multi-cutoff request first (OTP computes SPT once for all cutoffs).
   Falls back to parallel per-cutoff requests if multi-cutoff returns identical shapes.
   mode-params is a map of OTP query params (e.g. {:modes \"TRANSIT,WALK\"})."
  [lat lng & {:keys [mode-params date time cutoff-minutes base-url]
              :or {mode-params {:modes "TRANSIT,WALK"}
                   date default-date  ; A Monday for weekday schedules (within GTFS validity)
                   time default-time}}]
  (let [cutoff-minutes (or cutoff-minutes
                           (:cutoff-minutes (config/isochrone-config))
                           default-cutoff-minutes)
        timeout-ms (:timeout-ms (config/otp-config))
        ;; Try single multi-cutoff request first (1 SPT computation instead of 8)
        multi-results (get-multi-cutoff-isochrone lat lng cutoff-minutes
                                                   :mode-params mode-params
                                                   :date date
                                                   :time time
                                                   :timeout-ms timeout-ms
                                                   :base-url base-url)
        results (if (multi-cutoff-results-valid? multi-results)
                  multi-results
                  ;; Fallback: parallel per-cutoff requests (original behavior)
                  (do
                    (when multi-results
                      (log/warn "Multi-cutoff returned identical geometries, falling back to per-cutoff requests"))
                    (let [single-results (pmap #(get-single-isochrone lat lng %
                                                                      :mode-params mode-params
                                                                      :date date
                                                                      :time time
                                                                      :timeout-ms timeout-ms
                                                                      :base-url base-url)
                                               cutoff-minutes)]
                      (filter some? single-results))))
        valid-results (filter some? results)]
    (if (seq valid-results)
      {:success true
       :data {:type "FeatureCollection"
              :features (mapv (fn [{:keys [cutoff geometry]}]
                                {:type "Feature"
                                 :properties {:time (str (* cutoff 60))}
                                 :geometry geometry})
                              valid-results)}}
      {:success false
       :error "No valid isochrone results from OTP"})))

(defn- parse-isochrone-response
  "Parse OTP 2.x TravelTime isochrone response into separate time-band geometries.
   OTP 2.x returns features with 'time' property in seconds (e.g., '1800' for 30 min)."
  [response]
  (when (:success response)
    (let [features (get-in response [:data :features])]
      (reduce
       (fn [acc feature]
         ;; OTP 2.x TravelTime returns 'time' property in seconds
         (let [time-str (get-in feature [:properties :time])
               ;; Parse "1800" -> 30 minutes, "2700" -> 45 minutes, etc.
               time-seconds (when time-str (Integer/parseInt time-str))
               time-minutes (when time-seconds (quot time-seconds 60))
               key (when time-minutes (keyword (str time-minutes "m")))]
           (if key
             (assoc acc key (:geometry feature))
             acc)))
       {}
       features))))

(defn compute-isochrones
  "Compute all isochrone bands for a location.
   Optionally accepts :base-url to target a specific OTP instance."
  [lat lng & {:keys [mode departure-time day-type base-url]
              :or {mode "transit"
                   departure-time "10:00:00"
                   day-type "weekday"}}]
  (let [otp-mode-params (case mode
                          "transit"      {:modes "TRANSIT,WALK"}
                          "transit+bike" {:modes "TRANSIT"
                                          :accessModes "BIKE"
                                          :egressModes "BIKE"}
                          "bike"         {:modes "BIKE"}
                          "walk"         {:modes "WALK"}
                          {:modes "TRANSIT,WALK"})
        ;; Pick appropriate date based on day type (2026 dates within GTFS validity)
        date (case day-type
               "weekday" default-date                    ; Monday
               "saturday" (:saturday weekend-dates)      ; Saturday
               "sunday" (:sunday weekend-dates)          ; Sunday
               default-date)
        response (get-isochrone lat lng
                                :mode-params otp-mode-params
                                :date date
                                :time departure-time
                                :base-url base-url)]
    (if (:success response)
      {:success true
       :isochrones (parse-isochrone-response response)}
      response)))
