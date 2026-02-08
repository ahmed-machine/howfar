(ns howfar.map.isochrone
  (:require ["leaflet" :as L]
            [howfar.config :as config]))

(def ^:private default-band-color "#888")
(def ^:private fill-opacity 1.0)
(def ^:private border-weight 1.5)
(def ^:private border-opacity 1.0)

;; Store isochrone layers for cleanup
(defonce isochrone-layers (atom []))

(defn clear-isochrone-layers
  "Remove all isochrone layers from map"
  [map-instance]
  (when map-instance
    (doseq [layer @isochrone-layers]
      (.remove layer))
    (reset! isochrone-layers [])))

(defn create-isochrone-layer
  "Create a Leaflet GeoJSON layer for an isochrone band.
   Rendered into the 'isochrone' pane with opaque fills — pane-level
   opacity handles transparency for the entire group.
   Optional color-override replaces the default band color."
  [geometry band & {:keys [color-override]}]
  (let [GeoJSON (.-GeoJSON L)
        color (or color-override (get config/band-colors band default-band-color))
        geojson (clj->js {:type "Feature"
                          :geometry geometry
                          :properties {:band (name band)}})]
    (new GeoJSON geojson
                 #js {:pane "isochrone"
                      :style #js {:fillColor color
                                  :fillOpacity fill-opacity
                                  :color color
                                  :weight border-weight
                                  :opacity border-opacity}})))

(defn update-isochrone-layers
  "Update isochrone layers on map.
   Receives raw features and renders largest-first (painter's algorithm).
   Each inner polygon's opaque fill covers the outer, so every pixel
   shows only the smallest band's color.
   In comparison mode, features have :order and :color keys for explicit
   interleaved rendering."
  [map-instance features]
  (clear-isochrone-layers map-instance)
  (when (and map-instance (seq features))
    (let [has-order? (some :order features)
          sorted (if has-order?
                   (sort-by :order features)
                   (reverse (sort-by #(get config/band-order (:band %) 99) features)))]
      (doseq [{:keys [band geometry color]} sorted]
        (when geometry
          (let [layer (create-isochrone-layer geometry band :color-override color)]
            (.addTo layer map-instance)
            (swap! isochrone-layers conj layer)))))))
