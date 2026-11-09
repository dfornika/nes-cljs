(ns nes.tiles-view
  "The CHR / tile viewer: the two pattern tables the PPU currently sees,
  and a close-up of one tile showing how its 16 bytes become pixels."
  (:require [reagent.core :as r]
            [nes.canvas :refer [pixel-canvas]]
            [nes.chr :as chr]
            [nes.console :as console]
            [nes.emulator :as emu]))

(def ^:private scale 2)

;; Which palette to color tiles with, and which tile is under the mouse /
;; selected. `defonce` so it survives hot reloads.
(defonce ^:private view (r/atom {:palette :gray
                                 :selected {:table 0 :tile 0}
                                 :hover nil}))

(defn- hex [n width]
  (str "$" (.padStart (.toUpperCase (.toString n 16)) width "0")))

(defn- bin [n]
  (.padStart (.toString n 2) 8 "0"))

(defn- colors-for
  "The 4 colors (0x00BBGGRR) to draw tiles with for palette choice `p`."
  [emulator p]
  (if (= p :gray)
    chr/grayscale
    (let [master (emu/master-palette emulator)]
      (mapv #(aget master %) (chr/palette-colors (emu/ppu-memory emulator) p)))))

;; ---------------------------------------------------------------------------
;; Palette picker

(defn- swatches [colors]
  [:span {:style {:display "inline-flex" :border "1px solid #8c959f"}}
   (for [[i c] (map-indexed vector colors)]
     ^{:key i} [:span {:style {:width "12px" :height "12px" :background (chr/bgr->css c)}}])])

(defn- palette-picker [emulator]
  (let [current (:palette @view)
        option (fn [p label]
                 [:label {:key (str p)
                          :style {:display "inline-flex" :align-items "center" :gap "4px"
                                  :padding "2px 6px" :border-radius "4px" :cursor "pointer"
                                  :background (when (= p current) "#ddf4ff")}}
                  [:input {:type "radio" :name "chr-palette" :checked (= p current)
                           :on-change #(swap! view assoc :palette p)}]
                  label [swatches (colors-for emulator p)]])]
    [:div {:style {:display "flex" :flex-wrap "wrap" :gap "4px" :align-items "center"}}
     [:span {:style {:margin-right "4px"}} "Colors:"]
     (option :gray "Gray")
     (for [p (range 4)] (option p (str "BG " p)))
     (for [p (range 4 8)] (option p (str "Sprite " (- p 4))))]))

;; ---------------------------------------------------------------------------
;; Pattern tables

(defn- tile-under-mouse [^js e]
  (let [x (quot (.. e -nativeEvent -offsetX) (* 8 scale))
        y (quot (.. e -nativeEvent -offsetY) (* 8 scale))]
    (when (and (< -1 x 16) (< -1 y 16))
      (+ x (* 16 y)))))

(defn- outline [tile color]
  [:div {:style {:position "absolute" :pointer-events "none"
                 :left (* scale 8 (mod tile 16)) :top (* scale 8 (quot tile 16))
                 :width (* scale 8) :height (* scale 8)
                 :box-sizing "border-box"
                 :outline (str "2px solid " color)}}])

(defn- pattern-table [emulator table tick]
  (let [{:keys [palette selected hover]} @view
        colors (colors-for emulator palette)]
    [:div
     [:div {:style {:margin-bottom "4px"}}
      [:strong "Pattern table " table] " "
      [:small {:style {:color "#57606a"}}
       (hex (* table 0x1000) 4) "–" (hex (+ (* table 0x1000) 0xFFF) 4)]]
     [:div {:style {:position "relative" :width (* 128 scale)
                    :cursor "crosshair" :background "black"}}
      [pixel-canvas {:width 128 :height 128 :scale scale
                     :tick [tick palette]
                     :paint #(chr/draw-pattern-table! % (emu/ppu-memory emulator) table colors)
                     :on-mouse-move #(when-let [t (tile-under-mouse %)]
                                       (swap! view assoc :hover {:table table :tile t}))
                     :on-mouse-leave #(swap! view assoc :hover nil)
                     :on-click #(when-let [t (tile-under-mouse %)]
                                  (swap! view assoc :selected {:table table :tile t}))}]
      (when (= table (:table hover)) [outline (:tile hover) "#ff8182"])
      (when (= table (:table selected)) [outline (:tile selected) "#54aeff"])]]))

;; ---------------------------------------------------------------------------
;; Tile close-up

(def ^:private cell 16)

(defn- bit-grid
  "8x8 grid of numbers, each cell drawn with `cell-color`."
  [rows cell-color text-color]
  [:div {:style {:display "grid"
                 :grid-template-columns (str "repeat(8, " cell "px)")
                 :border "1px solid #8c959f"
                 :font-family "ui-monospace, Menlo, Consolas, monospace"
                 :font-size "10px"}}
   (for [[y row] (map-indexed vector rows)
         [x v] (map-indexed vector row)]
     ^{:key (+ x (* 8 y))}
     [:div {:style {:width cell :height cell :line-height (str cell "px")
                    :text-align "center"
                    :background (cell-color v)
                    :color (text-color v)}}
      v])])

(defn- grid-with-caption [caption grid]
  [:div [:div {:style {:font-size "12px" :margin-bottom "4px"}} caption] grid])

(def ^:private op-style {:font-size "20px" :align-self "center" :padding "0 6px" :margin-top "16px"})

(defn- tile-detail [emulator _tick]
  (let [{:keys [palette selected hover]} @view
        {:keys [table tile]} (or hover selected)
        mem (emu/ppu-memory emulator)
        addr (chr/tile-address table tile)
        {:keys [bytes plane0 plane1]} (chr/tile-planes mem addr)
        pixels (chr/tile-pixels mem addr)
        colors (colors-for emulator palette)
        bit-bg #(if (= 1 %) "#24292f" "white")
        bit-fg #(if (= 1 %) "white" "#8c959f")
        mono {:font-family "ui-monospace, Menlo, Consolas, monospace" :font-size "12px"}]
    [:div {:style {:margin-top "12px"}}
     [:div {:style {:margin-bottom "8px"}}
      [:strong "Tile " (hex tile 2)] " in pattern table " table
      " · PPU address " [:code (hex addr 4) "–" (hex (+ addr 15) 4)]
      (when hover [:small {:style {:color "#57606a"}} " (hovering - click to pin)"])]
     [:div {:style {:display "flex" :flex-wrap "wrap" :align-items "flex-start"}}
      [grid-with-caption "Plane 0 (low bit)" [bit-grid plane0 bit-bg bit-fg]]
      [:div {:style op-style} "+"]
      [grid-with-caption "Plane 1 (high bit) × 2" [bit-grid (mapv #(mapv (partial * 2) %) plane1)
                                                  #(if (pos? %) "#24292f" "white")
                                                  #(if (pos? %) "white" "#8c959f")]]
      [:div {:style op-style} "="]
      [grid-with-caption "Color number"
       [bit-grid pixels
        #(chr/bgr->css (nth colors %))
        #(if (chr/light? (nth colors %)) "black" "white")]]]
     [:table {:style (merge mono {:margin-top "12px" :border-collapse "collapse"})}
      [:thead
       [:tr {:style {:text-align "left" :color "#57606a"}}
        [:th "Row"] [:th {:style {:padding-left "12px"}} "Plane 0 byte"]
        [:th {:style {:padding-left "12px"}} "Plane 1 byte"]]]
      [:tbody
       (for [y (range 8)]
         ^{:key y}
         [:tr
          [:td y]
          [:td {:style {:padding-left "12px"}}
           (hex (+ addr y) 4) ": " (hex (nth bytes y) 2) " = " (bin (nth bytes y))]
          [:td {:style {:padding-left "12px"}}
           (hex (+ addr y 8) 4) ": " (hex (nth bytes (+ y 8)) 2) " = " (bin (nth bytes (+ y 8)))]])]]]))

;; ---------------------------------------------------------------------------

(defn tiles-view []
  (let [tick @console/inspect-tick
        emulator (console/emulator-instance)]
    (if-not emulator
      [:p {:style {:color "#57606a"}} "Load a ROM to see its tiles."]
      [:div
       [:p {:style {:margin-top 0 :color "#57606a" :font-size "14px"}}
        "Every graphic on the NES is built from 8×8 tiles like these. "
        "This is what the PPU can see right now - games with bank switching "
        "or CHR RAM change it as they run. Hover or click a tile to see how it's stored."]
       [palette-picker emulator]
       [:div {:style {:display "flex" :gap "16px" :flex-wrap "wrap" :margin-top "8px"}}
        [pattern-table emulator 0 tick]
        [pattern-table emulator 1 tick]]
       [tile-detail emulator tick]])))
