(ns nes.drawer
  "A panel that slides up over the bottom of its container. Drag the bar
  at its top to resize it, or click the bar to open or close it.

  Its height lives in the app db under the key you give it (nil when
  closed), so it survives hot reloads. While closed, its contents aren't
  rendered at all, so views that cost something to update (like the
  schematic's bus monitor) don't slow the emulator down.")

(def handle-height
  "How tall the bar is, in CSS pixels: all that shows while closed."
  30)

(defn- container-height [^js handle]
  (.. handle -parentElement -parentElement -clientHeight))

(defn drawer
  "`db` is the app state atom and `k` the key for the drawer's height.
  `label` names what's inside. `content` is a Reagent component (vector)
  rendered while open."
  [db k label content]
  (let [drag (atom nil)            ; {:y :h :max :moved?} during a drag
        just-dragged? (atom false)]
    (fn [db k label content]
      (let [h (get @db k)
            open? (some? h)
            set-height! (fn [px limit]
                          (swap! db assoc k (-> px (max handle-height) (min limit))))
            toggle! (fn [^js handle]
                      (swap! db assoc k (when-not open?
                                          (js/Math.round (* 0.55 (container-height handle))))))]
        [:div {:class "drawer"
               :style {:height (if open? h handle-height)}}
         [:button
          {:class "drawer-handle"
           :aria-expanded open?
           :title (if open? "Drag to resize, click to close" "Drag up or click to open")
           :on-pointer-down (fn [^js e]
                              (.setPointerCapture (.-currentTarget e) (.-pointerId e))
                              (reset! drag {:y (.-clientY e)
                                            :h (or h handle-height)
                                            :max (- (container-height (.-currentTarget e)) 8)
                                            :moved? false}))
           :on-pointer-move (fn [^js e]
                              (when-let [{:keys [y moved?] :as d} @drag]
                                (let [dy (- y (.-clientY e))]
                                  ;; a few pixels of wobble is still a click
                                  (when (or moved? (> (abs dy) 4))
                                    (swap! drag assoc :moved? true)
                                    (set-height! (+ (:h d) dy) (:max d))))))
           :on-pointer-up (fn [_]
                            (when-let [{:keys [moved?]} @drag]
                              (reset! drag nil)
                              (reset! just-dragged? moved?)
                              ;; dragged nearly shut: close it
                              (when (and moved? (< (get @db k) (+ handle-height 40)))
                                (swap! db assoc k nil))))
           ;; also how the keyboard (Enter, Space) opens and closes it
           :on-click (fn [^js e]
                       (if @just-dragged?
                         (reset! just-dragged? false)
                         (toggle! (.-currentTarget e))))}
          [:span {:class "drawer-grip"}]
          [:span label " " (if open? "▾" "▴")]]
         (when open?
           [:div {:class "drawer-body"} content])]))))
