(ns howfar.map.markers
  (:require ["leaflet" :as L]
            [clojure.string :as str]))

(def ^:private marker-size 22)
(def ^:private marker-anchor 11)
(def ^:private min-stop-zoom 15)
(def ^:private bus-zoom 17)

;; Colors
(def ^:private color-subway "#2563eb")
(def ^:private color-bus "#d97706")
(def ^:private color-bus-wheel "#92400e")
(def ^:private color-ferry "#0891b2")
(def ^:private color-default "#9ca3af")
(def ^:private color-rail-regional "#059669")
(def ^:private color-rail-amtrak "#dc2626")
(def ^:private color-rail-other "#6b7280")

;; Priority values
(def ^:private priority-amtrak 500)
(def ^:private priority-regional-rail 400)
(def ^:private priority-other-rail 300)
(def ^:private priority-ferry 200)
(def ^:private priority-subway 100)

;; Transit stop marker clusters
(defonce stop-markers (atom []))

(defn- escape-html
  "Escape HTML special characters to prevent XSS"
  [s]
  (when s
    (-> (str s)
        (str/replace "&" "&amp;")
        (str/replace "<" "&lt;")
        (str/replace ">" "&gt;")
        (str/replace "\"" "&quot;"))))

(defn- stop-priority
  "Return a numeric priority for z-ordering. Higher = rendered on top."
  [{:keys [stop_type agency]}]
  (case stop_type
    "rail"   (case agency
               "Amtrak"                             priority-amtrak
               ("LIRR" "Metro-North" "NJ Transit")  priority-regional-rail
               priority-other-rail)
    "ferry"  priority-ferry
    "subway" priority-subway
    0))

(defn- rail-color
  "Return fill color for a rail stop based on agency"
  [agency]
  (case agency
    ("LIRR" "Metro-North" "NJ Transit") color-rail-regional
    "Amtrak"                             color-rail-amtrak
    color-rail-other))

(defn stop-icon-svg
  "Return an SVG string with a shape reflecting the transit mode"
  [stop-type agency]
  (case stop-type
    "subway"  (str "<svg width='22' height='22' viewBox='0 0 22 22'>"
                   "<rect x='4' y='3' width='14' height='14' rx='3.5' fill='#2563eb' opacity='0.9'/>"
                   "<rect x='6.5' y='6' width='3.5' height='3' rx='0.75' fill='white' opacity='0.95'/>"
                   "<rect x='12' y='6' width='3.5' height='3' rx='0.75' fill='white' opacity='0.95'/>"
                   "<rect x='7' y='12' width='8' height='1.5' rx='0.75' fill='white' opacity='0.6'/>"
                   "</svg>")
    "rail"    (let [c (rail-color agency)]
               (str "<svg width='22' height='22' viewBox='0 0 22 22'>"
                    "<polygon points='11,2 20,11 11,20 2,11' fill='" c "' opacity='0.85'/>"
                    "</svg>"))
    "bus"     (str "<svg width='22' height='22' viewBox='0 0 22 22' opacity='0.35'>"
                   "<rect x='3' y='4' width='16' height='11' rx='2.5' fill='#d97706'/>"
                   "<rect x='5' y='5.5' width='4' height='3' rx='0.75' fill='white'/>"
                   "<rect x='10.5' y='5.5' width='4' height='3' rx='0.75' fill='white'/>"
                   "<circle cx='7' cy='16' r='1.5' fill='#92400e'/>"
                   "<circle cx='15' cy='16' r='1.5' fill='#92400e'/>"
                   "</svg>")
    "ferry"   (str "<svg width='22' height='22' viewBox='0 0 22 22'>"
                   "<polygon points='11,4 18,14 4,14' fill='#0891b2' opacity='0.85'/>"
                   "<path d='M2,17 Q6,13.5 11,17 Q16,20.5 20,17' fill='none' stroke='#0891b2' stroke-width='1.5' opacity='0.85'/>"
                   "</svg>")
    ;; default
    (str "<svg width='22' height='22' viewBox='0 0 22 22'>"
         "<circle cx='11' cy='11' r='7' fill='#9ca3af' opacity='0.75'/>"
         "</svg>")))

(defn clear-stop-markers
  "Remove all transit stop markers from map"
  [map-instance]
  (when map-instance
    (doseq [marker @stop-markers]
      (.remove marker))
    (reset! stop-markers [])))

(defn create-stop-marker
  "Create a marker for a transit stop"
  [stop]
  (let [Marker (.-Marker L)
        DivIcon (.-DivIcon L)
        icon (new DivIcon (clj->js {:className "transit-stop-marker"
                                    :html (stop-icon-svg (:stop_type stop) (:agency stop))
                                    :iconSize [marker-size marker-size]
                                    :iconAnchor [marker-anchor marker-anchor]}))]
    (new Marker #js [(:lat stop) (:lng stop)]
                #js {:icon icon
                     :zIndexOffset (stop-priority stop)})))

(defn add-stop-popup
  "Add popup to stop marker"
  [^js marker stop]
  (.bindPopup marker
              (str "<b>" (escape-html (:stop_name stop)) "</b><br>"
                   (when (:agency stop) (str (escape-html (:agency stop)) "<br>"))
                   (when (:stop_type stop) (escape-html (:stop_type stop)))))
  marker)

(defn update-stop-markers
  "Update transit stop markers on map (only at zoom >= 15)"
  [^js map-instance stops]
  (clear-stop-markers map-instance)
  (when (and map-instance (seq stops)
             (>= (.getZoom map-instance) min-stop-zoom))
    (let [zoom (.getZoom map-instance)
          show-bus? (>= zoom bus-zoom)]
      (doseq [stop (sort-by stop-priority stops)
              :when (or (not= (:stop_type stop) "bus") show-bus?)]
        (let [marker (-> (create-stop-marker stop)
                         (add-stop-popup stop))]
          (.addTo marker map-instance)
          (swap! stop-markers conj marker))))))
