(ns howfar.map.core
  (:require [reagent.core :as r]
            [re-frame.core :as rf]
            [howfar.map.isochrone :as isochrone]
            [howfar.map.lines :as lines]
            [howfar.map.markers :as markers]
            ["leaflet" :as L]))

;; Leaflet map instance
(defonce leaflet-map (atom nil))
(defonce origin-marker (atom nil))
(defonce last-click-time (atom 0))
(defonce move-timer (atom nil))

(defn- bounds->map
  "Extract bounds + zoom from a Leaflet map instance into a Clojure map."
  [^js map-instance]
  (let [bounds (.getBounds map-instance)]
    {:north (.-lat (.getNorthEast bounds))
     :south (.-lat (.getSouthWest bounds))
     :east  (.-lng (.getNorthEast bounds))
     :west  (.-lng (.getSouthWest bounds))
     :zoom  (.getZoom map-instance)}))

(def tile-layer-url "https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}{r}.png")
(def tile-layer-attribution "&copy; <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a> &copy; <a href=\"https://carto.com/attributions\">CARTO</a>")

(def ^:private origin-icon-aspect
  "Width / height ratio of me.png (1629 / 2360)"
  (/ 1629 2360))

(defn origin-icon-height
  "Calculate origin marker icon height in pixels based on zoom level"
  [zoom]
  (let [h (+ 12 (* zoom 2))]
    (max 16 (min h 70))))

(defn make-origin-icon
  "Create origin icon at given zoom level"
  [zoom]
  (let [Icon (.-Icon L)
        h (origin-icon-height zoom)
        w (js/Math.round (* h origin-icon-aspect))]
    (new Icon (clj->js {:iconUrl "/img/me.png"
                         :iconSize [w h]
                         :iconAnchor [(/ w 2) (/ h 2)]}))))

;; Default bounds covering NYC metro area isochrone extent (with padding)
(def default-max-bounds
  {:south 39.5 :west -76
   :north 42 :east -71.75})

(defn- attach-touch-tap-handler
  "Touch tap fallback for mobile.
   Leaflet 2.0 alpha dropped the legacy Tap handler, so we manually detect
   short, small-distance taps and convert them to map clicks.
   Can be removed when Leaflet 2.0 ships proper tap support."
  [^js map-instance]
  (let [container (.getContainer map-instance)
        touch-state (atom nil)]
    (.addEventListener container "touchstart"
      (fn [^js e]
        (when (== (.-length (.-touches e)) 1)
          (let [^js touch (aget (.-touches e) 0)]
            (reset! touch-state {:x (.-clientX touch)
                                 :y (.-clientY touch)
                                 :time (.now js/Date)}))))
      #js {:passive true})
    (.addEventListener container "touchend"
      (fn [^js e]
        (when-let [{:keys [x y time]} @touch-state]
          (reset! touch-state nil)
          (when (== (.-length (.-changedTouches e)) 1)
            (let [^js touch (aget (.-changedTouches e) 0)
                  dx (- (.-clientX touch) x)
                  dy (- (.-clientY touch) y)
                  dist (js/Math.sqrt (+ (* dx dx) (* dy dy)))
                  elapsed (- (.now js/Date) time)
                  now (.now js/Date)]
              (when (and (< dist 15)
                         (< elapsed 500)
                         (> (- now @last-click-time) 300))
                (reset! last-click-time now)
                (let [rect (.getBoundingClientRect container)
                      cx (- (.-clientX touch) (.-left rect))
                      cy (- (.-clientY touch) (.-top rect))
                      Point (.-Point L)
                      pt (new Point cx cy)
                      latlng (.containerPointToLatLng map-instance pt)]
                  (rf/dispatch [:map/click (.-lat latlng) (.-lng latlng)])))))))
      #js {:passive true})))

(defn create-map
  "Initialize Leaflet map"
  [element-id]
  (let [Map (.-Map L)
        TileLayer (.-TileLayer L)
        {:keys [south west north east]} default-max-bounds
        map-instance (new Map element-id #js {:center #js [40.7128 -74.0060]
                                              :zoom 12
                                              :minZoom 8
                                              :zoomControl true
                                              :clickTolerance 10
                                              :maxBoundsViscosity 1.0
                                              :maxBounds #js [#js [south west]
                                                              #js [north east]]})]

    ;; Add tile layer
    (.addTo (new TileLayer tile-layer-url
                           #js {:attribution tile-layer-attribution
                                :maxZoom 19})
            map-instance)

    ;; Handle map click (debounced to prevent double-fire with touch fallback)
    (.on map-instance "click"
         (fn [^js e]
           (let [now (.now js/Date)]
             (when (> (- now @last-click-time) 300)
               (reset! last-click-time now)
               (let [^js latlng (.-latlng e)]
                 (rf/dispatch [:map/click (.-lat latlng) (.-lng latlng)]))))))

    ;; Touch tap fallback for mobile (Leaflet 2.0 alpha dropped legacy Tap handler)
    (attach-touch-tap-handler map-instance)

    ;; Update origin marker icon size on zoom + toggle transit line visibility
    (.on map-instance "zoomend"
         (fn [_]
           (when-let [^js marker @origin-marker]
             (.setIcon marker (make-origin-icon (.getZoom ^js map-instance))))
           (lines/update-visibility map-instance)))

    ;; Handle map move/zoom (debounced to avoid flashing transit stop markers)
    (.on map-instance "moveend"
         (fn [_]
           (when @move-timer (js/clearTimeout @move-timer))
           (reset! move-timer
             (js/setTimeout
               (fn []
                 (rf/dispatch [:map/set-bounds (bounds->map map-instance)]))
               300))))

    ;; Create isolated pane for isochrone layers (painter's algorithm)
    (let [pane (.createPane map-instance "isochrone")]
      (set! (.. pane -style -zIndex) "399")
      (set! (.. pane -style -opacity) "0.35"))

    ;; Transit line layer (below isochrones)
    (lines/create-pane map-instance)
    (lines/load-and-render! map-instance)

    ;; Store reference
    (reset! leaflet-map map-instance)

    ;; Initial bounds dispatch
    (rf/dispatch [:map/set-bounds (bounds->map map-instance)])

    map-instance))

;; Side-effect: pan map to coordinates
(rf/reg-fx
 :geolocation/pan-map
 (fn [{:keys [lat lng]}]
   (when-let [^js m @leaflet-map]
     (.setView m #js [lat lng] 14))))

;; Side-effect: set max bounds on map
(rf/reg-fx
 :map/set-max-bounds
 (fn [{:keys [north south east west]}]
   (when (and north south east west)
     (when-let [^js m @leaflet-map]
       (let [LatLngBounds (.-LatLngBounds L)
             pad 0.05
             bounds (new LatLngBounds
                         #js [(- south pad) (- west pad)]
                         #js [(+ north pad) (+ east pad)])]
         (.setMaxBounds m bounds))))))

(defn update-origin-marker
  "Update or create origin marker"
  [intersection]
  (when-let [^js map-instance @leaflet-map]
    (let [Marker (.-Marker L)]
      ;; Remove existing marker
      (when-let [^js m @origin-marker]
        (.remove m))

      ;; Add new marker if intersection selected
      (when intersection
        (let [icon (make-origin-icon (.getZoom map-instance))
              marker (.addTo
                      (new Marker #js [(:lat intersection) (:lng intersection)]
                                  #js {:icon icon})
                      map-instance)]
          (reset! origin-marker marker))))))

(defn map-component
  "Main map component"
  []
  (let [mounted (r/atom false)
        prev-isochrone (atom nil)]
    (r/create-class
     {:component-did-mount
      (fn [_]
        (when-not @mounted
          (create-map "map")
          (reset! mounted true)))

      :component-did-update
      (fn [this _]
        (let [[_ intersection isochrone-rings transit-stops] (r/argv this)]
          (update-origin-marker intersection)
          (when (not= isochrone-rings @prev-isochrone)
            (reset! prev-isochrone isochrone-rings)
            (isochrone/update-isochrone-layers @leaflet-map isochrone-rings))
          (markers/update-stop-markers @leaflet-map transit-stops)))

      :reagent-render
      (fn [_intersection _isochrone-rings _transit-stops]
        [:div#map {:style {:height "100%" :width "100%"}}])})))

(defn map-container
  "Map container with subscriptions"
  []
  (let [intersection @(rf/subscribe [:origin/intersection])
        isochrone-features @(rf/subscribe [:isochrone/geojson-features])
        transit-stops @(rf/subscribe [:viewport/transit-stops])]
    [map-component intersection isochrone-features transit-stops]))
