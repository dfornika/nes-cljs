(ns nes.sprites-view
  "The sprite (OAM) viewer: where all 64 sprites are on screen, what each
  one looks like, and what its 4 bytes of OAM say."
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [nes.canvas :refer [pixel-canvas]]
            [nes.chr :as chr]
            [nes.console :as console]
            [nes.cpu.disasm :refer [hex]]
            [nes.emulator :as emu]
            [nes.screen :as screen]
            [nes.sprites :as sprites]))

(def ^:private muted {:color "#57606a"})
(def ^:private mono {:font-family "ui-monospace, Menlo, Consolas, monospace"})

(defonce ^:private view (r/atom {:hover nil :selected 0 :backdrop? true}))

(defn- sprite-colors [emulator palette]
  (let [master (emu/master-palette emulator)]
    (mapv #(aget master %) (chr/palette-colors (emu/ppu-memory emulator) palette))))

;; ---------------------------------------------------------------------------
;; Everything on one screen

(defn- paint-overview! [^js pixels emulator {:keys [sprite-height sprite-table]} all backdrop?]
  (let [frame (screen/frame-pixels)
        mem (emu/ppu-memory emulator)]
    ;; Background: the screen, dimmed, or a checkerboard
    (dotimes [i (* 256 240)]
      (aset pixels i
            (if (and backdrop? frame)
              (bit-or 0xFF000000 (bit-and (unsigned-bit-shift-right (aget frame i) 2) 0x3F3F3F))
              (let [x (mod i 256) y (quot i 256)]
                (if (even? (+ (quot x 8) (quot y 8))) 0xFF303030 0xFF262626)))))
    ;; Draw from last to first, so lower-numbered sprites end up on top,
    ;; as on the real PPU
    (doseq [s (rseq all)
            :when (not (:hidden? s))]
      (sprites/draw-sprite! pixels 256 240 mem s sprite-height sprite-table
                            (sprite-colors emulator (:palette s))
                            (:x s) (inc (:y s))))))

(def ^:private scale 2)

(defn- box [{:keys [x y]} height color]
  [:div {:style {:position "absolute" :pointer-events "none"
                 :left (* scale x) :top (* scale (inc y))
                 :width (* scale 8) :height (* scale height)
                 :box-sizing "border-box"
                 :outline (str "2px solid " color)}}])

(defn- overview [emulator flags all tick]
  (let [{:keys [hover selected backdrop?]} @view
        height (:sprite-height flags)
        sprite-at (fn [^js e]
                    (let [x (/ (.. e -nativeEvent -offsetX) scale)
                          y (/ (.. e -nativeEvent -offsetY) scale)]
                      ;; the first (topmost) visible sprite under the mouse
                      (some #(when (and (not (:hidden? %))
                                        (<= (:x %) x (+ (:x %) 7))
                                        (<= (inc (:y %)) y (+ (:y %) height)))
                               (:index %))
                            all)))]
    [:div
     [:label {:style {:font-size "13px"}}
      [:input {:type "checkbox" :checked backdrop?
               :on-change #(swap! view update :backdrop? not)}]
      " Show the screen behind the sprites"]
     [:div {:style {:position "relative" :width (* 256 scale) :margin-top "6px"}}
      [pixel-canvas {:width 256 :height 240 :scale scale
                     :tick [tick backdrop?]
                     :paint #(paint-overview! % emulator flags all backdrop?)
                     :on-mouse-move #(swap! view assoc :hover (sprite-at %))
                     :on-mouse-leave #(swap! view assoc :hover nil)
                     :on-click #(when-let [i (sprite-at %)] (swap! view assoc :selected i))}]
      (when-let [s (and hover (nth all hover))] [box s height "#ff8182"])
      (when-let [s (and selected (nth all selected))]
        (when-not (:hidden? s) [box s height "#54aeff"]))]]))

;; ---------------------------------------------------------------------------
;; The 64 sprites

(defn- sprite-cell [emulator flags s tick hovered? selected?]
  (let [height (:sprite-height flags)]
    [:div {:on-mouse-enter #(swap! view assoc :hover (:index s))
           :on-mouse-leave #(swap! view assoc :hover nil)
           :on-click #(swap! view assoc :selected (:index s))
           :title (str "Sprite " (:index s) (when (:hidden? s) " (hidden)"))
           :style {:display "flex" :flex-direction "column" :align-items "center"
                    :padding "3px" :cursor "pointer" :border-radius "4px"
                    :opacity (if (:hidden? s) 0.35 1)
                    :background (cond selected? "#ddf4ff" hovered? "#ffebe9")}}
     [pixel-canvas {:width 8 :height height :scale 3
                    :tick [tick (:attr s) (:tile s) height]
                    :style {:background "#d0d7de"}
                    :paint (fn [^js px]
                             (.fill px 0)
                             (sprites/draw-sprite! px 8 height (emu/ppu-memory emulator) s height
                                                   (:sprite-table flags)
                                                   (sprite-colors emulator (:palette s)) 0 0))}]
     [:div {:style (merge mono muted {:font-size "10px"})} (:index s)]]))

(defn- sprite-grid [emulator flags all tick]
  (let [{:keys [hover selected]} @view]
    [:div {:style {:display "grid" :grid-template-columns "repeat(16, auto)"
                   :gap "2px" :justify-content "start"}}
     (for [s all]
       ^{:key (:index s)}
       [sprite-cell emulator flags s tick (= hover (:index s)) (= selected (:index s))])]))

;; ---------------------------------------------------------------------------
;; One sprite's bytes

(defn- bits [v] (.padStart (.toString v 2) 8 "0"))

(defn- sprite-detail [{:keys [sprite-height sprite-table]} all]
  (let [{:keys [hover selected]} @view
        i (or hover selected)]
    (when i
      (let [{:keys [index x y tile attr palette behind-bg? flip-h? flip-v? hidden?] :as s} (nth all i)
            addrs (sprites/tile-addresses s sprite-height sprite-table)
            row (fn [n label value meaning]
                  [:tr
                   [:td {:style (merge muted mono {:padding-right "8px"})} (hex (+ (* 4 index) n) 2)]
                   [:td {:style {:padding-right "8px"}} label]
                   [:td {:style (merge mono {:padding-right "12px"})} value]
                   [:td {:style muted} meaning]])]
        [:div {:style {:margin-top "12px"}}
         [:div [:strong "Sprite " index] (when hover [:small {:style muted} " (hovering - click to pin)"])]
         [:table {:style {:font-size "13px" :margin-top "4px" :border-collapse "collapse"}}
          [:tbody
           (row 0 "Y" (hex y 2)
                (if hidden?
                  "≥ $EF: below the bottom of the screen, so hidden"
                  (str "top edge on scanline " (inc y))))
           (row 1 "Tile" (hex tile 2)
                (str "drawn from PPU " (str/join " + " (map #(hex % 4) addrs))
                     (when (= sprite-height 16) " (8×16: tile bit 0 picks the pattern table)")))
           (row 2 "Attributes" (str "%" (bits attr))
                (str "palette " (- palette 4) " (sprite palette at " (hex (+ 0x3F00 (* 4 palette)) 4) ")"
                     (when behind-bg? ", behind background")
                     (when flip-h? ", flipped horizontally")
                     (when flip-v? ", flipped vertically")))
           (row 3 "X" (hex x 2) (str "left edge at pixel " x))]]]))))

;; ---------------------------------------------------------------------------

(defn sprites-view []
  (let [tick @console/inspect-tick]
    (if-let [emulator (console/emulator-instance)]
      (let [flags (emu/ppu-flags emulator)
            all (sprites/sprites (emu/oam emulator))
            visible (count (remove :hidden? all))]
        [:div
         [:p {:style (merge muted {:margin-top 0 :font-size "14px"})}
          "Sprites are the things that move: characters, bullets, cursors. The PPU's sprite memory (OAM) "
          "holds 64 of them, 4 bytes each. Hover or click one to see its bytes."]
         [:div {:style (merge muted {:font-size "13px" :margin-bottom "6px"})}
          visible " of 64 sprites on screen · size 8×" (:sprite-height flags)
          (when (= 8 (:sprite-height flags))
            (str " · tiles from pattern table " (:sprite-table flags)))
          (when-not (:show-sprites? flags) " · sprites are switched off (PPUMASK)")]
         [overview emulator flags all tick]
         [:div {:style {:margin-top "12px"}} [sprite-grid emulator flags all tick]]
         [sprite-detail flags all]])
      [:p {:style muted} "Load a ROM to see its sprites."])))
