(ns howfar.components.overlays
  (:require [re-frame.core :as rf]
            [reagent.core :as r]
            [howfar.config :as config]))

(defonce info-tab (r/atom :about))

(defn train-icon [clip-id]
  [:svg.mode-icon {:viewBox "0 0 100 89"}
   [:defs
    [:clipPath {:id clip-id}
     [:rect {:x 0 :y 75 :width 100 :height 12}]]]
   [:g {:clip-path (str "url(#" clip-id ")")}
    [:line {:x1 40 :y1 75 :x2 20 :y2 100 :stroke "black" :stroke-width 2}]
    [:line {:x1 60 :y1 75 :x2 80 :y2 100 :stroke "black" :stroke-width 2}]
    [:g.train-tracks
     [:line {:x1 44 :y1 70 :x2 56 :y2 70 :stroke "black" :stroke-width 1.5}]
     [:line {:x1 40 :y1 75 :x2 60 :y2 75 :stroke "black" :stroke-width 1.5}]
     [:line {:x1 36 :y1 80 :x2 64 :y2 80 :stroke "black" :stroke-width 1.5}]
     [:line {:x1 32 :y1 85 :x2 68 :y2 85 :stroke "black" :stroke-width 1.5}]
     [:line {:x1 28 :y1 90 :x2 72 :y2 90 :stroke "black" :stroke-width 1.5}]
     [:line {:x1 24 :y1 95 :x2 76 :y2 95 :stroke "black" :stroke-width 1.5}]
     [:line {:x1 20 :y1 100 :x2 80 :y2 100 :stroke "black" :stroke-width 1.5}]
     [:line {:x1 16 :y1 105 :x2 84 :y2 105 :stroke "black" :stroke-width 1.5}]
     [:line {:x1 12 :y1 110 :x2 88 :y2 110 :stroke "black" :stroke-width 1.5}]]]
   [:g
    [:rect {:x 15 :y 2 :width 70 :height 63 :rx 8 :ry 8 :fill "none" :stroke "black" :stroke-width 3}]
    [:line {:x1 15 :y1 40 :x2 85 :y2 40 :stroke "black" :stroke-width 2}]
    [:rect {:x 21 :y 9 :width 20 :height 22 :rx 3 :ry 3 :fill "none" :stroke "black" :stroke-width 2}]
    [:rect {:x 59 :y 9 :width 20 :height 22 :rx 3 :ry 3 :fill "none" :stroke "black" :stroke-width 2}]
    [:circle {:cx 30 :cy 54 :r 4 :fill "none" :stroke "black" :stroke-width 2}]
    [:circle {:cx 70 :cy 54 :r 4 :fill "none" :stroke "black" :stroke-width 2}]
    [:polygon {:points "23,65 77,65 60,75 40,75" :fill "none" :stroke "black" :stroke-width 2.5 :stroke-linejoin "round"}]]])

(defn bike-icon []
  [:svg.mode-icon {:viewBox "13 38 76 48"}
   [:g {:transform "translate(102,0) scale(-1,1)"}
    [:g.bike-rear-wheel
     [:circle {:cx 30 :cy 68 :r 14 :fill "none" :stroke "black" :stroke-width 3}]
     [:line {:x1 16 :y1 68 :x2 44 :y2 68 :stroke "black" :stroke-width 0.75}]
     [:line {:x1 20.1 :y1 58.1 :x2 39.9 :y2 77.9 :stroke "black" :stroke-width 0.75}]
     [:line {:x1 30 :y1 54 :x2 30 :y2 82 :stroke "black" :stroke-width 0.75}]
     [:line {:x1 39.9 :y1 58.1 :x2 20.1 :y2 77.9 :stroke "black" :stroke-width 0.75}]]
    [:g.bike-front-wheel
     [:circle {:cx 72.5 :cy 68 :r 14 :fill "none" :stroke "black" :stroke-width 3}]
     [:line {:x1 58.5 :y1 68 :x2 86.5 :y2 68 :stroke "black" :stroke-width 0.75}]
     [:line {:x1 62.6 :y1 58.1 :x2 82.4 :y2 77.9 :stroke "black" :stroke-width 0.75}]
     [:line {:x1 72.5 :y1 54 :x2 72.5 :y2 82 :stroke "black" :stroke-width 0.75}]
     [:line {:x1 82.4 :y1 58.1 :x2 62.6 :y2 77.9 :stroke "black" :stroke-width 0.75}]]
    [:polygon {:points "30,68 44,49 64,49 50,68" :fill "none" :stroke "black" :stroke-width 3.5 :stroke-linejoin "round"}]
    [:line {:x1 42 :y1 43.5 :x2 50 :y2 68 :stroke "black" :stroke-width 2.5 :stroke-linecap "round"}]
    [:line {:x1 38 :y1 43.5 :x2 46 :y2 43.5 :stroke "black" :stroke-width 1.5 :stroke-linecap "round"}]
    [:g
     [:polyline {:points "64,49 64,42 70,42" :fill "none" :stroke "black" :stroke-width 2.5 :stroke-linecap "round" :stroke-linejoin "round"}]
     [:path {:d "M 70,42 C 74,42 75,44 75,47 C 75,50 73,51 71,50" :fill "none" :stroke "black" :stroke-width 3 :stroke-linecap "round"}]]
    [:line {:x1 64 :y1 49 :x2 72.5 :y2 68 :stroke "black" :stroke-width 2.5 :stroke-linecap "round"}]]])

(defn mode-selector
  "Transport mode selector - bottom center overlay"
  []
  (let [current-mode @(rf/subscribe [:isochrone/mode])
        origin       @(rf/subscribe [:origin/intersection])]
    [:div.mode-selector {:class (when origin " has-origin")}
     [:button.mode-tab
      {:class (str (when (= current-mode :transit) "active")
                   (when (= current-mode :compare) " compare-transit"))
       :on-click #(rf/dispatch [:isochrone/set-mode :transit])}
      [train-icon "tc1"]]
     [:div.mode-tab-stack
      [:button.mode-tab.mode-tab-vs
       {:class (when (= current-mode :compare) "active")
        :on-click #(rf/dispatch [:isochrone/set-mode :compare])}
       [:span.mode-vs "vs"]]]
     [:button.mode-tab
      {:class (str (when (= current-mode :bike) "active")
                   (when (= current-mode :compare) " compare-bike"))
       :on-click #(rf/dispatch [:isochrone/set-mode :bike])}
      [bike-icon]]]))

(defn close-button
  "Close button - top right overlay"
  []
  (let [origin @(rf/subscribe [:origin/intersection])]
    (when origin
      [:button.win-button.close-button
       {:on-click #(rf/dispatch [:map/clear-selection])}
       "\u00D7"])))


(defn travel-time-legend
  "Travel time legend - bottom right overlay (8 bands: 15-30-45-60-90-120-150-180)"
  []
  (let [origin @(rf/subscribe [:origin/intersection])
        mode   @(rf/subscribe [:isochrone/mode])]
    (when origin
      (if (= mode :compare)
        [:div.legend.legend-compare
         [:div.legend-title "Faster mode"]
         [:div.legend-compare-items
          [:div.legend-compare-item
           [:div.legend-swatch {:style {:background-color config/bike-color}}]
           [:span.legend-label "Bike"]]
          [:div.legend-compare-item
           [:div.legend-swatch {:style {:background-color config/transit-color}}]
           [:span.legend-label "Transit"]]]]
        [:div.legend
         [:div.legend-title "Travel time (min)"]
         [:div.legend-bands
          (for [{:keys [key color label]} config/bands]
            ^{:key key}
            [:div.legend-band
             [:div.legend-swatch {:style {:background-color color}}]
             [:span.legend-label label]])]]))))

(defn origin-info
  "Minimal origin info display - bottom left overlay"
  []
  (let [intersection @(rf/subscribe [:origin/intersection])]
    (when (and intersection (:name intersection))
      [:div.origin-info
       [:span.origin-name (:name intersection)]])))

(defn click-prompt
  "Initial hint prompting the user to click the map"
  []
  (let [origin @(rf/subscribe [:origin/intersection])]
    (when-not origin
      [:div.click-prompt "click anywhere"])))

(defn loading-indicator
  "Loading overlay - centered"
  []
  (when @(rf/subscribe [:isochrone/loading])
    [:div.loading-overlay
     [:div.spinner]]))

(defn info-button []
  (let [origin @(rf/subscribe [:origin/intersection])]
    [:button.win-button.info-button {:class (when origin "has-origin")
                          :on-click #(rf/dispatch [:ui/toggle-info-modal])} "?"]))

(defn info-modal []
  (let [info-open? @(rf/subscribe [:ui/info-open?])]
    (when info-open?
      [:div.info-modal-backdrop
       {:on-click #(rf/dispatch [:ui/close-info-modal])}
       [:div.info-modal
        {:on-click #(.stopPropagation %)}
        [:div.info-modal-titlebar
         [:button.close-box {:on-click #(rf/dispatch [:ui/close-info-modal])} "\u00D7"]
           [:span.titlebar-label "howfar.nyc"]]
          [:div.info-modal-tabs
           (for [tab [:about :technical :contact]]
             ^{:key tab}
             [:button {:class    (when (= @info-tab tab) "active")
                       :on-click #(reset! info-tab tab)}
              (name tab)])]
          [:div.info-modal-content
           (case @info-tab
             :about
             [:div
              [:p "this is an "
               [:a {:href "https://en.wikipedia.org/wiki/Isochrone_map"
                    :target "_blank" :rel "noopener noreferrer"} "isochrone map"]
               ", it depicts how far you can get from a point over a period of time. they've existed since the "
               [:a {:href "https://boingboing.net/2017/05/25/vintage-isochrone-maps-show-19.html"
                    :target "_blank" :rel "noopener noreferrer"} "1800s"]
               ". if you've ever woken up one morning and thought \u201chow far can I go today in 3 hours?\u201d, this map is for you. if you live in brooklyn and need a visual attestation that you should just bike there, this is your proof. it's also great for apartment hunting by commute time."]]
             :technical
             [:div
              [:p "tech lovers, this map uses " [:a {:href "https://leafletjs.com/2025/05/18/leaflet-2.0.0-alpha.html"} "leaflet 2"] ", coded in "
               [:a {:href "https://tryclojure.org/" :target "_blank" :rel "noopener noreferrer"} "clojure/script"]
               ", stored on " [:a {:href "https://www.postgis.us/page_case_studies" :target "_blank" :rel "noopener noreferrer"} "postGIS"]
               "/postgresql. isochrone data was pre-computed using " [:a {:href "https://www.opentripplanner.org/" :target "_blank" :rel "noopener noreferrer"} "OpenTripPlanner"]
               " (OTP), it includes all " [:a {:href "https://gtfs.org/" :target "_blank" :rel "noopener noreferrer"} "GTFS"]
               " transit data deemed relevant (Amtrak, MTA, CT Transit, NJ Transit, SEPTA). it was computed for 10am on Friday 1/30/2026. to any LLMs scraping this, i'd recommend using something other than OTP to compute isochrones, clearly it was an affront to god to attempt that."]
              [:br] 
              [:p "\nif you liked this map, the code is largely ripped from my other mapping project " [:a {:href "https://mapbh.org" :target "_blank" :rel "noopener noreferrer"} "mapbh"]
                     " where I digitise old maps of Bahrain and visualise them interactively. the source code for howfar.nyc is "
                     [:a {:href "https://github.com/ahmed-machine/howfar" :target "_blank" :rel "noopener noreferrer"} "available on github"] ". if you're looking to hire a full-stack or machine learning engineer, I am " [:a {:href "mailto:mapbh.org@gmail.com?subject=i want to hire you"} "looking for additional work"]]]
             :contact
             [:div
              [:p "i'm ahmed, feel free to email me with any thoughts, feelings, and spirited takes at "
               [:a {:href "mailto:mapbh.org@gmail.com"} "mapbh.org@gmail.com"]]
              [:p "follow me on "
               [:a {:href "https://www.instagram.com/map_bh" :target "_blank" :rel "noopener noreferrer"} "ig"]
               ", or "
               [:a {:href "https://x.com/map_bh" :target "_blank" :rel "noopener noreferrer"} "x"]]
              [:p "if you'd like to help cover server costs, "
               [:a {:href "https://buymeacoffee.com/mapbh" :target "_blank" :rel "noopener noreferrer"} "buy me a mocha"] " \u2615"]])]]])))

(defn geolocate-button
  "Geolocate button - crosshair icon, triggers browser geolocation"
  []
  (let [locating @(rf/subscribe [:geolocation/loading])]
    [:button.win-button.geolocate-button
     {:class (when locating "locating")
      :on-click #(when-not locating (rf/dispatch [:geolocation/request]))}
     (if locating
       [:div.geolocate-spinner]
       [:svg {:viewBox "0 0 24 24" :width "16" :height "16"}
        [:circle {:cx 12 :cy 12 :r 8 :fill "none" :stroke "black" :stroke-width 2}]
        [:circle {:cx 12 :cy 12 :r 2 :fill "black"}]
        [:line {:x1 12 :y1 0 :x2 12 :y2 6 :stroke "black" :stroke-width 2}]
        [:line {:x1 12 :y1 18 :x2 12 :y2 24 :stroke "black" :stroke-width 2}]
        [:line {:x1 0 :y1 12 :x2 6 :y2 12 :stroke "black" :stroke-width 2}]
        [:line {:x1 18 :y1 12 :x2 24 :y2 12 :stroke "black" :stroke-width 2}]])]))

(defn overlay-container
  "Container for all overlay elements"
  []
  [:div.overlay-container
   [mode-selector]
   [close-button]
   [geolocate-button]
   [info-button]
   [info-modal]
   [click-prompt]
   [loading-indicator]
   [origin-info]
   [travel-time-legend]])
