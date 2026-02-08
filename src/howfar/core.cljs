(ns howfar.core
  (:require [reagent.dom :as rdom]
            [re-frame.core :as rf]
            [howfar.api]
            [howfar.state]
            [howfar.map.core :as map-core]
            [howfar.components.overlays :as overlays]))

(defn app
  "Main application component"
  []
  [:div.app-container
   [:div.map-container
    [map-core/map-container]]
   [overlays/overlay-container]])

(defn ^:export init
  "Initialize application"
  []
  (rf/dispatch-sync [:initialize-db])
  (rf/dispatch [:keyboard/add-listener])
  (rdom/render [app] (.getElementById js/document "app")))

(defn ^:export reload
  "Hot reload callback"
  []
  (rf/clear-subscription-cache!)
  (rdom/render [app] (.getElementById js/document "app")))
