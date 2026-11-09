(ns nes.background-view
  "The background (nametable) viewer: all four nametables, the part the
  screen is showing, and how any one tile is put together."
  (:require [reagent.core :as r]
            [nes.canvas :refer [pixel-canvas]]
            [nes.chr :as chr]
            [nes.console :as console]
            [nes.cpu.disasm :refer [hex]]
            [nes.emulator :as emu]
            [nes.nametable :as nt]
            [nes.state :refer [db]]
            [nes.text-table :as text-table]))

(def ^:private muted {:color "#57606a"})
(def ^:private mono {:font-family "ui-monospace, Menlo, Consolas, monospace"})

(defonce ^:private view (r/atom {:camera? true :attributes? false :grid? false
                                 :hover nil :selected nil}))

(defn- bg-palettes
  "The four background palettes as vectors of 0x00BBGGRR colors."
  [e]
  (let [master (emu/master-palette e)
        mem (emu/ppu-memory e)]
    (mapv (fn [p] (mapv #(aget master %) (chr/palette-colors mem p))) (range 4))))

(def ^:private mirroring-notes
  {:vertical "Vertical mirroring: $2000 and $2800 are the same memory, and so are $2400 and $2C00. Room for two screens side by side, for scrolling sideways."
   :horizontal "Horizontal mirroring: $2000 and $2400 are the same memory, and so are $2800 and $2C00. Room for two screens stacked, for scrolling up and down."
   :single-screen "Single-screen: all four nametables are the same memory."
   :four-screen "Four-screen: the cartridge adds RAM so all four nametables are separate."})

;; ---------------------------------------------------------------------------
;; The overlay drawn on top of the nametables

(defn- overlay [{:keys [camera? attributes? grid?]} scroll hover selected]
  [:svg {:viewBox "0 0 512 480" :width 512 :height 480
         :style {:position "absolute" :left 0 :top 0 :pointer-events "none"}}
   (when grid?
     [:g {:stroke "rgba(255,255,255,0.18)" :stroke-width 1}
      (for [x (range 8 512 8)] ^{:key x} [:line {:x1 (+ x 0.5) :y1 0 :x2 (+ x 0.5) :y2 480}])
      (for [y (range 8 480 8)] ^{:key y} [:line {:x1 0 :y1 (+ y 0.5) :x2 512 :y2 (+ y 0.5)}])])
   (when attributes?
     [:g {:stroke "rgba(255,216,61,0.6)" :stroke-width 1}
      (for [x (range 16 512 16)] ^{:key x} [:line {:x1 (+ x 0.5) :y1 0 :x2 (+ x 0.5) :y2 480}])
      (for [y (range 16 480 16)] ^{:key y} [:line {:x1 0 :y1 (+ y 0.5) :x2 512 :y2 (+ y 0.5)}])])
   ;; the four nametables
   [:g {:stroke "rgba(255,255,255,0.7)" :stroke-width 2 :fill "none"}
    [:line {:x1 256 :y1 0 :x2 256 :y2 480}]
    [:line {:x1 0 :y1 240 :x2 512 :y2 240}]]
   (for [[i base] (map-indexed vector nt/addresses)]
     ^{:key i}
     [:text {:x (+ 4 (* 256 (mod i 2))) :y (+ 14 (* 240 (quot i 2)))
             :fill "white" :font-size 12 :font-family "ui-monospace, monospace"
             :stroke "black" :stroke-width 3 :paint-order "stroke"}
      (hex base 4)])
   (when camera?
     (for [[i {:keys [x y w h]}] (map-indexed vector (nt/camera-rects (:x scroll) (:y scroll)))]
       ^{:key i}
       [:rect {:x (+ x 1) :y (+ y 1) :width (- w 2) :height (- h 2)
               :fill "rgba(255,129,130,0.08)" :stroke "#ff8182" :stroke-width 2
               :stroke-dasharray "6 3"}]))
   (for [[t color] [[selected "#54aeff"] [hover "#fd8c73"]]
         :when t]
     ^{:key color}
     [:rect {:x (+ (* 256 (mod (:nametable t) 2)) (* 8 (:col t)))
             :y (+ (* 240 (quot (:nametable t) 2)) (* 8 (:row t)))
             :width 8 :height 8 :fill "none" :stroke color :stroke-width 2}])])

;; ---------------------------------------------------------------------------
;; Details of one tile

(defn- swatches [colors]
  [:span {:style {:display "inline-flex" :border "1px solid #8c959f" :vertical-align "middle"}}
   (for [[i c] (map-indexed vector colors)]
     ^{:key i} [:span {:style {:width "14px" :height "14px" :background (chr/bgr->css c)}}])])

(defn- tile-detail [e flags palettes tick]
  (let [{:keys [hover selected]} @view
        t (or hover selected)]
    (if-not t
      [:div {:style (merge muted {:font-size "13px"})}
       "Hover over a tile to see where it comes from; click to pin it."]
      (let [{:keys [nametable col row address tile pattern-address attribute-address
                    attribute palette]} (nt/tile-info #(emu/ppu-peek e %) (:nametable t) (:col t) (:row t)
                                                      (:bg-table flags))
            colors (nth palettes palette)
            mem (emu/ppu-memory e)
            row-fn (fn [label & value] [:tr [:td {:style (merge muted {:padding-right "10px"})} label]
                                        (into [:td] value)])]
        [:div {:style {:display "flex" :gap "16px" :align-items "flex-start" :flex-wrap "wrap"}}
         [pixel-canvas {:width 8 :height 8 :scale 8
                        :tick [tick address palette tile]
                        :style {:border "1px solid #8c959f"}
                        :paint (fn [^js px]
                                 (dotimes [y 8]
                                   (dotimes [x 8]
                                     (aset px (+ x (* 8 y))
                                           (bit-or 0xFF000000 (nth colors (chr/pixel mem pattern-address x y)))))))}]
         [:table {:style {:font-size "13px" :border-collapse "collapse"}}
          [:tbody
           (row-fn "Position" "nametable " nametable " (" (hex (nth nt/addresses nametable) 4) "), column " col ", row " row)
           (row-fn "Tile number" [:span {:style mono} (hex tile 2)] " at " [:span {:style mono} (hex address 4)])
           (row-fn "Pattern" "tile " (hex tile 2) " of pattern table " (:bg-table flags)
                   ", at " [:span {:style mono} (hex pattern-address 4)])
           (row-fn "Attribute" [:span {:style mono} (hex attribute 2) " = %" (.padStart (.toString attribute 2) 8 "0")]
                   " at " [:span {:style mono} (hex attribute-address 4)])
           (row-fn "Palette" "background palette " palette " " [swatches colors]
                   [:span {:style muted} " (bits " (nt/attribute-shift col row) "–"
                    (inc (nt/attribute-shift col row)) " of the attribute byte)"])]]]))))

;; ---------------------------------------------------------------------------

(defn- tile-at [^js ev]
  (let [x (.. ev -nativeEvent -offsetX)
        y (.. ev -nativeEvent -offsetY)]
    (when (and (< -1 x 512) (< -1 y 480))
      (let [n (+ (if (>= x 256) 1 0) (if (>= y 240) 2 0))]
        {:nametable n
         :col (quot (mod x 256) 8)
         :row (min 29 (quot (mod y 240) 8))}))))

(defn- checkbox [k label]
  [:label {:style {:font-size "13px" :margin-right "12px" :white-space "nowrap"}}
   [:input {:type "checkbox" :checked (boolean (k @view)) :on-change #(swap! view update k not)}]
   " " label])

(defn- tbl-file-button []
  (let [input (atom nil)]
    (fn []
      [:<>
       [:button {:on-click #(some-> ^js @input (.click))
                 :title "A ROM hacker's table file: lines like C1=A, saying which tile is which letter"}
        "Load a .tbl file…"]
       [:input {:type "file" :accept ".tbl,.txt" :style {:display "none"}
                :ref #(reset! input %)
                :on-change (fn [^js ev]
                             (when-let [f (aget (.. ev -target -files) 0)]
                               (-> (.text f)
                                   (.then (fn [text]
                                            (let [table (text-table/parse text)]
                                              (swap! db assoc-in [:text-tables (.-name f)] table)
                                              (swap! db assoc :text-table (.-name f)))))))
                             (set! (.. ev -target -value) ""))}]])))

(defn- screen-text
  "The visible screen read as text, through a text table (see
  nes.text-table): letters are just tiles, so with a table saying which
  tile is which letter, the nametables are text."
  [e scroll]
  (let [registered (text-table/tables-for (:rom @db))
        loaded (for [[n t] (:text-tables @db)] {:name n :table t})
        tables (concat registered loaded)
        chosen (or (some #(when (= (:text-table @db) (:name %)) %) tables) (first tables))]
    [:div {:style {:margin-top "14px"}}
     [:h4 {:style {:margin "0 0 6px"}} "Screen as text"]
     [:div {:style {:display "flex" :gap "8px" :align-items "center" :flex-wrap "wrap" :font-size "13px"}}
      (when (seq tables)
        [:select {:value (or (:name chosen) "")
                  :on-change #(swap! db assoc :text-table (.. % -target -value))}
         (for [{:keys [name]} tables] ^{:key name} [:option {:value name} name])])
      [tbl-file-button]]
     (if chosen
       [:pre {:style (merge mono {:background "#0d1117" :color "#e6edf3" :padding "8px 10px"
                                  :font-size "12px" :line-height "1.25" :width "fit-content"
                                  :border-radius "6px" :margin "8px 0 0"})}
        (->> (text-table/visible-lines (:table chosen) #(emu/ppu-peek e %) (:x scroll) (:y scroll))
             (interpose "\n")
             (apply str))]
       [:p {:style (merge muted {:font-size "13px"})}
        "Games don't store letters as ASCII: a letter is whichever background tile has its picture. "
        "A text table (.tbl file) says which tile is which letter; ROM hacking sites like "
        [:a {:href "https://datacrystal.tcrf.net/" :target "_blank"} "Data Crystal"]
        " have them for many games. Load one to read the screen as text."])]))

(defn background-view []
  (let [tick @console/inspect-tick]
    (if-let [e (console/emulator-instance)]
      (let [flags (emu/ppu-flags e)
            palettes (bg-palettes e)
            {:keys [scroll]} (emu/ppu-state e)
            mirroring (emu/mirroring e)
            {:keys [hover selected] :as v} @view]
        [:div
         [:p {:style (merge muted {:margin-top 0 :font-size "14px"})}
          "The background is made of nametables: grids of tile numbers, 32 × 30 to a screen. "
          "There's room for four, in a 2 × 2 square the screen scrolls around in. "
          "The dashed rectangle is what's on screen."]
         [:div {:style (merge muted {:font-size "13px" :margin-bottom "6px"})}
          (get mirroring-notes mirroring (str "Mirroring: " (name mirroring)))
          " · Scroll " [:span {:style mono} "x " (:x scroll) ", y " (:y scroll)]
          " · Tiles from pattern table " (:bg-table flags)
          (when-not (:show-bg? flags) " · background switched off (PPUMASK)")]
         [:div {:style {:margin-bottom "6px"}}
          [checkbox :camera? "Screen"]
          [checkbox :attributes? "Attribute areas (16×16)"]
          [checkbox :grid? "Tile grid"]]
         [:div {:style {:position "relative" :width 512 :height 480 :cursor "crosshair"}}
          [pixel-canvas {:width 512 :height 480 :scale 1
                         :tick [tick (:bg-table flags) palettes]
                         :paint #(nt/draw-all! % (fn [a] (emu/ppu-peek e a)) (emu/ppu-memory e)
                                               (:bg-table flags) palettes)
                         :on-mouse-move #(swap! view assoc :hover (tile-at %))
                         :on-mouse-leave #(swap! view assoc :hover nil)
                         :on-click #(swap! view assoc :selected (tile-at %))}]
          [overlay v scroll hover selected]]
         [:div {:style {:margin-top "10px" :min-height "80px"}}
          [tile-detail e flags palettes tick]]
         [screen-text e scroll]])
      [:p {:style muted} "Load a ROM to see its background."])))
