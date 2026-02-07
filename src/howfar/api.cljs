(ns howfar.api
  (:require [ajax.core :as ajax]
            [re-frame.core :as rf]))

(def api-base "")

(defn api-url [path]
  (str api-base path))

;; Generic API request effect
(rf/reg-fx
 :http-request
 (fn [{:keys [method url params on-success on-failure]}]
   (ajax/ajax-request
    {:uri url
     :method (or method :get)
     :params params
     :format (ajax/json-request-format)
     :response-format (ajax/json-response-format {:keywords? true})
     :handler (fn [[ok response]]
                (if ok
                  (rf/dispatch (conj on-success response))
                  (rf/dispatch (conj on-failure response))))})))

(defn- store-isochrone
  "Unpack isochrone response into db, handling both normal and compare modes."
  [db iso]
  (let [compare? (and (map? iso) (:transit iso) (:bike iso))]
    (-> db
        (assoc :isochrone/data (if compare? (:transit iso) iso))
        (assoc :isochrone/compare-data (when compare? (:bike iso)))
        (assoc :isochrone/loading false))))

;; Combined click endpoint - fetches nearest intersection + isochrone in one request
(rf/reg-event-fx
 :api/fetch-click
 (fn [{:keys [db]} [_ lat lng]]
   (let [mode (or (:isochrone/mode db) :transit)]
     {:db (assoc db :isochrone/loading true)
      :http-request
      {:url (api-url "/api/click")
       :params {:lat lat :lng lng :mode (name mode)}
       :on-success [:api/click-loaded]
       :on-failure [:api/request-failed :click]}})))

(rf/reg-event-db
 :api/click-loaded
 (fn [db [_ response]]
   (-> db
       (assoc :origin/intersection (:intersection response))
       (store-isochrone (:isochrone response)))))

;; Fetch isochrone for intersection
(rf/reg-event-fx
 :api/fetch-isochrone
 (fn [{:keys [db]} [_ intersection-id]]
   (let [mode (or (:isochrone/mode db) :transit)]
     {:db (assoc db :isochrone/loading true)
      :http-request
      {:url (api-url (str "/api/isochrone/" intersection-id))
       :params {:mode (name mode)}
       :on-success [:api/isochrone-loaded]
       :on-failure [:api/request-failed :isochrone]}})))

(rf/reg-event-db
 :api/isochrone-loaded
 (fn [db [_ response]]
   (store-isochrone db (:isochrone response))))

;; Fetch transit stops in viewport
(rf/reg-event-fx
 :api/fetch-transit-stops
 (fn [_ [_ bounds]]
   {:http-request
    {:url (api-url "/api/transit/stops/viewport")
     :params {:minLat (:south bounds)
              :maxLat (:north bounds)
              :minLng (:west bounds)
              :maxLng (:east bounds)}
     :on-success [:api/transit-stops-loaded]
     :on-failure [:api/request-failed :transit-stops]}}))

(rf/reg-event-db
 :api/transit-stops-loaded
 (fn [db [_ response]]
   (assoc db :viewport/transit-stops (:stops response))))

;; Fetch isochrone bounding box
(rf/reg-event-fx
 :api/fetch-bbox
 (fn [_ _]
   {:http-request
    {:url (api-url "/api/bbox")
     :on-success [:api/bbox-loaded]
     :on-failure [:api/request-failed :bbox]}}))

(rf/reg-event-fx
 :api/bbox-loaded
 (fn [{:keys [db]} [_ response]]
   {:db (assoc db :map/max-bounds (:bbox response))
    :map/set-max-bounds (:bbox response)}))

;; Error handling
(rf/reg-event-db
 :api/request-failed
 (fn [db [_ request-type error]]
   (let [error-msg (cond
                     (string? error) error
                     (map? error) (or (:status-text error)
                                      (:message error)
                                      (pr-str error))
                     :else (pr-str error))]
     (js/console.error (str "API request failed: " (name request-type) " - " error-msg))
     (cond-> (-> db
                 (assoc :isochrone/loading false)
                 (assoc-in [:errors request-type] error))
       (#{:isochrone :click} request-type) (-> (assoc :isochrone/data nil)
                                              (assoc :isochrone/compare-data nil))))))
