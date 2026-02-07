(ns backend.db
  (:require [next.jdbc :as jdbc]
            [next.jdbc.connection :as connection]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [backend.config :as config]
            [cheshire.core :as json]
            [clojure.tools.logging :as log])
  (:import (com.zaxxer.hikari HikariDataSource)))

(defonce datasource (atom nil))

(defn get-datasource
  "Get or create HikariCP database connection pool"
  []
  (or @datasource
      (reset! datasource
              (connection/->pool HikariDataSource (config/db-config)))))

(defn execute!
  "Execute a SQL statement"
  [sql-map]
  (jdbc/execute! (get-datasource)
                 (sql/format sql-map)
                 {:builder-fn rs/as-unqualified-lower-maps}))

(defn execute-one!
  "Execute a SQL statement and return single result"
  [sql-map]
  (jdbc/execute-one! (get-datasource)
                     (sql/format sql-map)
                     {:builder-fn rs/as-unqualified-lower-maps}))

;; Helper functions

(defn- geometry-to-geojson
  "Convert PostGIS geometry to GeoJSON"
  [row key]
  (if-let [geom (get row key)]
    (assoc row key (json/parse-string geom true))
    row))

;; Intersection queries

(defn get-intersections-in-viewport
  "Get intersections within map bounds, with computed status.
   Optional sample-group (0-3) filters to show only 1-in-4 intersections."
  [min-lat max-lat min-lng max-lng & {:keys [limit mode departure-time day-type sample-group]
                                       :or {limit 500
                                            mode "transit"
                                            departure-time "10:00:00"
                                            day-type "weekday"}}]
  (if sample-group
    (jdbc/execute!
     (get-datasource)
     ["SELECT i.id, i.osm_node_id, i.name, i.lat, i.lng, i.borough,
              CASE WHEN ib.origin_id IS NOT NULL THEN true ELSE false END AS is_computed
       FROM intersections i
       LEFT JOIN isochrone_bands ib ON i.id = ib.origin_id
         AND ib.mode = ? AND ib.departure_time = ?::time AND ib.day_type = ?
         AND ib.cutoff_minutes = 30
       WHERE i.lat >= ? AND i.lat <= ? AND i.lng >= ? AND i.lng <= ?
         AND i.sample_group = ?
       LIMIT ?"
      mode departure-time day-type min-lat max-lat min-lng max-lng sample-group limit]
     {:builder-fn rs/as-unqualified-lower-maps})
    (jdbc/execute!
     (get-datasource)
     ["SELECT i.id, i.osm_node_id, i.name, i.lat, i.lng, i.borough,
              CASE WHEN ib.origin_id IS NOT NULL THEN true ELSE false END AS is_computed
       FROM intersections i
       LEFT JOIN isochrone_bands ib ON i.id = ib.origin_id
         AND ib.mode = ? AND ib.departure_time = ?::time AND ib.day_type = ?
         AND ib.cutoff_minutes = 30
       WHERE i.lat >= ? AND i.lat <= ? AND i.lng >= ? AND i.lng <= ?
       LIMIT ?"
      mode departure-time day-type min-lat max-lat min-lng max-lng limit]
     {:builder-fn rs/as-unqualified-lower-maps})))

(defn get-nearest-with-isochrone
  "Find nearest intersection that has cached isochrone bands, in a single query.
   Uses CTE with EXISTS to find the nearest intersection with pre-computed data,
   then pivots isochrone_bands rows into columns.
   ST_Simplify reduces payload size (~0.0001 degrees ≈ 11m at NYC latitude)."
  [lat lng & {:keys [mode departure-time day-type]
              :or {mode "transit"
                   departure-time "10:00:00"
                   day-type "weekday"}}]
  (when-let [result (jdbc/execute-one!
                     (get-datasource)
                     ["WITH nearest_cached AS (
                         SELECT i.id, i.osm_node_id, i.name, i.lat, i.lng, i.borough,
                                ST_Distance(i.geom, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography) AS distance_m
                         FROM intersections i
                         WHERE EXISTS (
                           SELECT 1 FROM isochrone_bands ib
                           WHERE ib.origin_id = i.id
                             AND ib.mode = ? AND ib.departure_time = ?::time AND ib.day_type = ?
                         )
                         ORDER BY i.geom <-> ST_SetSRID(ST_MakePoint(?, ?), 4326)
                         LIMIT 1
                       ),
                       bands AS (
                         SELECT ib.origin_id, ib.cutoff_minutes,
                                ST_AsGeoJSON(ST_Simplify(ib.geometry, 0.0001)) AS geom_json
                         FROM isochrone_bands ib
                         JOIN nearest_cached n ON ib.origin_id = n.id
                         WHERE ib.mode = ? AND ib.departure_time = ?::time AND ib.day_type = ?
                       )
                       SELECT n.*,
                              MAX(CASE WHEN b.cutoff_minutes = 15 THEN b.geom_json END) AS isochrone_15m,
                              MAX(CASE WHEN b.cutoff_minutes = 30 THEN b.geom_json END) AS isochrone_30m,
                              MAX(CASE WHEN b.cutoff_minutes = 45 THEN b.geom_json END) AS isochrone_45m,
                              MAX(CASE WHEN b.cutoff_minutes = 60 THEN b.geom_json END) AS isochrone_60m,
                              MAX(CASE WHEN b.cutoff_minutes = 90 THEN b.geom_json END) AS isochrone_90m,
                              MAX(CASE WHEN b.cutoff_minutes = 120 THEN b.geom_json END) AS isochrone_120m,
                              MAX(CASE WHEN b.cutoff_minutes = 150 THEN b.geom_json END) AS isochrone_150m,
                              MAX(CASE WHEN b.cutoff_minutes = 180 THEN b.geom_json END) AS isochrone_180m
                       FROM nearest_cached n
                       LEFT JOIN bands b ON true
                       GROUP BY n.id, n.osm_node_id, n.name, n.lat, n.lng, n.borough, n.distance_m"
                      lng lat mode departure-time day-type lng lat mode departure-time day-type]
                     {:builder-fn rs/as-unqualified-lower-maps})]
    (-> result
        (geometry-to-geojson :isochrone_15m)
        (geometry-to-geojson :isochrone_30m)
        (geometry-to-geojson :isochrone_45m)
        (geometry-to-geojson :isochrone_60m)
        (geometry-to-geojson :isochrone_90m)
        (geometry-to-geojson :isochrone_120m)
        (geometry-to-geojson :isochrone_150m)
        (geometry-to-geojson :isochrone_180m))))

(defn get-intersection-by-id
  "Get intersection by ID"
  [id]
  (execute-one!
   {:select [:id :osm_node_id :name :lat :lng :borough]
    :from [:intersections]
    :where [:= :id id]}))

;; Isochrone cache queries

(defn get-cached-isochrone
  "Get pre-computed isochrone bands from cache (normalized table).
   Returns a map with all available bands keyed by :isochrone_XXm.
   ST_Simplify reduces payload size (~0.0001 degrees ≈ 11m at NYC latitude)."
  [origin-id & {:keys [mode departure-time day-type]
                :or {mode "transit"
                     departure-time "10:00:00"
                     day-type "weekday"}}]
  (let [rows (jdbc/execute!
              (get-datasource)
              ["SELECT cutoff_minutes,
                       ST_AsGeoJSON(ST_Simplify(geometry, 0.0001)) AS geom_json
                FROM isochrone_bands
                WHERE origin_id = ?
                  AND mode = ?
                  AND departure_time = ?::time
                  AND day_type = ?"
               origin-id mode departure-time day-type]
              {:builder-fn rs/as-unqualified-lower-maps})]
    (when (seq rows)
      (reduce
       (fn [acc {:keys [cutoff_minutes geom_json]}]
         (let [key (keyword (str "isochrone_" cutoff_minutes "m"))]
           (assoc acc key (when geom_json (json/parse-string geom_json true)))))
       {:origin_id origin-id
        :mode mode
        :departure_time departure-time
        :day_type day-type}
       rows))))

(defn get-nearest-with-both-modes
  "Find nearest intersection that has cached isochrone bands for BOTH transit and bike.
   Returns intersection info plus both isochrone datasets."
  [lat lng & {:keys [departure-time day-type]
              :or {departure-time "10:00:00"
                   day-type "weekday"}}]
  (when-let [result (jdbc/execute-one!
                     (get-datasource)
                     ["WITH nearest_cached AS (
                         SELECT i.id, i.osm_node_id, i.name, i.lat, i.lng, i.borough,
                                ST_Distance(i.geom, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography) AS distance_m
                         FROM intersections i
                         WHERE EXISTS (
                           SELECT 1 FROM isochrone_bands ib
                           WHERE ib.origin_id = i.id
                             AND ib.mode = 'transit' AND ib.departure_time = ?::time AND ib.day_type = ?
                         )
                         AND EXISTS (
                           SELECT 1 FROM isochrone_bands ib
                           WHERE ib.origin_id = i.id
                             AND ib.mode = 'bike' AND ib.departure_time = ?::time AND ib.day_type = ?
                         )
                         ORDER BY i.geom <-> ST_SetSRID(ST_MakePoint(?, ?), 4326)
                         LIMIT 1
                       )
                       SELECT n.id, n.osm_node_id, n.name, n.lat, n.lng, n.borough, n.distance_m
                       FROM nearest_cached n"
                      lng lat departure-time day-type departure-time day-type lng lat]
                     {:builder-fn rs/as-unqualified-lower-maps})]
    (let [intersection {:id (:id result)
                        :osm_node_id (:osm_node_id result)
                        :name (:name result)
                        :lat (:lat result)
                        :lng (:lng result)
                        :borough (:borough result)
                        :distance_m (:distance_m result)}
          transit-iso (get-cached-isochrone (:id result)
                                            :mode "transit"
                                            :departure-time departure-time
                                            :day-type day-type)
          bike-iso (get-cached-isochrone (:id result)
                                         :mode "bike"
                                         :departure-time departure-time
                                         :day-type day-type)]
      {:intersection intersection
       :transit transit-iso
       :bike bike-iso})))

(defn save-isochrone!
  "Save computed isochrone bands to cache (normalized table).
   Isochrones is a map like {:15m geom, :30m geom, ...}
   Clips geometry against land_boundary if available, preserving originals in geometry_unclipped."
  [origin-id mode departure-time day-type isochrones]
  (doseq [[band-key geometry] isochrones
          :when geometry]
    (let [band-name (name band-key)
          ;; Extract numeric part from "15m", "30m", etc.
          cutoff (Integer/parseInt (subs band-name 0 (dec (count band-name))))
          geojson-str (json/generate-string geometry)]
      (jdbc/execute!
       (get-datasource)
       ["INSERT INTO isochrone_bands
         (origin_id, mode, departure_time, day_type, cutoff_minutes, geometry, geometry_unclipped)
         VALUES (?, ?, ?::time, ?, ?,
                 COALESCE(
                     ST_CollectionExtract(ST_MakeValid(ST_Intersection(
                         ST_GeomFromGeoJSON(?),
                         (SELECT geometry FROM land_boundary LIMIT 1)
                     )), 3),
                     ST_GeomFromGeoJSON(?)
                 ),
                 ST_GeomFromGeoJSON(?))
         ON CONFLICT (origin_id, mode, departure_time, day_type, cutoff_minutes)
         DO UPDATE SET
           geometry = EXCLUDED.geometry,
           geometry_unclipped = EXCLUDED.geometry_unclipped,
           computed_at = CURRENT_TIMESTAMP"
        origin-id mode departure-time day-type cutoff
        geojson-str geojson-str geojson-str]))))

;; Transit stops queries

(defn get-transit-stops-in-viewport
  "Get transit stops within map bounds"
  [min-lat max-lat min-lng max-lng & {:keys [limit] :or {limit 2000}}]
  (execute!
   {:select [:id :gtfs_stop_id :stop_name :lat :lng :stop_type :agency]
    :from [:transit_stops]
    :where [:and
            [:>= :lat min-lat]
            [:<= :lat max-lat]
            [:>= :lng min-lng]
            [:<= :lng max-lng]
            [:in :stop_type ["subway" "bus" "rail" "ferry"]]]
    :limit limit}))

(defn get-nearby-transit-stops
  "Get transit stops near a point"
  [lat lng & {:keys [radius-m limit] :or {radius-m 500 limit 10}}]
  (jdbc/execute!
   (get-datasource)
   ["SELECT id, gtfs_stop_id, stop_name, lat, lng, stop_type, agency,
     ST_Distance(geom, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography) AS distance_m
     FROM transit_stops
     WHERE stop_type IN ('subway', 'bus', 'rail', 'ferry')
       AND ST_DWithin(geom::geography, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?)
     ORDER BY distance_m
     LIMIT ?"
    lng lat lng lat radius-m limit]
   {:builder-fn rs/as-unqualified-lower-maps}))

;; Bounding box query

(defonce bbox-cache (atom nil))

(defn get-isochrone-bbox
  "Get bounding box of all precomputed isochrone_bands geometries.
   Cached in atom since data is static."
  []
  (or @bbox-cache
      (when-let [row (jdbc/execute-one!
                       (get-datasource)
                       ["SELECT ST_XMin(ext) AS west, ST_XMax(ext) AS east,
                               ST_YMin(ext) AS south, ST_YMax(ext) AS north
                        FROM (SELECT ST_Extent(geometry) AS ext FROM isochrone_bands) sub"]
                       {:builder-fn rs/as-unqualified-lower-maps})]
        (let [bbox {:north (:north row)
                    :south (:south row)
                    :east  (:east row)
                    :west  (:west row)}]
          (reset! bbox-cache bbox)
          bbox))))

;; Stats queries

(defn get-intersection-count
  "Get total number of intersections"
  []
  (:count (execute-one! {:select [[:%count.* :count]] :from [:intersections]})))

(defn get-cache-stats
  "Get isochrone cache statistics by mode (from normalized bands table)"
  []
  (jdbc/execute!
   (get-datasource)
   ["SELECT mode,
            COUNT(DISTINCT origin_id) AS count,
            MIN(computed_at) AS oldest,
            MAX(computed_at) AS newest
     FROM isochrone_bands
     GROUP BY mode"]
   {:builder-fn rs/as-unqualified-lower-maps}))

(defn get-computation-progress
  "Get batch computation progress"
  []
  (execute! {:select [:*] :from [:computation_progress]}))

;; Batch processing

(defn get-pending-intersections
  "Get intersections pending computation (no cached bands or incomplete bands).
   Filters to transit-accessible areas and prioritizes NYC boroughs first."
  [mode departure-time day-type & {:keys [limit] :or {limit 100}}]
  (jdbc/execute!
   (get-datasource)
   ["SELECT i.id, i.lat, i.lng
     FROM intersections i
     LEFT JOIN batch_status bs ON i.id = bs.intersection_id
       AND bs.mode = ? AND bs.departure_time = ?::time AND bs.day_type = ?
     LEFT JOIN (
       SELECT origin_id, COUNT(*) as band_count
       FROM isochrone_bands
       WHERE mode = ? AND departure_time = ?::time AND day_type = ?
       GROUP BY origin_id
     ) ib ON i.id = ib.origin_id
     WHERE (bs.id IS NULL OR bs.status = 'pending' OR bs.status = 'completed')
       AND (ib.origin_id IS NULL OR ib.band_count < 8)
       AND i.borough IN ('Manhattan', 'Brooklyn', 'Queens', 'Bronx', 'Staten Island')
     ORDER BY
       CASE i.borough
         WHEN 'Manhattan'     THEN 1
         WHEN 'Brooklyn'      THEN 2
         WHEN 'Queens'        THEN 3
         WHEN 'Bronx'         THEN 4
         WHEN 'Staten Island' THEN 5
       END,
       i.id
     LIMIT ?"
    mode departure-time day-type mode departure-time day-type limit]
   {:builder-fn rs/as-unqualified-lower-maps}))

(defn mark-processing!
  "Mark intersection as being processed"
  [intersection-id mode departure-time day-type]
  (jdbc/execute!
   (get-datasource)
   ["INSERT INTO batch_status (intersection_id, mode, departure_time, day_type, status, started_at)
     VALUES (?, ?, ?::time, ?, 'processing', CURRENT_TIMESTAMP)
     ON CONFLICT (intersection_id, mode, departure_time, day_type)
     DO UPDATE SET status = 'processing', started_at = CURRENT_TIMESTAMP, error_message = NULL"
    intersection-id mode departure-time day-type]))

(defn mark-completed!
  "Mark intersection computation as completed"
  [intersection-id mode departure-time day-type]
  (jdbc/execute!
   (get-datasource)
   ["UPDATE batch_status
     SET status = 'completed', completed_at = CURRENT_TIMESTAMP
     WHERE intersection_id = ? AND mode = ? AND departure_time = ?::time AND day_type = ?"
    intersection-id mode departure-time day-type]))

(defn mark-failed!
  "Mark intersection computation as failed"
  [intersection-id mode departure-time day-type error-message]
  (jdbc/execute!
   (get-datasource)
   ["UPDATE batch_status
     SET status = 'failed', error_message = ?, completed_at = CURRENT_TIMESTAMP
     WHERE intersection_id = ? AND mode = ? AND departure_time = ?::time AND day_type = ?"
    error-message intersection-id mode departure-time day-type]))

(defn reset-failed!
  "Reset all failed batch entries back to pending status for retry"
  [mode departure-time day-type]
  (jdbc/execute-one!
   (get-datasource)
   ["UPDATE batch_status
     SET status = 'pending', error_message = NULL, started_at = NULL, completed_at = NULL
     WHERE mode = ? AND departure_time = ?::time AND day_type = ? AND status = 'failed'
     RETURNING (SELECT COUNT(*) FROM batch_status WHERE mode = ? AND departure_time = ?::time AND day_type = ? AND status = 'pending')"
    mode departure-time day-type mode departure-time day-type]))

(defn get-failed-count
  "Get count of failed batch entries"
  [mode departure-time day-type]
  (:count
   (jdbc/execute-one!
    (get-datasource)
    ["SELECT COUNT(*) as count FROM batch_status
      WHERE mode = ? AND departure_time = ?::time AND day_type = ? AND status = 'failed'"
     mode departure-time day-type]
    {:builder-fn rs/as-unqualified-lower-maps})))

(defn get-batch-summary
  "Get summary of batch processing status for a mode/time/day combination.
   Only counts intersections within the transit-accessible filter scope."
  [mode departure-time day-type]
  (jdbc/execute-one!
   (get-datasource)
   ["SELECT
       (SELECT COUNT(*) FROM intersections
        WHERE borough IN ('Manhattan', 'Brooklyn', 'Queens', 'Bronx', 'Staten Island')
       ) AS total_intersections,
       (SELECT COUNT(*) FROM (
         SELECT origin_id FROM isochrone_bands
         WHERE mode = ? AND departure_time = ?::time AND day_type = ?
         GROUP BY origin_id
         HAVING COUNT(*) = 8
       ) complete) AS cached,
       (SELECT COUNT(*) FROM (
         SELECT origin_id FROM isochrone_bands
         WHERE mode = ? AND departure_time = ?::time AND day_type = ?
         GROUP BY origin_id
         HAVING COUNT(*) < 8
       ) partial) AS partial,
       (SELECT COUNT(*) FROM batch_status
        WHERE mode = ? AND departure_time = ?::time AND day_type = ? AND status = 'failed') AS failed"
    mode departure-time day-type mode departure-time day-type mode departure-time day-type]
   {:builder-fn rs/as-unqualified-lower-maps}))
