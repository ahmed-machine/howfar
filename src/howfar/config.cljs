(ns howfar.config)

;; Canonical band definitions — single source of truth for colors, order, labels
(def bands
  [{:key :15m  :color "#0891b2" :label "15"}
   {:key :30m  :color "#10b981" :label "30"}
   {:key :45m  :color "#65a30d" :label "45"}
   {:key :60m  :color "#fde047" :label "60"}
   {:key :90m  :color "#f97316" :label "90"}
   {:key :120m :color "#ef4444" :label "120"}
   {:key :150m :color "#be185d" :label "150"}
   {:key :180m :color "#881337" :label "180"}])

(def band-colors (into {} (map (juxt :key :color) bands)))
(def band-order  (into {} (map-indexed (fn [i b] [(:key b) (inc i)]) bands)))
(def band-keys   (mapv :key bands))

;; Compare mode colors
(def transit-color "#991b1b")
(def bike-color "#2563eb")
