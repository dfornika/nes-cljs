(ns nes.touchpad
  "An on-screen controller for touch screens: a D-pad, A and B, Select and
  Start, pressing player 1's buttons.

  Every finger on it is followed separately, so you can hold Right and
  press A at the same time. A finger that starts on the D-pad steers by
  its angle from the D-pad's centre (so the diagonals work, and it keeps
  working if a thumb drifts off the edge); one that starts on a button
  can slide to another (B to A) without lifting."
  (:require [clojure.set :as set]
            [reagent.core :as r]
            [nes.console :as console]
            [nes.state :refer [db]]))

(def ^:private storage-key "nes-cljs.touch-controls")

(defn- touch-device? []
  (and (exists? js/matchMedia) (.-matches (js/matchMedia "(pointer: coarse)"))))

(defn shown?
  "Whether to show the touch controls: as chosen, or by default on touch
  screens."
  []
  (let [choice (:touch-controls @db)]
    (if (some? choice) choice
        (let [saved (try (.getItem js/localStorage storage-key) (catch :default _ nil))]
          (if saved (= saved "true") (touch-device?))))))

(defn show!
  "Show (or hide) the touch controls, and remember the choice."
  [on?]
  (swap! db assoc :touch-controls on?)
  (try (.setItem js/localStorage storage-key (str on?)) (catch :default _ nil)))

;; ---------------------------------------------------------------------------
;; Which buttons each finger is on

;; pointer id -> {:buttons #{...}, :dpad the D-pad element if it started there}
(defonce ^:private fingers (atom {}))
;; the buttons pressed now (for drawing them pressed)
(defonce ^:private held (r/atom #{}))

(defn- dpad-directions
  "The directions a finger at (x, y) presses on D-pad element `el`: by
  angle from its centre, in 8 sectors, with a small dead zone."
  [^js el x y]
  (let [rect (.getBoundingClientRect el)
        dx (- x (+ (.-left rect) (/ (.-width rect) 2)))
        dy (- y (+ (.-top rect) (/ (.-height rect) 2)))]
    (if (< (js/Math.hypot dx dy) (* 0.15 (.-width rect)))
      #{}
      (let [sector (mod (js/Math.round (/ (js/Math.atan2 dy dx) (/ js/Math.PI 4))) 8)]
        ;; sector 0 is right, going clockwise (y points down)
        (case sector
          0 #{:right} 1 #{:right :down} 2 #{:down} 3 #{:down :left}
          4 #{:left} 5 #{:left :up} 6 #{:up} 7 #{:up :right})))))

(defn- buttons-at
  "The buttons under a finger at (x, y)."
  [x y]
  (let [^js el (js/document.elementFromPoint x y)]
    (if-let [^js dpad (some-> el (.closest "[data-dpad]"))]
      (dpad-directions dpad x y)
      (if-let [b (some-> el (.closest "[data-button]") (.getAttribute "data-button"))]
        #{(keyword b)}
        #{}))))

(defn- update-buttons! []
  (let [now (apply set/union #{} (map :buttons (vals @fingers)))
        before @held
        pressed (set/difference now before)]
    (doseq [b pressed] (console/button-down! 1 b))
    (doseq [b (set/difference before now)] (console/button-up! 1 b))
    ;; a short buzz for each new press, on phones that can
    (when (and (seq pressed) (exists? js/navigator.vibrate))
      (try (.vibrate js/navigator 12) (catch :default _ nil)))
    (reset! held now)))

(defn- finger-down! [^js e]
  (.preventDefault e)
  ;; keep getting this finger's moves even when it leaves the button
  (try (.setPointerCapture (.-currentTarget e) (.-pointerId e)) (catch :default _ nil))
  (let [x (.-clientX e) y (.-clientY e)
        dpad (some-> ^js (js/document.elementFromPoint x y) (.closest "[data-dpad]"))]
    (swap! fingers assoc (.-pointerId e)
           {:dpad dpad :buttons (if dpad (dpad-directions dpad x y) (buttons-at x y))}))
  (update-buttons!))

(defn- finger-move! [^js e]
  (when-let [{:keys [dpad]} (get @fingers (.-pointerId e))]
    (let [x (.-clientX e) y (.-clientY e)]
      (swap! fingers assoc-in [(.-pointerId e) :buttons]
             (if dpad (dpad-directions dpad x y) (buttons-at x y))))
    (update-buttons!)))

(defn- finger-up! [^js e]
  (swap! fingers dissoc (.-pointerId e))
  (update-buttons!))

(defn- release-all! []
  (reset! fingers {})
  (update-buttons!))

;; ---------------------------------------------------------------------------
;; Drawing it

(def ^:private handlers
  {:on-pointer-down finger-down!
   :on-pointer-move finger-move!
   :on-pointer-up finger-up!
   :on-pointer-cancel finger-up!
   :on-lost-pointer-capture finger-up!
   :on-context-menu #(.preventDefault %)})

(defn- dpad []
  (let [h @held
        arm (fn [dir style label]
              [:div {:class (str "tp-arm" (when (h dir) " tp-on")) :style style} label])]
    (into [:div (merge {:class "tp-dpad" :data-dpad true} handlers)]
          [[arm :up {:grid-area "up"} "▲"]
           [arm :left {:grid-area "left"} "◀"]
           [:div {:class "tp-arm tp-hub" :style {:grid-area "hub"}}]
           [arm :right {:grid-area "right"} "▶"]
           [arm :down {:grid-area "down"} "▼"]])))

(defn- button [k label class]
  [:div (merge {:class (str class (when (@held k) " tp-on")) :data-button (name k)} handlers)
   label])

(defn touchpad
  "The controller, when it's switched on. Where it goes depends on the
  layout (see style.css): under the screen, or over its sides in big
  screen mode in landscape."
  []
  (r/create-class
   {:display-name "touchpad"
    :component-will-unmount release-all!
    :reagent-render
    (fn []
      (when (shown?)
        [:<>
         [:div {:class "touchpad" :aria-label "Touch controller"}
          [dpad]
          [:div {:class "tp-middle"}
           [button :select "SELECT" "tp-pill"]
           [button :start "START" "tp-pill"]]
          [:div {:class "tp-ab"}
           [button :b "B" "tp-round"]
           [button :a "A" "tp-round tp-a"]]]
         [:div {:class "touch-hint"}
          "For the touch controller in landscape, use Big screen."]]))}))
