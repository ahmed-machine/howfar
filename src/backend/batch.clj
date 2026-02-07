(ns backend.batch
  "Batch processing for pre-computing isochrones"
  (:require [backend.config :as config]
            [backend.db :as db]
            [backend.cache :as cache]
            [backend.otp :as otp]
            [clojure.tools.logging :as log])
  (:gen-class))

(defn wait-for-otp
  "Wait for OTP to become available"
  [& {:keys [max-attempts interval-ms] :or {max-attempts 30 interval-ms 10000}}]
  (log/info "Waiting for OTP to become available...")
  (loop [attempts 0]
    (cond
      (otp/health-check)
      (do (log/info "OTP is ready") true)

      (>= attempts max-attempts)
      (do (log/error "OTP did not become available after" max-attempts "attempts")
          false)

      :else
      (do
        (log/info "OTP not ready, waiting..." (str "(" (inc attempts) "/" max-attempts ")"))
        (Thread/sleep interval-ms)
        (recur (inc attempts))))))

(defn run-batch
  "Run batch processing for a specific mode/time/day combination.
   :parallelism controls thread count (default: 15)"
  [& {:keys [mode departure-time day-type batch-size max-batches parallelism]
      :or {mode "transit"
           departure-time "10:00:00"
           day-type "weekday"
           batch-size 100
           max-batches nil
           parallelism 15}}]
  (log/info "Starting batch processing:"
            {:mode mode :departure-time departure-time :day-type day-type :parallelism parallelism})

  ;; Log summary of current state (shows already-cached count)
  (let [summary (db/get-batch-summary mode departure-time day-type)
        total (:total_intersections summary)
        cached (:cached summary)
        failed (:failed summary)
        remaining (- total cached)]
    (log/info "Batch status:"
              {:total total :already-cached cached :failed failed :remaining remaining})
    (println (str "[batch] " cached "/" total " cached (" (format "%.1f" (if (pos? total) (* 100.0 (/ (double cached) total)) 0.0)) "%), "
                  remaining " remaining, " failed " failed"))
    (if (zero? remaining)
      (do
        (log/info "All intersections already cached, nothing to do")
        (println "[batch] Nothing to do - all intersections cached.")
        0)
      ;; Process remaining intersections
      (let [start-ms (System/currentTimeMillis)]
        (loop [batch-num 1
               total-processed 0]
          (let [processed (cache/warm-cache! :mode mode
                                             :departure-time departure-time
                                             :day-type day-type
                                             :batch-size batch-size
                                             :parallelism parallelism)
                new-total (+ total-processed processed)
                now-cached (+ cached new-total)
                pct (if (pos? total) (* 100.0 (/ (double now-cached) total)) 0.0)
                elapsed-s (/ (- (System/currentTimeMillis) start-ms) 1000.0)
                rate (if (pos? elapsed-s) (/ new-total elapsed-s) 0.0)]
            (println (str "[batch " batch-num "] "
                          now-cached "/" total " (" (format "%.1f" pct) "%) | "
                          "+" processed " this batch | "
                          (format "%.1f" rate) "/s | "
                          (- remaining new-total) " remaining"))
            (if (and (> processed 0)
                     (or (nil? max-batches) (< batch-num max-batches)))
              (do
                (log/info "Batch" batch-num "complete:" processed "intersections processed."
                          "Total:" new-total)
                (recur (inc batch-num) new-total))
              (do
                (log/info "Batch processing complete. Total processed:" new-total)
                (println (str "[batch] Done. Processed " new-total " intersections in "
                              (format "%.0f" elapsed-s) "s ("
                              (format "%.1f" rate) "/s)"))
                new-total))))))))

(defn print-status
  "Print batch status summary"
  [mode departure-time day-type]
  (let [summary (db/get-batch-summary mode departure-time day-type)
        total (:total_intersections summary)
        cached (:cached summary)
        partial (:partial summary 0)
        failed (:failed summary)
        remaining (- total cached)
        pct (if (pos? total) (* 100.0 (/ cached total)) 0.0)]
    (println)
    (println "Batch Status Summary")
    (println "====================")
    (println (str "Mode:           " mode))
    (println (str "Departure time: " departure-time))
    (println (str "Day type:       " day-type))
    (println)
    (println (str "Total intersections: " total))
    (println (str "Fully cached:        " cached " (" (format "%.1f" pct) "%)"))
    (when (pos? partial)
      (println (str "Partial (< 8 bands): " partial)))
    (println (str "Failed:              " failed))
    (println (str "Remaining:           " remaining))
    (println)))

(defn retry-failed
  "Reset failed entries and rerun batch processing"
  [mode departure-time day-type parallelism]
  (let [failed-count (db/get-failed-count mode departure-time day-type)]
    (if (zero? failed-count)
      (do
        (log/info "No failed entries to retry")
        (println "No failed entries to retry")
        0)
      (do
        (log/info "Resetting" failed-count "failed entries for retry")
        (println (str "Resetting " failed-count " failed entries for retry..."))
        (db/reset-failed! mode departure-time day-type)
        (run-batch :mode mode
                   :departure-time departure-time
                   :day-type day-type
                   :parallelism parallelism)))))

(defn print-usage
  []
  (println)
  (println "Usage: clj -M:batch <command> [options]")
  (println)
  (println "Commands:")
  (println "  status [mode] [time] [day-type]  Show batch processing status")
  (println "  run [mode] [time] [day-type] [parallelism]  Run batch processing (default)")
  (println "  retry [mode] [time] [day-type] [parallelism]  Retry failed entries")
  (println)
  (println "Options:")
  (println "  mode         Transport mode: transit, transit+bike, bike (default: transit)")
  (println "  time         Departure time HH:MM:SS (default: 08:00:00)")
  (println "  day-type     Day type: weekday, saturday, sunday (default: weekday)")
  (println "  parallelism  Number of parallel threads (default: 15)")
  (println)
  (println "Examples:")
  (println "  clj -M:batch status")
  (println "  clj -M:batch status transit 08:00:00 weekday")
  (println "  clj -M:batch run transit 08:00:00 weekday")
  (println "  clj -M:batch run bike 10:00:00 weekday 15")
  (println "  clj -M:batch run transit+bike 08:00:00 weekday 15")
  (println "  clj -M:batch retry transit 08:00:00 weekday 24  # Retry failed with 24 threads")
  (println))

(defn -main
  "Main entry point for batch processing"
  [& args]
  (config/load-config)

  (let [command (first args)
        ;; If first arg looks like a mode (not a command), treat as implicit "run"
        [command rest-args] (cond
                              (nil? command) ["run" []]
                              (#{"status" "run" "retry" "help" "--help" "-h"} command) [command (rest args)]
                              :else ["run" args])
        mode (or (first rest-args) "transit")
        departure-time (or (second rest-args) "10:00:00")
        day-type (or (nth rest-args 2 nil) "weekday")
        parallelism (if-let [p (nth rest-args 3 nil)]
                      (Integer/parseInt p)
                      15)]

    (case command
      ("help" "--help" "-h")
      (print-usage)

      "status"
      (print-status mode departure-time day-type)

      "run"
      (if (wait-for-otp)
        (do
          (log/info "Starting batch computation for" mode departure-time day-type
                    "with" parallelism "threads")
          (let [processed (run-batch :mode mode
                                     :departure-time departure-time
                                     :day-type day-type
                                     :parallelism parallelism)]
            (log/info "Batch job completed. Processed" processed "intersections.")
            (System/exit 0)))
        (do
          (log/error "Could not connect to OTP. Exiting.")
          (System/exit 1)))

      "retry"
      (if (wait-for-otp)
        (do
          (log/info "Retrying failed entries for" mode departure-time day-type
                    "with" parallelism "threads")
          (let [processed (retry-failed mode departure-time day-type parallelism)]
            (log/info "Retry completed. Processed" processed "intersections.")
            (System/exit 0)))
        (do
          (log/error "Could not connect to OTP. Exiting.")
          (System/exit 1))))))
