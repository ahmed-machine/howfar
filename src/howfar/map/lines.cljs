(ns howfar.map.lines
  "Render subway & rail line geometries from static GeoJSON."
  (:require ["leaflet" :as L]))

(defonce transit-layer (atom nil))
(defonce ^:private pane-created? (atom false))

(def ^:private min-zoom 15)
(def ^:private pane-z-index "398")
(def ^:private transit-lines-path "/data/transit-lines.geojson")
(def ^:private default-line-color "#888")
(def ^:private line-weight 2)
(def ^:private line-opacity 0.5)

(defn create-pane
  "Create the transit-lines pane below isochrones."
  [^js map-instance]
  (when-not @pane-created?
    (let [pane (.createPane map-instance "transit-lines")]
      (set! (.. pane -style -zIndex) pane-z-index)
      (set! (.. pane -style -pointerEvents) "none"))
    (reset! pane-created? true)))

(defn update-visibility
  "Show/hide transit lines based on zoom level."
  [^js map-instance]
  (when-let [^js layer @transit-layer]
    (if (>= (.getZoom map-instance) min-zoom)
      (when-not (.hasLayer map-instance layer)
        (.addLayer map-instance layer))
      (when (.hasLayer map-instance layer)
        (.removeLayer map-instance layer)))))

(defn load-and-render!
  "Fetch transit-lines.geojson and add as a styled L.GeoJSON layer."
  [^js map-instance]
  (-> (js/fetch transit-lines-path)
      (.then (fn [^js resp] (.json resp)))
      (.then (fn [data]
               (let [GeoJSON (.-GeoJSON L)
                     layer (new GeoJSON data
                                #js {:pane "transit-lines"
                                     :interactive false
                                     :style (fn [^js feature]
                                              (let [color (.. feature -properties -route_color)]
                                                #js {:color (or color default-line-color)
                                                     :weight line-weight
                                                     :opacity line-opacity}))})]
                 (reset! transit-layer layer)
                 (update-visibility map-instance))))))
