(ns backend.cache
  (:require [backend.db :as db]
            [backend.otp :as otp]
            [backend.config :as config]
            [clojure.tools.logging :as log]
            [com.climate.claypoole :as cp]))

(def ^:private default-batch-size 100)
(def ^:private default-parallelism 10)
(def ^:private default-departure-time "10:00:00")
(def ^:private default-day-type "weekday")
(def ^:private default-mode "transit")

(defn- validate-isochrone-coverage
  "Check if 180m isochrone has any coverage.
   Returns nil if valid, or error message if empty."
  [isochrones]
  (when-let [iso-180m (get isochrones :180m)]
    (let [coords (get iso-180m :coordinates [])]
      (when (empty? coords)
        "Empty isochrone - no reachable area"))))

(defn- process-intersection!
  "Process a single intersection - compute isochrone and save to cache.
   Optionally accepts :base-url to target a specific OTP instance.
   Returns :success, :failed, or :error"
  [{:keys [id lat lng]} mode departure-time day-type & {:keys [base-url]}]
  (try
    (log/info "Thread starting intersection" id "via" base-url)
    (db/mark-processing! id mode departure-time day-type)
    (log/info "Marked processing for intersection" id "- calling OTP")
    (let [result (otp/compute-isochrones lat lng
                                          :mode mode
                                          :departure-time departure-time
                                          :day-type day-type
                                          :base-url base-url)]
      (if (:success result)
        ;; Check for truncated results (search timeout)
        (if-let [truncation-error (validate-isochrone-coverage (:isochrones result))]
          (do
            (db/mark-failed! id mode departure-time day-type truncation-error)
            (log/warn "Truncated isochrone for intersection" id "-" truncation-error)
            :failed)
          (do
            (db/save-isochrone! id mode departure-time day-type
                                (:isochrones result))
            (db/mark-completed! id mode departure-time day-type)
            (log/debug "Computed isochrone for intersection" id)
            :success))
        (do
          (db/mark-failed! id mode departure-time day-type (:error result))
          (log/warn "Failed to compute isochrone for intersection" id (:error result))
          :failed)))
    (catch Exception e
      (db/mark-failed! id mode departure-time day-type (.getMessage e))
      (log/error e "Exception computing isochrone for intersection" id)
      :error)))

(defn warm-cache!
  "Pre-compute isochrones for a batch of intersections using parallel processing.
   :parallelism controls thread count (default: 10).
   Each worker is assigned to a dedicated OTP instance for consistent results."
  [& {:keys [mode departure-time day-type batch-size parallelism]
      :or {mode default-mode
           departure-time default-departure-time
           day-type default-day-type
           batch-size default-batch-size
           parallelism default-parallelism}}]
  (let [pending (db/get-pending-intersections mode departure-time day-type
                                               :limit batch-size)
        otp-instances otp/otp-instances
        num-instances (count otp-instances)]
    (log/info "Processing" (count pending) "intersections for" mode departure-time day-type
              "with" parallelism "threads across" num-instances "OTP instances")
    (when (seq pending)
      (let [pool (cp/threadpool parallelism)
            ;; Assign each intersection to an OTP instance based on index
            indexed-pending (map-indexed (fn [idx intersection]
                                           [intersection (nth otp-instances (mod idx num-instances))])
                                         pending)
            results (try
                      (doall
                       (cp/pmap pool
                                (fn [[intersection base-url]]
                                  (process-intersection! intersection mode departure-time day-type
                                                         :base-url base-url))
                                indexed-pending))
                      (finally
                        (cp/shutdown pool)))
            success-count (count (filter #{:success} results))
            failed-count (count (filter #{:failed :error} results))]
        (log/info "Batch complete:" success-count "succeeded," failed-count "failed")))
    (count pending)))
