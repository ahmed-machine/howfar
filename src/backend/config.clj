(ns backend.config
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

(defonce config (atom nil))

(defn- parse-dotenv
  "Parse a .env file into a map of variable names to values.
   Ignores blank lines and comments."
  [path]
  (let [file (io/file path)]
    (when (.exists file)
      (->> (slurp file)
           str/split-lines
           (remove #(or (str/blank? %) (str/starts-with? (str/trim %) "#")))
           (keep (fn [line]
                   (when-let [idx (str/index-of line "=")]
                     [(str/trim (subs line 0 idx))
                      (str/trim (subs line (inc idx)))])))
           (into {})))))

(defn- resolve-env
  "Resolve a config value: real env var > .env file > default."
  [dotenv var-name default]
  (or (System/getenv var-name)
      (get dotenv var-name)
      default))

(defn load-config
  "Load configuration from resources/config.edn, with .env file support.
   Priority: environment variables > .env file > config.edn defaults."
  []
  (let [;; Try .env relative to working dir, then relative to classpath root
        dotenv-file (let [f (io/file ".env")]
                      (if (.exists f)
                        f
                        (some-> (io/resource "config.edn")
                                (io/file)
                                (.getParentFile)   ; resources/
                                (.getParentFile)   ; project root
                                (io/file ".env"))))
        dotenv (if (and dotenv-file (.exists dotenv-file))
                 (do (log/info "Loading .env from" (.getAbsolutePath dotenv-file))
                     (parse-dotenv (.getAbsolutePath dotenv-file)))
                 (do (log/warn "No .env file found (cwd:" (System/getProperty "user.dir") ")")
                     {}))
        base (aero/read-config (io/resource "config.edn"))]
    (let [db-host   (resolve-env dotenv "DB_HOST" "localhost")
          resolved {"DB_HOST"     db-host
                     "DB_PORT"     (resolve-env dotenv "DB_PORT" "5432")
                     "DB_USER"     (resolve-env dotenv "DB_USER" "howfar")
                     "DB_PASSWORD" (resolve-env dotenv "DB_PASSWORD" nil)
                     "DB_NAME"     (resolve-env dotenv "DB_NAME" "howfar")
                     "PORT"        (resolve-env dotenv "PORT" "3001")
                     "OTP_URL"     (resolve-env dotenv "OTP_URL" (str "http://" db-host ":8080"))}]
      (log/info "Resolved config:")
      (doseq [[k v] (sort resolved)]
        (log/info (str "  " k "=" (if (= k "DB_PASSWORD") "********" v))))
      (reset! config
              (-> base
                  (assoc-in [:database :host] (get resolved "DB_HOST"))
                  (assoc-in [:database :port] (Long/parseLong (get resolved "DB_PORT")))
                  (assoc-in [:database :user] (get resolved "DB_USER"))
                  (assoc-in [:database :password] (get resolved "DB_PASSWORD"))
                  (assoc-in [:database :dbname] (get resolved "DB_NAME"))
                  (assoc-in [:server :port] (Long/parseLong (get resolved "PORT")))
                  (assoc-in [:otp :base-url] (get resolved "OTP_URL")))))))

(defn get-config
  "Get current configuration, loading if not yet loaded"
  []
  (or @config (load-config)))

(defn server-config [] (:server (get-config)))
(defn db-config [] (:database (get-config)))
(defn otp-config [] (:otp (get-config)))
(defn isochrone-config [] (:isochrone (get-config)))
