(ns howfar.state
  (:require [re-frame.core :as rf]
            [howfar.config :as config]))

;;--- DB & Subscriptions ---

(def default-db
  {:origin/intersection nil          ; Selected intersection
   :isochrone/data nil               ; GeoJSON isochrone polygons
   :isochrone/compare-data nil       ; Secondary mode data for comparison
   :isochrone/mode :transit          ; :transit, :transit+bike, :bike, :compare
   :isochrone/day-type :weekday      ; Day type
   :isochrone/visible-bands (set config/band-keys)
   :isochrone/loading false
   :geolocation/loading false
   :map/max-bounds nil
   :viewport/transit-stops []
   :viewport/bounds nil
   :ui/info-open? false
   :errors {}})

(rf/reg-event-db
 :initialize-db
 (fn [_ _]
   default-db))

(rf/reg-sub :origin/intersection (fn [db] (:origin/intersection db)))
(rf/reg-sub :isochrone/data (fn [db] (:isochrone/data db)))
(rf/reg-sub :isochrone/compare-data (fn [db] (:isochrone/compare-data db)))
(rf/reg-sub :isochrone/mode (fn [db] (:isochrone/mode db)))
(rf/reg-sub :isochrone/day-type (fn [db] (:isochrone/day-type db)))
(rf/reg-sub :isochrone/visible-bands (fn [db] (:isochrone/visible-bands db)))
(rf/reg-sub :isochrone/loading (fn [db] (:isochrone/loading db)))
(rf/reg-sub :viewport/transit-stops (fn [db] (:viewport/transit-stops db)))
(rf/reg-sub :geolocation/loading (fn [db] (:geolocation/loading db)))
(rf/reg-sub :map/max-bounds (fn [db] (:map/max-bounds db)))
(rf/reg-sub :ui/info-open? (fn [db] (:ui/info-open? db)))

(rf/reg-fx
 :add-keyboard-listener
 (fn [_]
   (.addEventListener js/document "keydown"
     (fn [e]
       (when (= (.-key e) "Escape")
         (rf/dispatch [:map/clear-selection])
         (rf/dispatch [:ui/close-info-modal])))
     true)))

(rf/reg-event-fx
 :keyboard/add-listener
 (fn [_ _]
   {:add-keyboard-listener nil}))

(rf/reg-event-db
 :ui/close-info-modal
 (fn [db _]
   (assoc db :ui/info-open? false)))

(rf/reg-event-db
 :ui/toggle-info-modal
 (fn [db _]
   (update db :ui/info-open? not)))

;;--- Map Events ---

(rf/reg-event-fx
 :map/set-bounds
 (fn [{:keys [db]} [_ bounds]]
   (let [zoomed-in? (>= (:zoom bounds) 15)]
     (cond-> {:db (cond-> (assoc db :viewport/bounds bounds)
                    (not zoomed-in?) (assoc :viewport/transit-stops []))}
       zoomed-in? (assoc :dispatch [:api/fetch-transit-stops bounds])))))

(rf/reg-event-fx
 :map/click
 (fn [_ [_ lat lng]]
   {:dispatch [:api/fetch-click lat lng]}))

(rf/reg-event-db
 :map/clear-selection
 (fn [db _]
   (-> db
       (assoc :origin/intersection nil)
       (assoc :isochrone/data nil)
       (assoc :isochrone/compare-data nil))))

;;--- Isochrone Events ---


(rf/reg-event-fx
 :isochrone/set-mode
 (fn [{:keys [db]} [_ mode]]
   (if (= mode (:isochrone/mode db))
     {}
     (let [intersection (:origin/intersection db)
           dispatches (cond-> []
                        intersection (conj [:api/fetch-isochrone (:id intersection)]))]
       (cond-> {:db (-> db
                        (assoc :isochrone/mode mode)
                        (assoc :isochrone/data nil)
                        (assoc :isochrone/compare-data nil))}
         (seq dispatches) (assoc :dispatch-n dispatches))))))

;;--- Geolocation Events ---

(rf/reg-fx
 :geolocation/get-position
 (fn [_]
   (if (exists? js/navigator.geolocation)
     (.getCurrentPosition js/navigator.geolocation
       (fn [pos]
         (rf/dispatch [:geolocation/success
                       (.. pos -coords -latitude)
                       (.. pos -coords -longitude)]))
       (fn [err]
         (rf/dispatch [:geolocation/failure (.-message err)])))
     (rf/dispatch [:geolocation/failure "Geolocation not supported"]))))

(rf/reg-event-fx
 :geolocation/request
 (fn [{:keys [db]} _]
   {:db (assoc db :geolocation/loading true)
    :geolocation/get-position nil}))

(rf/reg-event-fx
 :geolocation/success
 (fn [{:keys [db]} [_ lat lng]]
   {:db (assoc db :geolocation/loading false)
    :dispatch [:map/click lat lng]
    :geolocation/pan-map {:lat lat :lng lng}}))

(rf/reg-event-db
 :geolocation/failure
 (fn [db [_ message]]
   (js/console.warn "Geolocation failed:" message)
   (assoc db :geolocation/loading false)))

(rf/reg-sub
 :isochrone/has-data
 (fn [db]
   (some? (:isochrone/data db))))

(rf/reg-sub
 :isochrone/geojson-features
 (fn [db]
   (let [mode (:isochrone/mode db)
         visible-bands (:isochrone/visible-bands db)]
     (if (= mode :compare)
       ;; Comparison mode: interleave transit (dark red) and bike (blue) from largest to smallest.
       ;; Within each band the mode painted second (higher :order) wins ties.
       ;; Small bands (15m, 30m): bike on top — rewards cycling for short trips.
       ;; Larger bands: transit on top — transit wins ties at longer distances.
       (let [transit-data (:isochrone/data db)
             bike-data (:isochrone/compare-data db)
             bands-descending (reverse config/band-keys)
             bike-wins? #{:15m :30m}]
         (when (or transit-data bike-data)
           (reduce
            (fn [acc [order-idx band]]
              (let [data-key (keyword (str "isochrone_" (name band)))
                    transit-geom (when transit-data (get transit-data data-key))
                    bike-geom (when bike-data (get bike-data data-key))
                    base-order (* order-idx 2)]
                (if (bike-wins? band)
                  ;; Small bands: transit first, bike on top
                  (cond-> acc
                    (and (contains? visible-bands band) transit-geom)
                    (conj {:band band :geometry transit-geom :color config/transit-color :order base-order})
                    (and (contains? visible-bands band) bike-geom)
                    (conj {:band band :geometry bike-geom :color config/bike-color :order (inc base-order)}))
                  ;; Larger bands: bike first, transit on top
                  (cond-> acc
                    (and (contains? visible-bands band) bike-geom)
                    (conj {:band band :geometry bike-geom :color config/bike-color :order base-order})
                    (and (contains? visible-bands band) transit-geom)
                    (conj {:band band :geometry transit-geom :color config/transit-color :order (inc base-order)})))))
            []
            (map-indexed vector bands-descending))))
       ;; Normal mode
       (when-let [data (:isochrone/data db)]
         (reduce
          (fn [acc band]
            (let [data-key (keyword (str "isochrone_" (name band)))
                  geometry (get data data-key)]
              (if (and (contains? visible-bands band) geometry)
                (conj acc {:band band :geometry geometry})
                acc)))
          []
          config/band-keys))))))
