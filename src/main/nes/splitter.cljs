(ns nes.splitter
  "A vertical bar between two panels, side by side, that you drag left and
  right to give one more room and the other less.

  The bar sets the width of the panel on its left. That width lives in the
  app db under the key you give it (nil until it's been dragged, meaning
  'whatever the stylesheet says'), and is remembered between visits.
  Double-click the bar to go back to the stylesheet's width.")

(def ^:private storage-key "nes-cljs.split")

(defn saved-width
  "The width remembered from an earlier visit, or nil."
  []
  (try
    (let [n (js/parseInt (.getItem js/localStorage storage-key))]
      (when (pos? n) n))
    (catch :default _ nil)))

(defn- save! [px]
  (try
    (if px
      (.setItem js/localStorage storage-key (str px))
      (.removeItem js/localStorage storage-key))
    (catch :default _ nil)))

(def ^:private min-left
  "The narrowest the left panel gets, in CSS pixels."
  280)

(def ^:private min-right
  "...and the least room left for the panel on the right."
  340)

(defn- left-width [^js bar]
  (.. bar -previousElementSibling getBoundingClientRect -width))

(defn- limit
  "`px` kept within what fits in the bar's container."
  [^js bar px]
  (let [room (- (.. bar -parentElement -clientWidth) min-right)]
    (js/Math.round (-> px (min room) (max min-left)))))

(defn splitter
  "`db` is the app state atom and `k` the key for the left panel's width."
  [db k]
  (let [drag (atom nil)]   ; {:x :w} during a drag
    (fn [db k]
      (let [set-width! (fn [^js bar px]
                         (let [px (limit bar px)]
                           (swap! db assoc k px)
                           (save! px)))]
        [:div
         {:class "splitter"
          :role "separator"
          :aria-orientation "vertical"
          :aria-label "Resize the screen and the inspectors"
          :tab-index 0
          :title "Drag to resize (or use the arrow keys). Double-click to reset."
          :on-pointer-down (fn [^js e]
                             (.preventDefault e)
                             (.setPointerCapture (.-currentTarget e) (.-pointerId e))
                             (reset! drag {:x (.-clientX e) :w (left-width (.-currentTarget e))}))
          :on-pointer-move (fn [^js e]
                             (when-let [{:keys [x w]} @drag]
                               (set-width! (.-currentTarget e) (+ w (- (.-clientX e) x)))))
          :on-pointer-up #(reset! drag nil)
          :on-pointer-cancel #(reset! drag nil)
          :on-double-click (fn [_]
                             (swap! db assoc k nil)
                             (save! nil))
          :on-key-down (fn [^js e]
                         (when-let [dx ({"ArrowLeft" -24 "ArrowRight" 24} (.-key e))]
                           (.preventDefault e)
                           (set-width! (.-currentTarget e) (+ (left-width (.-currentTarget e)) dx))))}
         [:span {:class "splitter-grip"}]]))))
